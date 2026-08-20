package tech.illusion.spaceclock.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CityTest {
    @Test fun `existing four-arg construction still works, displayName defaults to null`() {
        val city = City("北京", "Asia/Shanghai", 39.90f, 116.40f)
        assertNull(city.displayName)
    }

    @Test fun `displayName can be set explicitly`() {
        val city = City(CURRENT_LOCATION_CITY_NAME, "Asia/Shanghai", 31.23f, 121.47f, "上海")
        assertEquals("上海", city.displayName)
    }

    @Test fun `CURRENT_LOCATION_CITY_NAME is the fixed literal`() {
        assertEquals("当前位置", CURRENT_LOCATION_CITY_NAME)
    }
}
