package tech.illusion.spaceclock.timing

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * `phaseTotalMs` 是进度环的分母。以前 `FocusPage` 自己复制了一份同样的换算公式，
     * 两份公式会各自漂移；由引擎唯一提供之后，这个测试就是防漂移的那道闸。
     */
    @Test
    fun `phase total tracks the current phase`() {
        val e = engine()
        assertEquals(25 * min, e.phaseTotalMs)              // WORK
        e.skip(0L)
        assertEquals(Phase.SHORT_BREAK, e.phase)
        assertEquals(5 * min, e.phaseTotalMs)               // SHORT_BREAK
        // 走到长休：WORK→SHORT→WORK→…→第 4 个 WORK 之后才是 LONG_BREAK（roundsPerLong=4）。
        // 阶段序列本身已有别的测试盯着，这里只关心分母跟着阶段走。
        while (e.phase != Phase.LONG_BREAK) e.skip(0L)
        assertEquals(15 * min, e.phaseTotalMs)              // LONG_BREAK
    }

    @Test
    fun `phase total follows a config change`() {
        val e = engine()
        assertTrue(e.applyConfig(e.config.copy(workMin = 45)))
        assertEquals(45 * min, e.phaseTotalMs)
    }

    /**
     * 运行中改配置会让当前阶段的剩余时间凭空跳变。以前只有 UI 的 `enabled = !running` 挡着，
     * 前台服务是第二个调用方，必须由引擎自己兜住——纵深防御，不靠某个界面的可用态。
     */
    @Test
    fun `config change is refused while running`() {
        val e = engine()
        e.start(0L)
        val before = e.config
        assertFalse(e.applyConfig(PomodoroConfig(workMin = 45, shortMin = 10, longMin = 30)))
        assertEquals(before, e.config)
        assertEquals(25 * min, e.remainingMs(0L))   // 剩余时间没有被跳变
    }

    @Test
    fun `config change is accepted while paused`() {
        val e = engine()
        e.start(0L)
        e.pause(1 * min)
        assertTrue(e.applyConfig(PomodoroConfig(workMin = 45, shortMin = 10, longMin = 30)))
        assertEquals(45, e.config.workMin)
        assertEquals(44 * min, e.remainingMs(1 * min))
    }
}
