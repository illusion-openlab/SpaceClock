package tech.illusion.spaceclock.content

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.platform.debugLog
import tech.illusion.spaceclock.theme.TABULAR_FIGURES

/**
 * 闹钟到点后覆盖整个体积窗口的提醒卡片（含 NavBar）。两个必做点见类注释里的行内注释：
 * 遮罩颜色角色是不透明的，要自己 `copy(alpha=)`；遮罩必须吞掉指针事件，否则点击会穿透点亮
 * 下层导航按钮——工作区已经在别的任务上踩过这个坑。
 */
@Composable
fun AlarmCard(alarm: Alarm, weatherLine: String?, onSnooze: () -> Unit, onDismiss: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    Box(
        Modifier.fillMaxSize()
            // 1) 颜色角色是不透明的，遮罩要自己 copy(alpha=)
            .background(scheme.fillSecondary.copy(alpha = 0.72f))
            // 2) 遮罩必须消费指针事件，否则点击会穿透点亮下层按钮
            .pointerInput(Unit) { detectTapGestures { /* 吞掉 */ } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.backgroundMaterial(enable = true, style = Material.Regular)
                .clip(RoundedCornerShape(20.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "%02d:%02d".format(alarm.hour, alarm.minute),
                style = PicoTheme.typography.displayMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
                color = scheme.labelPrimary,
            )
            // 天气文字：Task 9 前恒为 null，那就整行不显示，不要显示空字符串占位。
            weatherLine?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = PicoTheme.typography.bodyMedium, color = scheme.labelSecondary)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSnooze,
                    // 门禁 B 诊断：截图相机是固定窄视角+带角度的，不能拿截图像素去猜这两个
                    // 按钮的真实坐标，必须打真实布局坐标出来才能精确点击验证遮罩穿透。
                    modifier = Modifier.onGloballyPositioned { c ->
                        debugLog("AlarmLayout") {
                            "cardSnooze w=${c.size.width} h=${c.size.height} " +
                                "x=${c.positionInRoot().x} y=${c.positionInRoot().y}"
                        }
                    },
                ) { Text("稍后 10 分钟") }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.onGloballyPositioned { c ->
                        debugLog("AlarmLayout") {
                            "cardDismiss w=${c.size.width} h=${c.size.height} " +
                                "x=${c.positionInRoot().x} y=${c.positionInRoot().y}"
                        }
                    },
                ) { Text("关闭") }
            }
        }
    }
}
