package com.mockloc.domain.model

/**
 * 路线中的航路点模型。
 *
 * @property point 地理坐标点（经纬度）
 * @property altitude 航路点指定高度（米），默认 0.0
 * @property speedMps 航路点指定段速度（米/秒），为 null 时采用全局巡航配置速度
 * @property dwellTimeSeconds 到达该航路点后的停留等待时间（秒），默认 0.0
 */
data class Waypoint(
    val point: GeoPoint,
    val altitude: Double = 0.0,
    val speedMps: Float? = null,
    val dwellTimeSeconds: Double = 0.0
) {
    init {
        require(dwellTimeSeconds >= 0.0) { "dwellTimeSeconds must be non-negative, was: $dwellTimeSeconds" }
        require(speedMps == null || speedMps >= 0f) { "speedMps must be non-negative if specified, was: $speedMps" }
    }

    /**
     * 获取有效高度：若自身显式指定了非 0 高度则使用自身高度，否则回退至 point 的高度。
     */
    val effectiveAltitude: Double
        get() = if (altitude != 0.0) altitude else point.altitude
}
