package tech.illusion.spaceclock.timing

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PomodoroEngineTest {
    private fun engine() = PomodoroEngine(PomodoroConfig(workMin = 25, shortMin = 5, longMin = 15, roundsPerLong = 4))
    private val min = 60_000L

    @Test
    fun `starts in work phase at round one`() {
        val e = engine()
        assertEquals(Phase.WORK, e.phase)
        assertEquals(1, e.round)
        assertEquals(25 * min, e.remainingMs(0L))
    }

    @Test
    fun `work rolls into short break and counts one tomato`() {
        val e = engine()
        e.start(0L)
        e.advanceTo(25 * min)
        assertEquals(Phase.SHORT_BREAK, e.phase)
        assertEquals(1, e.completedToday)
        assertEquals(25 * min, e.focusedTodayMs)
    }

    @Test
    fun `fourth work phase rolls into long break`() {
        val e = engine()
        e.start(0L)
        var t = 0L
        repeat(3) {
            t += 25 * min; e.advanceTo(t)   // work -> short
            t += 5 * min; e.advanceTo(t)    // short -> work
        }
        t += 25 * min; e.advanceTo(t)       // 第 4 个 work 结束
        assertEquals(Phase.LONG_BREAK, e.phase)
        assertEquals(4, e.completedToday)
    }

    @Test
    fun `skip jumps phase without counting a tomato`() {
        val e = engine()
        e.start(0L)
        e.skip(10 * min)
        assertEquals(Phase.SHORT_BREAK, e.phase)
        assertEquals(0, e.completedToday)      // 跳过不算完成
        assertEquals(0L, e.focusedTodayMs)
    }

    @Test
    fun `breaks do not count toward focused time`() {
        val e = engine()
        e.start(0L)
        e.advanceTo(25 * min)                  // -> short break
        e.advanceTo(30 * min)                  // short break 结束 -> work
        assertEquals(1, e.completedToday)
        assertEquals(25 * min, e.focusedTodayMs)  // 休息的 5 分钟不算专注
    }

    @Test
    fun `stats reset on a new day`() {
        val e = engine()
        e.start(0L); e.advanceTo(25 * min)
        assertEquals(1, e.completedToday)
        e.rollOverIfNewDay(LocalDate.of(2026, 8, 20))
        assertEquals(0, e.completedToday)
        assertEquals(0L, e.focusedTodayMs)
    }

    @Test
    fun `pause freezes the remaining time`() {
        val e = engine()
        e.start(0L)
        e.pause(10 * min)
        assertEquals(15 * min, e.remainingMs(99 * min))
    }
}
