package tech.illusion.spaceclock.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.nextTriggerAt
import tech.illusion.spaceclock.alarm.pollAlarms
import tech.illusion.spaceclock.content.pages.AlarmPage
import tech.illusion.spaceclock.content.pages.FocusPage
import tech.illusion.spaceclock.content.pages.HomePage
import tech.illusion.spaceclock.content.pages.TimerPage
import tech.illusion.spaceclock.content.pages.WorldPage
import tech.illusion.spaceclock.content.pages.formatMinSec
import tech.illusion.spaceclock.store.ClockStore
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import tech.illusion.spaceclock.time.City
import tech.illusion.spaceclock.timing.Phase
import tech.illusion.spaceclock.timing.PomodoroEngine
import tech.illusion.spaceclock.timing.TimerEngine
import android.os.SystemClock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 三层 C 布局：背景层是全尺寸的地球 `SpatialView`，上面叠一层 2D UI（左栏时钟 40% + 右侧
 * 玻璃面板 56%，间距 4%），底部再叠一条导航按钮。
 */
@Composable
fun ClockVolume() {
    var page by remember { mutableStateOf(ClockPage.HOME) }

    // 持久化入口集中在这一层：WorldPage/FocusPage 都不直接碰 ClockStore，靠 ClockVolume
    // 的回调读写——和秒表/倒计时引擎"状态活在页面切换之上"同一个理由，持久层访问点也不该
    // 散在各页面里。context 用 LocalContext.current（StockQuote WatchlistLocalStore 同款
    // 已验证路径），ClockStore 本身只在第一次组合时 remember 一次，内部是普通 SharedPreferences
    // 读写，不需要跟着 recomposition 重建。
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

    // 秒表 / 倒计时各自一个引擎实例（Task 5），在这一层 remember 而不是 TimerPage 内部：
    // TimerPage 只在 page==TIMER 时才被组合进树，切到其他页会把它连同内部 remember 一起
    // 销毁。状态副行（下面 timerStatusLine）不管当前在看哪一页都要能读到"计时/倒计时是否
    // 在跑"，引擎必须活在页面切换之上——和 selectedCities/focusedCity 同一个理由。
    val stopwatchEngine = remember { TimerEngine() }
    val countdownEngine = remember { TimerEngine() }

    // 番茄钟引擎同样活在页面切换之上，理由和秒表/倒计时一致。回填今日统计的三步顺序是
    // 任务简报锁死的硬顺序：rollOverIfNewDay 先判断跨天（它内部 statsDate 初值是 null，
    // 首次调用一定会清零），再读持久层，最后 seedStats 回填——顺序反了会静默把磁盘上的
    // 「今日完成」冲掉，用户看到的只是数字莫名归零，不会报错。三行故意连在一起写在同一个
    // remember 块里，不拆到不同生命周期回调，避免以后有人在中间插一行读盘操作。
    val pomodoroEngine = remember {
        PomodoroEngine(store.pomodoroConfig).also { engine ->
            val startupToday = LocalDate.now()
            engine.rollOverIfNewDay(startupToday)                                   // 1. 先判定跨天
            val (count, focusedMs) = store.loadTodayStats(startupToday)             // 2. 再读持久层
            engine.seedStats(startupToday, count, focusedMs)                        // 3. 最后回填
        }
    }

    // 状态副行用的时间源，独立于 TimerPage/FocusPage 自己 16ms 一跳的精细循环（那些只在
    // 页面可见且计时器在跑时才存在）。这里只需要 mm:ss 级新鲜度，跟随下面已有的 1 秒轮询
    // 一起刷新即可，不必再起一条协程。TimerPage/FocusPage 的每次引擎变更还会通过
    // onEngineChanged 立刻把它刷新一次，不必等到下一次整秒对齐。
    var timerNowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    // 闹钟列表：和 selectedCityNames 同样的读盘/回写约定，初值直接读 ClockStore，
    // 变更通过 AlarmPage 的 onAlarmsChanged 一次性写回（增删改开关都走这一条路径）。
    var alarms by remember { mutableStateOf(store.alarms) }

    // 到点待展示的闹钟队列：同一轮 10 秒轮询里可能有多个闹钟同时到点，卡片一次只能盖一个，
    // 排队逐个展示而不是丢掉——静默漏报一个闹钟比多等一轮更糟。
    var firingQueue by remember { mutableStateOf<List<Alarm>>(emptyList()) }
    val firingAlarm = firingQueue.firstOrNull()
    // 「稍后」= 记一个 10 分钟后的时刻，只在内存里，不需要跨进程重启存活（任务简报歧义决议 3）。
    var snoozeUntil by remember { mutableStateOf<Map<Long, ZonedDateTime>>(emptyMap()) }
    // 上一次轮询检查到的时刻：nextTriggerAt 的边界是"严格晚于 from"，所以要用 nextTriggerAt(上次检查时刻)
    // 而不是 nextTriggerAt(现在) 来判断"这一轮窗口内是否有闹钟到点"——用 now 查只能看到未来，永远看不到
    // "刚刚已经到点"这一刻。
    var lastAlarmCheckAt by remember { mutableStateOf(ZonedDateTime.now()) }

    // 每 10 秒对所有启用的闹钟扫一遍：Task 8 会把这套调度搬进前台服务，本任务先在 UI 层跑通。
    // Compose 在离开这棵组合树时会自动取消这个协程，不需要额外的 dispose 钩子。
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            val nowCheck = ZonedDateTime.now()
            val fromCheck = lastAlarmCheckAt

            // 轮询判定本身是纯函数 pollAlarms（alarm/Alarm.kt）：给定当前闹钟表 + 稍后表 +
            // 本轮时间窗口，算出该新触发哪些闹钟、稍后表该怎么更新。这里只负责状态落地。
            val result = pollAlarms(alarms, snoozeUntil, fromCheck, nowCheck)
            snoozeUntil = result.updatedSnoozeUntil
            if (result.newlyFired.isNotEmpty()) {
                val existingIds = firingQueue.map { it.id }.toSet()
                firingQueue = firingQueue + result.newlyFired.filter { it.id !in existingIds }
            }
            lastAlarmCheckAt = nowCheck
        }
    }

    // 每秒一跳。禁止用 withFrameNanos——它在 WindowContainer 里只触发一次。
    LaunchedEffect(Unit) {
        while (true) {
            val zonedNow = ZonedDateTime.now()
            val newToday = zonedNow.toLocalDate()
            if (newToday != today) {
                // 应用可能整夜开着不关：跨天不能只在启动时判定一次，这里复用同一套
                // rollOverIfNewDay + saveTodayStats，覆盖"应用一直在前台，午夜自然滚过去"
                // 这条路径。当天内重复调用是无副作用的空操作（statsDate == today 直接返回）。
                pomodoroEngine.rollOverIfNewDay(newToday)
                store.saveTodayStats(newToday, pomodoroEngine.completedToday, pomodoroEngine.focusedTodayMs)
            }
            now = zonedNow.toLocalTime()
            today = newToday
            timerNowMs = SystemClock.elapsedRealtime()
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
                statusLineFor(page, today, alarms, stopwatchEngine, countdownEngine, pomodoroEngine, timerNowMs),
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
                    ClockPage.TIMER -> TimerPage(
                        stopwatchEngine = stopwatchEngine,
                        countdownEngine = countdownEngine,
                        onEngineChanged = { timerNowMs = SystemClock.elapsedRealtime() },
                    )
                    ClockPage.FOCUS -> FocusPage(
                        engine = pomodoroEngine,
                        onConfigChanged = { store.pomodoroConfig = it },
                        onStatsChanged = {
                            store.saveTodayStats(
                                LocalDate.now(),
                                pomodoroEngine.completedToday,
                                pomodoroEngine.focusedTodayMs,
                            )
                            timerNowMs = SystemClock.elapsedRealtime()
                        },
                    )
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
        // 只负责队列的进出：稍后=记 10 分钟后的 snooze 时刻并让下一个排队的闹钟顶上来；
        // 关闭=直接让下一个顶上来。
        firingAlarm?.let { alarm ->
            AlarmCard(
                alarm = alarm,
                weatherLine = null, // Task 9 前恒为 null（任务简报歧义决议 2）
                onSnooze = {
                    snoozeUntil = snoozeUntil + (alarm.id to ZonedDateTime.now().plusMinutes(10))
                    firingQueue = firingQueue.drop(1)
                },
                onDismiss = { firingQueue = firingQueue.drop(1) },
            )
        }
    }
}

/**
 * 状态副行：设计文档 §3 的表。占位会被逐任务替换成真实数据——
 * HOME→Task 2、WORLD→Task 9、ALARM→Task 7、TIMER→Task 5、FOCUS→Task 6（本任务）。
 */
private fun statusLineFor(
    page: ClockPage,
    today: LocalDate,
    alarms: List<Alarm>,
    stopwatchEngine: TimerEngine,
    countdownEngine: TimerEngine,
    pomodoroEngine: PomodoroEngine,
    timerNowMs: Long,
): String = when (page) {
    ClockPage.HOME -> homeStatusLine(today)
    ClockPage.WORLD -> "—"
    ClockPage.ALARM -> alarmStatusLine(alarms)
    ClockPage.TIMER -> timerStatusLine(stopwatchEngine, countdownEngine, timerNowMs)
    ClockPage.FOCUS -> focusStatusLine(pomodoroEngine)
}

/** 「下个闹钟 07:00」；全部关闭或列表为空时显示「无闹钟」。 */
private fun alarmStatusLine(alarms: List<Alarm>): String {
    val now = ZonedDateTime.now()
    val next = alarms.mapNotNull { it.nextTriggerAt(now) }.minOrNull()
    return if (next == null) "无闹钟" else "下个闹钟 %02d:%02d".format(next.hour, next.minute)
}

/**
 * 秒表和倒计时是两个独立 `TimerEngine` 实例（架构裁决保留，见 `AGENTS.md` 跨任务契约），
 * 理论上可以同时在跑——单引擎 + `mode` 字段的设计下这种情况不存在，这条 tie-break 规则本身
 * 就是两引擎架构的直接后果，不是凭空加的分支。两者都在跑时优先报秒表——两个 UI 步骤
 * 描述里秒表排在前面，且秒表更常是"顺手掐一下"的临时用途，被状态行盖掉的代价更高。
 */
private fun timerStatusLine(stopwatchEngine: TimerEngine, countdownEngine: TimerEngine, nowMs: Long): String =
    when {
        stopwatchEngine.isRunning -> "秒表运行中"
        countdownEngine.isRunning -> "倒计时 ${formatMinSec(countdownEngine.remainingMs(nowMs))}"
        else -> "未开始"
    }

/** 未开始（从未 start 过）和"暂停中"共用"未开始"文案——简报只区分运行中/未开始两态。 */
private fun focusStatusLine(engine: PomodoroEngine): String {
    if (!engine.isRunning) return "未开始"
    return when (engine.phase) {
        Phase.WORK -> "专注中 · 第 ${engine.round} 轮"
        Phase.SHORT_BREAK, Phase.LONG_BREAK -> "休息中 · 第 ${engine.round} 轮"
    }
}

/** 星期表按 `dayOfWeek.value`（1=周一..7=周日）取，不用 DateTimeFormatter 的 locale 默认值——设备 locale 不确定。 */
private val WEEKDAY_NAMES_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun homeStatusLine(date: LocalDate): String {
    val weekday = WEEKDAY_NAMES_ZH[date.dayOfWeek.value - 1]
    return "$weekday ${date.monthValue}月${date.dayOfMonth}日"
}

