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

    // ---- 暂停态播种（Task 8 fix round 1，用户裁决②：暂停的秒表落盘 + 重开以暂停态恢复）----

    @Test
    fun `seedPaused restores elapsed and laps without running`() {
        val e = TimerEngine()
        e.seedPaused(8_000L, listOf(3_000L, 5_000L))
        assertFalse(e.isRunning)
        assertEquals(8_000L, e.elapsedMs(1_000_000L))   // 播种后时间不自己走
        assertEquals(listOf(3_000L, 5_000L), e.laps)
    }

    @Test
    fun `seeded stopwatch resumes from the restored total`() {
        val e = TimerEngine()
        e.seedPaused(8_000L, emptyList())
        e.start(100_000L)
        assertEquals(9_500L, e.elapsedMs(101_500L))     // 恢复的 8s 之上继续累加
    }

    @Test
    fun `next lap after seeding measures the split not the total`() {
        // 计次存的是分段差（`lap` 的规格），所以播种时必须把"上一次计次的累计值"一起恢复，
        // 否则重开后的第一段会把之前所有段的时长又算一遍。
        // 播种值故意让"总用时"(9s) 与"计次累计"(3+5=8s) 不相等：这样只有把 lastLapTotal 恢复成
        // 分段和的实现才能算出 3_000；错误地拿总用时当上次计次点会算成 2_000。
        val e = TimerEngine()
        e.seedPaused(9_000L, listOf(3_000L, 5_000L))    // 上次计次时累计 8_000，之后又跑了 1s
        e.start(100_000L)
        e.lap(102_000L)                                  // 累计 11_000 → 本段 3_000
        assertEquals(listOf(3_000L, 5_000L, 3_000L), e.laps)
    }

    @Test
    fun `reset clears a seeded snapshot`() {
        val e = TimerEngine()
        e.seedPaused(8_000L, listOf(3_000L, 5_000L))
        e.reset()
        assertEquals(0L, e.elapsedMs(1_000L))
        assertTrue(e.laps.isEmpty())
    }
}
