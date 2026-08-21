package tech.illusion.spaceclock.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 测 JSON -> [IpLocation] 的纯映射函数 [parseIpLocation]，不发真实网络请求。跟
 * `WeatherRepositoryTest` 不同：那边只测 `beaufortScale` 纯函数，不碰任何 Android API；
 * 这里的 [parseIpLocation] 用到 `org.json.JSONObject`，它在纯 JUnit 下（没有 Robolectric）
 * 会抛 `RuntimeException("Method X not mocked")`。这个类必须标 @RunWith(RobolectricTestRunner::class)
 * 才能让 org.json 在测试里真正跑起来——不能删掉这个注解。
 *
 * 样例 JSON 用的是 geojs.io 的真实响应形状（2026-08-21 从 ipapi.co 换过来后实测确认）：
 * `latitude`/`longitude` 是 JSON 字符串，不是 number。
 */
@RunWith(RobolectricTestRunner::class)
class LocationRepositoryTest {
    @Test fun `parses a well-formed geojs response`() {
        val json = """
            {"accuracy":20,"area_code":"0","asn":38136,"city":"Tokyo","continent_code":"AS",
             "country":"Japan","country_code":"JP","ip":"185.220.238.43","latitude":"35.6893",
             "longitude":"139.6899","organization_name":"Akari Networks","region":"Tokyo",
             "timezone":"Asia/Tokyo"}
        """.trimIndent()
        assertEquals(
            IpLocation(cityName = "Tokyo", zoneId = "Asia/Tokyo", lat = 35.6893f, lon = 139.6899f),
            parseIpLocation(json),
        )
    }

    @Test fun `missing city field returns null`() {
        val json = """{"latitude":"31.2304","longitude":"121.4737","timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `missing timezone field returns null`() {
        val json = """{"city":"Shanghai","latitude":"31.2304","longitude":"121.4737"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `malformed json returns null`() {
        assertNull(parseIpLocation("not json"))
    }

    @Test fun `non-numeric latitude string returns null`() {
        val json = """{"city":"Shanghai","latitude":"not-a-number","longitude":"121.4737","timezone":"Asia/Shanghai"}"""
        assertNull(parseIpLocation(json))
    }

    @Test fun `invalid timezone id returns null`() {
        val json = """
            {"city":"Shanghai","latitude":"31.2304","longitude":"121.4737","timezone":"Not/A/Real/Zone"}
        """.trimIndent()
        assertNull(parseIpLocation(json))
    }

    @Test fun `out-of-range latitude returns null`() {
        val json = """
            {"city":"Shanghai","latitude":"200.0","longitude":"121.4737","timezone":"Asia/Shanghai"}
        """.trimIndent()
        assertNull(parseIpLocation(json))
    }
}
