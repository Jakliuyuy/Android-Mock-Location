package com.mockloc.domain.math

import com.mockloc.domain.model.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * 针对 SpatialMath 的对抗性压力测试 (Empirical Challenger Suite)。
 * 严苛覆盖：对跖点 (Antipodes)、两极点 (Poles)、反子午线跨越 (Dateline)、
 * 重合与微米极近点 (Identical/Near-Identical)、极端与非法插值比例 (Fractions)、数值稳定性与度量公理。
 */
class SpatialMathAdversarialTest {

    private val delta = 0.001
    private val distToleranceMeters = 0.1 // 10 厘米容差

    // =========================================================================
    // 1. 对跖点测试 (Antipodal Points: d = π * R)
    // =========================================================================

    @Test
    fun testAntipodalDistanceEquator() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 180.0)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        val expected = Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, distToleranceMeters)
    }

    @Test
    fun testAntipodalDistanceEquatorArbitrary() {
        val p1 = GeoPoint(0.0, 45.0)
        val p2 = GeoPoint(0.0, -135.0)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        val expected = Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, distToleranceMeters)
    }

    @Test
    fun testAntipodalDistanceGeneralLatitude() {
        val p1 = GeoPoint(35.0, 120.0)
        val p2 = GeoPoint(-35.0, -60.0)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        val expected = Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, 0.20)
    }

    @Test
    fun testAntipodalDistanceHighLatitude() {
        val p1 = GeoPoint(89.9999, 10.0)
        val p2 = GeoPoint(-89.9999, -170.0)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        val expected = Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, 1.0)
    }

    @Test
    fun testAntipodalInterpolationDoesNotCrash() {
        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 180.0)
        // 在对跖点上，Slerp 的 sinD 接近 0
        val mid = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        assertNotNull(mid)
        assertFalse("Latitude must not be NaN", mid.latitude.isNaN())
        assertFalse("Longitude must not be NaN", mid.longitude.isNaN())
        assertTrue("Latitude must be in valid range", mid.latitude in -90.0..90.0)
        assertTrue("Longitude must be in valid range", mid.longitude in -180.0..180.0)
    }

    // =========================================================================
    // 2. 两极点测试 (Poles: +90.0, -90.0)
    // =========================================================================

    @Test
    fun testNorthPoleToSouthPoleDistance() {
        val northPole = GeoPoint(90.0, 0.0)
        val southPole = GeoPoint(-90.0, 0.0)
        val dist = SpatialMath.calculateHaversineDistance(northPole, southPole)
        val expected = Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, distToleranceMeters)
    }

    @Test
    fun testPolesInternalDistanceAtDifferentLongitudes() {
        // 北极点无论经度如何，物理上均为同一点
        val np1 = GeoPoint(90.0, 0.0)
        val np2 = GeoPoint(90.0, 120.0)
        val dist = SpatialMath.calculateHaversineDistance(np1, np2)
        assertEquals(0.0, dist, 0.01)

        val sp1 = GeoPoint(-90.0, -60.0)
        val sp2 = GeoPoint(-90.0, 150.0)
        val distSp = SpatialMath.calculateHaversineDistance(sp1, sp2)
        assertEquals(0.0, distSp, 0.01)
    }

    @Test
    fun testBearingFromNorthPoleDueSouth() {
        val northPole = GeoPoint(90.0, 0.0)
        val target = GeoPoint(0.0, 0.0)
        val bearing = SpatialMath.calculateBearing(northPole, target)
        assertEquals(180.0f, bearing, 0.01f)
    }

    @Test
    fun testBearingFromSouthPoleDueNorth() {
        val southPole = GeoPoint(-90.0, 0.0)
        val target = GeoPoint(0.0, 0.0)
        val bearing = SpatialMath.calculateBearing(southPole, target)
        assertEquals(0.0f, bearing, 0.01f)
    }

    @Test
    fun testInterpolationThroughNorthPole() {
        // 从经度 0度 纬度 80度 穿过北极到 经度 180度 纬度 80度
        val p1 = GeoPoint(80.0, 0.0)
        val p2 = GeoPoint(80.0, 180.0)
        val mid = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        // 中点应当恰好落在北极点 (纬度 90度)
        assertEquals(90.0, mid.latitude, 0.01)
    }

    // =========================================================================
    // 3. 国际日界线 / 反子午线跨越测试 (Dateline: 180° / -180°)
    // =========================================================================

    @Test
    fun testDatelineCrossingDistanceEquator() {
        val p1 = GeoPoint(0.0, 179.5)
        val p2 = GeoPoint(0.0, -179.5)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        // 跨越 1.0 度经度，大圆距离
        val expected = (1.0 / 360.0) * 2 * Math.PI * SpatialMath.EARTH_RADIUS
        assertEquals(expected, dist, 1.0)
    }

    @Test
    fun testDatelineCrossingDistanceHighLatitude() {
        val p1 = GeoPoint(60.0, 179.0)
        val p2 = GeoPoint(60.0, -179.0)
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        // 经度差 2 度，纬度 60度 (周长减半)
        val directDist = SpatialMath.calculateHaversineDistance(p2, p1)
        assertEquals(dist, directDist, 0.001)
        assertTrue(dist < 250000.0) // 不应绕过整个地球（>39000km）
    }

    @Test
    fun testDatelineCrossingBearingEastbound() {
        val p1 = GeoPoint(0.0, 179.5)
        val p2 = GeoPoint(0.0, -179.5)
        val bearing = SpatialMath.calculateBearing(p1, p2)
        assertEquals(90.0f, bearing, 0.01f)
    }

    @Test
    fun testDatelineCrossingBearingWestbound() {
        val p1 = GeoPoint(0.0, -179.5)
        val p2 = GeoPoint(0.0, 179.5)
        val bearing = SpatialMath.calculateBearing(p1, p2)
        assertEquals(270.0f, bearing, 0.01f)
    }

    @Test
    fun testDatelineInterpolationMidpoint() {
        val p1 = GeoPoint(10.0, 179.0)
        val p2 = GeoPoint(10.0, -179.0)
        val mid = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        // 中点经度应为 180.0 或 -180.0
        assertTrue("Expected mid longitude near 180 or -180, got ${mid.longitude}",
            abs(abs(mid.longitude) - 180.0) < 0.001)
        assertEquals(10.0, mid.latitude, 0.01)
    }

    // =========================================================================
    // 4. 重合点与微米级极近点测试 (Identical / Near-Identical)
    // =========================================================================

    @Test
    fun testIdenticalPointsMetrics() {
        val p = GeoPoint(34.0522, -118.2437)
        val dist = SpatialMath.calculateHaversineDistance(p, p)
        assertEquals(0.0, dist, 1e-9)

        val bearing = SpatialMath.calculateBearing(p, p)
        assertFalse("Bearing must not be NaN", bearing.isNaN())
        assertEquals(0.0f, bearing, 0.001f)

        val interpolated = SpatialMath.interpolateGreatCircle(p, p, 0.5)
        assertEquals(p.latitude, interpolated.latitude, 1e-9)
        assertEquals(p.longitude, interpolated.longitude, 1e-9)
    }

    @Test
    fun testNearIdenticalPointsStability() {
        val p1 = GeoPoint(30.0, 120.0)
        val p2 = GeoPoint(30.0, 120.000000001) // 约 0.1 毫米距离
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        assertTrue(dist >= 0.0)
        assertTrue(dist < 0.01) // 小于 1 厘米

        val mid = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        assertEquals(p1.latitude, mid.latitude, 1e-6)
        assertEquals(p1.longitude, mid.longitude, 1e-6)
    }

    // =========================================================================
    // 5. 极端与边界比例测试 (Extreme & Boundary Fractions)
    // =========================================================================

    @Test
    fun testBoundaryFractionsClampExact() {
        val p1 = GeoPoint(10.0, 20.0)
        val p2 = GeoPoint(30.0, 40.0)
        assertEquals(p1, SpatialMath.interpolateGreatCircle(p1, p2, 0.0))
        assertEquals(p2, SpatialMath.interpolateGreatCircle(p1, p2, 1.0))
        assertEquals(p1, SpatialMath.interpolateGreatCircle(p1, p2, -0.0001))
        assertEquals(p1, SpatialMath.interpolateGreatCircle(p1, p2, -1e12))
        assertEquals(p2, SpatialMath.interpolateGreatCircle(p1, p2, 1.0001))
        assertEquals(p2, SpatialMath.interpolateGreatCircle(p1, p2, 1e12))
        assertEquals(p1, SpatialMath.interpolateGreatCircle(p1, p2, Double.NEGATIVE_INFINITY))
        assertEquals(p2, SpatialMath.interpolateGreatCircle(p1, p2, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testNaNFractionHandling() {
        val p1 = GeoPoint(10.0, 20.0)
        val p2 = GeoPoint(30.0, 40.0)
        try {
            val result = SpatialMath.interpolateGreatCircle(p1, p2, Double.NaN)
            // 如果返回，则不应有非法坐标
            assertFalse(result.latitude.isNaN())
            assertFalse(result.longitude.isNaN())
        } catch (e: Exception) {
            // 抛出异常是预期的防护行为（阻止非法坐标逃逸到系统层）
            assertTrue("Expected IllegalArgumentException on NaN", e is IllegalArgumentException)
        }
    }

    // =========================================================================
    // 6. 数值稳定性与度量公理 (Metric Axioms & Monotonicity)
    // =========================================================================

    @Test
    fun testTriangleInequality() {
        val pA = GeoPoint(39.9042, 116.4074) // 北京
        val pB = GeoPoint(31.2304, 121.4737) // 上海
        val pC = GeoPoint(23.1291, 113.2644) // 广州

        val dAC = SpatialMath.calculateHaversineDistance(pA, pC)
        val dAB = SpatialMath.calculateHaversineDistance(pA, pB)
        val dBC = SpatialMath.calculateHaversineDistance(pB, pC)

        assertTrue("Triangle inequality violated: d(A,C) > d(A,B) + d(B,C)", dAC <= dAB + dBC + 1e-3)
    }

    @Test
    fun testDistanceSymmetryComprehensive() {
        val pairs = listOf(
            GeoPoint(0.0, 0.0) to GeoPoint(45.0, 90.0),
            GeoPoint(-30.0, -50.0) to GeoPoint(60.0, 140.0),
            GeoPoint(89.0, 179.0) to GeoPoint(-89.0, -179.0),
            GeoPoint(0.0, 179.999) to GeoPoint(0.0, -179.999)
        )
        for ((a, b) in pairs) {
            val dAB = SpatialMath.calculateHaversineDistance(a, b)
            val dBA = SpatialMath.calculateHaversineDistance(b, a)
            assertEquals("Symmetry failed for $a and $b", dAB, dBA, 1e-4)
        }
    }

    @Test
    fun testInterpolationMonotonicityAlongPath() {
        val p1 = GeoPoint(10.0, 20.0)
        val p2 = GeoPoint(50.0, 80.0)
        val totalDist = SpatialMath.calculateHaversineDistance(p1, p2)
        var lastDist = 0.0

        for (step in 1..20) {
            val fraction = step / 20.0
            val pt = SpatialMath.interpolateGreatCircle(p1, p2, fraction)
            val dFromStart = SpatialMath.calculateHaversineDistance(p1, pt)
            assertTrue("Distance must increase monotonically at step $step: last=$lastDist, cur=$dFromStart",
                dFromStart >= lastDist - 1e-6)
            lastDist = dFromStart
        }
        assertEquals(totalDist, lastDist, 1.0)
    }
}
