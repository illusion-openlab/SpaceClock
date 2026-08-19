package tech.illusion.spaceclock.alarm

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding: the snooze re-fire path in `ClockVolume`'s poll block did not check `enabled`,
 * unlike the normal `nextTriggerAt` path a few lines below it. A user could dismiss with 「稍后」,
 * disable the alarm in `AlarmPage` during the 10-minute wait, and it would still fire when the
 * snooze matured — contradicting the brief's invariant ("disabled alarm never fires") that
 * `AlarmTest`'s own `disabled alarm never fires` case already pins for `nextTriggerAt`.
 *
 * `pollAlarms` (alarm/Alarm.kt) is the pure function extracted from that poll block so this can be
 * unit-tested instead of requiring a device round, and so Task 8 can reuse it verbatim when the
 * polling moves into a foreground service.
 */
class AlarmPollTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 8, day, h, m, 0, 0, zone)

    @Test
    fun `snoozed alarm disabled during the wait does not re-fire and is dropped from snoozeUntil`() {
        val alarm = Alarm(1, 7, 0, Repeat.Daily, enabled = false) // disabled while snoozing
        val snoozeUntil = mapOf(1L to at(19, 7, 10)) // due at 07:10
        val result = pollAlarms(
            alarms = listOf(alarm),
            snoozeUntil = snoozeUntil,
            fromCheck = at(19, 7, 9),
            nowCheck = at(19, 7, 11), // past the snooze wake time
        )
        assertTrue("disabled alarm must not re-fire", result.newlyFired.isEmpty())
        assertFalse("dead snooze entry must be dropped, not left for a future re-enable", result.updatedSnoozeUntil.containsKey(1L))
    }

    @Test
    fun `snoozed alarm still enabled fires when due`() {
        val alarm = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        val snoozeUntil = mapOf(1L to at(19, 7, 10))
        val result = pollAlarms(
            alarms = listOf(alarm),
            snoozeUntil = snoozeUntil,
            fromCheck = at(19, 7, 9),
            nowCheck = at(19, 7, 11),
        )
        assertEquals(listOf(alarm), result.newlyFired)
        assertFalse("consumed snooze entry must be removed after firing", result.updatedSnoozeUntil.containsKey(1L))
    }

    @Test
    fun `disabled alarm's snooze entry is dropped immediately even before it is due`() {
        val alarm = Alarm(1, 7, 0, Repeat.Daily, enabled = false)
        val snoozeUntil = mapOf(1L to at(19, 7, 30)) // not due yet
        val result = pollAlarms(
            alarms = listOf(alarm),
            snoozeUntil = snoozeUntil,
            fromCheck = at(19, 7, 9),
            nowCheck = at(19, 7, 11),
        )
        assertTrue(result.newlyFired.isEmpty())
        assertFalse("disabled alarm's stale snooze entry should not linger", result.updatedSnoozeUntil.containsKey(1L))
    }

    @Test
    fun `enabled alarm's not-yet-due snooze entry is retained unchanged`() {
        val alarm = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        val snoozeUntil = mapOf(1L to at(19, 7, 30)) // not due yet
        val result = pollAlarms(
            alarms = listOf(alarm),
            snoozeUntil = snoozeUntil,
            fromCheck = at(19, 7, 9),
            nowCheck = at(19, 7, 11),
        )
        assertTrue(result.newlyFired.isEmpty())
        assertEquals(snoozeUntil, result.updatedSnoozeUntil)
    }

    @Test
    fun `normal nextTriggerAt path is unaffected by an unrelated snooze entry`() {
        val due = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        val other = Alarm(2, 8, 0, Repeat.Daily, enabled = true)
        val result = pollAlarms(
            alarms = listOf(due, other),
            snoozeUntil = mapOf(2L to at(19, 9, 0)), // unrelated snooze, not due
            fromCheck = at(19, 6, 59),
            nowCheck = at(19, 7, 1),
        )
        assertEquals(listOf(due), result.newlyFired)
    }
}
