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

/**
 * 2026-08-21：原来用 ipapi.co，上线后在宿主机 curl、模拟器、真机三处都实测到它返回
 * `{"reason":"RateLimited","error":true}`（免费额度用完，不是网络抖动，`curl` 直接确认过
 * 响应体）。换成同形状的 HTTPS 免密钥服务 geojs.io——这是设计文档早就预留的退路（"替换成
 * 另一个 HTTPS 免密钥服务即是纯 LocationRepository 内部实现细节，不影响本设计其余部分"）。
 */
private const val IP_GEOLOCATION_URL = "https://get.geojs.io/v1/ip/geo.json"

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
 * geojs.io 的响应体 -> [IpLocation]。实测响应把 `latitude`/`longitude` 编码成 JSON 字符串
 * （如 `"35.6893"`），不是 JSON number——用 `getString(...).toDouble()` 显式转换，不依赖
 * `org.json` 对字符串数值的隐式类型转换。字段缺失、时区不合法、经纬度超出范围都收敛成 null，
 * 不抛异常——供 [LocationRepository.fetch] 直接用，也供单元测试直接喂样例 JSON，不用真的发
 * 网络请求。
 */
internal fun parseIpLocation(body: String): IpLocation? = runCatching {
    val json = JSONObject(body)
    val cityName = json.getString("city")
    val zoneId = json.getString("timezone")
    val lat = json.getString("latitude").toDouble()
    val lon = json.getString("longitude").toDouble()
    if (cityName.isBlank()) return@runCatching null
    if (zoneId !in ZoneId.getAvailableZoneIds()) return@runCatching null
    if (!lat.isFinite() || lat !in -90.0..90.0) return@runCatching null
    if (!lon.isFinite() || lon !in -180.0..180.0) return@runCatching null
    IpLocation(cityName = cityName, zoneId = zoneId, lat = lat.toFloat(), lon = lon.toFloat())
}.getOrNull()
