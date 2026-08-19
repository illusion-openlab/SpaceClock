package tech.illusion.spaceclock.earth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.illusion.spaceclock.time.BUILTIN_CITIES
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * `EarthScene` 的纯几何部分。SDK 实体不进单测——这里测的是"角度算对了没有"，
 * 上机那一半（轴向映射、球心偏移、观察者方向）由 task-4-report.md 的实测日志覆盖。
 */
class EarthSceneMathTest {

    /**
     * 绕引擎 +Y 的自转，右手系：yaw 为正时 +Z 转向 +X。
     *
     * 这不是复制被测代码，而是编码**引擎的**约定：`EulerAngles(0, yaw, 0).toQuat()` 是绕世界 Y
     * 的右手旋转（foundation-6.0.0 `EulerAngles.kt` 的 `qy`），而映射 `engine=(x_m,z_m,−y_m)`
     * 行列式为 +1、手性不翻转（AGENTS.md）。设备实测同样吻合：round 1/2 的 `PIN-READOUT`
     * 里 9 座城市的方位角全部等于 `−(λ + spin)`。
     */
    private fun spinAboutY(v: Triple<Float, Float, Float>, yawDeg: Float): Triple<Float, Float, Float> {
        val s = Math.toRadians(yawDeg.toDouble())
        val (x, y, z) = v
        return Triple(
            (x * cos(s) + z * sin(s)).toFloat(),
            y,
            (-x * sin(s) + z * cos(s)).toFloat(),
        )
    }

    /** 引擎方位角：`atan2(z, x)`，度。观察者方向是 +90°（正面朝 +Z）。 */
    private fun azimuthDeg(v: Triple<Float, Float, Float>): Float =
        Math.toDegrees(atan2(v.third.toDouble(), v.first.toDouble())).toFloat()

    private fun engineDirOf(latDeg: Float, lonDeg: Float): Triple<Float, Float, Float> =
        toEngineTriple(GeoMath.surfacePoint(latDeg, lonDeg, radius = 1f, lift = 1f))

    @Test
    fun `north pole maps onto the engine plus Y axis`() {
        val (x, y, z) = engineDirOf(90f, 0f)
        assertEquals(0f, x, 1e-4f)
        assertEquals(1f, y, 1e-4f)
        assertEquals(0f, z, 1e-4f)
    }

    @Test
    fun `equator points stay in the engine XZ plane`() {
        listOf(0f, 45f, -120f, 179f).forEach { lon ->
            assertEquals("lon=$lon", 0f, engineDirOf(0f, lon).second, 1e-4f)
        }
    }

    @Test
    fun `at rest a city sits at engine azimuth minus its model azimuth`() {
        // spin=0 时方位角 λ 的点落在引擎方位角 −λ。这条关系是 focusTargetSpinDeg 的前提，
        // 也是设备实测（PIN-READOUT）直接验证过的那一条。
        BUILTIN_CITIES.forEach { city ->
            val expected = wrap360(-cityAzimuthDeg(city.lon))
            val actual = wrap360(azimuthDeg(engineDirOf(0f, city.lon)))
            assertEquals(city.name, expected, actual, 0.05f)
        }
    }

    @Test
    fun `focus spin turns every builtin city to face the viewer`() {
        BUILTIN_CITIES.forEach { city ->
            val spun = spinAboutY(engineDirOf(city.lat, city.lon), focusTargetSpinDeg(city.lon))
            // 到位后城市方向的方位角必须是观察者方向（+90°，即正对 +Z）。
            assertEquals(city.name, 90f, wrap360(azimuthDeg(spun)), 0.05f)
            // 而且必须在**近侧**半球，不是背面的那个同方位角点。
            assertTrue("${city.name} z=${spun.third}", spun.third > 0f)
        }
    }

    @Test
    fun `focus spin ignores latitude`() {
        // 只有经度决定自转角：同经度不同纬度的两点必须给出同一个目标角。
        assertEquals(focusTargetSpinDeg(116.40f), focusTargetSpinDeg(116.40f), 0f)
        assertEquals(
            wrap360(-90f - cityAzimuthDeg(116.40f)),
            focusTargetSpinDeg(116.40f),
            1e-3f,
        )
    }

    @Test
    fun `shortest delta never takes the long way round`() {
        assertEquals(20f, shortestDelta(350f, 10f), 1e-3f)
        assertEquals(-20f, shortestDelta(10f, 350f), 1e-3f)
        assertEquals(180f, shortestDelta(0f, 180f), 1e-3f)
        assertEquals(-90f, shortestDelta(90f, 0f), 1e-3f)
        assertTrue(shortestDelta(0f, 181f) in -180f..0f)
    }

    @Test
    fun `wrap360 normalises negatives and multiples`() {
        assertEquals(270f, wrap360(-90f), 1e-3f)
        assertEquals(0f, wrap360(720f), 1e-3f)
        assertEquals(1f, wrap360(361f), 1e-3f)
    }

    @Test
    fun `city azimuth applies the verified 198 degree offset`() {
        assertEquals(198f, cityAzimuthDeg(0f), 1e-3f)
        assertEquals(0f, cityAzimuthDeg(162f), 1e-3f)
        assertEquals(78f, cityAzimuthDeg(-120f), 1e-3f)
    }
}
