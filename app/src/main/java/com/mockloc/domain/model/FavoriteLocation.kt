package com.mockloc.domain.model

data class FavoriteLocation(
    val id: String,
    val name: String,
    val address: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val coordinateType: CoordinateType = CoordinateType.WGS84,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toGeoPoint(): GeoPoint = GeoPoint.createSafe(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude
    )
}
