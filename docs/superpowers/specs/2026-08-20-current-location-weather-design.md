# 当前位置天气：设计

## 背景

天气卡（`WeatherRepository`）现在只能显示 `BUILTIN_CITIES` 固定表里的城市天气，"本地城市"由用户在世界时钟页手动选一个内置城市（`ClockStore.homeCityName`）。`ClockStore.kt` 里有一条现存注释明确写着"V1 不申请定位权限（设计文档 §1），所以'本地'只能由用户指定"——本设计要做的是补上这个 V2：让用户可以通过联网 IP 地理定位拿到自己实际所在的经纬度，作为一座"当前位置"城市加入城市列表、参与天气/世界时钟的现有流程。

## 目标 / 非目标

**目标**

- 新增一种城市来源：通过 IP 地理定位服务解析出经纬度、时区、城市名，合成一个 `City` 条目。
- 这个条目完全复用现有"城市列表 / 设为本地 / 天气卡"三件套的既有机制，不另起一套 UI 概念。
- 不申请 Android 定位权限（`ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`），因为 PICO 头显是否有可靠的 GPS/网络位置提供者未经验证；纯 HTTPS 请求不依赖设备定位硬件。

**非目标**

- 不做通用的"删除任意城市"功能——现状是只能加不能减，这条限制超出本次范围，只解决"当前位置"这一个特殊条目的过时问题（见下方"重新定位"）。
- 不做自动/后台定期重新定位——只在用户主动点击时触发一次网络请求。
- 不追求设备级定位精度——IP 地理定位是城市级精度，出口 IP/代理可能导致偏一个城市，这是已知取舍，不在本设计里解决。

## 数据来源

`https://ipapi.co/json/`——HTTPS、免密钥、单次请求返回 `city`、`latitude`、`longitude`、`timezone`（IANA 时区 id）。选它是因为字段形状跟项目已有的 Open-Meteo 集成很像（一次 JSON 请求拿到所有需要的字段），且是 HTTPS（`targetSdk 35` 默认禁止明文 HTTP，项目也没有开 `usesCleartextTraffic`，所以候选服务必须原生支持 HTTPS 免密钥）。

⚠️ 明确标注：`ipapi.co` 是一个外部服务选型，项目内没有任何既有证据或先例验证过它——不是 PICO Spatial SDK 事实，不经 `pico-dev-knowledge` 核实。它的免费额度、可用性由服务方决定，可能变化；如果后续验证发现不可用，替换成同形状的另一个 HTTPS 免密钥服务（如 geojs.io）即是纯 `LocationRepository` 内部实现细节,不影响本设计其余部分。

## 架构

新增 `tech.illusion.spaceclock.location` 包，形状对齐现有 `weather` 包：

### `LocationRepository`

```kotlin
class LocationRepository(private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun locate(): IpLocation?
}

data class IpLocation(val cityName: String, val zoneId: String, val lat: Float, val lon: Float)
```

- 单一职责：发一次 HTTP GET，解析 JSON，返回 `IpLocation?`。
- **永不向调用方抛异常**——网络/超时/JSON 解析失败一律收敛成 `null`，跟 `WeatherRepository.fetch()` 被 `snapshot()` 用 `runCatching` 包起来那条"天气挂了不该让时钟挂了"的契约保持同一风格（这里更进一步，直接在 repository 内部吞掉，因为这是一次性用户触发的请求，没有旧缓存可退回，`null` 就是唯一的失败态）。
- 不做磁盘缓存/内存缓存——这层完全无状态，是否持久化由调用方（`ClockVolume`）决定。
- JSON → `IpLocation` 的映射拆成一个独立的纯函数（同 `WeatherRepository` 里 `beaufortScale`/`toHourMinute` 的做法），供单元测试直接喂样例 JSON。

### `City` 新增字段

```kotlin
data class City(
    val name: String,
    val zoneId: String,
    val lat: Float,
    val lon: Float,
    val displayName: String? = null,
)
```

- 默认值 `null`，`BUILTIN_CITIES` 的 30 个条目不受影响。
- 只有"当前位置"这一个合成条目会用到它：装真实解析出来的城市名（如"上海"），用于 UI 副文本展示。

### 命名与去重

- 合成的 `City` 其 `name` **固定字面量 `"当前位置"`**，不用解析出来的真实城市名——这样它永远不会跟任何 `BUILTIN_CITIES` 条目撞名（撞名会破坏现有以 `name` 作为唯一键的去重逻辑和 `weatherByCity` 的 map key)。
- 真实城市名装进 `displayName`，只用于展示，不参与任何身份判定。

## 数据持久化

`ClockStore` 新增一个字段：

```kotlin
var currentLocationCity: City?   // null = 从未成功定位过
```

用现有的 `sep`（Unit Separator）分隔符序列化 `name|zoneId|lat|lon|displayName` 五段，仿照 `stopwatchSnapshot` 的"不存在时整体返回 null"写法。

`ClockVolume` 里现有的：

```kotlin
val selectedCities = remember(selectedCityNames) {
    selectedCityNames.mapNotNull { name -> BUILTIN_CITIES.find { it.name == name } }
}
```

改成：

```kotlin
val selectedCities = remember(selectedCityNames, currentLocationCity) {
    selectedCityNames.mapNotNull { name ->
        if (name == CURRENT_LOCATION_CITY_NAME) currentLocationCity
        else BUILTIN_CITIES.find { it.name == name }
    }
}
```

其余下游消费点（`weatherByCity`、世界时钟行、"设为本地"）不变——它们只认 `List<City>`，不关心某个 `City` 是从固定表来的还是定位来的。

天气缓存键 `"%.2f,%.2f".format(city.lat, city.lon)` 天然随经纬度变化，"重新定位"更新坐标后会自动命中一个新 key，`WeatherRepository` 不需要任何改动。

## UI 改动

### `CityPicker`（`WorldPage.kt`）

- 列表顶部新增一行/按钮"使用当前位置"，仅当 `CURRENT_LOCATION_CITY_NAME` 不在 `alreadyAdded` 时显示（复用现有 `alreadyAdded` 过滤机制）。
- 点击后：调用 `LocationRepository.locate()`（挂起）。
  - 成功：合成 `City(CURRENT_LOCATION_CITY_NAME, zoneId, lat, lon, displayName = cityName)`，走跟选内置城市完全一样的 `onAddCity` 回调（加入 `selectedCityNames`、关闭 picker）。
  - 失败：picker 内展示一行提示"定位失败，请检查网络后重试"，picker 不关闭，按钮可再次点击。

### `WorldClockRow`

- 当 `city.name == CURRENT_LOCATION_CITY_NAME` 时：
  - 主文本改为 `"${city.name}" + (city.displayName?.let { " · $it" } ?: "")`，即"当前位置 · 上海"。
  - 在现有"设为本地"行内动作旁边新增一个"重新定位"动作。点击后重新调用 `locate()`，成功则原地覆盖 `ClockStore.currentLocationCity`（`name` 不变，`zoneId`/`lat`/`lon`/`displayName` 更新），失败则该次点击无副作用（保留旧坐标，可再次尝试）。
  - 这个动作只服务这一个特殊条目，不引入通用的城市编辑/删除功能。

## 错误处理小结

| 场景 | 行为 |
|---|---|
| `locate()` 网络/超时/DNS 失败 | 返回 `null`，picker/行内动作显示重试提示，不崩溃 |
| `locate()` 返回的 JSON 缺字段/格式不对 | 同上，视为失败 |
| 从未成功定位过 | `currentLocationCity == null`，`CityPicker` 里"使用当前位置"按钮正常可点，`selectedCities` 里如果 `selectedCityNames` 意外含有 `"当前位置"`（理论上不会，因为只有 locate 成功才会走 `onAddCity`）也会被 `mapNotNull` 安全丢弃 |

## 测试

新增 `LocationRepositoryTest.kt`，镜像 `WeatherRepositoryTest.kt` 的风格：对 JSON→`IpLocation` 的纯映射函数喂样例数据，覆盖：成功解析、字段缺失、字段类型不对三种样例。网络请求本身不做真实集成测试（跟 `WeatherRepository.fetch()` 现状一致）。

## 范围边界（明确不做）

- 不新增 Android 定位权限。
- 不做通用的城市删除/编辑功能。
- 不做定期自动重新定位。
- 不追求设备级定位精度。
