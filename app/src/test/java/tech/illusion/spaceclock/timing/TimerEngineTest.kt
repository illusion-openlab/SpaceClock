package tech.illusion.spaceclock.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerEngineTest {

    @Test
    fun `fresh stopwatch reads zero and is not running`() {
        val e = TimerEngine()
        assertEquals(0L, e.elapsedMs(1000L))
        assertFalse(e.isRunning)
    }

    @Test
    fun `stopwatch accumulates only while running`() {
        val e = TimerEngine()
        e.start(1_000L)
        assertEquals(500L, e.elapsedMs(1_500L))
        e.pause(2_000L)
        assertEquals(1_000L, e.elapsedMs(9_999L))   // 暂停后时间不再走
    }

    @Test
    fun `resume adds to the previously accumulated time`() {
        val e = TimerEngine()
        e.start(0L); e.pause(1_000L)
        e.start(5_000L)
        assertEquals(1_500L, e.elapsedMs(5_500L))
    }

    @Test
    fun `reset clears elapsed and laps`() {
        val e = TimerEngine()
        e.start(0L); e.lap(300L); e.lap(700L); e.pause(1_000L)
        e.reset()
        assertEquals(0L, e.elapsedMs(9_999L))
        assertTrue(e.laps.isEmpty())
    }

    @Test
    fun `laps record splits not cumulative totals`() {
        val e = TimerEngine()
        e.start(0L)
        e.lap(300L)   // 第 1 段 300
        e.lap(700L)   // 第 2 段 400
        assertEquals(listOf(300L, 400L), e.laps)
    }

    @Test
    fun `countdown counts down and clamps at zero`() {
        val e = TimerEngine()
        e.setCountdown(5_000L)
        e.start(1_000L)
        assertEquals(3_000L, e.remainingMs(3_000L))
        assertEquals(0L, e.remainingMs(99_000L))   // 不会变成负数
    }

    @Test
    fun `countdown reports finished exactly at zero`() {
        val e = TimerEngine()
        e.setCountdown(5_000L)
        e.start(0L)
        assertFalse(e.isFinished(4_999L))
        assertTrue(e.isFinished(5_000L))
    }

    @Test
    fun `setCountdown while running restarts the countdown`() {
        val e = TimerEngine()
        e.setCountdown(5_000L); e.start(0L)
        e.setCountdown(10_000L)
        assertFalse(e.isRunning)                    // 改时长会停下来，避免"改了一半还在跑"的歧义状态
        assertEquals(10_000L, e.remainingMs(3_000L))
    }
}
