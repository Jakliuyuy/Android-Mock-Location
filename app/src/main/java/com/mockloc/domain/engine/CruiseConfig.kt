package com.mockloc.domain.engine

data class CruiseConfig(
    val speedMps: Double = SpeedPreset.WALK.speedMps,
    val loopMode: LoopMode = LoopMode.ONE_WAY,
    val enableSpeedNoise: Boolean = true,
    val speedNoiseRatio: Double = 0.08,
    val enableMicroDrift: Boolean = true,
    val maxDriftRadiusMeters: Double = 1.5
) {
    init {
        require(speedMps.isFinite() && speedMps >= 0.0) {
            "speedMps must be finite and >= 0, was: $speedMps"
        }
        require(speedNoiseRatio.isFinite() && speedNoiseRatio in 0.0..1.0) {
            "speedNoiseRatio must be finite and in [0.0, 1.0], was: $speedNoiseRatio"
        }
        require(maxDriftRadiusMeters.isFinite() && maxDriftRadiusMeters >= 0.0) {
            "maxDriftRadiusMeters must be finite and >= 0, was: $maxDriftRadiusMeters"
        }
    }
}
