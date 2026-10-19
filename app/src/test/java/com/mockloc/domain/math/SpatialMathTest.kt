package com.mockloc.domain.math

import com.mockloc.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 SpatialMath 空间数学引擎的全面单元测试。
 * 覆盖已知城市距离、四大基准方位角、大圆插值等分性、边界条件及物理属性插值。
 */
class SpatialMathTest {

    private val delta = 0.001
    private val floatDelta = 0.01f

    @Test
    fun testHaversineDistanceKnownCities() {
        // 北京天安门 (39.9042, 116.4074) 至 上海东方明珠 (31.2304, 121.4737)
        val beijing = GeoPoint(39.9042, 116.4074)
        val shanghai = GeoPoint(31.2304, 121.4737)

        val distance = SpatialMath.calculateHaversineDistance(beijing, shanghai)

        // 理论大圆球面距离约为 1067.3 km
        assertTrue("Distance should be around 1067 km, actual: $distance", distance in 1060000.0..1075000.0)
    }

    @Test
    fun testHaversineDistanceIdenticalPoint() {
        val point = GeoPoint(39.9042, 116.4074)
        val distance = SpatialMath.calculateHaversineDistance(point, point)
        assertEquals(0.0, distance, delta)
    }

    @Test
    fun testHaversineDistanceSymmetry() {
        val p1 = GeoPoint(22.5431, 114.0579) // 深圳
        val p2 = GeoPoint(30.6574, 104.0658) // 成都

        val d12 = SpatialMath.calculateHaversineDistance(p1, p2)
        val d21 = SpatialMath.calculateHaversineDistance(p2, p1)

        assertEquals(d12, d21, delta)
        assertTrue(d12 > 1000000.0) // 距离大于 1000 km
    }

    @Test
    fun testBearingCardinalDirections() {
        val origin = GeoPoint(0.0, 0.0)

        // 正北
        val north = GeoPoint(1.0, 0.0)
        assertEquals(0.0f, SpatialMath.calculateBearing(origin, north), floatDelta)

        // 正东
        val east = GeoPoint(0.0, 1.0)
        assertEquals(90.0f, SpatialMath.calculateBearing(origin, east), floatDelta)

        // 正南
        val south = GeoPoint(-1.0, 0.0)
        assertEquals(180.0f, SpatialMath.calculateBearing(origin, south), floatDelta)

        // 正西
        val west = GeoPoint(0.0, -1.0)
        assertEquals(270.0f, SpatialMath.calculateBearing(origin, west), floatDelta)
    }

    @Test
    fun testBearingNorthEast() {
        val origin = GeoPoint(0.0, 0.0)
        val northEast = GeoPoint(1.0, 1.0)
        val bearing = SpatialMath.calculateBearing(origin, northEast)
        // 接近 45 度 (赤道附近 (1,1) 方位角略小于 45 度)
        assertTrue("Bearing should be near 45 deg, actual: $bearing", bearing in 44.0f..46.0f)
    }

    @Test
    fun testGreatCircleInterpolationEndpoints() {
        val p1 = GeoPoint(10.0, 20.0, altitude = 100.0, accuracy = 3.0f, timestamp = 1000L)
        val p2 = GeoPoint(30.0, 40.0, altitude = 300.0, accuracy = 7.0f, timestamp = 3000L)

        // fraction <= 0 返回起点
        val start = SpatialMath.interpolateGreatCircle(p1, p2, 0.0)
        assertEquals(p1.latitude, start.latitude, delta)
        assertEquals(p1.longitude, start.longitude, delta)
        assertEquals(p1.altitude, start.altitude, delta)
        assertEquals(p1.accuracy, start.accuracy, floatDelta)
        assertEquals(p1.timestamp, start.timestamp)

        val negativeFraction = SpatialMath.interpolateGreatCircle(p1, p2, -0.5)
        assertEquals(p1, negativeFraction)

        // fraction >= 1 返回终点
        val end = SpatialMath.interpolateGreatCircle(p1, p2, 1.0)
        assertEquals(p2.latitude, end.latitude, delta)
        assertEquals(p2.longitude, end.longitude, delta)
        assertEquals(p2.altitude, end.altitude, delta)
        assertEquals(p2.accuracy, end.accuracy, floatDelta)
        assertEquals(p2.timestamp, end.timestamp)

        val overflowFraction = SpatialMath.interpolateGreatCircle(p1, p2, 1.5)
        assertEquals(p2, overflowFraction)
    }

    @Test
    fun testGreatCircleInterpolationMidpointEquidistance() {
        val p1 = GeoPoint(20.0, 100.0, altitude = 50.0, accuracy = 4.0f, timestamp = 1000L)
        val p2 = GeoPoint(25.0, 110.0, altitude = 150.0, accuracy = 8.0f, timestamp = 5000L)

        val mid = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)

        val d1 = SpatialMath.calculateHaversineDistance(p1, mid)
        val d2 = SpatialMath.calculateHaversineDistance(mid, p2)
        val totalDistance = SpatialMath.calculateHaversineDistance(p1, p2)

        // 中点到两端距离相等，误差小于 1 米
        assertEquals("Midpoint distances must be equal", d1, d2, 1.0)
        // 分段距离之和等于总距离
        assertEquals(totalDistance, d1 + d2, 1.0)

        // 验证高度、精度与时间戳的线性插值
        assertEquals(100.0, mid.altitude, delta)
        assertEquals(6.0f, mid.accuracy, floatDelta)
        assertEquals(3000L, mid.timestamp)
    }

    @Test
    fun testGreatCircleInterpolationCoincidentPoints() {
        val p1 = GeoPoint(31.2304, 121.4737, altitude = 10.0)
        val p2 = GeoPoint(31.2304, 121.4737, altitude = 20.0)

        val result = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        assertEquals(p1.latitude, result.latitude, delta)
        assertEquals(p1.longitude, result.longitude, delta)
    }
}
