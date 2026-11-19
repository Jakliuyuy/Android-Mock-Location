package com.mockloc.domain.math

import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateConverterTest {

    @Test
    fun `domestic coordinates round trip between WGS84 GCJ02 and BD09 within 0_5 meters`() {
        val domesticPoints = listOf(
            39.908722 to 116.397499, // 北京天安门
            31.230416 to 121.473701, // 上海人民广场
            22.543099 to 114.057868, // 深圳市民中心
            29.652491 to 91.172112   // 拉萨布达拉宫
        )

        for ((lat, lon) in domesticPoints) {
            assertFalse("($lat, $lon) should be inside China", CoordinateConverter.isOutOfChina(lat, lon))

            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lon)
            val recoveredFromGcj = CoordinateConverter.gcj02ToWgs84(gcj.latitude, gcj.longitude)
            val errGcj = SpatialMath.calculateHaversineDistance(lat, lon, recoveredFromGcj.latitude, recoveredFromGcj.longitude)
            assertTrue("WGS84<->GCJ02 round-trip error $errGcj m exceeded 0.5m", errGcj < 0.5)

            val bd = CoordinateConverter.gcj02ToBd09(gcj.latitude, gcj.longitude)
            val gcjFromBd = CoordinateConverter.bd09ToGcj02(bd.latitude, bd.longitude)
            val errBdGcj = SpatialMath.calculateHaversineDistance(gcj.latitude, gcj.longitude, gcjFromBd.latitude, gcjFromBd.longitude)
            assertTrue("GCJ02<->BD09 round-trip error $errBdGcj m exceeded 1.0m", errBdGcj < 1.0)

            val wgsViaBd = CoordinateConverter.bd09ToWgs84(
                CoordinateConverter.wgs84ToBd09(lat, lon).latitude,
                CoordinateConverter.wgs84ToBd09(lat, lon).longitude
            )
            val errFull = SpatialMath.calculateHaversineDistance(lat, lon, wgsViaBd.latitude, wgsViaBd.longitude)
            assertTrue("WGS84<->BD09 round-trip error $errFull m exceeded 1.0m", errFull < 1.0)
        }
    }

    @Test
    fun `foreign coordinates pass through unchanged`() {
        val foreignPoints = listOf(
            40.7128 to -74.0060,   // 纽约
            51.5074 to -0.1278,    // 伦敦
            35.6762 to 139.6503,   // 东京
            -33.8688 to 151.2093,  // 悉尼
            0.0 to 0.0             // 赤道海洋
        )

        for ((lat, lon) in foreignPoints) {
            assertTrue("($lat, $lon) should be outside China", CoordinateConverter.isOutOfChina(lat, lon))

            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lon)
            assertEquals(lat, gcj.latitude, 1e-12)
            assertEquals(lon, gcj.longitude, 1e-12)

            val bd = CoordinateConverter.wgs84ToBd09(lat, lon)
            assertEquals(lat, bd.latitude, 1e-12)
            assertEquals(lon, bd.longitude, 1e-12)

            val back = CoordinateConverter.bd09ToWgs84(lat, lon)
            assertEquals(lat, back.latitude, 1e-12)
            assertEquals(lon, back.longitude, 1e-12)
        }
    }

    @Test
    fun `extreme and non-finite inputs never throw and produce valid GeoPoint`() {
        val extremeInputs = listOf(
            Double.NaN to Double.NaN,
            Double.POSITIVE_INFINITY to Double.NEGATIVE_INFINITY,
            120.0 to 250.0,
            -95.0 to -200.0,
            90.0 to 180.0,
            -90.0 to -180.0
        )

        for ((lat, lon) in extremeInputs) {
            val p1 = CoordinateConverter.wgs84ToGcj02(lat, lon)
            val p2 = CoordinateConverter.gcj02ToWgs84(lat, lon)
            val p3 = CoordinateConverter.wgs84ToBd09(lat, lon)
            val p4 = CoordinateConverter.bd09ToWgs84(lat, lon)

            listOf(p1, p2, p3, p4).forEach { p ->
                assertTrue(GeoPoint.isValid(p.latitude, p.longitude))
            }
        }
    }

    @Test
    fun `convert preserves altitude accuracy and timestamp across coordinate types`() {
        val original = GeoPoint(39.9087, 116.3975, altitude = 123.4, accuracy = 2.5f, timestamp = 999L)
        val gcj = CoordinateConverter.convert(original, CoordinateType.WGS84, CoordinateType.GCJ02)
        assertEquals(123.4, gcj.altitude, 1e-9)
        assertEquals(2.5f, gcj.accuracy, 1e-6f)
        assertEquals(999L, gcj.timestamp)
        assertTrue(gcj.latitude != original.latitude || gcj.longitude != original.longitude)

        val same = CoordinateConverter.convert(original, CoordinateType.WGS84, CoordinateType.WGS84)
        assertEquals(original, same)
    }

    @Test
    fun `china boundary rectangle aligns with E2E baseline and reverse buffer handles edge points`() {
        // 中国领土极点城市与四至矩形内点均判定为境内且 6 向闭环误差 < 0.5m
        val chinaCitiesAndBorders = listOf(
            53.48 to 122.37, // 漠河
            48.27 to 134.67, // 乌苏镇
            39.47 to 75.99,  // 喀什
            18.25 to 109.51, // 三亚
            3.98 to 112.30,  // 曾母暗沙
            25.74 to 123.47, // 钓鱼岛
            39.9 to 73.67,   // CHINA_RECT_INNER
            3.86 to 73.66,   // CHINA_RECT_SW_CORNER
            53.55 to 135.05  // CHINA_RECT_NE_CORNER
        )
        for ((lat, lon) in chinaCitiesAndBorders) {
            assertFalse("($lat, $lon) should be inside China", CoordinateConverter.isOutOfChina(lat, lon))
            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lon)
            val wgsBack = CoordinateConverter.gcj02ToWgs84(gcj.latitude, gcj.longitude)
            val err = SpatialMath.calculateHaversineDistance(lat, lon, wgsBack.latitude, wgsBack.longitude)
            assertTrue("($lat, $lon) WGS<->GCJ round-trip error $err m exceeded 0.5m", err < 0.5)
        }

        // 中国四至矩形 4 个理论角点：全部 6 向往返误差均应 < 0.5m
        val fourCorners = listOf(
            3.86 to 73.66,   // SW_CORNER
            3.86 to 135.05,  // SE_CORNER
            53.55 to 73.66,  // NW_CORNER
            53.55 to 135.05  // NE_CORNER
        )
        for ((lat, lon) in fourCorners) {
            assertFalse("($lat, $lon) should be inside China", CoordinateConverter.isOutOfChina(lat, lon))

            val gcj = CoordinateConverter.wgs84ToGcj02(lat, lon)
            val wgsFromGcj = CoordinateConverter.gcj02ToWgs84(gcj.latitude, gcj.longitude)
            val errWgsGcj = SpatialMath.calculateHaversineDistance(lat, lon, wgsFromGcj.latitude, wgsFromGcj.longitude)
            assertTrue("($lat, $lon) WGS->GCJ->WGS error $errWgsGcj m >= 0.5m", errWgsGcj < 0.5)

            val gcjBack = CoordinateConverter.wgs84ToGcj02(wgsFromGcj.latitude, wgsFromGcj.longitude)
            val errGcjWgs = SpatialMath.calculateHaversineDistance(gcj.latitude, gcj.longitude, gcjBack.latitude, gcjBack.longitude)
            assertTrue("($lat, $lon) GCJ->WGS->GCJ error $errGcjWgs m >= 0.5m", errGcjWgs < 0.5)

            val bdFromGcj = CoordinateConverter.gcj02ToBd09(gcj.latitude, gcj.longitude)
            val gcjFromBd = CoordinateConverter.bd09ToGcj02(bdFromGcj.latitude, bdFromGcj.longitude)
            val errGcjBd = SpatialMath.calculateHaversineDistance(gcj.latitude, gcj.longitude, gcjFromBd.latitude, gcjFromBd.longitude)
            assertTrue("($lat, $lon) GCJ->BD->GCJ error $errGcjBd m >= 0.5m", errGcjBd < 0.5)

            val bdFromWgs = CoordinateConverter.wgs84ToBd09(lat, lon)
            val gcjFromBdDirect = CoordinateConverter.bd09ToGcj02(bdFromWgs.latitude, bdFromWgs.longitude)
            val bdBackFromGcj = CoordinateConverter.gcj02ToBd09(gcjFromBdDirect.latitude, gcjFromBdDirect.longitude)
            val errBdGcj = SpatialMath.calculateHaversineDistance(bdFromWgs.latitude, bdFromWgs.longitude, bdBackFromGcj.latitude, bdBackFromGcj.longitude)
            assertTrue("($lat, $lon) BD->GCJ->BD error $errBdGcj m >= 0.5m", errBdGcj < 0.5)

            val wgsFromBd = CoordinateConverter.bd09ToWgs84(bdFromWgs.latitude, bdFromWgs.longitude)
            val errWgsBd = SpatialMath.calculateHaversineDistance(lat, lon, wgsFromBd.latitude, wgsFromBd.longitude)
            assertTrue("($lat, $lon) WGS->BD->WGS error $errWgsBd m >= 0.5m", errWgsBd < 0.5)

            val bdBackFromWgs = CoordinateConverter.wgs84ToBd09(wgsFromBd.latitude, wgsFromBd.longitude)
            val errBdWgs = SpatialMath.calculateHaversineDistance(bdFromWgs.latitude, bdFromWgs.longitude, bdBackFromWgs.latitude, bdBackFromWgs.longitude)
            assertTrue("($lat, $lon) BD->WGS->BD error $errBdWgs m >= 0.5m", errBdWgs < 0.5)
        }

        // 与 E2E GeoMathBaselineE2ETest 对齐的矩形外侧点判定为境外并原样透传（全 6 向）
        val rectOuterPoints = listOf(
            39.9 to 73.65,   // CHINA_RECT_OUTER
            53.56 to 120.0   // CHINA_RECT_NORTH_OUTER
        )
        for ((lat, lon) in rectOuterPoints) {
            assertTrue("($lat, $lon) should be outside China", CoordinateConverter.isOutOfChina(lat, lon))
            val conversions = listOf(
                CoordinateConverter.wgs84ToGcj02(lat, lon),
                CoordinateConverter.gcj02ToWgs84(lat, lon),
                CoordinateConverter.gcj02ToBd09(lat, lon),
                CoordinateConverter.bd09ToGcj02(lat, lon),
                CoordinateConverter.wgs84ToBd09(lat, lon),
                CoordinateConverter.bd09ToWgs84(lat, lon)
            )
            conversions.forEach { converted ->
                assertEquals(lat, converted.latitude, 1e-12)
                assertEquals(lon, converted.longitude, 1e-12)
            }
        }
    }
}
