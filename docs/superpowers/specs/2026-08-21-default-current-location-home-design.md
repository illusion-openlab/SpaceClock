# 当前位置作为默认本地城市：设计

## 背景

`docs/superpowers/specs/2026-08-20-current-location-weather-design.md` 交付的是"当前位置"作为一个
**用户手动添加、手动设为本地**的可选城市——`ClockStore.homeCityName` 的默认值仍然是硬编码的
`"北京"`（`ClockStore.kt`），从未跑过定位。本设计要做的是把"当前位置"提升为**默认的本地城市**：
新装/从未手动设过本地的用户，首页天气卡默认显示当前位置的天气，而不是北京；同时每次启动都在后台
重新定位一次，让这份数据保持新鲜。

## 目标 / 非目标

**目标**

- 从未手动设过本地城市的用户，首页天气默认显示"当前位置"的天气，而不是硬编码的北京。
- 用户一旦手动把任意城市（包括再次显式选"当前位置"）设为本地，这个手动选择永久优先，不会被
  自动定位悄悄覆盖回去。
- 当"当前位置"是当前生效的本地城市时，每次 App 启动都在后台重新定位一次，刷新数据；重新定位的
  网络请求返回之前，继续显示上一次成功定位的地址和天气（不清空、不阻塞）。
- 首页天气卡对"当前位置"这个特殊本地城市，标题显示真实城市名（"当前位置：上海 · 今日天气"），
  并提供一个"重定位"入口，不用切到世界时钟页去点。

**非目标**

- 不申请 Android 定位权限——本设计完全建立在已有的 IP 地理定位（`LocationRepository`）之上。
- 当前生效的本地城市不是"当前位置"时（用户手动选了别的城市），启动时不发起任何定位请求——
  "只在相关时才联网"这条原则对这批用户保持不变。
- 不在首页新增"定位失败"提示文案——首页这次刷新是后台便利性质，不是用户主动发起的操作，
  失败就静默保留旧数据；世界时钟页已有的"定位失败，请检查网络后重试"提示保留在那一侧，供用户
  主动排查时使用。
- 不引入新的持久化标记位区分"默认值"和"用户手动选的当前位置"——两者最终都是
  `homeCityName == CURRENT_LOCATION_CITY_NAME`，行为上不需要区分。

## 核心机制：默认值来自 `SharedPreferences` 的"从未写过"语义

`ClockStore.homeCityName` 现状：

```kotlin
var homeCityName: String
    get() = prefs.getString(KEY_HOME_CITY, "北京")!!
    set(value) = prefs.edit().putString(KEY_HOME_CITY, value).apply()
```

这个 getter 只有在 `KEY_HOME_CITY` **从未被写过**时才会返回默认值——而写入只发生在用户点"设为本地"
的那一刻（`ClockVolume.kt` 的 `onSetHome` 回调）。把默认值字面量从 `"北京"` 换成
`CURRENT_LOCATION_CITY_NAME` 就足够实现"默认当前位置、手动选优先"的全部语义，不需要额外的
"是否手动选过"标记位：

- 全新安装、从未点过"设为本地"的用户：`homeCityName` 一直读到默认值 `"当前位置"`。
- 任何时候点了一次"设为本地"（不管选的是内置城市还是再次显式选"当前位置"）：这次选择被持久化写入，
  从此这个值优先于默认值，行为和现在完全一样。
- 已经安装过旧版本、从未手动设过本地的老用户：升级后 `KEY_HOME_CITY` 依然是"从未写过"的状态，
  会自然读到新默认值——不需要任何迁移代码。

## 启动时自动重新定位

`ClockVolume` 增加一个只跑一次的启动副作用：

```kotlin
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

- **触发条件**：只看*当前生效的*本地城市是不是"当前位置"，跟它是默认值还是用户手动选的无关——
  两种情况都应该在启动时刷新。用户手动选了别的城市时，这段代码整体不跑，不发任何请求。
- **`homeLocating`**：新的一份纯内存状态（`var homeLocating by remember { mutableStateOf(false) }`），
  不落盘——每次启动天然重置，直接实现"每次启动都重新定位"，不需要额外的"上次定位时间"判断。
- **复用已有的 `locateCurrentPosition`**：这个函数（上一轮设计已交付）只在定位成功时才覆盖
  `currentLocationCity`；失败或还在飞行中时，上一次成功的地址原样保留——"新数据返回前用旧数据"
  这条要求不需要新代码，是复用现有函数的自然结果。
- **`selectedCityNames` 兜底**：如果"当前位置"还从未被加进城市列表（例如全新安装，用户从没去世界
  时钟页点过"使用当前位置"），启动时顺手把它加进去，这样 `selectedCities` 的解析分支才能认出它
  （沿用上一轮设计里 `if (name == CURRENT_LOCATION_CITY_NAME) currentLocationCity else ...` 那条
  分支，不用改）。

`homeLocating` 同时驱动世界时钟页现有的"重新定位"行内动作和本设计新增的首页"重定位"入口——
三个触发点（启动、世界时钟页那一行、首页新按钮）共用同一份状态和同一个函数，不重复实现。

## 首页 (`HomePage.kt`) 改动

### 新增参数

```kotlin
@Composable
fun HomePage(
    weather: WeatherSnapshot?,
    cityName: String,
    isCurrentLocation: Boolean,
    displayName: String?,
    isLocating: Boolean,
    onRelocate: () -> Unit,
)
```

- `isCurrentLocation`：`cityName == CURRENT_LOCATION_CITY_NAME`（由 `ClockVolume` 算好传入，
  `HomePage` 不需要认识这个常量）。
- `displayName`：只在 `isCurrentLocation` 为真时有意义，取自 `currentLocationCity?.displayName`。
- `isLocating`：`homeLocating` 原样传入。
- `onRelocate`：`ClockVolume` 现有的 `onRelocateCurrentLocation` 原样传入。

### 标题渲染

非当前位置城市：完全不变，`"$cityName · 今日天气"`。

当前位置城市，按 `displayName`/`isLocating` 组合：

| `displayName` | `isLocating` | 标题文案 |
|---|---|---|
| `null`（从未定位成功过） | `true` | `当前位置 · 定位中…` |
| `null` | `false`（定位过至少一次但失败，或还没跑过） | `当前位置 · 今日天气` |
| 非 `null`（定位成功过，可能是刚更新的也可能是上一次的旧值） | 不区分 | `当前位置：$displayName · 今日天气` |

`weather` 为 `null` 时数值区仍然走已有的 `?: "—"` 兜底——不新增专门的"定位失败"数值态，跟设计目标
"首页不新增失败提示文案"一致。

### "重定位"入口

标题旁新增一个 `Link`，**只在 `isCurrentLocation` 为真时显示**（手动选的城市没有"重定位"这个概念，
经纬度是内置表里的常量，本来就不会变）。`isLocating` 为真时文案换成"定位中…"且不可点，避免用户在
一次定位飞行中重复触发。点击调用 `onRelocate`。

## `ClockVolume` 接线

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

`onRelocateCurrentLocation` 已经存在（上一轮设计交付），本设计只是多传一份 `isCurrentLocation`/
`displayName`/`isLocating` 给首页渲染用，不需要新建回调。

## 错误处理小结

| 场景 | 行为 |
|---|---|
| 当前位置是本地城市，启动定位失败（离线/服务限流） | 首页保留上一次成功的地址和天气；标题按上表落到 `当前位置 · 今日天气`（`displayName` 仍是旧值时会显示旧城市名） |
| 全新安装，从未定位成功过，且这次启动定位也失败 | 首页显示 `当前位置 · 今日天气`，天气数值区全部 `—`——跟现有"没数据显示 —"的规则一致，不新增专门文案 |
| 本地城市是手动选的内置城市 | 启动不发任何定位请求，首页标题/行为跟现在完全一样 |

## 范围边界（明确不做）

- 不申请 Android 定位权限。
- 本地城市不是"当前位置"时，启动不触发任何联网定位。
- 首页不新增"定位失败"提示文案。
- 不引入区分"默认值"和"用户手动选当前位置"的新持久化标记位。
