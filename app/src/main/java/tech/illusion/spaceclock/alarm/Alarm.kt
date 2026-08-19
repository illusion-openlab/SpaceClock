package tech.illusion.spaceclock.alarm

import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

sealed interface Repeat {
    data object Daily : Repeat
    data object Weekdays : Repeat
    data object Weekends : Repeat
    /** isoDays 用 DayOfWeek.value：周一=1 … 周日=7。空集合表示永不触发。 */
    data class Days(val isoDays: Set<Int>) : Repeat
}

data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val repeat: Repeat,
    val enabled: Boolean,
)

private fun Repeat.matches(day: DayOfWeek): Boolean = when (this) {
    Repeat.Daily -> true
    Repeat.Weekdays -> day.value in 1..5
    Repeat.Weekends -> day.value in 6..7
    is Repeat.Days -> day.value in isoDays
}

/**
 * 下一次触发时刻；关闭的闹钟或永不匹配的规则返回 null。
 *
 * 从 from 当天开始往后扫最多 8 天：7 天足够覆盖任何周重复规则，第 8 天是为了处理
 * "今天符合规则但时刻已过"——那种情况要落到下一个符合规则的日子，可能正好是 7 天后。
 *
 * 边界口径：候选时刻必须**严格晚于** from。正好等于 from 的闹钟视为已经响过了
 * （否则每次查询都会把当前这一刻当成"即将触发"，触发逻辑会反复开火）。
 */
fun Alarm.nextTriggerAt(from: ZonedDateTime): ZonedDateTime? {
    if (!enabled) return null
    if (repeat is Repeat.Days && repeat.isoDays.isEmpty()) return null

    for (offset in 0..8) {
        val candidate = from.plusDays(offset.toLong())
            .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!repeat.matches(candidate.dayOfWeek)) continue
        if (candidate.isAfter(from)) return candidate
    }
    return null
}

fun serializeAlarms(list: List<Alarm>): String = JSONArray().apply {
    list.forEach { a ->
        put(JSONObject().apply {
            put("id", a.id); put("h", a.hour); put("m", a.minute); put("on", a.enabled)
            when (val r = a.repeat) {
                Repeat.Daily -> put("rep", "daily")
                Repeat.Weekdays -> put("rep", "weekdays")
                Repeat.Weekends -> put("rep", "weekends")
                is Repeat.Days -> { put("rep", "days"); put("days", JSONArray(r.isoDays.toList())) }
            }
        })
    }
}.toString()

fun parseAlarms(json: String): List<Alarm> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val repeat = when (o.getString("rep")) {
            "daily" -> Repeat.Daily
            "weekdays" -> Repeat.Weekdays
            "weekends" -> Repeat.Weekends
            "days" -> {
                val d = o.getJSONArray("days")
                Repeat.Days((0 until d.length()).map { d.getInt(it) }.toSet())
            }
            // 未知取值落到 Daily 而不是抛异常：存档格式将来若扩展，
            // 旧版本读到新规则时应该退化成"每天响"，而不是把整张闹钟表读丢。
            else -> Repeat.Daily
        }
        Alarm(o.getLong("id"), o.getInt("h"), o.getInt("m"), repeat, o.getBoolean("on"))
    }
}
