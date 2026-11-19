package com.mockloc.domain.model

data class GeocodingResult(
    val name: String,
    val address: String = "",
    val latitude: Double,
    val longitude: Double,
    val coordinateType: CoordinateType = CoordinateType.WGS84,
    val city: String = "",
    val source: String = ""
) {
    fun toGeoPoint(): GeoPoint = GeoPoint.createSafe(
        latitude = latitude,
        longitude = longitude
    )
}
