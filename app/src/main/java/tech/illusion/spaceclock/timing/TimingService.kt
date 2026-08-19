package tech.illusion.spaceclock.timing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import tech.illusion.spaceclock.platform.LaunchActivity
import tech.illusion.spaceclock.platform.debugLog
import tech.illusion.spaceclock.store.ClockStore
import tech.illusion.spaceclock.store.StopwatchSnapshot

private const val TAG = "TimingService"
private const val CHANNEL_ID = "spaceclock_timing"
private const val NOTIFICATION_ID = 1001

/**
 * 到点告警用**另一个频道**，而不是复用 [CHANNEL_ID]。
 *
 * 硬性原因：频道的 importance 在同一次安装内创建之后改不动（系统只允许用户降级）。
 * [CHANNEL_ID] 是常驻通知，必须 `IMPORTANCE_LOW`（否则每次文案变化都会响一声），复用它会让
 * 「时间到」永远不响。所以新频道 + 新 channel id，两条通知各自独立。
 */
private const val ALERT_CHANNEL_ID = "spaceclock_alerts"

/**
 * 告警通知是**非常驻**的（和前台服务那条 [NOTIFICATION_ID] 无关，`stopForeground` 摘不掉它），
 * 倒计时与闹钟各用一个 id：两者可能同时未确认，共用一个 id 会让后到的把前一条顶掉。
 */
private const val COUNTDOWN_ALERT_ID = 2001
private const val ALARM_ALERT_ID = 2002

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

    /** 未确认的倒计时「时间到」。落盘在 [persistEdges]，重开时在 [onCreate] 里补弹。 */
    private var countdownFinished = false

    /** 上一次真的写进磁盘的三份"未确认/可恢复"状态，用来做边沿触发（见 [persistEdges]）。 */
    private var persistedCountdownAlert = false
    private var persistedPendingAlarmIds: List<Long> = emptyList()
    private var persistedStopwatch: StopwatchSnapshot? = null

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

        // ---- 补弹（用户裁决：未确认的提醒落盘 + 重开时再浮现）----
        // 三份落盘状态在这里回填。**一律不重发通知**：通知是"到点那一刻"的事件，补弹只负责让
        // 界面上的提醒重新出现；重发会让用户在打开应用时又被响一次已经知道的事。
        countdownFinished = store.countdownAlertPending
        persistedCountdownAlert = countdownFinished
        val pendingIds = store.pendingAlarmAlertIds
        // 顺序按落盘的 id 顺序恢复（= 当时的展示顺序）；已被用户删掉的闹钟自然被过滤掉。
        firingQueue = pendingIds.mapNotNull { id -> alarms.find { it.id == id } }
        persistedPendingAlarmIds = firingQueue.map { it.id }
        store.stopwatchSnapshot?.let { snap ->
            stopwatch.seedPaused(snap.elapsedMs, snap.laps)
            persistedStopwatch = snap
        }
        if (persistedPendingAlarmIds != pendingIds) store.pendingAlarmAlertIds = persistedPendingAlarmIds

        createChannel()
        publish()
        _firingAlarm.value = firingQueue.firstOrNull()
        ensureRunState()
        scope.launch { tickLoop() }
        scope.launch { alarmLoop() }
        // 把回填结果打出来：进程被杀后重启时，这一行就是"落盘的今日统计有没有真的读回来"的证据。
        Log.i(
            TAG,
            "created alarms=${alarms.size} enabled=${alarms.count { it.enabled }} " +
                "seededCompletedToday=${pomodoro.completedToday} " +
                "seededFocusedTodayMs=${pomodoro.focusedTodayMs} config=${pomodoro.config} " +
                "RESURFACE countdownFinished=$countdownFinished " +
                "pendingAlarmIds=$persistedPendingAlarmIds (of $pendingIds) " +
                "seededStopwatchElapsedMs=${stopwatch.elapsedMs(SystemClock.elapsedRealtime())} " +
                "seededLaps=${stopwatch.laps.size}",
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
        cancelAlert(COUNTDOWN_ALERT_ID)
    }

    fun startCountdown() = command { countdown.start(it) }
    fun pauseCountdown() = command { countdown.pause(it) }

    /** 复位，同时清掉「时间到」——UI 的「知道了」走的也是这一条。 */
    fun resetCountdown() = command {
        countdown.reset()
        countdownFinished = false
        // 用户已经在界面上确认了，那条系统通知不能继续挂着（否则点开又是一次已知的提醒）。
        cancelAlert(COUNTDOWN_ALERT_ID)
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
        // C1 定论轮次的观测点：卡片显示的是 firingQueue.first()（`ClockVolume` 读的就是
        // `firingAlarm`），这里打出"被 snooze 的 id"和"接上卡片的下一个 id"，让"被显示的那个
        // 闹钟才是被 snooze 的那个"成为可观测事实，而不是代码审查里的推理。
        Log.i(
            TAG,
            "SNOOZE-APPLIED snoozedId=${head.id} until=${snoozeUntil[head.id]} " +
                "queueAfter=${firingQueue.map { it.id }}",
        )
        if (firingQueue.isEmpty()) cancelAlert(ALARM_ALERT_ID)
    }

    fun dismissFiringAlarm() = command {
        val head = firingQueue.firstOrNull() ?: return@command
        firingQueue = firingQueue.drop(1)
        Log.i(TAG, "DISMISS-APPLIED dismissedId=${head.id} queueAfter=${firingQueue.map { it.id }}")
        if (firingQueue.isEmpty()) cancelAlert(ALARM_ALERT_ID)
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
        persistEdges()
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
                // 到点告警：必须发在下面 ensureRunState() 可能的 stopSelf() **之前**——通知一旦
                // post 就独立于服务生命周期存在，晚一步服务就没了。这一帧是唯一的到点边沿
                // （下一 tick 起 countdown.isRunning 已经是 false），所以天然只响一次，
                // 不需要额外闩锁；补弹路径（onCreate）不走这里，也就不会重发。
                postAlert(COUNTDOWN_ALERT_ID, "倒计时结束", "时间到")
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
            // 落盘走 tick 里的边沿触发（Task 6 定的形状）：onPause/onDestroy 会被 force-stop 绕过。
            persistEdges()
            ensureRunState()

            ticks++
            if (ticks % TICK_LOG_EVERY == 0L) {
                // 心跳是周期性仪表（每 5 秒一条，整个运行期不停），走 debugLog 门控：
                // 门禁 B 装的是 debug APK，"服务真的在后台跑"这条判据的取证能力没变。
                debugLog(TAG) {
                    "TICK ticks=$ticks elapsedRealtime=$now foreground=$foreground " +
                        "stopwatch=${stopwatch.isRunning} countdown=${countdown.isRunning} " +
                        "pomodoro=${pomodoro.isRunning} remainingMs=${pomodoro.remainingMs(now)}"
                }
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
                val added = result.newlyFired.filter { it.id !in existingIds }
                firingQueue = firingQueue + added
                Log.i(
                    TAG,
                    "ALARM-FIRED ids=${result.newlyFired.map { it.id }} " +
                        "added=${added.map { it.id }} queue=${firingQueue.map { it.id }} at=$nowCheck",
                )
                // 到点告警（评审 Important 3）：窗口关着的时候闹钟到点，原先只往 firingQueue 里
                // 排一条、常驻通知还写着「闹钟守候中」，用户什么都收不到（比倒计时那个洞更大）。
                // 这里补一条高优先级通知，文案用**本轮真的新到点**的那个闹钟（不是队头——队头可能
                // 是上一轮还没确认的旧提醒）。只在这条边沿发，补弹路径不经过这里，所以不会重发。
                added.firstOrNull()?.let { fired ->
                    postAlert(ALARM_ALERT_ID, "闹钟", "%02d:%02d 到点了".format(fired.hour, fired.minute))
                }
            }
            lastAlarmCheckAt = nowCheck
            _firingAlarm.value = firingQueue.firstOrNull()
            persistEdges()
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
                // 这条日志是"权限被拒时 notify() 到底有没有被调用过"的唯一证据（Task 8 的
                // §5.4 把这条列为未验证路径）。文案只在计时开始/结束时变，不是每 tick 一条。
                Log.i(TAG, "notify ongoing text=$text")
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

    /**
     * 把三份"必须熬过关窗/强杀"的状态按**边沿触发**写盘：先算出这一刻应该是什么值，只在和
     * 上一次真的写进去的值不同时才写一次。
     *
     * 为什么不写在 `onPause` / `onDestroy`：`pico-cli app stop` 是 force-stop 语义，那两个回调
     * 一行都不跑（Task 6 实测）。为什么不每 tick 无条件写：正在跑的秒表每 200ms 都在变，
     * 那样就变成每秒 5 次 SharedPreferences 写。
     */
    private fun persistEdges() {
        if (countdownFinished != persistedCountdownAlert) {
            store.countdownAlertPending = countdownFinished
            persistedCountdownAlert = countdownFinished
            Log.i(TAG, "PERSIST countdownAlertPending=$countdownFinished")
        }

        val pendingIds = firingQueue.map { it.id }
        if (pendingIds != persistedPendingAlarmIds) {
            store.pendingAlarmAlertIds = pendingIds
            persistedPendingAlarmIds = pendingIds
            Log.i(TAG, "PERSIST pendingAlarmAlertIds=$pendingIds")
        }

        // 只落盘"暂停且有内容"的秒表（用户裁决②）：正在跑的秒表由前台服务保证存活，而它的
        // 快照每 tick 都在变；一旦重新开始跑就把磁盘上那份清掉，否则重开会恢复出一份过期数据。
        val snapshot = if (!stopwatch.isRunning) {
            val elapsed = stopwatch.elapsedMs(SystemClock.elapsedRealtime())
            if (elapsed > 0L || stopwatch.laps.isNotEmpty()) {
                StopwatchSnapshot(elapsed, stopwatch.laps.toList())
            } else null
        } else null
        if (snapshot != persistedStopwatch) {
            store.stopwatchSnapshot = snapshot
            persistedStopwatch = snapshot
            Log.i(TAG, "PERSIST stopwatchSnapshot=$snapshot")
        }
    }

    /**
     * 发一条**非常驻**的到点告警。
     *
     * `POST_NOTIFICATIONS` 被拒时系统静默丢弃这条通知（不抛异常），此时兜底的是**补弹**——
     * 未确认的提醒已经落盘，用户下次打开窗口会看到「时间到」/`AlarmCard`。所以被拒不是残缺路径。
     */
    private fun postAlert(id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            this,
            id,
            Intent(this, LaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(id, notification) }
            .onSuccess { Log.i(TAG, "ALERT-POSTED id=$id text=$text") }
            .onFailure { Log.w(TAG, "ALERT-POST-FAILED id=$id ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** 用户在界面上确认之后摘掉对应的告警通知（补弹靠落盘，不靠这条通知留着）。 */
    private fun cancelAlert(id: Int) {
        runCatching { getSystemService(NotificationManager::class.java).cancel(id) }
        Log.i(TAG, "ALERT-CANCELLED id=$id")
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
        val manager = getSystemService(NotificationManager::class.java)
        // 常驻通知：IMPORTANCE_LOW，否则每次文案变化都会响一声。
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "计时", NotificationManager.IMPORTANCE_LOW)
        )
        // 到点告警：另一个频道 + 高重要性。importance 创建后改不动，所以不能复用上面那个。
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "到点提醒", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
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
