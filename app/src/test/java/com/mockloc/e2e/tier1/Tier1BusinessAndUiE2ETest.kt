package com.mockloc.e2e.tier1

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.favorite.FavoriteRepository
import com.mockloc.data.route.RouteRepository
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.scope.RecommendedScopeRepository
import com.mockloc.data.whitelist.InstalledAppRecord
import com.mockloc.data.whitelist.PackageQuerySource
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.CruiseEngine
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.geocoding.DomesticGeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.InMemoryPreferencesOpener
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.TestEnvironmentPresets
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toCruiseConfig
import com.mockloc.e2e.factory.toEnvironmentStatus
import com.mockloc.e2e.factory.toGeocodingHttpClient
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toPlannedRoute
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.factory.withMainDispatcher
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.presentation.MainViewModel
import com.mockloc.presentation.WhitelistViewModel
import com.mockloc.presentation.gateway.ControllerEngineGateway
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.presentation.map.OsmMapCoordinateBridge
import com.mockloc.presentation.map.OsmMapController
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.root.EnvironmentDetector
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.NoOpRootLocationDriver
import com.mockloc.service.PermissionCheckResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class Tier1BusinessAndUiE2ETest {

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

    private suspend fun awaitUntil(timeoutMs: Long = 3000L, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(15L)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    private fun mockPmForPackages(
        installed: Set<String>,
        systemApps: Set<String> = emptySet(),
        labels: Map<String, String> = emptyMap()
    ): PackageManager {
        val pm = Mockito.mock(PackageManager::class.java)
        val all = installed.map { pkg ->
            val appInfo = Mockito.mock(ApplicationInfo::class.java)
            appInfo.flags = if (pkg in systemApps) ApplicationInfo.FLAG_SYSTEM else 0
            Mockito.`when`(appInfo.loadLabel(pm)).thenReturn(labels[pkg] ?: pkg)
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = appInfo
            }
        }
        @Suppress("DEPRECATION")
        Mockito.`when`(pm.getInstalledPackages(Mockito.anyInt())).thenReturn(all)
        runCatching {
            Mockito.`when`(pm.getInstalledPackages(Mockito.nullable(PackageManager.PackageInfoFlags::class.java)))
                .thenReturn(all)
        }
        @Suppress("DEPRECATION")
        Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
            val pkg = inv.getArgument<String>(0)
            all.firstOrNull { it.packageName == pkg }
                ?: throw PackageManager.NameNotFoundException(pkg)
        }
        runCatching {
            Mockito.`when`(
                pm.getPackageInfo(
                    Mockito.anyString(),
                    Mockito.nullable(PackageManager.PackageInfoFlags::class.java)
                )
            ).thenAnswer { inv ->
                val pkg = inv.getArgument<String>(0)
                all.firstOrNull { it.packageName == pkg }
                    ?: throw PackageManager.NameNotFoundException(pkg)
            }
        }
        return pm
    }

    private fun parseColorsHex(file: File): Map<String, Int> {
        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }.newDocumentBuilder().parse(InputSource(StringReader(file.readText(Charsets.UTF_8))))
        val nodes = doc.getElementsByTagName("color")
        val map = LinkedHashMap<String, Int>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name").trim()
            val raw = el.textContent?.trim()?.removePrefix("#").orEmpty()
            if (name.isNotEmpty() && (raw.length == 6 || raw.length == 8)) {
                map[name] = if (raw.length == 6) (0xFF000000L or raw.toLong(16)).toInt() else raw.toLong(16).toInt()
            }
        }
        return map
    }

    // ==================== F12: 地图浏览选点与国内直连搜地 ====================

    @Test
    fun `T1-F12-01 domestic geocoding keyword search hits domestic endpoint and normalizes GCJ02 to WGS84`() = runBlocking {
        try {
            val gcj = CoordinateConverter.wgs84ToGcj02(
                TestCoordinates.BEIJING_TIANANMEN.latitude,
                TestCoordinates.BEIJING_TIANANMEN.longitude
            )
            val amapJson = """
                {
                  "status": "1",
                  "data": {
                    "tip_list": [
                      {
                        "tip": {
                          "name": "天安门",
                          "district": "北京市东城区",
                          "address": "东长安街",
                          "cityname": "北京市",
                          "location": "${String.format(Locale.US, "%.6f,%.6f", gcj.longitude, gcj.latitude)}"
                        }
                      }
                    ]
                  }
                }
            """.trimIndent()

            val requester = FakeGeocodingHttpRequester().apply {
                stubSuccess(Regex("""ditu\.amap\.com/service/poiTipslite"""), amapJson)
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            val res = service.search("天安门")
            assertTrue(res.isSuccess)
            val list = res.getOrThrow()
            assertEquals(1, list.size)
            assertEquals(1, requester.requestCount.get())
            assertTrue(requester.requestedUrls.first().startsWith("https://ditu.amap.com/service/poiTipslite"))

            val item = list.first()
            assertEquals("天安门", item.name)
            assertEquals("amap_web", item.source)
            assertTrue(abs(item.latitude - TestCoordinates.BEIJING_TIANANMEN.latitude) < 1e-4)
            assertTrue(abs(item.longitude - TestCoordinates.BEIJING_TIANANMEN.longitude) < 1e-4)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F12-02 direct coordinate input parses comma space chinese comma and hemisphere lon-first without network`() = runBlocking {
        try {
            val requester = FakeGeocodingHttpRequester()
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            val commaRes = service.search("39.9087, 116.3975").getOrThrow()
            val spaceRes = service.search("31.2397 121.4998").getOrThrow()
            val cnCommaRes = service.search("22.5332，114.0545").getOrThrow()
            val lonFirstHemiRes = service.search("114.0545E, 22.5332N").getOrThrow()

            assertEquals("Direct coordinate parsing must not issue HTTP requests", 0, requester.requestCount.get())

            assertEquals(1, commaRes.size)
            assertEquals("direct_wgs84", commaRes.first().source)
            assertEquals(39.9087, commaRes.first().latitude, 1e-6)
            assertEquals(116.3975, commaRes.first().longitude, 1e-6)

            assertEquals(1, spaceRes.size)
            assertEquals("direct_wgs84", spaceRes.first().source)
            assertEquals(31.2397, spaceRes.first().latitude, 1e-6)
            assertEquals(121.4998, spaceRes.first().longitude, 1e-6)

            assertEquals(1, cnCommaRes.size)
            assertEquals(22.5332, cnCommaRes.first().latitude, 1e-6)
            assertEquals(114.0545, cnCommaRes.first().longitude, 1e-6)

            assertEquals(1, lonFirstHemiRes.size)
            assertEquals("direct_wgs84", lonFirstHemiRes.first().source)
            assertEquals(22.5332, lonFirstHemiRes.first().latitude, 1e-6)
            assertEquals(114.0545, lonFirstHemiRes.first().longitude, 1e-6)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F12-03 online timeout falls back smoothly to offline 50plus landmark database`() = runBlocking {
        try {
            assertTrue(
                "Offline database should contain 50+ cities/landmarks",
                OfflineFallbackGeocodingService.PLACES.size >= 50
            )
            val requester = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val service = DomesticGeocodingService(
                httpClient = requester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )

            val pearlRes = service.search("东方明珠")
            val westLakeRes = service.search("West Lake")

            assertTrue(pearlRes.isSuccess)
            assertTrue(westLakeRes.isSuccess)

            val pearlList = pearlRes.getOrThrow()
            val westLakeList = westLakeRes.getOrThrow()
            assertTrue(pearlList.isNotEmpty())
            assertTrue(westLakeList.isNotEmpty())

            assertEquals(OfflineFallbackGeocodingService.SOURCE_FALLBACK, pearlList.first().source)
            assertTrue(pearlList.any { it.name.contains("东方明珠") || it.name.contains("陆家嘴") })
            assertEquals(31.2397, pearlList.first().latitude, 1e-3)
            assertEquals(OfflineFallbackGeocodingService.SOURCE_FALLBACK, westLakeList.first().source)
            assertTrue(westLakeList.any { it.name.contains("西湖") })
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F12-04 reverseGeocode returns online amap regeo and degrades to offline nearest landmark`() = runBlocking {
        try {
            val regeoJson = """
                {
                  "status": "1",
                  "data": {
                    "city": "北京市",
                    "desc": "北京市东城区东长安街天安门广场",
                    "poi_list": [
                      { "name": "天安门广场" }
                    ]
                  }
                }
            """.trimIndent()
            val onlineRequester = FakeGeocodingHttpRequester().apply {
                stubSuccess(Regex("""ditu\.amap\.com/service/regeo"""), regeoJson)
            }
            val onlineService = DomesticGeocodingService(
                httpClient = onlineRequester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )
            val onlineOut = onlineService.reverseGeocode(
                TestCoordinates.BEIJING_TIANANMEN.latitude,
                TestCoordinates.BEIJING_TIANANMEN.longitude
            ).getOrThrow()
            assertNotNull(onlineOut)
            assertEquals("amap_regeo", onlineOut!!.source)
            assertTrue(onlineOut.name.contains("天安门") || onlineOut.address.contains("天安门"))

            val offlineRequester = FakeGeocodingHttpRequester().apply {
                stubTimeout(Regex(".*"))
            }
            val offlineService = DomesticGeocodingService(
                httpClient = offlineRequester.toGeocodingHttpClient(),
                fallbackService = OfflineFallbackGeocodingService(),
                ioDispatcher = Dispatchers.IO
            )
            val offlineOut = offlineService.reverseGeocode(
                TestCoordinates.BEIJING_TIANANMEN.latitude,
                TestCoordinates.BEIJING_TIANANMEN.longitude
            ).getOrThrow()
            assertNotNull(offlineOut)
            assertEquals(OfflineFallbackGeocodingService.SOURCE_NEAREST, offlineOut!!.source)
            assertTrue(offlineOut.name.contains("天安门") || offlineOut.city.contains("北京"))
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F12-05 map tap and long press waypoint sync converts GCJ02 display back to WGS84 domain`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var vm: MainViewModel? = null
            var controller: MockEngineController? = null
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val favRepo = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val routeRepo = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = NoOpRootLocationDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope
                )
                val localController = MockEngineController(coordinator, { PermissionCheckResult(true, true, true) }, Dispatchers.IO)
                controller = localController
                val env = TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus()

                vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(localController),
                    geocodingService = OfflineFallbackGeocodingService(),
                    favoriteRepository = favRepo,
                    routeRepository = routeRepo,
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { env },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { true },
                    ioDispatcher = Dispatchers.IO
                )

                val shanghaiWgs = TestCoordinates.SHANGHAI_LUJIAZUI.toGeoPoint()
                val (gcjLat, gcjLon) = OsmMapCoordinateBridge.toMapDisplayPoint(shanghaiWgs)
                assertTrue(abs(gcjLat - shanghaiWgs.latitude) > 1e-4)

                val tappedWgs = OsmMapCoordinateBridge.toWgs84DomainPoint(gcjLat, gcjLon)
                vm.onMapTapped(tappedWgs)
                vm.appendSelectedPointToRoute()

                val state = vm.uiState.value
                assertTrue(abs(state.selectedPoint.latitude - shanghaiWgs.latitude) < 1e-5)
                assertTrue(abs(state.selectedPoint.longitude - shanghaiWgs.longitude) < 1e-5)
                assertTrue(abs(configRepo.currentConfig.latitude - shanghaiWgs.latitude) < 1e-5)
                assertEquals(1, state.draftWaypoints.size)
                assertTrue(abs(state.draftWaypoints.first().point.latitude - shanghaiWgs.latitude) < 1e-5)
                assertTrue(state.latInput.startsWith("31.239"))
            } finally {
                cleanupUiResources(
                    viewModels = listOfNotNull(vm),
                    controllers = listOfNotNull(controller),
                    scopes = listOf(ioScope)
                )
            }
        }
    }

    // ==================== F13: 常用地点收藏夹完整功能 ====================

    @Test
    fun `T1-F13-01 initial FavoriteRepository returns valid immutable coordinates list`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            val list = repo.getAll()
            assertTrue(list.isNotEmpty())
            for (item in list) {
                assertTrue(item.id.isNotBlank())
                assertTrue(item.name.isNotBlank())
                assertTrue(GeoPoint.isValid(item.latitude, item.longitude))
            }
            val listAgain = repo.getAll()
            assertEquals(list, listAgain)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F13-02 add favorite persists across instances and orders newest at top`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val clock = AtomicLong(1_700_000_000_000L)
            val repo = FavoriteRepository(
                prefs = prefs,
                ioDispatcher = Dispatchers.IO,
                ioScope = ioScope,
                timeProvider = { clock.getAndIncrement() }
            )
            repo.ensureInitialized()

            clock.set(1_800_000_000_000L)
            val addRes = repo.add(
                FavoriteLocation(
                    id = "",
                    name = "公司",
                    address = "深圳南山",
                    latitude = 22.5332,
                    longitude = 114.0545,
                    createdAtMs = 1_800_000_000_000L
                )
            )
            assertTrue(addRes.isSuccess)
            val added = addRes.getOrThrow()

            val all = repo.getAll()
            assertEquals("公司", all.first().name)
            assertEquals("深圳南山", all.first().address)

            val reloadedRepo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            reloadedRepo.ensureInitialized()
            val reloadedAll = reloadedRepo.getAll()
            assertEquals(added.id, reloadedAll.first().id)
            assertEquals(22.5332, reloadedAll.first().latitude, 1e-6)
            assertEquals(114.0545, reloadedAll.first().longitude, 1e-6)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F13-03 update favorite renames and edits address while preserving id and coordinates`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            val added = repo.add("旧家", "旧地址", 39.9200, 116.4100).getOrThrow()

            val updatedRes = repo.update(added.id, "新家地址", "北京市朝阳区")
            assertTrue(updatedRes.isSuccess)
            val updated = updatedRes.getOrThrow()
            assertEquals(added.id, updated.id)
            assertEquals("新家地址", updated.name)
            assertEquals("北京市朝阳区", updated.address)
            assertEquals(39.9200, updated.latitude, 1e-6)
            assertEquals(116.4100, updated.longitude, 1e-6)

            val reloaded = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            reloaded.ensureInitialized()
            val fetched = reloaded.getById(added.id)
            assertNotNull(fetched)
            assertEquals("新家地址", fetched!!.name)
            assertEquals("北京市朝阳区", fetched.address)
            assertEquals(39.9200, fetched.latitude, 1e-6)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F13-04 search favorite filters by chinese english and empty query returns all`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = FavoriteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            assertTrue(repo.clearAll())

            repo.add("北京故宫", "东城区景山前街", 39.9163, 116.3972).getOrThrow()
            repo.add("上海外滩", "黄浦区中山东一路", 31.2397, 121.4998).getOrThrow()
            repo.add("深圳科技园", "南山区 Shenzhen Tech Park", 22.5332, 113.9512).getOrThrow()

            val byCity = repo.search("深圳")
            assertEquals(1, byCity.size)
            assertEquals("深圳科技园", byCity.first().name)

            val byAddr = repo.search("南山")
            assertEquals(1, byAddr.size)

            val byCase = repo.search("tech")
            assertEquals(1, byCase.size)
            assertEquals("深圳科技园", byCase.first().name)

            val all = repo.search("")
            assertEquals(3, all.size)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F13-05 remove favorite deletes entry and useFavoriteAsSingle loads coordinates into ViewModel and config`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val favPrefs = InMemorySharedPreferences()
                val favRepo = FavoriteRepository(favPrefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                favRepo.ensureInitialized()
                favRepo.clearAll()

                val toRemove = favRepo.add("待删点", "临时地址", 30.25, 120.15).getOrThrow()
                val toKeep = favRepo.add("常用地标", "深圳市南山区", 22.5332, 113.9512, altitude = 28.0).getOrThrow()

                assertTrue(favRepo.remove(toRemove.id))
                assertTrue(favRepo.getAll().none { it.id == toRemove.id })

                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val routeRepo = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
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
                    favoriteRepository = favRepo,
                    routeRepository = routeRepo,
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { true },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                vm.useFavoriteAsSingle(toKeep)

                awaitUntil {
                    abs(vm.uiState.value.selectedPoint.latitude - 22.5332) < 1e-6 &&
                        (favRepo.getUsageStats(toKeep.id)?.useCount ?: 0) >= 1
                }
                assertEquals(22.5332, configRepo.currentConfig.latitude, 1e-6)
                assertEquals(113.9512, configRepo.currentConfig.longitude, 1e-6)
                assertEquals(28.0, configRepo.currentConfig.altitude, 1e-6)
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    // ==================== F14: 多航点路线管理与持久化 ====================

    @Test
    fun `T1-F14-01 saveRoute persists multi-waypoint route and CruiseConfig holds LoopMode losslessly`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            val spec = TestRouteSpecs.BEIJING_COMMUTE_3WP.copy(loopMode = com.mockloc.e2e.factory.TestLoopMode.PING_PONG)
            val route = spec.toPlannedRoute()
            val cruiseConfig = spec.toCruiseConfig()
            assertEquals(LoopMode.PING_PONG, cruiseConfig.loopMode)

            val saved = repo.saveRoute(route).getOrThrow()
            assertEquals(3, saved.waypoints.size)

            val reloadedRepo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            reloadedRepo.ensureInitialized()
            val loaded = reloadedRepo.getRouteById(saved.id)
            assertNotNull(loaded)
            assertEquals(route.name, loaded!!.name)
            assertEquals(3, loaded.waypoints.size)
            for (i in route.waypoints.indices) {
                assertEquals(route.waypoints[i].name, loaded.waypoints[i].name)
                assertEquals(route.waypoints[i].point.latitude, loaded.waypoints[i].point.latitude, 1e-6)
                assertEquals(route.waypoints[i].point.longitude, loaded.waypoints[i].point.longitude, 1e-6)
                assertEquals(route.waypoints[i].point.altitude, loaded.waypoints[i].point.altitude, 1e-6)
                assertEquals(route.waypoints[i].targetSpeedMps, loaded.waypoints[i].targetSpeedMps)
                assertEquals(route.waypoints[i].dwellTimeSeconds, loaded.waypoints[i].dwellTimeSeconds, 1e-6)
            }
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F14-02 waypoint add reorder delete preserves order and updates totalDistanceMeters`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            val base = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute()
            val initialDistance = base.totalDistanceMeters()
            assertTrue(initialDistance > 0.0)

            val wp4 = Waypoint(TestCoordinates.SHANGHAI_LUJIAZUI.toGeoPoint(), name = "上海陆家嘴", targetSpeedMps = 6.0f)
            val mutable = base.waypoints.toMutableList()
            mutable.add(wp4)
            // 交换第 2 与第 3 航点 (index 1 & 2)
            val tmp = mutable[1]
            mutable[1] = mutable[2]
            mutable[2] = tmp
            // 删除第 1 航点 (index 0)
            mutable.removeAt(0)

            val modified = base.copy(waypoints = mutable.toList())
            val expectedDist = modified.totalDistanceMeters()
            assertTrue(abs(expectedDist - initialDistance) > 100.0)

            repo.saveRoute(modified).getOrThrow()

            val reloadedRepo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            val loaded = reloadedRepo.getRouteById(base.id)!!
            assertEquals(3, loaded.waypoints.size)
            assertEquals("鸟巢", loaded.waypoints[0].name)
            assertEquals("故宫", loaded.waypoints[1].name)
            assertEquals("上海陆家嘴", loaded.waypoints[2].name)
            assertEquals(expectedDist, loaded.totalDistanceMeters(), 1e-6)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F14-03 saveRoute with duplicate ID updates idempotently without creating duplicate entries`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            val r1 = PlannedRoute(
                id = "route_1",
                name = "初版路线",
                waypoints = listOf(Waypoint(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), "起点"))
            )
            repo.saveRoute(r1).getOrThrow()

            val r1Updated = r1.copy(
                name = "更新版环线",
                waypoints = listOf(
                    Waypoint(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), "起点"),
                    Waypoint(TestCoordinates.BEIJING_FORBIDDEN_CITY.toGeoPoint(), "终点")
                )
            )
            repo.saveRoute(r1Updated).getOrThrow()

            val matching = repo.getAllRoutes().filter { it.id == "route_1" }
            assertEquals(1, matching.size)
            assertEquals("更新版环线", matching.first().name)
            assertEquals(2, matching.first().waypoints.size)

            val loopCfg = CruiseConfig(speedMps = 4.0, loopMode = LoopMode.LOOP)
            assertEquals(LoopMode.LOOP, loopCfg.loopMode)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F14-04 deleteRoute removes target route without affecting other saved routes`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()
            assertTrue(repo.clearAllRoutes())

            val route1 = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute().copy(id = "route_1")
            val route2 = TestRouteSpecs.CLOSED_LOOP_4WP.toPlannedRoute().copy(id = "route_2")
            repo.saveRoute(route1).getOrThrow()
            repo.saveRoute(route2).getOrThrow()

            assertTrue(repo.deleteRoute("route_1"))

            val remaining = repo.getAllRoutes()
            assertEquals(1, remaining.size)
            assertEquals("route_2", remaining.first().id)

            val reloadedRepo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            val reloadedRemaining = reloadedRepo.getAllRoutes()
            assertEquals(1, reloadedRemaining.size)
            assertEquals("route_2", reloadedRemaining.first().id)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F14-05 loaded route drives CruiseEngine and MockEngineCoordinator accurately`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = RouteRepository(prefs, ioDispatcher = Dispatchers.IO, ioScope = ioScope)
            repo.ensureInitialized()

            val spec = TestRouteSpecs.BEIJING_COMMUTE_3WP
            val saved = repo.saveRoute(spec.toPlannedRoute()).getOrThrow()
            val loaded = repo.getRouteById(saved.id)!!

            val cruiseCfg = CruiseConfig(speedMps = 3.5, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
            val engine = CruiseEngine()
            engine.loadRoute(loaded, cruiseCfg)
            engine.start()
            val step = engine.advanceStep(1.0)
            assertEquals(CruiseEngine.State.RUNNING, engine.state)
            assertFalse(step.isCompleted)
            assertEquals(loaded.waypoints.size, step.totalWaypoints)
            val expectedFraction = (step.distanceTraveledMeters / loaded.totalDistanceMeters()).toFloat()
            assertEquals(expectedFraction, step.progressFraction, 1e-4f)

            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            val coordinator = MockEngineCoordinator(
                configRepository = configRepo,
                rootDriver = NoOpRootLocationDriver,
                permissionProvider = { PermissionCheckResult(true, true, true) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = ioScope
            )
            coordinator.startCruise(loaded, cruiseCfg, configRepo.currentConfig)
            val rt = coordinator.runtimeState.value
            assertEquals(MockRunStatus.CruiseRunning, rt.status)
            assertEquals(loaded.waypoints.size, rt.totalWaypoints)
            coordinator.stopMock()
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    // ==================== F15: 目标应用分流白名单管理 ====================

    @Test
    fun `T1-F15-01 getInstalledApps excludeSystem filters unselected system apps self and android framework`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            configRepo.updateTargetPackages(setOf("com.sys.selected"))
            val records = listOf(
                InstalledAppRecord("com.mockloc", "MockLocation", isSystemApp = false),
                InstalledAppRecord("android", "Android System", isSystemApp = true),
                InstalledAppRecord("com.sys.selected", "已选系统定位", isSystemApp = true),
                InstalledAppRecord("com.sys.unselected", "未选系统组件", isSystemApp = true),
                InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
                InstalledAppRecord("com.autonavi.minimap", "高德地图", isSystemApp = false),
                InstalledAppRecord("com.user.demo", "示例应用", isSystemApp = false)
            )
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { records },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )

            val apps = repo.getInstalledApps(includeSystem = false)
            val pkgs = apps.map { it.packageName }.toSet()
            assertFalse(pkgs.contains("com.mockloc"))
            assertFalse(pkgs.contains("android"))
            assertFalse(pkgs.contains("com.sys.unselected"))
            assertTrue(pkgs.contains("com.sys.selected"))
            assertTrue(pkgs.containsAll(listOf("com.tencent.mm", "com.autonavi.minimap", "com.user.demo")))
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F15-02 getInstalledApps sorting and filterApps search by chinese english and package`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            configRepo.updateTargetPackages(setOf("com.user.selected"))
            val records = listOf(
                InstalledAppRecord("com.user.zebra", "Zebra App", isSystemApp = false),
                InstalledAppRecord("com.autonavi.minimap", "Amap 高德地图", isSystemApp = false),
                InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
                InstalledAppRecord("com.user.selected", "已选应用", isSystemApp = false)
            )
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { records },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )

            val all = repo.getInstalledApps(includeSystem = true)
            assertEquals("Selected app must sort first", "com.user.selected", all.first().packageName)
            assertTrue(all[1].isRecommended && all[2].isRecommended)
            assertEquals("Unselected non-recommended sorts last", "com.user.zebra", all.last().packageName)

            assertEquals(1, repo.filterApps(all, "微信").size)
            assertEquals(1, repo.filterApps(all, "tencent").size)
            assertEquals(1, repo.filterApps(all, "Amap").size)
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F15-03 saveSelectedPackages syncs MockConfigRepository IPC bundle and explicit broadcast`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
            val syncedCalls = CopyOnWriteArrayList<Pair<MockConfig, Set<String>>>()
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { emptyList() },
                onPackagesSynced = { cfg, affected -> syncedCalls.add(cfg to affected) },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )

            val targetSet = setOf("com.tencent.mm", "com.autonavi.minimap")
            val saved = repo.saveSelectedPackages(targetSet)
            assertEquals(targetSet, saved)
            assertEquals(targetSet, repo.getSelectedPackages())
            assertTrue(repo.isPackageSelected("com.tencent.mm"))
            assertEquals(targetSet, configRepo.currentConfig.targetPackages)
            assertTrue(syncedCalls.isNotEmpty())

            val fakeBundle = StatefulFakeBundle().asAndroidBundle()
            ConfigSyncSerializer.toBundle(configRepo.currentConfig, fakeBundle)
            val restored = ConfigSyncSerializer.fromBundle(fakeBundle)
            assertEquals(targetSet, restored.targetPackages)

            val mockIntent = Mockito.mock(Intent::class.java)
            Mockito.`when`(mockIntent.setPackage(Mockito.anyString())).thenReturn(mockIntent)
            Mockito.`when`(mockIntent.putExtras(Mockito.any(android.os.Bundle::class.java))).thenReturn(mockIntent)
            MockLocationForegroundService.buildConfigBroadcastIntent(
                config = configRepo.currentConfig,
                targetPackage = "com.tencent.mm",
                intentFactory = { mockIntent },
                bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
            )
            Mockito.verify(mockIntent).setPackage("com.tencent.mm")
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F15-04 strict opt-in whitelist isolation via isPackageSelected and MockConfig isEnabledForPackage`() {
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
            assertTrue(repo.isPackageSelected("com.tencent.mm"))
            assertFalse(repo.isPackageSelected("com.tencent.wework"))
            assertTrue(cfg.isEnabledForPackage("com.tencent.mm"))
            assertFalse(cfg.isEnabledForPackage("com.tencent.wework"))
            assertFalse(cfg.isEnabledForPackage("com.mockloc"))
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F15-05 selectAllFiltered and clearAll persist empty selection across restarts`() = runBlocking {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configPrefs = InMemorySharedPreferences()
            val metaPrefs = InMemorySharedPreferences()
            val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 0L)
            val records = listOf(
                InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
                InstalledAppRecord("com.autonavi.minimap", "高德地图", isSystemApp = false)
            )
            val repo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource { records },
                prepopulateOnFirstInit = true,
                ioDispatcher = Dispatchers.IO
            )

            val apps = repo.getInstalledApps(false)
            val afterSelectAll = repo.selectAllFiltered(apps)
            assertTrue(afterSelectAll.containsAll(setOf("com.tencent.mm", "com.autonavi.minimap")))

            val afterClear = repo.clearAll()
            assertTrue(afterClear.isEmpty())

            val reloadedConfigRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 0L)
            val reloadedWhitelist = WhitelistRepository(
                configRepository = reloadedConfigRepo,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource { records },
                prepopulateOnFirstInit = true,
                ioDispatcher = Dispatchers.IO
            )
            assertTrue(reloadedWhitelist.getSelectedPackages().isEmpty())
        } finally {
            ioScope.cancel()
            resetAllRepositorySingletons()
        }
    }

    // ==================== F16: LSPosed 推荐作用域与引导 ====================

    @Test
    fun `T1-F16-01 RecommendedScopeRepository returns 16 entries covering all 10 core packages`() {
        try {
            val scopes = RecommendedScopeRepository.getRecommendedScopes(PackageInstallChecker { true })
            assertEquals(16, scopes.size)

            val pkgSet = scopes.map { it.packageName }.toSet()
            for (corePkg in StaticManifestAndResourceChecker.CORE_RECOMMENDED_SCOPE_PACKAGES) {
                assertTrue("Missing core recommended package: $corePkg", corePkg in pkgSet)
            }
            for (item in scopes) {
                assertTrue(item.appName.isNotBlank())
                assertTrue(item.category.isNotBlank())
                assertTrue(item.description.isNotBlank())
            }
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F16-02 PackageManager accurately detects installed vs uninstalled recommended scopes`() {
        try {
            val pm = mockPmForPackages(setOf("com.tencent.mm", "com.autonavi.minimap"))
            val scopes = RecommendedScopeRepository.getRecommendedScopes(pm)

            val wechat = scopes.first { it.packageName == "com.tencent.mm" }
            val amap = scopes.first { it.packageName == "com.autonavi.minimap" }
            val baidu = scopes.first { it.packageName == "com.baidu.BaiduMap" }
            val lark = scopes.first { it.packageName == "com.ss.android.lark" }

            assertTrue(wechat.isInstalled)
            assertTrue(amap.isInstalled)
            assertFalse(baidu.isInstalled)
            assertFalse(lark.isInstalled)
            assertEquals(2, scopes.count { it.isInstalled })
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F16-03 ScopeSummary and EnvironmentStatus count installed recommended scopes consistently`() = runBlocking {
        try {
            val installed3 = setOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap")
            val pm = mockPmForPackages(installed3)
            val scopes = RecommendedScopeRepository.getRecommendedScopes(pm)
            val summary = RecommendedScopeRepository.summarizeScopeStats(scopes)

            assertEquals(3, summary.installedTargetCount)
            assertEquals(14, summary.totalTargetCount)
            assertEquals(3, summary.installedAllCount)
            assertEquals(16, summary.totalAllCount)

            val scopeArray = RecommendedScopeRepository.getXposedScopeArrayPackages().toTypedArray()
            val ctx = buildTestAppContext(
                installedPackages = installed3,
                xposedScopeArray = scopeArray
            )
            val shell = FakeShellExecutor().apply {
                stubCommand(Regex(".*"), FakeShellResult(exitCode = 1))
            }.toShellExecutor()
            val envStatus = EnvironmentDetector.detectEnvironment(ctx, shell, sdkInt = 35)
            assertEquals(3, envStatus.recommendedScopeInstalledCount)
            assertEquals(15, envStatus.recommendedScopeTotalCount)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F16-04 arrays xml xposed_scope aligns 1 to 1 with RecommendedScopeRepository`() {
        try {
            val arraysFile = File(WorkspacePathResolver.resDir(), "values/arrays.xml")
            assertTrue("Expected arrays.xml at ${arraysFile.absolutePath}", arraysFile.isFile)
            val doc = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }.newDocumentBuilder().parse(InputSource(StringReader(arraysFile.readText(Charsets.UTF_8))))

            val arrays = doc.getElementsByTagName("string-array")
            var xmlItems: List<String>? = null
            for (i in 0 until arrays.length) {
                val el = arrays.item(i) as? Element ?: continue
                if (el.getAttribute("name") == "xposed_scope") {
                    val items = el.getElementsByTagName("item")
                    xmlItems = (0 until items.length).map { idx -> items.item(idx).textContent.trim() }
                    break
                }
            }

            assertNotNull(xmlItems)
            val repoItems = RecommendedScopeRepository.getXposedScopeArrayPackages()
            assertEquals(15, repoItems.size)
            assertEquals(repoItems, xmlItems)
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F16-05 batch import skips self and android framework while UI state differentiates installed scopes`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val installedSet = setOf("com.mockloc", "android", "com.tencent.mm", "com.sankuai.meituan")
                val checker = PackageInstallChecker { pkg -> pkg in installedSet }

                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val favRepo = FavoriteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
                val routeRepo = RouteRepository(InMemorySharedPreferences(), ioDispatcher = Dispatchers.IO, ioScope = ioScope)
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
                    favoriteRepository = favRepo,
                    routeRepository = routeRepo,
                    whitelistRepositoryProvider = { whitelistRepo },
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_LSPOSED_ONLY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = checker,
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val job = launch(Dispatchers.Unconfined) {
                    vm.events.collect { events.add(it) }
                }

                awaitUntil { vm.uiState.value.recommendedScopes.size == 16 }
                val uiScopes = vm.uiState.value.recommendedScopes
                assertTrue(uiScopes.first { it.packageName == "com.tencent.mm" }.isInstalled)
                assertFalse(uiScopes.first { it.packageName == "com.baidu.BaiduMap" }.isInstalled)
                assertEquals(2, vm.uiState.value.scopeSummary.installedTargetCount)

                vm.importRecommendedInstalledToWhitelist()
                awaitUntil { events.any { it is MainUiEvent.ScopeGuideFeedback } }

                val feedback = events.filterIsInstance<MainUiEvent.ScopeGuideFeedback>().last()
                assertTrue(feedback.success)
                val selected = whitelistRepo.getSelectedPackages()
                assertEquals(setOf("com.tencent.mm", "com.sankuai.meituan"), selected)
                assertFalse(selected.contains("com.mockloc"))
                assertFalse(selected.contains("android"))

                job.cancel()
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    // ==================== F17: Material 3 UI 重构与防崩溃 ====================

    @Test
    fun `T1-F17-01 MainViewModel initial StateFlow emits safely and refreshes EnvironmentStatus asynchronously`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            val trackedControllers = mutableListOf<MockEngineController>()
            try {
                val gate = CompletableDeferred<Unit>()
                val envStarted = CompletableDeferred<Unit>()
                val expectedEnv = TestEnvironmentPresets.ENV_DUAL_READY.toEnvironmentStatus()

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
                    environmentDetector = EnvironmentDetectorGateway {
                        envStarted.complete(Unit)
                        gate.await()
                        expectedEnv
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                envStarted.await()
                assertTrue(vm.uiState.value.environmentStatus == null || vm.uiState.value.isDetectingEnvironment)

                gate.complete(Unit)
                awaitUntil {
                    vm.uiState.value.environmentStatus == expectedEnv &&
                        !vm.uiState.value.isDetectingEnvironment
                }
                assertEquals(expectedEnv, vm.uiState.value.environmentStatus)
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T1-F17-02 MainViewModel mode switch LSPOSED_ONLY and valid coordinates start single mock`() = withMainDispatcher {
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
                val stopRootCalled = AtomicBoolean(false)

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
                    environmentDetector = EnvironmentDetectorGateway { TestEnvironmentPresets.ENV_LSPOSED_ONLY.toEnvironmentStatus() },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = Dispatchers.IO,
                    stopRootProvider = { stopRootCalled.set(true) }
                ).also { trackedVms.add(it) }

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                awaitUntil { vm.uiState.value.config.mode == MockMode.LSPOSED_ONLY && stopRootCalled.get() }

                vm.onCoordinateTextChanged("31.2397", "121.4998", "15.0", "2.5")
                vm.onStartSingleClicked()

                awaitUntil {
                    vm.uiState.value.runtimeState.status == MockRunStatus.SingleRunning &&
                        configRepo.currentConfig.enabled
                }
                assertEquals(MockMode.LSPOSED_ONLY, configRepo.currentConfig.mode)
                assertEquals(31.2397, configRepo.currentConfig.latitude, 1e-6)
                assertEquals(121.4998, configRepo.currentConfig.longitude, 1e-6)
                assertTrue(vm.uiState.value.isRunningAny)

                vm.stopMock()
                awaitUntil { vm.uiState.value.runtimeState.status == MockRunStatus.Idle }
            } finally {
                cleanupUiResources(trackedVms, trackedControllers, listOf(ioScope))
            }
        }
    }

    @Test
    fun `T1-F17-03 WhitelistViewModel loads searches and toggles packages consistently`() = withMainDispatcher {
        runBlocking {
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val trackedVms = mutableListOf<ViewModel>()
            try {
                val configRepo = MockConfigRepository(InMemorySharedPreferences(), ioScope = ioScope, debounceMs = 0L)
                val records = listOf(
                    InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
                    InstalledAppRecord("com.autonavi.minimap", "高德地图", isSystemApp = false),
                    InstalledAppRecord("com.android.settings", "设置", isSystemApp = true)
                )
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { records },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val vm = WhitelistViewModel(
                    whitelistRepositoryProvider = { whitelistRepo },
                    packageInstallChecker = PackageInstallChecker { it in setOf("com.tencent.mm", "com.autonavi.minimap") },
                    ioDispatcher = Dispatchers.IO
                ).also { trackedVms.add(it) }

                awaitUntil { !vm.uiState.value.isLoading }
                assertEquals(3, vm.uiState.value.allApps.size)
                assertEquals(2, vm.uiState.value.filteredApps.size)

                vm.updateSearchQuery("微信")
                assertEquals(1, vm.uiState.value.filteredApps.size)
                assertEquals("com.tencent.mm", vm.uiState.value.filteredApps.first().packageName)

                vm.togglePackage("com.tencent.mm")
                awaitUntil { "com.tencent.mm" in vm.uiState.value.selectedPackages }
                assertEquals(whitelistRepo.getSelectedPackages(), vm.uiState.value.selectedPackages)
                assertTrue(vm.uiState.value.filteredApps.first().isSelected)

                vm.updateSearchQuery("")
                vm.setIncludeSystemApps(true)
                assertEquals(3, vm.uiState.value.filteredApps.size)

                vm.selectAllFiltered()
                awaitUntil { vm.uiState.value.selectedPackages.size == 3 }
                vm.clearFiltered()
                awaitUntil { vm.uiState.value.selectedPackages.isEmpty() }
            } finally {
                cleanupUiResources(trackedVms, emptyList(), listOf(ioScope))
            }
        }
    }

    @Test
    fun `T1-F17-04 Material 3 theme values-night and ViewBinding compliance verified`() {
        try {
            val projectRoot = WorkspacePathResolver.resolveProjectRoot()
            val resDir = WorkspacePathResolver.resDir()
            val resReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resDir)
            assertTrue(
                "Resource & Night compliance failed:\n${resReport.violations.joinToString("\n")}",
                resReport.passed
            )

            val buildGradle = File(projectRoot, "app/build.gradle.kts").readText(Charsets.UTF_8)
            assertTrue(
                "app/build.gradle.kts must enable viewBinding",
                buildGradle.contains("viewBinding = true") || buildGradle.contains("viewBinding=true")
            )

            val presentationDir = File(WorkspacePathResolver.mainJavaDir(), "presentation")
            val bindingTargets = mapOf(
                "MainActivity.kt" to "ActivityMainBinding.inflate",
                "WhitelistActivity.kt" to "ActivityWhitelistBinding.inflate",
                "ScopeGuideDialogFragment.kt" to "DialogScopeGuideBinding.inflate",
                "FavoritesBottomSheet.kt" to "SheetFavoritesBinding.inflate",
                "RouteManagerBottomSheet.kt" to "SheetRouteManagerBinding.inflate"
            )
            for ((fileName, expectedInflate) in bindingTargets) {
                val content = File(presentationDir, fileName).readText(Charsets.UTF_8)
                assertTrue("$fileName must use $expectedInflate", content.contains(expectedInflate))
            }
        } finally {
            resetAllRepositorySingletons()
        }
    }

    @Test
    fun `T1-F17-05 Edge-to-Edge WindowInsets handling and day-night WCAG badge contrast verified`() {
        try {
            val presentationDir = File(WorkspacePathResolver.mainJavaDir(), "presentation")
            val mainActivitySrc = File(presentationDir, "MainActivity.kt").readText(Charsets.UTF_8)
            val whitelistActivitySrc = File(presentationDir, "WhitelistActivity.kt").readText(Charsets.UTF_8)
            val scopeGuideSrc = File(presentationDir, "ScopeGuideDialogFragment.kt").readText(Charsets.UTF_8)
            val favSheetSrc = File(presentationDir, "FavoritesBottomSheet.kt").readText(Charsets.UTF_8)
            val routeSheetSrc = File(presentationDir, "RouteManagerBottomSheet.kt").readText(Charsets.UTF_8)
            val edgeHelperSrc = File(presentationDir, "ui/EdgeToEdgeHelper.kt").readText(Charsets.UTF_8)

            assertTrue(edgeHelperSrc.contains("ViewCompat.setOnApplyWindowInsetsListener"))
            assertTrue(mainActivitySrc.contains("enableEdgeToEdge()"))
            assertTrue(mainActivitySrc.contains("EdgeToEdgeHelper.apply"))
            assertTrue(whitelistActivitySrc.contains("enableEdgeToEdge()"))
            assertTrue(whitelistActivitySrc.contains("EdgeToEdgeHelper.apply"))
            assertTrue(scopeGuideSrc.contains("EdgeToEdgeHelper.apply"))
            assertTrue(favSheetSrc.contains("EdgeToEdgeHelper.apply"))
            assertTrue(routeSheetSrc.contains("EdgeToEdgeHelper.apply"))

            val resDir = WorkspacePathResolver.resDir()
            val dayColors = parseColorsHex(File(resDir, "values/colors.xml"))
            val nightColors = parseColorsHex(File(resDir, "values-night/colors.xml"))
            for (key in listOf("map_marker_start", "map_marker_waypoint", "map_marker_end")) {
                val dayFill = dayColors.getValue(key)
                val nightFill = nightColors.getValue(key)
                assertTrue(
                    OsmMapController.calculateContrastRatio(
                        OsmMapController.selectBadgeTextColor(dayFill),
                        dayFill
                    ) >= 4.5
                )
                assertTrue(
                    OsmMapController.calculateContrastRatio(
                        OsmMapController.selectBadgeTextColor(nightFill),
                        nightFill
                    ) >= 4.5
                )
            }
        } finally {
            resetAllRepositorySingletons()
        }
    }
}
