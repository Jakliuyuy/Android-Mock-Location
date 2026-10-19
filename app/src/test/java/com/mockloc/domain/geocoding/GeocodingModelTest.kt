package com.mockloc.domain.geocoding

import com.mockloc.domain.model.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 地理编码与逆地理编码服务单元测试。
 * 验证离线地标检索、模糊子串匹配、最近邻逆地理编码、Nominatim JSON解析及网络降级机制。
 */
class GeocodingModelTest {

    private val offlineService = OfflineFallbackGeocodingService()

    @Test
    fun testOfflineDatabaseSize_hasMoreThan50Landmarks() {
        assertTrue(
            "Offline landmark database must contain at least 50 landmarks",
            OfflineFallbackGeocodingService.LANDMARKS.size >= 50
        )
    }

    @Test
    fun testOfflineSearch_exactAndSubstringMatch() = runBlocking {
        // 精确/子串中文搜索
        val beijingResults = offlineService.search("天安门")
        assertFalse("Search for '天安门' should return results", beijingResults.isEmpty())
        val first = beijingResults.first()
        assertTrue("Name should contain 天安门", first.name.contains("天安门"))
        assertEquals("Beijing Tiananmen latitude roughly 39.9", 39.9087, first.point.latitude, 0.01)
        assertEquals("Beijing Tiananmen longitude roughly 116.4", 116.3975, first.point.longitude, 0.01)

        val shanghaiResults = offlineService.search("东方明珠")
        assertFalse(shanghaiResults.isEmpty())
        assertTrue(shanghaiResults.first().name.contains("东方明珠"))

        val hangzhouResults = offlineService.search("西湖")
        assertFalse(hangzhouResults.isEmpty())
        assertTrue(hangzhouResults.first().name.contains("西湖"))

        val guangzhouResults = offlineService.search("广州塔")
        assertFalse(guangzhouResults.isEmpty())
        assertTrue(guangzhouResults.first().name.contains("广州塔"))
    }

    @Test
    fun testOfflineSearch_prefixAndCaseInsensitive() = runBlocking {
        // 拼音别名与大小写不敏感
        val pinyinResults = offlineService.search("beijing")
        assertFalse("Should match alias 'beijing'", pinyinResults.isEmpty())
        assertTrue(pinyinResults.any { it.name.contains("天安门") })

        val upperCaseResults = offlineService.search("TIANANMEN")
        assertFalse("Should match alias case-insensitively", upperCaseResults.isEmpty())

        val englishResults = offlineService.search("west lake")
        assertFalse("Should match alias 'west lake'", englishResults.isEmpty())
        assertTrue(englishResults.first().name.contains("西湖"))
    }

    @Test
    fun testOfflineSearch_emptyQueryHandling() = runBlocking {
        assertTrue("Empty query returns empty list", offlineService.search("").isEmpty())
        assertTrue("Whitespace query returns empty list", offlineService.search("   ").isEmpty())
    }

    @Test
    fun testOfflineSearch_limitConstraint() = runBlocking {
        val limited = offlineService.search("公园", limit = 2)
        assertTrue("Limit constraint respected", limited.size <= 2)
    }

    @Test
    fun testOfflineReverse_nearestNeighborLookup() = runBlocking {
        // 传入天安门附近 100 米坐标 (39.9088, 116.3976)
        val nearTiananmen = GeoPoint(39.9088, 116.3976)
        val reverseResult = offlineService.reverse(nearTiananmen)

        assertNotNull("Reverse geocode should not be null", reverseResult)
        assertEquals("Nearest landmark should be Beijing Tiananmen", "北京天安门", reverseResult?.name)

        // 传入东方明珠附近坐标 (31.2396, 121.4997)
        val nearPearl = GeoPoint(31.2396, 121.4997)
        val reversePearl = offlineService.reverse(nearPearl)

        assertNotNull(reversePearl)
        assertEquals("Nearest landmark should be Shanghai Oriental Pearl", "上海东方明珠", reversePearl?.name)
    }

    @Test
    fun testNominatim_jsonParsing() {
        val service = NominatimGeocodingService()

        val sampleSearchJson = """
            [
              {
                "place_id": 1001,
                "lat": "39.908722",
                "lon": "116.397496",
                "name": "天安门",
                "display_name": "天安门, 东长安街, 东城区, 北京市, 中国",
                "type": "attraction"
              },
              {
                "place_id": 1002,
                "lat": "31.239700",
                "lon": "121.499800",
                "display_name": "东方明珠广播电视塔, 世纪大道1号, 浦东新区, 上海市",
                "type": "tower"
              }
            ]
        """.trimIndent()

        val parsed = service.parseSearchResults(sampleSearchJson)
        assertEquals("Should parse 2 items", 2, parsed.size)
        assertEquals("天安门", parsed[0].name)
        assertEquals(39.908722, parsed[0].point.latitude, 0.000001)
        assertEquals(116.397496, parsed[0].point.longitude, 0.000001)

        assertEquals("东方明珠广播电视塔", parsed[1].name)
        assertEquals(31.239700, parsed[1].point.latitude, 0.000001)
    }

    @Test
    fun testNominatim_reverseJsonParsing() {
        val service = NominatimGeocodingService()

        val sampleReverseJson = """
            {
              "place_id": 2001,
              "lat": "30.243500",
              "lon": "120.141700",
              "name": "西湖",
              "display_name": "西湖, 西湖区, 杭州市, 浙江省, 中国",
              "type": "water"
            }
        """.trimIndent()

        val parsed = service.parseReverseResult(sampleReverseJson)
        assertNotNull(parsed)
        assertEquals("西湖", parsed?.name)
        assertEquals(30.2435, parsed!!.point.latitude, 0.0001)
        assertEquals(120.1417, parsed.point.longitude, 0.0001)
    }

    @Test
    fun testNominatim_fallbackToOfflineOnNetworkError() = runBlocking {
        // 构造一个必定抛出网络异常的 httpRequester
        val errorService = NominatimGeocodingService(
            fallbackService = offlineService,
            httpRequester = { throw IOException("Simulated network timeout") }
        )

        // 搜索应无缝降级到离线服务
        val results = errorService.search("广州塔")
        assertFalse("Fallback should successfully provide offline results", results.isEmpty())
        assertTrue(results.first().name.contains("广州塔"))

        // 逆向查询也应无缝降级到离线服务
        val reverse = errorService.reverse(GeoPoint(23.1065, 113.3245))
        assertNotNull("Fallback reverse should succeed", reverse)
        assertEquals("广州塔", reverse?.name)
    }
}
