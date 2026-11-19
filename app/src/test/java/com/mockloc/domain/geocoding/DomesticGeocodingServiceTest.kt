package com.mockloc.domain.geocoding

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class DomesticGeocodingServiceTest {

    private fun newService(
        requester: FakeGeocodingHttpRequester,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    ): Pair<DomesticGeocodingService, AtomicInteger> {
        val callCount = AtomicInteger(0)
        val client = GeocodingHttpClient { url ->
            callCount.incrementAndGet()
            val resp = requester.request(url)
            GeocodingHttpResponse(resp.statusCode, resp.body)
        }
        return DomesticGeocodingService(
            httpClient = client,
            fallbackService = OfflineFallbackGeocodingService(),
            ioDispatcher = ioDispatcher
        ) to callCount
    }

    @Test
    fun `UT-G01 standard decimal and Chinese punctuation coordinates resolve directly without HTTP`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, callCount) = newService(requester)

        val cases = listOf(
            "39.9087, 116.3975" to (39.9087 to 116.3975),
            "39.9087，116.3975" to (39.9087 to 116.3975),
            "39.9087 116.3975" to (39.9087 to 116.3975),
            "-33.8688, +151.2093" to (-33.8688 to 151.2093)
        )

        for ((query, expected) in cases) {
            val result = service.search(query)
            assertTrue("Query $query should succeed", result.isSuccess)
            val list = result.getOrThrow()
            assertEquals("Query $query should return 1 item", 1, list.size)
            assertEquals(expected.first, list[0].latitude, 1e-6)
            assertEquals(expected.second, list[0].longitude, 1e-6)
            assertEquals(CoordinateType.WGS84, list[0].coordinateType)
            assertEquals("direct_wgs84", list[0].source)
        }
        assertEquals(0, callCount.get())
    }

    @Test
    fun `UT-G02 hemisphere prefix suffix and lat lon swap recognition`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, callCount) = newService(requester)

        val cases = listOf(
            "N39.9087, E116.3975" to (39.9087 to 116.3975),
            "39.9087°N, 116.3975°E" to (39.9087 to 116.3975),
            "116.3975E, 39.9087N" to (39.9087 to 116.3975),
            "33.8688S 151.2093E" to (-33.8688 to 151.2093),
            "W 74.0060, N 40.7128" to (40.7128 to -74.0060)
        )

        for ((query, expected) in cases) {
            val list = service.search(query).getOrThrow()
            assertEquals("Query $query should parse 1 coordinate", 1, list.size)
            assertEquals(expected.first, list[0].latitude, 1e-6)
            assertEquals(expected.second, list[0].longitude, 1e-6)
        }
        assertEquals(0, callCount.get())
    }

    @Test
    fun `UT-G03 DMS and DM coordinate formats parse accurately`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, _) = newService(requester)

        val dms1 = service.search("""39°54'15"N, 116°24'27"E""").getOrThrow()
        assertEquals(1, dms1.size)
        assertEquals(39.0 + 54.0 / 60.0 + 15.0 / 3600.0, dms1[0].latitude, 1e-5)
        assertEquals(116.0 + 24.0 / 60.0 + 27.0 / 3600.0, dms1[0].longitude, 1e-5)

        val dms2 = service.search("""39° 54′ 15.5″ N 116° 24′ 27.2″ E""").getOrThrow()
        assertEquals(1, dms2.size)
        assertEquals(39.0 + 54.0 / 60.0 + 15.5 / 3600.0, dms2[0].latitude, 1e-5)
        assertEquals(116.0 + 24.0 / 60.0 + 27.2 / 3600.0, dms2[0].longitude, 1e-5)

        val dm = service.search("39°54.25'N, 116°24.45'E").getOrThrow()
        assertEquals(1, dm.size)
        assertEquals(39.0 + 54.25 / 60.0, dm[0].latitude, 1e-5)
        assertEquals(116.0 + 24.45 / 60.0, dm[0].longitude, 1e-5)
    }

    @Test
    fun `UT-G04 explicit coordinate type prefix converts GCJ02 and BD09 to authoritative WGS84`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, _) = newService(requester)

        val gcjRes = service.search("gcj02: 39.9087, 116.3975").getOrThrow().single()
        val expectedGcjToWgs = CoordinateConverter.gcj02ToWgs84(39.9087, 116.3975)
        assertEquals(CoordinateType.WGS84, gcjRes.coordinateType)
        assertEquals("direct_gcj02", gcjRes.source)
        assertEquals(expectedGcjToWgs.latitude, gcjRes.latitude, 1e-8)
        assertEquals(expectedGcjToWgs.longitude, gcjRes.longitude, 1e-8)

        val bdRes = service.search("bd09：39.915, 116.404").getOrThrow().single()
        val expectedBdToWgs = CoordinateConverter.bd09ToWgs84(39.915, 116.404)
        assertEquals(CoordinateType.WGS84, bdRes.coordinateType)
        assertEquals("direct_bd09", bdRes.source)
        assertEquals(expectedBdToWgs.latitude, bdRes.latitude, 1e-8)
        assertEquals(expectedBdToWgs.longitude, bdRes.longitude, 1e-8)

        val wgsRes = service.search("wgs84: 39.9087, 116.3975").getOrThrow().single()
        assertEquals(39.9087, wgsRes.latitude, 1e-8)
        assertEquals(116.3975, wgsRes.longitude, 1e-8)
    }

    @Test
    fun `UT-G05 out of range and invalid coordinate inputs never clamp and never trigger HTTP`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, callCount) = newService(requester)

        val invalidQueries = listOf(
            "",
            "   ",
            "95.0, 116.4",
            "-91.0, 116.4",
            "39.9, 185.0",
            "39.9, -181.0",
            "NaN, 116.4",
            "Infinity, 0.0",
            "39°60'00\"N, 116°24'27\"E",
            "39°54'60\"N, 116°24'27\"E",
            "N39.9, S116.4",
            "N-39.9, E116.4"
        )

        for (query in invalidQueries) {
            val res = service.search(query)
            assertTrue("Query $query must not throw", res.isSuccess)
            assertTrue("Query $query must return empty list", res.getOrThrow().isEmpty())
        }
        assertEquals(0, callCount.get())
    }

    @Test
    fun `UT-G06 online Amap multi-schema JSON parses and converts GCJ02 to WGS84`() = runTest {
        val gcjLat = 39.9101
        val gcjLon = 116.4037
        val expectedWgs = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)

        // 1) tip_list + tip nested + x/y
        val tipListRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(
                Regex(".*poiTipslite.*"),
                """
                {
                  "status": "1",
                  "data": {
                    "tip_list": [
                      {
                        "tip": {
                          "name": "天安门广场",
                          "district": "东城区",
                          "address": [],
                          "cityname": "北京市",
                          "x": "$gcjLon",
                          "y": "$gcjLat"
                        }
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        }
        val (service1, _) = newService(tipListRequester)
        val res1 = service1.search("天安门").getOrThrow()
        assertEquals(1, res1.size)
        assertEquals("天安门广场", res1[0].name)
        assertEquals("北京市", res1[0].city)
        assertEquals("东城区", res1[0].address)
        assertFalse(res1[0].address.contains("[]"))
        assertEquals(CoordinateType.WGS84, res1[0].coordinateType)
        assertEquals("amap_web", res1[0].source)
        assertEquals(expectedWgs.latitude, res1[0].latitude, 1e-7)
        assertEquals(expectedWgs.longitude, res1[0].longitude, 1e-7)

        // 2) pois + location + address string
        val poisRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(
                Regex(".*poiTipslite.*"),
                """
                {
                  "status": "1",
                  "pois": [
                    {
                      "name": "东方明珠",
                      "adname": "浦东新区",
                      "cityname": "上海市",
                      "address": "世纪大道1号",
                      "location": "121.4998,31.2397"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val (service2, _) = newService(poisRequester)
        val res2 = service2.search("东方明珠").getOrThrow()
        assertEquals(1, res2.size)
        assertEquals("东方明珠", res2[0].name)
        val expectedSh = CoordinateConverter.gcj02ToWgs84(31.2397, 121.4998)
        assertEquals(expectedSh.latitude, res2[0].latitude, 1e-7)
        assertEquals(expectedSh.longitude, res2[0].longitude, 1e-7)
    }

    @Test
    fun `UT-G07 online HTTP failures risk control HTML and empty tips smoothly fallback to offline`() = runTest {
        val failureScenarios: List<(FakeGeocodingHttpRequester) -> Unit> = listOf(
            { it.stubTimeout(Regex(".*")) },
            { it.stubNetworkException(Regex(".*"), IOException("Network unreachable")) },
            { it.stubHttpError(Regex(".*"), statusCode = 403, body = "Forbidden") },
            { it.stubHttpError(Regex(".*"), statusCode = 502, body = "Bad Gateway") },
            { it.stubSuccess(Regex(".*"), """{"status":"0","info":"ACCESS_DENIED"}""") },
            { it.stubSuccess(Regex(".*"), "<html><body>验证码拦截</body></html>") },
            { it.stubSuccess(Regex(".*"), """{"status":"1","data":{"tip_list":[]}}""") }
        )

        for (configure in failureScenarios) {
            val requester = FakeGeocodingHttpRequester().also(configure)
            val (service, _) = newService(requester)

            val res = service.search("天安门")
            assertTrue(res.isSuccess)
            val items = res.getOrThrow()
            assertTrue("Fallback should find 天安门", items.isNotEmpty())
            assertEquals("天安门", items[0].name)
            assertEquals(OfflineFallbackGeocodingService.SOURCE_FALLBACK, items[0].source)
            assertEquals(CoordinateType.WGS84, items[0].coordinateType)
            assertTrue(abs(items[0].latitude - 39.9087) < 1e-3)
        }
    }

    @Test
    fun `UT-G08 uses injected ioDispatcher and propagates CancellationException`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val capturedUrls = mutableListOf<String>()

        val slowClient = GeocodingHttpClient { url ->
            capturedUrls.add(url)
            Thread.sleep(50)
            GeocodingHttpResponse(200, """{"status":"1","data":{"tip_list":[]}}""")
        }
        val service = DomesticGeocodingService(
            httpClient = slowClient,
            fallbackService = OfflineFallbackGeocodingService(),
            ioDispatcher = testDispatcher
        )

        val deferred = async { service.search("天安门") }
        // StandardTestDispatcher 尚未推进前不应执行 HTTP 请求
        assertTrue(capturedUrls.isEmpty())

        testScheduler.advanceUntilIdle()
        val result = deferred.await()
        assertTrue(capturedUrls.isNotEmpty())
        assertTrue(capturedUrls.first().contains("ditu.amap.com/service/poiTipslite"))
        assertTrue(result.getOrThrow().isNotEmpty())

        // 验证 CancellationException 原样传播而非被吞掉
        val cancellingClient = GeocodingHttpClient {
            throw CancellationException("job cancelled")
        }
        val cancelService = DomesticGeocodingService(
            httpClient = cancellingClient,
            fallbackService = OfflineFallbackGeocodingService(),
            ioDispatcher = testDispatcher
        )
        var cancellationPropagated = false
        try {
            cancelService.search("故宫")
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue("CancellationException must not be swallowed", cancellationPropagated)
    }

    @Test
    fun `online reverse geocode converts WGS84 input to GCJ02 in request URL and falls back cleanly`() = runTest {
        val wgsLat = 39.9087
        val wgsLon = 116.3975
        val expectedGcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
        var requestedUrl = ""

        val client = GeocodingHttpClient { url ->
            requestedUrl = url
            GeocodingHttpResponse(
                200,
                """
                {
                  "status": "1",
                  "data": {
                    "desc": "北京市东城区东长安街天安门",
                    "city": "北京市",
                    "poi_list": [{"name": "天安门城楼"}]
                  }
                }
                """.trimIndent()
            )
        }
        val service = DomesticGeocodingService(
            httpClient = client,
            fallbackService = OfflineFallbackGeocodingService(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val res = service.reverseGeocode(wgsLat, wgsLon).getOrThrow()
        assertNotNull(res)
        assertEquals("天安门城楼", res!!.name)
        assertEquals("北京市", res.city)
        assertEquals("amap_regeo", res.source)
        assertEquals(wgsLat, res.latitude, 1e-7)
        assertEquals(wgsLon, res.longitude, 1e-7)
        assertTrue(requestedUrl.contains(String.format("%.4f", expectedGcj.latitude)))
        assertTrue(requestedUrl.contains(String.format("%.4f", expectedGcj.longitude)))

        // 非法坐标不触发请求直接返回 null
        val invalid = service.reverseGeocode(95.0, 116.3975).getOrThrow()
        assertNull(invalid)

        // 触发异常时平滑降级到离线逆地理
        val failingService = DomesticGeocodingService(
            httpClient = GeocodingHttpClient { throw SocketTimeoutException("timeout") },
            fallbackService = OfflineFallbackGeocodingService(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val fallbackRegeo = failingService.reverseGeocode(wgsLat, wgsLon).getOrThrow()
        assertNotNull(fallbackRegeo)
        assertEquals("天安门", fallbackRegeo!!.name)
        assertTrue(GeoPoint.isValid(fallbackRegeo.latitude, fallbackRegeo.longitude))
    }

    @Test
    fun `regression blocking HTTP cancellation followed by IOException or SocketTimeoutException must propagate CancellationException`() = runBlocking {
        // 即使底层阻塞 HTTP 客户端忽略中断并抛出 SocketTimeoutException / IOException，
        // 也不得进入 fallbackService 或返回离线 Result.success，必须直接抛出 CancellationException。
        val exceptionsToThrow = listOf(
            SocketTimeoutException("simulated socket timeout after cancel"),
            IOException("simulated io error after cancel")
        )

        for (exception in exceptionsToThrow) {
            // 1) search 路径
            run {
                val enteredHttp = CountDownLatch(1)
                val cancelIssued = CountDownLatch(1)
                val fallbackCalled = AtomicBoolean(false)
                val returnedResult = AtomicBoolean(false)
                val caughtCe = AtomicBoolean(false)

                val client = GeocodingHttpClient {
                    enteredHttp.countDown()
                    // 故意吞掉 InterruptedException 模拟不可中断阻塞后抛 SocketTimeoutException/IOException
                    while (true) {
                        try {
                            if (cancelIssued.await(2, TimeUnit.SECONDS)) break
                        } catch (_: InterruptedException) {
                            // 继续等待 cancelIssued 后抛出目标异常
                        }
                    }
                    throw exception
                }
                val spyFallback = object : GeocodingService {
                    override suspend fun search(query: String): Result<List<com.mockloc.domain.model.GeocodingResult>> {
                        fallbackCalled.set(true)
                        return Result.success(emptyList())
                    }

                    override suspend fun reverseGeocode(lat: Double, lon: Double): Result<com.mockloc.domain.model.GeocodingResult?> {
                        fallbackCalled.set(true)
                        return Result.success(null)
                    }
                }
                val service = DomesticGeocodingService(
                    httpClient = client,
                    fallbackService = spyFallback,
                    ioDispatcher = Dispatchers.IO
                )
                val job = launch(Dispatchers.Default) {
                    try {
                        service.search("天安门")
                        returnedResult.set(true)
                    } catch (_: CancellationException) {
                        caughtCe.set(true)
                    }
                }
                assertTrue(enteredHttp.await(2, TimeUnit.SECONDS))
                job.cancel()
                cancelIssued.countDown()
                job.join()

                assertFalse("search() must not return Result on ${exception.javaClass.simpleName}", returnedResult.get())
                assertFalse("search() must not invoke fallbackService after cancel", fallbackCalled.get())
                assertTrue("search() must throw CancellationException on ${exception.javaClass.simpleName}", caughtCe.get())
            }

            // 2) reverseGeocode 路径
            run {
                val enteredHttp = CountDownLatch(1)
                val cancelIssued = CountDownLatch(1)
                val fallbackCalled = AtomicBoolean(false)
                val returnedResult = AtomicBoolean(false)
                val caughtCe = AtomicBoolean(false)

                val client = GeocodingHttpClient {
                    enteredHttp.countDown()
                    try {
                        cancelIssued.await(2, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // 模拟线程被 runInterruptible 中断后底层库转为 IOException / SocketTimeoutException
                    }
                    throw exception
                }
                val spyFallback = object : GeocodingService {
                    override suspend fun search(query: String): Result<List<com.mockloc.domain.model.GeocodingResult>> {
                        fallbackCalled.set(true)
                        return Result.success(emptyList())
                    }

                    override suspend fun reverseGeocode(lat: Double, lon: Double): Result<com.mockloc.domain.model.GeocodingResult?> {
                        fallbackCalled.set(true)
                        return Result.success(null)
                    }
                }
                val service = DomesticGeocodingService(
                    httpClient = client,
                    fallbackService = spyFallback,
                    ioDispatcher = Dispatchers.IO
                )
                val job = launch(Dispatchers.Default) {
                    try {
                        service.reverseGeocode(39.9087, 116.3975)
                        returnedResult.set(true)
                    } catch (_: CancellationException) {
                        caughtCe.set(true)
                    }
                }
                assertTrue(enteredHttp.await(2, TimeUnit.SECONDS))
                job.cancel()
                cancelIssued.countDown()
                job.join()

                assertFalse("reverseGeocode() must not return Result on ${exception.javaClass.simpleName}", returnedResult.get())
                assertFalse("reverseGeocode() must not invoke fallbackService after cancel", fallbackCalled.get())
                assertTrue("reverseGeocode() must throw CancellationException on ${exception.javaClass.simpleName}", caughtCe.get())
            }
        }
    }

    @Test
    fun `regression fullwidth coordinates double-single-quote seconds and empty prefix handling`() = runTest {
        val requester = FakeGeocodingHttpRequester()
        val (service, callCount) = newService(requester)

        // 1) 全角数字、全角小数点、全角正负号、全角字母、全角空格、全角前缀
        val fwPlain = CoordinateQueryParser.parseWithStatus("３９．９０８７，１１６．３９７５")
        assertTrue(fwPlain is CoordinateParseOutcome.Valid)
        assertEquals(39.9087, (fwPlain as CoordinateParseOutcome.Valid).result.latitude, 1e-6)
        assertEquals(116.3975, fwPlain.result.longitude, 1e-6)

        val fwSigned = CoordinateQueryParser.parseWithStatus("＋３９．９０８７，－１１６．３９７５")
        assertTrue(fwSigned is CoordinateParseOutcome.Valid)
        assertEquals(39.9087, (fwSigned as CoordinateParseOutcome.Valid).result.latitude, 1e-6)
        assertEquals(-116.3975, fwSigned.result.longitude, 1e-6)

        val fwHemi = CoordinateQueryParser.parseWithStatus("　39.9087Ｎ，　116.3975Ｅ　")
        assertTrue(fwHemi is CoordinateParseOutcome.Valid)
        assertEquals(39.9087, (fwHemi as CoordinateParseOutcome.Valid).result.latitude, 1e-6)
        assertEquals(116.3975, fwHemi.result.longitude, 1e-6)

        val fwPrefix = CoordinateQueryParser.parseWithStatus("ｇｃｊ０２：39.9087, 116.3975")
        assertTrue(fwPrefix is CoordinateParseOutcome.Valid)
        val expectedWgs = CoordinateConverter.gcj02ToWgs84(39.9087, 116.3975)
        assertEquals(expectedWgs.latitude, (fwPrefix as CoordinateParseOutcome.Valid).result.latitude, 1e-8)
        assertEquals(expectedWgs.longitude, fwPrefix.result.longitude, 1e-8)
        assertEquals("direct_gcj02", fwPrefix.result.source)

        // 2) 连续两个单引号 '' 及全角单引号 ＇＇ 表示角秒
        val doubleSingleQuote = CoordinateQueryParser.parseWithStatus("39° 54' 15.5'' N, 116° 24' 27.2'' E")
        assertTrue(doubleSingleQuote is CoordinateParseOutcome.Valid)
        assertEquals(39.0 + 54.0 / 60.0 + 15.5 / 3600.0, (doubleSingleQuote as CoordinateParseOutcome.Valid).result.latitude, 1e-6)
        assertEquals(116.0 + 24.0 / 60.0 + 27.2 / 3600.0, doubleSingleQuote.result.longitude, 1e-6)

        val fwDoubleSingleQuote = CoordinateQueryParser.parseWithStatus("３９° ５４＇ １５．５＇＇ Ｎ， １１６° ２４＇ ２７．２＇＇ Ｅ")
        assertTrue(fwDoubleSingleQuote is CoordinateParseOutcome.Valid)
        assertEquals(39.0 + 54.0 / 60.0 + 15.5 / 3600.0, (fwDoubleSingleQuote as CoordinateParseOutcome.Valid).result.latitude, 1e-6)
        assertEquals(116.0 + 24.0 / 60.0 + 27.2 / 3600.0, fwDoubleSingleQuote.result.longitude, 1e-6)

        // 3) 空体前缀 gcj02: / gcj02:   必须命中 OutOfRangeOrInvalid，不发起 HTTP
        assertEquals(CoordinateParseOutcome.OutOfRangeOrInvalid, CoordinateQueryParser.parseWithStatus("gcj02:"))
        assertEquals(CoordinateParseOutcome.OutOfRangeOrInvalid, CoordinateQueryParser.parseWithStatus("gcj02:   "))
        assertEquals(CoordinateParseOutcome.OutOfRangeOrInvalid, CoordinateQueryParser.parseWithStatus("ｇｃｊ０２：　"))
        assertTrue(service.search("gcj02:   ").getOrThrow().isEmpty())
        assertEquals(0, callCount.get())
    }

    @Test
    fun `multi-provider cascade resolves Liupanshui via Photon Nominatim or Open-Meteo when Amap returns risk HTML`() = runTest {
        // 1) Amap 风控 HTML -> Photon 成功返回
        val photonRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(Regex(".*ditu\\.amap\\.com.*"), "<!DOCTYPE html><html><script src='punish'></script></html>")
            stubSuccess(
                Regex(".*photon\\.komoot\\.io.*"),
                """
                {
                  "type": "FeatureCollection",
                  "features": [
                    {
                      "properties": {"name": "六盘水市", "city": "六盘水市", "state": "贵州省", "country": "中国"},
                      "geometry": {"type": "Point", "coordinates": [104.83333, 26.59444]}
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val (photonService, _) = newService(photonRequester)
        val photonRes = photonService.search("六盘水").getOrThrow()
        assertEquals(1, photonRes.size)
        assertEquals("六盘水市", photonRes[0].name)
        assertEquals("photon_online", photonRes[0].source)
        assertEquals(26.59444, photonRes[0].latitude, 1e-5)
        assertEquals(104.83333, photonRes[0].longitude, 1e-5)

        // 2) Amap + Photon 失败 -> Nominatim 成功返回
        val nominatimRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(Regex(".*ditu\\.amap\\.com.*"), "<html>blocked</html>")
            stubHttpError(Regex(".*photon\\.komoot\\.io.*"), 503, "busy")
            stubSuccess(
                Regex(".*nominatim\\.openstreetmap\\.org.*"),
                """
                [
                  {
                    "lat": "26.5963778",
                    "lon": "104.8279652",
                    "name": "六盘水市",
                    "display_name": "六盘水市, 贵州省, 中国"
                  }
                ]
                """.trimIndent()
            )
        }
        val (nominatimService, _) = newService(nominatimRequester)
        val nomRes = nominatimService.search("六盘水").getOrThrow()
        assertEquals(1, nomRes.size)
        assertEquals("六盘水市", nomRes[0].name)
        assertEquals("nominatim_online", nomRes[0].source)

        // 3) Amap + Photon + Nominatim 失败 -> Open-Meteo 成功返回
        val openMeteoRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(Regex(".*ditu\\.amap\\.com.*"), "<html>blocked</html>")
            stubTimeout(Regex(".*photon\\.komoot\\.io.*"))
            stubTimeout(Regex(".*nominatim\\.openstreetmap\\.org.*"))
            stubSuccess(
                Regex(".*geocoding-api\\.open-meteo\\.com.*"),
                """
                {
                  "results": [
                    {
                      "name": "六盘水市",
                      "latitude": 26.59444,
                      "longitude": 104.83333,
                      "country": "中国",
                      "admin1": "贵州",
                      "admin2": "六盘水市"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val (omService, _) = newService(openMeteoRequester)
        val omRes = omService.search("六盘水").getOrThrow()
        assertEquals(1, omRes.size)
        assertEquals("六盘水市", omRes[0].name)
        assertEquals("open_meteo_online", omRes[0].source)

        // 4) 百度 Web 搜索（中国大陆直连免 Key，BD-09MC -> WGS-84 精确转换）
        val baiduRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(Regex(".*ditu\\.amap\\.com.*"), "<html>blocked</html>")
            stubSuccess(
                Regex(".*map\\.baidu\\.com.*"),
                """
                {
                  "content": [
                    {
                      "name": "万达广场(六盘水店)",
                      "addr": "六盘水市钟山区碧云路与麒麟路交汇处",
                      "x": 1167358396,
                      "y": 305206857,
                      "city_name": "六盘水市"
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val (baiduService, _) = newService(baiduRequester)
        val baiduRes = baiduService.search("六盘水万达").getOrThrow()
        assertEquals(1, baiduRes.size)
        assertEquals("万达广场(六盘水店)", baiduRes[0].name)
        assertEquals("baidu_web", baiduRes[0].source)
        assertTrue(baiduRes[0].latitude in 26.5..26.7)
        assertTrue(baiduRes[0].longitude in 104.7..104.95)

        // 5) ArcGIS World Geocoding（免 Key 标准 WGS-84）
        val arcgisRequester = FakeGeocodingHttpRequester().apply {
            stubSuccess(Regex(".*ditu\\.amap\\.com.*"), "<html>blocked</html>")
            stubHttpError(Regex(".*map\\.baidu\\.com.*"), 403, "forbidden")
            stubSuccess(
                Regex(".*geocode\\.arcgis\\.com.*"),
                """
                {
                  "candidates": [
                    {
                      "address": "贵州省六盘水",
                      "location": {"x": 104.8279652, "y": 26.5963778},
                      "attributes": {"Match_addr": "贵州省六盘水", "Place_addr": "贵州省六盘水市", "City": "六盘水市", "Region": "贵州省"}
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val (arcgisService, _) = newService(arcgisRequester)
        val arcRes = arcgisService.search("六盘水").getOrThrow()
        assertEquals(1, arcRes.size)
        assertEquals("贵州省六盘水", arcRes[0].name)
        assertEquals("arcgis_online", arcRes[0].source)
        assertEquals(26.5963778, arcRes[0].latitude, 1e-6)
        assertEquals(104.8279652, arcRes[0].longitude, 1e-6)
    }
}
