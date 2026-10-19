package com.mockloc.domain.engine

import com.mockloc.domain.model.GeoPoint

/**
 * 巡航引擎单步积分输出结果。
 *
 * @property point 当前经纬度坐标（叠加微漂移后）
 * @property altitude 当前海拔高度（米）
 * @property speedMps 当前瞬时速度（米/秒，叠加抖动后）
 * @property bearingDeg 当前运动航向方位角（[0.0, 360.0) 度）
 * @property progressRatio 总体进度比例（[0.0, 1.0]）
 * @property isCompleted 是否已抵达终点并完成巡航
 * @property currentSegmentIndex 当前所在线段索引（从 0 开始）
 * @property distanceTraveledMeters 累计行驶距离（米）
 */
data class CruiseOutput(
    val point: GeoPoint,
    val altitude: Double,
    val speedMps: Float,
    val bearingDeg: Float,
    val progressRatio: Float,
    val isCompleted: Boolean,
    val currentSegmentIndex: Int,
    val distanceTraveledMeters: Double
)
