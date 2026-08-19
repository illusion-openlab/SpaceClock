package tech.illusion.spaceclock.content.pages

import android.os.SystemClock
import android.util.Log
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
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.timing.TimerEngine
import tech.illusion.spaceclock.timing.TimerMode

/**
 * 「计时」页：秒表 + 倒计时共用一个大数字显示区，顶部 `SegmentControl` 切换。
 *
 * 两个模式各自一个 [TimerEngine] 实例，不是共享一个——`setCountdown` 会停下并清零计时
 * （引擎自身的设计决定，见 `TimerEngine` 文档），如果秒表和倒计时公用同一个引擎，用户在
 * 倒计时页调时长会把正在跑/暂停的秒表悄悄清零，这是一个真实的数据丢失场景，不是"多一个对象
 * 更整齐"的偏好问题。两个引擎实例都由 [ClockVolume] 持有并 remember，切页不会重置——
 * 状态副行（Step 6）要在离开本页后仍然读到它们的运行状态。
 *
 * [onEngineChanged] 在任何引擎变更（start/pause/lap/reset/setCountdown）后立即调用，
 * 驱动 `ClockVolume` 立刻刷新状态副行，不必等它自己的 1Hz 轮询。
 */
@Composable
fun TimerPage(
    stopwatchEngine: TimerEngine,
    countdownEngine: TimerEngine,
    onEngineChanged: () -> Unit,
) {
    var uiMode by remember {
        mutableStateOf(if (countdownEngine.isRunning) TimerMode.COUNTDOWN else TimerMode.STOPWATCH)
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        SegmentControl(modifier = Modifier.width(160.dp)) {
            SegmentItem(
                selected = uiMode == TimerMode.STOPWATCH,
                onClick = { uiMode = TimerMode.STOPWATCH },
                title = { Text("秒表", style = PicoTheme.typography.labelMedium) },
            )
            SegmentItem(
                selected = uiMode == TimerMode.COUNTDOWN,
                onClick = { uiMode = TimerMode.COUNTDOWN },
                title = { Text("倒计时", style = PicoTheme.typography.labelMedium) },
            )
        }
        Spacer(Modifier.height(12.dp))
        when (uiMode) {
            TimerMode.STOPWATCH -> StopwatchSection(stopwatchEngine, onEngineChanged)
            TimerMode.COUNTDOWN -> CountdownSection(countdownEngine, onEngineChanged)
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
private fun StopwatchSection(engine: TimerEngine, onEngineChanged: () -> Unit) {
    var running by remember { mutableStateOf(engine.isRunning) }
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var laps by remember { mutableStateOf(engine.laps.toList()) }

    // 门禁 B 验证项：只在 running 时循环，暂停后协程立即退出，不空转（全局约束禁止
    // withFrameNanos，这里也不能变成"退出条件写错、一直空转"的另一种反面教材）。
    // tickCount 每 30 次（约 480ms）打一条日志，方便上机后用两次间隔的 logcat 判定循环
    // 是否真的停了——纯"进入/退出各打一条"证明不了协程有没有在两条日志之间偷偷空转。
    LaunchedEffect(running) {
        Log.i("TimerTick", "stopwatch loop enter running=$running")
        var tickCount = 0
        while (running) {
            tickCount++
            nowMs = SystemClock.elapsedRealtime()
            if (tickCount % 30 == 0) {
                Log.d("TimerTick", "stopwatch tick count=$tickCount nowMs=$nowMs")
            }
            delay(16L)
        }
        Log.i("TimerTick", "stopwatch loop exit tickCount=$tickCount")
    }

    val elapsed = engine.elapsedMs(nowMs)

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigDigits(formatStopwatchClock(elapsed), PicoTheme.colorScheme.labelPrimary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    if (running) engine.pause(t) else engine.start(t)
                    running = engine.isRunning
                    nowMs = t
                    onEngineChanged()
                },
            ) { Text(if (running) "暂停" else "开始", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    engine.lap(t)
                    laps = engine.laps.toList()
                    nowMs = t
                    onEngineChanged()
                },
                enabled = running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PicoTheme.colorScheme.fillTertiary,
                    contentColor = PicoTheme.colorScheme.labelPrimary,
                ),
            ) { Text("计次", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = {
                    engine.reset()
                    laps = engine.laps.toList()
                    running = engine.isRunning
                    nowMs = SystemClock.elapsedRealtime()
                    onEngineChanged()
                },
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
                        style = PicoTheme.typography.bodyMedium,
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
private fun CountdownSection(engine: TimerEngine, onEngineChanged: () -> Unit) {
    var running by remember { mutableStateOf(engine.isRunning) }
    var finished by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    // 还没选过时长（engine.mode 还是初始的 STOPWATCH）时，大数字区用这个预览将要设的时长；
    // 一旦调过预设/自定义，displayMs 就直接读 engine.remainingMs，这个值不再参与显示。
    var pendingMinutes by remember { mutableStateOf(5) }

    // 同一份"只在运行时循环"写法：归零瞬间在循环内部检测，立刻 pause + 停循环 + 进入
    // "时间到"反馈，不依赖外部再点一次才发现已经归零。
    LaunchedEffect(running) {
        Log.i("TimerTick", "countdown loop enter running=$running")
        var tickCount = 0
        while (running) {
            tickCount++
            val t = SystemClock.elapsedRealtime()
            nowMs = t
            if (engine.isFinished(t)) {
                engine.pause(t)
                running = false
                finished = true
                onEngineChanged()
            } else {
                if (tickCount % 30 == 0) {
                    Log.d("TimerTick", "countdown tick count=$tickCount nowMs=$nowMs")
                }
                delay(16L)
            }
        }
        Log.i("TimerTick", "countdown loop exit tickCount=$tickCount finished=$finished")
    }

    if (finished) {
        CountdownFinished(
            onAcknowledge = {
                engine.reset()
                finished = false
                running = engine.isRunning
                nowMs = SystemClock.elapsedRealtime()
                onEngineChanged()
            },
        )
        return
    }

    val hasDuration = engine.mode == TimerMode.COUNTDOWN
    val displayMs = if (hasDuration) engine.remainingMs(nowMs) else pendingMinutes * 60_000L

    fun applyMinutes(minutes: Int) {
        pendingMinutes = minutes
        engine.setCountdown(minutes * 60_000L)
        running = engine.isRunning // setCountdown 会停下来，这里同步回 false
        nowMs = SystemClock.elapsedRealtime()
        onEngineChanged()
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
                    ) { Text("$minutes 分", style = PicoTheme.typography.labelMedium) }
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
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    if (running) engine.pause(t) else engine.start(t)
                    running = engine.isRunning
                    nowMs = t
                    onEngineChanged()
                },
                enabled = hasDuration,
            ) { Text(if (running) "暂停" else "开始", style = PicoTheme.typography.labelLarge) }

            Button(
                onClick = {
                    engine.reset()
                    running = engine.isRunning
                    nowMs = SystemClock.elapsedRealtime()
                    onEngineChanged()
                },
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
