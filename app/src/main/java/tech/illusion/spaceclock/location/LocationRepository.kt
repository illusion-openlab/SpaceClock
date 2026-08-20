package tech.illusion.spaceclock.location

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
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
    val cityName = json.getString("city")
    val zoneId = json.getString("timezone")
    val lat = json.getDouble("latitude")
    val lon = json.getDouble("longitude")
    if (cityName.isBlank()) return@runCatching null
    if (zoneId !in ZoneId.getAvailableZoneIds()) return@runCatching null
    if (!lat.isFinite() || lat !in -90.0..90.0) return@runCatching null
    if (!lon.isFinite() || lon !in -180.0..180.0) return@runCatching null
    IpLocation(cityName = cityName, zoneId = zoneId, lat = lat.toFloat(), lon = lon.toFloat())
}.getOrNull()
