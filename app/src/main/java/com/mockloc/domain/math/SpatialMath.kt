package com.mockloc.domain.math

import com.mockloc.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SpatialMath {
    const val EARTH_RADIUS_METERS = 6_371_000.0

    fun calculateHaversineDistance(p1: GeoPoint, p2: GeoPoint): Double {
        return calculateHaversineDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }

    fun calculateHaversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return 0.0
        }
        val safeLat1 = GeoPoint.clampLatitude(lat1)
        val safeLon1 = GeoPoint.normalizeLongitude(lon1)
        val safeLat2 = GeoPoint.clampLatitude(lat2)
        val safeLon2 = GeoPoint.normalizeLongitude(lon2)

        val dLat = Math.toRadians(safeLat2 - safeLat1)
        val dLon = Math.toRadians(safeLon2 - safeLon1)
        val rLat1 = Math.toRadians(safeLat1)
        val rLat2 = Math.toRadians(safeLat2)

        val a = sin(dLat / 2.0).pow(2) +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2)
        val clampedA = a.coerceIn(0.0, 1.0)
        val c = 2.0 * asin(sqrt(clampedA))
        return EARTH_RADIUS_METERS * c
    }

    fun calculateBearing(p1: GeoPoint, p2: GeoPoint): Float {
        return calculateBearing(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }

    fun calculateBearing(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return 0f
        }
        if (lat1 == lat2 && lon1 == lon2) {
            return 0f
        }
        val rLat1 = Math.toRadians(GeoPoint.clampLatitude(lat1))
        val rLat2 = Math.toRadians(GeoPoint.clampLatitude(lat2))
        val dLon = Math.toRadians(
            GeoPoint.normalizeLongitude(lon2) - GeoPoint.normalizeLongitude(lon1)
        )

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        if (x == 0.0 && y == 0.0) return 0f

        val deg = Math.toDegrees(atan2(y, x))
        val normalized = ((deg % 360.0) + 360.0) % 360.0
        return if (normalized.isFinite()) normalized.toFloat() else 0f
    }

    fun interpolateGreatCircle(p1: GeoPoint, p2: GeoPoint, fraction: Double): GeoPoint {
        if (fraction.isNaN()) return p1
        if (fraction <= 0.0) return p1
        if (fraction >= 1.0) return p2

        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val h = (sin(dLat / 2.0).pow(2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2)).coerceIn(0.0, 1.0)
        val d = 2.0 * asin(sqrt(h))

        val alt = p1.altitude + (p2.altitude - p1.altitude) * fraction
        val acc = (p1.accuracy + (p2.accuracy - p1.accuracy) * fraction).toFloat()

        val sinD = sin(d)
        if (d < 1e-12 || kotlin.math.abs(sinD) < 1e-9) {
            // 重合点或对跖点：退化为安全的经纬度线性插值（防除零与 NaN）
            var deltaLon = p2.longitude - p1.longitude
            if (deltaLon > 180.0) deltaLon -= 360.0
            if (deltaLon < -180.0) deltaLon += 360.0
            val outLat = p1.latitude + (p2.latitude - p1.latitude) * fraction
            val outLon = p1.longitude + deltaLon * fraction
            return GeoPoint.createSafe(
                latitude = outLat,
                longitude = outLon,
                altitude = alt,
                accuracy = acc,
                timestamp = p1.timestamp,
                normalizeLon = true
            )
        }

        val a = sin((1.0 - fraction) * d) / sinD
        val b = sin(fraction * d) / sinD

        val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        val z = a * sin(lat1) + b * sin(lat2)

        val outLat = Math.toDegrees(atan2(z, sqrt(x * x + y * y)))
        val outLon = Math.toDegrees(atan2(y, x))

        return GeoPoint.createSafe(
            latitude = outLat,
            longitude = outLon,
            altitude = alt,
            accuracy = acc,
            timestamp = p1.timestamp,
            fallbackLatitude = p1.latitude,
            fallbackLongitude = p1.longitude,
            normalizeLon = true
        )
    }

    fun offsetByMeters(point: GeoPoint, northMeters: Double, eastMeters: Double): GeoPoint {
        if (!northMeters.isFinite() || !eastMeters.isFinite()) return point
        if (northMeters == 0.0 && eastMeters == 0.0) return point

        val dLatDeg = (northMeters / EARTH_RADIUS_METERS) * (180.0 / PI)
        val newLat = GeoPoint.clampLatitude(point.latitude + dLatDeg, point.latitude)

        val cosLat = cos(Math.toRadians(newLat)).coerceAtLeast(1e-6)
        val dLonDeg = (eastMeters / (EARTH_RADIUS_METERS * cosLat)) * (180.0 / PI)
        val newLon = GeoPoint.normalizeLongitude(point.longitude + dLonDeg, point.longitude)

        return GeoPoint.createSafe(
            latitude = newLat,
            longitude = newLon,
            altitude = point.altitude,
            accuracy = point.accuracy,
            timestamp = point.timestamp,
            fallbackLatitude = point.latitude,
            fallbackLongitude = point.longitude,
            normalizeLon = true
        )
    }
}
