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
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.delay
import tech.illusion.spaceclock.content.pages.AlarmPage
import tech.illusion.spaceclock.content.pages.FocusPage
import tech.illusion.spaceclock.content.pages.HomePage
import tech.illusion.spaceclock.content.pages.TimerPage
import tech.illusion.spaceclock.content.pages.WorldPage
import tech.illusion.spaceclock.content.pages.formatMinSec
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import tech.illusion.spaceclock.time.City
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

    // 世界时钟的城市状态提到这一层，原因不是"看起来更整齐"：`WorldPage` 只在 WORLD 页存在，
    // 页面级 remember 会随切页销毁，而地球的标记点必须在切到其他页之后仍然在（设计文档 §2：
    // 地球是常驻背景层）。Task 6 的 `ClockStore` 会把这三份状态一起接上持久化，这里仍然只放
    // 内存态，不提前建那条管线。
    var selectedCities by remember { mutableStateOf(DEFAULT_SELECTED_CITIES) }
    var homeCityName by remember { mutableStateOf(DEFAULT_HOME_CITY) }
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
    // 状态副行用的时间源，独立于 TimerPage 自己 16ms 一跳的精细循环（那个只在页面可见且
    // 计时器在跑时才存在）。这里只需要 mm:ss 级新鲜度，跟随下面已有的 1 秒轮询一起刷新即可，
    // 不必再起一条协程。TimerPage 的每次引擎变更还会通过 onTimerEngineChanged 立刻把它
    // 刷新一次，不必等到下一次整秒对齐。
    var timerNowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    // 每秒一跳。禁止用 withFrameNanos——它在 WindowContainer 里只触发一次。
    LaunchedEffect(Unit) {
        while (true) {
            val zonedNow = ZonedDateTime.now()
            now = zonedNow.toLocalTime()
            today = zonedNow.toLocalDate()
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
                statusLineFor(page, today, stopwatchEngine, countdownEngine, timerNowMs),
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
                        onAddCity = { selectedCities = selectedCities + it },
                        onSetHome = { homeCityName = it.name },
                        onSelectCity = { focusedCity = it },
                    )
                    ClockPage.ALARM -> AlarmPage()
                    ClockPage.TIMER -> TimerPage(
                        stopwatchEngine = stopwatchEngine,
                        countdownEngine = countdownEngine,
                        onEngineChanged = { timerNowMs = SystemClock.elapsedRealtime() },
                    )
                    ClockPage.FOCUS -> FocusPage()
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
    }
}

/**
 * 状态副行：设计文档 §3 的表。占位会被逐任务替换成真实数据——
 * HOME→Task 2、WORLD→Task 9、ALARM→Task 7、TIMER→Task 5（本任务）、FOCUS→Task 6。
 */
private fun statusLineFor(
    page: ClockPage,
    today: LocalDate,
    stopwatchEngine: TimerEngine,
    countdownEngine: TimerEngine,
    timerNowMs: Long,
): String = when (page) {
    ClockPage.HOME -> homeStatusLine(today)
    ClockPage.WORLD -> "—"
    ClockPage.ALARM -> "—"
    ClockPage.TIMER -> timerStatusLine(stopwatchEngine, countdownEngine, timerNowMs)
    ClockPage.FOCUS -> "—"
}

/**
 * 秒表和倒计时是两个独立引擎，理论上可以同时在跑；两者都在跑时优先报秒表——两个 UI 步骤
 * 描述里秒表排在前面，且秒表更常是"顺手掐一下"的临时用途，被状态行盖掉的代价更高。
 */
private fun timerStatusLine(stopwatchEngine: TimerEngine, countdownEngine: TimerEngine, nowMs: Long): String =
    when {
        stopwatchEngine.isRunning -> "秒表运行中"
        countdownEngine.isRunning -> "倒计时 ${formatMinSec(countdownEngine.remainingMs(nowMs))}"
        else -> "未开始"
    }

/** 星期表按 `dayOfWeek.value`（1=周一..7=周日）取，不用 DateTimeFormatter 的 locale 默认值——设备 locale 不确定。 */
private val WEEKDAY_NAMES_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun homeStatusLine(date: LocalDate): String {
    val weekday = WEEKDAY_NAMES_ZH[date.dayOfWeek.value - 1]
    return "$weekday ${date.monthValue}月${date.dayOfMonth}日"
}

/**
 * 初始展示的城市。跨时区取样，方便门禁 B 同时验证"6 行一屏"和"超过 6 城可滚动"两种状态
 * （面板可视区实测 800px、行高 120px，前 6 行进得去）。约翰内斯堡（5 字）刻意排在第 2 位：
 * 它是列表行长名字截断的现场样本，排在第一屏才验得到。
 */
private val DEFAULT_SELECTED_CITIES: List<City> =
    listOf("北京", "约翰内斯堡", "东京", "伦敦", "纽约", "悉尼", "迪拜", "巴黎", "洛杉矶")
        .mapNotNull { name -> BUILTIN_CITIES.find { it.name == name } }

/** 默认本地城市（Task 3 歧义决议 1）。 */
private const val DEFAULT_HOME_CITY = "北京"
