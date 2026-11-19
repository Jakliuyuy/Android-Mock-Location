package com.mockloc.domain.math

import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object CoordinateConverter {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
    private const val X_PI = PI * 3000.0 / 180.0

    // 中国四至矩形边界（与 E2E 基准 GeoCoordinateMathReference 完全对齐）
    private const val CHINA_MIN_LAT = 3.86
    private const val CHINA_MAX_LAT = 53.55
    private const val CHINA_MIN_LON = 73.66
    private const val CHINA_MAX_LON = 135.05

    // 逆向解算极边缘外扩小缓冲（足以覆盖 GCJ-02/BD-09 最大 ~0.015° 正向偏转出框，配合严格框内校验避免误算境外边界点）
    private const val REVERSE_BORDER_BUFFER_DEG = 0.02

    // 逆解收敛与角点往返浮点 ULP 容差（度）
    private const val RECT_ULP_EPSILON_DEG = 1e-9

    fun isOutOfChina(lat: Double, lon: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return true
        return lon < CHINA_MIN_LON || lon > CHINA_MAX_LON || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT
    }

    private fun isOutOfChinaWithBuffer(lat: Double, lon: Double, bufferDeg: Double): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return true
        return lon < CHINA_MIN_LON - bufferDeg ||
            lon > CHINA_MAX_LON + bufferDeg ||
            lat < CHINA_MIN_LAT - bufferDeg ||
            lat > CHINA_MAX_LAT + bufferDeg
    }

    fun wgs84ToGcj02(lat: Double, lon: Double): GeoPoint {
        val safeLat = GeoPoint.clampLatitude(lat)
        val safeLon = GeoPoint.clampLongitude(lon)
        if (!lat.isFinite() || !lon.isFinite() || isOutOfChina(safeLat, safeLon)) {
            return GeoPoint(safeLat, safeLon)
        }
        val (dLat, dLon) = delta(safeLat, safeLon)
        return GeoPoint.createSafe(
            latitude = safeLat + dLat,
            longitude = safeLon + dLon,
            fallbackLatitude = safeLat,
            fallbackLongitude = safeLon,
            normalizeLon = true
        )
    }

    fun gcj02ToWgs84(lat: Double, lon: Double): GeoPoint {
        val safeLat = GeoPoint.clampLatitude(lat)
        val safeLon = GeoPoint.clampLongitude(lon)
        if (!lat.isFinite() || !lon.isFinite() || isOutOfChinaWithBuffer(safeLat, safeLon, REVERSE_BORDER_BUFFER_DEG)) {
            return GeoPoint(safeLat, safeLon)
        }
        val inBufferOnly = isOutOfChina(safeLat, safeLon)
        var (wgsLat, wgsLon) = solveGcj02ToWgs84Raw(safeLat, safeLon)
        if (inBufferOnly) {
            if (isOutOfChinaWithBuffer(wgsLat, wgsLon, RECT_ULP_EPSILON_DEG)) {
                return GeoPoint(safeLat, safeLon)
            }
            if (isOutOfChina(wgsLat, wgsLon)) {
                wgsLat = wgsLat.coerceIn(CHINA_MIN_LAT, CHINA_MAX_LAT)
                wgsLon = wgsLon.coerceIn(CHINA_MIN_LON, CHINA_MAX_LON)
            }
        }
        return GeoPoint.createSafe(
            latitude = wgsLat,
            longitude = wgsLon,
            fallbackLatitude = safeLat,
            fallbackLongitude = safeLon,
            normalizeLon = true
        )
    }

    private fun solveGcj02ToWgs84Raw(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        // 迭代逼近法反解精确 WGS-84（通常 4-6 次收敛到 < 1e-11 度，误差 < 0.001m）
        var wgsLat = gcjLat
        var wgsLon = gcjLon
        for (i in 0 until 10) {
            val gcj = wgs84ToGcj02Raw(wgsLat, wgsLon)
            val dLat = gcj.first - gcjLat
            val dLon = gcj.second - gcjLon
            wgsLat -= dLat
            wgsLon -= dLon
            if (abs(dLat) < 1e-11 && abs(dLon) < 1e-11) {
                break
            }
        }
        return wgsLat to wgsLon
    }

    private fun wgs84ToGcj02Raw(lat: Double, lon: Double): Pair<Double, Double> {
        val (dLat, dLon) = delta(lat, lon)
        return (lat + dLat) to (lon + dLon)
    }

    private fun gcj02ToBd09Raw(lat: Double, lon: Double): Pair<Double, Double> {
        val z = sqrt(lon * lon + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lon) + 0.000003 * cos(lon * X_PI)
        val bdLon = z * cos(theta) + 0.0065
        val bdLat = z * sin(theta) + 0.006
        return bdLat to bdLon
    }

    private fun solveBd09ToGcj02Raw(bdLat: Double, bdLon: Double): Pair<Double, Double> {
        val x = bdLon - 0.0065
        val y = bdLat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = atan2(y, x) - 0.000003 * cos(x * X_PI)
        var gcjLon = z * cos(theta)
        var gcjLat = z * sin(theta)
        // 轻量不动点迭代把 BD-09 反解收敛至 < 1e-12 度（远优于 1e-9° ULP 容差）
        for (i in 0 until 5) {
            val (estBdLat, estBdLon) = gcj02ToBd09Raw(gcjLat, gcjLon)
            val dLat = estBdLat - bdLat
            val dLon = estBdLon - bdLon
            gcjLat -= dLat
            gcjLon -= dLon
            if (abs(dLat) < 1e-12 && abs(dLon) < 1e-12) {
                break
            }
        }
        return gcjLat to gcjLon
    }

    fun gcj02ToBd09(lat: Double, lon: Double): GeoPoint {
        val safeLat = GeoPoint.clampLatitude(lat)
        val safeLon = GeoPoint.clampLongitude(lon)
        if (!lat.isFinite() || !lon.isFinite() || isOutOfChinaWithBuffer(safeLat, safeLon, REVERSE_BORDER_BUFFER_DEG)) {
            return GeoPoint(safeLat, safeLon)
        }
        if (isOutOfChina(safeLat, safeLon)) {
            // 若 GCJ 处于边界缓冲带，仅当其对应的 WGS 原点落在中国矩形内（含 ULP 容差）才继续 BD-09 偏移
            val (wgsLat, wgsLon) = solveGcj02ToWgs84Raw(safeLat, safeLon)
            if (isOutOfChinaWithBuffer(wgsLat, wgsLon, RECT_ULP_EPSILON_DEG)) {
                return GeoPoint(safeLat, safeLon)
            }
        }
        val (bdLat, bdLon) = gcj02ToBd09Raw(safeLat, safeLon)
        return GeoPoint.createSafe(
            latitude = bdLat,
            longitude = bdLon,
            fallbackLatitude = safeLat,
            fallbackLongitude = safeLon,
            normalizeLon = true
        )
    }

    fun bd09ToGcj02(lat: Double, lon: Double): GeoPoint {
        val safeLat = GeoPoint.clampLatitude(lat)
        val safeLon = GeoPoint.clampLongitude(lon)
        if (!lat.isFinite() || !lon.isFinite() || isOutOfChinaWithBuffer(safeLat, safeLon, REVERSE_BORDER_BUFFER_DEG)) {
            return GeoPoint(safeLat, safeLon)
        }
        val inBufferOnly = isOutOfChina(safeLat, safeLon)
        val (gcjLat, gcjLon) = solveBd09ToGcj02Raw(safeLat, safeLon)
        if (inBufferOnly && isOutOfChinaWithBuffer(gcjLat, gcjLon, RECT_ULP_EPSILON_DEG)) {
            // 若反解后的 GCJ 仍在矩形外但处于缓冲带内，进一步核对其 WGS 原点是否在中国矩形内（含 1e-9° ULP 容差）
            val (wgsLat, wgsLon) = solveGcj02ToWgs84Raw(gcjLat, gcjLon)
            if (isOutOfChinaWithBuffer(wgsLat, wgsLon, RECT_ULP_EPSILON_DEG)) {
                return GeoPoint(safeLat, safeLon)
            }
        }
        return GeoPoint.createSafe(
            latitude = gcjLat,
            longitude = gcjLon,
            fallbackLatitude = safeLat,
            fallbackLongitude = safeLon,
            normalizeLon = true
        )
    }

    fun wgs84ToBd09(lat: Double, lon: Double): GeoPoint {
        val safeLat = GeoPoint.clampLatitude(lat)
        val safeLon = GeoPoint.clampLongitude(lon)
        if (!lat.isFinite() || !lon.isFinite() || isOutOfChina(safeLat, safeLon)) {
            return GeoPoint(safeLat, safeLon)
        }
        val (gcjLat, gcjLon) = wgs84ToGcj02Raw(safeLat, safeLon)
        val (bdLat, bdLon) = gcj02ToBd09Raw(gcjLat, gcjLon)
        return GeoPoint.createSafe(
            latitude = bdLat,
            longitude = bdLon,
            fallbackLatitude = safeLat,
            fallbackLongitude = safeLon,
            normalizeLon = true
        )
    }

    fun bd09ToWgs84(lat: Double, lon: Double): GeoPoint {
        val gcj = bd09ToGcj02(lat, lon)
        return gcj02ToWgs84(gcj.latitude, gcj.longitude)
    }

    fun convert(point: GeoPoint, from: CoordinateType, to: CoordinateType): GeoPoint {
        if (from == to) return point
        val converted = when (from) {
            CoordinateType.WGS84 -> when (to) {
                CoordinateType.WGS84 -> point
                CoordinateType.GCJ02 -> wgs84ToGcj02(point.latitude, point.longitude)
                CoordinateType.BD09 -> wgs84ToBd09(point.latitude, point.longitude)
            }
            CoordinateType.GCJ02 -> when (to) {
                CoordinateType.WGS84 -> gcj02ToWgs84(point.latitude, point.longitude)
                CoordinateType.GCJ02 -> point
                CoordinateType.BD09 -> gcj02ToBd09(point.latitude, point.longitude)
            }
            CoordinateType.BD09 -> when (to) {
                CoordinateType.WGS84 -> bd09ToWgs84(point.latitude, point.longitude)
                CoordinateType.GCJ02 -> bd09ToGcj02(point.latitude, point.longitude)
                CoordinateType.BD09 -> point
            }
        }
        return point.copy(
            latitude = converted.latitude,
            longitude = converted.longitude
        )
    }

    private fun delta(lat: Double, lon: Double): Pair<Double, Double> {
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
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
