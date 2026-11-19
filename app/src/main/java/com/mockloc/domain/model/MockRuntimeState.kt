package com.mockloc.domain.model

data class MockRuntimeState(
    val status: MockRunStatus = MockRunStatus.Idle,
    val activeMode: MockMode = MockMode.AUTO,
    val currentPoint: GeoPoint? = null,
    val speedMps: Double = 0.0,
    val bearingDeg: Float = 0f,
    val progressFraction: Float = 0f,
    val activeWaypointIndex: Int = 0,
    val totalWaypoints: Int = 0,
    val lastSyncTimeMs: Long = 0L,
    val errorMessage: String? = null
)
