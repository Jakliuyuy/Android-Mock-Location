package com.mockloc.presentation

import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.favorite.FavoriteRepository
import com.mockloc.data.route.RouteRepository
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.whitelist.InstalledAppRecord
import com.mockloc.data.whitelist.PackageQuerySource
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.geocoding.GeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.presentation.gateway.ControllerEngineGateway
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.MockEngineGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.root.EnvironmentStatus
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.PermissionCheckResult
import com.mockloc.service.RootLocationDriver
import com.mockloc.testing.InMemorySharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeMockEngineGateway : MockEngineGateway {
        val state = MutableStateFlow(MockRuntimeState())
        override val runtimeState: StateFlow<MockRuntimeState> = state

        val callLog = mutableListOf<String>()
        var lastSinglePoint: GeoPoint? = null
        var lastSingleConfig: MockConfig? = null
        var lastCruiseRoute: PlannedRoute? = null
        var lastCruiseConfig: CruiseConfig? = null
        var lastBaseConfig: MockConfig? = null
        var lastSpeedUpdated: Double? = null
        var lastJitterUpdated: Boolean? = null
        var released = false

        override fun startSingleMock(point: GeoPoint, config: MockConfig) {
            callLog.add("startSingleMock:${config.mode}")
            lastSinglePoint = point
            lastSingleConfig = config
            state.value = MockRuntimeState(
                status = MockRunStatus.SingleRunning,
                activeMode = config.mode,
                currentPoint = point
            )
        }

        override fun updateSingleTarget(point: GeoPoint) {
            callLog.add("updateSingleTarget")
            lastSinglePoint = point
            state.value = state.value.copy(currentPoint = point)
        }

        override fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) {
            callLog.add("startCruise:${baseConfig.mode}")
            lastCruiseRoute = route
            lastCruiseConfig = cruiseConfig
            lastBaseConfig = baseConfig
            state.value = MockRuntimeState(
                status = MockRunStatus.CruiseRunning,
                activeMode = baseConfig.mode,
                currentPoint = route.waypoints.firstOrNull()?.point,
                speedMps = cruiseConfig.speedMps,
                progressFraction = 0f,
                activeWaypointIndex = 0,
                totalWaypoints = route.waypoints.size
            )
        }

        override fun pauseCruise() {
            callLog.add("pauseCruise")
            state.value = state.value.copy(status = MockRunStatus.CruisePaused)
        }

        override fun resumeCruise() {
            callLog.add("resumeCruise")
            state.value = state.value.copy(status = MockRunStatus.CruiseRunning)
        }

        override fun stopMock() {
            callLog.add("stopMock")
            state.value = state.value.copy(
                status = MockRunStatus.Idle,
                speedMps = 0.0,
                progressFraction = 0f
            )
        }

        override fun updateSpeed(speedMps: Double) {
            callLog.add("updateSpeed:$speedMps")
            lastSpeedUpdated = speedMps
            state.value = state.value.copy(speedMps = speedMps)
        }

        override fun updateJitter(enabled: Boolean) {
            callLog.add("updateJitter:$enabled")
            lastJitterUpdated = enabled
        }

        override fun release() {
            released = true
        }
    }

    private class FakeGeocodingService : GeocodingService {
        var nextResults: Result<List<GeocodingResult>> = Result.success(emptyList())
        var nextReverse: Result<GeocodingResult?> = Result.success(null)

        override suspend fun search(query: String): Result<List<GeocodingResult>> = nextResults

        override suspend fun reverseGeocode(lat: Double, lon: Double): Result<GeocodingResult?> = nextReverse
    }

    private data class TestHarness(
        val vm: MainViewModel,
        val configRepo: MockConfigRepository,
        val engine: FakeMockEngineGateway,
        val geocoding: FakeGeocodingService,
        val favoriteRepo: FavoriteRepository,
        val routeRepo: RouteRepository,
        val whitelistRepo: WhitelistRepository,
        val broadcastedConfigs: MutableList<MockConfig>,
        var permissionResult: PermissionCheckResult
    )

    private fun createHarness(
        hasLocation: Boolean = true,
        hasNotification: Boolean = true
    ): TestHarness {
        val configRepo = MockConfigRepository(
            prefs = InMemorySharedPreferences(),
            isWorldReadable = false,
            ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        val engine = FakeMockEngineGateway()
        val geocoding = FakeGeocodingService()
        val favoriteRepo = FavoriteRepository(
            prefs = InMemorySharedPreferences(),
            ioDispatcher = testDispatcher,
            ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        val routeRepo = RouteRepository(
            prefs = InMemorySharedPreferences(),
            ioDispatcher = testDispatcher,
            ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        val whitelistRepo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = InMemorySharedPreferences(),
            packageQuerySource = PackageQuerySource {
                listOf(InstalledAppRecord("com.autonavi.minimap", "高德地图", false))
            },
            prepopulateOnFirstInit = false,
            ioDispatcher = testDispatcher
        )
        val env = EnvironmentStatus(
            isLSPosedActive = true,
            xposedApiVersion = 93,
            isRootAvailable = true,
            rootProviderName = "Magisk",
            hasLocationPermission = hasLocation,
            hasNotificationPermission = hasNotification,
            recommendedScopeInstalledCount = 1,
            recommendedScopeTotalCount = 15
        )
        val broadcasted = mutableListOf<MockConfig>()
        val holder = object {
            var perm = PermissionCheckResult(
                hasLocationPermission = hasLocation,
                hasNotificationPermission = hasNotification,
                canStartForegroundLocation = hasLocation
            )
        }
        val vm = MainViewModel(
            configRepository = configRepo,
            engineGateway = engine,
            geocodingService = geocoding,
            favoriteRepository = favoriteRepo,
            routeRepository = routeRepo,
            whitelistRepositoryProvider = { whitelistRepo },
            environmentDetector = EnvironmentDetectorGateway { env },
            permissionProvider = PermissionStatusProvider { holder.perm },
            packageInstallChecker = PackageInstallChecker { pkg ->
                pkg in setOf("com.autonavi.minimap", "com.mockloc", "android")
            },
            onConfigChangedBroadcast = { cfg -> broadcasted.add(cfg) },
            ioDispatcher = testDispatcher,
            searchDebounceMs = 0L
        )
        return TestHarness(
            vm = vm,
            configRepo = configRepo,
            engine = engine,
            geocoding = geocoding,
            favoriteRepo = favoriteRepo,
            routeRepo = routeRepo,
            whitelistRepo = whitelistRepo,
            broadcastedConfigs = broadcasted,
            permissionResult = holder.perm
        )
    }

    @Test
    fun `coordinate validation rejects invalid inputs safely without throwing`() = runTest(testDispatcher) {
        val h = createHarness()
        advanceUntilIdle()

        assertNull(h.vm.applyCoordinateInputs("", "116.39", "50", "3"))
        assertNotNull(h.vm.uiState.value.latError)

        assertNull(h.vm.applyCoordinateInputs("abc", "116.39", "50", "3"))
        assertNotNull(h.vm.uiState.value.latError)

        assertNull(h.vm.applyCoordinateInputs("NaN", "116.39", "50", "3"))
        assertNotNull(h.vm.uiState.value.latError)

        assertNull(h.vm.applyCoordinateInputs("90.001", "116.39", "50", "3"))
        assertNotNull(h.vm.uiState.value.latError)

        assertNull(h.vm.applyCoordinateInputs("39.9", "180.001", "50", "3"))
        assertNotNull(h.vm.uiState.value.lonError)

        assertNull(h.vm.applyCoordinateInputs("39.9", "116.3", "50", "0"))
        assertNotNull(h.vm.uiState.value.accuracyError)

        assertFalse(h.vm.updateJitterRadiusInput("-2"))
        assertNotNull(h.vm.uiState.value.jitterRadiusError)

        // 合法 GCJ-02 输入转回权威 WGS-84
        h.vm.setInputCoordinateType(CoordinateType.GCJ02)
        val gcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)
        val applied = h.vm.applyCoordinateInputs(
            gcj.latitude.toString(),
            gcj.longitude.toString(),
            "60",
            "4"
        )
        assertNotNull(applied)
        assertEquals(39.9087, applied!!.latitude, 1e-4)
        assertEquals(116.3975, applied.longitude, 1e-4)
        assertNull(h.vm.uiState.value.latError)
    }

    @Test
    fun `permission check blocks start when location missing and resumes after grant`() = runTest(testDispatcher) {
        var locationGranted = false
        val configRepo = MockConfigRepository(
            prefs = InMemorySharedPreferences(),
            isWorldReadable = false,
            ioScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        )
        val engine = FakeMockEngineGateway()
        val vm = MainViewModel(
            configRepository = configRepo,
            engineGateway = engine,
            geocodingService = FakeGeocodingService(),
            favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), testDispatcher, kotlinx.coroutines.CoroutineScope(testDispatcher)),
            routeRepository = RouteRepository(InMemorySharedPreferences(), testDispatcher, kotlinx.coroutines.CoroutineScope(testDispatcher)),
            whitelistRepositoryProvider = {
                WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = testDispatcher)
            },
            environmentDetector = EnvironmentDetectorGateway {
                EnvironmentStatus(true, 93, false, "None", locationGranted, false, 0, 15)
            },
            permissionProvider = PermissionStatusProvider {
                PermissionCheckResult(locationGranted, false, locationGranted)
            },
            packageInstallChecker = PackageInstallChecker { false },
            ioDispatcher = testDispatcher,
            searchDebounceMs = 0L
        )
        val collectedEvents = mutableListOf<MainUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { collectedEvents.add(it) }
        }
        advanceUntilIdle()

        vm.onStartSingleClicked()
        assertTrue(collectedEvents.any { it is MainUiEvent.RequestPermissions && it.needLocation })
        assertTrue(engine.callLog.isEmpty())

        // 用户授予位置权限（即使拒绝通知权限也允许启动前台服务）
        locationGranted = true
        vm.onPermissionsResult(locationGranted = true, notificationGranted = false)
        advanceUntilIdle()

        assertTrue(engine.state.value.status is MockRunStatus.SingleRunning)
        assertTrue(collectedEvents.any { it is MainUiEvent.ShowSnackbar && it.message.contains("通知权限") })
        job.cancel()
    }

    @Test
    fun `switching MockMode while running stops old provider and restarts with new mode`() = runTest(testDispatcher) {
        val h = createHarness()
        advanceUntilIdle()

        h.vm.onStartSingleClicked()
        assertEquals(MockMode.AUTO, h.engine.state.value.activeMode)

        h.engine.callLog.clear()
        h.vm.setMockMode(MockMode.LSPOSED_ONLY)
        advanceUntilIdle()

        assertEquals(listOf("stopMock", "startSingleMock:LSPOSED_ONLY"), h.engine.callLog)
        assertEquals(MockMode.LSPOSED_ONLY, h.engine.state.value.activeMode)
        assertTrue(h.broadcastedConfigs.last().mode == MockMode.LSPOSED_ONLY)
    }

    @Test
    fun `anti detect toggles and jitter radius trigger immediate config broadcast`() = runTest(testDispatcher) {
        val h = createHarness()
        advanceUntilIdle()
        h.broadcastedConfigs.clear()

        h.vm.setAntiDetectCellEnabled(false)
        h.vm.setAntiDetectWifiEnabled(false)
        h.vm.setAntiDetectGnssEnabled(false)
        h.vm.setSingleJitterEnabled(false)
        assertTrue(h.vm.updateJitterRadiusInput("5.5"))

        val latest = h.configRepo.currentConfig
        assertFalse(latest.antiDetectCellEnabled)
        assertFalse(latest.antiDetectWifiEnabled)
        assertFalse(latest.antiDetectGnssEnabled)
        assertFalse(latest.jitterEnabled)
        assertEquals(5.5, latest.jitterRadiusMeters, 1e-6)
        assertTrue(h.broadcastedConfigs.size >= 5)
        assertEquals(latest, h.broadcastedConfigs.last())
    }

    @Test
    fun `map tap updates running single target and search handles offline fallback`() = runTest(testDispatcher) {
        val h = createHarness()
        advanceUntilIdle()

        h.vm.onStartSingleClicked()
        val nextPoint = GeoPoint(31.2397, 121.4998)
        h.geocoding.nextReverse = Result.success(
            GeocodingResult(
                name = "东方明珠",
                address = "浦东新区",
                latitude = 31.2397,
                longitude = 121.4998,
                source = "amap_regeo"
            )
        )
        h.vm.onMapTapped(nextPoint)
        advanceUntilIdle()

        assertEquals(31.2397, h.engine.lastSinglePoint!!.latitude, 1e-6)
        assertTrue(h.vm.uiState.value.selectedAddressLabel.contains("东方明珠"))

        // 测试离线兜底标志识别
        h.geocoding.nextResults = Result.success(
            listOf(
                GeocodingResult(
                    name = "杭州西湖",
                    address = "内置地标库",
                    latitude = 30.25,
                    longitude = 120.15,
                    source = OfflineFallbackGeocodingService.SOURCE_FALLBACK
                )
            )
        )
        h.vm.onSearchQueryChanged("西湖")
        advanceUntilIdle()

        assertTrue(h.vm.uiState.value.isSearchOfflineFallback)
        assertEquals(1, h.vm.uiState.value.searchResults.size)
    }

    @Test
    fun `cruise speed presets ONE_WAY completion detection and restart work end to end`() =
        runTest(testDispatcher) {
            val h = createHarness()
            advanceUntilIdle()

            // 空航点拦截
            h.vm.onStartCruiseClicked()
            assertTrue(h.engine.callLog.isEmpty())

            // 添加航点并调序
            h.vm.appendWaypoint(GeoPoint(39.90, 116.39), "起点")
            h.vm.appendWaypoint(GeoPoint(39.91, 116.40), "终点")
            h.vm.moveWaypointDown(0)
            assertEquals("终点", h.vm.uiState.value.draftWaypoints[0].name)
            h.vm.moveWaypointUp(1)
            assertEquals("起点", h.vm.uiState.value.draftWaypoints[0].name)

            // 验证精确速度预设值
            h.vm.selectSpeedPreset(SpeedPreset.BIKE)
            assertEquals(4.2, h.vm.uiState.value.effectiveCruiseSpeedMps, 1e-6)
            h.vm.selectSpeedPreset(SpeedPreset.DRIVE)
            assertEquals(16.7, h.vm.uiState.value.effectiveCruiseSpeedMps, 1e-6)
            h.vm.selectSpeedPreset(SpeedPreset.WALK)
            assertEquals(1.4, h.vm.uiState.value.effectiveCruiseSpeedMps, 1e-6)

            h.vm.onStartCruiseClicked()
            assertEquals(MockRunStatus.CruiseRunning, h.engine.state.value.status)

            // 模拟巡航中改速度
            h.vm.selectSpeedPreset(SpeedPreset.BIKE)
            assertEquals(4.2, h.engine.lastSpeedUpdated!!, 1e-6)

            // 模拟 ONE_WAY 到达终点后 coordinator 输出 Idle + progressFraction = 1.0f
            h.engine.state.value = MockRuntimeState(
                status = MockRunStatus.Idle,
                activeMode = MockMode.AUTO,
                currentPoint = GeoPoint(39.91, 116.40),
                speedMps = 0.0,
                progressFraction = 1.0f,
                activeWaypointIndex = 1,
                totalWaypoints = 2
            )
            advanceUntilIdle()

            assertTrue(h.vm.uiState.value.isOneWayCompleted)

            // 一键重跑
            h.vm.restartCruiseFromCompleted()
            assertEquals(MockRunStatus.CruiseRunning, h.engine.state.value.status)
        }

    @Test
    fun `favorites update conflict surfaces error and recommended import skips special packages`() =
        runTest(testDispatcher) {
            val h = createHarness()
            val events = mutableListOf<MainUiEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                h.vm.events.collect { events.add(it) }
            }
            advanceUntilIdle()

            val firstFav = h.vm.uiState.value.favorites.first()
            val secondFav = h.vm.uiState.value.favorites[1]

            // 将第二条记录坐标改为与第一条完全冲突，应通过 FavoriteFeedback 透传冲突报错
            h.vm.updateFavorite(secondFav.id, secondFav.name, "冲突地址", firstFav.toGeoPoint())
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && !it.success })

            // 空白名称更新同样拦截报错
            events.clear()
            h.vm.updateFavorite(secondFav.id, "   ", null)
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && !it.success })

            // 正常重命名编辑并校验结果
            h.vm.updateFavorite(secondFav.id, "自定义重命名", "新地址")
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && it.success })
            assertEquals("自定义重命名", h.vm.uiState.value.favorites.first { it.id == secondFav.id }.name)

            // 一键导入推荐应用与复制包名发出独立 ScopeGuideFeedback
            events.clear()
            h.vm.importRecommendedInstalledToWhitelist()
            advanceUntilIdle()
            assertEquals(setOf("com.autonavi.minimap"), h.whitelistRepo.getSelectedPackages())
            assertTrue(events.any { it is MainUiEvent.ScopeGuideFeedback && it.success })

            events.clear()
            h.vm.copyPackageName("com.autonavi.minimap")
            assertTrue(events.any { it is MainUiEvent.CopyToClipboard && it.text == "com.autonavi.minimap" })
            assertTrue(events.any { it is MainUiEvent.ScopeGuideFeedback && it.success })
            job.cancel()
        }

    private class SpyRootLocationDriver : RootLocationDriver {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val isActive = AtomicBoolean(false)

        override suspend fun startOrUpdate(
            latitude: Double,
            longitude: Double,
            altitude: Double,
            accuracy: Float,
            bearing: Float,
            speed: Float
        ): Result<Unit> {
            startCalls.incrementAndGet()
            isActive.set(true)
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCalls.incrementAndGet()
            isActive.set(false)
            return Result.success(Unit)
        }
    }

    private class CoordinatorBackedEngineGateway(
        private val coordinator: MockEngineCoordinator
    ) : MockEngineGateway {
        override val runtimeState: StateFlow<MockRuntimeState> get() = coordinator.runtimeState
        override fun startSingleMock(point: GeoPoint, config: MockConfig) =
            coordinator.startSingleMock(point, config)
        override fun updateSingleTarget(point: GeoPoint) =
            coordinator.updateSingleTarget(point)
        override fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) =
            coordinator.startCruise(route, cruiseConfig, baseConfig)
        override fun pauseCruise() = coordinator.pauseCruise()
        override fun resumeCruise() = coordinator.resumeCruise()
        override fun stopMock() = coordinator.stopMock()
        override fun updateSpeed(speedMps: Double) = coordinator.updateSpeed(speedMps)
        override fun updateJitter(enabled: Boolean) = coordinator.updateJitter(enabled)
        override fun release() = Unit
    }

    @Test
    fun `real MockEngineCoordinator stops SpyRootLocationDriver when switching AUTO to LSPOSED_ONLY`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                // 1) AUTO 下启动单点并切往 LSPOSED_ONLY
                vm.onStartSingleClicked()
                val deadline1 = System.currentTimeMillis() + 2000L
                while (spyDriver.startCalls.get() == 0 && System.currentTimeMillis() < deadline1) {
                    kotlinx.coroutines.delay(10)
                }
                assertTrue("Expected root startCalls > 0", spyDriver.startCalls.get() > 0)
                assertTrue("Expected root driver active before switch", spyDriver.isActive.get())

                val stopsBeforeSingleSwitch = spyDriver.stopCalls.get()
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                val deadline2 = System.currentTimeMillis() + 2000L
                while ((vm.uiState.value.config.mode != MockMode.LSPOSED_ONLY ||
                        coordinator.runtimeState.value.activeMode != MockMode.LSPOSED_ONLY ||
                        coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < deadline2
                ) {
                    kotlinx.coroutines.delay(10)
                }
                kotlinx.coroutines.delay(60)
                assertTrue("Expected spyDriver.stopCalls increased", spyDriver.stopCalls.get() > stopsBeforeSingleSwitch)
                assertFalse("Expected root driver inactive in LSPOSED_ONLY", spyDriver.isActive.get())
                assertEquals(MockMode.LSPOSED_ONLY, coordinator.runtimeState.value.activeMode)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                // 2) 切回 AUTO 并启动多航点巡航，再切往 LSPOSED_ONLY
                vm.stopMock()
                kotlinx.coroutines.delay(40)
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(40)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                val startsBeforeCruise = spyDriver.startCalls.get()
                vm.onStartCruiseClicked()
                val deadline3 = System.currentTimeMillis() + 2000L
                while (spyDriver.startCalls.get() <= startsBeforeCruise && System.currentTimeMillis() < deadline3) {
                    kotlinx.coroutines.delay(10)
                }
                assertTrue(spyDriver.isActive.get())

                val stopsBeforeCruiseSwitch = spyDriver.stopCalls.get()
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                val deadline4 = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.activeMode != MockMode.LSPOSED_ONLY ||
                        coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < deadline4
                ) {
                    kotlinx.coroutines.delay(10)
                }
                kotlinx.coroutines.delay(60)
                assertTrue("Expected spyDriver.stopCalls increased during cruise switch", spyDriver.stopCalls.get() > stopsBeforeCruiseSwitch)
                assertFalse("Expected root driver inactive after cruise switch to LSPOSED_ONLY", spyDriver.isActive.get())
                assertEquals(MockMode.LSPOSED_ONLY, coordinator.runtimeState.value.activeMode)

                // 3) CruisePaused 切换模式保持 Idle 不擅自恢复为 CruiseRunning
                vm.pauseCruise()
                kotlinx.coroutines.delay(30)
                assertEquals(MockRunStatus.CruisePaused, coordinator.runtimeState.value.status)
                vm.setMockMode(MockMode.ROOT_ONLY)
                kotlinx.coroutines.delay(120)
                assertEquals(MockRunStatus.Idle, coordinator.runtimeState.value.status)
                assertEquals(MockMode.ROOT_ONLY, vm.uiState.value.config.mode)

                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineCoordinator single jitter preserves selectedPoint anchor`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                configRepo.updateImmediate {
                    it.copy(
                        latitude = 39.9087,
                        longitude = 116.3975,
                        jitterEnabled = true,
                        jitterRadiusMeters = 50.0
                    )
                }
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = SpyRootLocationDriver(),
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L
                )

                val initialAnchor = vm.uiState.value.selectedPoint
                vm.onStartSingleClicked()
                kotlinx.coroutines.delay(220)
                vm.stopMock()
                kotlinx.coroutines.delay(50)

                assertEquals(initialAnchor.latitude, vm.uiState.value.selectedPoint.latitude, 0.0)
                assertEquals(initialAnchor.longitude, vm.uiState.value.selectedPoint.longitude, 0.0)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `missing location permission does not persist config and joint permission skips duplicate prompt`() =
        runTest(testDispatcher) {
            var locationGranted = false
            val configRepo = MockConfigRepository(
                prefs = InMemorySharedPreferences(),
                isWorldReadable = false,
                ioScope = CoroutineScope(testDispatcher)
            )
            val engine = FakeMockEngineGateway()
            val broadcasted = mutableListOf<MockConfig>()
            val vm = MainViewModel(
                configRepository = configRepo,
                engineGateway = engine,
                geocodingService = FakeGeocodingService(),
                favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), testDispatcher, CoroutineScope(testDispatcher)),
                routeRepository = RouteRepository(InMemorySharedPreferences(), testDispatcher, CoroutineScope(testDispatcher)),
                whitelistRepositoryProvider = {
                    WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = testDispatcher)
                },
                environmentDetector = EnvironmentDetectorGateway {
                    EnvironmentStatus(true, 93, false, "None", locationGranted, false, 0, 15)
                },
                permissionProvider = PermissionStatusProvider {
                    PermissionCheckResult(locationGranted, false, locationGranted)
                },
                packageInstallChecker = PackageInstallChecker { false },
                onConfigChangedBroadcast = { broadcasted.add(it) },
                ioDispatcher = testDispatcher,
                searchDebounceMs = 0L
            )
            val events = mutableListOf<MainUiEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.events.collect { events.add(it) }
            }
            advanceUntilIdle()
            broadcasted.clear()

            // 输入新坐标，但在缺定位权限时点击启动：不得提前写入 config 或发广播
            vm.onCoordinateTextChanged(latText = "31.2397", lonText = "121.4998")
            vm.onStartSingleClicked()
            assertTrue(events.any { it is MainUiEvent.RequestPermissions && it.needLocation && it.needNotification })
            assertTrue("Should not broadcast config before permission check passes", broadcasted.isEmpty())
            assertEquals(MockConfig.DEFAULT.latitude, configRepo.currentConfig.latitude, 1e-6)

            // 授予位置权限但拒绝通知权限，启动成功；随后停止再次点击启动不得重复弹窗要通知权限
            locationGranted = true
            vm.onPermissionsResult(locationGranted = true, notificationGranted = false)
            advanceUntilIdle()
            assertEquals(MockRunStatus.SingleRunning, engine.state.value.status)

            vm.stopMock()
            events.clear()
            vm.onStartSingleClicked()
            assertFalse(
                "Should not ask notification permission repeatedly after joint prompt",
                events.any { it is MainUiEvent.RequestPermissions }
            )
            assertEquals(MockRunStatus.SingleRunning, engine.state.value.status)
            job.cancel()
        }

    @Test
    fun `jitter toggles stay synchronized and cruise jitter radius change emits restart hint`() =
        runTest(testDispatcher) {
            val h = createHarness()
            val events = mutableListOf<MainUiEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                h.vm.events.collect { events.add(it) }
            }
            advanceUntilIdle()

            h.vm.setSingleJitterEnabled(false)
            assertFalse(h.vm.uiState.value.enableMicroDrift)
            assertFalse(h.configRepo.currentConfig.jitterEnabled)

            h.vm.setMicroDriftEnabled(true)
            assertTrue(h.vm.uiState.value.enableMicroDrift)
            assertTrue(h.configRepo.currentConfig.jitterEnabled)

            h.vm.appendWaypoint(GeoPoint(39.90, 116.39), "A")
            h.vm.onStartCruiseClicked()
            events.clear()
            assertTrue(h.vm.updateJitterRadiusInput("18.5"))
            assertTrue(events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("下次启动") })
            job.cancel()
        }

    @Test
    fun `route persistence full flow handles OOB indices and pin drag`() =
        runTest(testDispatcher) {
            val h = createHarness()
            val events = mutableListOf<MainUiEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                h.vm.events.collect { events.add(it) }
            }
            advanceUntilIdle()

            h.vm.onPinDragged(GeoPoint(30.25, 120.15))
            assertEquals(30.25, h.vm.uiState.value.selectedPoint.latitude, 1e-6)

            // 空白路线名称/空航点拦截
            h.vm.saveDraftRoute("   ")
            assertTrue(events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("路线名称") })
            h.vm.saveDraftRoute("空航点路线")
            assertTrue(events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("航点为空") })

            // 添加选点到路线并执行越界保护
            h.vm.appendSelectedPointToRoute()
            assertEquals(1, h.vm.uiState.value.draftWaypoints.size)
            h.vm.moveWaypointUp(0)
            h.vm.moveWaypointDown(0)
            h.vm.removeWaypointAt(-1)
            h.vm.removeWaypointAt(99)
            assertEquals(1, h.vm.uiState.value.draftWaypoints.size)

            // 保存、加载、重命名、删除正在加载路线、恢复示例路线
            h.vm.saveDraftRoute("杭州环线")
            advanceUntilIdle()
            val saved = h.vm.uiState.value.savedRoutes.first { it.name == "杭州环线" }
            assertEquals(saved.id, h.vm.uiState.value.draftRouteId)

            events.clear()
            h.vm.renameSavedRoute(saved.id, "西湖环线")
            advanceUntilIdle()
            assertEquals("西湖环线", h.vm.uiState.value.draftRouteName)
            assertTrue(events.any { it is MainUiEvent.RouteFeedback && it.success })

            events.clear()
            h.vm.deleteSavedRoute(saved.id)
            advanceUntilIdle()
            assertNull(h.vm.uiState.value.draftRouteId)
            assertTrue(events.any { it is MainUiEvent.RouteFeedback && it.success })

            h.vm.resetSampleRoutes()
            advanceUntilIdle()
            assertTrue(h.vm.uiState.value.savedRoutes.isNotEmpty())
            val sample = h.vm.uiState.value.savedRoutes.first()
            h.vm.loadRouteToDraft(sample)
            assertEquals(sample.id, h.vm.uiState.value.draftRouteId)
            assertEquals(sample.waypoints.size, h.vm.uiState.value.draftWaypoints.size)

            h.vm.clearDraftWaypoints()
            assertTrue(h.vm.uiState.value.draftWaypoints.isEmpty())
            assertNull(h.vm.uiState.value.draftRouteId)
            job.cancel()
        }

    @Test
    fun `favorites CRUD full flow and BD09 coordinate conversion work accurately`() =
        runTest(testDispatcher) {
            val h = createHarness()
            val events = mutableListOf<MainUiEvent>()
            val job = launch(UnconfinedTestDispatcher(testScheduler)) {
                h.vm.events.collect { events.add(it) }
            }
            advanceUntilIdle()

            // BD-09 双向转换校验
            h.vm.setInputCoordinateType(CoordinateType.BD09)
            val bd = CoordinateConverter.wgs84ToBd09(39.9087, 116.3975)
            val convertedBack = h.vm.applyCoordinateInputs(bd.latitude.toString(), bd.longitude.toString(), "50", "5")
            assertNotNull(convertedBack)
            assertEquals(39.9087, convertedBack!!.latitude, 1e-4)
            assertEquals(116.3975, convertedBack.longitude, 1e-4)

            // 首页快捷收藏发 ShowSnackbar，弹窗收藏发 FavoriteFeedback
            events.clear()
            h.vm.addFavorite("首页收藏点", "天安门附近", GeoPoint(39.9100, 116.4000), fromMainScreen = true)
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("已收藏") })

            events.clear()
            h.vm.addFavorite("弹窗收藏点", "西城", GeoPoint(39.9200, 116.3800), fromMainScreen = false)
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && it.success })

            val added = h.vm.uiState.value.favorites.first { it.name == "弹窗收藏点" }
            h.vm.useFavoriteAsSingle(added)
            advanceUntilIdle()
            assertEquals(added.latitude, h.vm.uiState.value.selectedPoint.latitude, 1e-6)

            h.vm.appendFavoriteToRoute(added)
            advanceUntilIdle()
            assertTrue(h.vm.uiState.value.draftWaypoints.any { it.name == "弹窗收藏点" })

            events.clear()
            h.vm.removeFavorite(added.id)
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && it.success })

            events.clear()
            h.vm.resetDefaultFavorites()
            advanceUntilIdle()
            assertTrue(events.any { it is MainUiEvent.FavoriteFeedback && it.success })
            job.cancel()
        }

    @Test
    fun `real MockEngineCoordinator stopMock during setMockMode suspend window prevents ghost resurrection`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                // 1) SingleRunning 挂起窗口内触发 stopMock()：绝不幽灵重启
                vm.onStartSingleClicked()
                val singleDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning &&
                    System.currentTimeMillis() < singleDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(12)
                vm.stopMock()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected Idle after stopMock during SingleRunning mode switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertFalse(
                    "Expected config.enabled == false after stopMock",
                    configRepo.currentConfig.enabled
                )
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)

                // 2) CruiseRunning 挂起窗口内触发 stopMock()：绝不幽灵重启
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(30)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                vm.onStartCruiseClicked()
                val cruiseDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning &&
                    System.currentTimeMillis() < cruiseDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(12)
                vm.stopMock()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected Idle after stopMock during CruiseRunning mode switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertFalse(
                    "Expected config.enabled == false after cruise stopMock",
                    configRepo.currentConfig.enabled
                )
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineCoordinator map tap during setMockMode suspend window uses latest target without stale rollback`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                configRepo.updateImmediate {
                    it.copy(
                        latitude = 39.9087,
                        longitude = 116.3975,
                        jitterEnabled = false
                    )
                }
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                vm.onStartSingleClicked()
                val startDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning &&
                    System.currentTimeMillis() < startDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                val newPoint = GeoPoint(
                    latitude = 31.2397,
                    longitude = 121.4998,
                    altitude = MockConfig.DEFAULT_ALTITUDE,
                    accuracy = MockConfig.DEFAULT_ACCURACY
                )
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(12)
                vm.onMapTapped(newPoint)

                kotlinx.coroutines.delay(180)
                val runtime = coordinator.runtimeState.value
                assertTrue(
                    "Expected SingleRunning after setMockMode + map tap, got ${runtime.status}",
                    runtime.status is MockRunStatus.SingleRunning
                )
                assertEquals(MockMode.LSPOSED_ONLY, runtime.activeMode)
                val currentPoint = runtime.currentPoint
                assertNotNull(currentPoint)
                assertEquals(newPoint.latitude, currentPoint!!.latitude, 0.0)
                assertEquals(newPoint.longitude, currentPoint.longitude, 0.0)
                assertEquals(newPoint.altitude, currentPoint.altitude, 0.0)
                assertEquals(newPoint.accuracy, currentPoint.accuracy, 0f)
                assertEquals(newPoint.latitude, vm.uiState.value.selectedPoint.latitude, 0.0)
                assertEquals(newPoint.longitude, vm.uiState.value.selectedPoint.longitude, 0.0)

                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineCoordinator rapid mode switches preserve active SingleRunning state`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val stopRootCalls = AtomicInteger(0)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        stopRootCalls.incrementAndGet()
                        spyDriver.stop()
                    }
                )

                vm.onStartSingleClicked()
                val startDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning &&
                    System.currentTimeMillis() < startDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                vm.setMockMode(MockMode.ROOT_ONLY)
                vm.setMockMode(MockMode.LSPOSED_ONLY)

                val switchDeadline = System.currentTimeMillis() + 3000L
                while ((stopRootCalls.get() < 2 ||
                        vm.uiState.value.config.mode != MockMode.LSPOSED_ONLY ||
                        coordinator.runtimeState.value.activeMode != MockMode.LSPOSED_ONLY ||
                        coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < switchDeadline
                ) {
                    kotlinx.coroutines.delay(10)
                }
                kotlinx.coroutines.delay(80)
                assertEquals(2, stopRootCalls.get())
                assertEquals(MockMode.LSPOSED_ONLY, coordinator.runtimeState.value.activeMode)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                assertFalse(spyDriver.isActive.get())

                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineCoordinator Idle and Error roundtrip mode switches never swallow second switch before starting single mock`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val allowForeground = AtomicBoolean(true)
                val stopRootCalls = AtomicInteger(0)
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { allowForeground.get() },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = CoordinatorBackedEngineGateway(coordinator),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        stopRootCalls.incrementAndGet()
                        spyDriver.stop()
                    }
                )

                suspend fun awaitSettledMode(expectedMode: MockMode) {
                    val deadline = System.currentTimeMillis() + 2000L
                    while ((configRepo.currentConfig.mode != expectedMode ||
                            vm.uiState.value.config.mode != expectedMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                    assertEquals(expectedMode, configRepo.currentConfig.mode)
                    assertEquals(expectedMode, vm.uiState.value.config.mode)
                }

                suspend fun awaitModeConverged(expectedMode: MockMode) {
                    val deadline = System.currentTimeMillis() + 2000L
                    while ((configRepo.currentConfig.mode != expectedMode ||
                            vm.uiState.value.config.mode != expectedMode ||
                            coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                            coordinator.runtimeState.value.activeMode != expectedMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(10)
                    }
                    kotlinx.coroutines.delay(50)
                    assertEquals(expectedMode, configRepo.currentConfig.mode)
                    assertEquals(expectedMode, vm.uiState.value.config.mode)
                    assertTrue(
                        "Expected SingleRunning for $expectedMode, got ${coordinator.runtimeState.value.status}",
                        coordinator.runtimeState.value.status is MockRunStatus.SingleRunning
                    )
                    assertEquals(expectedMode, coordinator.runtimeState.value.activeMode)
                }

                // 1) Idle 态：AUTO -> ROOT_ONLY -> AUTO（连续无延迟，排在控制队列内按序收敛）
                repeat(3) {
                    vm.setMockMode(MockMode.AUTO)
                    awaitSettledMode(MockMode.AUTO)
                    assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Idle)

                    vm.setMockMode(MockMode.ROOT_ONLY)
                    vm.setMockMode(MockMode.AUTO)
                    withContext(mainExecutor) {
                        vm.onStartSingleClicked()
                    }
                    awaitModeConverged(MockMode.AUTO)

                    vm.stopMock()
                    val stopDeadline = System.currentTimeMillis() + 2000L
                    while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                        System.currentTimeMillis() < stopDeadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                }

                // 2) Idle 态：三连无任何延迟直接调用（AUTO -> ROOT_ONLY -> AUTO -> onStartSingleClicked）
                vm.setMockMode(MockMode.ROOT_ONLY)
                vm.setMockMode(MockMode.AUTO)
                vm.onStartSingleClicked()
                awaitModeConverged(MockMode.AUTO)

                vm.stopMock()
                val stopDeadline1 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                    System.currentTimeMillis() < stopDeadline1
                ) {
                    kotlinx.coroutines.delay(5)
                }

                // 3) Idle 态：LSPOSED_ONLY -> ROOT_ONLY -> LSPOSED_ONLY（通过 stopRootCalls 校验中间切换未被吞并）
                val initialStopRootCalls = stopRootCalls.get()
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                awaitSettledMode(MockMode.LSPOSED_ONLY)
                val settleLspStopDeadline = System.currentTimeMillis() + 2000L
                while (stopRootCalls.get() <= initialStopRootCalls &&
                    System.currentTimeMillis() < settleLspStopDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertEquals(initialStopRootCalls + 1, stopRootCalls.get())

                repeat(3) {
                    val stopsBeforeRound = stopRootCalls.get()
                    vm.setMockMode(MockMode.ROOT_ONLY)
                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    withContext(mainExecutor) {
                        vm.onStartSingleClicked()
                    }
                    awaitModeConverged(MockMode.LSPOSED_ONLY)
                    assertEquals(
                        "Expected intermediate ROOT_ONLY -> LSPOSED_ONLY switch not swallowed",
                        stopsBeforeRound + 1,
                        stopRootCalls.get()
                    )
                    assertFalse("Expected root driver inactive in LSPOSED_ONLY", spyDriver.isActive.get())

                    vm.stopMock()
                    val stopDeadline = System.currentTimeMillis() + 2000L
                    while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                        System.currentTimeMillis() < stopDeadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                }

                // 4) Error 态：AUTO -> ROOT_ONLY -> AUTO 与 LSPOSED_ONLY -> ROOT_ONLY -> LSPOSED_ONLY
                vm.setMockMode(MockMode.AUTO)
                awaitSettledMode(MockMode.AUTO)
                allowForeground.set(false)
                vm.onStartSingleClicked()
                val errDeadline1 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Error &&
                    System.currentTimeMillis() < errDeadline1
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Error)
                allowForeground.set(true)

                vm.setMockMode(MockMode.ROOT_ONLY)
                vm.setMockMode(MockMode.AUTO)
                withContext(mainExecutor) {
                    vm.onStartSingleClicked()
                }
                awaitModeConverged(MockMode.AUTO)

                vm.stopMock()
                val stopDeadline2 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                    System.currentTimeMillis() < stopDeadline2
                ) {
                    kotlinx.coroutines.delay(5)
                }

                val stopsBeforeErrLsp = stopRootCalls.get()
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                awaitSettledMode(MockMode.LSPOSED_ONLY)
                val errLspSettleDeadline = System.currentTimeMillis() + 2000L
                while (stopRootCalls.get() <= stopsBeforeErrLsp &&
                    System.currentTimeMillis() < errLspSettleDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                allowForeground.set(false)
                vm.onStartSingleClicked()
                val errDeadline2 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Error &&
                    System.currentTimeMillis() < errDeadline2
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Error)
                allowForeground.set(true)

                val stopsBeforeErrRoundtrip = stopRootCalls.get()
                vm.setMockMode(MockMode.ROOT_ONLY)
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                withContext(mainExecutor) {
                    vm.onStartSingleClicked()
                }
                awaitModeConverged(MockMode.LSPOSED_ONLY)
                assertEquals(
                    "Expected Error roundtrip ROOT_ONLY -> LSPOSED_ONLY not swallowed",
                    stopsBeforeErrRoundtrip + 1,
                    stopRootCalls.get()
                )
                assertFalse("Expected root driver inactive in LSPOSED_ONLY after Error roundtrip", spyDriver.isActive.get())

                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineController external coordinator stopMock during setMockMode suspend window prevents ghost resurrection`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                var enteredLatch: CountDownLatch? = null
                var releaseLatch: CountDownLatch? = null

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        enteredLatch?.countDown()
                        releaseLatch?.await(2, TimeUnit.SECONDS)
                        spyDriver.stop()
                    }
                )

                // A1) SingleRunning 挂起窗口内触发外部 coordinator.stopMock()（模拟 ACTION_CONTROL_STOP / onDestroy）并紧接 onMapTapped 触发 StateFlow conflation
                vm.onStartSingleClicked()
                val singleStartDeadline = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < singleStartDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning)

                val singleEntered = CountDownLatch(1)
                val singleRelease = CountDownLatch(1)
                enteredLatch = singleEntered
                releaseLatch = singleRelease

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                assertTrue("Timed out waiting for stopRootProvider window (SingleRunning)", singleEntered.await(2, TimeUnit.SECONDS))

                // 模拟通知栏停止直接调 coordinator.stopMock()，紧接用户点地图触发 StateFlow conflation 遮蔽
                coordinator.stopMock()
                val tappedDuringWindow = GeoPoint(31.2304, 121.4737, MockConfig.DEFAULT_ALTITUDE, MockConfig.DEFAULT_ACCURACY)
                vm.onMapTapped(tappedDuringWindow)
                singleRelease.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected coordinator Idle after external stopMock + onMapTapped during SingleRunning switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(
                    "Expected uiState Idle after external stopMock, got ${vm.uiState.value.runtimeState.status}",
                    vm.uiState.value.runtimeState.status is MockRunStatus.Idle
                )
                assertFalse("Expected config.enabled == false after external stopMock", configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertFalse("Expected spyDriver inactive", spyDriver.isActive.get())

                // A2) CruiseRunning 挂起窗口内触发外部 coordinator.stopMock()
                enteredLatch = null
                releaseLatch = null
                vm.setMockMode(MockMode.AUTO)
                val autoSettleDeadline = System.currentTimeMillis() + 2000L
                while ((configRepo.currentConfig.mode != MockMode.AUTO ||
                        vm.uiState.value.config.mode != MockMode.AUTO) &&
                    System.currentTimeMillis() < autoSettleDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }

                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                vm.onStartCruiseClicked()
                val cruiseStartDeadline = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < cruiseStartDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning)

                val cruiseEntered = CountDownLatch(1)
                val cruiseRelease = CountDownLatch(1)
                enteredLatch = cruiseEntered
                releaseLatch = cruiseRelease

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                assertTrue("Timed out waiting for stopRootProvider window (CruiseRunning)", cruiseEntered.await(2, TimeUnit.SECONDS))
                coordinator.stopMock()
                cruiseRelease.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected coordinator Idle after external stopMock during CruiseRunning switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertFalse(spyDriver.isActive.get())

                // A3) 外部先停 + controllerDispatcher 滞后后再调 setMockMode：绝不幽灵复活
                enteredLatch = null
                releaseLatch = null
                vm.setMockMode(MockMode.AUTO)
                val autoSettle2 = System.currentTimeMillis() + 2000L
                while ((configRepo.currentConfig.mode != MockMode.AUTO ||
                        vm.uiState.value.config.mode != MockMode.AUTO) &&
                    System.currentTimeMillis() < autoSettle2
                ) {
                    kotlinx.coroutines.delay(5)
                }
                vm.onStartSingleClicked()
                val singleStart2 = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < singleStart2
                ) {
                    kotlinx.coroutines.delay(5)
                }

                val blockerEntered = CountDownLatch(1)
                val blockerRelease = CountDownLatch(1)
                controllerDispatcher.executor.execute {
                    blockerEntered.countDown()
                    blockerRelease.await(2, TimeUnit.SECONDS)
                }
                assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))

                // 外部先停掉 coordinator，而 controller.runtimeState 仍滞留在 SingleRunning
                coordinator.stopMock()
                assertTrue(controller.runtimeState.value.status is MockRunStatus.SingleRunning)
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(30)
                blockerRelease.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected Idle when external stopMock preceded setMockMode under controller lag, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineController async runtimeState lag prevents ghost resurrection after stopMock pauseCruise and rapid startSingle`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                suspend fun blockControllerDispatcher(): CountDownLatch {
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    controllerDispatcher.executor.execute {
                        entered.countDown()
                        release.await(2, TimeUnit.SECONDS)
                    }
                    assertTrue("Failed to block controllerDispatcher", entered.await(2, TimeUnit.SECONDS))
                    return release
                }

                // B1) 确定性异步滞后：SingleRunning 下 vm.stopMock() 后无延迟紧接 vm.setMockMode(LSPOSED_ONLY) -> 0 幽灵复活
                vm.onStartSingleClicked()
                val startDeadline1 = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < startDeadline1
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning)

                val release1 = blockControllerDispatcher()
                vm.stopMock()
                assertTrue(
                    "Expected controller.runtimeState still lagged at SingleRunning before release",
                    controller.runtimeState.value.status is MockRunStatus.SingleRunning
                )
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(25)
                release1.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected Idle after stopMock + setMockMode under controller lag, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertFalse(spyDriver.isActive.get())

                // B2) 确定性异步滞后：CruiseRunning 下 vm.pauseCruise() 后无延迟紧接 vm.setMockMode(AUTO) -> 保持 Idle 不复活 CruiseRunning
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                vm.onStartCruiseClicked()
                val cruiseDeadline = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < cruiseDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning)

                val release2 = blockControllerDispatcher()
                vm.pauseCruise()
                assertTrue(
                    "Expected controller.runtimeState lagged at CruiseRunning before release",
                    controller.runtimeState.value.status is MockRunStatus.CruiseRunning
                )
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(25)
                release2.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected Idle after pauseCruise + setMockMode under controller lag, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertEquals(MockMode.AUTO, vm.uiState.value.config.mode)

                // B3) 确定性异步滞后：Idle 下 vm.onStartSingleClicked() 后无延迟紧接 vm.setMockMode(LSPOSED_ONLY) -> 正确收敛 SingleRunning(LSPOSED_ONLY) 并停掉旧 Root
                val stopsBeforeStartSwitch = spyDriver.stopCalls.get()
                val release3 = blockControllerDispatcher()
                vm.onStartSingleClicked()
                assertTrue(
                    "Expected controller.runtimeState lagged at Idle before release",
                    controller.runtimeState.value.status is MockRunStatus.Idle
                )
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(25)
                release3.countDown()

                val convergeDeadline = System.currentTimeMillis() + 2500L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        coordinator.runtimeState.value.activeMode != MockMode.LSPOSED_ONLY ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.config.mode != MockMode.LSPOSED_ONLY) &&
                    System.currentTimeMillis() < convergeDeadline
                ) {
                    kotlinx.coroutines.delay(10)
                }
                kotlinx.coroutines.delay(80)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                assertEquals(MockMode.LSPOSED_ONLY, coordinator.runtimeState.value.activeMode)
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertTrue(spyDriver.stopCalls.get() > stopsBeforeStartSwitch)
                assertFalse(spyDriver.isActive.get())

                vm.stopMock()
                kotlinx.coroutines.delay(40)

                // B4) 5 轮无门闩真实并发压测：startSingle + setMockMode 与 stopMock + setMockMode 交错执行
                repeat(5) { round ->
                    val targetMode = if (round % 2 == 0) MockMode.AUTO else MockMode.ROOT_ONLY
                    vm.onStartSingleClicked()
                    vm.setMockMode(targetMode)
                    val runDeadline = System.currentTimeMillis() + 2000L
                    while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                            coordinator.runtimeState.value.activeMode != targetMode ||
                            vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning ||
                            vm.uiState.value.config.mode != targetMode) &&
                        System.currentTimeMillis() < runDeadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                    assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                    assertEquals(targetMode, coordinator.runtimeState.value.activeMode)

                    val stopTargetMode = MockMode.LSPOSED_ONLY
                    vm.stopMock()
                    vm.setMockMode(stopTargetMode)
                    val stopRoundDeadline = System.currentTimeMillis() + 2000L
                    while ((vm.uiState.value.config.mode != stopTargetMode ||
                            vm.uiState.value.runtimeState.status !is MockRunStatus.Idle) &&
                        System.currentTimeMillis() < stopRoundDeadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                    kotlinx.coroutines.delay(100)
                    assertTrue(
                        "Round $round expected Idle after stopMock + setMockMode, got ${coordinator.runtimeState.value.status}",
                        coordinator.runtimeState.value.status is MockRunStatus.Idle
                    )
                    assertFalse(configRepo.currentConfig.enabled)
                    assertEquals(stopTargetMode, vm.uiState.value.config.mode)
                    assertFalse(spyDriver.isActive.get())
                }
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `external coordinator stopMock followed by concurrent whitelist update during setMockMode window prevents ghost resurrection`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val attackExecutor = Executors.newSingleThreadExecutor()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 25L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val wlRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )

                var enteredLatch: CountDownLatch? = null
                var releaseLatch: CountDownLatch? = null

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = { wlRepo },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        enteredLatch?.countDown()
                        releaseLatch?.await(2, TimeUnit.SECONDS)
                        spyDriver.stop()
                    }
                )

                // C1) SingleRunning 挂起窗口内：coordinator.stopMock() 紧接 WhitelistRepository.togglePackage 引发 StateFlow conflation
                vm.onStartSingleClicked()
                val singleDeadline = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < singleDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning)

                val singleEntered = CountDownLatch(1)
                val singleRelease = CountDownLatch(1)
                enteredLatch = singleEntered
                releaseLatch = singleRelease

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                assertTrue("Timed out waiting for stopRootProvider window (SingleRunning)", singleEntered.await(2, TimeUnit.SECONDS))

                // 无延迟连续触发外部停机与白名单修改，跳过中间停机版本触发 isOnlyTargetPackagesChanged 分支
                coordinator.stopMock()
                wlRepo.togglePackage("com.test.snipe1e")
                singleRelease.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected coordinator Idle after stopMock + togglePackage during SingleRunning switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(
                    "Expected uiState Idle after stopMock + togglePackage, got ${vm.uiState.value.runtimeState.status}",
                    vm.uiState.value.runtimeState.status is MockRunStatus.Idle
                )
                assertFalse("Expected config.enabled == false", configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertTrue("com.test.snipe1e" in configRepo.currentConfig.targetPackages)
                assertFalse(spyDriver.isActive.get())

                // C2) CruiseRunning 挂起窗口内：coordinator.stopMock() 紧接 WhitelistRepository.togglePackage
                enteredLatch = null
                releaseLatch = null
                vm.setMockMode(MockMode.AUTO)
                val autoDeadline = System.currentTimeMillis() + 2000L
                while ((configRepo.currentConfig.mode != MockMode.AUTO ||
                        vm.uiState.value.config.mode != MockMode.AUTO) &&
                    System.currentTimeMillis() < autoDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }

                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                vm.onStartCruiseClicked()
                val cruiseDeadline = System.currentTimeMillis() + 2000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < cruiseDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning)

                val cruiseEntered = CountDownLatch(1)
                val cruiseRelease = CountDownLatch(1)
                enteredLatch = cruiseEntered
                releaseLatch = cruiseRelease

                vm.setMockMode(MockMode.LSPOSED_ONLY)
                assertTrue("Timed out waiting for stopRootProvider window (CruiseRunning)", cruiseEntered.await(2, TimeUnit.SECONDS))

                coordinator.stopMock()
                wlRepo.togglePackage("com.test.snipe1e.cruise")
                cruiseRelease.countDown()

                kotlinx.coroutines.delay(180)
                assertTrue(
                    "Expected coordinator Idle after stopMock + togglePackage during CruiseRunning switch, got ${coordinator.runtimeState.value.status}",
                    coordinator.runtimeState.value.status is MockRunStatus.Idle
                )
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertEquals(MockMode.LSPOSED_ONLY, vm.uiState.value.config.mode)
                assertTrue("com.test.snipe1e.cruise" in configRepo.currentConfig.targetPackages)
                assertFalse(spyDriver.isActive.get())

                // C3) 20 轮真实默认时钟（无门闩阻塞）并发压测：stopMock + togglePackage 交错落入 40ms 窗口
                repeat(20) { round ->
                    enteredLatch = null
                    releaseLatch = null
                    vm.setMockMode(MockMode.AUTO)
                    val modeDeadline = System.currentTimeMillis() + 2000L
                    while ((configRepo.currentConfig.mode != MockMode.AUTO ||
                            vm.uiState.value.config.mode != MockMode.AUTO) &&
                        System.currentTimeMillis() < modeDeadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }

                    vm.onStartSingleClicked()
                    val runDeadline = System.currentTimeMillis() + 2000L
                    while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                            vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                        System.currentTimeMillis() < runDeadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                    val stopWindowHit = CountDownLatch(1)
                    val attackDone = CountDownLatch(1)
                    enteredLatch = stopWindowHit
                    attackExecutor.execute {
                        if (stopWindowHit.await(2, TimeUnit.SECONDS)) {
                            coordinator.stopMock()
                            wlRepo.togglePackage("com.test.realclock.r$round")
                        }
                        attackDone.countDown()
                    }

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Round $round timed out waiting for attack completion", attackDone.await(3, TimeUnit.SECONDS))

                    val settleDeadline = System.currentTimeMillis() + 2000L
                    while ((configRepo.currentConfig.mode != MockMode.LSPOSED_ONLY ||
                            vm.uiState.value.config.mode != MockMode.LSPOSED_ONLY) &&
                        System.currentTimeMillis() < settleDeadline
                    ) {
                        kotlinx.coroutines.delay(5)
                    }
                    kotlinx.coroutines.delay(120)
                    assertTrue(
                        "Real-clock round $round ghost resurrection! coord=${coordinator.runtimeState.value.status}",
                        coordinator.runtimeState.value.status is MockRunStatus.Idle
                    )
                    assertTrue(
                        "Real-clock round $round uiState not Idle: ${vm.uiState.value.runtimeState.status}",
                        vm.uiState.value.runtimeState.status is MockRunStatus.Idle
                    )
                    assertFalse("Real-clock round $round config.enabled should be false", configRepo.currentConfig.enabled)
                    assertFalse(spyDriver.isActive.get())
                }
            }
        } finally {
            coordinatorScope.cancel()
            attackExecutor.shutdownNow()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `MockConfigRepository lastStopOrEngineDisabledVersion and isVersionStrictlyAfter handle normal old and wraparound versions accurately`() {
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val configRepo = MockConfigRepository(
                prefs = InMemorySharedPreferences(),
                isWorldReadable = false,
                ioScope = ioScope,
                debounceMs = 10L
            )
            val wlRepo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = InMemorySharedPreferences(),
                packageQuerySource = PackageQuerySource { emptyList() },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )

            // 初始为 0L
            assertEquals(0L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(0L, configRepo.engineDisableSeq)

            // 1) enabled = true 写入不更新 lastStopOrEngineDisabledVersion 与 engineDisableSeq
            val startedCfg = configRepo.updateImmediate { it.copy(enabled = true) }
            assertEquals(1L, startedCfg.version)
            assertEquals(0L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(0L, configRepo.engineDisableSeq)

            // 2) 白名单 togglePackage 在 enabled=true 下不更新
            wlRepo.togglePackage("com.autonavi.minimap")
            assertEquals(2L, configRepo.currentConfig.version)
            assertEquals(0L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(0L, configRepo.engineDisableSeq)

            // 3) 引擎停机 (!next.enabled && targetPackages 不变) 更新为 next.version 且 engineDisableSeq +1
            val stoppedCfg = configRepo.updateImmediate { it.copy(enabled = false, speed = 0.0f) }
            assertEquals(3L, stoppedCfg.version)
            assertEquals(3L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, configRepo.engineDisableSeq)

            // 4) 停机态下修改白名单 (targetPackages 改变)：严禁更新 lastStopOrEngineDisabledVersion 与 engineDisableSeq
            wlRepo.togglePackage("com.tencent.mm")
            assertEquals(4L, configRepo.currentConfig.version)
            assertEquals(3L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, configRepo.engineDisableSeq)

            // 5) updateDebounced 写入 !next.enabled && targetPackages 不变时同步更新
            val debouncedStopped = configRepo.updateDebounced { it.copy(enabled = false, speed = 0.0f) }
            assertEquals(5L, debouncedStopped.version)
            assertEquals(5L, configRepo.lastStopOrEngineDisabledVersion)
            assertEquals(2L, configRepo.engineDisableSeq)

            // 6) reloadFromPrefs 若从外部恢复到停机态新快照，亦更新 lastStopOrEngineDisabledVersion 与 engineDisableSeq
            val sharedPrefs = InMemorySharedPreferences()
            val reloadRepo = MockConfigRepository(
                prefs = sharedPrefs,
                isWorldReadable = false,
                ioScope = ioScope,
                debounceMs = 10L
            )
            assertEquals(0L, reloadRepo.engineDisableSeq)
            reloadRepo.updateImmediate { it.copy(enabled = true) }
            assertEquals(0L, reloadRepo.lastStopOrEngineDisabledVersion)
            assertEquals(0L, reloadRepo.engineDisableSeq)
            val externalStopCfg = reloadRepo.currentConfig.copy(enabled = false, version = 9L)
            com.mockloc.ipc.ConfigSyncSerializer.writeToPrefs(sharedPrefs.edit(), externalStopCfg).commit()
            reloadRepo.reloadFromPrefs()
            assertEquals(9L, reloadRepo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, reloadRepo.engineDisableSeq)

            // 7) 严格版本比较与回绕边界校验
            assertFalse(MainViewModel.isVersionStrictlyAfter(0L, 0L))
            assertFalse(MainViewModel.isVersionStrictlyAfter(0L, 10L))
            assertFalse(MainViewModel.isVersionStrictlyAfter(-1L, 10L))
            assertFalse(MainViewModel.isVersionStrictlyAfter(5L, -1L))
            // 关键陷阱场景：此前停机于 v=5，后来恢复运行又并发白名单使 baseline=10，绝不能误判为回绕
            assertFalse(MainViewModel.isVersionStrictlyAfter(5L, 10L))
            assertFalse(MainViewModel.isVersionStrictlyAfter(10L, 10L))
            assertTrue(MainViewModel.isVersionStrictlyAfter(11L, 10L))
            assertTrue(MainViewModel.isVersionStrictlyAfter(5L, 0L))
            // 真回绕场景：MockConfigRepository 在 Long.MAX_VALUE - 1L 处回绕到 1L
            assertTrue(MainViewModel.isVersionStrictlyAfter(1L, Long.MAX_VALUE - 1L))
            assertTrue(MainViewModel.isVersionStrictlyAfter(2L, Long.MAX_VALUE - 5L))
            // 回绕前旧版本 (Long.MAX_VALUE - 2L) vs 回绕后新基线 (3L) 应返回 false
            assertFalse(MainViewModel.isVersionStrictlyAfter(Long.MAX_VALUE - 2L, 3L))
            assertTrue(MainViewModel.isVersionStrictlyAfter(Long.MAX_VALUE - 1L, Long.MAX_VALUE - 2L))

            // 8) reloadFromPrefs C4 四重精确边界验证（current.enabled / targetPackages / 幂等 / 停机态更新）
            val c4Prefs = InMemorySharedPreferences()
            val c4Repo = MockConfigRepository(
                prefs = c4Prefs,
                isWorldReadable = false,
                ioScope = ioScope,
                debounceMs = 10L
            )
            c4Repo.updateImmediate {
                it.copy(enabled = true, targetPackages = setOf("com.a"))
            }
            assertEquals(0L, c4Repo.engineDisableSeq)

            // C4-1: current.enabled == true 且外部停机同时修改 targetPackages -> 必须递增
            val c4Case1 = c4Repo.currentConfig.copy(
                enabled = false,
                targetPackages = setOf("com.a", "com.b"),
                version = 11L
            )
            com.mockloc.ipc.ConfigSyncSerializer.writeToPrefs(c4Prefs.edit(), c4Case1).commit()
            c4Repo.reloadFromPrefs()
            assertEquals(11L, c4Repo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, c4Repo.engineDisableSeq)

            // C4-2: 幂等重载 loaded == current -> 严禁重复递增
            c4Repo.reloadFromPrefs()
            assertEquals(11L, c4Repo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, c4Repo.engineDisableSeq)

            // C4-3: current.enabled == false 仅外部白名单变更 targetPackages -> 严禁误计为停机
            val c4Case3 = c4Repo.currentConfig.copy(
                enabled = false,
                targetPackages = setOf("com.c"),
                version = 12L
            )
            com.mockloc.ipc.ConfigSyncSerializer.writeToPrefs(c4Prefs.edit(), c4Case3).commit()
            c4Repo.reloadFromPrefs()
            assertEquals(11L, c4Repo.lastStopOrEngineDisabledVersion)
            assertEquals(1L, c4Repo.engineDisableSeq)

            // C4-4: current.enabled == false 外部同白名单新停机快照 (loaded != current) -> 递增
            val c4Case4 = c4Repo.currentConfig.copy(
                enabled = false,
                targetPackages = setOf("com.c"),
                speed = 0.0f,
                latitude = 31.2304,
                version = 13L
            )
            com.mockloc.ipc.ConfigSyncSerializer.writeToPrefs(c4Prefs.edit(), c4Case4).commit()
            c4Repo.reloadFromPrefs()
            assertEquals(13L, c4Repo.lastStopOrEngineDisabledVersion)
            assertEquals(2L, c4Repo.engineDisableSeq)
        } finally {
            ioScope.cancel()
        }
    }

    @Test
    fun `real MockEngineCoordinator rapid 1ms tick mode switches 200 rounds cause zero false aborts`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 1L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                val drainingField = MainViewModel::class.java.getDeclaredField("isDrainingControlQueue").apply {
                    isAccessible = true
                }
                val lockField = MainViewModel::class.java.getDeclaredField("lifecycleLock").apply {
                    isAccessible = true
                }
                val lockObj = lockField.get(vm)!!
                fun isDraining(): Boolean = synchronized(lockObj) { drainingField.getBoolean(vm) }

                // 1) TICK-CHECK 1: SingleRunning + tickIntervalMs = 1L，连续 200 轮模式切换
                vm.onStartSingleClicked()
                val startDeadline = System.currentTimeMillis() + 3000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < startDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                val cycleModes = listOf(
                    MockMode.LSPOSED_ONLY,
                    MockMode.ROOT_ONLY,
                    MockMode.LSPOSED_ONLY,
                    MockMode.AUTO
                )
                var singleFalseAborts = 0
                for (round in 0 until 200) {
                    val targetMode = cycleModes[round % cycleModes.size]
                    vm.setMockMode(targetMode)
                    val deadline = System.currentTimeMillis() + 5000L
                    while ((isDraining() ||
                            vm.uiState.value.config.mode != targetMode ||
                            coordinator.runtimeState.value.activeMode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    if (isDraining() ||
                        coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        !configRepo.currentConfig.enabled ||
                        configRepo.currentConfig.mode != targetMode
                    ) {
                        singleFalseAborts++
                        break
                    }
                }
                assertEquals(
                    "Expected 0 false aborts over 200 SingleRunning switches at 1ms tick",
                    0,
                    singleFalseAborts
                )
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                assertTrue(configRepo.currentConfig.enabled)

                // 2) CruiseRunning + tickIntervalMs = 1L，连续 50 轮模式切换（使用 LOOP 模式避免单程结束）
                vm.stopMock()
                val stopDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                    System.currentTimeMillis() < stopDeadline
                ) {
                    kotlinx.coroutines.delay(2)
                }
                vm.setLoopMode(com.mockloc.domain.engine.LoopMode.LOOP)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9587, 116.4575), "B")
                vm.onStartCruiseClicked()
                val cruiseDeadline = System.currentTimeMillis() + 3000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < cruiseDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

                var cruiseFalseAborts = 0
                for (round in 0 until 50) {
                    val targetMode = cycleModes[round % cycleModes.size]
                    vm.setMockMode(targetMode)
                    val deadline = System.currentTimeMillis() + 5000L
                    while ((isDraining() ||
                            vm.uiState.value.config.mode != targetMode ||
                            coordinator.runtimeState.value.activeMode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    if (isDraining() ||
                        coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        !configRepo.currentConfig.enabled ||
                        configRepo.currentConfig.mode != targetMode
                    ) {
                        cruiseFalseAborts++
                        break
                    }
                }
                assertEquals(
                    "Expected 0 false aborts over 50 CruiseRunning switches at 1ms tick",
                    0,
                    cruiseFalseAborts
                )
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                assertTrue(configRepo.currentConfig.enabled)

                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real MockEngineCoordinator concurrent high frequency whitelist toggles during 50 mode switches cause zero false aborts`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val toggleExecutor = Executors.newSingleThreadExecutor()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 5L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val wlRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )
                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = { wlRepo },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                val drainingField = MainViewModel::class.java.getDeclaredField("isDrainingControlQueue").apply {
                    isAccessible = true
                }
                val lockField = MainViewModel::class.java.getDeclaredField("lifecycleLock").apply {
                    isAccessible = true
                }
                val lockObj = lockField.get(vm)!!
                fun isDraining(): Boolean = synchronized(lockObj) { drainingField.getBoolean(vm) }

                val keepToggling = AtomicBoolean(true)
                val toggleCount = AtomicInteger(0)
                val toggleFuture = toggleExecutor.submit {
                    val packages = arrayOf(
                        "com.test.snipe3b.a",
                        "com.test.snipe3b.b",
                        "com.test.snipe3b.c"
                    )
                    var idx = 0
                    while (keepToggling.get()) {
                        wlRepo.togglePackage(packages[idx % packages.size])
                        idx++
                        toggleCount.incrementAndGet()
                        try {
                            Thread.sleep(1L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }

                // 1) SNIPE 3B: SingleRunning + tickIntervalMs = 5L + 后台高频并发 togglePackage 50 轮
                vm.onStartSingleClicked()
                val startDeadline = System.currentTimeMillis() + 3000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                    System.currentTimeMillis() < startDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                val cycleModes = listOf(MockMode.LSPOSED_ONLY, MockMode.AUTO)
                var singleFalseAborts = 0
                for (round in 0 until 50) {
                    val targetMode = cycleModes[round % cycleModes.size]
                    vm.setMockMode(targetMode)
                    val deadline = System.currentTimeMillis() + 5000L
                    while ((isDraining() ||
                            vm.uiState.value.config.mode != targetMode ||
                            coordinator.runtimeState.value.activeMode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    if (isDraining() ||
                        coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        !configRepo.currentConfig.enabled ||
                        configRepo.currentConfig.mode != targetMode
                    ) {
                        singleFalseAborts++
                        break
                    }
                }
                assertEquals(
                    "Expected 0 false aborts over 50 SingleRunning switches under concurrent whitelist toggles",
                    0,
                    singleFalseAborts
                )
                assertTrue("Expected concurrent toggles executed", toggleCount.get() > 20)

                // 2) CruiseRunning + tickIntervalMs = 5L + 后台高频并发 togglePackage 20 轮
                vm.stopMock()
                val stopDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.Idle &&
                    System.currentTimeMillis() < stopDeadline
                ) {
                    kotlinx.coroutines.delay(2)
                }
                vm.setLoopMode(com.mockloc.domain.engine.LoopMode.LOOP)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9587, 116.4575), "B")
                vm.onStartCruiseClicked()
                val cruiseDeadline = System.currentTimeMillis() + 3000L
                while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                    System.currentTimeMillis() < cruiseDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

                var cruiseFalseAborts = 0
                for (round in 0 until 20) {
                    val targetMode = cycleModes[round % cycleModes.size]
                    vm.setMockMode(targetMode)
                    val deadline = System.currentTimeMillis() + 5000L
                    while ((isDraining() ||
                            vm.uiState.value.config.mode != targetMode ||
                            coordinator.runtimeState.value.activeMode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    if (isDraining() ||
                        coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        !configRepo.currentConfig.enabled ||
                        configRepo.currentConfig.mode != targetMode
                    ) {
                        cruiseFalseAborts++
                        break
                    }
                }
                assertEquals(
                    "Expected 0 false aborts over 20 CruiseRunning switches under concurrent whitelist toggles",
                    0,
                    cruiseFalseAborts
                )

                keepToggling.set(false)
                toggleFuture.get(2, TimeUnit.SECONDS)
                vm.stopMock()
                kotlinx.coroutines.delay(30)
            }
        } finally {
            coordinatorScope.cancel()
            toggleExecutor.shutdownNow()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `external coordinator stopMock right after startSingleMock or startCruise inside switch critical section still aborts without ghost resurrection`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 5L
                )
                val injectExternalStopBeforeStart = AtomicBoolean(false)
                val injectExternalStopOnNextStart = AtomicBoolean(false)
                val wlRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )
                val gateway = object : MockEngineGateway {
                    override val runtimeState: StateFlow<MockRuntimeState> get() = coordinator.runtimeState
                    override fun startSingleMock(point: GeoPoint, config: MockConfig) {
                        val triggerPreStartStop = injectExternalStopBeforeStart.compareAndSet(true, false)
                        if (triggerPreStartStop) {
                            // 模拟在 preStartCfg 读取之后、coordinator.startSingleMock 写入之前的微秒窗口发生外部停机
                            coordinator.stopMock()
                        }
                        coordinator.startSingleMock(point, config)
                        if (triggerPreStartStop) {
                            // 紧接白名单更新使 postStartCfg.enabled 保持 true，完全依靠 isVersionStrictlyAfter(disabledVer, preStartCfg.version) 精确拦截
                            wlRepo.togglePackage("com.test.prestart.stop")
                        }
                        if (injectExternalStopOnNextStart.compareAndSet(true, false)) {
                            // 模拟在 startSingleMock 与 postStartCfg 读取之间的微秒窗口发生真实外部停机
                            coordinator.stopMock()
                        }
                    }
                    override fun updateSingleTarget(point: GeoPoint) = coordinator.updateSingleTarget(point)
                    override fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) {
                        val triggerPreStartStop = injectExternalStopBeforeStart.compareAndSet(true, false)
                        if (triggerPreStartStop) {
                            coordinator.stopMock()
                        }
                        coordinator.startCruise(route, cruiseConfig, baseConfig)
                        if (triggerPreStartStop) {
                            wlRepo.togglePackage("com.test.prestart.cruise.stop")
                        }
                        if (injectExternalStopOnNextStart.compareAndSet(true, false)) {
                            coordinator.stopMock()
                        }
                    }
                    override fun pauseCruise() = coordinator.pauseCruise()
                    override fun resumeCruise() = coordinator.resumeCruise()
                    override fun stopMock() = coordinator.stopMock()
                    override fun updateSpeed(speedMps: Double) = coordinator.updateSpeed(speedMps)
                    override fun updateJitter(enabled: Boolean) = coordinator.updateJitter(enabled)
                    override fun release() = Unit
                }

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = gateway,
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = { wlRepo },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                // 1) SingleRunning 临界区后窗口真实外部停机 -> 必须中止并保持 Idle
                vm.onStartSingleClicked()
                val sDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning &&
                    System.currentTimeMillis() < sDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                injectExternalStopOnNextStart.set(true)
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(180)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Idle)
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)

                // 2) SingleRunning 临界区前窗口 (startSingleMock 写 enabled=true 之前) 外部停机 + 白名单并发 -> isVersionStrictlyAfter 精确拦截
                vm.onStartSingleClicked()
                val sDeadline2 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning &&
                    System.currentTimeMillis() < sDeadline2
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                injectExternalStopBeforeStart.set(true)
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(180)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Idle)
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertTrue("com.test.prestart.stop" in configRepo.currentConfig.targetPackages)

                // 3) CruiseRunning 临界区后窗口真实外部停机 -> 必须中止并保持 Idle
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(40)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9187, 116.4075), "B")
                vm.onStartCruiseClicked()
                val cDeadline = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning &&
                    System.currentTimeMillis() < cDeadline
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

                injectExternalStopOnNextStart.set(true)
                vm.setMockMode(MockMode.LSPOSED_ONLY)
                kotlinx.coroutines.delay(180)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Idle)
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)

                // 4) CruiseRunning 临界区前窗口外部停机 + 白名单并发 -> isVersionStrictlyAfter 精确拦截
                vm.onStartCruiseClicked()
                val cDeadline2 = System.currentTimeMillis() + 2000L
                while (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning &&
                    System.currentTimeMillis() < cDeadline2
                ) {
                    kotlinx.coroutines.delay(5)
                }
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                injectExternalStopBeforeStart.set(true)
                vm.setMockMode(MockMode.AUTO)
                kotlinx.coroutines.delay(180)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Idle)
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)
                assertFalse(configRepo.currentConfig.enabled)
                assertTrue("com.test.prestart.cruise.stop" in configRepo.currentConfig.targetPackages)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `concurrent even whitelist toggles (A-B-A) during mode switch window cause zero false aborts while external stopMock plus A-B-A aborts cleanly`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val workerExecutor = Executors.newSingleThreadExecutor()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 5L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val wlRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )

                val gateArmed = AtomicBoolean(false)
                val enteredLatchRef = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)
                val releaseLatchRef = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = { wlRepo },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        spyDriver.stop()
                        if (gateArmed.compareAndSet(true, false)) {
                            enteredLatchRef.get()?.countDown()
                            releaseLatchRef.get()?.await(2, TimeUnit.SECONDS)
                        }
                    }
                )

                val drainingField = MainViewModel::class.java.getDeclaredField("isDrainingControlQueue").apply {
                    isAccessible = true
                }
                val lockField = MainViewModel::class.java.getDeclaredField("lifecycleLock").apply {
                    isAccessible = true
                }
                val lockObj = lockField.get(vm)!!
                fun isDraining(): Boolean = synchronized(lockObj) { drainingField.getBoolean(vm) }

                suspend fun waitUntilSettled(targetMode: MockMode) {
                    val deadline = System.currentTimeMillis() + 3000L
                    while ((isDraining() || vm.uiState.value.config.mode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    kotlinx.coroutines.delay(15)
                }

                suspend fun ensureRunningSingle(mode: MockMode) {
                    if (vm.uiState.value.config.mode != mode) {
                        vm.setMockMode(mode)
                        waitUntilSettled(mode)
                    }
                    if (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning) {
                        vm.onStartSingleClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(3)
                        }
                    }
                    assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                }

                suspend fun ensureRunningCruise(mode: MockMode) {
                    if (vm.uiState.value.config.mode != mode) {
                        vm.setMockMode(mode)
                        waitUntilSettled(mode)
                    }
                    if (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning) {
                        vm.onStartCruiseClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(3)
                        }
                    }
                    assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                }

                // A1) stopRootProvider 窗口内并发偶数次 togglePackage (Single 10 轮 + Cruise 10 轮，0 误杀)
                var a1FalseAborts = 0
                for (round in 0 until 10) {
                    ensureRunningSingle(MockMode.AUTO)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    enteredLatchRef.set(entered)
                    releaseLatchRef.set(release)
                    gateArmed.set(true)

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Timed out entering stopRootProvider", entered.await(2, TimeUnit.SECONDS))
                    val toggles = if (round % 2 == 0) 2 else 4
                    repeat(toggles) { wlRepo.togglePackage("com.test.aba.single") }
                    release.countDown()

                    waitUntilSettled(MockMode.LSPOSED_ONLY)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        !configRepo.currentConfig.enabled
                    ) {
                        a1FalseAborts++
                    }
                }
                vm.stopMock()
                kotlinx.coroutines.delay(25)
                vm.setLoopMode(com.mockloc.domain.engine.LoopMode.LOOP)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9587, 116.4575), "B")
                for (round in 0 until 10) {
                    ensureRunningCruise(MockMode.AUTO)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    enteredLatchRef.set(entered)
                    releaseLatchRef.set(release)
                    gateArmed.set(true)

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Timed out entering stopRootProvider", entered.await(2, TimeUnit.SECONDS))
                    val toggles = if (round % 2 == 0) 2 else 4
                    repeat(toggles) { wlRepo.togglePackage("com.test.aba.cruise") }
                    release.countDown()

                    waitUntilSettled(MockMode.LSPOSED_ONLY)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        !configRepo.currentConfig.enabled
                    ) {
                        a1FalseAborts++
                    }
                }
                assertEquals("A1 expected 0 false aborts on A-B-A whitelist toggles", 0, a1FalseAborts)

                // A2) delay(40L) 无阻塞停机窗口内并发偶数次 togglePackage (ROOT_ONLY <-> AUTO, Single 10 + Cruise 10, 0 误杀)
                var a2FalseAborts = 0
                vm.stopMock()
                kotlinx.coroutines.delay(25)
                for (round in 0 until 10) {
                    val startMode = if (round % 2 == 0) MockMode.ROOT_ONLY else MockMode.AUTO
                    val nextMode = if (round % 2 == 0) MockMode.AUTO else MockMode.ROOT_ONLY
                    ensureRunningSingle(startMode)
                    val future = workerExecutor.submit {
                        Thread.sleep(2L)
                        repeat(2) { wlRepo.togglePackage("com.test.aba.d40.s") }
                    }
                    vm.setMockMode(nextMode)
                    future.get(2, TimeUnit.SECONDS)
                    waitUntilSettled(nextMode)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                        !configRepo.currentConfig.enabled
                    ) {
                        a2FalseAborts++
                    }
                }
                vm.stopMock()
                kotlinx.coroutines.delay(25)
                for (round in 0 until 10) {
                    val startMode = if (round % 2 == 0) MockMode.ROOT_ONLY else MockMode.AUTO
                    val nextMode = if (round % 2 == 0) MockMode.AUTO else MockMode.ROOT_ONLY
                    ensureRunningCruise(startMode)
                    val future = workerExecutor.submit {
                        Thread.sleep(2L)
                        repeat(2) { wlRepo.togglePackage("com.test.aba.d40.c") }
                    }
                    vm.setMockMode(nextMode)
                    future.get(2, TimeUnit.SECONDS)
                    waitUntilSettled(nextMode)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                        !configRepo.currentConfig.enabled
                    ) {
                        a2FalseAborts++
                    }
                }
                assertEquals("A2 expected 0 false aborts during delay(40) A-B-A", 0, a2FalseAborts)

                // A3) stopRootProvider 窗口内外部 coordinator.stopMock() + A-B-A 偶数次 togglePackage (Single 10 + Cruise 10, 20/20 拦截)
                var a3Ghosts = 0
                vm.stopMock()
                kotlinx.coroutines.delay(25)
                for (round in 0 until 10) {
                    ensureRunningSingle(MockMode.AUTO)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    enteredLatchRef.set(entered)
                    releaseLatchRef.set(release)
                    gateArmed.set(true)

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Timed out entering stopRootProvider", entered.await(2, TimeUnit.SECONDS))
                    coordinator.stopMock()
                    repeat(2) { wlRepo.togglePackage("com.test.aba.stop.s") }
                    release.countDown()

                    waitUntilSettled(MockMode.LSPOSED_ONLY)
                    kotlinx.coroutines.delay(30)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.Idle ||
                        configRepo.currentConfig.enabled ||
                        spyDriver.isActive.get()
                    ) {
                        a3Ghosts++
                    }
                }
                for (round in 0 until 10) {
                    ensureRunningCruise(MockMode.AUTO)
                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    enteredLatchRef.set(entered)
                    releaseLatchRef.set(release)
                    gateArmed.set(true)

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Timed out entering stopRootProvider", entered.await(2, TimeUnit.SECONDS))
                    coordinator.stopMock()
                    repeat(2) { wlRepo.togglePackage("com.test.aba.stop.c") }
                    release.countDown()

                    waitUntilSettled(MockMode.LSPOSED_ONLY)
                    kotlinx.coroutines.delay(30)
                    if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.Idle ||
                        configRepo.currentConfig.enabled ||
                        spyDriver.isActive.get()
                    ) {
                        a3Ghosts++
                    }
                }
                assertEquals("A3 expected 0 ghosts on external stopMock + A-B-A", 0, a3Ghosts)
            }
        } finally {
            coordinatorScope.cancel()
            workerExecutor.shutdownNow()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `external coordinator stopMock right before internal stopMock after switch quota armed prevents ghost resurrection across fixed and real clocks`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                val useFixedClock = AtomicBoolean(true)
                val sharedClock: () -> Long = {
                    if (useFixedClock.get()) 1_700_000_000_000L else System.currentTimeMillis()
                }
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope,
                    timeProvider = sharedClock
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 5L,
                    timeProvider = sharedClock
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val delegateGateway = ControllerEngineGateway(controller)
                val injectExternalStopAtInternalStopEntry = AtomicBoolean(false)
                val gateway = object : MockEngineGateway {
                    override val runtimeState: StateFlow<MockRuntimeState> get() = delegateGateway.runtimeState
                    override val mayEmitAsyncStopConfig: Boolean get() = delegateGateway.mayEmitAsyncStopConfig
                    override fun startSingleMock(point: GeoPoint, config: MockConfig) =
                        delegateGateway.startSingleMock(point, config)
                    override fun updateSingleTarget(point: GeoPoint) =
                        delegateGateway.updateSingleTarget(point)
                    override fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) =
                        delegateGateway.startCruise(route, cruiseConfig, baseConfig)
                    override fun pauseCruise() = delegateGateway.pauseCruise()
                    override fun resumeCruise() = delegateGateway.resumeCruise()
                    override fun stopMock() {
                        if (injectExternalStopAtInternalStopEntry.compareAndSet(true, false)) {
                            coordinator.stopMock()
                        }
                        delegateGateway.stopMock()
                    }
                    override fun updateSpeed(speedMps: Double) = delegateGateway.updateSpeed(speedMps)
                    override fun updateJitter(enabled: Boolean) = delegateGateway.updateJitter(enabled)
                    override fun release() = delegateGateway.release()
                }

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = gateway,
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = {
                        WhitelistRepository(configRepo, InMemorySharedPreferences(), { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = ioExecutor)
                    },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = { spyDriver.stop() }
                )

                val drainingField = MainViewModel::class.java.getDeclaredField("isDrainingControlQueue").apply {
                    isAccessible = true
                }
                val lockField = MainViewModel::class.java.getDeclaredField("lifecycleLock").apply {
                    isAccessible = true
                }
                val lockObj = lockField.get(vm)!!
                fun isDraining(): Boolean = synchronized(lockObj) { drainingField.getBoolean(vm) }

                suspend fun waitUntilSettled(targetMode: MockMode) {
                    val deadline = System.currentTimeMillis() + 3000L
                    while ((isDraining() || vm.uiState.value.config.mode != targetMode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    kotlinx.coroutines.delay(40)
                }

                vm.setLoopMode(com.mockloc.domain.engine.LoopMode.LOOP)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9587, 116.4575), "B")

                var ghosts = 0
                for (fixedClock in listOf(true, false)) {
                    useFixedClock.set(fixedClock)
                    repeat(10) {
                        vm.setMockMode(MockMode.AUTO)
                        waitUntilSettled(MockMode.AUTO)
                        vm.onStartSingleClicked()
                        val sDeadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                            System.currentTimeMillis() < sDeadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)

                        injectExternalStopAtInternalStopEntry.set(true)
                        vm.setMockMode(MockMode.LSPOSED_ONLY)
                        waitUntilSettled(MockMode.LSPOSED_ONLY)
                        if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                            vm.uiState.value.runtimeState.status !is MockRunStatus.Idle ||
                            configRepo.currentConfig.enabled ||
                            spyDriver.isActive.get()
                        ) {
                            ghosts++
                        }
                    }

                    repeat(10) {
                        vm.setMockMode(MockMode.AUTO)
                        waitUntilSettled(MockMode.AUTO)
                        vm.onStartCruiseClicked()
                        val cDeadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                            System.currentTimeMillis() < cDeadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)

                        injectExternalStopAtInternalStopEntry.set(true)
                        vm.setMockMode(MockMode.LSPOSED_ONLY)
                        waitUntilSettled(MockMode.LSPOSED_ONLY)
                        if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                            vm.uiState.value.runtimeState.status !is MockRunStatus.Idle ||
                            configRepo.currentConfig.enabled ||
                            spyDriver.isActive.get()
                        ) {
                            ghosts++
                        }
                    }
                }
                assertEquals("Expected 0 ghost resurrections across 40 PROBE-BUG-1 rounds", 0, ghosts)
            }
        } finally {
            coordinatorScope.cancel()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }

    @Test
    fun `real clock concurrent external stopMock and UI anti-detect updates during mode switches 300 rounds produce zero ghost resurrections`() {
        val mainExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val controllerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val ioExecutor = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val attackExecutor = Executors.newSingleThreadExecutor()
        val coordinatorScope = CoroutineScope(SupervisorJob() + ioExecutor)
        Dispatchers.setMain(mainExecutor)
        try {
            runBlocking {
                // 100% 默认真实时钟、真实 Coordinator + ControllerEngineGateway（零网关包装）
                val configRepo = MockConfigRepository(
                    prefs = InMemorySharedPreferences(),
                    isWorldReadable = false,
                    ioScope = coordinatorScope
                )
                val spyDriver = SpyRootLocationDriver()
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = spyDriver,
                    permissionProvider = { PermissionCheckResult(true, true, true) },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = coordinatorScope,
                    tickIntervalMs = 5L
                )
                val controller = MockEngineController(
                    coordinator = coordinator,
                    permissionCheck = { PermissionCheckResult(true, true, true) },
                    dispatcher = controllerDispatcher
                )
                val wlRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = InMemorySharedPreferences(),
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = ioExecutor
                )

                val gateArmed = AtomicBoolean(false)
                val enteredLatchRef = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)
                val releaseLatchRef = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>(null)

                val vm = MainViewModel(
                    configRepository = configRepo,
                    engineGateway = ControllerEngineGateway(controller),
                    geocodingService = FakeGeocodingService(),
                    favoriteRepository = FavoriteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    routeRepository = RouteRepository(InMemorySharedPreferences(), ioExecutor, coordinatorScope),
                    whitelistRepositoryProvider = { wlRepo },
                    environmentDetector = EnvironmentDetectorGateway {
                        EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 15)
                    },
                    permissionProvider = PermissionStatusProvider { PermissionCheckResult(true, true, true) },
                    packageInstallChecker = PackageInstallChecker { false },
                    ioDispatcher = ioExecutor,
                    searchDebounceMs = 0L,
                    stopRootProvider = {
                        spyDriver.stop()
                        if (gateArmed.compareAndSet(true, false)) {
                            enteredLatchRef.get()?.countDown()
                            releaseLatchRef.get()?.await(2, TimeUnit.SECONDS)
                        }
                    }
                )

                val drainingField = MainViewModel::class.java.getDeclaredField("isDrainingControlQueue").apply {
                    isAccessible = true
                }
                val lockField = MainViewModel::class.java.getDeclaredField("lifecycleLock").apply {
                    isAccessible = true
                }
                val lockObj = lockField.get(vm)!!
                fun isDraining(): Boolean = synchronized(lockObj) { drainingField.getBoolean(vm) }

                suspend fun waitUntilMode(mode: MockMode, extraDelayMs: Long = 0L) {
                    val deadline = System.currentTimeMillis() + 3000L
                    while ((isDraining() || vm.uiState.value.config.mode != mode) &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(2)
                    }
                    if (extraDelayMs > 0L) {
                        kotlinx.coroutines.delay(extraDelayMs)
                    }
                }

                vm.setLoopMode(com.mockloc.domain.engine.LoopMode.LOOP)
                vm.appendWaypoint(GeoPoint(39.9087, 116.3975), "A")
                vm.appendWaypoint(GeoPoint(39.9587, 116.4575), "B")

                // Part A: PROBE-BUG-2 (200 轮：Single 100 + Cruise 100)
                var partAGhosts = 0
                for (round in 0 until 200) {
                    val isSingle = round < 100
                    vm.setMockMode(MockMode.AUTO)
                    waitUntilMode(MockMode.AUTO)
                    if (isSingle) {
                        vm.onStartSingleClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                    } else {
                        vm.onStartCruiseClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                    }

                    val delayMs = (round % 3).toLong()
                    val attackFuture = attackExecutor.submit {
                        if (delayMs > 0L) {
                            Thread.sleep(delayMs)
                        }
                        coordinator.stopMock()
                        wlRepo.togglePackage("com.test.p2.r$round")
                    }
                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    attackFuture.get(2, TimeUnit.SECONDS)
                    waitUntilMode(MockMode.LSPOSED_ONLY, extraDelayMs = 20L)

                    if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                        configRepo.currentConfig.enabled ||
                        spyDriver.isActive.get()
                    ) {
                        partAGhosts++
                    }
                }
                assertEquals("Expected 0 ghost resurrections in PROBE-BUG-2 (200 rounds)", 0, partAGhosts)

                // Part B: PROBE-BUG-3 (100 轮：Single 50 + Cruise 50，叠加主线程并发 setAntiDetectCellEnabled)
                var partBGhosts = 0
                for (round in 0 until 100) {
                    val isSingle = round < 50
                    vm.setMockMode(MockMode.AUTO)
                    waitUntilMode(MockMode.AUTO)
                    if (isSingle) {
                        vm.onStartSingleClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                    } else {
                        vm.onStartCruiseClicked()
                        val deadline = System.currentTimeMillis() + 2000L
                        while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                                vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                            System.currentTimeMillis() < deadline
                        ) {
                            kotlinx.coroutines.delay(2)
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                    }

                    val delayMs = (round % 3).toLong()
                    val attackFuture = attackExecutor.submit {
                        if (delayMs > 0L) {
                            Thread.sleep(delayMs)
                        }
                        coordinator.stopMock()
                        wlRepo.togglePackage("com.test.p3.r$round")
                    }
                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    withContext(mainExecutor) {
                        vm.setAntiDetectCellEnabled(round % 2 == 0)
                    }
                    attackFuture.get(2, TimeUnit.SECONDS)
                    waitUntilMode(MockMode.LSPOSED_ONLY, extraDelayMs = 20L)

                    if (coordinator.runtimeState.value.status !is MockRunStatus.Idle ||
                        vm.uiState.value.runtimeState.status !is MockRunStatus.Idle ||
                        configRepo.currentConfig.enabled ||
                        spyDriver.isActive.get()
                    ) {
                        partBGhosts++
                    }
                }
                assertEquals("Expected 0 ghost resurrections in PROBE-BUG-3 (100 rounds)", 0, partBGhosts)

                // Part C: 停机窗口内无外部停机，仅并发 setAntiDetectCellEnabled + togglePackage -> 0 误杀
                var partCFalseAborts = 0
                for (round in 0 until 20) {
                    val isSingle = round < 10
                    vm.setMockMode(MockMode.AUTO)
                    waitUntilMode(MockMode.AUTO)
                    if (isSingle) {
                        if (coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning) {
                            vm.onStartSingleClicked()
                            val deadline = System.currentTimeMillis() + 2000L
                            while ((coordinator.runtimeState.value.status !is MockRunStatus.SingleRunning ||
                                    vm.uiState.value.runtimeState.status !is MockRunStatus.SingleRunning) &&
                                System.currentTimeMillis() < deadline
                            ) {
                                kotlinx.coroutines.delay(2)
                            }
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.SingleRunning)
                    } else {
                        if (round == 10) {
                            vm.stopMock()
                            kotlinx.coroutines.delay(25)
                        }
                        if (coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning) {
                            vm.onStartCruiseClicked()
                            val deadline = System.currentTimeMillis() + 2000L
                            while ((coordinator.runtimeState.value.status !is MockRunStatus.CruiseRunning ||
                                    vm.uiState.value.runtimeState.status !is MockRunStatus.CruiseRunning) &&
                                System.currentTimeMillis() < deadline
                            ) {
                                kotlinx.coroutines.delay(2)
                            }
                        }
                        assertTrue(coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning)
                    }

                    val entered = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    enteredLatchRef.set(entered)
                    releaseLatchRef.set(release)
                    gateArmed.set(true)

                    vm.setMockMode(MockMode.LSPOSED_ONLY)
                    assertTrue("Timed out entering stopRootProvider in Part C", entered.await(2, TimeUnit.SECONDS))
                    withContext(mainExecutor) {
                        vm.setAntiDetectCellEnabled(round % 2 == 0)
                        vm.setAntiDetectWifiEnabled(round % 2 != 0)
                    }
                    repeat(2) { wlRepo.togglePackage("com.test.pc.r$round") }
                    release.countDown()

                    waitUntilMode(MockMode.LSPOSED_ONLY, extraDelayMs = 15L)
                    val expectedOk = if (isSingle) {
                        coordinator.runtimeState.value.status is MockRunStatus.SingleRunning &&
                            configRepo.currentConfig.enabled
                    } else {
                        coordinator.runtimeState.value.status is MockRunStatus.CruiseRunning &&
                            configRepo.currentConfig.enabled
                    }
                    if (!expectedOk) {
                        partCFalseAborts++
                    }
                }
                assertEquals("Expected 0 false aborts under harmless UI+whitelist updates", 0, partCFalseAborts)

                vm.stopMock()
                kotlinx.coroutines.delay(25)
            }
        } finally {
            coordinatorScope.cancel()
            attackExecutor.shutdownNow()
            mainExecutor.close()
            controllerDispatcher.close()
            ioExecutor.close()
            Dispatchers.setMain(testDispatcher)
        }
    }
}
