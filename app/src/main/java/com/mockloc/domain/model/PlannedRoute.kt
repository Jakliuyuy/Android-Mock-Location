package com.mockloc.domain.model

import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.math.SpatialMath

/**
 * 规划路线模型。
 *
 * @property id 路线唯一标识符
 * @property name 路线显示名称
 * @property waypoints 航路点有序列表
 * @property loopMode 路线循环模式，默认为单向 ONE_WAY
 */
data class PlannedRoute(
    val id: String,
    val name: String,
    val waypoints: List<Waypoint>,
    val loopMode: LoopMode = LoopMode.ONE_WAY
) {
    /**
     * 计算规划路线各航点按顺序连接的总大圆距离（米）。
     * 若航点数量少于 2，返回 0.0。
     */
    fun totalDistanceMeters(): Double {
        if (waypoints.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until waypoints.size - 1) {
            total += SpatialMath.calculateHaversineDistance(
                waypoints[i].point,
                waypoints[i + 1].point
            )
        }
        return total
    }

    /**
     * 获取各个相邻线段的距离（米）列表。
     */
    fun segmentDistances(): List<Double> {
        if (waypoints.size < 2) return emptyList()
        val distances = ArrayList<Double>(waypoints.size - 1)
        for (i in 0 until waypoints.size - 1) {
            distances.add(
                SpatialMath.calculateHaversineDistance(
                    waypoints[i].point,
                    waypoints[i + 1].point
                )
            )
        }
        return distances
    }

    /**
     * 校验路线是否至少包含一个航路点。
     */
    val isValid: Boolean
        get() = waypoints.isNotEmpty()
}
