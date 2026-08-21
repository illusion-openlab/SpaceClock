# 当前位置作为默认本地城市 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"当前位置"成为首页天气卡的默认本地城市（取代硬编码的"北京"），每次启动在后台重新定位一次，首页新增真实城市名标题和"重定位"入口。

**Architecture:** `ClockStore.homeCityName` 的默认值字面量从 `"北京"` 换成 `CURRENT_LOCATION_CITY_NAME`——不需要新的持久化标记位，`SharedPreferences` 键"从未写过"这个既有语义天然实现"默认当前位置、用户手动选优先"。`ClockVolume` 新增一个只跑一次的启动 `LaunchedEffect`：只在当前生效的本地城市是"当前位置"时才触发一次后台定位，复用上一轮设计已交付的 `locateCurrentPosition`。`HomePage` 新增标题状态机（拆成纯函数方便测试）和一个"重定位"入口。

**Tech Stack:** Kotlin, Jetpack Compose（SpatialUI `com.pico.spatial.ui.design.*` + `PicoTheme`），JUnit4（纯函数单元测试，本任务不涉及 `org.json`/`Context`，不需要 Robolectric）。

## Global Constraints

- 不申请 Android 定位权限——继续建立在已有的 `LocationRepository`（IP 地理定位）之上。
- 只在*当前生效的*本地城市是 `CURRENT_LOCATION_CITY_NAME` 时，App 启动才发起后台定位请求；本地城市是手动选的其他城市时，启动不触发任何联网请求。
- 用户一旦手动"设为本地"（不管选的是内置城市还是再次显式选"当前位置"），这个选择永久优先于默认值——不引入新的持久化标记位区分"默认值"和"用户手动选的当前位置"，两者都是 `homeCityName == CURRENT_LOCATION_CITY_NAME`，行为上不需要区分。
- 后台定位失败/还在飞行中时，首页保留上一次成功定位的地址和天气；不清空、不阻塞、不新增专门的失败提示文案（首页这次刷新是后台便利性质，不是用户主动操作）。
- `HomePage` 不需要认识 `CURRENT_LOCATION_CITY_NAME` 这个常量本身——`isCurrentLocation` 由 `ClockVolume` 算好传入。
- 首页标题渲染逻辑（见下表）拆成一个不碰 Compose/Context 的纯函数，供单元测试直接覆盖：

  | `displayName` | `isLocating` | 标题文案 |
  |---|---|---|
  | `null` | `true` | `当前位置 · 定位中…` |
  | `null` | `false` | `当前位置 · 今日天气` |
  | 非 `null` | 不区分 | `当前位置：$displayName · 今日天气` |
  | （非当前位置城市） | — | `$cityName · 今日天气`（不变） |

---

### Task 1: `ClockStore.homeCityName` 默认值改为当前位置

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt`

**Interfaces:**
- Consumes: `CURRENT_LOCATION_CITY_NAME`（`tech.illusion.spaceclock.time`，已在上一轮功能里交付）。
- Produces: 无新符号——只改一个既有属性的默认值语义。Task 3 依赖这个改动才能让"默认当前位置"生效。

这个属性是 `Context`-绑定的 `SharedPreferences` 读写，项目里没有 Robolectric 之外的方式单元测试它（跟现有 `homeCityName`/`selectedCityNames` 这几个字段一直以来的情况一样，没有专门测试）。本任务的验证手段是代码走查 + 编译，不是单元测试。

- [ ] **Step 1: 加 import**

在 `app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt` 顶部 import 块追加：

```kotlin
import tech.illusion.spaceclock.time.CURRENT_LOCATION_CITY_NAME
```

- [ ] **Step 2: 改默认值 + 更新过时的文档注释**

把现有的（当前第 29-37 行）：

```kotlin
    /**
     * 本地城市名。决定时钟页天气卡与状态副行显示哪座城。
     *
     * 为什么需要这个字段：V1 不申请定位权限（设计文档 §1），所以"本地"只能由用户指定。
     * 默认北京；用户在世界时钟页用「设为本地」改。Task 2 曾临时用字面量 "本地" 占位，接上后要删掉。
     */
    var homeCityName: String
        get() = prefs.getString(KEY_HOME_CITY, "北京")!!
        set(value) = prefs.edit().putString(KEY_HOME_CITY, value).apply()
```

改成：

```kotlin
    /**
     * 本地城市名。决定时钟页天气卡与状态副行显示哪座城。
     *
     * 默认值是 [CURRENT_LOCATION_CITY_NAME]（"当前位置"，2026-08-21 设计文档）——这只在
     * `SharedPreferences` 的 `KEY_HOME_CITY` 键从未被写过时生效。写入只发生在用户点「设为本地」
     * 的那一刻：一旦写过（哪怕手动选的还是"当前位置"），这次选择永久优先于默认值，不会被
     * 后续的自动定位悄悄覆盖。V1 阶段这里默认过北京、不申请定位权限；现在默认值换成当前位置，
     * 但依然不申请 Android 定位权限——走的是已有的 IP 地理定位（`LocationRepository`）。
     */
    var homeCityName: String
        get() = prefs.getString(KEY_HOME_CITY, CURRENT_LOCATION_CITY_NAME)!!
        set(value) = prefs.edit().putString(KEY_HOME_CITY, value).apply()
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt
git commit -m "feat: default home city to current-location when never manually set"
```

---

### Task 2: `HomePage.kt` 标题状态机 + "重定位" 入口

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/content/pages/HomePage.kt`
- Test: `app/src/test/java/tech/illusion/spaceclock/content/pages/HomePageTest.kt`

**Interfaces:**
- Produces: `HomePage` 新增参数 `isCurrentLocation: Boolean = false`、`displayName: String? = null`、`isLocating: Boolean = false`、`onRelocate: () -> Unit = {}`（**都带默认值**，保证本任务落地后 `ClockVolume` 现有调用点不用改也能编译——Task 3 才会传真实实现覆盖默认值）；顶层纯函数 `internal fun homeTitleLine(cityName: String, isCurrentLocation: Boolean, displayName: String?, isLocating: Boolean): String`，Task 3 不直接调用它（`HomePage` 内部调用），但两边共享同一份标题规则。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/tech/illusion/spaceclock/content/pages/HomePageTest.kt`：

```kotlin
package tech.illusion.spaceclock.content.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePageTest {
    @Test fun `non current-location city keeps original title`() {
        assertEquals(
            "北京 · 今日天气",
            homeTitleLine(cityName = "北京", isCurrentLocation = false, displayName = null, isLocating = false),
        )
    }

    @Test fun `current-location with displayName shows real city name regardless of locating`() {
        assertEquals(
            "当前位置：上海 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = "上海", isLocating = false),
        )
        assertEquals(
            "当前位置：上海 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = "上海", isLocating = true),
        )
    }

    @Test fun `current-location never resolved and locating shows locating text`() {
        assertEquals(
            "当前位置 · 定位中…",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = null, isLocating = true),
        )
    }

    @Test fun `current-location never resolved and not locating falls back to plain title`() {
        assertEquals(
            "当前位置 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = null, isLocating = false),
        )
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.content.pages.HomePageTest"`
Expected: FAIL — `homeTitleLine` 还不存在，编译报错。

- [ ] **Step 3: 实现**

把 `app/src/main/java/tech/illusion/spaceclock/content/pages/HomePage.kt` 整个替换为：

```kotlin
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
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.content.pages.HomePageTest"`
Expected: PASS（4 个用例全绿）

- [ ] **Step 5: 编译验证（Compose 部分没有测试框架覆盖，编译是这部分的验证底线）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`ClockVolume.kt` 现有的 `HomePage(...)` 调用点没有传新参数，靠默认值编译通过）。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/content/pages/HomePage.kt app/src/test/java/tech/illusion/spaceclock/content/pages/HomePageTest.kt
git commit -m "feat: add current-location title states and relocate action to HomePage"
```

---

### Task 3: `ClockVolume` 接线——启动自动定位 + 首页调用点

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/content/ClockVolume.kt`

**Interfaces:**
- Consumes: Task 1 的 `homeCityName` 新默认值；Task 2 的 `HomePage` 新参数；已有的 `locateCurrentPosition: suspend () -> City?`、`currentLocationCity: City?`、`CURRENT_LOCATION_CITY_NAME`（上一轮设计交付，未改动）。
- Produces: 无新公开符号——这是把默认值和 UI 参数接起来的最后一步。

**关键设计点（不用额外写代码，但要知道为什么会生效）：** 启动效果只在 `homeCityName == CURRENT_LOCATION_CITY_NAME` 时才跑；这个条件既覆盖"从未手动设过本地、吃到 Task 1 新默认值"的情况，也覆盖"用户之前手动选过当前位置"的情况——两者殊途同归，不需要分开判断。`homeLocating` 是纯内存状态（不落盘），`LaunchedEffect(Unit)` 只在这棵树第一次组合时跑一次，两者加起来天然实现"每次进程启动都重新定位一次"，不需要额外记"上次定位时间"。

- [ ] **Step 1: 加一份 `homeLocating` 状态**

把现有的（当前第 106-109 行）：

```kotlin
    // 定位（本设计）：跟 weatherRepository 同样的"外部 IO 入口集中在这一层"理由，只持有一份实例。
    // 不做自动/周期性定位——只在用户点"使用当前位置"/"重新定位"时才发起一次网络请求。
    val locationRepository = remember { LocationRepository() }
    val coroutineScope = rememberCoroutineScope()
```

改成：

```kotlin
    // 定位：跟 weatherRepository 同样的"外部 IO 入口集中在这一层"理由，只持有一份实例。
    val locationRepository = remember { LocationRepository() }
    val coroutineScope = rememberCoroutineScope()
    // 是否正在定位（2026-08-21 设计文档）：纯内存状态，不落盘——每次启动天然重置，配合下面的
    // 启动 LaunchedEffect(Unit) 直接实现"每次启动都重新定位一次"，不用额外记"上次定位时间"。
    // 三个触发点（启动自动定位、WorldClockRow 的"重新定位"、HomePage 的"重定位"）共用这一份状态
    // 和下面同一个 locateCurrentPosition，不重复实现。
    var homeLocating by remember { mutableStateOf(false) }
```

- [ ] **Step 2: `onRelocateCurrentLocation` 包一层 `homeLocating`**

把现有的（当前第 128-130 行）：

```kotlin
    val onRelocateCurrentLocation: () -> Unit = {
        coroutineScope.launch { locateCurrentPosition() }
    }
```

改成：

```kotlin
    val onRelocateCurrentLocation: () -> Unit = {
        coroutineScope.launch {
            homeLocating = true
            locateCurrentPosition()
            homeLocating = false
        }
    }
```

- [ ] **Step 3: 加启动自动定位的 `LaunchedEffect`**

紧接着上一步（`onRelocateCurrentLocation` 定义之后、天气仓库那段"Task 9"注释块之前）插入：

```kotlin
    // 当前位置是当前生效的本地城市时（不管是 Task 1 的新默认值，还是用户之前手动选的），
    // 每次进程启动都在后台重新定位一次，让首页数据保持新鲜（2026-08-21 设计文档）。手动选了
    // 别的城市完全不进这个分支，跟现有"只在相关时才联网"的原则一致，不会给这批用户增加请求。
    LaunchedEffect(Unit) {
        if (homeCityName == CURRENT_LOCATION_CITY_NAME) {
            if (CURRENT_LOCATION_CITY_NAME !in selectedCityNames) {
                val updated = selectedCityNames + CURRENT_LOCATION_CITY_NAME
                store.selectedCityNames = updated
                selectedCityNames = updated
            }
            homeLocating = true
            locateCurrentPosition()
            homeLocating = false
        }
    }
```

- [ ] **Step 4: 首页调用点传入新参数**

把现有的（当前第 297-300 行）：

```kotlin
                        ClockPage.HOME -> HomePage(
                            weather = weatherByCity[homeCityName],
                            cityName = homeCityName,
                        )
```

改成：

```kotlin
                        ClockPage.HOME -> HomePage(
                            weather = weatherByCity[homeCityName],
                            cityName = homeCityName,
                            isCurrentLocation = homeCityName == CURRENT_LOCATION_CITY_NAME,
                            displayName = currentLocationCity?.displayName,
                            isLocating = homeLocating,
                            onRelocate = onRelocateCurrentLocation,
                        )
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 全量单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（含 Task 2 的 4 条新用例，以及全部既有用例）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/content/ClockVolume.kt
git commit -m "feat: auto-relocate on launch and wire relocate state into HomePage"
```

---

### Task 4: 构建 + 上机验证 + 文档更新

**Files:**
- Modify: `AGENTS.md`（只更新测试计数，Step 5）

**Interfaces:** 无新代码接口。

- [ ] **Step 1: 完整 Debug 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 环境预检（如本次会话尚未做过健康的环境检查）**

按 `pico-env-doctor` 技能跑一次环境检查。如果本次 host session 内已经有新鲜的健康检查结果，直接复用。

- [ ] **Step 3: 安装并启动，跑一遍新流程**

按 `spatial-emulator-usage` 技能把 Step 1 产出的 APK 装到模拟器/设备并启动，走一遍：

1. **全新数据场景**（如果测试设备上此前跑过上一轮"当前位置"功能、已经有 `homeCityName`/
   `currentLocationCity` 落盘数据，先用 `pico-cli app clear-data` 或等效方式清空该应用数据，
   确保 `SharedPreferences` 里 `home_city` 键真的从未写过——只有这样才能验证到 Task 1 的新
   默认值真正生效，而不是复用了上一轮验证时手动设过的本地城市）：启动 App，切到首页，确认
   标题先显示"当前位置 · 定位中…"、数值区全部"—"，随后（定位成功的话）标题变成
   "当前位置：<真实城市名> · 今日天气"，天气数值区不再是"—"。若本环境的 IP 定位服务仍处于
   限流状态（上一轮验证时 `ipapi.co` 返回过 403/429），定位失败也是可接受的证据——标题应该
   落回"当前位置 · 今日天气"（不是一直卡在"定位中…"），天气数值区保持"—"，且不崩溃。
2. **重定位按钮**：在首页标题旁点"重定位"，确认按钮文案短暂变成"定位中…"（不可点），随后恢复
   成"重定位"，App 不崩溃。
3. **手动选择优先级不被打破**：切到世界时钟页，把某个内置城市（如"东京"）"设为本地"，回到首页
   确认标题变成"东京 · 今日天气"（不再是"当前位置"相关文案，也没有"重定位"入口）。重启 App
   （`pico-cli app stop` 后重新 `pico-cli app launch`），确认首页仍然显示"东京"，**没有**发起
   自动定位请求（logcat 里这次启动不应该再出现新的 `LocationRepository: HTTP-STATUS` 行）——
   这是本任务除了"默认当前位置"之外同样重要的反向验证：手动选择必须真正阻止启动时的联网请求，
   不只是阻止首页显示。
4. 把本地城市改回"当前位置"（世界时钟页那一行点"设为本地"），重启 App，确认这次启动 logcat
   里**确实**出现了新的 `LocationRepository: HTTP-STATUS` 行——验证条件判断的另一侧没有反过来
   被写死成"永远不刷新"。

- [ ] **Step 4: 采集证据**

按 `spatial-emulator-usage` 技能采集 logcat（重点看 `LocationRepository`/`HTTP-STATUS`/
`WeatherRepository`/`WEATHER-SNAPSHOT` 几个 tag，确认 Step 3.3 那次重启**没有**新的
`LocationRepository` 行、Step 3.4 那次重启**有**）和至少两张截图（"定位中"态、
"当前位置：<城市名>"态，如果网络环境允许拿到后者；如果两次都被限流，至少截一张"定位失败后
落回当前位置 · 今日天气"的状态，如实记录网络环境限制）。

- [ ] **Step 5: 更新 `AGENTS.md` 测试计数**

先跑一次 `./gradlew :app:testDebugUnitTest` 记下真实的最终测试总数（Task 2 新增 4 条用例，
应该在上一轮功能收尾时记的基线上再加 4）。打开 `AGENTS.md`，找到 `./gradlew :app:testDebugUnitTest`
那一行附带的"当前基线 N 个全绿"注释（上一轮功能收尾时刚更新过，这次只是再往上加），把 N 改成
这次实测到的真实数字。

- [ ] **Step 6: 提交（如 Step 3 发现了需要改代码的问题）**

如果验证过程中发现了需要改代码的问题，修完后按 Task 1-3 同样的"改动 + 编译/测试验证 + commit"
节奏单独提交，不要在这一步一次性堆多个改动。无论 Step 3 是否发现代码问题，Step 5 的 AGENTS.md
更新都要提交：

```bash
git add AGENTS.md
git commit -m "docs: update AGENTS.md test count after default-current-location-home feature"
```
