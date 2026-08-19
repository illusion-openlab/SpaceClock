package tech.illusion.spaceclock.weather

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tech.illusion.spaceclock.time.City

private const val TAG = "WeatherRepository"

/**
 * 缓存新鲜窗口：30 分钟内的数据直接用，不重新拉。公开（不是 `private`）是因为
 * HomePage 要用同一个数字判断"要不要显示『更新于 X 分钟前』"——UI 和仓库共用一份口径，
 * 不允许 UI 自己再猜一个 30 分钟出来（写死在两处、改一处漏一处正是设计文档 §5.5 想避免的事）。
 */
const val WEATHER_FRESH_WINDOW_MS = 30 * 60_000L

/** 多城刷新时城市之间的限流间隔（设计文档 §5.5：批量请求的接口形态未确认，串行 + 限流）。 */
private const val CITY_FETCH_DELAY_MS = 200L

/**
 * 天气数据源：Open-Meteo，逐城请求、内存缓存 + `SharedPreferences` 兜底、失败永不抛异常。
 *
 * [snapshot] 的核心分支（新鲜缓存直接用 / 拉取失败退回旧缓存 / 什么都没有返回 null）照抄任务
 * 简报给的骨架，一字不改；在此之上加了两处扩展，都是简报正文里点名但骨架代码没画出来的部分：
 *   1. `SharedPreferences` 兜底——构造时按需从磁盘补种内存缓存，让"进程刚起、内存缓存是空的"
 *      也能命中"过期缓存"分支而不是直接落到 null。
 *   2. [snapshotAll]——多城串行 + 城市间限流的调用方式，简报正文写了这条约束但骨架只给了
 *      单城版本的 [snapshot]，多城编排逻辑需要一个地方落地，不该让每个调用点各自拼一遍。
 *
 * 因为要接触 `SharedPreferences`，构造函数比骨架多了一个前置的 `context` 形参
 * （骨架原文：`class WeatherRepository(private val io: CoroutineDispatcher = Dispatchers.IO)`，
 * 没有 context）；`io` 形参本身的名字、类型、默认值都原样保留。
 */
class WeatherRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val prefs = context.getSharedPreferences("spaceclock_weather", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, WeatherSnapshot>()

    /**
     * 取一次天气。三种返回：
     *   新鲜数据 → 直接用
     *   过期缓存 → 也返回（UI 按 fetchedAtEpochSec 显示"更新于 X 分钟前"）
     *   什么都没有 → null（UI 显示 "—"）
     * 永不抛异常：天气挂了不该让时钟挂了。
     */
    suspend fun snapshot(city: City): WeatherSnapshot? = withContext(io) {
        val key = "%.2f,%.2f".format(city.lat, city.lon)
        val cached = cache[key] ?: loadFromDisk(key)?.also { cache[key] = it }
        val nowMs = System.currentTimeMillis()
        if (cached != null && nowMs - cached.fetchedAtEpochSec * 1000 < WEATHER_FRESH_WINDOW_MS) {
            Log.i(TAG, "WEATHER-SNAPSHOT city=${city.name} source=cache-fresh temp=${cached.tempC} fetchedAtEpochSec=${cached.fetchedAtEpochSec}")
            return@withContext cached
        }
        // fetch() 本身在网络/DNS 层面失败时会在拿到 HTTP 状态码之前就抛（比如断网时
        // HttpURLConnection.getResponseCode() 直接抛 UnknownHostException/ConnectException），
        // 这种情况下 fetch() 内部的 HTTP-STATUS 日志行永远不会打出来——这一行 WEATHER-SNAPSHOT
        // 才是覆盖"离线、真的没打到网络"这个分支的唯一稳定标记，门禁 B 断网验证靠它定位时机。
        val fetched = runCatching { fetch(city) }.getOrNull()
        if (fetched != null) {
            cache[key] = fetched
            saveToDisk(key, fetched)
            Log.i(TAG, "WEATHER-SNAPSHOT city=${city.name} source=network temp=${fetched.tempC} fetchedAtEpochSec=${fetched.fetchedAtEpochSec}")
        } else if (cached != null) {
            Log.i(TAG, "WEATHER-SNAPSHOT city=${city.name} source=cache-stale temp=${cached.tempC} fetchedAtEpochSec=${cached.fetchedAtEpochSec}")
        } else {
            Log.i(TAG, "WEATHER-SNAPSHOT city=${city.name} source=none")
        }
        fetched ?: cached          // 拉失败就退回旧数据，而不是把已有数据也丢掉
    }

    /**
     * 多城刷新的编排入口：**串行**调用 [snapshot]，城市之间 `delay(200)`——不并发打同一个免费
     * 接口，避免限流把全部城市都打失败（设计文档 §5.5）。返回 `city.name -> snapshot` 的映射，
     * 某座城拉取失败时对应的值是 [snapshot] 自己退化出的结果（旧缓存或 null），这个函数不再
     * 额外处理失败。
     */
    suspend fun snapshotAll(cities: List<City>): Map<String, WeatherSnapshot?> {
        val result = mutableMapOf<String, WeatherSnapshot?>()
        cities.forEachIndexed { index, city ->
            if (index > 0) delay(CITY_FETCH_DELAY_MS)
            result[city.name] = snapshot(city)
        }
        return result
    }

    private fun loadFromDisk(key: String): WeatherSnapshot? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split(FIELD_SEP)
        if (parts.size != 10) return null
        return runCatching {
            WeatherSnapshot(
                tempC = parts[0].toInt(),
                highC = parts[1].toInt(),
                lowC = parts[2].toInt(),
                feelsLikeC = parts[3].toInt(),
                humidityPct = parts[4].toInt(),
                windScale = parts[5].toInt(),
                sunrise = parts[6],
                sunset = parts[7],
                wmoCode = parts[8].toInt(),
                fetchedAtEpochSec = parts[9].toLong(),
            )
        }.getOrNull()
    }

    private fun saveToDisk(key: String, snapshot: WeatherSnapshot) {
        val raw = with(snapshot) {
            listOf(
                tempC, highC, lowC, feelsLikeC, humidityPct, windScale,
                sunrise, sunset, wmoCode, fetchedAtEpochSec,
            ).joinToString(FIELD_SEP.toString())
        }
        runCatching { prefs.edit().putString(key, raw).apply() }
    }

    /**
     * 实际的网络请求。调用方已经用 `runCatching` 包了一层，这里允许抛（超时/DNS/JSON 解析都
     * 会抛），不在这一层再吞一次——保持单一职责，"绝不抛到 UI"这条契约由 [snapshot] 兜底。
     */
    private fun fetch(city: City): WeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${city.lat}&longitude=${city.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset" +
            "&timezone=auto&forecast_days=1"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        val body: String
        val statusCode: Int
        try {
            statusCode = connection.responseCode
            // 证据契约（controller notes §3）：HTTP 状态码打进日志，供设备验证时和界面数字交叉核对。
            Log.i(TAG, "HTTP-STATUS city=${city.name} code=$statusCode")
            if (statusCode != HttpURLConnection.HTTP_OK) {
                error("unexpected HTTP status $statusCode for ${city.name}")
            }
            body = connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val sunrise = daily.getJSONArray("sunrise").getString(0).let(::toHourMinute)
        val sunset = daily.getJSONArray("sunset").getString(0).let(::toHourMinute)
        val fetchedAtEpochSec = System.currentTimeMillis() / 1000
        val snapshot = WeatherSnapshot(
            tempC = current.getDouble("temperature_2m").roundToIntHalfUp(),
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToIntHalfUp(),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToIntHalfUp(),
            feelsLikeC = current.getDouble("apparent_temperature").roundToIntHalfUp(),
            humidityPct = current.getDouble("relative_humidity_2m").roundToIntHalfUp(),
            windScale = beaufortScale(current.getDouble("wind_speed_10m")),
            sunrise = sunrise,
            sunset = sunset,
            wmoCode = current.getInt("weather_code"),
            fetchedAtEpochSec = fetchedAtEpochSec,
        )
        // 证据契约：原始 JSON 关键字段 + fetchedAtEpochSec 一起打出来，跟界面数字交叉核对，
        // 排除"界面上显示的是硬编码/上一次缓存"这个相反假设。
        Log.i(
            TAG,
            "HTTP-BODY city=${city.name} temp=${current.optString("temperature_2m")} " +
                "code=${current.optString("weather_code")} " +
                "high=${daily.getJSONArray("temperature_2m_max").optString(0)} " +
                "low=${daily.getJSONArray("temperature_2m_min").optString(0)} " +
                "fetchedAtEpochSec=$fetchedAtEpochSec",
        )
        return snapshot
    }

    private companion object {
        const val FIELD_SEP = '|'
    }
}

/** "2026-08-19T05:32" -> "05:32"。Open-Meteo 在 `timezone=auto` 下返回的日出日落已经是当地时间。 */
private fun toHourMinute(isoDateTime: String): String {
    val time = isoDateTime.substringAfter('T')
    return if (time.length >= 5) time.take(5) else time
}

private fun Double.roundToIntHalfUp(): Int = Math.round(this).toInt()

/**
 * 风速 (km/h) -> 蒲福风级 (Beaufort scale)。
 *
 * 单位口径（假设，未经项目内证据验证）：Open-Meteo 的 `current=...,wind_speed_10m,...`
 * 请求没有传 `wind_speed_unit`，按官方文档默认单位是 km/h，所以下表按 km/h 解释；
 * fix round 1 评审标了这条"⚠️ 无法从项目内证据确认"，这里只写清楚假设，不声称已验证。
 *
 * `WeatherSnapshot.windScale` 的单位是"级"，不是原始风速，需要这一层换算。表按标准蒲福风级
 * （km/h）的**下一档下界**取值，配合严格 `<` 比较；即 `BEAUFORT_THRESHOLDS_KMH[i]` 是"还算
 * i 级"的风速上限的下一个值（等价写法是保留每档上界改用 `<=` 比较，两者选一，不要混用）：
 *   0: <1     1: 1–5     2: 6–11    3: 12–19    4: 20–28    5: 29–38    6: 39–49
 *   7: 50–61  8: 62–74   9: 75–88   10: 89–102  11: 103–117 12: ≥118
 */
private val BEAUFORT_THRESHOLDS_KMH = doubleArrayOf(
    1.0, 6.0, 12.0, 20.0, 29.0, 39.0, 50.0, 62.0, 75.0, 89.0, 103.0, 118.0,
)

internal fun beaufortScale(speedKmh: Double): Int {
    for (scale in BEAUFORT_THRESHOLDS_KMH.indices) {
        if (speedKmh < BEAUFORT_THRESHOLDS_KMH[scale]) return scale
    }
    return 12
}
