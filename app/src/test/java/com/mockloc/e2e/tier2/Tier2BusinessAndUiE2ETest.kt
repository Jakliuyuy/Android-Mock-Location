package com.mockloc.e2e.tier2

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.favorite.FavoriteRepository
import com.mockloc.data.route.RouteRepository
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.scope.RecommendedScopeRepository
import com.mockloc.data.whitelist.AndroidPackageQuerySource
import com.mockloc.data.whitelist.InstalledAppRecord
import com.mockloc.data.whitelist.PackageQuerySource
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.geocoding.CoordinateParseOutcome
import com.mockloc.domain.geocoding.CoordinateQueryParser
import com.mockloc.domain.geocoding.DomesticGeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.TestEnvironmentPresets
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toEnvironmentStatus
import com.mockloc.e2e.factory.toGeocodingHttpClient
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toPlannedRoute
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.factory.withMainDispatcher
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.presentation.MainViewModel
import com.mockloc.presentation.gateway.ControllerEngineGateway
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.root.EnvironmentDetector
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.NoOpRootLocationDriver
import com.mockloc.service.PermissionCheckResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class Tier2BusinessAndUiE2ETest {

    private fun resetAllRepositorySingletons() {
        FavoriteRepository.setInstanceForTest(null)
        RouteRepository.setInstanceForTest(null)
        WhitelistRepository.setInstanceForTest(null)
        MockConfigRepository.setInstanceForTest(null)
    }

    private fun cleanupUiResources(
        viewModels: List<ViewModel> = emptyList(),
        controllers: List<MockEngineController> = emptyList(),
        scopes: List<CoroutineScope> = emptyList()
    ) {
        runBlocking {
            withTimeoutOrNull(3_000L) {
                viewModels.forEach { vm ->
                    runCatching { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                }
            }
            controllers.forEach { controller ->
                runCatching { controller.release() }
            }
            withTimeoutOrNull(3_000L) {
                scopes.forEach { scope ->
                    runCatching { scope.coroutineContext[Job]?.cancelAndJoin() }
                }
            }
        }
        resetAllRepositorySingletons()
    }

    private suspend fun awaitUntil(timeoutMs: Long = 3000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(15L)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    // ==================== F12: 地图浏览选点与国内直连搜地（边界与异常） ====================

    @Test
    fun `T2-F12-01 empty blank queries skip network and special characters are URL encoded safely`() = runBlocking {
        try {
            val requester = FakeGeocodingHttpRequester().apply {
                stubSuccess(Regex(".*"), """{"status":"1","data":{"tip_list":[]}}""")
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            assertTrue(service.search("").getOrThrow().isEmpty())
            assertTrue(service.search("   \t\n  ").getOrThrow().isEmpty())
            assertEquals("Blank queries must not hit network", 0, requester.requestCount.get())

            val specialRes = service.search("!@#\$%^&*()_+<script>")
            val ethnicRes = service.search("新疆维吾尔自治区")
            assertTrue(specialRes.isSuccess)
            assertTrue(ethnicRes.isSuccess)
            assertTrue(requester.requestCount.get() >= 2)

            val encodedSpecialUrl = requester.requestedUrls.firstOrNull { it.contains("words=") }
                ?: requester.requestedUrls[0]
            assertFalse("Raw '<' must be percent-encoded", encodedSpecialUrl.contains("<script>"))
            if (encodedSpecialUrl.contains("words=")) {
                assertFalse(
                    "Raw '&' in query must be percent-encoded",
                    encodedSpecialUrl.substringAfter("words=").substringBefore("&city=").contains("&")
                )
            }
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F12-02 out-of-range coordinates single number and non-numeric text are rejected cleanly`() = runBlocking {
        try {
            val requester = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            assertEquals(
                CoordinateParseOutcome.OutOfRangeOrInvalid,
                CoordinateQueryParser.parseWithStatus("95.0, 200.0")
            )
            assertEquals(
                CoordinateParseOutcome.OutOfRangeOrInvalid,
                CoordinateQueryParser.parseWithStatus("-91.0, 116.4")
            )
            assertTrue(service.search("95.0, 200.0").getOrThrow().isEmpty())
            assertEquals(0, requester.requestCount.get())

            assertEquals(CoordinateParseOutcome.NotACoordinate, CoordinateQueryParser.parseWithStatus("39.9087"))
            assertEquals(CoordinateParseOutcome.NotACoordinate, CoordinateQueryParser.parseWithStatus("abc, def"))
            assertTrue(service.search("39.9087").isSuccess)
            assertTrue(service.search("abc, def").isSuccess)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F12-03 HTTP 403 502 empty body malformed JSON and timeout all fall back smoothly`() = runBlocking {
        try {
            val scenarios: List<(FakeGeocodingHttpRequester) -> Unit> = listOf(
                { it.stubHttpError(Regex(".*"), 403, "Forbidden") },
                { it.stubHttpError(Regex(".*"), 502, "Bad Gateway") },
                { it.stubSuccess(Regex(".*"), "") },
                { it.stubSuccess(Regex(".*"), "{bad_json") },
                { it.stubTimeout(Regex(".*")) }
            )

            for (configure in scenarios) {
                val requester = FakeGeocodingHttpRequester().also(configure)
                val service = DomesticGeocodingService(
                    httpClient = requester.toGeocodingHttpClient(),
                    fallbackService = OfflineFallbackGeocodingService(),
                    ioDispatcher = Dispatchers.IO
                )

                val known = service.search("天安门").getOrThrow()
                assertTrue("Known landmark must still match via offline fallback", known.isNotEmpty())
                assertEquals(OfflineFallbackGeocodingService.SOURCE_FALLBACK, known.first().source)

                val unknown = service.search("完全不存在的火星地标XYZ").getOrThrow()
                assertTrue(unknown.isEmpty())
            }
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F12-04 fallback result count respects MAX_RESULTS cap and unknown query returns emptyList`() = runBlocking {
        try {
            val requester = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            val beijingResults = service.search("北京").getOrThrow()
            assertTrue(beijingResults.isNotEmpty())
            assertTrue(
                "Offline results (${beijingResults.size}) must be <= 20",
                beijingResults.size <= 20
            )

            val missing = service.search("火星基地99999").getOrThrow()
            assertTrue(missing.isEmpty())
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F12-05 reverseGeocode handles extreme boundary coordinates and out-of-range safely`() = runBlocking {
        try {
            val requester = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            val northPoleBoundary = service.reverseGeocode(90.0, 180.0)
            val southPoleBoundary = service.reverseGeocode(-90.0, -180.0)
            assertTrue(northPoleBoundary.isSuccess)
            assertTrue(southPoleBoundary.isSuccess)
            assertNotNull(northPoleBoundary.getOrNull())

            val outLatLon = service.reverseGeocode(91.0, 181.0)
            val nanInput = service.reverseGeocode(Double.NaN, 116.4)
            assertTrue(outLatLon.isSuccess)
            assertNull(outLatLon.getOrNull())
            assertTrue(nanInput.isSuccess)
            assertNull(nanInput.getOrNull())
        } finally {
            resetAllRepositorySingletons()
        }
    }

    // ==================== F13: 常用地点收藏夹完整功能（边界与异常） ====================

    @Test
    fun `T2-F13-01 corrupted favorites JSON and out-of-range records recover safely`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences().apply {
                injectRawValue(FavoriteRepository.KEY_INITIALIZED, true)
                injectRawValue(FavoriteRepository.KEY_FAVORITES_JSON, "{invalid_json")
            }
            val repo1 = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo1.ensureInitialized()
            assertTrue(repo1.getAll().isEmpty())

            val mixedJson = """
                [
                  {"id":"bad1","name":"越界","address":"","latitude":95.0,"longitude":116.4},
                  {"id":"ok1","name":"合法点","address":"北京","latitude":39.9087,"longitude":116.3975}
                ]
            """.trimIndent()
            prefs.injectRawValue(FavoriteRepository.KEY_INITIALIZED, true)
            prefs.injectRawValue(FavoriteRepository.KEY_FAVORITES_JSON, mixedJson)

            val repo2 = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo2.ensureInitialized()
            val loaded = repo2.getAll()
            assertEquals(1, loaded.size)
            assertEquals("ok1", loaded.first().id)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F13-02 duplicate favorite addition deduplicates and updates existing record`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            assertTrue(repo.clearAll())

            val first = repo.add("打卡点", "地址A", 31.2397, 121.4998).getOrThrow()
            val second = repo.add("打卡点", "地址B", 31.2397, 121.4998).getOrThrow()

            val all = repo.getAll()
            assertEquals(1, all.size)
            assertEquals(first.id, second.id)
            assertEquals("地址B", all.first().address)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F13-03 update with missing ID or blank name fails safely without mutating valid data`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            val valid = repo.add("合法收藏", "北京", 39.9087, 116.3975).getOrThrow()

            val missingRes = repo.update("non-existent-id", "新名")
            assertTrue(missingRes.isFailure)
            assertTrue(missingRes.exceptionOrNull() is NoSuchElementException)

            val blankRes = repo.update(valid.id, "   ")
            assertTrue(blankRes.isFailure)
            assertTrue(blankRes.exceptionOrNull() is IllegalArgumentException)

            val after = repo.getById(valid.id)!!
            assertEquals("合法收藏", after.name)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F13-04 deleting all favorites leaves empty list across repository restarts`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            for (item in repo.getAll()) {
                assertTrue(repo.remove(item.id))
            }
            assertTrue(repo.getAll().isEmpty())
            assertTrue(repo.search("北京").isEmpty())

            val reloaded = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            reloaded.ensureInitialized()
            assertTrue("Defaults must not resurrect after user deletes all", reloaded.getAll().isEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F13-05 special characters emoji quotes newlines and 10-thread concurrency stay intact`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            repo.clearAll()

            val specialName = "家 🏠 \"A\"\nB"
            val specialAddr = "朝阳区\\测试\"路'\t1号"
            val added = repo.add(specialName, specialAddr, 39.91, 116.41).getOrThrow()

            val reloaded = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            val fetched = reloaded.getById(added.id)!!
            assertEquals(specialName, fetched.name)
            assertEquals(specialAddr, fetched.address)

            val errors = CopyOnWriteArrayList<Throwable>()
            val jobs = (0 until 10).map { tIdx ->
                async(Dispatchers.Default) {
                    runCatching {
                        repeat(15) { step ->
                            val lat = 20.0 + tIdx * 0.5 + step * 0.01
                            val lon = 100.0 + tIdx * 0.5 + step * 0.01
                            val res = reloaded.add("并发点_${tIdx}_$step", "Addr", lat, lon)
                            res.getOrNull()?.let { item ->
                                reloaded.update(item.id, "更新_${tIdx}_$step", "Addr2")
                                if (step % 3 == 0) {
                                    reloaded.remove(item.id)
                                }
                            }
                            reloaded.getAll()
                            reloaded.search("并发点_$tIdx")
                        }
                    }.onFailure { errors.add(it) }
                }
            }
            jobs.awaitAll()
            assertTrue("Unexpected concurrency exceptions: $errors", errors.isEmpty())
            assertTrue(reloaded.getAll().isNotEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    // ==================== F14: 多航点路线管理与持久化（边界与异常） ====================

    @Test
    fun `T2-F14-01 truncated corrupted route JSON recovers safely to emptyList`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences().apply {
                injectRawValue(RouteRepository.KEY_INITIALIZED, true)
                injectRawValue(RouteRepository.KEY_ROUTES_JSON, """[{"id":"1"""")
            }
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            assertTrue(repo.getAllRoutes().isEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F14-02 saveRoute rejects blank route name and empty-waypoints route`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            repo.clearAllRoutes()

            val blankNameRoute = PlannedRoute(
                id = "r_blank",
                name = "   ",
                waypoints = listOf(Waypoint(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()))
            )
            val blankRes = repo.saveRoute(blankNameRoute)
            assertTrue(blankRes.isFailure)
            assertTrue(blankRes.exceptionOrNull() is IllegalArgumentException)

            val emptyWpRoute = TestRouteSpecs.EMPTY_ROUTE.toPlannedRoute()
            val emptyRes = repo.saveRoute(emptyWpRoute)
            assertTrue(emptyRes.isFailure)
            assertTrue(emptyRes.exceptionOrNull() is IllegalArgumentException)

            assertTrue(repo.getAllRoutes().isEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F14-03 deleteRoute with unknown ID and repeated deletion is idempotent and safe`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            repo.clearAllRoutes()

            val rKeep = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute().copy(id = "r_keep")
            val r1 = TestRouteSpecs.CLOSED_LOOP_4WP.toPlannedRoute().copy(id = "r1")
            repo.saveRoute(rKeep).getOrThrow()
            repo.saveRoute(r1).getOrThrow()

            assertFalse(repo.deleteRoute("not_found"))
            assertTrue(repo.deleteRoute("r1"))
            assertFalse(repo.deleteRoute("r1"))

            val remaining = repo.getAllRoutes()
            assertEquals(1, remaining.size)
            assertEquals("r_keep", remaining.first().id)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F14-04 500-waypoint large route persists reloads and preserves totalDistanceMeters`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            repo.clearAllRoutes()

            val large = TestRouteSpecs.LARGE_500WP_ROUTE.toPlannedRoute()
            val expectedDist = large.totalDistanceMeters()
            val savedRes = repo.saveRoute(large)
            assertTrue(savedRes.isSuccess)

            val reloadedRepo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            val loaded = reloadedRepo.getRouteById(large.id)
            assertNotNull(loaded)
            assertEquals(500, loaded!!.waypoints.size)
            assertEquals(large.waypoints.first().point.latitude, loaded.waypoints.first().point.latitude, 1e-6)
            assertEquals(large.waypoints.last().point.longitude, loaded.waypoints.last().point.longitude, 1e-6)
            assertEquals(expectedDist, loaded.totalDistanceMeters(), 1e-6)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F14-05 Waypoint constructor and RouteRepository reject invalid dwellTime and targetSpeedMps`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pt = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()

            val negDwellThrown = runCatching { Waypoint(pt, dwellTimeSeconds = -0.1) }.exceptionOrNull()
            assertTrue(negDwellThrown is IllegalArgumentException)

            val nanDwellThrown = runCatching { Waypoint(pt, dwellTimeSeconds = Double.NaN) }.exceptionOrNull()
            assertTrue(nanDwellThrown is IllegalArgumentException)

            val negSpeedThrown = runCatching { Waypoint(pt, targetSpeedMps = -2.0f) }.exceptionOrNull()
            assertTrue(negSpeedThrown is IllegalArgumentException)

            val infSpeedThrown = runCatching { Waypoint(pt, targetSpeedMps = Float.POSITIVE_INFINITY) }.exceptionOrNull()
            assertTrue(infSpeedThrown is IllegalArgumentException)

            val repo = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            val zeroSpeedRoute = PlannedRoute(
                id = "r_zero_speed",
                name = "零速航点",
                waypoints = listOf(Waypoint(pt, targetSpeedMps = 0.0f))
            )
            assertTrue(repo.saveRoute(zeroSpeedRoute).isFailure)

            val overSpeedRoute = PlannedRoute(
                id = "r_over_speed",
                name = "超速航点",
                waypoints = listOf(Waypoint(pt, targetSpeedMps = 301.0f))
            )
            assertTrue(repo.saveRoute(overSpeedRoute).isFailure)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    // ==================== F15: 目标应用分流白名单管理（边界与异常） ====================

    @Test
    fun `T2-F15-01 PackageManager exception degrades gracefully to emptyList`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pm = Mockito.mock(PackageManager::class.java)
            @Suppress("DEPRECATION")
            Mockito.`when`(pm.getInstalledPackages(Mockito.anyInt()))
                .thenThrow(RuntimeException("DeadObjectException simulated"))
            runCatching {
                Mockito.`when`(pm.getInstalledPackages(Mockito.nullable(PackageManager.PackageInfoFlags::class.java)))
                    .thenThrow(RuntimeException("DeadObjectException simulated"))
            }

            val querySource = AndroidPackageQuerySource(pm, Dispatchers.IO)
            assertTrue(querySource.queryInstalledApps().isEmpty())

            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = querySource,
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )
            assertTrue(repo.getInstalledApps(true).isEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F15-02 dirty PackageInfo skips null packageName and falls back when loadLabel throws`() = runBlocking {
        try {
            val pm = Mockito.mock(PackageManager::class.java)

            val nullPkgInfo = PackageInfo().apply {
                PackageInfo::class.java.getField("packageName").set(this, null)
            }

            val badLabelAppInfo = Mockito.mock(ApplicationInfo::class.java).apply {
                flags = 0
                Mockito.`when`(loadLabel(pm)).thenThrow(RuntimeException("Resource table broken"))
            }
            val badLabelPkgInfo = PackageInfo().apply {
                packageName = "com.bad.label"
                applicationInfo = badLabelAppInfo
            }

            val goodAppInfo = Mockito.mock(ApplicationInfo::class.java).apply {
                flags = 0
                Mockito.`when`(loadLabel(pm)).thenReturn("正常应用")
            }
            val goodPkgInfo = PackageInfo().apply {
                packageName = "com.good.app"
                applicationInfo = goodAppInfo
            }

            val rawList = listOf(nullPkgInfo, badLabelPkgInfo, goodPkgInfo)
            @Suppress("DEPRECATION")
            Mockito.`when`(pm.getInstalledPackages(Mockito.anyInt())).thenReturn(rawList)
            runCatching {
                Mockito.`when`(pm.getInstalledPackages(Mockito.nullable(PackageManager.PackageInfoFlags::class.java)))
                    .thenReturn(rawList)
            }

            val records = AndroidPackageQuerySource(pm, Dispatchers.IO).queryInstalledApps()
            assertEquals(2, records.size)
            assertEquals("com.bad.label", records[0].appName)
            assertEquals("正常应用", records[1].appName)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F15-03 null empty blank and malformed package names never match whitelist`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            configRepo.updateImmediate { it.copy(enabled = true) }
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { emptyList() },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )
            repo.saveSelectedPackages(setOf("com.tencent.mm"))
            val cfg = configRepo.currentConfig

            val invalidInputs = listOf<String?>(null, "", "   ", "com.app/evil", "com.app;id")
            for (pkg in invalidInputs) {
                assertFalse("WhitelistRepository must reject '$pkg'", repo.isPackageSelected(pkg))
                assertFalse("MockConfig must reject '$pkg'", cfg.isEnabledForPackage(pkg))
            }
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F15-04 filterApps treats regex metacharacters as literal substrings without PatternSyntaxException`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            val records = listOf(
                InstalledAppRecord("com.special.demo", "[com.*?(test)\\", isSystemApp = false),
                InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false)
            )
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { records },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )
            val apps = repo.getInstalledApps(true)

            assertEquals(1, repo.filterApps(apps, "[com.").size)
            assertEquals(1, repo.filterApps(apps, "*?").size)
            assertEquals(1, repo.filterApps(apps, "(test").size)
            assertEquals(1, repo.filterApps(apps, "\\").size)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F15-05 saving emptySet persists across restarts without resurrecting default preset packages`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configPrefs = InMemorySharedPreferences()
            val metaPrefs = InMemorySharedPreferences()

            val configRepo1 = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 0L)
            val whitelist1 = WhitelistRepository(
                configRepository = configRepo1,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource { emptyList() },
                prepopulateOnFirstInit = true,
                ioDispatcher = Dispatchers.IO
            )
            assertEquals(WhitelistRepository.DEFAULT_PRESET_PACKAGES.size, whitelist1.getSelectedPackages().size)

            whitelist1.saveSelectedPackages(emptySet())
            assertTrue(whitelist1.getSelectedPackages().isEmpty())

            val configRepo2 = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 0L)
            val whitelist2 = WhitelistRepository(
                configRepository = configRepo2,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource { emptyList() },
                prepopulateOnFirstInit = true,
                ioDispatcher = Dispatchers.IO
            )
            assertTrue(
                "Explicitly cleared whitelist must not resurrect default 9 packages",
                whitelist2.getSelectedPackages().isEmpty()
            )
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    // ==================== F16: LSPosed 推荐作用域与引导（边界与异常） ====================

    @Test
    fun `T2-F16-01 zero installed recommended apps yields all false and zero stats`() = runBlocking {
        try {
            val pm = Mockito.mock(PackageManager::class.java)
            @Suppress("DEPRECATION")
            Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt()))
                .thenThrow(PackageManager.NameNotFoundException("not installed"))
            runCatching {
                Mockito.`when`(
                    pm.getPackageInfo(
                        Mockito.anyString(),
                        Mockito.nullable(PackageManager.PackageInfoFlags::class.java)
                    )
                ).thenThrow(PackageManager.NameNotFoundException("not installed"))
            }

            val scopes = RecommendedScopeRepository.getRecommendedScopes(pm)
            assertEquals(16, scopes.size)
            assertTrue(scopes.none { it.isInstalled })

            val summary = RecommendedScopeRepository.summarizeScopeStats(scopes)
            assertEquals(0, summary.installedTargetCount)
            assertEquals(0, summary.installedAllCount)

            val ctx = buildTestAppContext(
                installedPackages = emptySet(),
                xposedScopeArray = RecommendedScopeRepository.getXposedScopeArrayPackages().toTypedArray()
            )
            val shell = FakeShellExecutor().apply {
                stubCommand(Regex(".*"), FakeShellResult(exitCode = 1))
            }.toShellExecutor()
            val env = EnvironmentDetector.detectEnvironment(ctx, shell, sdkInt = 35)
            assertEquals(0, env.recommendedScopeInstalledCount)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F16-02 SecurityException on single package isolates failure without blocking other probes`() {
        try {
            val pm = Mockito.mock(PackageManager::class.java)
            fun handleQuery(pkg: String): PackageInfo {
                return when (pkg) {
                    "com.tencent.mm" -> throw SecurityException("Access denied for wechat")
                    "com.autonavi.minimap" -> PackageInfo().apply { packageName = pkg }
                    else -> throw PackageManager.NameNotFoundException(pkg)
                }
            }
            @Suppress("DEPRECATION")
            Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
                handleQuery(inv.getArgument(0))
            }
            runCatching {
                Mockito.`when`(
                    pm.getPackageInfo(
                        Mockito.anyString(),
                        Mockito.nullable(PackageManager.PackageInfoFlags::class.java)
                    )
                ).thenAnswer { inv ->
                    handleQuery(inv.getArgument(0))
                }
            }

            val scopes = RecommendedScopeRepository.getRecommendedScopes(pm)
            assertFalse(scopes.first { it.packageName == "com.tencent.mm" }.isInstalled)
            assertTrue(scopes.first { it.packageName == "com.autonavi.minimap" }.isInstalled)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F16-03 missing LSPosed Manager and zero installed scopes degrade without ActivityNotFoundException`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val pm = Mockito.mock(PackageManager::class.java)
                @Suppress("DEPRECATION")
                Mockito.`when`(pm.resolveActivity(Mockito.any(Intent::class.java), Mockito.anyInt()))
                    .thenReturn(null)
                @Suppress("DEPRECATION")
                Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt()))
                    .thenThrow(PackageManager.NameNotFoundException("none"))
                runCatching {
                    Mockito.`when`(
                        pm.getPackageInfo(
                            Mockito.anyString(),
                            Mockito.nullable(PackageManager.PackageInfoFlags::class.java)
                        )
                    ).thenThrow(PackageManager.NameNotFoundException("none"))
                }

                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val batchRes = RecommendedScopeRepository.addAllInstalledToWhitelist(pm, whitelistRepo)
                assertTrue(batchRes.importedPackages.isEmpty())

                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                    .also { trackedControllers.add(it) }
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_UNPRIVILEGED.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { pkg ->
                        runCatching {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(pkg, 0)
                            true
                        }.getOrDefault(false)
                    },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val job = launch(Dispatchers.Unconfined) {
                    vm.events.collect { events.add(it) }
                }
                vm.importRecommendedInstalledToWhitelist()
                awaitUntil { events.any { it is MainUiEvent.ScopeGuideFeedback } }
                val fb = events.filterIsInstance<MainUiEvent.ScopeGuideFeedback>().last()
                assertTrue(fb.success)
                assertTrue(fb.message.contains("未检测到"))
                job.cancel()

                // 静态核对 ScopeGuideDialogFragment.kt 无裸露外部 startActivity 调用，彻底免疫 ActivityNotFoundException
                val scopeGuideFile = File(WorkspacePathResolver.mainJavaDir(), "presentation/ScopeGuideDialogFragment.kt")
                val scopeGuideSrc = scopeGuideFile.readText(Charsets.UTF_8)
                val hasUnprotectedStartActivity = scopeGuideSrc.contains("startActivity(") &&
                    !scopeGuideSrc.contains("ActivityNotFoundException")
                assertFalse(
                    "ScopeGuideDialogFragment must not invoke unprotected startActivity without ActivityNotFoundException handling",
                    hasUnprotectedStartActivity
                )
                // 同时确认 resolveActivity 为 null 时不会抛异常
                assertNull(
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(Intent("org.lsposed.manager.ACTION"), 0)
                )
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T2-F16-04 self package android framework and third-party scopes distinguish system scope descriptions`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val scopes = RecommendedScopeRepository.getRecommendedScopes(PackageInstallChecker { true })
            val selfItem = scopes.first { it.packageName == RecommendedScopeRepository.SELF_PACKAGE_NAME }
            val androidItem = scopes.first { it.packageName == RecommendedScopeRepository.ANDROID_FRAMEWORK_PACKAGE }
            val thirdParty = scopes.filter {
                it.packageName != RecommendedScopeRepository.SELF_PACKAGE_NAME &&
                    it.packageName != RecommendedScopeRepository.ANDROID_FRAMEWORK_PACKAGE
            }

            assertTrue(selfItem.isRequiredSystemScope)
            assertTrue(selfItem.description.contains("激活状态自检"))
            assertTrue(androidItem.isRequiredSystemScope)
            assertTrue(androidItem.description.contains("系统框架"))
            assertEquals(14, thirdParty.size)
            assertTrue(thirdParty.all { !it.isRequiredSystemScope && it.description.isNotBlank() })

            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            val whitelistRepo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { emptyList() },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )
            val res = RecommendedScopeRepository.addAllInstalledToWhitelist(PackageInstallChecker { true }, whitelistRepo)
            assertEquals(setOf("com.mockloc", "android"), res.skippedSpecialPackages)
            assertEquals(14, res.importedPackages.size)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T2-F16-05 xposed_scope static audit rejects empty duplicate invalid items and passes real arrays xml`() {
        try {
            val emptyXml = """<resources><string-array name="xposed_scope"></string-array></resources>"""
            assertFalse(StaticManifestAndResourceChecker.checkXposedScopeArrayXml(emptyXml).passed)

            val dupXml = """
                <resources>
                  <string-array name="xposed_scope">
                    <item>com.mockloc</item>
                    <item>com.tencent.mm</item>
                    <item>com.tencent.mm</item>
                  </string-array>
                </resources>
            """.trimIndent()
            assertFalse(StaticManifestAndResourceChecker.checkXposedScopeArrayXml(dupXml).passed)

            val blankAndInvalidXml = """
                <resources>
                  <string-array name="xposed_scope">
                    <item>   </item>
                    <item>badpkg</item>
                  </string-array>
                </resources>
            """.trimIndent()
            assertFalse(StaticManifestAndResourceChecker.checkXposedScopeArrayXml(blankAndInvalidXml).passed)

            val realArraysFile = File(WorkspacePathResolver.resDir(), "values/arrays.xml")
            val realReport = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(realArraysFile.readText(Charsets.UTF_8))
            assertTrue("Real arrays.xml failed: ${realReport.violations}", realReport.passed)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    // ==================== F17: Material 3 UI 重构与防崩溃（边界与异常） ====================

    @Test
    fun `T2-F17-01 invalid coordinate texts show field errors and block starting single mock`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val initialLat = configRepo.currentConfig.latitude
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                    .also { trackedControllers.add(it) }
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(
                            configRepository = configRepo,
                            metaPrefs = InMemorySharedPreferences(),
                            packageQuerySource = PackageQuerySource { emptyList() },
                            prepopulateOnFirstInit = false,
                            ioDispatcher = Dispatchers.IO
                        )
                    },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val invalidPairs = listOf(
                    "abc" to "116.4",
                    "" to "116.4",
                    "95.0" to "116.4",
                    "39.9" to "-181.0",
                    "NaN" to "116.4"
                )
                for ((lat, lon) in invalidPairs) {
                    assertNull(vm.applyCoordinateInputs(lat, lon, "50.0", "3.0"))
                    vm.onCoordinateTextChanged(lat, lon, "50.0", "3.0")
                    vm.onStartSingleClicked()

                    val state = vm.uiState.value
                    assertTrue(
                        "Expected latError or lonError for ($lat, $lon)",
                        state.latError != null || state.lonError != null
                    )
                    assertEquals(MockRunStatus.Idle, state.runtimeState.status)
                    assertFalse(configRepo.currentConfig.enabled)
                    assertEquals(initialLat, configRepo.currentConfig.latitude, 1e-6)
                }
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T2-F17-02 invalid custom speed and jitter radius inputs emit feedback without CruiseConfig crash`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                    .also { trackedControllers.add(it) }
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(
                            configRepository = configRepo,
                            metaPrefs = InMemorySharedPreferences(),
                            packageQuerySource = PackageQuerySource { emptyList() },
                            prepopulateOnFirstInit = false,
                            ioDispatcher = Dispatchers.IO
                        )
                    },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val job = launch(Dispatchers.Unconfined) {
                    vm.events.collect { events.add(it) }
                }

                val initialSpeed = vm.uiState.value.customSpeedMps
                vm.updateCustomSpeed(-5.0)
                vm.updateCustomSpeed(0.0)
                vm.updateCustomSpeed(Double.NaN)
                vm.updateCustomSpeed(500.0)

                assertEquals(initialSpeed, vm.uiState.value.customSpeedMps, 1e-6)
                val snackbars = events.filterIsInstance<MainUiEvent.ShowSnackbar>()
                assertEquals(4, snackbars.size)

                assertFalse(vm.updateJitterRadiusInput("abc"))
                assertNotNull(vm.uiState.value.jitterRadiusError)
                assertFalse(vm.updateJitterRadiusInput("-1.0"))
                assertNotNull(vm.uiState.value.jitterRadiusError)

                job.cancel()
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T2-F17-03 starting cruise with zero waypoints emits guidance Snackbar without IllegalStateException`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                    .also { trackedControllers.add(it) }
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(
                            configRepository = configRepo,
                            metaPrefs = InMemorySharedPreferences(),
                            packageQuerySource = PackageQuerySource { emptyList() },
                            prepopulateOnFirstInit = false,
                            ioDispatcher = Dispatchers.IO
                        )
                    },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val job = launch(Dispatchers.Unconfined) {
                    vm.events.collect { events.add(it) }
                }

                // 0 航点契约拦截；注：MainViewModel 与 RouteRepository 允许 >= 1 航点单点驻留巡航
                assertTrue(vm.uiState.value.draftWaypoints.isEmpty())
                vm.onStartCruiseClicked()

                val snackbar = events.filterIsInstance<MainUiEvent.ShowSnackbar>().lastOrNull()
                assertNotNull(snackbar)
                assertTrue(snackbar!!.message.contains("航点"))
                assertEquals(MockRunStatus.Idle, vm.uiState.value.runtimeState.status)
                job.cancel()
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T2-F17-04 unprivileged environment without location permission emits RequestPermissions and stays Idle`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(false, false, false, "缺少定位权限") },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(
                    coordinator,
                    { PermissionCheckResult(false, false, false, "缺少定位权限") },
                    Dispatchers.IO
                ).also { trackedControllers.add(it) }
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(
                            configRepository = configRepo,
                            metaPrefs = InMemorySharedPreferences(),
                            packageQuerySource = PackageQuerySource { emptyList() },
                            prepopulateOnFirstInit = false,
                            ioDispatcher = Dispatchers.IO
                        )
                    },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_UNPRIVILEGED.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(false, false, false, "缺少定位权限") },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val job = launch(Dispatchers.Unconfined) {
                    vm.events.collect { events.add(it) }
                }

                awaitUntil { vm.uiState.value.environmentStatus != null }
                assertFalse(vm.uiState.value.environmentStatus!!.isRootAvailable)
                assertFalse(vm.uiState.value.environmentStatus!!.isLSPosedActive)

                vm.onStartSingleClicked()
                val reqPerm = events.filterIsInstance<MainUiEvent.RequestPermissions>().lastOrNull()
                assertNotNull(reqPerm)
                assertTrue(reqPerm!!.needLocation)
                assertTrue(reqPerm.needNotification)
                assertEquals(MockRunStatus.Idle, vm.uiState.value.runtimeState.status)
                assertFalse(configRepo.currentConfig.enabled)

                vm.onPermissionsResult(locationGranted = false, notificationGranted = false)
                val deniedSnackbar = events.filterIsInstance<MainUiEvent.ShowSnackbar>().lastOrNull()
                assertNotNull(deniedSnackbar)
                assertTrue(deniedSnackbar!!.message.contains("权限"))
                assertEquals(MockRunStatus.Idle, vm.uiState.value.runtimeState.status)
                job.cancel()
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T2-F17-05 rapid 10x start-stop toggles and ViewModel recreation synchronize state cleanly`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val controller = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                    .also { trackedControllers.add(it) }
                val sharedGateway = ControllerEngineGateway(controller)
                val favRepo = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val routeRepo = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val env = TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus()

                val vm1 = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = sharedGateway,
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = favRepo,
                    routeRepository = routeRepo,
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { env },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val modes = listOf(MockMode.AUTO, MockMode.LSPOSED_ONLY, MockMode.ROOT_ONLY)
                repeat(10) { idx ->
                    vm1.setMockMode(modes[idx % modes.size])
                    vm1.onStartSingleClicked()
                    vm1.stopMock()
                }
                awaitUntil {
                    vm1.uiState.value.runtimeState.status == MockRunStatus.Idle &&
                        !configRepo.currentConfig.enabled
                }

                vm1.setMockMode(MockMode.LSPOSED_ONLY)
                vm1.onCoordinateTextChanged("22.5332", "113.9512", "10.0", "3.0")
                vm1.onStartSingleClicked()
                awaitUntil {
                    vm1.uiState.value.runtimeState.status == MockRunStatus.SingleRunning &&
                        vm1.uiState.value.config.mode == MockMode.LSPOSED_ONLY &&
                        configRepo.currentConfig.enabled
                }

                // 模拟配置变更/屏幕旋转销毁重建 ViewModel，共享同一持久化仓库与服务网关
                val vm2 = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = sharedGateway,
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = favRepo,
                    routeRepository = routeRepo,
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { env },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                awaitUntil {
                    vm2.uiState.value.runtimeState.status == MockRunStatus.SingleRunning &&
                        vm2.uiState.value.config.mode == MockMode.LSPOSED_ONLY
                }
                assertEquals(22.5332, vm2.uiState.value.selectedPoint.latitude, 1e-6)
                assertEquals(113.9512, vm2.uiState.value.selectedPoint.longitude, 1e-6)
                assertTrue(vm2.uiState.value.isRunningAny)

                vm2.stopMock()
                awaitUntil { vm2.uiState.value.runtimeState.status == MockRunStatus.Idle }
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }
}
