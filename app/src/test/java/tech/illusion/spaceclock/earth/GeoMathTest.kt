package tech.illusion.spaceclock.earth

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {
    private val r = 0.140328f
    private val eps = 1e-4f

    @Test
    fun `north pole sits on the polar axis`() {
        val (x, y, z) = GeoMath.surfacePoint(90f, 0f, r, lift = 1f)
        assertEquals(0f, x, eps)
        assertEquals(0f, y, eps)
        assertEquals(r, z, eps)
    }

    @Test
    fun `equator prime meridian lands at the 198 degree offset`() {
        // realLon 0 -> modelLon 198°：cos198≈-0.9511, sin198≈-0.3090
        val (x, y, z) = GeoMath.surfacePoint(0f, 0f, r, lift = 1f)
        assertEquals(-0.13346f, x, eps)
        assertEquals(-0.04336f, y, eps)
        assertEquals(0f, z, eps)
    }

    @Test
    fun `beijing matches the verified offline render`() {
        // 设计文档 §4.3 验证过的值（含球心偏移前）：modelLon = 116.4 + 198 = 314.4
        val (x, y, z) = GeoMath.surfacePoint(39.9f, 116.4f, r, lift = 1f)
        assertEquals(0.07533f, x, eps)
        assertEquals(-0.07691f, y, eps)
        assertEquals(0.09004f, z, eps)
    }

    @Test
    fun `lift pushes the point off the surface without changing direction`() {
        val (x0, y0, z0) = GeoMath.surfacePoint(39.9f, 116.4f, r, lift = 1f)
        val (x1, y1, z1) = GeoMath.surfacePoint(39.9f, 116.4f, r, lift = 1.02f)
        assertEquals(x0 * 1.02f, x1, eps)
        assertEquals(y0 * 1.02f, y1, eps)
        assertEquals(z0 * 1.02f, z1, eps)
    }

    @Test
    fun `longitude wraps past 360`() {
        val a = GeoMath.surfacePoint(0f, 200f, r, lift = 1f)   // modelLon 398 -> 38
        val b = GeoMath.surfacePoint(0f, -160f, r, lift = 1f)  // modelLon 38
        assertEquals(a.first, b.first, eps)
        assertEquals(a.second, b.second, eps)
    }
}
