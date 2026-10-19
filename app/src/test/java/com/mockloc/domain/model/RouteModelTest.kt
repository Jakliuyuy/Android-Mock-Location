package com.mockloc.domain.model

import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.math.SpatialMath
import org.junit.Assert.*
import org.junit.Test

/**
 * 路线与模型数据结构 (Waypoint, PlannedRoute, SpeedPreset, CruiseConfig) 单元测试。
 */
class RouteModelTest {

    @Test
    fun testWaypointDefaultsAndEffectiveAltitude() {
        val p = GeoPoint(30.0, 120.0, altitude = 88.0)
        val wpDefault = Waypoint(point = p)

        assertEquals(0.0, wpDefault.altitude, 0.0)
        assertEquals(88.0, wpDefault.effectiveAltitude, 0.0)
        assertNull(wpDefault.speedMps)
        assertEquals(0.0, wpDefault.dwellTimeSeconds, 0.0)

        val wpExplicit = Waypoint(point = p, altitude = 120.0, speedMps = 15.0f, dwellTimeSeconds = 3.5)
        assertEquals(120.0, wpExplicit.altitude, 0.0)
        assertEquals(120.0, wpExplicit.effectiveAltitude, 0.0)
        assertEquals(15.0f, wpExplicit.speedMps!!, 0.0f)
        assertEquals(3.5, wpExplicit.dwellTimeSeconds, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testWaypointRejectsNegativeDwellTime() {
        Waypoint(point = GeoPoint(0.0, 0.0), dwellTimeSeconds = -0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testWaypointRejectsNegativeSpeed() {
        Waypoint(point = GeoPoint(0.0, 0.0), speedMps = -1.0f)
    }

    @Test
    fun testPlannedRouteDistanceCalculation() {
        val emptyRoute = PlannedRoute("empty", "Empty", emptyList())
        assertEquals(0.0, emptyRoute.totalDistanceMeters(), 0.0)
        assertTrue(emptyRoute.segmentDistances().isEmpty())
        assertFalse(emptyRoute.isValid)

        val singlePointRoute = PlannedRoute("single", "Single", listOf(Waypoint(GeoPoint(0.0, 0.0))))
        assertEquals(0.0, singlePointRoute.totalDistanceMeters(), 0.0)
        assertTrue(singlePointRoute.segmentDistances().isEmpty())
        assertTrue(singlePointRoute.isValid)

        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 1.0)
        val p3 = GeoPoint(0.0, 2.0)
        val route = PlannedRoute("multi", "Multi", listOf(Waypoint(p1), Waypoint(p2), Waypoint(p3)))

        val d1 = SpatialMath.calculateHaversineDistance(p1, p2)
        val d2 = SpatialMath.calculateHaversineDistance(p2, p3)
        val totalExpected = d1 + d2

        assertEquals(totalExpected, route.totalDistanceMeters(), 0.001)
        val segments = route.segmentDistances()
        assertEquals(2, segments.size)
        assertEquals(d1, segments[0], 0.001)
        assertEquals(d2, segments[1], 0.001)
    }

    @Test
    fun testSpeedPresetsValues() {
        assertEquals(1.2f, SpeedPreset.WALK.speedMps, 0.01f)
        assertEquals(4.5f, SpeedPreset.BIKE.speedMps, 0.01f)
        assertEquals(12.0f, SpeedPreset.DRIVE.speedMps, 0.01f)

        assertEquals(1.2f * 3.6f, SpeedPreset.WALK.speedKmh, 0.05f)
        assertEquals(4.5f * 3.6f, SpeedPreset.BIKE.speedKmh, 0.05f)
        assertEquals(12.0f * 3.6f, SpeedPreset.DRIVE.speedKmh, 0.05f)
    }

    @Test
    fun testCruiseConfigValidation() {
        val validConfig = CruiseConfig(baseSpeedMps = 5.0f, loopMode = LoopMode.LOOP)
        assertEquals(5.0f, validConfig.baseSpeedMps, 0.0f)
        assertEquals(LoopMode.LOOP, validConfig.loopMode)
        assertTrue(validConfig.enableJitter)
        assertTrue(validConfig.enableMicroDrift)
        assertEquals(4.0, validConfig.maxDriftRadiusMeters, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCruiseConfigRejectsNegativeSpeed() {
        CruiseConfig(baseSpeedMps = -2.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCruiseConfigRejectsNegativeDriftRadius() {
        CruiseConfig(baseSpeedMps = 2.0f, maxDriftRadiusMeters = -1.0)
    }
}
