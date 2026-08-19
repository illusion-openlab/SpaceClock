package tech.illusion.spaceclock.timing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.pollAlarms
import tech.illusion.spaceclock.store.ClockStore

private const val TAG = "TimingService"
private const val CHANNEL_ID = "spaceclock_timing"
private const val NOTIFICATION_ID = 1001

/** 服务侧 tick 周期。UI 要 0.01 秒精度时按 [TimerState.sampledAtMs] 自己插值，服务不必 60Hz。 */
private const val TICK_MS = 200L

/** 闹钟轮询周期，与迁移前 `ClockVolume` 的 10 秒一致——`pollAlarms` 的窗口口径依赖它。 */
private const val ALARM_POLL_MS = 10_000L

/** 「稍后」= 10 分钟后再响一次，只活在内存里，不跨进程重启。 */
private const val SNOOZE_MINUTES = 10L

/** tick 日志抽样：每 25 个 tick（约 5 秒）一条，用来在锁外核对服务在后台是否真的没断过。 */
private const val TICK_LOG_EVERY = 25L

/**
 * 秒表 / 倒计时状态。字段都是 UI 直接要显示的量，UI 不再持有任何引擎。
 *
 * [sampledAtMs] 是这份快照被采样时的 `SystemClock.elapsedRealtime()`。秒表要 `mm:ss.SS`
 * 的 0.01 秒精度，而服务只有 5Hz——UI 用 `stopwatchElapsedMs + (now - sampledAtMs)` 自己
 * 插值补上中间那段，服务不必为了两位小数把 tick 提到 60Hz。
 */
data class TimerState(
    val stopwatchRunning: Boolean = false,
    val stopwatchElapsedMs: Long = 0L,
    val laps: List<Long> = emptyList(),
    val countdownRunning: Boolean = false,
    val countdownHasDuration: Boolean = false,
    val countdownRemainingMs: Long = 0L,
    /** 倒计时已归零、用户还没点「知道了」。归零判定在服务里做，UI 不在的时候也算得到。 */
    val countdownFinished: Boolean = false,
    val sampledAtMs: Long = 0L,
)

/**
 * 番茄钟状态。[phaseTotalMs] 由引擎提供（[PomodoroEngine.phaseTotalMs]），不再由 UI 自己
 * 复制一份阶段时长换算公式。
 *
 * [remainingMs] / [phaseTotalMs] 的默认值从 [config] 推出来，不写字面量：服务连上之前的
 * 那一两帧会用这份默认状态，默认成 0 会让进度环闪一下 `00:00`。
 */
data class PomodoroState(
    val config: PomodoroConfig = PomodoroConfig(),
    val running: Boolean = false,
    val phase: Phase = Phase.WORK,
    val round: Int = 1,
    val completedToday: Int = 0,
    val focusedTodayMs: Long = 0L,
    val remainingMs: Long = config.workMin * 60_000L,
    val phaseTotalMs: Long = config.workMin * 60_000L,
)

/**
 * 计时状态的唯一真源：秒表 / 倒计时 / 番茄钟三个独立引擎 + 闹钟调度都活在这里，UI 只观察
 * `StateFlow` 并回调命令。
 *
 * **为什么是三个独立引擎**（`AGENTS.md` 的跨任务契约，人工裁决定稿）：一个 `TimerEngine`
 * 只有一对 `accumulatedMs`/`startedAtMs`，装不下「暂停的秒表 + 正在跑的倒计时」两份并存
 * 状态；而 `setCountdown` 按规格必须停表清零，共享实例会让用户在倒计时页调时长时把正在跑的
 * 秒表悄悄清零。不要图省事合并回一个带 `mode` 字段的共享实例。
 *
 * **前台状态**：只在「有任何计时在跑 或 有启用的闹钟」时 `startForeground`，全部空闲时
 * `stopForeground` + `stopSelf()`——一个什么都没在跑的时钟不该常驻一条前台通知。
 *
 * **线程**：作用域固定在主线程（`Dispatchers.Main.immediate`）。命令由 UI 在主线程直接同步
 * 调用，tick 协程也在主线程，两条路径天然串行，三个引擎不需要额外加锁。200ms 一跳的循环对
 * 主线程的负担可以忽略，换来的是不用推理引擎的并发可见性。
 */
class TimingService : Service() {

    inner class LocalBinder : Binder() {
        val service: TimingService get() = this@TimingService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var store: ClockStore
    private val stopwatch = TimerEngine()
    private val countdown = TimerEngine()
    private lateinit var pomodoro: PomodoroEngine

    private var countdownFinished = false

    /** 已经结算过统计的日期。跨天要先归零再落盘，见 [rollOverIfNewDay]。 */
    private var statsDay: LocalDate = LocalDate.now()

    private var alarms: List<Alarm> = emptyList()
    private var snoozeUntil: Map<Long, ZonedDateTime> = emptyMap()
    private var lastAlarmCheckAt: ZonedDateTime = ZonedDateTime.now()

    /** 同一轮轮询可能有多个闹钟到点，卡片一次只盖一个，排队逐个展示而不是丢掉。 */
    private var firingQueue: List<Alarm> = emptyList()

    private val _timerState = MutableStateFlow(TimerState())
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _pomodoroState = MutableStateFlow(PomodoroState())
    val pomodoroState: StateFlow<PomodoroState> = _pomodoroState.asStateFlow()

    private val _firingAlarm = MutableStateFlow<Alarm?>(null)
    val firingAlarm: StateFlow<Alarm?> = _firingAlarm.asStateFlow()

    private var foreground = false
    private var notificationText: String? = null

    override fun onCreate() {
        super.onCreate()
        store = ClockStore(this)
        // 回填今日统计的三步顺序是硬顺序：rollOverIfNewDay 先判跨天（statsDate 初值是 null，
        // 首次调用一定清零），再读持久层，最后 seedStats 回填。顺序反了会静默把磁盘上的
        // 「今日完成」冲掉——seedStats 的 KDoc 明写了这条。
        val today = LocalDate.now()
        pomodoro = PomodoroEngine(store.pomodoroConfig).also { engine ->
            engine.rollOverIfNewDay(today)                              // 1. 先判定跨天
            val (count, focusedMs) = store.loadTodayStats(today)        // 2. 再读持久层
            engine.seedStats(today, count, focusedMs)                   // 3. 最后回填
        }
        statsDay = today
        // 闹钟表自己从持久层读一份，不等 UI 推：服务要在窗口关掉之后也能按时开火。
        alarms = store.alarms
        createChannel()
        publish()
        ensureRunState()
        scope.launch { tickLoop() }
        scope.launch { alarmLoop() }
        // 把回填结果打出来：进程被杀后重启时，这一行就是"落盘的今日统计有没有真的读回来"的证据。
        Log.i(
            TAG,
            "created alarms=${alarms.size} enabled=${alarms.count { it.enabled }} " +
                "seededCompletedToday=${pomodoro.completedToday} " +
                "seededFocusedTodayMs=${pomodoro.focusedTodayMs} config=${pomodoro.config}",
        )
    }

    /** START_NOT_STICKY：被系统杀掉后用空 intent 重启一个什么都没在跑的计时服务没有意义。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // 生命周期清理：两条协程随服务一起结束，否则 tick 会在服务停掉之后继续推进引擎。
        scope.cancel()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    // ---- 命令（UI 主线程同步调用） ----

    fun startStopwatch() = command { stopwatch.start(it) }
    fun pauseStopwatch() = command { stopwatch.pause(it) }
    fun lapStopwatch() = command { stopwatch.lap(it) }
    fun resetStopwatch() = command { stopwatch.reset() }

    fun setCountdown(totalMs: Long) = command {
        countdown.setCountdown(totalMs)     // 规格：改时长会停表清零
        countdownFinished = false
    }

    fun startCountdown() = command { countdown.start(it) }
    fun pauseCountdown() = command { countdown.pause(it) }

    /** 复位，同时清掉「时间到」——UI 的「知道了」走的也是这一条。 */
    fun resetCountdown() = command {
        countdown.reset()
        countdownFinished = false
    }

    fun startPomodoro() = command { pomodoro.start(it) }
    fun pausePomodoro() = command { pomodoro.pause(it) }
    fun skipPomodoro() = command { pomodoro.skip(it) }

    /** 运行中改时长配置由引擎拒绝（[PomodoroEngine.applyConfig]）；被拒时不落盘。 */
    fun setPomodoroConfig(config: PomodoroConfig) = command {
        if (pomodoro.applyConfig(config)) {
            store.pomodoroConfig = config
        } else {
            Log.w(TAG, "pomodoro config change refused while running")
        }
    }

    /** 闹钟表由 UI 拥有并落盘（`AlarmPage` → `ClockVolume`），这里只接一份快照用来轮询。 */
    fun setAlarms(list: List<Alarm>) = command { alarms = list }

    fun snoozeFiringAlarm() = command {
        val head = firingQueue.firstOrNull() ?: return@command
        snoozeUntil = snoozeUntil + (head.id to ZonedDateTime.now().plusMinutes(SNOOZE_MINUTES))
        firingQueue = firingQueue.drop(1)
    }

    fun dismissFiringAlarm() = command {
        if (firingQueue.isEmpty()) return@command
        firingQueue = firingQueue.drop(1)
    }

    /**
     * 所有命令的公共收尾：取一次 now、执行、立刻发布状态、同步前台状态。
     * 立刻发布（而不是等下一个 tick）是为了让按钮点下去的反馈不带 200ms 延迟。
     */
    private inline fun command(body: (nowMs: Long) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        body(now)
        publish(now)
        _firingAlarm.value = firingQueue.firstOrNull()
        ensureRunState()
    }

    // ---- 循环 ----

    private suspend fun tickLoop() {
        var ticks = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            // 本 tick 有没有改过状态。倒计时归零 / 跨天归零这两件事都会让"在跑"变成"不在跑"，
            // 如果只按 anyRunning 决定要不要发布，那一帧的变化就永远发不出去——上机实测过这个
            // bug：倒计时归零后 UI 停在 00:00、按钮还是「暂停」，「时间到」永远不出现。
            var mutated = rollOverIfNewDay()

            if (countdown.isRunning && countdown.isFinished(now)) {
                countdown.pause(now)
                countdownFinished = true
                mutated = true
                Log.i(TAG, "countdown finished elapsedRealtime=$now")
            }

            if (pomodoro.isRunning) {
                val prevCount = pomodoro.completedToday
                val prevMs = pomodoro.focusedTodayMs
                val prevPhase = pomodoro.phase
                pomodoro.advanceTo(now)
                if (pomodoro.phase != prevPhase) {
                    Log.i(
                        TAG,
                        "PHASE-BOUNDARY $prevPhase -> ${pomodoro.phase} round=${pomodoro.round} " +
                            "completedToday=${pomodoro.completedToday} elapsedRealtime=$now",
                    )
                }
                // 边缘触发落盘（Task 6 定下的形状，别改成 onPause/onDestroy 落盘）：
                // 只在真的结算了一个番茄时写一次，force-stop 时那两个回调一行都不跑，
                // 而这里写完就已经在磁盘上了。advanceTo 每 200ms 调一次，但这两个字段
                // 只在跨越 WORK 阶段时变化，不会每 tick 都写 SharedPreferences。
                if (pomodoro.completedToday != prevCount || pomodoro.focusedTodayMs != prevMs) {
                    store.saveTodayStats(LocalDate.now(), pomodoro.completedToday, pomodoro.focusedTodayMs)
                    Log.i(
                        TAG,
                        "STATS-SAVED completedToday=${pomodoro.completedToday} " +
                            "focusedTodayMs=${pomodoro.focusedTodayMs}",
                    )
                }
            }

            // 全空闲且本 tick 没改过任何状态时不发布：TimerState 每次都带新的 sampledAtMs，
            // 照发会让 UI 在什么都没在跑的时候每秒白重组 5 次。
            val anyRunning = stopwatch.isRunning || countdown.isRunning || pomodoro.isRunning
            if (anyRunning || mutated) publish(now)
            ensureRunState()

            ticks++
            if (ticks % TICK_LOG_EVERY == 0L) {
                Log.i(
                    TAG,
                    "TICK ticks=$ticks elapsedRealtime=$now foreground=$foreground " +
                        "stopwatch=${stopwatch.isRunning} countdown=${countdown.isRunning} " +
                        "pomodoro=${pomodoro.isRunning} remainingMs=${pomodoro.remainingMs(now)}",
                )
            }
            delay(TICK_MS)
        }
    }

    private suspend fun alarmLoop() {
        while (true) {
            delay(ALARM_POLL_MS)
            val nowCheck = ZonedDateTime.now()
            val fromCheck = lastAlarmCheckAt
            // 轮询判定本身是纯函数 pollAlarms（alarm/Alarm.kt）：窗口是 (fromCheck, nowCheck]，
            // 所以 lastAlarmCheckAt 必须原样推进到 nowCheck；dueSnoozeIds 同时挡住 nextTriggerAt
            // 分支，保证同一轮里一个闹钟不会从两条路各响一次。这两条性质不能动。
            val result = pollAlarms(alarms, snoozeUntil, fromCheck, nowCheck)
            snoozeUntil = result.updatedSnoozeUntil
            if (result.newlyFired.isNotEmpty()) {
                val existingIds = firingQueue.mapTo(mutableSetOf()) { it.id }
                firingQueue = firingQueue + result.newlyFired.filter { it.id !in existingIds }
                Log.i(TAG, "ALARM-FIRED ids=${result.newlyFired.map { it.id }} at=$nowCheck")
            }
            lastAlarmCheckAt = nowCheck
            _firingAlarm.value = firingQueue.firstOrNull()
        }
    }

    /**
     * 跨天归零。应用可能整夜开着不关，跨天不能只在 onCreate 判一次。
     * 顺序同 onCreate：先 rollOverIfNewDay 清零，再把清零后的值落盘。
     *
     * @return 是否真的跨天了（调用方据此决定要不要发布新状态）。
     */
    private fun rollOverIfNewDay(): Boolean {
        val today = LocalDate.now()
        if (today == statsDay) return false
        statsDay = today
        pomodoro.rollOverIfNewDay(today)
        store.saveTodayStats(today, pomodoro.completedToday, pomodoro.focusedTodayMs)
        Log.i(TAG, "stats rolled over to $today")
        return true
    }

    // ---- 状态发布 / 前台状态 ----

    private fun publish(nowMs: Long = SystemClock.elapsedRealtime()) {
        _timerState.value = TimerState(
            stopwatchRunning = stopwatch.isRunning,
            stopwatchElapsedMs = stopwatch.elapsedMs(nowMs),
            laps = stopwatch.laps.toList(),
            countdownRunning = countdown.isRunning,
            countdownHasDuration = countdown.hasDuration,
            countdownRemainingMs = countdown.remainingMs(nowMs),
            countdownFinished = countdownFinished,
            sampledAtMs = nowMs,
        )
        _pomodoroState.value = PomodoroState(
            config = pomodoro.config,
            running = pomodoro.isRunning,
            phase = pomodoro.phase,
            round = pomodoro.round,
            completedToday = pomodoro.completedToday,
            focusedTodayMs = pomodoro.focusedTodayMs,
            remainingMs = pomodoro.remainingMs(nowMs),
            phaseTotalMs = pomodoro.phaseTotalMs,
        )
    }

    /**
     * 同步前台状态。「有任何计时在跑 或 有启用的闹钟」→ 前台；全空闲 → 摘通知并 `stopSelf()`。
     *
     * 只有命令路径会把服务推上前台，而命令只可能来自前台的 UI，所以永远不会撞上 Android 12+
     * 的「后台不许启动前台服务」限制。tick 循环只可能触发反方向（跑完了变空闲），那个方向没有限制。
     */
    private fun ensureRunState() {
        val active = stopwatch.isRunning || countdown.isRunning || pomodoro.isRunning ||
            alarms.any { it.enabled }
        if (active) {
            val text = notificationSummary()
            if (!foreground) {
                enterStartedState()
                // FGS 起不来也不该拖垮计时：tick 循环与前台状态无关，照跑。
                val ok = runCatching { startForeground(NOTIFICATION_ID, buildNotification(text)) }
                    .onFailure { Log.e(TAG, "startForeground failed ${it.javaClass.name}: ${it.message}", it) }
                    .isSuccess
                foreground = ok
                notificationText = if (ok) text else null
                if (ok) Log.i(TAG, "startForeground OK text=$text")
            } else if (text != notificationText) {
                // 只在文案变化时 notify。通知文案故意不带秒数——带了就得每秒刷一次。
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text))
                notificationText = text
            }
        } else if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            notificationText = null
            Log.i(TAG, "idle: stopForeground + stopSelf")
            // 还有客户端绑着时 stopSelf 不会立刻销毁，只是退出 started 状态；
            // 解绑之后系统才回收。这正是想要的：UI 还在就继续观察，UI 走了就别常驻。
            stopSelf()
        }
    }

    /**
     * 把服务推进 started 状态。只被绑定（never started）的服务在最后一个客户端解绑时就会被销毁，
     * 那样窗口一关计时就断了，前台服务也就白做了。从服务自己调 `startService` 只发生在命令路径
     * （应用在前台），不会撞上后台启动限制；已经 started 时再调一次只是多一次 onStartCommand。
     */
    private fun enterStartedState() {
        runCatching { startService(Intent(this, TimingService::class.java)) }
            .onFailure { Log.w(TAG, "startService(self) refused ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun notificationSummary(): String {
        val parts = buildList {
            if (stopwatch.isRunning) add("秒表")
            if (countdown.isRunning) add("倒计时")
            if (pomodoro.isRunning) add(if (pomodoro.phase == Phase.WORK) "专注" else "休息")
        }
        return if (parts.isEmpty()) "闹钟守候中" else parts.joinToString(" · ") + " 计时中"
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "计时", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SpaceClock")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
