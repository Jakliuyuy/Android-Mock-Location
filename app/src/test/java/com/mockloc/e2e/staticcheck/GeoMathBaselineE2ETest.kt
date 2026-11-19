package com.mockloc.e2e.staticcheck

import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.infra.GeoCoordinateMathReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class GeoMathBaselineE2ETest {

    @Test
    fun `isOutOfChina accurately distinguishes domestic cities and foreign or border points`() {
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.BEIJING_TIANANMEN.latitude, TestCoordinates.BEIJING_TIANANMEN.longitude))
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.SHANGHAI_LUJIAZUI.latitude, TestCoordinates.SHANGHAI_LUJIAZUI.longitude))
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.SHENZHEN_NANSHAN.latitude, TestCoordinates.SHENZHEN_NANSHAN.longitude))
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.LHASA_POTALA.latitude, TestCoordinates.LHASA_POTALA.longitude))

        // 中国矩形临界点 (TEST_INFRA §3 T2-F04-02)
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.CHINA_RECT_INNER.latitude, TestCoordinates.CHINA_RECT_INNER.longitude))
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.CHINA_RECT_SW_CORNER.latitude, TestCoordinates.CHINA_RECT_SW_CORNER.longitude))
        assertFalse(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.CHINA_RECT_NE_CORNER.latitude, TestCoordinates.CHINA_RECT_NE_CORNER.longitude))
        assertTrue(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.CHINA_RECT_OUTER.latitude, TestCoordinates.CHINA_RECT_OUTER.longitude))
        assertTrue(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.CHINA_RECT_NORTH_OUTER.latitude, TestCoordinates.CHINA_RECT_NORTH_OUTER.longitude))

        // 境外点
        assertTrue(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.TOKYO_STATION.latitude, TestCoordinates.TOKYO_STATION.longitude))
        assertTrue(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.NEW_YORK_TIMES_SQUARE.latitude, TestCoordinates.NEW_YORK_TIMES_SQUARE.longitude))
        assertTrue(GeoCoordinateMathReference.isOutOfChina(TestCoordinates.LONDON.latitude, TestCoordinates.LONDON.longitude))
    }

    @Test
    fun `foreign coordinates undergo strict identity transformation without China offset`() {
        val foreignPoints = listOf(
            TestCoordinates.TOKYO_STATION,
            TestCoordinates.NEW_YORK_TIMES_SQUARE,
            TestCoordinates.LONDON,
            TestCoordinates.EQUATOR_PRIME_MERIDIAN,
            TestCoordinates.NORTH_POLE,
            TestCoordinates.SOUTH_POLE,
            TestCoordinates.ANTIMERIDIAN_POS,
            TestCoordinates.ANTIMERIDIAN_NEG
        )
        for (p in foreignPoints) {
            val gcj = GeoCoordinateMathReference.wgs84ToGcj02(p.latitude, p.longitude)
            val bdFromWgs = GeoCoordinateMathReference.wgs84ToBd09(p.latitude, p.longitude)
            val wgsFromGcj = GeoCoordinateMathReference.gcj02ToWgs84(p.latitude, p.longitude)
            val wgsFromBd = GeoCoordinateMathReference.bd09ToWgs84(p.latitude, p.longitude)

            assertEquals(p.latitude, gcj.lat, 0.0)
            assertEquals(p.longitude, gcj.lon, 0.0)
            assertEquals(p.latitude, bdFromWgs.lat, 0.0)
            assertEquals(p.longitude, bdFromWgs.lon, 0.0)
            assertEquals(p.latitude, wgsFromGcj.lat, 0.0)
            assertEquals(p.longitude, wgsFromGcj.lon, 0.0)
            assertEquals(p.latitude, wgsFromBd.lat, 0.0)
            assertEquals(p.longitude, wgsFromBd.lon, 0.0)
        }
    }

    @Test
    fun `domestic WGS84 to GCJ02 to BD09 round trip errors are strictly below 1e-6 degrees`() {
        val domesticPoints = listOf(
            TestCoordinates.BEIJING_TIANANMEN,
            TestCoordinates.BEIJING_BIRDS_NEST,
            TestCoordinates.SHANGHAI_LUJIAZUI,
            TestCoordinates.SHENZHEN_NANSHAN,
            TestCoordinates.LHASA_POTALA
        )

        for (p in domesticPoints) {
            val gcj = GeoCoordinateMathReference.wgs84ToGcj02(p.latitude, p.longitude)
            val shiftMeters = GeoCoordinateMathReference.haversineDistanceMeters(p.latitude, p.longitude, gcj.lat, gcj.lon)
            assertTrue("Expected China GCJ-02 offset in 50..900m for $p, got $shiftMeters", shiftMeters in 50.0..900.0)

            val wgsBack = GeoCoordinateMathReference.gcj02ToWgs84(gcj.lat, gcj.lon)
            assertTrue("WGS->GCJ->WGS lat error too large: ${abs(wgsBack.lat - p.latitude)}", abs(wgsBack.lat - p.latitude) < 1e-6)
            assertTrue("WGS->GCJ->WGS lon error too large: ${abs(wgsBack.lon - p.longitude)}", abs(wgsBack.lon - p.longitude) < 1e-6)

            val bdFromDirect = GeoCoordinateMathReference.wgs84ToBd09(p.latitude, p.longitude)
            val bdFromGcj = GeoCoordinateMathReference.gcj02ToBd09(gcj.lat, gcj.lon)
            assertEquals(bdFromGcj.lat, bdFromDirect.lat, 1e-9)
            assertEquals(bdFromGcj.lon, bdFromDirect.lon, 1e-9)

            val gcjBack = GeoCoordinateMathReference.bd09ToGcj02(bdFromGcj.lat, bdFromGcj.lon)
            assertTrue("GCJ->BD->GCJ lat error too large: ${abs(gcjBack.lat - gcj.lat)}", abs(gcjBack.lat - gcj.lat) < 1e-6)
            assertTrue("GCJ->BD->GCJ lon error too large: ${abs(gcjBack.lon - gcj.lon)}", abs(gcjBack.lon - gcj.lon) < 1e-6)

            val wgsFromBd = GeoCoordinateMathReference.bd09ToWgs84(bdFromDirect.lat, bdFromDirect.lon)
            assertTrue("WGS->BD->WGS lat error too large: ${abs(wgsFromBd.lat - p.latitude)}", abs(wgsFromBd.lat - p.latitude) < 1e-6)
            assertTrue("WGS->BD->WGS lon error too large: ${abs(wgsFromBd.lon - p.longitude)}", abs(wgsFromBd.lon - p.longitude) < 1e-6)
        }
    }

    @Test
    fun `haversineDistanceMeters and bearingDegrees match known physical landmarks`() {
        // 天安门 -> 鸟巢 约 9.35 km，方向近正北 (~359..1 度)
        val dist = GeoCoordinateMathReference.haversineDistanceMeters(
            TestCoordinates.BEIJING_TIANANMEN.latitude,
            TestCoordinates.BEIJING_TIANANMEN.longitude,
            TestCoordinates.BEIJING_BIRDS_NEST.latitude,
            TestCoordinates.BEIJING_BIRDS_NEST.longitude
        )
        assertTrue("Expected Tiananmen->BirdsNest distance ~9350m, got $dist", dist in 9200.0..9500.0)

        val bearing = GeoCoordinateMathReference.bearingDegrees(
            TestCoordinates.BEIJING_TIANANMEN.latitude,
            TestCoordinates.BEIJING_TIANANMEN.longitude,
            TestCoordinates.BEIJING_BIRDS_NEST.latitude,
            TestCoordinates.BEIJING_BIRDS_NEST.longitude
        )
        assertTrue("Expected nearly due north bearing, got $bearing", bearing > 355.0 || bearing < 5.0)

        // 天安门 -> 正东一点方位角约 90°
        val eastBearing = GeoCoordinateMathReference.bearingDegrees(39.9087, 116.3975, 39.9087, 116.4975)
        assertEquals(90.0, eastBearing, 1.0)
    }

    @Test
    fun `slerpInterpolate and math protect against coincident points, antipodal points, and NaN`() {
        // 重合点保护
        val p = TestCoordinates.BEIJING_TIANANMEN
        assertEquals(0.0, GeoCoordinateMathReference.haversineDistanceMeters(p.latitude, p.longitude, p.latitude, p.longitude), 0.0)
        val sameBearing = GeoCoordinateMathReference.bearingDegrees(p.latitude, p.longitude, p.latitude, p.longitude)
        assertTrue(sameBearing.isFinite())
        val sameSlerp = GeoCoordinateMathReference.slerpInterpolate(p.latitude, p.longitude, p.latitude, p.longitude, 0.5)
        assertTrue(sameSlerp.lat.isFinite() && sameSlerp.lon.isFinite())
        assertEquals(p.latitude, sameSlerp.lat, 1e-9)
        assertEquals(p.longitude, sameSlerp.lon, 1e-9)

        // 对跖点保护: (0, 0) 与 (0, 180) 以及 (90, 0) 与 (-90, 0)
        val antipodalDist = GeoCoordinateMathReference.haversineDistanceMeters(0.0, 0.0, 0.0, 180.0)
        assertEquals(PI * 6_371_000.0, antipodalDist, 10.0)

        val midEq = GeoCoordinateMathReference.slerpInterpolate(0.0, 0.0, 0.0, 180.0, 0.5)
        assertTrue("Midpoint on antipodal equator must be finite, got $midEq", midEq.lat.isFinite() && midEq.lon.isFinite())
        val distFromStart = GeoCoordinateMathReference.haversineDistanceMeters(0.0, 0.0, midEq.lat, midEq.lon)
        assertEquals(antipodalDist / 2.0, distFromStart, 50.0)

        val midPole = GeoCoordinateMathReference.slerpInterpolate(90.0, 0.0, -90.0, 0.0, 0.5)
        assertTrue("Midpoint between poles must be finite, got $midPole", midPole.lat.isFinite() && midPole.lon.isFinite())
        assertEquals(0.0, midPole.lat, 1e-6)

        // 非有限入参安全兜底
        val nanSlerp = GeoCoordinateMathReference.slerpInterpolate(Double.NaN, 116.4, 39.9, 116.4, 0.5)
        assertTrue(nanSlerp.lat.isFinite() && nanSlerp.lon.isFinite())
        assertTrue(GeoCoordinateMathReference.isOutOfChina(Double.NaN, 116.4))
        assertEquals(0.0, GeoCoordinateMathReference.haversineDistanceMeters(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0), 0.0)
        // NaN/Inf 转换安全不崩溃
        val nanGcj = GeoCoordinateMathReference.wgs84ToGcj02(Double.NaN, 116.4)
        assertTrue(nanGcj.lat.isNaN())
    }

    @Test
    fun `slerpInterpolate handles antimeridian coincident points and near-pole antipodal pairs accurately`() {
        // 1. 日界线物理重合点 (0.0, 180.0) <-> (0.0, -180.0) 不得绕经 0° 经线漂移 20,000 km
        val fractions = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        for (f in fractions) {
            val mid = GeoCoordinateMathReference.slerpInterpolate(0.0, 180.0, 0.0, -180.0, f)
            assertTrue("Result must be finite at f=$f", mid.lat.isFinite() && mid.lon.isFinite())
            val distFromStart = GeoCoordinateMathReference.haversineDistanceMeters(0.0, 180.0, mid.lat, mid.lon)
            assertEquals("Antimeridian coincident points must stay at 0m for f=$f (got $mid)", 0.0, distFromStart, 1e-3)
            assertTrue("Longitude must stay in [-180, 180], got ${mid.lon}", mid.lon in -180.0..180.0)
        }

        // 2. 近极点对跖点 (89.999999, 45.0) <-> (-89.999999, -135.0) 不发生灾难性相消
        val totalDist = GeoCoordinateMathReference.haversineDistanceMeters(89.999999, 45.0, -89.999999, -135.0)
        for (f in listOf(0.25, 0.5, 0.75)) {
            val mid = GeoCoordinateMathReference.slerpInterpolate(89.999999, 45.0, -89.999999, -135.0, f)
            assertTrue("Near-pole antipodal midpoint must be finite at f=$f", mid.lat.isFinite() && mid.lon.isFinite())
            val dStart = GeoCoordinateMathReference.haversineDistanceMeters(89.999999, 45.0, mid.lat, mid.lon)
            val dEnd = GeoCoordinateMathReference.haversineDistanceMeters(mid.lat, mid.lon, -89.999999, -135.0)
            assertEquals("Distance from start at f=$f should be ${totalDist * f}, got $dStart", totalDist * f, dStart, 50.0)
            assertEquals("Distance to end at f=$f should be ${totalDist * (1.0 - f)}, got $dEnd", totalDist * (1.0 - f), dEnd, 50.0)
        }

        // 3. 距离中国矩形边界 >= 0.01° 的内侧点往返精度验证
        val safeBorderPoints = listOf(
            3.87 to 100.0,
            53.54 to 100.0,
            30.0 to 73.67,
            30.0 to 135.04,
            53.54 to 135.04
        )
        for ((lat, lon) in safeBorderPoints) {
            val gcj = GeoCoordinateMathReference.wgs84ToGcj02(lat, lon)
            val wgsBack = GeoCoordinateMathReference.gcj02ToWgs84(gcj.lat, gcj.lon)
            assertTrue("Round-trip lat error at ($lat,$lon): ${abs(wgsBack.lat - lat)}", abs(wgsBack.lat - lat) < 1e-5)
            assertTrue("Round-trip lon error at ($lat,$lon): ${abs(wgsBack.lon - lon)}", abs(wgsBack.lon - lon) < 1e-5)
        }
    }
}
