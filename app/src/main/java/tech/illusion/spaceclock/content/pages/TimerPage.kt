package tech.illusion.spaceclock.content.pages

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay
import tech.illusion.spaceclock.platform.debugLog
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.timing.TimerState
import tech.illusion.spaceclock.timing.TimingService

/** 顶部 `SegmentControl` 的两个标签——纯 UI 选择，与计时状态无关。 */
private enum class TimerTab { STOPWATCH, COUNTDOWN }

/**
 * 「计时」页：秒表 + 倒计时共用一个大数字显示区，顶部 `SegmentControl` 切换。
 *
 * 本页**不持有任何计时状态**（Task 8）：秒表和倒计时的两个独立 `TimerEngine` 实例都活在
 * [TimingService] 里，这里只读 [state] 并把按钮点击转成服务命令。切出应用后计时继续跑，
 * 靠的就是引擎不在 Compose 树里。
 *
 * 直接传 [service]（而不是包一层命令接口）是刻意的：只有一个实现、没有测试替身，多一层接口
 * 只是多一层间接。[service] 可空是绑定本身的性质——连上之前 [state] 是空闲默认值，点击是空操作。
 */
@Composable
fun TimerPage(state: TimerState, service: TimingService?) {
    // 播种要把「未确认的到点」也算进来。只看 countdownRunning 是 Task 8 迁移引入的真回归：
    // 倒计时在用户不在本页时归零（服务照样算得到），回到本页时 countdownRunning 已经是 false，
    // 于是 uiMode 落到 STOPWATCH，而「时间到」只在 COUNTDOWN 分支里渲染——StateFlow 里有
    // countdownFinished=true，界面上零暴露面：用户看到的是 00:00.00 的秒表。
    var uiMode by remember {
        mutableStateOf(
            if (state.countdownRunning || state.countdownFinished) TimerTab.COUNTDOWN
            else TimerTab.STOPWATCH
        )
    }
    // 播种只覆盖"本页重新挂载"。还有两种情况播种碰不到：用户正停在本页的秒表标签上时倒计时
    // 归零；以及本页在服务连上之前就挂载了（那一帧 state 还是空闲默认值）。两种都会让「时间到」
    // 同样零暴露面，所以再补一个"未确认的到点出现时把标签切过去"的副作用。
    // 用户之后自己切回秒表标签时 key 没变，不会被反复拽回来。
    LaunchedEffect(state.countdownFinished) {
        if (state.countdownFinished) uiMode = TimerTab.COUNTDOWN
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        SegmentControl(modifier = Modifier.width(160.dp)) {
            SegmentItem(
                selected = uiMode == TimerTab.STOPWATCH,
                onClick = { uiMode = TimerTab.STOPWATCH },
                title = { Text("秒表", style = PicoTheme.typography.labelMedium) },
            )
            SegmentItem(
                selected = uiMode == TimerTab.COUNTDOWN,
                onClick = { uiMode = TimerTab.COUNTDOWN },
                title = { Text("倒计时", style = PicoTheme.typography.labelMedium) },
            )
        }
        Spacer(Modifier.height(12.dp))
        when (uiMode) {
            TimerTab.STOPWATCH -> StopwatchSection(state, service)
            TimerTab.COUNTDOWN -> CountdownSection(state, service)
        }
    }
}

/** 共用的大数字显示区：两个模式各自调用同一个函数，保证视觉位置/样式一致。 */
@Composable
private fun ColumnScope.BigDigits(text: String, color: Color) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = PicoTheme.typography.displayLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = color,
        )
    }
}

@Composable
private fun StopwatchSection(state: TimerState, service: TimingService?) {
    val running = state.stopwatchRunning
    val laps = state.laps
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }

    // 这条 16ms 循环**不再推进任何状态**——引擎在服务里，这里只是重新读一次单调时钟，用来把
    // 服务 5Hz 的采样插值成 0.01 秒精度的显示（服务不必为了两位小数跑 60Hz）。
    // 只在 running 时循环，暂停后协程立即退出，不空转（全局约束禁止 withFrameNanos）。
    LaunchedEffect(running) {
        debugLog("TimerTick") { "stopwatch interpolation loop enter running=$running" }
        var tickCount = 0
        while (running) {
            tickCount++
            nowMs = SystemClock.elapsedRealtime()
            if (tickCount % 30 == 0) {
                debugLog("TimerTick") { "stopwatch tick count=$tickCount nowMs=$nowMs" }
            }
            delay(16L)
        }
        debugLog("TimerTick") { "stopwatch interpolation loop exit tickCount=$tickCount" }
    }

    // 插值：采样值 + 采样之后又过去的那一段。coerceAtLeast(0) 挡住"服务刚发布新采样、本地
    // nowMs 还没跳"的那 16ms 窗口，否则显示会倒退一下。
    val elapsed = state.stopwatchElapsedMs +
        if (running) (nowMs - state.sampledAtMs).coerceAtLeast(0L) else 0L

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigDigits(formatStopwatchClock(elapsed), PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (running) service?.pauseStopwatch() else service?.startStopwatch() },
            ) { Text(if (running) "暂停" else "开始", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = { service?.lapStopwatch() },
                enabled = running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PicoTheme.colorScheme.fillTertiary,
                    contentColor = PicoTheme.colorScheme.labelPrimary,
                ),
            ) { Text("计次", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = { service?.resetStopwatch() },
                enabled = !running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PicoTheme.colorScheme.fillTertiary,
                    contentColor = PicoTheme.colorScheme.labelPrimary,
                ),
            ) { Text("复位", style = PicoTheme.typography.labelLarge) }
        }
        Spacer(Modifier.height(12.dp))
        // 最新的在最上：按原始顺序编号（第 1/2/3…段），再整体倒序显示。
        val displayLaps = remember(laps) { laps.mapIndexed { i, ms -> (i + 1) to ms }.asReversed() }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(items = displayLaps, key = { it.first }) { (index, ms) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "第 $index 段",
                        style = PicoTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = PicoTheme.colorScheme.labelSecondary,
                    )
                    Text(
                        formatStopwatchClock(ms),
                        style = PicoTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = PicoTheme.colorScheme.labelPrimary,
                    )
                }
                HorizontalDivider(color = PicoTheme.colorScheme.dividerLine)
            }
        }
    }
}

/** 预设时长（分钟）。设计要求：1/3/5/10/30 分 + 自定义。 */
private val COUNTDOWN_PRESETS_MIN = listOf(1, 3, 5, 10, 30)
private const val CUSTOM_MIN_MINUTES = 1
private const val CUSTOM_MAX_MINUTES = 180

@Composable
private fun CountdownSection(state: TimerState, service: TimingService?) {
    val running = state.countdownRunning
    // 还没选过时长（countdownHasDuration 还是 false）时，大数字区用这个预览将要设的时长；
    // 一旦调过预设/自定义，displayMs 就直接读服务发布的剩余时间，这个值不再参与显示。
    // 这是纯 UI 的候选值，不是计时状态，所以留在本页 remember 里。
    var pendingMinutes by remember { mutableStateOf(5) }

    // 归零判定搬进了服务（`TimingService.tickLoop`）：应用切出去、倒计时在后台归零，回来时
    // 「时间到」照样在。本页不再需要一条 16ms 循环——倒计时是 mm:ss 精度，服务 5Hz 够了。
    if (state.countdownFinished) {
        CountdownFinished(onAcknowledge = { service?.resetCountdown() })
        return
    }

    val hasDuration = state.countdownHasDuration
    val displayMs = if (hasDuration) state.countdownRemainingMs else pendingMinutes * 60_000L

    fun applyMinutes(minutes: Int) {
        pendingMinutes = minutes
        service?.setCountdown(minutes * 60_000L)    // 服务侧的 setCountdown 会停表清零
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigDigits(formatMinSec(displayMs), PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(12.dp))
        if (!running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COUNTDOWN_PRESETS_MIN.forEach { minutes ->
                    Button(
                        onClick = { applyMinutes(minutes) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PicoTheme.colorScheme.fillTertiary,
                            contentColor = PicoTheme.colorScheme.labelPrimary,
                        ),
                    ) {
                        Text(
                            "$minutes 分",
                            style = PicoTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { applyMinutes((pendingMinutes - 1).coerceIn(CUSTOM_MIN_MINUTES, CUSTOM_MAX_MINUTES)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillTertiary,
                        contentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                ) { Text("－", style = PicoTheme.typography.labelLarge) }
                Text(
                    "$pendingMinutes 分（自定义）",
                    style = PicoTheme.typography.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = PicoTheme.colorScheme.labelSecondary,
                )
                Button(
                    onClick = { applyMinutes((pendingMinutes + 1).coerceIn(CUSTOM_MIN_MINUTES, CUSTOM_MAX_MINUTES)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillTertiary,
                        contentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                ) { Text("＋", style = PicoTheme.typography.labelLarge) }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (running) service?.pauseCountdown() else service?.startCountdown() },
                enabled = hasDuration,
            ) { Text(if (running) "暂停" else "开始", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = { service?.resetCountdown() },
                enabled = !running && hasDuration,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PicoTheme.colorScheme.fillTertiary,
                    contentColor = PicoTheme.colorScheme.labelPrimary,
                ),
            ) { Text("复位", style = PicoTheme.typography.labelLarge) }
        }
    }
}

/**
 * 倒计时归零反馈：大数字区换成「时间到」+ alert 高亮，tick 循环已经在调用处停掉，这里只负责
 * 展示态和「知道了」复位。不复用 Task 7 的 `AlarmCard`（那是闹钟专用的全屏遮罩，会打断用户
 * 正在看的其他页——这里只是玻璃面板内部换一屏内容，不盖住整个体积窗口）。
 */
@Composable
private fun CountdownFinished(onAcknowledge: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "时间到",
                style = PicoTheme.typography.displayLarge,
                color = PicoTheme.colorScheme.alert,
            )
        }
        Button(
            onClick = onAcknowledge,
            colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.alert),
        ) { Text("知道了", style = PicoTheme.typography.labelLarge) }
        Spacer(Modifier.height(12.dp))
    }
}

/** `mm:ss.SS`——秒表要求的 0.01 秒精度。 */
internal fun formatStopwatchClock(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val totalCentis = clamped / 10
    val centis = totalCentis % 100
    val totalSeconds = totalCentis / 100
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return "%02d:%02d.%02d".format(minutes, seconds, centis)
}

/** `mm:ss`——倒计时精度，以及 `ClockVolume` 状态副行复用同一份格式化。 */
internal fun formatMinSec(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return "%02d:%02d".format(minutes, seconds)
}
