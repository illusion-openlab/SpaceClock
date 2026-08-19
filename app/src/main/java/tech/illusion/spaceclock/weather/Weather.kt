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
