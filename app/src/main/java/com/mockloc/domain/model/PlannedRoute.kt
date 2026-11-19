package com.mockloc.domain.model

import com.mockloc.domain.math.SpatialMath
import java.util.UUID

data class PlannedRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "未命名路线",
    val waypoints: List<Waypoint> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun isEmpty(): Boolean = waypoints.isEmpty()

    fun isValid(): Boolean = waypoints.isNotEmpty()

    fun segmentCount(): Int = (waypoints.size - 1).coerceAtLeast(0)

    fun segmentDistanceMeters(index: Int): Double {
        if (index < 0 || index >= waypoints.size - 1) return 0.0
        return SpatialMath.calculateHaversineDistance(
            waypoints[index].point,
            waypoints[index + 1].point
        )
    }

    fun closingSegmentDistanceMeters(): Double {
        if (waypoints.size < 2) return 0.0
        return SpatialMath.calculateHaversineDistance(
            waypoints.last().point,
            waypoints.first().point
        )
    }

    fun totalDistanceMeters(): Double {
        if (waypoints.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until waypoints.size - 1) {
            total += segmentDistanceMeters(i)
        }
        return total
    }
}
