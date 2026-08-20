package tech.illusion.spaceclock.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 只测 JSON -> [IpLocation] 的纯映射函数 [parseIpLocation]，不发真实网络请求——
 * 跟 `WeatherRepositoryTest` 只测 `beaufortScale` 纯函数的现状一致。
 */
@RunWith(RobolectricTestRunner::class)
class LocationRepositoryTest {
    @Test fun `parses a well-formed ipapi response`() {
        val json = """
            {"ip":"1.2.3.4","city":"Shanghai","region":"Shanghai","country_name":"China",
             "latitude":31.2304,"longitude":121.4737,"timezone":"Asia/Shanghai"}
        """.trimIndent()
        assertEquals(
            IpLocation(cityName = "Shanghai", zoneId = "Asia/Shanghai", lat = 31.2304f, lon = 121.4737f),
            parseIpLocation(json),
        )
    }

    @Test fun `missing city field returns null`() {
        val json = """{"latitude":31.2304,"longitude":121.4737,"timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `error response returns null`() {
        // ipapi.co 限流/出错时返回 {"error": true, "reason": "...", "message": "..."}，没有城市字段。
        val json = """{"error":true,"reason":"RateLimited","message":"Too many requests"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `malformed json returns null`() {
        assertNull(parseIpLocation("not json"))
    }

    @Test fun `wrong field type returns null`() {
        val json = """{"city":"Shanghai","latitude":"not-a-number","longitude":121.4737,"timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }
}
