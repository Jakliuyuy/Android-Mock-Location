package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

class MotionSimulator(
    private val random: Random = Random.Default
) {
    private var speedResidualMps: Double = 0.0
    private var driftNorthMeters: Double = 0.0
    private var driftEastMeters: Double = 0.0

    fun nextSpeed(
        baseSpeedMps: Double,
        noiseRatio: Double = 0.08,
        enableNoise: Boolean = true,
        deltaSeconds: Double = 1.0
    ): Double {
        if (!baseSpeedMps.isFinite() || baseSpeedMps <= 0.0) {
            speedResidualMps = 0.0
            return 0.0
        }
        if (!enableNoise || !noiseRatio.isFinite() || noiseRatio <= 0.0) {
            speedResidualMps = 0.0
            return baseSpeedMps
        }
        val safeRatio = noiseRatio.coerceIn(0.0, 1.0)
        val maxDelta = baseSpeedMps * safeRatio
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
            return (baseSpeedMps + speedResidualMps).coerceIn(
                (baseSpeedMps - maxDelta).coerceAtLeast(0.0),
                baseSpeedMps + maxDelta
            )
        }

        // AR-1 相关时间常数为 4 秒
        val tau = 4.0
        val phi = exp(-deltaSeconds / tau)
        val sigma = maxDelta * 0.45 * sqrt((1.0 - phi * phi).coerceAtLeast(0.0))
        val gaussian = nextGaussian()
        speedResidualMps = (phi * speedResidualMps + sigma * gaussian).coerceIn(-maxDelta, maxDelta)
        val result = (baseSpeedMps + speedResidualMps).coerceIn(
            (baseSpeedMps - maxDelta).coerceAtLeast(0.0),
            baseSpeedMps + maxDelta
        )
        return if (result.isFinite()) result else baseSpeedMps
    }

    fun applyMicroDrift(
        basePoint: GeoPoint,
        maxDriftRadiusMeters: Double,
        enableDrift: Boolean,
        deltaSeconds: Double = 1.0
    ): GeoPoint {
        if (!enableDrift ||
            !maxDriftRadiusMeters.isFinite() ||
            maxDriftRadiusMeters <= 0.0 ||
            !deltaSeconds.isFinite() ||
            deltaSeconds <= 0.0
        ) {
            return basePoint
        }

        // Ornstein-Uhlenbeck (OU) 微漂移过程
        val theta = 0.35
        val decay = exp(-theta * deltaSeconds)
        val sigma = maxDriftRadiusMeters * 0.35 * sqrt((1.0 - decay * decay).coerceAtLeast(0.0))

        val nextNorth = decay * driftNorthMeters + sigma * nextGaussian()
        val nextEast = decay * driftEastMeters + sigma * nextGaussian()

        if (!nextNorth.isFinite() || !nextEast.isFinite()) {
            return basePoint
        }

        val radius = hypot(nextNorth, nextEast)
        if (radius > maxDriftRadiusMeters && radius > 0.0) {
            val scale = maxDriftRadiusMeters / radius
            driftNorthMeters = nextNorth * scale
            driftEastMeters = nextEast * scale
        } else {
            driftNorthMeters = nextNorth
            driftEastMeters = nextEast
        }

        var candidate = SpatialMath.offsetByMeters(basePoint, driftNorthMeters, driftEastMeters)
        // 高纬度极区经线急速收敛或浮点舍入可能使球面距离微幅溢出 maxDriftRadiusMeters，按实际 Haversine 距离比例回缩
        for (attempt in 0 until 4) {
            val actualDist = SpatialMath.calculateHaversineDistance(basePoint, candidate)
            if (actualDist <= maxDriftRadiusMeters || actualDist <= 0.0) {
                return candidate
            }
            val shrink = (maxDriftRadiusMeters * 0.999999) / actualDist
            driftNorthMeters *= shrink
            driftEastMeters *= shrink
            candidate = SpatialMath.offsetByMeters(basePoint, driftNorthMeters, driftEastMeters)
        }

        if (SpatialMath.calculateHaversineDistance(basePoint, candidate) > maxDriftRadiusMeters) {
            driftNorthMeters = 0.0
            driftEastMeters = 0.0
            return basePoint
        }
        return candidate
    }

    fun reset() {
        speedResidualMps = 0.0
        driftNorthMeters = 0.0
        driftEastMeters = 0.0
    }

    private fun nextGaussian(): Double {
        // Box-Muller 变换（防 u1 == 0 产生 -Infinity）
        val u1 = random.nextDouble().coerceIn(1e-12, 1.0 - 1e-12)
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return if (z.isFinite()) z else 0.0
    }
}
