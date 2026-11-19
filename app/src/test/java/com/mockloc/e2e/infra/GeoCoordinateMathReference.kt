package com.mockloc.e2e.infra

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RefGeoPoint(val lat: Double, val lon: Double)

/**
 * 从零独立实现的纯 JVM 地理数学参考真值辅助器（Oracle）。
 * 用于 Phase 1 基准验证及 Phase 2 交叉校验生产 `CoordinateConverter` 与 `SpatialMath`。
 *
 * 边界与异常输入契约说明：
 * 1. 非有限输入（`NaN` / `±Infinity`）：`isOutOfChina` 返回 `true`；`wgs84ToGcj02`、`gcj02ToWgs84`、
 *    `gcj02ToBd09`、`bd09ToGcj02`、`wgs84ToBd09`、`bd09ToWgs84` 均安全不崩溃并按 T2-F04-03 契约
 *    原样透传输入坐标；`haversineDistanceMeters`、`bearingDegrees`、`slerpInterpolate` 返回有限兜底值。
 * 2. 中国矩形 `[3.86, 53.55] × [73.66, 135.05]` 边缘 `0.01°` 带特性：经典 GCJ-02/BD-09 正变换偏转
 *    最高可达 ~0.0095°（约 700–1000m），若输入点距矩形边缘 `< 0.01°`，正向偏转可能跃出矩形导致
 *    逆变换被判定为 `isOutOfChina == true` 而跳过逆解；正常精度往返闭环测试点应距矩形边缘 `>= 0.01°`。
 */
object GeoCoordinateMathReference {

    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    // 中国四至矩形参考边界 (TEST_INFRA.md §3 T2-F04-02 & §6.1)
    private const val CHINA_MIN_LAT = 3.86
    private const val CHINA_MAX_LAT = 53.55
    private const val CHINA_MIN_LON = 73.66
    private const val CHINA_MAX_LON = 135.05

    fun isOutOfChina(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return true
        return lon < CHINA_MIN_LON || lon > CHINA_MAX_LON || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        val (dLat, dLon) = delta(lat, lon)
        return RefGeoPoint(lat + dLat, lon + dLon)
    }

    /**
     * 精确迭代逆解 GCJ-02 -> WGS-84，收敛误差 < 1e-10 度（远优于 1e-6° 要求）。
     */
    fun gcj02ToWgs84(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        var wgsLat = lat
        var wgsLon = lon
        for (i in 0 until 20) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLon)
            val dLat = gcj.lat - lat
            val dLon = gcj.lon - lon
            wgsLat -= dLat
            wgsLon -= dLon
            if (abs(dLat) < 1e-10 && abs(dLon) < 1e-10) {
                break
            }
        }
        return RefGeoPoint(wgsLat, wgsLon)
    }

    fun gcj02ToBd09(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        val bdLon = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return RefGeoPoint(bdLat, bdLon)
    }

    fun bd09ToGcj02(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        val x = lon - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        val gcjLon = z * cos(theta)
        val gcjLat = z * sin(theta)
        return RefGeoPoint(gcjLat, gcjLon)
    }

    fun wgs84ToBd09(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        val gcj = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gcj.lat, gcj.lon)
    }

    fun bd09ToWgs84(lat: Double, lon: Double): RefGeoPoint {
        if (isOutOfChina(lat, lon)) return RefGeoPoint(lat, lon)
        val gcj = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gcj.lat, gcj.lon)
    }

    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return 0.0
        }
        val rLat1 = Math.toRadians(lat1)
        val rLon1 = Math.toRadians(lon1)
        val rLat2 = Math.toRadians(lat2)
        val rLon2 = Math.toRadians(lon2)
        val dLat = rLat2 - rLat1
        val dLon = rLon2 - rLon1
        val sinHalfLat = sin(dLat / 2.0)
        val sinHalfLon = sin(dLon / 2.0)
        val a = (sinHalfLat * sinHalfLat + cos(rLat1) * cos(rLat2) * sinHalfLon * sinHalfLon)
            .coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return 0.0
        }
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLonDeg = shortestLonDiff(lon1, lon2)
        val dLon = Math.toRadians(dLonDeg)
        if (abs(rLat2 - rLat1) < 1e-12 && abs(dLon) < 1e-12) {
            return 0.0
        }
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        if (abs(x) < 1e-15 && abs(y) < 1e-15) {
            return 0.0
        }
        val deg = Math.toDegrees(atan2(y, x))
        return ((deg % 360.0) + 360.0) % 360.0
    }

    /**
     * 球面线性插值（Slerp）：
     * - 使用半角公式 `omega = 2 * atan2(||p1 - p2||, ||p1 + p2||)` 彻底消除近极点对跖点 `acos(dot)` 精度退化；
     * - 对重合点（含 `±180°` 日界线物理重合点）执行最短经度角差插值并归一化到 `[-180.0, 180.0]`；
     * - 对跖点（`omega > PI - 1e-6`）使用正交切向量在大圆半圆上旋转；绝不产生 `NaN`。
     */
    fun slerpInterpolate(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        fraction: Double
    ): RefGeoPoint {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) {
            return RefGeoPoint(0.0, 0.0)
        }
        if (!fraction.isFinite()) return RefGeoPoint(lat1, lon1)
        val t = fraction.coerceIn(0.0, 1.0)
        if (t == 0.0) return RefGeoPoint(lat1, normalizeLon(lon1, preserveSign = true))
        if (t == 1.0) return RefGeoPoint(lat2, normalizeLon(lon2, preserveSign = true))

        val p1 = toUnitVector(lat1, lon1)
        val p2 = toUnitVector(lat2, lon2)
        val dx = p1[0] - p2[0]
        val dy = p1[1] - p2[1]
        val dz = p1[2] - p2[2]
        val sx = p1[0] + p2[0]
        val sy = p1[1] + p2[1]
        val sz = p1[2] + p2[2]
        val normDiff = sqrt(dx * dx + dy * dy + dz * dz)
        val normSum = sqrt(sx * sx + sy * sy + sz * sz)
        val omega = 2.0 * atan2(normDiff, normSum)

        // 重合点保护（含 ±180° 日界线物理重合点）：按最短经度角差插值并归一化至 [-180.0, 180.0]
        if (omega < 1e-12) {
            val dLon = shortestLonDiff(lon1, lon2)
            val outLat = lat1 + (lat2 - lat1) * t
            val outLon = normalizeLon(normalizeLon(lon1, preserveSign = true) + dLon * t, preserveSign = lon1 >= 0.0)
            return RefGeoPoint(outLat, outLon)
        }

        val vec = if (omega > PI - 1e-6 || normSum < 1e-6) {
            // 对跖点（omega ≈ PI）保护：构造与 p1 正交的单位切向量，在大圆半圆上按角度 t*omega 旋转
            val refAxis = if (abs(p1[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
            val cx = p1[1] * refAxis[2] - p1[2] * refAxis[1]
            val cy = p1[2] * refAxis[0] - p1[0] * refAxis[2]
            val cz = p1[0] * refAxis[1] - p1[1] * refAxis[0]
            val orthoNorm = sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(1e-12)
            val ortho = doubleArrayOf(cx / orthoNorm, cy / orthoNorm, cz / orthoNorm)
            val angle = t * omega
            val cosA = cos(angle)
            val sinA = sin(angle)
            doubleArrayOf(
                p1[0] * cosA + ortho[0] * sinA,
                p1[1] * cosA + ortho[1] * sinA,
                p1[2] * cosA + ortho[2] * sinA
            )
        } else {
            val sinOmega = sin(omega)
            val w1 = sin((1.0 - t) * omega) / sinOmega
            val w2 = sin(t * omega) / sinOmega
            doubleArrayOf(
                p1[0] * w1 + p2[0] * w2,
                p1[1] * w1 + p2[1] * w2,
                p1[2] * w1 + p2[2] * w2
            )
        }

        val norm = sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2]).coerceAtLeast(1e-15)
        val ux = vec[0] / norm
        val uy = vec[1] / norm
        val uz = (vec[2] / norm).coerceIn(-1.0, 1.0)

        val outLat = Math.toDegrees(atan2(uz, sqrt(ux * ux + uy * uy)))
        val outLon = Math.toDegrees(atan2(uy, ux))
        return RefGeoPoint(outLat, outLon)
    }

    private fun shortestLonDiff(lon1: Double, lon2: Double): Double {
        return (((lon2 - lon1 + 540.0) % 360.0) + 360.0) % 360.0 - 180.0
    }

    private fun normalizeLon(lon: Double, preserveSign: Boolean = false): Double {
        val wrapped = (((lon + 180.0) % 360.0) + 360.0) % 360.0 - 180.0
        return if (wrapped == -180.0 && preserveSign && lon > 0.0) 180.0 else wrapped
    }

    private fun toUnitVector(lat: Double, lon: Double): DoubleArray {
        val rLat = Math.toRadians(lat)
        val rLon = Math.toRadians(lon)
        val cosLat = cos(rLat)
        return doubleArrayOf(
            cosLat * cos(rLon),
            cosLat * sin(rLon),
            sin(rLat)
        )
    }

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - 105.0
        val y = lat - 35.0
        var dLat = transformLat(x, y)
        var dLon = transformLon(x, y)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1.0 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1.0 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLon
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
