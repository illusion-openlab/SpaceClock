package tech.illusion.spaceclock.content

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

/** 五个功能页，label 是导航按钮上显示的文字（设计文档 §3）。 */
enum class ClockPage(val label: String) {
    HOME("时钟"), WORLD("世界"), ALARM("闹钟"), TIMER("计时"), FOCUS("专注")
}

/** 底部五按钮导航条，等宽排布。选中态靠 colors 区分，不靠 style（Button 没有这个参数）。 */
@Composable
fun NavBar(current: ClockPage, onSelect: (ClockPage) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClockPage.entries.forEach { page ->
            // SpatialUI Button 没有 style 参数（样式由 size 推导）；禁用只传 enabled=false；
            // 不要自定义 ButtonSize，会静默丢圆角。选中态靠 colors 区分。
            Button(
                onClick = {
                    // 门禁 B 的穿透坑判据（AlarmCard 遮罩）：点这个按钮时如果这条日志没出现，
                    // 说明点击没有落到按钮上——不管是被遮罩吞掉还是压根没点中。
                    Log.i("NavBar", "tap reached page=${page.name}")
                    onSelect(page)
                },
                modifier = Modifier.weight(1f).onGloballyPositioned { c ->
                    Log.i(
                        "ClockLayout",
                        "navButton page=${page.name} w=${c.size.width} h=${c.size.height} " +
                            "x=${c.positionInRoot().x} y=${c.positionInRoot().y}",
                    )
                },
                colors = if (page == current) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = PicoTheme.colorScheme.fillTertiary,
                        contentColor = PicoTheme.colorScheme.labelSecondary,
                    )
                },
            ) { Text(page.label, style = PicoTheme.typography.labelMedium) }
        }
    }
}
