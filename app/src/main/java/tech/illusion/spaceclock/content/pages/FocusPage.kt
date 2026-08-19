package tech.illusion.spaceclock.content.pages

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.timing.Phase
import tech.illusion.spaceclock.timing.PomodoroConfig
import tech.illusion.spaceclock.timing.PomodoroEngine

/**
 * 「专注」页：番茄钟环形进度 + 今日统计 + 时长配置。
 *
 * [engine] 活在 `ClockVolume` 层，切页不销毁——和秒表/倒计时同一个理由（状态副行要在离开
 * 本页后仍然读得到"是否在跑 / 第几轮"）。本页只负责显示和交互，并在自己可见、且引擎在跑时
 * 驱动 `advanceTo`；离开本页时循环随 `LaunchedEffect` 一起销毁，回来时 `advanceTo` 的
 * while 循环会一次性补上被跳过的阶段（引擎文档里的"设备睡眠"场景在这里同样适用）。
 *
 * 不直接持有 `ClockStore`——持久化入口集中在 `ClockVolume`（[onConfigChanged] 落盘时长配置，
 * [onStatsChanged] 落盘今日统计），和 `WorldPage` 不碰 `ClockStore` 的既有分工一致。
 */
@Composable
fun FocusPage(
    engine: PomodoroEngine,
    onConfigChanged: (PomodoroConfig) -> Unit,
    onStatsChanged: () -> Unit,
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography

    var running by remember { mutableStateOf(engine.isRunning) }
    var nowMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var showSettings by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(engine.config) }

    // 只在运行时循环，暂停后立即退出（TimerPage 同款写法，全局约束禁止 withFrameNanos）。
    // advanceTo 是阶段跃迁唯一结算入口——remainingMs 只负责查询，这里必须显式调用 advanceTo
    // 才会真的翻阶段 / 计数，长时间没回到本页时它会用 while 一次性补上错过的阶段。
    LaunchedEffect(running) {
        while (running) {
            val t = SystemClock.elapsedRealtime()
            val prevCount = engine.completedToday
            val prevMs = engine.focusedTodayMs
            engine.advanceTo(t)
            nowMs = t
            // 只有真的结算了一个番茄才落盘：advanceTo 每 16ms 调一次，但 completedToday /
            // focusedTodayMs 只在跨越一个 WORK 阶段时才变化，不必每帧都写 SharedPreferences。
            if (engine.completedToday != prevCount || engine.focusedTodayMs != prevMs) {
                onStatsChanged()
            }
            delay(16L)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PomodoroRing(
                    phase = engine.phase,
                    remainingMs = engine.remainingMs(nowMs),
                    totalMs = phaseLenMsFor(engine.phase, config),
                )
                Spacer(Modifier.height(8.dp))
                // 深空蓝配色下工作/休息不能靠色相区分（设计文档 §5.4.1）：环已经用亮度+粗细
                // 区分了，这里再补一行文字阶段标签兜底——任何色觉差异都不能靠肉睛猜。
                Text(phaseLabel(engine.phase), style = typo.titleMedium, color = scheme.labelPrimary)
                Text(
                    "第 ${engine.round} 轮",
                    style = typo.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                    color = scheme.labelSecondary,
                )
            }
        }

        Text(
            "今日完成 ${engine.completedToday} 个番茄",
            style = typo.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = scheme.labelSecondary,
        )
        Text(
            "累计专注 ${formatHoursMinutes(engine.focusedTodayMs)}",
            style = typo.bodyMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = scheme.labelSecondary,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    if (running) engine.pause(t) else engine.start(t)
                    running = engine.isRunning
                    nowMs = t
                },
            ) { Text(if (running) "暂停" else "开始", style = typo.labelLarge) }

            Button(
                onClick = {
                    val t = SystemClock.elapsedRealtime()
                    engine.skip(t)
                    running = engine.isRunning
                    nowMs = t
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.fillTertiary,
                    contentColor = scheme.labelPrimary,
                ),
            ) { Text("跳过", style = typo.labelLarge) }
        }

        Spacer(Modifier.height(12.dp))
        Link(onClick = { showSettings = !showSettings }) {
            Text(if (showSettings) "设置 ▲" else "设置 ▾", style = typo.labelMedium)
        }
        if (showSettings) {
            Spacer(Modifier.height(8.dp))
            PomodoroSettings(
                config = config,
                // 计时进行中禁止改：改时长会让当前阶段的剩余时间跳变，SpatialUI 禁用只传
                // enabled = false，不自己画灰色状态。
                enabled = !running,
                onConfigChanged = { next ->
                    config = next
                    engine.config = next
                    onConfigChanged(next)
                },
            )
        }
    }
}

/**
 * 时长配置折叠区：工作 15/25/45/60、短休 5/10、长休 15/30（设计文档 §3.5）。
 *
 * 简报建议用 `SegmentControl`，但反编译 `design-6.0.0.aar` 确认这版 SDK 的
 * `SegmentControl`/`SegmentItem` 完全没有 `enabled` 参数（只有 selected/onClick/modifier/
 * interactionSource/textStyle/colors/icon/title/contentPadding）。任务简报的歧义决议 3
 * 要求"计时进行中禁止改"必须是真正的 `enabled = false`，不是自己画的灰色状态——`SegmentItem`
 * 给不了这个能力，`Button` 可以（`TimerPage.CountdownSection` 的预设分钟按钮已经这么用），
 * 所以这里改用同一套「一排 Button，选中态用主色，禁用时传真正的 enabled=false」模式，
 * 没有发明新组件，只是换成这个版本 SDK 里唯一支持"真禁用"的现成组件。
 */
@Composable
private fun PomodoroSettings(
    config: PomodoroConfig,
    enabled: Boolean,
    onConfigChanged: (PomodoroConfig) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        DurationRow(
            label = "工作时长",
            options = listOf(15, 25, 45, 60),
            selected = config.workMin,
            enabled = enabled,
            onSelect = { onConfigChanged(config.copy(workMin = it)) },
        )
        Spacer(Modifier.height(8.dp))
        DurationRow(
            label = "短休时长",
            options = listOf(5, 10),
            selected = config.shortMin,
            enabled = enabled,
            onSelect = { onConfigChanged(config.copy(shortMin = it)) },
        )
        Spacer(Modifier.height(8.dp))
        DurationRow(
            label = "长休时长",
            options = listOf(15, 30),
            selected = config.longMin,
            enabled = enabled,
            onSelect = { onConfigChanged(config.copy(longMin = it)) },
        )
    }
}

@Composable
private fun DurationRow(
    label: String,
    options: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = typo.labelSmall, color = scheme.labelTertiary)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { minutes ->
                Button(
                    onClick = { onSelect(minutes) },
                    enabled = enabled,
                    colors = if (minutes == selected) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = scheme.fillTertiary,
                            contentColor = scheme.labelSecondary,
                        )
                    },
                ) {
                    Text(
                        "$minutes 分",
                        style = typo.labelMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                    )
                }
            }
        }
    }
}

/**
 * 环形进度：SpatialUI 没有环形进度组件，标准 Compose `Canvas` + `drawArc` 自绘是合理选择，
 * 不是绕开设计系统的权宜之计（任务简报歧义决议 5）。
 *
 * 工作/休息不靠色相区分（深空蓝配色下不可行）：工作用主题强调色 + 粗环，休息用次要标签色
 * + 细环，两个维度都在变化，不依赖任何单一色觉线索；上面调用处还叠了文字阶段标签兜底。
 */
@Composable
private fun PomodoroRing(phase: Phase, remainingMs: Long, totalMs: Long) {
    val scheme = PicoTheme.colorScheme
    val progress = if (totalMs > 0L) {
        (1f - remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isWork = phase == Phase.WORK
    val ringColor = if (isWork) scheme.interaction else scheme.labelSecondary
    val trackColor = scheme.fillTertiary
    val strokeWidthDp = if (isWork) 14.dp else 8.dp

    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = strokeWidthDp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            formatMinSec(remainingMs),
            style = PicoTheme.typography.displayMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = scheme.labelPrimary,
        )
    }
}

/** 阶段总时长（毫秒）。和引擎内部的 `phaseLenMs` 同一份换算公式，但引擎没有把它公开——
 * 计划要求引擎接口保持简报给定的形状不变，这个纯函数留在 UI 侧重复一次比改引擎签名更小。 */
private fun phaseLenMsFor(phase: Phase, config: PomodoroConfig): Long = when (phase) {
    Phase.WORK -> config.workMin * 60_000L
    Phase.SHORT_BREAK -> config.shortMin * 60_000L
    Phase.LONG_BREAK -> config.longMin * 60_000L
}

private fun phaseLabel(phase: Phase): String = when (phase) {
    Phase.WORK -> "专注中"
    Phase.SHORT_BREAK -> "短休中"
    Phase.LONG_BREAK -> "长休中"
}

/** `X 小时 Y 分`——累计专注时长展示精度，不需要秒。 */
internal fun formatHoursMinutes(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0L) "$hours 小时 $minutes 分" else "$minutes 分"
}
