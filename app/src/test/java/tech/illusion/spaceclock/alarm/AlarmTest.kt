package tech.illusion.spaceclock.alarm

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-08-19 是星期三
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 8, day, h, m, 0, 0, zone)

    @Test
    fun `daily alarm later today fires today`() {
        val a = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        assertEquals(at(19, 7, 0), a.nextTriggerAt(at(19, 6, 30)))
    }

    @Test
    fun `daily alarm already passed fires tomorrow`() {
        val a = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        assertEquals(at(20, 7, 0), a.nextTriggerAt(at(19, 7, 1)))
    }

    @Test
    fun `alarm exactly now fires next cycle not now`() {
        val a = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        // 边界：正好等于当前时刻的闹钟已经响过了，下一次是明天。
        assertEquals(at(20, 7, 0), a.nextTriggerAt(at(19, 7, 0)))
    }

    @Test
    fun `weekday alarm on friday evening skips to monday`() {
        val a = Alarm(1, 7, 0, Repeat.Weekdays, enabled = true)
        assertEquals(at(24, 7, 0), a.nextTriggerAt(at(21, 20, 0)))  // 8/21 周五 -> 8/24 周一
    }

    @Test
    fun `weekend alarm on wednesday goes to saturday`() {
        val a = Alarm(1, 8, 30, Repeat.Weekends, enabled = true)
        assertEquals(at(22, 8, 30), a.nextTriggerAt(at(19, 9, 0)))  // 8/22 周六
    }

    @Test
    fun `custom days pick the nearest listed weekday`() {
        val a = Alarm(1, 6, 0, Repeat.Days(setOf(2, 4)), enabled = true)  // 周二、周四
        assertEquals(at(20, 6, 0), a.nextTriggerAt(at(19, 9, 0)))         // 周三 -> 周四
    }

    @Test
    fun `disabled alarm never fires`() {
        val a = Alarm(1, 7, 0, Repeat.Daily, enabled = false)
        assertNull(a.nextTriggerAt(at(19, 6, 0)))
    }

    @Test
    fun `custom days with empty set never fires`() {
        val a = Alarm(1, 7, 0, Repeat.Days(emptySet()), enabled = true)
        assertNull(a.nextTriggerAt(at(19, 6, 0)))
    }
}
