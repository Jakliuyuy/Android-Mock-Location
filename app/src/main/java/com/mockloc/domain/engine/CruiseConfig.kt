package com.mockloc.domain.engine

/**
 * 路线巡航引擎配置项。
 *
 * @property baseSpeedMps 基础巡航速度（米/秒），必须非负
 * @property loopMode 循环模式，默认为单向 ONE_WAY
 * @property enableJitter 是否启用 AR-1 速度抖动仿真，默认开启
 * @property enableMicroDrift 是否启用 OU 均值回归位置微漂移仿真，默认开启
 * @property maxDriftRadiusMeters 微漂移最大半径约束（米），默认 4.0 米
 */
data class CruiseConfig(
    val baseSpeedMps: Float,
    val loopMode: LoopMode = LoopMode.ONE_WAY,
    val enableJitter: Boolean = true,
    val enableMicroDrift: Boolean = true,
    val maxDriftRadiusMeters: Double = 4.0
) {
    init {
        require(baseSpeedMps >= 0f) { "baseSpeedMps must be non-negative, was: $baseSpeedMps" }
        require(maxDriftRadiusMeters >= 0.0) { "maxDriftRadiusMeters must be non-negative, was: $maxDriftRadiusMeters" }
    }
}
