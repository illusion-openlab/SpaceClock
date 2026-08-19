package tech.illusion.spaceclock.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `beaufortScale` 阈值边界回归测试（fix round 1，第 2 条）。
 *
 * 标准蒲福风级表（km/h）：0: <1  1: 1–5  2: 6–11  3: 12–19 ... 11: 103–117  12: ≥118。
 * 每档边界都要能落在正确的级别，尤其是旧实现系统性偏高一级的那些点（5.0、11.0、117.0）。
 */
class WeatherRepositoryTest {
    @Test fun `0point9 is scale 0`() = assertEquals(0, beaufortScale(0.9))
    @Test fun `1point0 is scale 1`() = assertEquals(1, beaufortScale(1.0))
    @Test fun `5point0 is scale 1`() = assertEquals(1, beaufortScale(5.0))
    @Test fun `6point0 is scale 2`() = assertEquals(2, beaufortScale(6.0))
    @Test fun `11point0 is scale 2`() = assertEquals(2, beaufortScale(11.0))
    @Test fun `12point0 is scale 3`() = assertEquals(3, beaufortScale(12.0))
    @Test fun `117point0 is scale 11`() = assertEquals(11, beaufortScale(117.0))
    @Test fun `118point0 is scale 12`() = assertEquals(12, beaufortScale(118.0))
}
