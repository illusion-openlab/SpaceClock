package tech.illusion.spaceclock.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import java.time.LocalTime

/**
 * 左栏：大号时间数字 + 底部状态副行。字号由物理长度反算，不写死 sp——
 * 设计文档 §2「文字可读性核算」：6.6cm 字高，3m 处张角 1.26°。窗口是可等比缩放的，
 * 写死 sp 缩放后就不是 6.6cm 了，所以每次组合都要重新换算。
 */
@Composable
fun LeftColumn(now: LocalTime, statusLine: String, modifier: Modifier = Modifier) {
    val converter = LocalPhysicalLengthConverter.current
    val density = LocalDensity.current
    val digitSp = with(density) { converter.lengthToDp(0.066f, LengthUnit.Meters).toSp() }
    Column(modifier = modifier) {
        Text(
            text = "%02d:%02d".format(now.hour, now.minute),
            style = PicoTheme.typography.displayMedium.copy(
                fontSize = digitSp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
            color = PicoTheme.colorScheme.labelPrimary,
        )
        Spacer(Modifier.weight(1f))
        Text(statusLine, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
    }
}
