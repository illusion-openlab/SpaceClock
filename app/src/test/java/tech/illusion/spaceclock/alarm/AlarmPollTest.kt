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
    fun `disabled alarm's not-yet-due snooze entry is retained, not eagerly dropped`() {
        // This pins the corrected behavior (controller ruling, fix round 2): the previous version
        // of this test asserted the OPPOSITE — that a disabled alarm's snooze entry was dropped
        // immediately, even before it was due. That eager drop was itself the Important defect: a
        // user who disables and quickly re-enables inside the snooze window would silently lose the
        // "稍后" they asked for, because nothing re-arms the specific postponed instant. The minimal,
        // correct fix only drops an entry once it is actually due — see the two-poll regression test
        // below (`disabling then re-enabling ...`) for the end-to-end case this protects.
        val alarm = Alarm(1, 7, 0, Repeat.Daily, enabled = false)
        val snoozeUntil = mapOf(1L to at(19, 7, 30)) // not due yet
        val result = pollAlarms(
            alarms = listOf(alarm),
            snoozeUntil = snoozeUntil,
            fromCheck = at(19, 7, 9),
            nowCheck = at(19, 7, 11),
        )
        assertTrue("disabled alarm must not fire", result.newlyFired.isEmpty())
        assertEquals(
            "not-yet-due entry must be retained even while the alarm is disabled — a quick " +
                "disable/re-enable inside the snooze window must not silently lose the postponed fire",
            snoozeUntil,
            result.updatedSnoozeUntil,
        )
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
    fun `normal nextTriggerAt path is unaffected by a due unrelated snooze entry`() {
        // Regression for a Minor finding: the previous version of this test used a snooze entry
        // that was never due (`dueSnoozeIds` was empty), so it would have passed identically with
        // `snoozeUntil = emptyMap()` — it never actually exercised cross-contamination. Here the
        // unrelated entry (id 2) IS due in this window, so it fires through the due-snooze branch;
        // the assertion only holds if the normal nextTriggerAt branch (id 1) still fires independently
        // and isn't suppressed or duplicated by the other alarm's due snooze entry.
        val due = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        val other = Alarm(2, 8, 0, Repeat.Daily, enabled = true)
        val result = pollAlarms(
            alarms = listOf(due, other),
            snoozeUntil = mapOf(2L to at(19, 7, 0)), // unrelated snooze, due within this window
            fromCheck = at(19, 6, 59),
            nowCheck = at(19, 7, 1),
        )
        assertEquals(setOf(1L, 2L), result.newlyFired.map { it.id }.toSet())
        assertFalse("consumed snooze entry must be removed", result.updatedSnoozeUntil.containsKey(2L))
    }

    @Test
    fun `disabling then re-enabling an alarm during its snooze wait still fires at the postponed time`() {
        // The Important defect this round fixes: pollAlarms used to drop a snooze entry for ANY
        // disabled alarm, not just a due one. A user who disables an alarm mid-snooze and re-enables
        // it before the postponed instant would silently lose the "稍后" — the entry is gone by the
        // time the alarm is re-enabled, and nextTriggerAt only re-arms the alarm's *regular* schedule,
        // not the specific snoozed instant. This test threads poll 1's actual `updatedSnoozeUntil`
        // output into poll 2's `snoozeUntil` input — exactly how ClockVolume.kt's LaunchedEffect
        // chains polls — because the bug lives precisely in what poll 1 returns; hand-writing poll 2's
        // input map would pass even with the bug present.
        val snoozedAt7_10 = mapOf(1L to at(19, 7, 10))

        // Poll 1: alarm observed disabled; its snooze entry (7:10) is not yet due at 7:05.
        val disabledAlarm = Alarm(1, 7, 0, Repeat.Daily, enabled = false)
        val poll1 = pollAlarms(
            alarms = listOf(disabledAlarm),
            snoozeUntil = snoozedAt7_10,
            fromCheck = at(19, 7, 1),
            nowCheck = at(19, 7, 5),
        )
        assertTrue("alarm is disabled; must not fire yet", poll1.newlyFired.isEmpty())

        // Between polls, the user re-enables the alarm. Poll 2 uses poll 1's ACTUAL returned
        // snoozeUntil (not a hand-written map) and reaches past the postponed instant (7:10).
        val reEnabledAlarm = Alarm(1, 7, 0, Repeat.Daily, enabled = true)
        val poll2 = pollAlarms(
            alarms = listOf(reEnabledAlarm),
            snoozeUntil = poll1.updatedSnoozeUntil,
            fromCheck = at(19, 7, 5),
            nowCheck = at(19, 7, 11),
        )
        assertEquals(
            "re-enabling before the postponed instant must still honor the snooze",
            listOf(reEnabledAlarm),
            poll2.newlyFired,
        )
    }
}
