# 当前位置天气（IP 地理定位）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在世界时钟页通过一次 IP 地理定位，把自己的真实所在地作为一座"当前位置"城市加入城市列表，复用现有的天气/世界时钟/设为本地机制，不申请 Android 定位权限。

**Architecture:** 新增 `tech.illusion.spaceclock.location` 包（`LocationRepository`，一次性、无状态、永不抛异常，形状对齐现有 `weather` 包）。`City` 增加一个可选 `displayName` 字段承载真实解析出的城市名。合成城市的 `name` 固定为字面量 `"当前位置"`（`CURRENT_LOCATION_CITY_NAME`），避免跟任何内置城市撞名。`ClockStore` 新增一个持久化字段存这份定位结果，`ClockVolume` 的 `selectedCities` 解析多一个分支认出这个特殊名字。UI 侧在 `CityPicker` 加"使用当前位置"选项，在 `WorldClockRow` 给这一行加"重新定位"动作。

**Tech Stack:** Kotlin, Jetpack Compose（SpatialUI `com.pico.spatial.ui.design.*` + `PicoTheme`），`HttpURLConnection` + `org.json.JSONObject`（跟现有 `WeatherRepository` 同一套，不引入新依赖），JUnit4（现有测试框架，无 Robolectric，`app/src/test` 下的测试不能碰真实 `Context`）。

## Global Constraints

- 不申请 `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`，也不修改 `AndroidManifest.xml`——纯 HTTPS 请求，`INTERNET` 权限已经声明过。
- 定位服务用 `https://ipapi.co/json/`（HTTPS、免密钥）——`targetSdk 35` 默认禁止明文 HTTP，且项目未开 `usesCleartextTraffic`，候选服务必须原生支持 HTTPS。
- `LocationRepository` 永不向调用方抛异常：网络/超时/JSON 解析失败一律收敛成 `null`（跟 `WeatherRepository.fetch()` 的契约同款）。
- 合成城市的 `City.name` 固定为字面量 `"当前位置"`（常量名 `CURRENT_LOCATION_CITY_NAME`），不用解析出的真实城市名——防止和 `BUILTIN_CITIES` 撞名，破坏现有以 `name` 为唯一键的去重/Map-key 逻辑。真实城市名装进 `City.displayName`，只做展示。
- 不做通用的"删除城市"功能、不做定期自动重新定位——只在用户点击时触发一次网络请求（"使用当前位置" / "重新定位"两个入口）。
- `app/src/test` 没有 Robolectric，不能实例化真实 `Context`/`SharedPreferences`——任何要被单元测试覆盖的逻辑必须拆成不碰 `Context` 的纯函数。

---

### Task 1: `City` 加 `displayName` 字段 + `CURRENT_LOCATION_CITY_NAME` 常量

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/time/City.kt`
- Test: `app/src/test/java/tech/illusion/spaceclock/time/CityTest.kt`

**Interfaces:**
- Produces: `data class City(val name: String, val zoneId: String, val lat: Float, val lon: Float, val displayName: String? = null)`；`const val CURRENT_LOCATION_CITY_NAME = "当前位置"`（`tech.illusion.spaceclock.time` 包）。后续所有任务都依赖这两个符号。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/tech/illusion/spaceclock/time/CityTest.kt`：

```kotlin
package tech.illusion.spaceclock.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CityTest {
    @Test fun `existing four-arg construction still works, displayName defaults to null`() {
        val city = City("北京", "Asia/Shanghai", 39.90f, 116.40f)
        assertNull(city.displayName)
    }

    @Test fun `displayName can be set explicitly`() {
        val city = City(CURRENT_LOCATION_CITY_NAME, "Asia/Shanghai", 31.23f, 121.47f, "上海")
        assertEquals("上海", city.displayName)
    }

    @Test fun `CURRENT_LOCATION_CITY_NAME is the fixed literal`() {
        assertEquals("当前位置", CURRENT_LOCATION_CITY_NAME)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.time.CityTest"`
Expected: FAIL — `City` 目前只有 4 个构造参数，`displayName`/`CURRENT_LOCATION_CITY_NAME` 都不存在，编译报错。

- [ ] **Step 3: 实现**

将 `app/src/main/java/tech/illusion/spaceclock/time/City.kt` 整个替换为：

```kotlin
package tech.illusion.spaceclock.time

/** 一个内置城市条目。经纬度是 Task 4 地球标记点的输入，不是展示用的装饰字段，不能缺。 */
data class City(
    val name: String,
    val zoneId: String,
    val lat: Float,
    val lon: Float,
    /**
     * 只有 IP 定位合成的"当前位置"条目会用它，装真实解析出的城市名（如"上海"），供 UI 展示副文本
     * "当前位置 · 上海"。`BUILTIN_CITIES` 的条目永远是 null——身份判定只认 [name]，不认这个字段。
     */
    val displayName: String? = null,
)

/**
 * IP 地理定位合成的城市固定用这个名字（不用解析出的真实城市名），确保它永远不会跟
 * [BUILTIN_CITIES] 里的任何条目撞名——现有逻辑（去重、`weatherByCity` 的 Map key）都以
 * `City.name` 为唯一键。
 */
const val CURRENT_LOCATION_CITY_NAME = "当前位置"
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.time.CityTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/time/City.kt app/src/test/java/tech/illusion/spaceclock/time/CityTest.kt
git commit -m "feat: add City.displayName and CURRENT_LOCATION_CITY_NAME"
```

---

### Task 2: `LocationRepository`（IP 地理定位）+ 单元测试

**Files:**
- Create: `app/src/main/java/tech/illusion/spaceclock/location/LocationRepository.kt`
- Test: `app/src/test/java/tech/illusion/spaceclock/location/LocationRepositoryTest.kt`

**Interfaces:**
- Consumes: 无（独立新模块，不依赖 Task 1 之外的任何东西）。
- Produces: `data class IpLocation(val cityName: String, val zoneId: String, val lat: Float, val lon: Float)`；`class LocationRepository(io: CoroutineDispatcher = Dispatchers.IO) { suspend fun locate(): IpLocation? }`；`internal fun parseIpLocation(body: String): IpLocation?`（纯函数，供测试和 `locate()` 内部共用）。Task 6（ClockVolume 接线）会用到 `LocationRepository().locate()` 和 `IpLocation` 的三个字段。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/tech/illusion/spaceclock/location/LocationRepositoryTest.kt`：

```kotlin
package tech.illusion.spaceclock.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测 JSON -> [IpLocation] 的纯映射函数 [parseIpLocation]，不发真实网络请求——
 * 跟 `WeatherRepositoryTest` 只测 `beaufortScale` 纯函数的现状一致。
 */
class LocationRepositoryTest {
    @Test fun `parses a well-formed ipapi response`() {
        val json = """
            {"ip":"1.2.3.4","city":"Shanghai","region":"Shanghai","country_name":"China",
             "latitude":31.2304,"longitude":121.4737,"timezone":"Asia/Shanghai"}
        """.trimIndent()
        assertEquals(
            IpLocation(cityName = "Shanghai", zoneId = "Asia/Shanghai", lat = 31.2304f, lon = 121.4737f),
            parseIpLocation(json),
        )
    }

    @Test fun `missing city field returns null`() {
        val json = """{"latitude":31.2304,"longitude":121.4737,"timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `error response returns null`() {
        // ipapi.co 限流/出错时返回 {"error": true, "reason": "...", "message": "..."}，没有城市字段。
        val json = """{"error":true,"reason":"RateLimited","message":"Too many requests"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `malformed json returns null`() {
        assertNull(parseIpLocation("not json"))
    }

    @Test fun `wrong field type returns null`() {
        val json = """{"city":"Shanghai","latitude":"not-a-number","longitude":121.4737,"timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.location.LocationRepositoryTest"`
Expected: FAIL — `tech.illusion.spaceclock.location` 包和 `parseIpLocation`/`IpLocation` 都还不存在，编译报错。

- [ ] **Step 3: 实现**

创建 `app/src/main/java/tech/illusion/spaceclock/location/LocationRepository.kt`：

```kotlin
package tech.illusion.spaceclock.location

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "LocationRepository"
private const val IP_GEOLOCATION_URL = "https://ipapi.co/json/"

/** 一次 IP 地理定位的结果。[zoneId] 是 IANA 时区 id（如 "Asia/Shanghai"），来自服务方解析。 */
data class IpLocation(val cityName: String, val zoneId: String, val lat: Float, val lon: Float)

/**
 * 通过公网 IP 解析大致所在城市——不申请 Android 定位权限，不依赖设备 GPS/网络位置提供者
 * （PICO 头显是否具备可靠定位硬件未经验证）。城市级精度，出口 IP/代理可能偏一个城市，
 * 这是已知取舍（设计文档"非目标"）。
 *
 * 只在调用方主动触发时打一次请求，不做内部缓存/持久化——是否记住结果、何时重新定位
 * 由调用方（[ClockVolume]）决定。永不向调用方抛异常：网络/超时/JSON 解析失败一律收敛成 null，
 * 跟 `WeatherRepository.fetch()` 的"天气挂了不该让时钟挂了"同一契约。
 */
class LocationRepository(private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun locate(): IpLocation? = withContext(io) {
        runCatching { fetch() }.getOrNull()
    }

    private fun fetch(): IpLocation? {
        val connection = (URL(IP_GEOLOCATION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        val body: String
        try {
            val statusCode = connection.responseCode
            Log.i(TAG, "HTTP-STATUS code=$statusCode")
            if (statusCode != HttpURLConnection.HTTP_OK) return null
            body = connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        return parseIpLocation(body)
    }
}

/**
 * ipapi.co 的响应体 -> [IpLocation]。字段缺失、类型不对、服务返回错误体
 * （限流/出错时的 `"error": true`）都收敛成 null，不抛异常——供 [LocationRepository.fetch]
 * 直接用，也供单元测试直接喂样例 JSON，不用真的发网络请求。
 */
internal fun parseIpLocation(body: String): IpLocation? = runCatching {
    val json = JSONObject(body)
    if (json.optBoolean("error", false)) return@runCatching null
    IpLocation(
        cityName = json.getString("city"),
        zoneId = json.getString("timezone"),
        lat = json.getDouble("latitude").toFloat(),
        lon = json.getDouble("longitude").toFloat(),
    )
}.getOrNull()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.location.LocationRepositoryTest"`
Expected: PASS（5 个用例全绿）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/location/LocationRepository.kt app/src/test/java/tech/illusion/spaceclock/location/LocationRepositoryTest.kt
git commit -m "feat: add LocationRepository for IP-based geolocation"
```

---

### Task 3: `ClockStore.currentLocationCity` 持久化

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt`
- Test: `app/src/test/java/tech/illusion/spaceclock/store/ClockStoreSerializationTest.kt`

**Interfaces:**
- Consumes: `City`（Task 1，含 `displayName`）。
- Produces: `ClockStore.currentLocationCity: City?`（get/set，null = 从未成功定位过）；内部纯函数 `internal fun serializeCurrentLocationCity(city: City?): String?` / `internal fun parseCurrentLocationCity(raw: String?): City?`，供 Task 6 间接使用（通过 `store.currentLocationCity`），供本任务测试直接调用。

**为什么拆成纯函数：** `app/src/test` 没有 Robolectric，不能实例化真实 `Context`/`SharedPreferences`（这也是项目里至今没有 `ClockStoreTest` 的原因）。把"City ↔ 字符串"的编解码逻辑拆成不碰 `Context` 的顶层函数，这部分才能被单元测试覆盖；`prefs.getString`/`prefs.edit()` 那两行胶水代码留在 `Context`-绑定的 getter/setter 里，不测（跟 `stopwatchSnapshot` 现有的其他字段一个待遇）。

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/tech/illusion/spaceclock/store/ClockStoreSerializationTest.kt`：

```kotlin
package tech.illusion.spaceclock.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceclock.time.City

class ClockStoreSerializationTest {
    @Test fun `round-trips a city with displayName`() {
        val city = City("当前位置", "Asia/Shanghai", 31.23f, 121.47f, "上海")
        val raw = serializeCurrentLocationCity(city)
        assertEquals(city, parseCurrentLocationCity(raw))
    }

    @Test fun `serializing null returns null`() {
        assertNull(serializeCurrentLocationCity(null))
    }

    @Test fun `parsing null returns null`() {
        assertNull(parseCurrentLocationCity(null))
    }

    @Test fun `parsing a malformed field count returns null`() {
        assertNull(parseCurrentLocationCity("onlytwo"))
    }

    @Test fun `parsing a non-numeric lat returns null`() {
        assertNull(parseCurrentLocationCity("当前位置Asia/Shanghainot-a-number121.47上海"))
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.store.ClockStoreSerializationTest"`
Expected: FAIL — `serializeCurrentLocationCity`/`parseCurrentLocationCity` 还不存在，编译报错。

- [ ] **Step 3: 实现**

在 `app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt` 顶部加 import，并在文件末尾（`class ClockStore` 之外、文件级作用域）加两个纯函数，在类内部加一个字段。

在文件顶部 import 块追加：

```kotlin
import tech.illusion.spaceclock.time.City
```

在 `class ClockStore { ... }` 内部，`selectedCityNames` 字段后面插入新字段：

```kotlin
    /**
     * IP 定位合成的"当前位置"城市；null 表示从未成功定位过。跟 [selectedCityNames]/[homeCityName]
     * 一样是纯值持久化，不是"是否已经问过定位权限"那类一次性标记——这里存的是最近一次成功定位的结果，
     * 每次"使用当前位置"/"重新定位"成功都会覆盖它。
     */
    var currentLocationCity: City?
        get() = parseCurrentLocationCity(prefs.getString(KEY_CURRENT_LOCATION_CITY, null))
        set(value) = prefs.edit()
            .putString(KEY_CURRENT_LOCATION_CITY, serializeCurrentLocationCity(value))
            .apply()
```

在 `private companion object { ... }` 的键列表里追加一行：

```kotlin
        const val KEY_CURRENT_LOCATION_CITY = "current_location_city"
```

在文件末尾（`class ClockStore` 闭合大括号之后）追加：

```kotlin
/** 跟 [ClockStore] 实例里的 `sep`（`''`）同一个字符，只是这里要保持纯函数、不碰 Context。 */
private const val CURRENT_LOCATION_FIELD_SEP = ''

/** [City] -> 持久化字符串；`null` 输入原样返回 `null`（表示"清空/从未定位过"）。 */
internal fun serializeCurrentLocationCity(city: City?): String? {
    if (city == null) return null
    return listOf(city.name, city.zoneId, city.lat, city.lon, city.displayName ?: "")
        .joinToString(CURRENT_LOCATION_FIELD_SEP.toString())
}

/** 持久化字符串 -> [City]；字段数不对、数值字段解析失败都返回 `null`，不抛异常。 */
internal fun parseCurrentLocationCity(raw: String?): City? {
    if (raw == null) return null
    val parts = raw.split(CURRENT_LOCATION_FIELD_SEP)
    if (parts.size != 5) return null
    return runCatching {
        City(
            name = parts[0],
            zoneId = parts[1],
            lat = parts[2].toFloat(),
            lon = parts[3].toFloat(),
            displayName = parts[4].ifEmpty { null },
        )
    }.getOrNull()
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "tech.illusion.spaceclock.store.ClockStoreSerializationTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/store/ClockStore.kt app/src/test/java/tech/illusion/spaceclock/store/ClockStoreSerializationTest.kt
git commit -m "feat: persist current-location city in ClockStore"
```

---

### Task 4: `CityPicker` 加"使用当前位置"选项

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/content/pages/WorldPage.kt`

**Interfaces:**
- Consumes: `CURRENT_LOCATION_CITY_NAME`（Task 1，`tech.illusion.spaceclock.time`）；`City`（Task 1）。
- Produces: `CityPicker` 新增必需参数 `locateCurrentPosition: suspend () -> City?`；`WorldPage` 新增参数 `locateCurrentPosition: suspend () -> City? = { null }`（**带默认值**，保证本任务落地后 `ClockVolume` 现有调用点不用改也能编译——Task 6 才会传真实实现覆盖默认值）。

本任务不接真实网络请求——`ClockVolume` 还没到 Task 6，先用默认的 `{ null }` 占位跑通 UI 分支（点击后必然走"定位失败"文案），保证每个任务都能独立编译。真实联调在 Task 6 之后。

- [ ] **Step 1: 加 imports**

在 `app/src/main/java/tech/illusion/spaceclock/content/pages/WorldPage.kt` 顶部 import 块追加：

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import tech.illusion.spaceclock.time.CURRENT_LOCATION_CITY_NAME
```

- [ ] **Step 2: `WorldPage` 签名加新参数并透传**

把 `WorldPage` 函数签名（当前第 68-76 行）：

```kotlin
@Composable
fun WorldPage(
    cities: List<City>,
    homeCityName: String,
    focusedCityName: String?,
    weatherByCity: Map<String, WeatherSnapshot?>,
    onAddCity: (City) -> Unit,
    onSetHome: (City) -> Unit,
    onSelectCity: (City) -> Unit,
) {
```

改成：

```kotlin
@Composable
fun WorldPage(
    cities: List<City>,
    homeCityName: String,
    focusedCityName: String?,
    weatherByCity: Map<String, WeatherSnapshot?>,
    onAddCity: (City) -> Unit,
    onSetHome: (City) -> Unit,
    onSelectCity: (City) -> Unit,
    locateCurrentPosition: suspend () -> City? = { null },
) {
```

把函数体里调用 `CityPicker` 的地方（当前第 90-98 行）：

```kotlin
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
```

改成：

```kotlin
    if (showPicker) {
        CityPicker(
            alreadyAdded = remember(cities) { cities.map { it.name }.toSet() },
            onPick = { city ->
                onAddCity(city)
                showPicker = false
            },
            onCancel = { showPicker = false },
            locateCurrentPosition = locateCurrentPosition,
        )
        return
    }
```

- [ ] **Step 3: `CityPicker` 实现"使用当前位置"**

把整个 `CityPicker` 函数（当前第 275-313 行）替换为：

```kotlin
/**
 * 同页内的城市选择子视图（不开新窗口）——从 [BUILTIN_CITIES] 里挑一个还没加过的城市，
 * 或者用 [locateCurrentPosition] 联网定位出一座"当前位置"（[CURRENT_LOCATION_CITY_NAME]）。
 * 挑中/定位成功后立刻返回主列表；已全部添加则给出说明文案而不是空白列表。
 */
@Composable
private fun CityPicker(
    alreadyAdded: Set<String>,
    onPick: (City) -> Unit,
    onCancel: () -> Unit,
    locateCurrentPosition: suspend () -> City?,
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography
    val available = remember(alreadyAdded) { BUILTIN_CITIES.filter { it.name !in alreadyAdded } }
    val showCurrentLocationOption = CURRENT_LOCATION_CITY_NAME !in alreadyAdded
    var locating by remember { mutableStateOf(false) }
    var locateFailed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("选择城市", style = typo.titleLarge, color = scheme.labelPrimary)
            Link(onClick = onCancel) { Text("取消") }
        }
        Spacer(Modifier.height(8.dp))

        if (showCurrentLocationOption) {
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !locating) {
                    locating = true
                    locateFailed = false
                    coroutineScope.launch {
                        val city = locateCurrentPosition()
                        locating = false
                        if (city != null) onPick(city) else locateFailed = true
                    }
                },
                headlineContent = {
                    Text(
                        if (locating) "定位中…" else "使用当前位置",
                        style = typo.bodyLarge,
                        color = scheme.labelPrimary,
                    )
                },
                supportingContent = {
                    val subtitle = when {
                        locating -> "正在通过网络定位…"
                        locateFailed -> "定位失败，请检查网络后重试"
                        else -> "根据网络 IP 推测所在城市，不使用设备定位权限"
                    }
                    Text(subtitle, style = typo.bodySmall, color = scheme.labelTertiary)
                },
            )
            HorizontalDivider(color = scheme.dividerLine)
        }

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
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`ClockVolume.kt` 里现有的 `WorldPage(...)` 调用点没有传 `locateCurrentPosition`，靠新参数的默认值 `{ null }` 编译通过）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/content/pages/WorldPage.kt
git commit -m "feat: add current-location option to CityPicker"
```

---

### Task 5: `WorldClockRow` 加"重新定位"动作 + 副文本

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/content/pages/WorldPage.kt`

**Interfaces:**
- Consumes: `CURRENT_LOCATION_CITY_NAME`（Task 1，已在 Task 4 import 过，本任务复用同一个 import）。
- Produces: `WorldClockRow` 新增可选参数 `onRelocate: (() -> Unit)? = null`；`WorldPage` 新增参数 `onRelocateCurrentLocation: () -> Unit = {}`（**带默认值**，理由同 Task 4——Task 6 才会传真实实现）。

- [ ] **Step 1: `WorldPage` 签名加新参数**

把 Task 4 改完后的 `WorldPage` 签名：

```kotlin
@Composable
fun WorldPage(
    cities: List<City>,
    homeCityName: String,
    focusedCityName: String?,
    weatherByCity: Map<String, WeatherSnapshot?>,
    onAddCity: (City) -> Unit,
    onSetHome: (City) -> Unit,
    onSelectCity: (City) -> Unit,
    locateCurrentPosition: suspend () -> City? = { null },
) {
```

改成：

```kotlin
@Composable
fun WorldPage(
    cities: List<City>,
    homeCityName: String,
    focusedCityName: String?,
    weatherByCity: Map<String, WeatherSnapshot?>,
    onAddCity: (City) -> Unit,
    onSetHome: (City) -> Unit,
    onSelectCity: (City) -> Unit,
    locateCurrentPosition: suspend () -> City? = { null },
    onRelocateCurrentLocation: () -> Unit = {},
) {
```

- [ ] **Step 2: `WorldClockRow` 调用点传入 `onRelocate`**

把 `LazyColumn` 里调用 `WorldClockRow` 的地方（当前第 130-145 行）：

```kotlin
                    items(items = cities, key = { it.name }) { city ->
                        WorldClockRow(
                            city = city,
                            now = now,
                            localZoneId = localZoneId,
                            isHome = city.name == homeCityName,
                            isPinned = city.name == focusedCityName,
                            weather = weatherByCity[city.name],
                            onSelect = { onSelectCity(city) },
                            onSetHome = { onSetHome(city) },
                            rowModifier = Modifier.onGloballyPositioned { c ->
                                debugLog("WorldLayout") {
                                    "row city=${city.name} h=${c.size.height} y=${c.positionInRoot().y}"
                                }
                            },
                        )
                        HorizontalDivider(color = scheme.dividerLine)
                    }
```

改成：

```kotlin
                    items(items = cities, key = { it.name }) { city ->
                        WorldClockRow(
                            city = city,
                            now = now,
                            localZoneId = localZoneId,
                            isHome = city.name == homeCityName,
                            isPinned = city.name == focusedCityName,
                            weather = weatherByCity[city.name],
                            onSelect = { onSelectCity(city) },
                            onSetHome = { onSetHome(city) },
                            onRelocate = if (city.name == CURRENT_LOCATION_CITY_NAME) onRelocateCurrentLocation else null,
                            rowModifier = Modifier.onGloballyPositioned { c ->
                                debugLog("WorldLayout") {
                                    "row city=${city.name} h=${c.size.height} y=${c.positionInRoot().y}"
                                }
                            },
                        )
                        HorizontalDivider(color = scheme.dividerLine)
                    }
```

- [ ] **Step 3: `WorldClockRow` 签名 + 渲染改动**

把 `WorldClockRow` 签名（当前第 182-193 行）：

```kotlin
@Composable
private fun WorldClockRow(
    city: City,
    now: Instant,
    localZoneId: String,
    isHome: Boolean,
    isPinned: Boolean,
    weather: WeatherSnapshot?,
    onSelect: () -> Unit,
    onSetHome: () -> Unit,
    rowModifier: Modifier = Modifier,
) {
```

改成：

```kotlin
@Composable
private fun WorldClockRow(
    city: City,
    now: Instant,
    localZoneId: String,
    isHome: Boolean,
    isPinned: Boolean,
    weather: WeatherSnapshot?,
    onSelect: () -> Unit,
    onSetHome: () -> Unit,
    onRelocate: (() -> Unit)? = null,
    rowModifier: Modifier = Modifier,
) {
```

把展示城市名的那一行（当前第 227-234 行）：

```kotlin
                    Text(
                        city.name,
                        style = typo.bodyLarge,
                        color = scheme.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
```

改成：

```kotlin
                    Text(
                        // "当前位置"条目额外带一段真实城市名副文本，如"当前位置 · 上海"；
                        // 内置城市 displayName 恒为 null，文案不变。
                        city.displayName?.let { "${city.name} · $it" } ?: city.name,
                        style = typo.bodyLarge,
                        color = scheme.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
```

把"设为本地"那一段（当前第 258-264 行）：

```kotlin
                    if (isHome) {
                        Text("本地", style = typo.labelSmall, color = scheme.interaction)
                    } else {
                        Link(onClick = onSetHome) {
                            Text("设为本地", style = typo.labelSmall)
                        }
                    }
```

改成：

```kotlin
                    if (isHome) {
                        Text("本地", style = typo.labelSmall, color = scheme.interaction)
                    } else {
                        Link(onClick = onSetHome) {
                            Text("设为本地", style = typo.labelSmall)
                        }
                    }
                    // 只有"当前位置"这一行会传非 null 的 onRelocate（WorldPage 按 city.name 判断）——
                    // 内置城市经纬度永远不变，没有"重新定位"的意义。
                    if (onRelocate != null) {
                        Link(onClick = onRelocate) {
                            Text("重新定位", style = typo.labelSmall)
                        }
                    }
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`onRelocateCurrentLocation` 有默认值 `{}`，`ClockVolume.kt` 现有调用点不用改）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/content/pages/WorldPage.kt
git commit -m "feat: add relocate action and display-name subtitle to WorldClockRow"
```

---

### Task 6: `ClockVolume` 接线——真正驱动定位/重定位

**Files:**
- Modify: `app/src/main/java/tech/illusion/spaceclock/content/ClockVolume.kt`

**Interfaces:**
- Consumes: `LocationRepository`/`IpLocation`（Task 2）；`ClockStore.currentLocationCity`（Task 3）；`City.displayName`/`CURRENT_LOCATION_CITY_NAME`（Task 1）；`WorldPage` 的 `locateCurrentPosition`/`onRelocateCurrentLocation` 参数（Task 4/5）。
- Produces: 无新公开符号——这是最终把前面几层接起来的任务。

**关键设计后果（不用额外写代码，但要知道为什么会生效）：** `selectedCities` 现在 `remember(selectedCityNames, currentLocationCity)`。`currentLocationCity` 是 `City`（data class，结构相等），"重新定位"更新它之后 `selectedCities` 会变成一个新列表（哪怕只是"当前位置"那一项经纬度变了）。下面第 102 行的 `LaunchedEffect(selectedCities)` 以这个列表做 key，值变化就会取消重启协程、立刻重新 `snapshotAll(selectedCities)`——定位成功后天气会自动跟着刷新，不需要专门为"重新定位"再手写一次天气拉取。

- [ ] **Step 1: 加 imports**

在 `app/src/main/java/tech/illusion/spaceclock/content/ClockVolume.kt` 顶部 import 块追加：

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import tech.illusion.spaceclock.location.LocationRepository
import tech.illusion.spaceclock.time.CURRENT_LOCATION_CITY_NAME
```

- [ ] **Step 2: 加状态 + 改 `selectedCities` 分支**

把现有的（当前第 88-92 行）：

```kotlin
    var selectedCityNames by remember { mutableStateOf(store.selectedCityNames) }
    var homeCityName by remember { mutableStateOf(store.homeCityName) }
    val selectedCities = remember(selectedCityNames) {
        selectedCityNames.mapNotNull { name -> BUILTIN_CITIES.find { it.name == name } }
    }
```

改成：

```kotlin
    var selectedCityNames by remember { mutableStateOf(store.selectedCityNames) }
    var homeCityName by remember { mutableStateOf(store.homeCityName) }
    // IP 定位合成的"当前位置"城市不在 BUILTIN_CITIES 里，selectedCityNames 命中这个特殊名字时
    // 改从这份状态取，其余名字仍然只认固定表——两条来源互不干扰。
    var currentLocationCity by remember { mutableStateOf(store.currentLocationCity) }
    val selectedCities = remember(selectedCityNames, currentLocationCity) {
        selectedCityNames.mapNotNull { name ->
            if (name == CURRENT_LOCATION_CITY_NAME) currentLocationCity
            else BUILTIN_CITIES.find { it.name == name }
        }
    }
```

- [ ] **Step 3: 加定位仓库实例 + 协程作用域 + 两个回调**

紧接着（当前第 93-94 行 `focusedCity` 声明之后、天气仓库声明之前）插入：

```kotlin
    // 定位（本设计）：跟 weatherRepository 同样的"外部 IO 入口集中在这一层"理由，只持有一份实例。
    // 不做自动/周期性定位——只在用户点"使用当前位置"/"重新定位"时才发起一次网络请求。
    val locationRepository = remember { LocationRepository() }
    val coroutineScope = rememberCoroutineScope()

    // 定位成功后落盘 + 更新内存态；两个 UI 入口（CityPicker 的"使用当前位置"、
    // WorldClockRow 的"重新定位"）共用这一份逻辑，只是触发方式不同（前者要把结果交给
    // onAddCity 加入城市列表，后者只是原地覆盖坐标，城市列表本来就已经包含它）。
    val locateCurrentPosition: suspend () -> City? = {
        locationRepository.locate()?.let { located ->
            City(
                name = CURRENT_LOCATION_CITY_NAME,
                zoneId = located.zoneId,
                lat = located.lat,
                lon = located.lon,
                displayName = located.cityName,
            ).also { city ->
                store.currentLocationCity = city
                currentLocationCity = city
            }
        }
    }
    val onRelocateCurrentLocation: () -> Unit = {
        coroutineScope.launch { locateCurrentPosition() }
    }
```

- [ ] **Step 4: 把两个回调传给 `WorldPage`**

把现有的 `ClockPage.WORLD -> WorldPage(...)` 调用（当前第 265-280 行）：

```kotlin
                        ClockPage.WORLD -> WorldPage(
                            cities = selectedCities,
                            homeCityName = homeCityName,
                            focusedCityName = focusedCity?.name,
                            weatherByCity = weatherByCity,
                            onAddCity = { city ->
                                val updated = selectedCityNames + city.name
                                store.selectedCityNames = updated
                                selectedCityNames = updated
                            },
                            onSetHome = { city ->
                                store.homeCityName = city.name
                                homeCityName = city.name
                            },
                            onSelectCity = { focusedCity = it },
                        )
```

改成：

```kotlin
                        ClockPage.WORLD -> WorldPage(
                            cities = selectedCities,
                            homeCityName = homeCityName,
                            focusedCityName = focusedCity?.name,
                            weatherByCity = weatherByCity,
                            onAddCity = { city ->
                                val updated = selectedCityNames + city.name
                                store.selectedCityNames = updated
                                selectedCityNames = updated
                            },
                            onSetHome = { city ->
                                store.homeCityName = city.name
                                homeCityName = city.name
                            },
                            onSelectCity = { focusedCity = it },
                            locateCurrentPosition = locateCurrentPosition,
                            onRelocateCurrentLocation = onRelocateCurrentLocation,
                        )
```

- [ ] **Step 5: 编译验证**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 全量单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（含 Task 1/2/3 的全部新用例，以及既有的 `WeatherRepositoryTest`/`WmoCodesTest`）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/tech/illusion/spaceclock/content/ClockVolume.kt
git commit -m "feat: wire current-location IP geolocation into ClockVolume"
```

---

### Task 7: 构建 + 上机验证

**Files:** 无代码改动——这是端到端验证任务。

**Interfaces:** 无。

- [ ] **Step 1: 完整 Debug 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL，产出 `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 2: 环境预检（如本次会话尚未做过健康的环境检查）**

按 `pico-env-doctor` 技能跑一次环境检查（PICO Spatial 插件的环境依赖任务前置门禁）——确认 `pico-cli` 可用、模拟器/设备可达。如果本次 host session 内已经有新鲜的健康检查结果，直接复用，不重复跑。

- [ ] **Step 3: 安装并启动，跑一遍新流程**

按 `spatial-emulator-usage` 技能把 Step 1 产出的 APK 装到模拟器/设备并启动，然后手动走一遍：

1. 切到世界时钟页 → 点"＋添加城市" → 确认列表顶部出现"使用当前位置"一行。
2. 点它 → 确认出现"定位中…"，随后要么成功返回主列表（新条目名字是"当前位置 · <真实城市名>"），要么显示"定位失败，请检查网络后重试"（断网场景下应该走这条分支）。
3. 定位成功后，确认该行天气不是"—"（除非该地点 Open-Meteo 本身请求失败）。
4. 点该行的"重新定位"，确认坐标/城市名可以被覆盖更新（同一地点下天气数值可能不变，但至少不报错、不崩溃）。
5. 点该行的"设为本地" → 切到 Home 页，确认状态副行/天气卡跟着显示这条"当前位置"的天气。

- [ ] **Step 4: 采集证据**

按 `spatial-emulator-usage` 技能采集 logcat（重点看 `LocationRepository`/`HTTP-STATUS`/`WeatherRepository`/`WEATHER-SNAPSHOT` 几个 tag）和至少一张成功态截图、一张断网失败态截图（如果能模拟断网）。

- [ ] **Step 5: 提交（如验证过程中发现并修了 bug）**

如果 Step 3 发现了需要改代码的问题，修完后按 Task 1-6 同样的"改动 + 编译验证 + commit"节奏单独提交，不要在这一步一次性堆多个改动。如果没有发现问题，本任务不产生新提交。
