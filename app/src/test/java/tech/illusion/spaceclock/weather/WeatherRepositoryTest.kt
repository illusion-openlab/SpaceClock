package tech.illusion.spaceclock.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `beaufortScale` 阈值边界回归测试（fix round 1，第 2 条；Task 10 补全中段档位）。
 *
 * 标准蒲福风级表（km/h）：
 * `0: <1  1: 1–5  2: 6–11  3: 12–19  4: 20–28  5: 29–38  6: 39–49
 *  7: 50–61  8: 62–74  9: 75–88  10: 89–102  11: 103–117  12: ≥118`
 *
 * **每一档都要有一对"上界/下一档下界"用例**，否则回归网是漏的。fix round 1 那 8 条用例
 * 只在 index 1 / 2 / 11 这三档上具备判别力：`BEAUFORT_THRESHOLDS_KMH` 是个整表平移的
 * off-by-one 风险结构（原 bug 就是整表偏一档），中段任何一档单独写错，那 8 条一条都不会红。
 * Task 10 把 index 3–10 的判别性边界补齐——现在表里 12 个阈值每一个都被两侧夹住，
 * 任何单点改动都至少让一条用例失败。
 */
class WeatherRepositoryTest {
    @Test fun `0point9 is scale 0`() = assertEquals(0, beaufortScale(0.9))
    @Test fun `1point0 is scale 1`() = assertEquals(1, beaufortScale(1.0))
    @Test fun `5point0 is scale 1`() = assertEquals(1, beaufortScale(5.0))
    @Test fun `6point0 is scale 2`() = assertEquals(2, beaufortScale(6.0))
    @Test fun `11point0 is scale 2`() = assertEquals(2, beaufortScale(11.0))
    @Test fun `12point0 is scale 3`() = assertEquals(3, beaufortScale(12.0))

    // ---- index 3–10 的判别性边界（Task 10 补全）----
    @Test fun `19point0 is scale 3`() = assertEquals(3, beaufortScale(19.0))
    @Test fun `20point0 is scale 4`() = assertEquals(4, beaufortScale(20.0))
    @Test fun `28point0 is scale 4`() = assertEquals(4, beaufortScale(28.0))
    @Test fun `29point0 is scale 5`() = assertEquals(5, beaufortScale(29.0))
    @Test fun `38point0 is scale 5`() = assertEquals(5, beaufortScale(38.0))
    @Test fun `39point0 is scale 6`() = assertEquals(6, beaufortScale(39.0))
    @Test fun `49point0 is scale 6`() = assertEquals(6, beaufortScale(49.0))
    @Test fun `50point0 is scale 7`() = assertEquals(7, beaufortScale(50.0))
    @Test fun `61point0 is scale 7`() = assertEquals(7, beaufortScale(61.0))
    @Test fun `62point0 is scale 8`() = assertEquals(8, beaufortScale(62.0))
    @Test fun `74point0 is scale 8`() = assertEquals(8, beaufortScale(74.0))
    @Test fun `75point0 is scale 9`() = assertEquals(9, beaufortScale(75.0))
    @Test fun `88point0 is scale 9`() = assertEquals(9, beaufortScale(88.0))
    @Test fun `89point0 is scale 10`() = assertEquals(10, beaufortScale(89.0))
    @Test fun `102point0 is scale 10`() = assertEquals(10, beaufortScale(102.0))
    @Test fun `103point0 is scale 11`() = assertEquals(11, beaufortScale(103.0))

    @Test fun `117point0 is scale 11`() = assertEquals(11, beaufortScale(117.0))
    @Test fun `118point0 is scale 12`() = assertEquals(12, beaufortScale(118.0))
}
