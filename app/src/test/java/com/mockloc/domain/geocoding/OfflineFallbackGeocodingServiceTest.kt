package com.mockloc.domain.geocoding

import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFallbackGeocodingServiceTest {

    private val service = OfflineFallbackGeocodingService()

    @Test
    fun `built-in dataset has over 110 places with valid authoritative WGS84 coordinates`() {
        assertTrue(
            "Dataset should contain at least 110 places, found ${OfflineFallbackGeocodingService.PLACES.size}",
            OfflineFallbackGeocodingService.PLACES.size >= 110
        )
        for (place in OfflineFallbackGeocodingService.PLACES) {
            assertTrue(place.name.isNotBlank())
            assertTrue(
                "Invalid coordinates for ${place.name}: ${place.latitude}, ${place.longitude}",
                GeoPoint.isValid(place.latitude, place.longitude)
            )
            assertTrue(place.pinyin.isNotBlank())
            assertTrue(place.initials.isNotBlank())
        }
    }

    @Test
    fun `UT-G09 offline search covers capitals prefecture cities landmarks pinyin initials aliases and fuzzy`() = runTest {
        // 省会/直辖市/特区
        for (city in listOf("拉萨", "乌鲁木齐", "台北", "香港", "澳门", "苏州", "青岛", "三亚")) {
            val res = service.search(city).getOrThrow()
            assertTrue("Should find $city", res.isNotEmpty())
            assertEquals(CoordinateType.WGS84, res[0].coordinateType)
            assertEquals(OfflineFallbackGeocodingService.SOURCE_FALLBACK, res[0].source)
        }

        // 热门地标与精确命中排首位
        val tam = service.search("天安门").getOrThrow()
        assertEquals("天安门", tam[0].name)
        assertEquals(39.9087, tam[0].latitude, 1e-4)
        assertEquals(116.3975, tam[0].longitude, 1e-4)

        // 全拼与简拼
        val nskjyPinyin = service.search("nanshankejiyuan").getOrThrow()
        assertEquals("南山科技园", nskjyPinyin[0].name)

        val lujiazuiPinyin = service.search("lujiazui").getOrThrow()
        assertEquals("上海陆家嘴", lujiazuiPinyin[0].name)

        val zgcInitials = service.search("zgc").getOrThrow()
        assertEquals("中关村", zgcInitials[0].name)

        val tamInitials = service.search("tam").getOrThrow()
        assertEquals("天安门", tamInitials[0].name)

        // 别名匹配
        val birdNest = service.search("鸟巢").getOrThrow()
        assertTrue(birdNest.isNotEmpty())
        assertTrue(birdNest[0].name.contains("鸟巢"))

        // 空格分词与有序子序列模糊匹配
        val tokenized = service.search("深圳 南山").getOrThrow()
        assertTrue(tokenized.isNotEmpty())
        assertEquals("南山科技园", tokenized[0].name)

        val fuzzySubseq = service.search("深北").getOrThrow()
        assertTrue(fuzzySubseq.any { it.name == "深圳北站" })

        val fuzzySubseq2 = service.search("天门").getOrThrow()
        assertTrue(fuzzySubseq2.any { it.name == "天安门" })

        // 空白输入
        assertTrue(service.search("   ").getOrThrow().isEmpty())
    }

    @Test
    fun `UT-G10 offline reverse geocode supports near distance direction offshore and invalid bounds`() = runTest {
        // 1. 近点 (< 500m)
        val near = service.reverseGeocode(39.9090, 116.3976).getOrThrow()
        assertNotNull(near)
        assertEquals("天安门", near!!.name)
        assertEquals("北京市", near.city)
        assertEquals(OfflineFallbackGeocodingService.SOURCE_NEAREST, near.source)

        // 2. 偏移 10km 正北方向 (500m .. 500km)
        val tiananmen = GeoPoint(39.9087, 116.3975)
        val north10km = SpatialMath.offsetByMeters(tiananmen, northMeters = 10_000.0, eastMeters = 0.0)
        val midRange = service.reverseGeocode(north10km.latitude, north10km.longitude).getOrThrow()
        assertNotNull(midRange)
        assertTrue("Expected direction text in ${midRange!!.name}", midRange.name.contains("方向约"))
        assertTrue(midRange.name.contains("公里") || midRange.name.contains("米"))
        assertEquals(north10km.latitude, midRange.latitude, 1e-7)
        assertEquals(north10km.longitude, midRange.longitude, 1e-7)

        // 3. 远海坐标 (0.0, 0.0) (> 500km)
        val offshore = service.reverseGeocode(0.0, 0.0).getOrThrow()
        assertNotNull(offshore)
        assertEquals(OfflineFallbackGeocodingService.SOURCE_COORD, offshore!!.source)
        assertTrue(offshore.name.contains("0.0000"))

        // 4. 非法 / NaN 坐标返回 null
        assertNull(service.reverseGeocode(91.0, 116.0).getOrThrow())
        assertNull(service.reverseGeocode(39.9, 181.0).getOrThrow())
        assertNull(service.reverseGeocode(Double.NaN, 116.0).getOrThrow())
        assertNull(service.reverseGeocode(39.9, Double.POSITIVE_INFINITY).getOrThrow())
    }
}
