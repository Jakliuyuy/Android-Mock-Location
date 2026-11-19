package com.mockloc.domain.model

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 5.0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be finite and in [-90.0, 90.0], was: $latitude"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be finite and in [-180.0, 180.0], was: $longitude"
        }
        require(altitude.isFinite()) {
            "Altitude must be finite, was: $altitude"
        }
        require(accuracy.isFinite() && accuracy >= 0f) {
            "Accuracy must be finite and >= 0, was: $accuracy"
        }
    }

    companion object {
        const val MIN_LAT = -90.0
        const val MAX_LAT = 90.0
        const val MIN_LON = -180.0
        const val MAX_LON = 180.0

        fun isValid(latitude: Double, longitude: Double): Boolean {
            return latitude.isFinite() &&
                longitude.isFinite() &&
                latitude in MIN_LAT..MAX_LAT &&
                longitude in MIN_LON..MAX_LON
        }

        fun clampLatitude(latitude: Double, fallback: Double = 0.0): Double {
            val safeFallback = if (fallback.isFinite()) fallback.coerceIn(MIN_LAT, MAX_LAT) else 0.0
            return if (latitude.isFinite()) latitude.coerceIn(MIN_LAT, MAX_LAT) else safeFallback
        }

        fun clampLongitude(longitude: Double, fallback: Double = 0.0): Double {
            val safeFallback = if (fallback.isFinite()) fallback.coerceIn(MIN_LON, MAX_LON) else 0.0
            return if (longitude.isFinite()) longitude.coerceIn(MIN_LON, MAX_LON) else safeFallback
        }

        fun normalizeLongitude(longitude: Double, fallback: Double = 0.0): Double {
            if (!longitude.isFinite()) {
                return if (fallback.isFinite()) normalizeLongitude(fallback, 0.0) else 0.0
            }
            if (longitude in MIN_LON..MAX_LON) return longitude
            var normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            if (normalized == -180.0 && longitude > 0.0) {
                normalized = 180.0
            }
            return normalized.coerceIn(MIN_LON, MAX_LON)
        }

        fun createSafe(
            latitude: Double,
            longitude: Double,
            altitude: Double = 0.0,
            accuracy: Float = 5.0f,
            timestamp: Long = System.currentTimeMillis(),
            fallbackLatitude: Double = 0.0,
            fallbackLongitude: Double = 0.0,
            normalizeLon: Boolean = false
        ): GeoPoint {
            val safeLat = clampLatitude(latitude, fallbackLatitude)
            val safeLon = if (normalizeLon) {
                normalizeLongitude(longitude, fallbackLongitude)
            } else {
                clampLongitude(longitude, fallbackLongitude)
            }
            val safeAlt = if (altitude.isFinite()) altitude else 0.0
            val safeAcc = if (accuracy.isFinite() && accuracy >= 0f) accuracy else 5.0f
            return GeoPoint(
                latitude = safeLat,
                longitude = safeLon,
                altitude = safeAlt,
                accuracy = safeAcc,
                timestamp = timestamp
            )
        }
    }
}
