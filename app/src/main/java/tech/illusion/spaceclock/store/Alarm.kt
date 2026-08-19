package tech.illusion.spaceclock.store

/**
 * 闹钟数据模型占位。字段（时间、重复规则……）和真正的 JSON schema 由 Task 7 补全——
 * 这一轮只需要 `ClockStore.alarms` 的返回类型形状稳定，让 Task 7 接上时不用改
 * `ClockStore` 的公开接口。
 */
data class Alarm(val id: String)

/** 占位反序列化：Task 7 接入真正的 JSON schema 前恒为空列表，`ClockStore.alarms` 因此恒空。 */
internal fun parseAlarms(json: String): List<Alarm> = emptyList()

/** 占位序列化：与 [parseAlarms] 配对，Task 7 换成真正的 JSONArray 编码。 */
internal fun serializeAlarms(alarms: List<Alarm>): String = "[]"
