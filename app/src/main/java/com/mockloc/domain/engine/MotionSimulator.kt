package com.mockloc.domain.engine

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.GeoPoint
import java.util.Random
import kotlin.math.*

/**
 * 运动仿真器：提供基于物理与统计模型的速度噪声抖动（AR-1）和位置微漂移（Ornstein-Uhlenbeck 均值回归）。
 *
 * @param seed 可选的随机数种子，传入非空值可确保仿真序列的确定性与可重复性（常用于单元测试）
 * @param random 随机数发生器实例
 */
class MotionSimulator(
    val seed: Long? = null,
    private val random: Random = seed?.let { Random(it) } ?: Random()
) {
    // AR-1 速度抖动内部状态：上一时刻的速度
    private var previousSpeed: Float? = null

    // Ornstein-Uhlenbeck 微漂移切平面位移内部状态（单位：米，x 为正东向，y 为正北向）
    private var driftX: Double = 0.0
    private var driftY: Double = 0.0

    /**
     * 计算下一时刻的瞬时速度。
     * 采用一阶自回归模型 AR-1：v_t = alpha * v_{t-1} + (1 - alpha) * v_base + epsilon
     * 并将瞬时速度严格限制在 [0.5 * v_base, 1.5 * v_base] 的物理合理区间内。
     *
     * @param baseSpeedMps 基础巡航速度（米/秒）
     * @param enableJitter 是否启用抖动，若为 false 则直接返回 baseSpeedMps
     * @return 叠加抖动后的瞬时速度（米/秒）
     */
    @Synchronized
    fun nextSpeed(baseSpeedMps: Float, enableJitter: Boolean = true): Float {
        if (!enableJitter || baseSpeedMps <= 0f) {
            previousSpeed = baseSpeedMps
            return baseSpeedMps
        }

        val alpha = 0.85f // AR-1 自回归系数（平滑衰减因子）
        val prev = previousSpeed ?: baseSpeedMps
        val sigma = 0.08f * baseSpeedMps // 噪声标准差，与基准速度成比例
        val noise = (random.nextGaussian() * sigma).toFloat()

        val candidate = alpha * prev + (1f - alpha) * baseSpeedMps + noise
        val minSpeed = 0.5f * baseSpeedMps
        val maxSpeed = 1.5f * baseSpeedMps
        val clamped = candidate.coerceIn(minSpeed, maxSpeed)

        previousSpeed = clamped
        return clamped
    }

    /**
     * 对给定的基准地理坐标施加 Ornstein-Uhlenbeck (OU) 均值回归位置微小漂移。
     * dx = -theta * x * dt + sigma * dW_x
     * dy = -theta * y * dt + sigma * dW_y
     * 漂移向量长度严格受限在 maxDriftRadiusMeters 之内。
     *
     * @param point 基础地理坐标点
     * @param deltaSeconds 步进时间间隔（秒）
     * @param enableMicroDrift 是否启用微漂移
     * @param maxDriftRadiusMeters 允许的最大漂移半径（米，默认 4.0）
     * @return 叠加微漂移后的新地理坐标点
     */
    @Synchronized
    fun applyMicroDrift(
        point: GeoPoint,
        deltaSeconds: Double,
        enableMicroDrift: Boolean = true,
        maxDriftRadiusMeters: Double = 4.0
    ): GeoPoint {
        if (!enableMicroDrift || maxDriftRadiusMeters <= 0.0 || deltaSeconds <= 0.0) {
            return point
        }

        // OU 参数：theta (均值回归速率), sigma (波动率)
        val theta = 0.35
        val sigma = 1.0
        val dt = deltaSeconds.coerceAtLeast(0.0)

        // 精确解衰减系数与扩散噪声方差
        val decay = exp(-theta * dt)
        val variance = if (theta > 1e-6) {
            (sigma * sigma / (2.0 * theta)) * (1.0 - exp(-2.0 * theta * dt))
        } else {
            sigma * sigma * dt
        }
        val stdDev = sqrt(variance.coerceAtLeast(0.0))

        val zX = random.nextGaussian()
        val zY = random.nextGaussian()

        var newX = driftX * decay + stdDev * zX
        var newY = driftY * decay + stdDev * zY

        // 严格圆盘半径裁剪
        val radius = sqrt(newX * newX + newY * newY)
        if (radius > maxDriftRadiusMeters) {
            val scale = maxDriftRadiusMeters / radius
            newX *= scale
            newY *= scale
        }

        driftX = newX
        driftY = newY

        // 将切平面位移 (米) 映射为 WGS84 经纬度增量
        val earthRadius = SpatialMath.EARTH_RADIUS
        val deltaLatDeg = (driftY / earthRadius) * (180.0 / Math.PI)

        val latRad = Math.toRadians(point.latitude)
        val cosLat = max(abs(cos(latRad)), 1e-6)
        val deltaLonDeg = (driftX / (earthRadius * cosLat)) * (180.0 / Math.PI)

        val finalLat = (point.latitude + deltaLatDeg).coerceIn(-90.0, 90.0)
        val finalLon = normalizeLongitude(point.longitude + deltaLonDeg)

        return point.copy(
            latitude = finalLat,
            longitude = finalLon
        )
    }

    /**
     * 获取当前切平面位移向量 (x, y)（米），便于测试和调试。
     */
    @Synchronized
    fun getCurrentDriftOffset(): Pair<Double, Double> {
        return Pair(driftX, driftY)
    }

    /**
     * 重置运动仿真器内部状态（清空历史速度和累积漂移量）。
     */
    @Synchronized
    fun reset() {
        previousSpeed = null
        driftX = 0.0
        driftY = 0.0
        if (seed != null) {
            random.setSeed(seed)
        }
    }

    private fun normalizeLongitude(lon: Double): Double {
        var result = lon
        while (result > 180.0) result -= 360.0
        while (result < -180.0) result += 360.0
        return result
    }
}
