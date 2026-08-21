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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.weather.WEATHER_FRESH_WINDOW_MS
import tech.illusion.spaceclock.weather.WeatherSnapshot

/**
 * 时钟主页的右侧玻璃面板内容：城市名 + 天气快照。
 *
 * `weather == null` 时天气区显示 `—` 占位——设计的错误处理原则是「天气拉取失败不能拖垮时钟」，
 * 所以这里绝不空白也绝不崩。`weather` 既可能是刚拉到的新鲜数据，也可能是
 * `WeatherRepository.snapshot()` 在网络失败时退回的过期缓存——过期缓存仍然显示数字（不能因为
 * 数据旧就假装没有），只是额外加一行「更新于 X 分钟前」，判据与仓库共用 [WEATHER_FRESH_WINDOW_MS]，
 * 不在这里另猜一个 30 分钟。
 *
 * [isCurrentLocation]/[displayName]/[isLocating]/[onRelocate] 是 2026-08-21 设计新增的：本地城市
 * 是"当前位置"时标题要带真实城市名、支持在首页直接重新定位，不用切到世界时钟页。`HomePage`
 * 不需要认识 `CURRENT_LOCATION_CITY_NAME` 这个常量本身——`isCurrentLocation` 由 `ClockVolume`
 * 算好传入。四个新参数都带默认值，只是为了让改动分成小步、每步都能独立编译，不代表这几个参数
 * 在真实调用点是可选的。
 */
@Composable
fun HomePage(
    weather: WeatherSnapshot?,
    cityName: String,
    isCurrentLocation: Boolean = false,
    displayName: String? = null,
    isLocating: Boolean = false,
    onRelocate: () -> Unit = {},
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                homeTitleLine(cityName, isCurrentLocation, displayName, isLocating),
                style = typo.labelSmall,
                color = scheme.labelSecondary,
            )
            // 只有当前位置这一种本地城市有"重定位"的概念——手动选的内置城市经纬度是常量，
            // 永远不会变。定位飞行中显示纯文本"定位中…"（不可点），避免用户在一次请求还没
            // 返回时重复触发；这跟 WorldClockRow 里 isHome/isPinned 那组"本地"/"设为本地"
            // 二选一渲染是同一个模式，不新造一种写法。
            if (isCurrentLocation) {
                if (isLocating) {
                    Text("定位中…", style = typo.labelSmall, color = scheme.labelTertiary)
                } else {
                    Link(onClick = onRelocate) {
                        Text("重定位", style = typo.labelSmall)
                    }
                }
            }
        }
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

/**
 * 首页天气卡标题（设计文档 2026-08-21 的标题表）：非当前位置城市保持原样 `"$cityName · 今日天气"`
 * 不变；当前位置城市按 [displayName]（是否定位成功过）和 [isLocating]（是否正在定位）两个信号
 * 选文案。拆成纯函数方便 [HomePageTest] 覆盖四种组合，不用跑 Compose。
 */
internal fun homeTitleLine(
    cityName: String,
    isCurrentLocation: Boolean,
    displayName: String?,
    isLocating: Boolean,
): String {
    if (!isCurrentLocation) return "$cityName · 今日天气"
    return when {
        displayName != null -> "$cityName：$displayName · 今日天气"
        isLocating -> "$cityName · 定位中…"
        else -> "$cityName · 今日天气"
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
