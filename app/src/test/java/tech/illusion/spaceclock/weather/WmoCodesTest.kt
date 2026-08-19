package tech.illusion.spaceclock.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WmoCodesTest {
    @Test fun `code 0 is clear`() = assertEquals(WeatherKind.CLEAR, WmoCodes.describe(0).kind)
    @Test fun `codes 1 to 2 are cloudy`() {
        assertEquals(WeatherKind.CLOUDY, WmoCodes.describe(1).kind)
        assertEquals(WeatherKind.CLOUDY, WmoCodes.describe(2).kind)
    }
    @Test fun `code 3 is overcast`() = assertEquals(WeatherKind.OVERCAST, WmoCodes.describe(3).kind)
    @Test fun `codes 45 and 48 are fog`() {
        assertEquals(WeatherKind.FOG, WmoCodes.describe(45).kind)
        assertEquals(WeatherKind.FOG, WmoCodes.describe(48).kind)
    }
    @Test fun `drizzle and rain both map to rain`() {
        assertEquals(WeatherKind.RAIN, WmoCodes.describe(53).kind)
        assertEquals(WeatherKind.RAIN, WmoCodes.describe(65).kind)
        assertEquals(WeatherKind.RAIN, WmoCodes.describe(80).kind)
    }
    @Test fun `snow codes map to snow`() {
        assertEquals(WeatherKind.SNOW, WmoCodes.describe(71).kind)
        assertEquals(WeatherKind.SNOW, WmoCodes.describe(77).kind)
    }
    @Test fun `thunder codes map to thunder`() = assertEquals(WeatherKind.THUNDER, WmoCodes.describe(95).kind)
    @Test fun `hail codes map to hail`() = assertEquals(WeatherKind.HAIL, WmoCodes.describe(96).kind)

    @Test
    fun `unknown code falls back instead of crashing`() {
        val d = WmoCodes.describe(9999)
        assertEquals(WeatherKind.UNKNOWN, d.kind)
        assertEquals("未知", d.label)
    }
}
