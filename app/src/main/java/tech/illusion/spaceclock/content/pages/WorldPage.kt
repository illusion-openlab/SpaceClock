package tech.illusion.spaceclock.content.pages

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.Link
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.ScrollIndicator
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.delay
import tech.illusion.spaceclock.theme.TABULAR_FIGURES
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import tech.illusion.spaceclock.time.City
import tech.illusion.spaceclock.time.localTimeIn
import tech.illusion.spaceclock.time.offsetLabel
import java.time.Instant
import java.time.ZoneId

/**
 * 世界时钟列表页。
 *
 * 城市列表、"本地城市"标记和当前选中城市都由 `ClockVolume` 持有并传进来——它们要驱动常驻的
 * 地球标记点，页面级 `remember` 会随切页销毁（Task 4）。城市名列表和本地城市现在经
 * `ClockVolume` 落到 `ClockStore`（Task 6）：本页仍然只拿 `List<City>`/`String` 和几个
 * 回调，不直接碰 `ClockStore`——持久化入口集中在 `ClockVolume` 一处。`focusedCityName`
 * 依然是纯内存态（驱动地球转向的临时选中，不需要跨会话保留）。
 *
 * 时差换算的参照系用设备的物理时区（`ZoneId.systemDefault()`，和 Home 页 `ZonedDateTime.now()`
 * 同一来源），不是"设为本地"选中的那座城——后者只决定天气卡/状态副行看哪座城的天气
 * （Task 9），跟这一屏"和本地差几小时"里的"本地"是两个不同的概念，混用会让时差数字答非所问。
 *
 * [onSelectCity] 除了 UI 高亮，现在还驱动地球把该城转到正面（Task 4）。
 */
@Composable
fun WorldPage(
    cities: List<City>,
    homeCityName: String,
    focusedCityName: String?,
    onAddCity: (City) -> Unit,
    onSetHome: (City) -> Unit,
    onSelectCity: (City) -> Unit,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    // 世界时钟只需要分钟级精度，30 秒一跳足够新鲜又不至于每秒重排一整屏列表。
    // 禁止用 withFrameNanos——它在 WindowContainer 里只触发一次（Global Constraints）。
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = Instant.now()
        }
    }
    val localZoneId = remember { ZoneId.systemDefault().id }

    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        CityPicker(
            alreadyAdded = remember(cities) { cities.map { it.name }.toSet() },
            onPick = { city ->
                onAddCity(city)
                showPicker = false
            },
            onCancel = { showPicker = false },
        )
        return
    }

    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("世界时钟", style = typo.titleLarge, color = scheme.labelPrimary)
        Spacer(Modifier.height(8.dp))

        if (cities.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有添加城市 —— 点击下方“添加城市”",
                    style = typo.bodyMedium,
                    color = scheme.labelTertiary,
                )
            }
        } else {
            val listState = rememberLazyListState()
            // 门禁 B 的"6 行一屏不被裁"是几何判定，模拟器截图相机固定窄视角装不下整个面板——
            // 用实测坐标而不是照片构图判定（AGENTS.md 的教训）。记录列表可视区高度和每行的
            // 高度/y 位置，事后用算术核对前 6 行是否都落在可视区内。
            Box(
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { c ->
                    Log.i(
                        "WorldLayout",
                        "listViewport w=${c.size.width} h=${c.size.height} y=${c.positionInRoot().y}",
                    )
                },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items = cities, key = { it.name }) { city ->
                        WorldClockRow(
                            city = city,
                            now = now,
                            localZoneId = localZoneId,
                            isHome = city.name == homeCityName,
                            isPinned = city.name == focusedCityName,
                            onSelect = { onSelectCity(city) },
                            onSetHome = { onSetHome(city) },
                            rowModifier = Modifier.onGloballyPositioned { c ->
                                Log.i(
                                    "WorldLayout",
                                    "row city=${city.name} h=${c.size.height} y=${c.positionInRoot().y}",
                                )
                            },
                        )
                        HorizontalDivider(color = scheme.dividerLine)
                    }
                }
                ScrollIndicator(state = listState)
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showPicker = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = scheme.fillTertiary),
        ) {
            Text("＋ 添加城市", color = scheme.labelPrimary)
        }
    }
}

/**
 * 一行：城市名 + 时差 + 当地时间（tnum） + 天气占位 + "设为本地"行内动作。
 *
 * 高亮（选中态背景）同时服务两个语义：点击后的临时选中（供 Task 4 联动）和"当前本地城市"的
 * 常驻标记——按任务简报的歧义决议 1，不为后者另建一套 UI，直接复用这个高亮样式。
 *
 * `colors.backgroundColor` 未高亮时必须显式传 `Color.Transparent`，不能留 `Unspecified`——
 * `ListItemColors.copy()` 内部用 `takeOrElse{}`，`Unspecified` 会回落到默认的 15% 白
 * （fillLight），导致所有行看起来都是同一种"选中"底色。
 */
@Composable
private fun WorldClockRow(
    city: City,
    now: Instant,
    localZoneId: String,
    isHome: Boolean,
    isPinned: Boolean,
    onSelect: () -> Unit,
    onSetHome: () -> Unit,
    rowModifier: Modifier = Modifier,
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    val highlighted = isHome || isPinned
    val local = localTimeIn(city.zoneId, now)

    ListItem(
        modifier = Modifier.fillMaxWidth().then(rowModifier).clickable(onClick = onSelect),
        colors = ListItemDefaults.listItemColors(
            backgroundColor = if (highlighted) scheme.fillTertiary else Color.Transparent,
        ),
        shape = RoundedCornerShape(10.dp),
        // 全部塞进一个 headlineContent（单行），不用 supportingContent/trailingContent：实测
        // ListItem 的 TrailingContent 内部强制 `sizeIn(minHeight = 40.dp)`（design6-src
        // ListItem.kt），只要传了 trailingContent，行高就锁死在 ≥72dp(=144px)，两行/单行
        // headline 都救不回来——6 行 × 144px = 864px，超过面板实测 800px 的可视区高度
        // （WorldLayout 门禁 B 证据）。绕开 trailingContent、自己在 headlineContent 里用
        // SpaceBetween 把"名字+时差"和"时间+天气+设为本地"分两端排在同一行，行高回落到
        // ListItem 的 60dp MinHeight（=120px），6 行=720px，在 800px 可视区内留有余量。
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左组用 weight(1f) 而不是让两组各自撑满内容宽度：内置城市表里"约翰内斯堡"
                // "伊斯坦布尔"这类 5 字城市名 + 时差，加上右侧的时间/天气/"设为本地"，实测
                // 宽度接近面板可用宽度的上限。左组让权重、名字加 ellipsis，保证右侧的时间和
                // "设为本地"操作永远完整显示，长名字改成截断而不是把右侧挤出面板。
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        city.name,
                        style = typo.bodyLarge,
                        color = scheme.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = offsetLabel(city.zoneId, localZoneId, now),
                        style = typo.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = scheme.labelSecondary,
                        maxLines = 1,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "%02d:%02d".format(local.hour, local.minute),
                        style = typo.bodyLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = scheme.labelPrimary,
                    )
                    // 天气位：Task 9 前恒为占位——缺数据源不能让行空白或崩溃（Task 2 定的原则）。
                    Text("—", style = typo.bodySmall, color = scheme.labelTertiary)
                    if (isHome) {
                        Text("本地", style = typo.labelSmall, color = scheme.interaction)
                    } else {
                        Link(onClick = onSetHome) {
                            Text("设为本地", style = typo.labelSmall)
                        }
                    }
                }
            }
        },
    )
}

/**
 * 同页内的城市选择子视图（不开新窗口）——从 [BUILTIN_CITIES] 里挑一个还没加过的城市。
 * 挑中后立刻返回主列表；已全部添加则给出说明文案而不是空白列表。
 */
@Composable
private fun CityPicker(alreadyAdded: Set<String>, onPick: (City) -> Unit, onCancel: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    val available = remember(alreadyAdded) { BUILTIN_CITIES.filter { it.name !in alreadyAdded } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("选择城市", style = typo.titleLarge, color = scheme.labelPrimary)
            Link(onClick = onCancel) { Text("取消") }
        }
        Spacer(Modifier.height(8.dp))

        if (available.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("30 座内置城市都已添加", style = typo.bodyMedium, color = scheme.labelTertiary)
            }
        } else {
            val listState = rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items = available, key = { it.name }) { city ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = { onPick(city) }),
                            headlineContent = {
                                Text(city.name, style = typo.bodyLarge, color = scheme.labelPrimary)
                            },
                            supportingContent = {
                                Text(city.zoneId, style = typo.bodySmall, color = scheme.labelTertiary)
                            },
                        )
                        HorizontalDivider(color = scheme.dividerLine)
                    }
                }
                ScrollIndicator(state = listState)
            }
        }
    }
}
