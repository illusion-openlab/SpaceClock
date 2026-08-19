package tech.illusion.spaceclock.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.nextTriggerAt
import tech.illusion.spaceclock.content.pages.AlarmPage
import tech.illusion.spaceclock.content.pages.FocusPage
import tech.illusion.spaceclock.content.pages.HomePage
import tech.illusion.spaceclock.content.pages.TimerPage
import tech.illusion.spaceclock.content.pages.WorldPage
import tech.illusion.spaceclock.content.pages.formatMinSec
import tech.illusion.spaceclock.platform.rememberNotificationPermission
import tech.illusion.spaceclock.store.ClockStore
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import tech.illusion.spaceclock.time.City
import tech.illusion.spaceclock.timing.Phase
import tech.illusion.spaceclock.timing.PomodoroState
import tech.illusion.spaceclock.timing.TimerState
import tech.illusion.spaceclock.timing.TimingService
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

private const val TAG = "ClockVolume"

/**
 * 三层 C 布局：背景层是全尺寸的地球 `SpatialView`，上面叠一层 2D UI（左栏时钟 40% + 右侧
 * 玻璃面板 56%，间距 4%），底部再叠一条导航按钮。
 */
@Composable
fun ClockVolume() {
    var page by remember { mutableStateOf(ClockPage.HOME) }

    // UI 侧的持久化入口集中在这一层：各页面都不直接碰 ClockStore，靠 ClockVolume 的回调读写，
    // 持久层访问点不散在各页面里。context 用 LocalContext.current（StockQuote
    // WatchlistLocalStore 同款已验证路径），ClockStore 本身只在第一次组合时 remember 一次，
    // 内部是普通 SharedPreferences 读写，不需要跟着 recomposition 重建。
    //
    // 例外（Task 8）：番茄钟的时长配置与今日统计由 `TimingService` 自己读写。那两份数据的写入
    // 时刻（阶段跃迁）可能发生在窗口不可见时，放在 UI 层就等于"只有正在看那一页时才算"。
    val context = LocalContext.current
    val store = remember { ClockStore(context) }

    // 世界时钟的城市状态提到这一层，原因不是"看起来更整齐"：`WorldPage` 只在 WORLD 页存在，
    // 页面级 remember 会随切页销毁，而地球的标记点必须在切到其他页之后仍然在（设计文档 §2：
    // 地球是常驻背景层）。城市名列表和本地城市现在都来自 `ClockStore`（Task 6），初值直接读
    // 持久层；后续变更走下面 onAddCity/onSetHome 的回调，既更新这份内存态触发重组，也立刻写盘。
    var selectedCityNames by remember { mutableStateOf(store.selectedCityNames) }
    var homeCityName by remember { mutableStateOf(store.homeCityName) }
    val selectedCities = remember(selectedCityNames) {
        selectedCityNames.mapNotNull { name -> BUILTIN_CITIES.find { it.name == name } }
    }
    // 点选中的城市：驱动地球把它转到正面，同时给列表行做高亮。
    var focusedCity by remember { mutableStateOf<City?>(null) }
    // 墙上时间显示用 ZonedDateTime.now()（Global Constraints）：时区在取值源头就显式化，
    // 避免 Task 3 世界时钟 / Task 9 状态行继承隐式默认时区的写法。LeftColumn 的参数类型仍是
    // LocalTime——只换取值来源，不换接口，行为不变（两者都经系统默认时区，精确到秒一致）。
    var now by remember { mutableStateOf(ZonedDateTime.now().toLocalTime()) }
    // HOME 状态副行的日期跟着同一个墙上时钟源；单独 remember 一份 LocalDate 而不是每次从
    // now 反推，避免午夜跨天时状态行卡在旧日期（LocalTime 本身不带日期信息）。
    var today by remember { mutableStateOf(ZonedDateTime.now().toLocalDate()) }

    // 通知权限：POST_NOTIFICATIONS 在 Android 13+ 是运行时权限，只在 manifest 声明拿不到。
    // 被拒不影响任何功能——前台服务照起，闹钟照弹窗口内的 AlarmCard，只是那条通知不显示，
    // 未确认的提醒改由服务侧的补弹兜底（落盘 + 重开时再浮现）。只问一次的标记落在 ClockStore 里
    // （跨进程持久）——重开窗口会重挂载这棵树，LaunchedEffect(Unit) 挡不住第二次请求。
    // 返回值没有别的消费者，落一条日志把这条路径的实际结果留在设备证据里。
    val notificationsGranted = rememberNotificationPermission(store)
    LaunchedEffect(notificationsGranted) {
        Log.i(TAG, "POST_NOTIFICATIONS granted=$notificationsGranted (拒绝也不影响计时与闹钟)")
    }

    // 三个计时引擎 + 闹钟调度都活在前台服务里（Task 8）：唯一真源在服务侧，UI 只观察
    // StateFlow 并回调命令。UI 侧一行计时状态都不留——引擎放在 Compose 里的时候，切出应用
    // 后计时就停了，而 remember 的生命周期本来也装不住"窗口关掉之后还要继续跑"这件事。
    var service by remember { mutableStateOf<TimingService?>(null) }
    DisposableEffect(context) {
        val intent = Intent(context, TimingService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as TimingService.LocalBinder).service
                Log.i(TAG, "timing service connected")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                Log.i(TAG, "timing service disconnected")
            }
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            // 绑定不解绑会泄漏（Global Constraints 的生命周期义务）。注意 `pico-cli app stop`
            // 是 force-stop，这条路径一行都不跑——要验证它得用 KEYCODE_BACK 优雅关闭。
            service = null
            context.unbindService(connection)
            Log.i(TAG, "timing service unbound")
        }
    }

    // 服务连上前的那一两帧用一份空闲默认状态，避免在组合里条件调用 collectAsState。
    val idleTimerState = remember { MutableStateFlow(TimerState()) }
    val idlePomodoroState = remember { MutableStateFlow(PomodoroState()) }
    val idleFiringAlarm = remember { MutableStateFlow<Alarm?>(null) }
    val timerState by (service?.timerState ?: idleTimerState).collectAsState()
    val pomodoroState by (service?.pomodoroState ?: idlePomodoroState).collectAsState()
    val firingAlarm by (service?.firingAlarm ?: idleFiringAlarm).collectAsState()

    // 闹钟列表：和 selectedCityNames 同样的读盘/回写约定，初值直接读 ClockStore，
    // 变更通过 AlarmPage 的 onAlarmsChanged 一次性写回（增删改开关都走这一条路径）。
    // 轮询与开火判定在服务里，这里只把最新快照推过去。
    var alarms by remember { mutableStateOf(store.alarms) }
    LaunchedEffect(service, alarms) { service?.setAlarms(alarms) }

    // 每秒一跳，只驱动墙上时间显示。禁止用 withFrameNanos——它在 WindowContainer 里只触发一次。
    // 番茄钟的跨天归零已经搬进服务（那件事在窗口关掉之后也要发生，留在 UI 里不成立）。
    LaunchedEffect(Unit) {
        while (true) {
            val zonedNow = ZonedDateTime.now()
            now = zonedNow.toLocalTime()
            today = zonedNow.toLocalDate()
            delay(1000L - System.currentTimeMillis() % 1000L) // 对齐到整秒，避免跳秒抖动
        }
    }

    // 门禁 B 的裁切类判定项（按钮条不被裁、左右不重叠）不靠模拟器截图——那台相机固定窄视角，
    // 一帧最多装下 2/5 个按钮，看不到全貌。改用真实测得的坐标：记录根容器与三个子层的宽度/
    // 位置，事后用数字核对右边缘是否越过容器宽度。留在代码里成本很低，Task 3/9 等后续任务
    // 改这块布局时也用得上，所以不做完就摘掉。
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { c ->
            Log.i("ClockLayout", "root w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
        }
    ) {
        EarthView(cities = selectedCities, focusCity = focusedCity, modifier = Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            LeftColumn(
                now,
                statusLineFor(page, today, alarms, timerState, pomodoroState),
                Modifier.weight(0.40f).fillMaxHeight().onGloballyPositioned { c ->
                    Log.i("ClockLayout", "leftColumn w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
                },
            )
            Spacer(Modifier.weight(0.04f))
            Box(
                Modifier.weight(0.56f).fillMaxHeight()
                    .backgroundMaterial(enable = true, style = Material.Thin)
                    .clip(RoundedCornerShape(16.dp))
                    .onGloballyPositioned { c ->
                        Log.i("ClockLayout", "glassPanel w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
                    }
            ) {
                when (page) {
                    // weather 恒传 null——Task 9 接入 WeatherRepository 前 UI 只看到占位。
                    // cityName 同理是占位值，Task 9 换成真实定位结果，签名不变。
                    ClockPage.HOME -> HomePage(weather = null, cityName = "本地")
                    ClockPage.WORLD -> WorldPage(
                        cities = selectedCities,
                        homeCityName = homeCityName,
                        focusedCityName = focusedCity?.name,
                        onAddCity = { city ->
                            val updated = selectedCityNames + city.name
                            store.selectedCityNames = updated
                            selectedCityNames = updated
                        },
                        onSetHome = { city ->
                            store.homeCityName = city.name
                            homeCityName = city.name
                        },
                        onSelectCity = { focusedCity = it },
                    )
                    ClockPage.ALARM -> AlarmPage(
                        alarms = alarms,
                        onAlarmsChanged = { updated ->
                            store.alarms = updated
                            alarms = updated
                        },
                    )
                    ClockPage.TIMER -> TimerPage(state = timerState, service = service)
                    ClockPage.FOCUS -> FocusPage(state = pomodoroState, service = service)
                }
            }
        }
        NavBar(
            page,
            { page = it },
            Modifier.align(Alignment.BottomCenter).padding(20.dp).onGloballyPositioned { c ->
                Log.i("ClockLayout", "navBar w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
            },
        )

        // 提醒卡片盖在最上层——必须在 NavBar 之后添加才能真正盖住它（Compose 里同一个 Box
        // 的子节点后添加的画在上面）。遮罩自己吞掉指针事件（AlarmCard 内部实现），这里
        // 只负责把用户的选择转发给服务：稍后=记 10 分钟后的 snooze 时刻并让下一个排队的闹钟
        // 顶上来；关闭=直接让下一个顶上来。队列本身在服务里，UI 不在时也照样排。
        firingAlarm?.let { alarm ->
            AlarmCard(
                alarm = alarm,
                weatherLine = null, // Task 9 前恒为 null（任务简报歧义决议 2）
                onSnooze = { service?.snoozeFiringAlarm() },
                onDismiss = { service?.dismissFiringAlarm() },
            )
        }
    }
}

/**
 * 状态副行：设计文档 §3 的表。占位会被逐任务替换成真实数据——
 * HOME→Task 2、WORLD→Task 9（仍是占位）、ALARM→Task 7、TIMER→Task 5、FOCUS→Task 6。
 * 计时/番茄钟两行的数据源在 Task 8 从引擎实例换成了服务发布的状态快照，文案口径不变。
 */
private fun statusLineFor(
    page: ClockPage,
    today: LocalDate,
    alarms: List<Alarm>,
    timerState: TimerState,
    pomodoroState: PomodoroState,
): String = when (page) {
    ClockPage.HOME -> homeStatusLine(today)
    ClockPage.WORLD -> "—"
    ClockPage.ALARM -> alarmStatusLine(alarms)
    ClockPage.TIMER -> timerStatusLine(timerState)
    ClockPage.FOCUS -> focusStatusLine(pomodoroState)
}

/** 「下个闹钟 07:00」；全部关闭或列表为空时显示「无闹钟」。 */
private fun alarmStatusLine(alarms: List<Alarm>): String {
    val now = ZonedDateTime.now()
    val next = alarms.mapNotNull { it.nextTriggerAt(now) }.minOrNull()
    return if (next == null) "无闹钟" else "下个闹钟 %02d:%02d".format(next.hour, next.minute)
}

/**
 * 秒表和倒计时是两个独立 `TimerEngine` 实例（架构裁决保留，见 `AGENTS.md` 跨任务契约；
 * Task 8 起活在 `TimingService` 里），理论上可以同时在跑——单引擎 + `mode` 字段的设计下这种情况不存在，这条 tie-break 规则本身
 * 就是两引擎架构的直接后果，不是凭空加的分支。两者都在跑时优先报秒表——两个 UI 步骤
 * 描述里秒表排在前面，且秒表更常是"顺手掐一下"的临时用途，被状态行盖掉的代价更高。
 */
private fun timerStatusLine(state: TimerState): String =
    when {
        state.stopwatchRunning -> "秒表运行中"
        state.countdownRunning -> "倒计时 ${formatMinSec(state.countdownRemainingMs)}"
        else -> "未开始"
    }

/** 未开始（从未 start 过）和"暂停中"共用"未开始"文案——简报只区分运行中/未开始两态。 */
private fun focusStatusLine(state: PomodoroState): String {
    if (!state.running) return "未开始"
    return when (state.phase) {
        Phase.WORK -> "专注中 · 第 ${state.round} 轮"
        Phase.SHORT_BREAK, Phase.LONG_BREAK -> "休息中 · 第 ${state.round} 轮"
    }
}

/** 星期表按 `dayOfWeek.value`（1=周一..7=周日）取，不用 DateTimeFormatter 的 locale 默认值——设备 locale 不确定。 */
private val WEEKDAY_NAMES_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun homeStatusLine(date: LocalDate): String {
    val weekday = WEEKDAY_NAMES_ZH[date.dayOfWeek.value - 1]
    return "$weekday ${date.monthValue}月${date.dayOfMonth}日"
}

