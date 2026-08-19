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

/** 一轮轮询的结果：这一轮该展示哪些闹钟，以及更新后的「稍后」表。 */
data class AlarmPollResult(
    val newlyFired: List<Alarm>,
    val updatedSnoozeUntil: Map<Long, ZonedDateTime>,
)

/**
 * 纯函数：给定当前闹钟表、「稍后」表和本轮轮询的时间窗口 `(fromCheck, nowCheck]`，算出这一轮
 * 该新触发哪些闹钟、「稍后」表该怎么更新。抽成纯函数是为了可单测，也是 Task 8 把整套轮询搬进
 * 前台服务时可以直接复用，不用在 Service 里重新写一遍这段逻辑。
 *
 * enabled 快照决定「要不要真的响」：稍后 10 分钟等待期间用户把闹钟关掉，到点也不应该响
 * ——「关闭的闹钟永不触发」是简报锁死的硬性不变量，`nextTriggerAt` 已经用一个测试用例把它
 * 钉死在纯函数层，轮询层不能悄悄开一个绕过去的口子（见下面 `dueEnabledSnoozeIds`）。
 *
 * 但 enabled 快照**不**决定「要不要留着这条『稍后』记录」——只有到点的条目才摘：
 * 到点的条目无论 enabled 与否都已经处理完，摘掉是唯一正确选择；没到点的条目即使当前
 * disabled 也保留，因为「稍后」是用户的一次明确请求，10 分钟等待期内快速关闭再重新打开
 * 是完全正常的操作序列——静默丢掉这次「稍后」比让用户在重新打开后按原计划收到一次提醒更糟。
 * 这个窗口是有界的（snooze 长度，10 分钟）：条目一旦到点，不管 enabled 与否都会在下一次
 * 轮询被摘掉，不会无限期挂着。
 */
fun pollAlarms(
    alarms: List<Alarm>,
    snoozeUntil: Map<Long, ZonedDateTime>,
    fromCheck: ZonedDateTime,
    nowCheck: ZonedDateTime,
): AlarmPollResult {
    val enabledIds = alarms.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
    val dueSnoozeIds = snoozeUntil.filterValues { !it.isAfter(nowCheck) }.keys
    val dueEnabledSnoozeIds = dueSnoozeIds.filter { it in enabledIds }.toSet()

    val newlyFired = mutableListOf<Alarm>()
    if (dueEnabledSnoozeIds.isNotEmpty()) {
        alarms.filter { it.id in dueEnabledSnoozeIds }.forEach { newlyFired += it }
    }
    // 只摘到点的条目（不管 enabled 与否）。没到点的条目一律保留，即使当前 disabled——
    // 见上面的 KDoc：这是有界的（snooze 长度）等待窗口，不是无限期挂着的死记录。
    val updatedSnoozeUntil = snoozeUntil.filterKeys { it !in dueSnoozeIds }

    alarms.filter { it.enabled && it.id !in dueSnoozeIds }.forEach { a ->
        val trigger = a.nextTriggerAt(fromCheck)
        if (trigger != null && !trigger.isAfter(nowCheck)) newlyFired += a
    }
    return AlarmPollResult(newlyFired, updatedSnoozeUntil)
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
