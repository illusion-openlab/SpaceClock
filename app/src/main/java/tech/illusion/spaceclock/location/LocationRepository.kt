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

/**
 * 2026-08-21：geojs.io 不支持本地化，`city` 字段永远是英文/拼音（如 "Shenzhen"）——用
 * BigDataCloud 的免费、免密钥反向地理编码接口，拿 geojs.io 已经给出的经纬度去查一次中文城市名
 * （`localityLanguage=zh`）。这个接口只接受经纬度作为输入，不能替代 geojs.io 做 IP 定位，
 * 所以是两次请求串联，不是二选一。查不到/查失败时退回 geojs.io 原本的英文/拼音名，不让整次
 * 定位因为这一步失败。
 */
private const val REVERSE_GEOCODE_URL = "https://api.bigdatacloud.net/data/reverse-geocode-client"

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
        val located = fetchIpLocation() ?: return null
        // 反向地理编码失败/查不到中文名都不影响主结果——退回 geojs.io 原本的英文/拼音名，
        // 不能因为这一步的失败让整次定位（经纬度/时区）也跟着报废。
        val chineseName = runCatching { fetchChineseCityName(located.lat, located.lon) }.getOrNull()
        return if (chineseName != null) located.copy(cityName = chineseName) else located
    }

    private fun fetchIpLocation(): IpLocation? {
        val body = httpGet(IP_GEOLOCATION_URL) ?: return null
        return parseIpLocation(body)
    }

    private fun fetchChineseCityName(lat: Float, lon: Float): String? {
        val url = "$REVERSE_GEOCODE_URL?latitude=$lat&longitude=$lon&localityLanguage=zh"
        val body = httpGet(url) ?: return null
        return parseChineseCityName(body)
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
        }
        try {
            val statusCode = connection.responseCode
            Log.i(TAG, "HTTP-STATUS code=$statusCode url=$url")
            if (statusCode != HttpURLConnection.HTTP_OK) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * geojs.io 的响应体 -> [IpLocation]。实测响应把 `latitude`/`longitude` 编码成 JSON 字符串
 * （如 `"35.6893"`），不是 JSON number——用 `getString(...).toDouble()` 显式转换，不依赖
 * `org.json` 对字符串数值的隐式类型转换。字段缺失、时区不合法、经纬度超出范围都收敛成 null，
 * 不抛异常——供 [LocationRepository.fetchIpLocation] 直接用，也供单元测试直接喂样例 JSON，
 * 不用真的发网络请求。
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

/**
 * BigDataCloud 反向地理编码响应体 -> 中文城市名。只取顶层 `city` 字段，空字符串/字段缺失/
 * JSON 解析失败都收敛成 null（不当作可用结果）——供 [LocationRepository.fetchChineseCityName]
 * 直接用，也供单元测试直接喂样例 JSON。
 */
internal fun parseChineseCityName(body: String): String? = runCatching {
    val json = JSONObject(body)
    val city = json.getString("city")
    city.ifBlank { null }
}.getOrNull()
