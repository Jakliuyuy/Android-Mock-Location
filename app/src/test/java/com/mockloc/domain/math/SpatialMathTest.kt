package com.mockloc.domain.math

import com.mockloc.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialMathTest {

    @Test
    fun `haversine distance and bearing match real geography`() {
        val p1 = GeoPoint(39.9087, 116.3975)
        val p2 = GeoPoint(39.9187, 116.3975) // 正北约 1.11 km
        val dist = SpatialMath.calculateHaversineDistance(p1, p2)
        assertEquals(1111.9, dist, 15.0)

        val bearing = SpatialMath.calculateBearing(p1, p2)
        assertEquals(0f, bearing, 1.0f)

        assertEquals(0.0, SpatialMath.calculateHaversineDistance(p1, p1), 1e-6)
        assertEquals(0f, SpatialMath.calculateBearing(p1, p1), 1e-6f)
    }

    @Test
    fun `interpolateGreatCircle handles NaN fraction coincident points and antipodal points`() {
        val p1 = GeoPoint(0.0, 0.0, altitude = 10.0, accuracy = 2.0f)
        val antipodal = GeoPoint(0.0, 180.0, altitude = 30.0, accuracy = 6.0f)

        val nanResult = SpatialMath.interpolateGreatCircle(p1, antipodal, Double.NaN)
        assertEquals(p1, nanResult)

        val midAntipodal = SpatialMath.interpolateGreatCircle(p1, antipodal, 0.5)
        assertTrue(GeoPoint.isValid(midAntipodal.latitude, midAntipodal.longitude))
        assertEquals(20.0, midAntipodal.altitude, 1e-6)
        assertEquals(4.0f, midAntipodal.accuracy, 1e-4f)

        val same = SpatialMath.interpolateGreatCircle(p1, p1, 0.5)
        assertEquals(p1.latitude, same.latitude, 1e-9)
        assertEquals(p1.longitude, same.longitude, 1e-9)
    }

    @Test
    fun `offsetByMeters protects poles antimeridian and non-finite inputs`() {
        val nearPole = GeoPoint(89.9999, 179.999)
        val moved = SpatialMath.offsetByMeters(nearPole, northMeters = 5000.0, eastMeters = 5000.0)
        assertTrue(GeoPoint.isValid(moved.latitude, moved.longitude))

        val nanMoved = SpatialMath.offsetByMeters(nearPole, Double.NaN, Double.POSITIVE_INFINITY)
        assertEquals(nearPole, nanMoved)

        val origin = GeoPoint(39.9087, 116.3975)
        val shifted = SpatialMath.offsetByMeters(origin, northMeters = 100.0, eastMeters = 0.0)
        val dist = SpatialMath.calculateHaversineDistance(origin, shifted)
        assertEquals(100.0, dist, 1.0)
    }
}
