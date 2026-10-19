package com.mockloc.domain.model

/**
 * 纯领域层地理坐标点模型，与 Android SDK 解耦，用于跨平台与纯 JVM 测试。
 *
 * @property latitude 纬度，取值范围 [-90.0, 90.0]
 * @property longitude 经度，取值范围 [-180.0, 180.0]
 * @property altitude 海拔高度（单位：米），默认为 0.0
 * @property accuracy 定位精度（单位：米），默认为 5.0f
 * @property timestamp 时间戳（单位：毫秒），默认为当前系统时间
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 5.0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    init {
        require(latitude in -90.0..90.0) { "Latitude must be in [-90.0, 90.0], was: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude must be in [-180.0, 180.0], was: $longitude" }
    }
}
