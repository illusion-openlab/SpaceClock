package tech.illusion.spaceclock.weather

/**
 * 天气归类，供 UI 分支使用（图标选择、AlarmCard 追加提醒短语）。
 * 不直接暴露 WMO 原始 code 给 UI——所有消费点都通过 [WmoCodes.describe] 拿统一口径，
 * 不许在 UI 里散着写 `when (code)`（设计文档 §5.5）。
 */
enum class WeatherKind { CLEAR, CLOUDY, OVERCAST, FOG, RAIN, SNOW, THUNDER, HAIL, UNKNOWN }

/** 一次天气描述：中文短文案 + 归类。 */
data class WeatherDesc(val label: String, val kind: WeatherKind)

/**
 * Open-Meteo 用的 WMO (World Meteorological Organization) `weather_code` -> 中文描述的
 * 显式映射表。表未覆盖的 code 落到 [WeatherKind.UNKNOWN]，不抛异常——未知天气不该让调用方崩。
 *
 * 覆盖范围抄 Open-Meteo 文档给出的标准 WMO 天气代码表；`WmoCodesTest` 钉死的 9 个用例
 * （0/1/2/3/45/48/53/65/80/71/77/95/96）都能在表里直接查到。
 */
object WmoCodes {
    private val TABLE: Map<Int, WeatherDesc> = mapOf(
        0 to WeatherDesc("晴", WeatherKind.CLEAR),
        1 to WeatherDesc("多云", WeatherKind.CLOUDY),
        2 to WeatherDesc("多云", WeatherKind.CLOUDY),
        3 to WeatherDesc("阴", WeatherKind.OVERCAST),
        45 to WeatherDesc("有雾", WeatherKind.FOG),
        48 to WeatherDesc("有雾", WeatherKind.FOG),
        51 to WeatherDesc("小雨", WeatherKind.RAIN),
        53 to WeatherDesc("中雨", WeatherKind.RAIN),
        55 to WeatherDesc("大雨", WeatherKind.RAIN),
        56 to WeatherDesc("冻雨", WeatherKind.RAIN),
        57 to WeatherDesc("冻雨", WeatherKind.RAIN),
        61 to WeatherDesc("小雨", WeatherKind.RAIN),
        63 to WeatherDesc("中雨", WeatherKind.RAIN),
        65 to WeatherDesc("大雨", WeatherKind.RAIN),
        66 to WeatherDesc("冻雨", WeatherKind.RAIN),
        67 to WeatherDesc("冻雨", WeatherKind.RAIN),
        71 to WeatherDesc("小雪", WeatherKind.SNOW),
        73 to WeatherDesc("中雪", WeatherKind.SNOW),
        75 to WeatherDesc("大雪", WeatherKind.SNOW),
        77 to WeatherDesc("雪粒", WeatherKind.SNOW),
        80 to WeatherDesc("阵雨", WeatherKind.RAIN),
        81 to WeatherDesc("阵雨", WeatherKind.RAIN),
        82 to WeatherDesc("强阵雨", WeatherKind.RAIN),
        85 to WeatherDesc("阵雪", WeatherKind.SNOW),
        86 to WeatherDesc("强阵雪", WeatherKind.SNOW),
        95 to WeatherDesc("雷阵雨", WeatherKind.THUNDER),
        96 to WeatherDesc("雷阵雨伴冰雹", WeatherKind.HAIL),
        99 to WeatherDesc("雷阵雨伴冰雹", WeatherKind.HAIL),
    )

    fun describe(code: Int): WeatherDesc = TABLE[code] ?: WeatherDesc("未知", WeatherKind.UNKNOWN)
}

/**
 * 每种天气归类对应的行内图标——WorldPage 每行行尾 / ClockVolume 的状态副行统一从这里取，
 * 不各自枚举一份图标表（同一个理由：显式集中映射，不散着写）。
 *
 * **字形覆盖已在模拟器上实测（Task 10）**：9 个归类的图标全部有字形、零豆腐块，包括原先存疑的
 * 补充平面 emoji（🌫/🌧/🧊）。取证方式是临时钩子把 `WeatherKind.entries` 的图标强制渲染一遍
 * （自然路径下一次只看得到当前天气那一个，验不了其余 8 个），钩子已删。真机同款系统镜像待确认。
 *
 * 已知观感取舍：这些字符在设备上渲染成**彩色 emoji**，与设计文档 §5.4.1 刻意的单色深空蓝配色
 * 不完全一致。未处理，记在 `AGENTS.md` 的记账里。
 */
fun WeatherKind.icon(): String = when (this) {
    WeatherKind.CLEAR -> "☀"
    WeatherKind.CLOUDY -> "⛅"
    WeatherKind.OVERCAST -> "☁"
    WeatherKind.FOG -> "🌫"
    WeatherKind.RAIN -> "🌧"
    WeatherKind.SNOW -> "❄"
    WeatherKind.THUNDER -> "⛈"
    WeatherKind.HAIL -> "🧊"
    WeatherKind.UNKNOWN -> "—"
}
