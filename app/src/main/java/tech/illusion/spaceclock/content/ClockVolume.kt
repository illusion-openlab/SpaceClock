package tech.illusion.spaceclock.content

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
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
import tech.illusion.spaceclock.platform.debugLog
import tech.illusion.spaceclock.platform.rememberNotificationPermission
import tech.illusion.spaceclock.store.ClockStore
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import tech.illusion.spaceclock.time.City
import tech.illusion.spaceclock.timing.Phase
import tech.illusion.spaceclock.timing.PomodoroState
import tech.illusion.spaceclock.timing.TimerState
import tech.illusion.spaceclock.timing.TimingService
import tech.illusion.spaceclock.weather.WeatherRepository
import tech.illusion.spaceclock.weather.WeatherSnapshot
import tech.illusion.spaceclock.weather.WmoCodes
import tech.illusion.spaceclock.weather.alarmLine
import tech.illusion.spaceclock.weather.icon
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

    // 天气（Task 9）：仓库只在这一层持有一份实例（和 ClockStore 同样的"持久化/外部 IO 入口集中"
    // 理由），四个消费点（HomePage/WorldPage/AlarmCard/状态副行）都读同一份 weatherByCity 快照，
    // 不各自发起请求。本地城市保证已经在 selectedCities 里——"设为本地"只出现在已经显示的行上
    // （WorldClockRow 的 onSetHome），所以不需要为 homeCityName 单独发一次额外请求。
    val weatherRepository = remember { WeatherRepository(context) }
    var weatherByCity by remember { mutableStateOf<Map<String, WeatherSnapshot?>>(emptyMap()) }
    LaunchedEffect(selectedCities) {
        while (true) {
            weatherByCity = weatherRepository.snapshotAll(selectedCities)
            // 仓库内部按 WEATHER_FRESH_WINDOW_MS（30 分钟）做新鲜度判断，命中缓存时不会真的发
            // 网络请求——这里的刷新间隔只是"隔多久检查一次是否该刷新"，不是"隔多久打一次网络"。
            delay(5 * 60_000L)
        }
    }
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

    // 门禁 B 的裁切类判定项（按钮条不被裁、左右不重叠、**内容面板与按钮条不垂直重叠**）不靠
    // 模拟器截图——那台相机固定窄视角，一帧最多装下 2/5 个按钮，看不到全貌。改用真实测得的
    // 坐标：记录根容器与各子层的宽度/位置，事后用数字核对边缘关系。留在代码里成本很低
    // （debug-only，见 layoutLog），Task 3/9 等后续任务改这块布局时也用得上。
    //
    // 根容器要自己铺底色：设计文档 §5.4.1 把 `#080E17`（= fillSecondary）定义为「体积窗口
    // 底色」，而 manifest 里 `windowcontainer.materialbackground=0` 刻意关掉了系统那整块
    // 玻璃底（"会糊掉体积感"）。两件事加起来 = 除了应用自己画，没有别的东西会画这个底色。
    // 不铺的话，右侧面板那层 `Material.Thin` 薄玻璃透出来的就不是深空蓝，而是玻璃背后的
    // 3D 视图/透视画面（Task 10 issue ②）。
    Box(
        Modifier.fillMaxSize()
            .background(PicoTheme.colorScheme.fillSecondary)
            .onGloballyPositioned { c ->
                debugLog("ClockLayout") { "root w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}" }
            }
    ) {
        EarthView(cities = selectedCities, focusCity = focusedCity, modifier = Modifier.fillMaxSize())
        // 内容区与按钮条上下分区（Task 10 Step 2''）。原先 NavBar 用 `align(BottomCenter)`
        // 浮在 `Row` 之上，而两栏都 `fillMaxHeight()` 一直延伸到窗口底边——实测（AGENTS.md
        // 有坐标）面板底边 y=1085、按钮条顶边 y=989，**整条 96px 按钮条压在面板上面**，
        // 贴底的全宽按钮（AlarmPage 的「＋新建闹钟」/「保存」、WorldPage 的「＋添加城市」）
        // 有一部分点不到。
        //
        // 修法用 Column 分区而不是给面板加一个写死的 bottom padding：按钮条的高度由
        // SpatialUI Button 的固有尺寸决定（实测 96px = 48dp），写死就会在组件改版时静默失效。
        // 这也回到了设计稿的原样：`layout-v2.html` C 方案里面板是 `top:9%; bottom:19%`、
        // 按钮条 `bottom:5%; height:9%`，面板本来就不贴窗口底边，两者之间留 5%（≈ 外边距同宽）
        // 的间隙。所以间隙直接复用外边距的 20.dp，不新造常量。
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                LeftColumn(
                    now,
                    statusLineFor(page, today, alarms, timerState, pomodoroState, homeCityName, weatherByCity[homeCityName]),
                    Modifier.weight(0.40f).fillMaxHeight().onGloballyPositioned { c ->
                        debugLog("ClockLayout") { "leftColumn w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}" }
                    },
                )
                Spacer(Modifier.weight(0.04f))
                Box(
                    // modifier 顺序保持原样（`backgroundMaterial` 在前、`clip` 在后）。
                    // Task 10 上机 A/B 对照过"把 clip 挪到前面"这个假设：同一相机位姿下修复前后
                    // 面板四角的圆角**半径与弧线完全一致**（证据
                    // `task10-a1-control-home.png` / `task10-b1-fixed-home.png` 的角部裁剪），
                    // 说明这一层玻璃本来就吃到了 16dp 圆角，顺序假设被证伪 —— 所以不改它。
                    Modifier.weight(0.56f).fillMaxHeight()
                        .backgroundMaterial(enable = true, style = Material.Thin)
                        .clip(RoundedCornerShape(16.dp))
                        .onGloballyPositioned { c ->
                            debugLog("ClockLayout") { "glassPanel w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}" }
                        }
                ) {
                    when (page) {
                        ClockPage.HOME -> HomePage(
                            weather = weatherByCity[homeCityName],
                            cityName = homeCityName,
                        )
                        ClockPage.WORLD -> WorldPage(
                            cities = selectedCities,
                            homeCityName = homeCityName,
                            focusedCityName = focusedCity?.name,
                            weatherByCity = weatherByCity,
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
            Spacer(Modifier.height(20.dp))
            NavBar(
                page,
                { page = it },
                Modifier.fillMaxWidth().onGloballyPositioned { c ->
                    debugLog("ClockLayout") { "navBar w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}" }
                },
            )
        }

        // 提醒卡片盖在最上层——必须在装内容与按钮条的那个 Column **之后**添加才能真正盖住
        // 它们（Compose 里同一个 Box 的子节点后添加的画在上面）。遮罩自己吞掉指针事件（AlarmCard 内部实现），这里
        // 只负责把用户的选择转发给服务：稍后=记 10 分钟后的 snooze 时刻并让下一个排队的闹钟
        // 顶上来；关闭=直接让下一个顶上来。队列本身在服务里，UI 不在时也照样排。
        firingAlarm?.let { alarm ->
            AlarmCard(
                alarm = alarm,
                // 提醒卡片显示"本地城市"的天气——闹钟到点关心的是用户自己在哪儿，跟世界时钟
                // 列表里其他城市的天气无关。weatherByCity[homeCityName] 为 null（拉取失败且无
                // 缓存）时 alarmLine() 整个调不到，AlarmCard 内部 weatherLine?.let{} 会让那一行
                // 直接不显示，不留空字符串占位。
                weatherLine = weatherByCity[homeCityName]?.alarmLine(),
                onSnooze = { service?.snoozeFiringAlarm() },
                onDismiss = { service?.dismissFiringAlarm() },
            )
        }
    }
}

/**
 * 状态副行：设计文档 §3 的表。占位会被逐任务替换成真实数据——
 * HOME→Task 2、WORLD→Task 9、ALARM→Task 7、TIMER→Task 5、FOCUS→Task 6。
 * 计时/番茄钟两行的数据源在 Task 8 从引擎实例换成了服务发布的状态快照，文案口径不变。
 */
private fun statusLineFor(
    page: ClockPage,
    today: LocalDate,
    alarms: List<Alarm>,
    timerState: TimerState,
    pomodoroState: PomodoroState,
    homeCityName: String,
    homeWeather: WeatherSnapshot?,
): String = when (page) {
    ClockPage.HOME -> homeStatusLine(today)
    ClockPage.WORLD -> worldStatusLine(homeCityName, homeWeather)
    ClockPage.ALARM -> alarmStatusLine(alarms)
    ClockPage.TIMER -> timerStatusLine(timerState)
    ClockPage.FOCUS -> focusStatusLine(pomodoroState)
}

/**
 * WORLD 页状态副行：本地城市 + 天气一句话，「北京 · ☀ 26°」（设计文档 §3 状态副行表，
 * brief Step 5 item 4 逐字给定的格式）。无天气数据（拉取失败且无缓存）时**退回城市名本身**，
 * 不是 "—" 占位——brief 原文写的是「无数据时退回『北京』」，城市名本身永远有效，不需要占位符。
 */
private fun worldStatusLine(homeCityName: String, homeWeather: WeatherSnapshot?): String =
    homeWeather?.let {
        val icon = WmoCodes.describe(it.wmoCode).kind.icon()
        "$homeCityName · $icon ${it.tempC}°"
    } ?: homeCityName

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

