package tech.illusion.spaceclock.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.illusion.spaceclock.time.City

class ClockStoreSerializationTest {
    @Test fun `round-trips a city with displayName`() {
        val city = City("当前位置", "Asia/Shanghai", 31.23f, 121.47f, "上海")
        val raw = serializeCurrentLocationCity(city)
        assertEquals(city, parseCurrentLocationCity(raw))
    }

    @Test fun `serializing null returns null`() {
        assertNull(serializeCurrentLocationCity(null))
    }

    @Test fun `parsing null returns null`() {
        assertNull(parseCurrentLocationCity(null))
    }

    @Test fun `parsing a malformed field count returns null`() {
        assertNull(parseCurrentLocationCity("onlytwo"))
    }

    @Test fun `parsing a non-numeric lat returns null`() {
        assertNull(parseCurrentLocationCity("当前位置Asia/Shanghainot-a-number121.47上海"))
    }

    @Test fun `parsing an invalid zoneId returns null`() {
        val sep = ""
        val raw = listOf("当前位置", "Not/A/Zone", "31.23", "121.47", "上海").joinToString(sep)
        assertNull(parseCurrentLocationCity(raw))
    }
}
