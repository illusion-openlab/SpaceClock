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
