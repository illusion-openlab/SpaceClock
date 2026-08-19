package tech.illusion.spaceclock.content.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.weather.WEATHER_FRESH_WINDOW_MS
import tech.illusion.spaceclock.weather.WeatherSnapshot

/**
 * 时钟主页的右侧玻璃面板内容：城市名 + 天气快照。
 *
 * `weather == null` 时天气区显示 `—` 占位——设计的错误处理原则是「天气拉取失败不能拖垮时钟」，
 * 所以这里绝不空白也绝不崩。Task 9 接入 `WeatherRepository` 后，`weather` 既可能是刚拉到的新鲜
 * 数据，也可能是 `WeatherRepository.snapshot()` 在网络失败时退回的过期缓存——过期缓存仍然显示
 * 数字（不能因为数据旧就假装没有），只是额外加一行「更新于 X 分钟前」，判据与仓库共用
 * [WEATHER_FRESH_WINDOW_MS]，不在这里另猜一个 30 分钟。
 */
@Composable
fun HomePage(weather: WeatherSnapshot?, cityName: String) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("$cityName · 今日天气", style = typo.labelSmall, color = scheme.labelSecondary)
        weather?.let {
            val ageMs = System.currentTimeMillis() - it.fetchedAtEpochSec * 1000
            if (ageMs >= WEATHER_FRESH_WINDOW_MS) {
                Text(
                    "更新于 ${ageMs / 60_000} 分钟前",
                    style = typo.labelSmall,
                    color = scheme.labelTertiary,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = weather?.let { "${it.tempC}°" } ?: "—",
            style = typo.headlineLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = scheme.labelPrimary,
        )
        Spacer(Modifier.height(12.dp))
        KeyValueRow("最高 / 最低", weather?.let { "${it.highC}° / ${it.lowC}°" } ?: "—")
        KeyValueRow("体感", weather?.let { "${it.feelsLikeC}°" } ?: "—")
        KeyValueRow("湿度 / 风", weather?.let { "${it.humidityPct}% · ${it.windScale}级" } ?: "—")
        KeyValueRow("日出 / 日落", weather?.let { "${it.sunrise} / ${it.sunset}" } ?: "—")
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween) {
        Text(key, style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        Text(
            value,
            style = PicoTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = PicoTheme.colorScheme.labelPrimary,
        )
    }
    HorizontalDivider(color = PicoTheme.colorScheme.dividerLine)
}
