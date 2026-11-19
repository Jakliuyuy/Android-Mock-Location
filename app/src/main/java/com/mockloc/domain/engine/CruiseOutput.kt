package com.mockloc.domain.engine

import com.mockloc.domain.model.GeoPoint

data class CruiseOutput(
    val point: GeoPoint,
    val speedMps: Double,
    val bearingDeg: Float,
    val progressFraction: Float,
    val activeWaypointIndex: Int,
    val totalWaypoints: Int,
    val distanceTraveledMeters: Double,
    val isCompleted: Boolean
)
