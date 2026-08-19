package tech.illusion.spaceclock.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

fun localTimeIn(zoneId: String, at: Instant): LocalTime =
    at.atZone(ZoneId.of(zoneId)).toLocalTime()

/**
 * 相对本地时区的时差标签。
 * 用传入的 Instant 而不是 now()：DST 让「两地相差几小时」随日期变化，
 * 而且写死 now() 的测试会在半年后自己坏掉。
 * 半小时/45 分钟时区（印度、尼泊尔、澳中）不能整数除，用分钟算再格式化。
 */
fun offsetLabel(cityZone: String, localZone: String, at: Instant): String {
    val cityMin = ZoneId.of(cityZone).rules.getOffset(at).totalSeconds / 60
    val localMin = ZoneId.of(localZone).rules.getOffset(at).totalSeconds / 60
    val diff = cityMin - localMin
    if (diff == 0) return "±0"
    val sign = if (diff > 0) "+" else "−"
    val abs = kotlin.math.abs(diff)
    val h = abs / 60
    val m = abs % 60
    // 用 m*100/60 再去尾零，而不是 m*10/60：m*10/60 整数除会把 45 分截成 7（"5.7h"，
    // 应为 "5.75h"）而不报错——一个看起来合理但错误的值。m*100/60 再 trimEnd('0') 既能
    // 保留半小时时区的既有输出（30 -> 50 -> "5"），也能补上 15/45 分钟时区。
    val body = if (m == 0) "$h" else "$h." + (m * 100 / 60).toString().trimEnd('0')
    return "$sign${body}h"
}
