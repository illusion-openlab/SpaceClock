package tech.illusion.spaceclock.weather

/** 一次天气快照。Task 9 由 WeatherRepository 填充；在那之前 UI 收到的是 null。 */
data class WeatherSnapshot(
    val tempC: Int,
    val highC: Int,
    val lowC: Int,
    val feelsLikeC: Int,
    val humidityPct: Int,
    val windScale: Int,
    val sunrise: String,       // "05:32"
    val sunset: String,        // "19:04"
    val wmoCode: Int,
    val fetchedAtEpochSec: Long,
)

/** 提醒卡片那一句。只有雨雪雷才追加行动建议——晴天不需要"记得带伞"这种废话。 */
fun WeatherSnapshot.alarmLine(): String {
    val desc = WmoCodes.describe(wmoCode)
    val base = "今天${desc.label} ${lowC}–${highC}°"
    val advice = when (desc.kind) {
        WeatherKind.RAIN, WeatherKind.THUNDER -> "，记得带伞"
        WeatherKind.SNOW, WeatherKind.HAIL -> "，注意路滑"
        else -> ""
    }
    return base + advice
}
