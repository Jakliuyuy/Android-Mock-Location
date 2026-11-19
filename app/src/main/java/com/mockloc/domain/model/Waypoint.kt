package com.mockloc.domain.model

data class Waypoint(
    val point: GeoPoint,
    val name: String = "",
    val dwellTimeSeconds: Double = 0.0,
    val targetSpeedMps: Float? = null
) {
    init {
        require(dwellTimeSeconds.isFinite() && dwellTimeSeconds >= 0.0) {
            "Dwell time must be finite and >= 0, was: $dwellTimeSeconds"
        }
        targetSpeedMps?.let { speed ->
            require(speed.isFinite() && speed >= 0f) {
                "Target speed must be finite and >= 0, was: $speed"
            }
        }
    }
}
