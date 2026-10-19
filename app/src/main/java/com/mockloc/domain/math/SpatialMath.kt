package com.mockloc.domain.math

import com.mockloc.domain.model.GeoPoint
import kotlin.math.*

/**
 * 空间几何与球面距离计算工具类（基于 WGS84 椭球体平均球面模型）。
 * 纯 JVM 独立计算，无外部 SDK 依赖。
 */
object SpatialMath {
    /** WGS84 平均地球半径（单位：米） */
    const val EARTH_RADIUS = 6371000.0

    /**
     * 使用 Haversine 公式计算两个地理坐标点之间的球面大圆距离（单位：米）。
     *
     * @param p1 起点
     * @param p2 终点
     * @return 两点间的距离（米）
     */
    fun calculateHaversineDistance(p1: GeoPoint, p2: GeoPoint): Double {
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLat = Math.toRadians(p2.latitude - p1.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)

        val sinHalfDeltaLat = sin(deltaLat / 2.0)
        val sinHalfDeltaLon = sin(deltaLon / 2.0)

        val a = (sinHalfDeltaLat * sinHalfDeltaLat +
                cos(lat1Rad) * cos(lat2Rad) * sinHalfDeltaLon * sinHalfDeltaLon).coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS * c
    }

    /**
     * 计算从起点 p1 指向终点 p2 的大圆初始方位角（Initial Bearing）。
     *
     * @param p1 起点
     * @param p2 终点
     * @return 方位角，取值范围 [0.0, 360.0) 度，以正北为 0 度，顺时针增加
     */
    fun calculateBearing(p1: GeoPoint, p2: GeoPoint): Float {
        val lat1Rad = Math.toRadians(p1.latitude)
        val lat2Rad = Math.toRadians(p2.latitude)
        val deltaLon = Math.toRadians(p2.longitude - p1.longitude)

        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

        val degrees = Math.toDegrees(atan2(y, x))
        val normalized = (degrees + 360.0) % 360.0
        return normalized.toFloat()
    }

    /**
     * 在起点 p1 和终点 p2 之间进行球面大圆插值（Slerp）。
     * 同时对海拔高度和定位精度进行线性插值。
     *
     * @param p1 起点坐标
     * @param p2 终点坐标
     * @param fraction 插值比例系数，取值通常在 [0.0, 1.0] 之间
     * @return 插值计算后的地理坐标点
     */
    fun interpolateGreatCircle(p1: GeoPoint, p2: GeoPoint, fraction: Double): GeoPoint {
        if (fraction <= 0.0) return p1
        if (fraction >= 1.0) return p2

        val lat1 = Math.toRadians(p1.latitude)
        val lon1 = Math.toRadians(p1.longitude)
        val lat2 = Math.toRadians(p2.latitude)
        val lon2 = Math.toRadians(p2.longitude)

        val d = calculateHaversineDistance(p1, p2) / EARTH_RADIUS
        if (d < 1e-9) return p1 // 两点重合，避免分母为 0

        val sinD = sin(d)
        val a = sin((1.0 - fraction) * d) / sinD
        val b = sin(fraction * d) / sinD

        val x = a * cos(lat1) * cos(lon1) + b * cos(lat2) * cos(lon2)
        val y = a * cos(lat1) * sin(lon1) + b * cos(lat2) * sin(lon2)
        val z = a * sin(lat1) + b * sin(lat2)

        val lat = atan2(z, sqrt(x * x + y * y))
        val lon = atan2(y, x)

        val alt = p1.altitude + (p2.altitude - p1.altitude) * fraction
        val acc = (p1.accuracy + (p2.accuracy - p1.accuracy) * fraction).toFloat()
        val time = (p1.timestamp + ((p2.timestamp - p1.timestamp) * fraction).toLong())

        return GeoPoint(
            latitude = Math.toDegrees(lat),
            longitude = Math.toDegrees(lon),
            altitude = alt,
            accuracy = acc,
            timestamp = time
        )
    }
}
