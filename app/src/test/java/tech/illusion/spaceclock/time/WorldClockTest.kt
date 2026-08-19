package tech.illusion.spaceclock.time

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldClockTest {
    // 2026-08-19T01:41:00Z —— 北半球夏令时期间，刻意选这个时刻覆盖 DST 分支
    private val at = Instant.parse("2026-08-19T01:41:00Z")

    @Test
    fun `same zone shows plus minus zero`() {
        assertEquals("±0", offsetLabel("Asia/Shanghai", "Asia/Shanghai", at))
    }

    @Test
    fun `tokyo is one hour ahead of beijing`() {
        assertEquals("+1h", offsetLabel("Asia/Tokyo", "Asia/Shanghai", at))
    }

    @Test
    fun `london in summer time is seven hours behind beijing`() {
        // 伦敦夏令时 UTC+1，北京 UTC+8 -> -7h。不是冬令时的 -8h。
        assertEquals("−7h", offsetLabel("Europe/London", "Asia/Shanghai", at))
    }

    @Test
    fun `half hour offsets are not rounded away`() {
        // 新德里 UTC+5:30 vs 北京 UTC+8 -> -2.5h
        assertEquals("−2.5h", offsetLabel("Asia/Kolkata", "Asia/Shanghai", at))
    }

    @Test
    fun `local time is rendered in the target zone`() {
        assertEquals(9, localTimeIn("Asia/Shanghai", at).hour)
        assertEquals(41, localTimeIn("Asia/Shanghai", at).minute)
    }

    @Test
    fun `quarter hour offsets are not truncated`() {
        // 尼泊尔 UTC+5:45 vs 北京 UTC+8 -> -2.25h。
        // 回归钉子：m*10/60 的整除写法会得到 "−2.2h"，一个看起来合理但错误的值。
        assertEquals("−2.25h", offsetLabel("Asia/Kathmandu", "Asia/Shanghai", at))
    }
}
