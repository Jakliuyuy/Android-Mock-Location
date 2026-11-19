package com.mockloc.data.config

import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode

data class MockConfig(
    val enabled: Boolean = false,
    val mode: MockMode = MockMode.AUTO,
    val latitude: Double = DEFAULT_LATITUDE,
    val longitude: Double = DEFAULT_LONGITUDE,
    val altitude: Double = DEFAULT_ALTITUDE,
    val coordinateType: CoordinateType = CoordinateType.WGS84,
    val accuracy: Float = DEFAULT_ACCURACY,
    val speed: Float = 0.0f,
    val bearing: Float = 0.0f,
    val jitterEnabled: Boolean = true,
    val jitterRadiusMeters: Double = DEFAULT_JITTER_RADIUS_METERS,
    val antiDetectCellEnabled: Boolean = true,
    val antiDetectWifiEnabled: Boolean = true,
    val antiDetectGnssEnabled: Boolean = true,
    val targetPackages: Set<String> = emptySet(),
    val updatedAtMs: Long = 0L,
    val version: Long = 0L
) {
    fun sanitized(fallback: MockConfig = DEFAULT): MockConfig {
        val safeFallbackLat = if (fallback.latitude.isFinite()) {
            fallback.latitude.coerceIn(GeoPoint.MIN_LAT, GeoPoint.MAX_LAT)
        } else {
            DEFAULT_LATITUDE
        }
        val safeFallbackLon = if (fallback.longitude.isFinite()) {
            fallback.longitude.coerceIn(GeoPoint.MIN_LON, GeoPoint.MAX_LON)
        } else {
            DEFAULT_LONGITUDE
        }
        val safeFallbackAlt = if (fallback.altitude.isFinite()) fallback.altitude else DEFAULT_ALTITUDE

        val safeLat = if (latitude.isFinite()) {
            latitude.coerceIn(GeoPoint.MIN_LAT, GeoPoint.MAX_LAT)
        } else {
            safeFallbackLat
        }
        val safeLon = if (longitude.isFinite()) {
            longitude.coerceIn(GeoPoint.MIN_LON, GeoPoint.MAX_LON)
        } else {
            safeFallbackLon
        }
        val safeAlt = if (altitude.isFinite()) altitude else safeFallbackAlt

        val safeAcc = if (accuracy.isFinite() && accuracy > 0f) accuracy else DEFAULT_ACCURACY
        val safeSpeed = if (speed.isFinite() && speed >= 0f) speed else 0.0f
        val safeBearing = if (bearing.isFinite()) {
            val mod = ((bearing % 360f) + 360f) % 360f
            if (mod >= 360f) 0f else mod
        } else {
            0.0f
        }
        val safeJitterRadius = if (jitterRadiusMeters.isFinite() && jitterRadiusMeters >= 0.0) {
            jitterRadiusMeters
        } else {
            0.0
        }
        val safePackages = targetPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return copy(
            latitude = safeLat,
            longitude = safeLon,
            altitude = safeAlt,
            accuracy = safeAcc,
            speed = safeSpeed,
            bearing = safeBearing,
            jitterRadiusMeters = safeJitterRadius,
            targetPackages = safePackages,
            updatedAtMs = updatedAtMs.coerceAtLeast(0L),
            version = version.coerceAtLeast(0L)
        )
    }

    fun isEnabledForPackage(packageName: String?): Boolean {
        if (!enabled || packageName.isNullOrBlank()) return false
        return targetPackages.contains(packageName.trim())
    }

    fun toGeoPoint(timestamp: Long = updatedAtMs): GeoPoint {
        val safe = sanitized()
        return GeoPoint(
            latitude = safe.latitude,
            longitude = safe.longitude,
            altitude = safe.altitude,
            accuracy = safe.accuracy,
            timestamp = timestamp
        )
    }

    companion object {
        const val DEFAULT_LATITUDE = 39.9087
        const val DEFAULT_LONGITUDE = 116.3975
        const val DEFAULT_ALTITUDE = 50.0
        const val DEFAULT_ACCURACY = 3.0f
        const val DEFAULT_JITTER_RADIUS_METERS = 2.0

        val DEFAULT = MockConfig()
    }
}
