package com.mockloc.e2e.tier4

import android.app.AppOpsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
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
import com.mockloc.domain.engine.MotionSimulator
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.geocoding.DomesticGeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.e2e.factory.E2eFakeLocation
import com.mockloc.e2e.factory.E2eHookBackend
import com.mockloc.e2e.factory.E2eLbsSdkDoubles
import com.mockloc.e2e.factory.FakeGeocodingHttpRequester
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.FakeTestProviderBackend
import com.mockloc.e2e.factory.HiddenFieldsLocationStub
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toGeocodingHttpClient
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.factory.withMainDispatcher
import com.mockloc.e2e.infra.GeoCoordinateMathReference
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.hook.ClassLoaderHookHelper
import com.mockloc.hook.HookConfigStore
import com.mockloc.hook.LocationHook
import com.mockloc.hook.NetworkAntiDetectHook
import com.mockloc.hook.PrefsReader
import com.mockloc.hook.ProviderConfigFetcher
import com.mockloc.hook.ThirdPartyLbsSdkHook
import com.mockloc.hook.XposedInit
import com.mockloc.hook.cleaner.MockLocationCleaner
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.ipc.LocationConfigProvider
import com.mockloc.presentation.MainViewModel
import com.mockloc.presentation.WhitelistViewModel
import com.mockloc.presentation.gateway.ControllerEngineGateway
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.ui.EdgeToEdgeHelper
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.EnvironmentStatus
import com.mockloc.root.RootProviderManager
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.PermissionCheckResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import kotlin.math.abs

/**
 * Tier 4 真实端到端应用级场景测试 (T4-01..T4-10, 共 10 个端到端工作流)。
 *
 * 严格遵循 Clean-Slate 规范与真端到端串联要求，覆盖完整业务操作流与防风控对抗。
 */
class Tier4RealWorldWorkflowE2ETest {

    private class CleanupRegistry {
        private val viewModels = mutableListOf<ViewModel>()
        private val scopes = mutableListOf<CoroutineScope>()
        private val controllers = mutableListOf<MockEngineController>()
        private val locationHooks = mutableListOf<LocationHook>()
        private val networkHooks = mutableListOf<NetworkAntiDetectHook>()
        private val sdkHooks = mutableListOf<ThirdPartyLbsSdkHook>()
        private val classLoaderHelpers = mutableListOf<ClassLoaderHookHelper>()
        private val configStores = mutableListOf<HookConfigStore>()

        fun <T : ViewModel> track(vm: T): T = vm.also { viewModels.add(it) }
        fun track(scope: CoroutineScope): CoroutineScope = scope.also { scopes.add(it) }
        fun track(controller: MockEngineController): MockEngineController = controller.also { controllers.add(it) }
        fun track(hook: LocationHook): LocationHook = hook.also { locationHooks.add(it) }
        fun track(hook: NetworkAntiDetectHook): NetworkAntiDetectHook = hook.also { networkHooks.add(it) }
        fun track(hook: ThirdPartyLbsSdkHook): ThirdPartyLbsSdkHook = hook.also { sdkHooks.add(it) }
        fun track(helper: ClassLoaderHookHelper): ClassLoaderHookHelper = helper.also { classLoaderHelpers.add(it) }
        fun track(store: HookConfigStore): HookConfigStore = store.also { configStores.add(it) }

        fun cleanupAll() {
            runBlocking {
                withTimeoutOrNull(3_000L) {
                    viewModels.forEach { vm ->
                        runCatching { vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
                    }
                }
            }
            controllers.forEach { runCatching { it.release() } }
            runBlocking {
                withTimeoutOrNull(3_000L) {
                    scopes.forEach { scope ->
                        runCatching { scope.coroutineContext[Job]?.cancelAndJoin() }
                    }
                }
            }
            locationHooks.forEach { runCatching { it.clearForTest() } }
            networkHooks.forEach { runCatching { it.clearForTest() } }
            sdkHooks.forEach { runCatching { it.clearForTest() } }
            classLoaderHelpers.forEach { runCatching { it.resetForTest() } }
            configStores.forEach { runCatching { it.resetForTest() } }

            runCatching { LocationHook.shared.clearForTest() }
            LocationHook.currentAppContext = null
            LocationHook.currentTargetPackage = ""
            runCatching { NetworkAntiDetectHook.shared.clearForTest() }
            runCatching { ThirdPartyLbsSdkHook.shared.clearForTest() }
            runCatching { MockLocationCleaner.clearForTest() }
            runCatching { ClassLoaderHookHelper.shared.resetForTest() }
            runCatching { HookConfigStore.shared.resetForTest() }

            MockConfigRepository.setInstanceForTest(null)
            FavoriteRepository.setInstanceForTest(null)
            RouteRepository.setInstanceForTest(null)
            WhitelistRepository.setInstanceForTest(null)
            RootProviderManager.setInstanceForTest(null)
        }
    }

    private fun newCleanScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun grantedPermissions(): PermissionCheckResult = PermissionCheckResult(
        hasLocationPermission = true,
        hasNotificationPermission = true,
        canStartForegroundLocation = true,
        errorMessage = null
    )

    private fun deniedAllPermissions(): PermissionCheckResult = PermissionCheckResult(
        hasLocationPermission = false,
        hasNotificationPermission = false,
        canStartForegroundLocation = false,
        errorMessage = "缺少定位权限，禁止启动前台定位服务"
    )

    private fun buildMemoryPrefsReader(prefs: InMemorySharedPreferences): PrefsReader = object : PrefsReader {
        @Volatile
        private var lastSeenVersion = -1L

        override fun hasFileChanged(): Boolean = prefs.getLong(IpcProtocol.KEY_VERSION, 0L) != lastSeenVersion
        override fun reload() {
            lastSeenVersion = prefs.getLong(IpcProtocol.KEY_VERSION, 0L)
        }
        override fun readConfig(fallback: MockConfig): MockConfig = ConfigSyncSerializer.fromPrefs(prefs, fallback)
    }

    private fun buildBroadcastIntent(config: MockConfig, targetPackage: String): Intent {
        return MockLocationForegroundService.buildConfigBroadcastIntent(
            config = config,
            targetPackage = targetPackage,
            intentFactory = { action ->
                Mockito.mock(Intent::class.java).also { m ->
                    Mockito.`when`(m.action).thenReturn(action)
                    val capturedPkg = AtomicReference<String?>(null)
                    val capturedBundle = AtomicReference<Bundle?>(null)
                    Mockito.`when`(m.setPackage(Mockito.anyString())).thenAnswer { inv ->
                        capturedPkg.set(inv.getArgument(0))
                        m
                    }
                    Mockito.`when`(m.`package`).thenAnswer { capturedPkg.get() }
                    Mockito.`when`(m.putExtras(Mockito.any(Bundle::class.java))).thenAnswer { inv ->
                        capturedBundle.set(inv.getArgument(0))
                        m
                    }
                    Mockito.`when`(m.extras).thenAnswer { capturedBundle.get() }
                }
            },
            bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
        )
    }

    private fun buildLocationFactory(): (String, MockConfig) -> Location = { provider, cfg ->
        E2eFakeLocation(
            provider = provider,
            latitude = cfg.latitude,
            longitude = cfg.longitude,
            altitude = cfg.altitude,
            accuracy = cfg.accuracy,
            bearing = cfg.bearing,
            speed = cfg.speed,
            time = System.currentTimeMillis(),
            elapsedRealtimeNanos = System.nanoTime()
        ).asAndroidLocation()
    }

    private fun waitUntil(timeoutMs: Long = 3_500L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(15L)
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", predicate())
    }

    open class ProbeTelephonyCellCallback :
        TelephonyCallback(),
        TelephonyCallback.CellInfoListener {
        var lastCells: List<CellInfo>? = null
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            lastCells = cellInfo
        }
    }

    open class ProbeCellInfoCallback : TelephonyManager.CellInfoCallback() {
        var lastCells: List<CellInfo>? = null
        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
            lastCells = cellInfo
        }
    }

    open class ProbeGnssCallback : GnssStatus.Callback() {
        var started = false
        var satelliteCalled = false
        override fun onStarted() {
            started = true
        }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satelliteCalled = true
        }
    }

    open class ProbeNmeaListener : OnNmeaMessageListener {
        var lastNmea: String? = null
        override fun onNmeaMessage(message: String, timestamp: Long) {
            lastNmea = message
        }
    }

    open class StubLoadedApk(private val cl: ClassLoader) {
        open fun getClassLoader(): ClassLoader = cl
    }

    // ==================== T4-01 .. T4-10 ====================

    @Test
    fun `T4-01 urban commute trajectory cruise with anti-detect full chain`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val targetPkg = "com.tencent.work.checkin"
                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val favRepo = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope)
                val routeRepo = RouteRepository(routePrefs, Dispatchers.IO, ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepo, metaPrefs,
                    PackageQuerySource { listOf(InstalledAppRecord(targetPkg, "打卡应用", false)) },
                    prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO
                )
                whitelistRepo.saveSelectedPackages(setOf(targetPkg))

                // 目标进程 Hook 装配
                val hookStore = cleanup.track(
                    HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) }, throttleMs = 0L)
                )
                val backend = E2eHookBackend()
                val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
                Mockito.`when`(sanitizedWifi.bssid).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)
                Mockito.`when`(sanitizedWifi.ssid).thenReturn(NetworkAntiDetectHook.SANITIZED_SSID)

                val locHook = cleanup.track(LocationHook(backend, hookStore, locationFactory = buildLocationFactory()))
                val netHook = cleanup.track(
                    NetworkAntiDetectHook(
                        backend = backend,
                        configProvider = hookStore,
                        wifiInfoSanitizer = { _, _ -> sanitizedWifi },
                        sdkIntProvider = { 35 }
                    )
                )
                val testCl = object : ClassLoader(javaClass.classLoader) {}
                locHook.installSystemHooks(testCl, targetPkg)
                MockLocationCleaner.installHooks(testCl, backend, sdkInt = 35) {
                    hookStore.currentConfig().isEnabledForPackage(targetPkg)
                }
                netHook.install(testCl, targetPkg)

                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { cfg, pkgs ->
                        if (targetPkg in pkgs) {
                            hookStore.onBroadcastReceived(buildBroadcastIntent(cfg, targetPkg))
                            locHook.onConfigChanged(hookStore.currentConfig(), targetPkg)
                        }
                    },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 20L,
                    motionSimulator = MotionSimulator(kotlin.random.Random(101))
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )
                val requester = FakeGeocodingHttpRequester().apply { stubTimeout(Regex(".*")) }
                val geocoding = DomesticGeocodingService(requester.toGeocodingHttpClient(), OfflineFallbackGeocodingService(), Dispatchers.IO)

                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = geocoding,
                        favoriteRepository = favRepo,
                        routeRepository = routeRepo,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentStatus(true, 93, false, "None", true, true, 1, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { it == targetPkg },
                        ioDispatcher = Dispatchers.IO,
                        searchDebounceMs = 10L
                    )
                )

                // 1. 搜索天安门与鸟巢，构建 3 航点通勤路线并保存
                vm.onSearchQueryChanged("天安门")
                vm.submitSearchNow()
                waitUntil { vm.uiState.value.searchResults.isNotEmpty() }
                val p1 = vm.uiState.value.searchResults.first().toGeoPoint()
                vm.onSearchQueryChanged("鸟巢")
                vm.submitSearchNow()
                waitUntil { vm.uiState.value.searchResults.any { it.name.contains("鸟巢") } }
                val p3 = vm.uiState.value.searchResults.first { it.name.contains("鸟巢") }.toGeoPoint()
                val p2 = TestCoordinates.BEIJING_FORBIDDEN_CITY.toGeoPoint()

                val longCommuteRoute = PlannedRoute(
                    id = "commute_beijing",
                    name = "北京早高峰通勤",
                    waypoints = listOf(Waypoint(p1, "天安门"), Waypoint(p2, "故宫"), Waypoint(p3, "鸟巢"))
                )
                routeRepo.saveRoute(longCommuteRoute).getOrThrow()

                // 独立验证 CruiseEngine + MotionSimulator 在长途通勤上的 AR-1 速度波动与 OU 微漂移界限
                val mathSim = MotionSimulator(kotlin.random.Random(2026))
                val engine = CruiseEngine(mathSim)
                engine.loadRoute(
                    longCommuteRoute,
                    CruiseConfig(speedMps = 4.5, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = true, speedNoiseRatio = 0.25, enableMicroDrift = true, maxDriftRadiusMeters = 4.0)
                )
                engine.start()
                for (step in 0 until 20) {
                    val out = engine.advanceStep(1.0)
                    assertTrue("Speed ${out.speedMps} out of [0.5v, 1.5v]", out.speedMps in (4.5 * 0.5)..(4.5 * 1.5))
                    val driftSample = mathSim.applyMicroDrift(out.point, 4.0, true, 1.0)
                    val driftDist = SpatialMath.calculateHaversineDistance(out.point, driftSample)
                    assertTrue("OU drift $driftDist exceeded 4.05m", driftDist <= 4.05)
                }

                // 2. 在 MainViewModel 上使用紧凑 3 航点路线走完 WALK -> 4.5m/s -> Pause -> Resume -> ONE_WAY COMPLETED -> Restart
                val compactRoute = PlannedRoute(
                    id = "commute_compact",
                    name = "通勤闭环实测",
                    waypoints = listOf(
                        Waypoint(p1, "起点"),
                        Waypoint(GeoPoint(p1.latitude + 0.00012, p1.longitude, p1.altitude), "中继"),
                        Waypoint(GeoPoint(p1.latitude + 0.00024, p1.longitude, p1.altitude), "终点")
                    )
                )
                vm.loadRouteToDraft(compactRoute)
                vm.setMicroDriftEnabled(true)
                vm.setAntiDetectCellEnabled(true)
                vm.setAntiDetectWifiEnabled(true)
                vm.setAntiDetectGnssEnabled(true)
                vm.setLoopMode(LoopMode.ONE_WAY)
                vm.selectSpeedPreset(SpeedPreset.WALK)
                vm.onStartCruiseClicked()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning }

                // 调速至 4.5 m/s -> 暂停 -> 恢复
                vm.updateCustomSpeed(4.5)
                waitUntil { abs(vm.uiState.value.runtimeState.speedMps - 4.5) < 0.8 }
                vm.pauseCruise()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.CruisePaused }
                vm.resumeCruise()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning }

                // 在运行态抽检目标应用反检测探针
                val lmMock = Mockito.mock(LocationManager::class.java)
                val appLoc = backend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { null }.result as Location
                assertFalse(appLoc.isMock)
                assertFalse(appLoc.isFromMockProvider)
                val tmMock = Mockito.mock(TelephonyManager::class.java)
                assertTrue((backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tmMock) { listOf("Cell") }.result as List<*>).isEmpty())
                val wmMock = Mockito.mock(WifiManager::class.java)
                assertTrue((backend.invokeMethod(WifiManager::class.java, "getScanResults", wmMock) { listOf("AP") }.result as List<*>).isEmpty())
                val wifiMock = Mockito.mock(WifiInfo::class.java)
                assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiMock) { "aa:bb:cc:dd:ee:ff" }.result)
                val cr = Mockito.mock(ContentResolver::class.java)
                assertEquals("0", backend.invokeStatic(Settings.Secure::class.java, "getString", arrayOf(cr, "mock_location")) { "1" }.result)
                val appOps = Mockito.mock(AppOpsManager::class.java)
                assertEquals(AppOpsManager.MODE_IGNORED, backend.invokeMethod(AppOpsManager::class.java, "checkOpNoThrow", appOps, arrayOf("android:mock_location", 10001, targetPkg)) { AppOpsManager.MODE_ALLOWED }.result)

                // 提速走完 ONE_WAY -> COMPLETED 并重启复位
                vm.updateCustomSpeed(120.0)
                waitUntil(timeoutMs = 5_000L) { vm.uiState.value.isOneWayCompleted }
                assertTrue(vm.uiState.value.isOneWayCompleted)
                vm.updateCustomSpeed(5.0)
                vm.restartCruiseFromCompleted()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning }
                assertTrue(vm.uiState.value.runtimeState.progressFraction < 0.9f)

                vm.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-02 WeChat AMap Baidu GMS multi-SDK isolation and automatic coordinate mapping`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val installedPkgs = setOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap", "com.unlisted.nav")
                val installChecker = PackageInstallChecker { it in installedPkgs }
                val whitelistRepo = WhitelistRepository(
                    configRepo, metaPrefs,
                    PackageQuerySource {
                        listOf(
                            InstalledAppRecord("com.tencent.mm", "微信", false),
                            InstalledAppRecord("com.autonavi.minimap", "高德地图", false),
                            InstalledAppRecord("com.baidu.BaiduMap", "百度地图", false),
                            InstalledAppRecord("com.unlisted.nav", "未选导航", false)
                        )
                    },
                    prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO
                )
                // 通过 RecommendedScopeRepository 仅将已安装推荐应用导入白名单（不含 com.unlisted.nav）
                val importRes = RecommendedScopeRepository.addAllInstalledToWhitelist(installChecker, whitelistRepo)
                assertTrue(importRes.totalSelectedPackages.containsAll(listOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap")))
                assertFalse("com.unlisted.nav" in importRes.totalSelectedPackages)

                // 为 4 个独立进程准备 HookConfigStore
                val targetProcessStores = linkedMapOf<String, HookConfigStore>()
                for (pkg in installedPkgs) {
                    targetProcessStores[pkg] = cleanup.track(
                        HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) }, throttleMs = 0L)
                    )
                }

                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { cfg, _ ->
                        targetProcessStores.forEach { (pkg, store) ->
                            store.onBroadcastReceived(buildBroadcastIntent(cfg, pkg))
                        }
                    },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 50L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )
                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope),
                        routeRepository = RouteRepository(routePrefs, Dispatchers.IO, ioScope),
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentStatus(true, 93, false, "None", true, true, 3, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = installChecker,
                        ioDispatcher = Dispatchers.IO
                    )
                )

                val lujiazui = TestCoordinates.SHANGHAI_LUJIAZUI
                vm.applyCoordinateInputs(lujiazui.latitude.toString(), lujiazui.longitude.toString(), lujiazui.altitude.toString(), "2.0")
                vm.onStartSingleClicked()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }

                val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(lujiazui.latitude, lujiazui.longitude)
                val expectedBd = GeoCoordinateMathReference.wgs84ToBd09(lujiazui.latitude, lujiazui.longitude)

                // 1. 微信进程 (Tencent SDK -> GCJ-02)
                run {
                    val backend = E2eHookBackend().also { E2eLbsSdkDoubles.registerTencent(it) }
                    val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, targetProcessStores["com.tencent.mm"]!!))
                    sdkHook.installForClassLoader(object : ClassLoader(javaClass.classLoader) {}, "com.tencent.mm")
                    val mgr = E2eLbsSdkDoubles.TencentLocationManager()
                    val listener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
                    val reg = backend.invokeMethod(E2eLbsSdkDoubles.TencentLocationManager::class.java, "requestLocationUpdates", mgr, arrayOf("r", listener))
                    (reg.args[1] as E2eLbsSdkDoubles.TencentLocationListener).onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 1, "ERR")
                    assertEquals(expectedGcj.lat, listener.lastLocation!!.getLatitude(), 1e-5)
                    assertEquals(expectedGcj.lon, listener.lastLocation!!.getLongitude(), 1e-5)
                    assertEquals(0, listener.lastError)
                    assertEquals("", listener.lastLocation!!.getAddress())
                    assertFalse(listener.lastLocation!!.isMockGps())
                }

                // 2. 高德进程 (AMap SDK -> GCJ-02)
                run {
                    val backend = E2eHookBackend().also { E2eLbsSdkDoubles.registerAmap(it) }
                    val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, targetProcessStores["com.autonavi.minimap"]!!))
                    sdkHook.installForClassLoader(object : ClassLoader(javaClass.classLoader) {}, "com.autonavi.minimap")
                    val client = E2eLbsSdkDoubles.AMapLocationClient()
                    val listener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
                    backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", client, arrayOf(listener))
                    backend.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", listener, arrayOf(E2eLbsSdkDoubles.AMapLocation())) {
                        listener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
                    }
                    assertEquals(expectedGcj.lat, listener.lastLocation!!.getLatitude(), 1e-5)
                    assertEquals(expectedGcj.lon, listener.lastLocation!!.getLongitude(), 1e-5)
                    assertEquals(0, listener.lastLocation!!.getErrorCode())
                    assertEquals("", listener.lastLocation!!.getAddress())
                }

                // 3. 百度进程 (Baidu SDK -> BD-09)
                run {
                    val backend = E2eHookBackend().also { E2eLbsSdkDoubles.registerBaidu(it) }
                    val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, targetProcessStores["com.baidu.BaiduMap"]!!))
                    sdkHook.installForClassLoader(object : ClassLoader(javaClass.classLoader) {}, "com.baidu.BaiduMap")
                    val client = E2eLbsSdkDoubles.LocationClient()
                    val listener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
                    backend.invokeMethod(E2eLbsSdkDoubles.LocationClient::class.java, "registerLocationListener", client, arrayOf(listener))
                    backend.invokeMethod(E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java, "onReceiveLocation", listener, arrayOf(E2eLbsSdkDoubles.BDLocation())) {
                        listener.onReceiveLocation(it[0] as E2eLbsSdkDoubles.BDLocation); null
                    }
                    assertEquals(expectedBd.lat, listener.lastLocation!!.getLatitude(), 1e-5)
                    assertEquals(expectedBd.lon, listener.lastLocation!!.getLongitude(), 1e-5)
                    assertEquals(61, listener.lastLocation!!.getLocType())
                    assertEquals("", listener.lastLocation!!.getAddrStr())
                }

                // 4. GMS 通道与未选应用 com.unlisted.nav 对比
                run {
                    // 已选包名下的 GMS Fused 输出 WGS-84
                    val backendGms = E2eHookBackend().also { E2eLbsSdkDoubles.registerGms(it) }
                    val gmsHook = cleanup.track(ThirdPartyLbsSdkHook(backendGms, targetProcessStores["com.tencent.mm"]!!))
                    gmsHook.installForClassLoader(object : ClassLoader(javaClass.classLoader) {}, "com.tencent.mm")
                    val gmsClient = E2eLbsSdkDoubles.FusedLocationProviderClient()
                    val gmsCb = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
                    backendGms.invokeMethod(E2eLbsSdkDoubles.FusedLocationProviderClient::class.java, "requestLocationUpdates", gmsClient, arrayOf("r", gmsCb, null))
                    val rawGms = E2eFakeLocation(latitude = 22.0, longitude = 114.0).asAndroidLocation()
                    backendGms.invokeMethod(E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java, "onLocationResult", gmsCb, arrayOf(E2eLbsSdkDoubles.LocationResult(listOf(rawGms)))) {
                        gmsCb.onLocationResult(it[0] as E2eLbsSdkDoubles.LocationResult); null
                    }
                    assertEquals(lujiazui.latitude, gmsCb.lastResult!!.getLastLocation()!!.latitude, 1e-5)
                    assertEquals(lujiazui.longitude, gmsCb.lastResult!!.getLastLocation()!!.longitude, 1e-5)

                    // 未选应用 com.unlisted.nav 完全透传真实物理坐标
                    val unlistedStore = targetProcessStores["com.unlisted.nav"]!!
                    val backendUnlisted = E2eHookBackend().also { E2eLbsSdkDoubles.registerAmap(it) }
                    val unlistedHook = cleanup.track(ThirdPartyLbsSdkHook(backendUnlisted, unlistedStore))
                    unlistedHook.installForClassLoader(object : ClassLoader(javaClass.classLoader) {}, "com.unlisted.nav")
                    val c = E2eLbsSdkDoubles.AMapLocationClient()
                    val l = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
                    backendUnlisted.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", c, arrayOf(l))
                    val realPhysical = E2eLbsSdkDoubles.AMapLocation().apply { setLatitude(23.1291); setLongitude(113.2644); setErrorCode(0) }
                    backendUnlisted.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", l, arrayOf(realPhysical)) {
                        l.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
                    }
                    assertEquals(23.1291, l.lastLocation!!.getLatitude(), 1e-6)
                    assertEquals(113.2644, l.lastLocation!!.getLongitude(), 1e-6)
                    assertEquals("真实物理高德街道1号", l.lastLocation!!.getAddress())
                }

                vm.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-03 cold start permission recovery and process kill recovery via L1 and L3`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val scope1 = cleanup.track(newCleanScope())

                val permGranted = AtomicBoolean(false)
                val permProvider = { if (permGranted.get()) grantedPermissions() else deniedAllPermissions() }

                val repo1 = MockConfigRepository(configPrefs, isWorldReadable = true, ioScope = scope1, debounceMs = 10L)
                val whitelistRepo = WhitelistRepository(
                    repo1, metaPrefs, { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO
                )
                whitelistRepo.saveSelectedPackages(setOf("com.tencent.mm"))

                val coordinator1 = MockEngineCoordinator(
                    configRepository = repo1,
                    permissionProvider = permProvider,
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = scope1,
                    tickIntervalMs = 40L
                )
                val controller1 = cleanup.track(
                    MockEngineController(coordinator1, permProvider, Dispatchers.Unconfined)
                )
                val vm1 = cleanup.track(
                    MainViewModel(
                        configRepository = repo1,
                        engineGateway = ControllerEngineGateway(controller1),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = FavoriteRepository(favPrefs, Dispatchers.IO, scope1),
                        routeRepository = RouteRepository(routePrefs, Dispatchers.IO, scope1),
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentStatus(true, 93, false, "None", permGranted.get(), permGranted.get(), 1, 14)
                        },
                        permissionProvider = PermissionStatusProvider { permProvider() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO
                    )
                )

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val eventJob = scope1.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    vm1.events.collect { events.add(it) }
                }

                // 1. 首次无权限点击开启 -> 安全拒绝并发射权限引导
                val sz = TestCoordinates.SHENZHEN_NANSHAN
                vm1.applyCoordinateInputs(sz.latitude.toString(), sz.longitude.toString(), sz.altitude.toString(), "2.5")
                vm1.onStartSingleClicked()
                waitUntil { events.any { it is MainUiEvent.RequestPermissions } }
                assertTrue(vm1.uiState.value.runtimeState.status is MockRunStatus.Idle)

                // 2. 授予权限 -> 一键进入 SingleRunning 并持久化至 SafePrefs
                permGranted.set(true)
                vm1.onPermissionsResult(locationGranted = true, notificationGranted = true)
                waitUntil { vm1.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }
                assertEquals(sz.latitude, repo1.currentConfig.latitude, 1e-6)
                assertTrue(repo1.currentConfig.enabled)

                // 3. 强杀主控进程，且 L3 ContentProvider 被 <queries> 阻断
                eventJob.cancel()
                withTimeoutOrNull(3_000L) {
                    vm1.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
                controller1.release()
                scope1.cancel()
                MockConfigRepository.setInstanceForTest(null)

                val blockedStore = cleanup.track(
                    HookConfigStore(
                        prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) },
                        providerFetcher = object : ProviderConfigFetcher {
                            override fun fetchGlobalConfig(context: Context): Bundle? {
                                throw SecurityException("<queries> blocked")
                            }
                        },
                        throttleMs = 0L
                    )
                )
                val backend = E2eHookBackend()
                val locHook = cleanup.track(LocationHook(backend, blockedStore, locationFactory = buildLocationFactory()))
                locHook.installSystemHooks(object : ClassLoader(javaClass.classLoader) {}, "com.tencent.mm")

                val ctx = buildTestAppContext(packageName = "com.tencent.mm")
                assertFalse(blockedStore.fetchFromL3ProviderSafe(ctx))
                val l1Restored = blockedStore.getConfig(ctx, forceRefresh = true)
                assertTrue(l1Restored.enabled)
                assertEquals(sz.latitude, l1Restored.latitude, 1e-6)

                val lmMock = Mockito.mock(LocationManager::class.java)
                val targetLoc = backend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { null }.result as Location
                assertEquals(sz.latitude, targetLoc.latitude, 1e-6)

                // 4. 主控重建并允许 L3：从持久化仓储恢复而非易失内存
                val scope2 = cleanup.track(newCleanScope())
                val repo2 = MockConfigRepository(configPrefs, isWorldReadable = true, ioScope = scope2, debounceMs = 10L)
                val provider2 = LocationConfigProvider(
                    repositoryResolver = { repo2 },
                    bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
                )
                val l3Bundle = provider2.call(IpcProtocol.METHOD_GET_CONFIG, "com.tencent.mm", null)
                assertTrue(l3Bundle.getBoolean(IpcProtocol.RESULT_OK))
                val parsedL3 = ConfigSyncSerializer.fromBundle(l3Bundle)
                assertTrue(parsedL3.enabled)
                assertEquals(sz.latitude, parsedL3.latitude, 1e-6)
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-04 packed app early hook dynamic ClassLoader attach and live config refresh v1 to v3`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.packed.dynamic"
            val p1 = TestCoordinates.BEIJING_TIANANMEN
            val p2 = TestCoordinates.SHANGHAI_LUJIAZUI
            val p3 = TestCoordinates.SHENZHEN_NANSHAN

            val v1 = MockConfig(
                enabled = true,
                latitude = p1.latitude,
                longitude = p1.longitude,
                targetPackages = setOf(targetPkg),
                updatedAtMs = 1_700_000_001_000L,
                version = 1L
            )
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), v1).commit()

            val fakeClock = AtomicLong(1_000L)
            val store = cleanup.track(
                HookConfigStore(
                    prefsReaderFactory = { buildMemoryPrefsReader(prefs) },
                    elapsedRealtimeProvider = { fakeClock.get() },
                    throttleMs = 400L
                )
            )
            val backend = E2eHookBackend(declaredOnly = true)
            backend.registerClass("android.app.LoadedApk", StubLoadedApk::class.java)
            // 早期壳加载阶段：高德 SDK 与基站尚未解压
            backend.blockedClassNames.addAll(
                listOf(
                    "com.amap.api.location.AMapLocationClient",
                    "com.amap.api.location.AMapLocationListener",
                    "com.amap.api.location.AMapLocation",
                    "android.telephony.TelephonyManager",
                    "android.telephony.TelephonyCallback"
                )
            )

            val locHook = cleanup.track(LocationHook(backend, store, locationFactory = buildLocationFactory()))
            val netHook = cleanup.track(NetworkAntiDetectHook(backend, store))
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend))

            val shellCl = object : ClassLoader(javaClass.classLoader) {}
            XposedInit().handlePackageInternal(
                packageName = targetPkg,
                classLoader = shellCl,
                backend = backend,
                configStore = store,
                locationHook = locHook,
                networkHook = netHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 1. attachBaseContext 之前早期调用 getLastKnownLocation -> v1 生效
            val lmMock = Mockito.mock(LocationManager::class.java)
            val earlyLoc = backend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { null }.result as Location
            assertEquals(p1.latitude, earlyLoc.latitude, 1e-6)

            // 2. 动态加载插件 DexClassLoader -> 补装高德 SDK & TelephonyCallback Hook
            backend.blockedClassNames.clear()
            E2eLbsSdkDoubles.registerAmap(backend)
            val pluginCl = object : ClassLoader(javaClass.classLoader) {}
            val loadedApk = StubLoadedApk(pluginCl)
            backend.invokeMethod(StubLoadedApk::class.java, "getClassLoader", loadedApk) { loadedApk.getClassLoader() }

            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", amapClient, arrayOf(amapListener))

            val tmMock = Mockito.mock(TelephonyManager::class.java)
            val cellCb = ProbeTelephonyCellCallback()
            backend.invokeMethod(TelephonyManager::class.java, "registerTelephonyCallback", tmMock, arrayOf(Executor { it.run() }, cellCb))
            backend.invokeMethod(ProbeTelephonyCellCallback::class.java, "onCellInfoChanged", cellCb, arrayOf(mutableListOf(Mockito.mock(CellInfo::class.java)))) { args ->
                @Suppress("UNCHECKED_CAST")
                val listArg = (args[0] as List<CellInfo>).toMutableList()
                cellCb.onCellInfoChanged(listArg)
                null
            }
            assertTrue(cellCb.lastCells!!.isEmpty())

            // 3. 运行期连续更新配置 v1 -> v2 (L1) -> v3 (L2)，验证无 isConfigSynced 锁死
            val v2 = v1.copy(latitude = p2.latitude, longitude = p2.longitude, updatedAtMs = 1_700_000_002_000L, version = 2L)
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), v2).commit()
            fakeClock.set(2_000L)
            val locV2 = backend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { null }.result as Location
            assertEquals(p2.latitude, locV2.latitude, 1e-6)

            val v3 = v2.copy(latitude = p3.latitude, longitude = p3.longitude, updatedAtMs = 1_700_000_003_000L, version = 3L)
            assertTrue(store.onBroadcastReceived(buildBroadcastIntent(v3, targetPkg)))

            backend.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", amapListener, arrayOf(E2eLbsSdkDoubles.AMapLocation())) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
            }
            val expectedGcjV3 = GeoCoordinateMathReference.wgs84ToGcj02(p3.latitude, p3.longitude)
            assertEquals(expectedGcjV3.lat, amapListener.lastLocation!!.getLatitude(), 1e-5)
            assertEquals(expectedGcjV3.lon, amapListener.lastLocation!!.getLongitude(), 1e-5)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-05 pure ROOT_ONLY mode KernelSU multi-provider injection failure diagnosis and cleanup`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val suGranted = AtomicBoolean(false)
                val shell = FakeShellExecutor()
                val shellExec = object : com.mockloc.root.ShellExecutor {
                    override suspend fun execRoot(command: String, timeoutMs: Long) =
                        if (suGranted.get() || command == "id" || command.contains("ksud") || command.contains("su -v")) {
                            when {
                                command == "id" -> com.mockloc.root.ShellResult(0, "uid=0(root)", "")
                                command.contains("ksud") || command.contains("su -v") -> com.mockloc.root.ShellResult(0, "KernelSU 0.9.5", "")
                                else -> shell.execute(command, timeoutMs).let {
                                    com.mockloc.root.ShellResult(it.exitCode, it.stdout, it.stderr, it.timedOut, it.isSuccess)
                                }
                            }
                        } else {
                            com.mockloc.root.ShellResult(1, "", "su denied by user")
                        }

                    override suspend fun execShell(command: String, timeoutMs: Long) =
                        com.mockloc.root.ShellResult(1, "", "")

                    override suspend fun hasRootAccess(timeoutMs: Long) = true

                    override suspend fun detectRootProvider(timeoutMs: Long) = "KernelSU"
                }

                val testBackend = FakeTestProviderBackend(initialHasMockAppOp = false)
                testBackend.throwOnAdd["gps"] = SecurityException("not allowed to perform MOCK_LOCATION")

                val ctx = buildTestAppContext(packageName = "com.mockloc")
                val rootManager = RootProviderManager(
                    context = ctx,
                    shellExecutor = shellExec,
                    backendFactory = { testBackend },
                    sdkIntProvider = { 35 },
                    userIdProvider = { 0 },
                    uidProvider = { 10100 },
                    ioDispatcher = Dispatchers.IO
                )

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    rootDriver = rootManager,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 40L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )

                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope),
                        routeRepository = RouteRepository(routePrefs, Dispatchers.IO, ioScope),
                        whitelistRepositoryProvider = {
                            WhitelistRepository(configRepo, metaPrefs, { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO)
                        },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentDetector.detectEnvironment(ctx, shellExec, sdkInt = 35)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO,
                        stopRootProvider = { rootManager.stop() }
                    )
                )

                waitUntil { vm.uiState.value.environmentStatus != null }
                assertFalse(vm.uiState.value.environmentStatus!!.isLSPosedActive)
                assertTrue(vm.uiState.value.environmentStatus!!.isRootAvailable)
                assertEquals("KernelSU", vm.uiState.value.environmentStatus!!.rootProviderName)

                vm.setMockMode(MockMode.ROOT_ONLY)

                // 1. su 拒绝 -> 显式进入 Error 不吞异常
                vm.onStartSingleClicked()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Error }
                val errReason = (vm.uiState.value.runtimeState.status as MockRunStatus.Error).reason
                assertTrue(errReason.contains("Root"))

                // 2. 恢复 su 授权并清除 addTestProvider 异常 -> gps/network/fused (sdkInt=35) 注入生效
                suGranted.set(true)
                testBackend.throwOnAdd.clear()
                testBackend.hasMockAppOp.set(true)
                val lhasa = TestCoordinates.LHASA_POTALA
                vm.applyCoordinateInputs(lhasa.latitude.toString(), lhasa.longitude.toString(), lhasa.altitude.toString(), "3.0")
                vm.onStartSingleClicked()

                waitUntil {
                    vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning &&
                        testBackend.lastLocations.size == 3
                }
                assertEquals(35, testBackend.registeredProviders["gps"])
                assertEquals(35, testBackend.registeredProviders["network"])
                assertEquals(35, testBackend.registeredProviders["fused"])
                assertEquals(lhasa.latitude, testBackend.lastLocations["gps"]!!.latitude, 1e-4)

                // 3. 停止后全部 Provider 注销清理
                vm.stopMock()
                waitUntil {
                    vm.uiState.value.runtimeState.status is MockRunStatus.Idle &&
                        testBackend.registeredProviders.isEmpty()
                }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-06 offline geocoding fallback direct coord parsing favorite edit delete and use`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val requester = FakeGeocodingHttpRequester().apply { stubTimeout(Regex(".*")) }
                val geocoding = DomesticGeocodingService(
                    httpClient = requester.toGeocodingHttpClient(),
                    fallbackService = OfflineFallbackGeocodingService(),
                    ioDispatcher = Dispatchers.IO
                )
                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val favRepo = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope)
                val routeRepo = RouteRepository(routePrefs, Dispatchers.IO, ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepo, metaPrefs, { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO
                )
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 40L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )

                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = geocoding,
                        favoriteRepository = favRepo,
                        routeRepository = routeRepo,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentStatus(true, 93, false, "None", true, true, 0, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO,
                        searchDebounceMs = 10L
                    )
                )

                // 1. 断网搜索 "杭州西湖" -> 平滑降级离线地标库
                vm.onSearchQueryChanged("杭州西湖")
                vm.submitSearchNow()
                waitUntil { !vm.uiState.value.isSearching && vm.uiState.value.searchResults.isNotEmpty() }
                assertTrue(vm.uiState.value.isSearchOfflineFallback)
                val westLake = vm.uiState.value.searchResults.first()
                assertTrue(westLake.name.contains("西湖"))

                // 2. 直接经纬度 "39.9087, 116.3975" -> 免联网秒解析
                val countBeforeDirect = requester.requestCount.get()
                vm.onSearchQueryChanged("39.9087, 116.3975")
                vm.submitSearchNow()
                waitUntil { !vm.uiState.value.isSearching && vm.uiState.value.searchResults.any { it.source == "direct_wgs84" } }
                assertEquals(countBeforeDirect, requester.requestCount.get())

                // 3. 未知地名 -> 安全空结果 + 提示不崩溃
                vm.onSearchQueryChanged("不存在的火星地名9999")
                vm.submitSearchNow()
                waitUntil { !vm.uiState.value.isSearching && vm.uiState.value.searchInfoMessage != null }
                assertTrue(vm.uiState.value.searchResults.isEmpty())

                // 4. 收藏杭州西湖 -> 编辑重命名 -> 删除旧收藏 -> 一键选用并启动 -> 重启持久化
                vm.selectSearchResult(westLake)
                vm.addFavorite("杭州西湖", "浙江省杭州市")
                waitUntil { vm.uiState.value.favorites.any { it.name == "杭州西湖" } }
                val addedFav = vm.uiState.value.favorites.first { it.name == "杭州西湖" }
                val otherFav = vm.uiState.value.favorites.firstOrNull { it.id != addedFav.id }

                vm.updateFavorite(addedFav.id, "周末出游点", "杭州西湖风景名胜区")
                waitUntil { vm.uiState.value.favorites.any { it.id == addedFav.id && it.name == "周末出游点" } }
                if (otherFav != null) {
                    vm.removeFavorite(otherFav.id)
                    waitUntil { vm.uiState.value.favorites.none { it.id == otherFav.id } }
                }

                val edited = vm.uiState.value.favorites.first { it.id == addedFav.id }
                vm.useFavoriteAsSingle(edited)
                vm.onStartSingleClicked()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }
                assertEquals(edited.latitude, vm.uiState.value.runtimeState.currentPoint!!.latitude, 1e-5)

                // 重建 FavoriteRepository 确认持久化保留
                val favRepoReloaded = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope)
                val reloadedList = favRepoReloaded.getAll()
                assertTrue(reloadedList.any { it.name == "周末出游点" && it.address == "杭州西湖风景名胜区" })
                if (otherFav != null) {
                    assertTrue(reloadedList.none { it.id == otherFav.id })
                }

                vm.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-07 overseas roaming Tokyo and New York keep WGS-84 identity across SDKs and transoceanic cruise`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.roaming.app"
            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            E2eLbsSdkDoubles.registerAll(backend)
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, store))
            sdkHook.installForClassLoader(javaClass.classLoader, targetPkg)

            val tencentMgr = E2eLbsSdkDoubles.TencentLocationManager()
            val tencentListener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
            val regTencent = backend.invokeMethod(E2eLbsSdkDoubles.TencentLocationManager::class.java, "requestLocationUpdates", tencentMgr, arrayOf("r", tencentListener))
            val tencentProxy = regTencent.args[1] as E2eLbsSdkDoubles.TencentLocationListener

            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", amapClient, arrayOf(amapListener))

            val bdClient = E2eLbsSdkDoubles.LocationClient()
            val bdListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.LocationClient::class.java, "registerLocationListener", bdClient, arrayOf(bdListener))

            val gmsClient = E2eLbsSdkDoubles.FusedLocationProviderClient()
            val gmsCb = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
            backend.invokeMethod(E2eLbsSdkDoubles.FusedLocationProviderClient::class.java, "requestLocationUpdates", gmsClient, arrayOf("r", gmsCb, null))

            // 1. 境内北京天安门 -> GCJ-02 / BD-09 偏转
            val bj = TestCoordinates.BEIJING_TIANANMEN
            assertFalse(CoordinateConverter.isOutOfChina(bj.latitude, bj.longitude))
            store.onBroadcastReceived(
                buildBroadcastIntent(
                    MockConfig(enabled = true, latitude = bj.latitude, longitude = bj.longitude, targetPackages = setOf(targetPkg), updatedAtMs = 100L, version = 1L),
                    targetPkg
                )
            )
            backend.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", amapListener, arrayOf(E2eLbsSdkDoubles.AMapLocation())) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
            }
            assertTrue(
                GeoCoordinateMathReference.haversineDistanceMeters(
                    bj.latitude, bj.longitude,
                    amapListener.lastLocation!!.getLatitude(), amapListener.lastLocation!!.getLongitude()
                ) > 100.0
            )

            // 2. 切换至东京站与纽约时代广场 -> 严格恒等 WGS-84
            val overseasPoints = listOf(TestCoordinates.TOKYO_STATION, TestCoordinates.NEW_YORK_TIMES_SQUARE)
            var ver = 2L
            for (pt in overseasPoints) {
                assertTrue(CoordinateConverter.isOutOfChina(pt.latitude, pt.longitude))
                store.onBroadcastReceived(
                    buildBroadcastIntent(
                        MockConfig(enabled = true, latitude = pt.latitude, longitude = pt.longitude, altitude = pt.altitude, targetPackages = setOf(targetPkg), updatedAtMs = 100L + ver, version = ver),
                        targetPkg
                    )
                )
                ver++

                tencentProxy.onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 0, "OK")
                assertEquals(pt.latitude, tencentListener.lastLocation!!.getLatitude(), 1e-8)
                assertEquals(pt.longitude, tencentListener.lastLocation!!.getLongitude(), 1e-8)

                backend.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", amapListener, arrayOf(E2eLbsSdkDoubles.AMapLocation())) {
                    amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
                }
                assertEquals(pt.latitude, amapListener.lastLocation!!.getLatitude(), 1e-8)
                assertEquals(pt.longitude, amapListener.lastLocation!!.getLongitude(), 1e-8)

                backend.invokeMethod(E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java, "onReceiveLocation", bdListener, arrayOf(E2eLbsSdkDoubles.BDLocation())) {
                    bdListener.onReceiveLocation(it[0] as E2eLbsSdkDoubles.BDLocation); null
                }
                assertEquals(pt.latitude, bdListener.lastLocation!!.getLatitude(), 1e-8)
                assertEquals(pt.longitude, bdListener.lastLocation!!.getLongitude(), 1e-8)

                val rawGms = E2eFakeLocation(latitude = 0.0, longitude = 0.0).asAndroidLocation()
                backend.invokeMethod(E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java, "onLocationResult", gmsCb, arrayOf(E2eLbsSdkDoubles.LocationResult(listOf(rawGms)))) {
                    gmsCb.onLocationResult(it[0] as E2eLbsSdkDoubles.LocationResult); null
                }
                assertEquals(pt.latitude, gmsCb.lastResult!!.getLastLocation()!!.latitude, 1e-8)
                assertEquals(pt.longitude, gmsCb.lastResult!!.getLastLocation()!!.longitude, 1e-8)
            }

            // 3. 东京 -> 纽约跨大洋巡航与 SpatialMath 校验（无除零/NaN）
            val transoceanicRoute = PlannedRoute(
                id = "tokyo_to_ny",
                name = "东京-纽约跨洋航线",
                waypoints = listOf(
                    Waypoint(TestCoordinates.TOKYO_STATION.toGeoPoint(), "东京"),
                    Waypoint(TestCoordinates.NEW_YORK_TIMES_SQUARE.toGeoPoint(), "纽约")
                )
            )
            val totalDist = transoceanicRoute.totalDistanceMeters()
            assertTrue(totalDist > 10_000_000.0 && totalDist.isFinite())
            val engine = CruiseEngine(MotionSimulator(kotlin.random.Random(3)))
            engine.loadRoute(transoceanicRoute, CruiseConfig(speedMps = 500_000.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false))
            engine.start()
            while (!engine.isFinished()) {
                val out = engine.advanceStep(1.0)
                assertTrue(out.point.latitude.isFinite() && out.point.longitude.isFinite())
                assertTrue(out.bearingDeg.isFinite())
            }
            assertEquals(TestCoordinates.NEW_YORK_TIMES_SQUARE.latitude, engine.currentSnapshot().point.latitude, 1e-4)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-08 dynamic whitelist updates recommended scope guide and live L2 unhook restoration`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val installed = setOf("com.tencent.mm", "com.autonavi.minimap", "com.sys.location.helper")
                val checker = PackageInstallChecker { it in installed }

                // 1. 推荐作用域列表验证
                val scopes = RecommendedScopeRepository.getRecommendedScopes(checker)
                assertEquals(16, scopes.size)
                assertTrue(StaticManifestAndResourceChecker.CORE_RECOMMENDED_SCOPE_PACKAGES.all { pkg -> scopes.any { it.packageName == pkg } })

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val targetStores = mapOf(
                    "com.tencent.mm" to cleanup.track(HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) }, throttleMs = 0L)),
                    "com.autonavi.minimap" to cleanup.track(HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) }, throttleMs = 0L))
                )
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = metaPrefs,
                    packageQuerySource = PackageQuerySource {
                        listOf(
                            InstalledAppRecord("com.tencent.mm", "微信", false),
                            InstalledAppRecord("com.autonavi.minimap", "高德地图", false),
                            InstalledAppRecord("com.baidu.BaiduMap", "百度地图", false),
                            InstalledAppRecord("com.sys.location.helper", "系统定位服务", true)
                        )
                    },
                    onPackagesSynced = { cfg, _ ->
                        targetStores.forEach { (pkg, st) ->
                            st.onBroadcastReceived(buildBroadcastIntent(cfg, pkg))
                        }
                    },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )

                val wlVm = cleanup.track(
                    WhitelistViewModel(
                        whitelistRepositoryProvider = { whitelistRepo },
                        packageInstallChecker = checker,
                        ioDispatcher = Dispatchers.IO
                    )
                )
                waitUntil { !wlVm.uiState.value.isLoading && wlVm.uiState.value.allApps.isNotEmpty() }

                // 2. 搜索 "微信" 勾选 -> 开启显示系统应用全选 -> 清空 -> 再选微信与高德及一个系统应用
                wlVm.setIncludeSystemApps(false)
                wlVm.updateSearchQuery("微信")
                assertEquals(1, wlVm.uiState.value.filteredApps.size)
                wlVm.togglePackage("com.tencent.mm")
                waitUntil { "com.tencent.mm" in wlVm.uiState.value.selectedPackages }

                wlVm.updateSearchQuery("")
                wlVm.setIncludeSystemApps(true)
                wlVm.selectAllFiltered()
                waitUntil { wlVm.uiState.value.selectedPackages.size == 4 }

                wlVm.clearAll()
                waitUntil { wlVm.uiState.value.selectedPackages.isEmpty() }

                wlVm.togglePackage("com.tencent.mm")
                wlVm.togglePackage("com.autonavi.minimap")
                wlVm.togglePackage("com.sys.location.helper")
                waitUntil { wlVm.uiState.value.selectedPackages.size == 3 }

                // 关闭显示系统应用时，已选中的系统应用不被隐藏
                wlVm.setIncludeSystemApps(false)
                assertTrue(wlVm.uiState.value.filteredApps.any { it.packageName == "com.sys.location.helper" })

                // 开启模拟，验证微信与高德均处于模拟态
                val shanghai = TestCoordinates.SHANGHAI_LUJIAZUI
                val activeCfg = configRepo.updateImmediate {
                    it.copy(enabled = true, latitude = shanghai.latitude, longitude = shanghai.longitude)
                }
                targetStores.forEach { (pkg, st) -> st.onBroadcastReceived(buildBroadcastIntent(activeCfg, pkg)) }

                val amapBackend = E2eHookBackend()
                val amapLocHook = cleanup.track(LocationHook(amapBackend, targetStores["com.autonavi.minimap"]!!, locationFactory = buildLocationFactory()))
                amapLocHook.installSystemHooks(object : ClassLoader(javaClass.classLoader) {}, "com.autonavi.minimap")
                val lmMock = Mockito.mock(LocationManager::class.java)
                val realPhysical = E2eFakeLocation(latitude = 39.0, longitude = 116.0).asAndroidLocation()

                val mockedBefore = amapBackend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { realPhysical }.result as Location
                assertEquals(shanghai.latitude, mockedBefore.latitude, 1e-5)

                // 3. 运行中移除 com.autonavi.minimap -> L2 广播即时同步，高德立即放行真实位置
                wlVm.togglePackage("com.autonavi.minimap")
                waitUntil {
                    "com.autonavi.minimap" !in wlVm.uiState.value.selectedPackages &&
                        !targetStores["com.autonavi.minimap"]!!.isActiveFor("com.autonavi.minimap")
                }

                val restoredAfter = amapBackend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { realPhysical }.result as Location
                assertEquals(39.0, restoredAfter.latitude, 1e-6)
                assertTrue(targetStores["com.tencent.mm"]!!.isActiveFor("com.tencent.mm"))
                assertFalse(targetStores["com.autonavi.minimap"]!!.isActiveFor("com.autonavi.minimap"))
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-09 Android 14-17 comprehensive anti-risk opaque-box probe audit`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.risk.audit.target"
            val tiananmen = TestCoordinates.BEIJING_TIANANMEN
            val cfg = MockConfig(
                enabled = true,
                mode = MockMode.LSPOSED_ONLY,
                latitude = tiananmen.latitude,
                longitude = tiananmen.longitude,
                altitude = tiananmen.altitude,
                accuracy = 2.5f,
                antiDetectCellEnabled = true,
                antiDetectWifiEnabled = true,
                antiDetectGnssEnabled = true,
                targetPackages = setOf(targetPkg),
                updatedAtMs = 1_700_000_100_000L,
                version = 100L
            )
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfg).commit()

            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend(declaredOnly = false)
            E2eLbsSdkDoubles.registerAll(backend)

            val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
            Mockito.`when`(sanitizedWifi.bssid).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)
            Mockito.`when`(sanitizedWifi.ssid).thenReturn(NetworkAntiDetectHook.SANITIZED_SSID)
            Mockito.`when`(sanitizedWifi.macAddress).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)

            val locHook = cleanup.track(LocationHook(backend, store, locationFactory = buildLocationFactory()))
            val netHook = cleanup.track(NetworkAntiDetectHook(backend, store, wifiInfoSanitizer = { _, _ -> sanitizedWifi }, sdkIntProvider = { 35 }))
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend))

            XposedInit().handlePackageInternal(
                packageName = targetPkg,
                classLoader = object : ClassLoader(javaClass.classLoader) {},
                backend = backend,
                configStore = store,
                locationHook = locHook,
                networkHook = netHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 探针 1: Location.isMock / isFromMockProvider / mFieldsMask / extras
            val lmMock = Mockito.mock(LocationManager::class.java)
            val loc = backend.invokeMethod(LocationManager::class.java, "getLastKnownLocation", lmMock, arrayOf("gps")) { null }.result as Location
            assertFalse(loc.isMock)
            assertFalse(loc.isFromMockProvider)
            assertFalse(loc.extras?.containsKey("mockLocation") == true)
            assertFalse(loc.extras?.containsKey("mock") == true)
            assertFalse(loc.extras?.containsKey("isFromMockProvider") == true)
            val hidden = HiddenFieldsLocationStub()
            MockLocationCleaner.cleanHiddenFields(hidden)
            assertEquals(0, hidden.mFieldsMask and 16)
            assertFalse(hidden.mIsFromMockProvider)
            val sdk35Cl = object : ClassLoader(javaClass.classLoader) {}
            MockLocationCleaner.installHooks(sdk35Cl, backend, sdkInt = 35) {
                store.currentConfig().isEnabledForPackage(targetPkg)
            }
            assertEquals(false, backend.invokeMethod(Location::class.java, "isMock", loc) { true }.result)
            assertEquals(false, backend.invokeMethod(Location::class.java, "isFromMockProvider", loc) { true }.result)

            // 探针 2: Settings.Secure("mock_location") & AppOpsManager
            val cr = Mockito.mock(ContentResolver::class.java)
            assertEquals("0", backend.invokeStatic(Settings.Secure::class.java, "getString", arrayOf(cr, "mock_location")) { "1" }.result)
            assertEquals(0, backend.invokeStatic(Settings.Secure::class.java, "getInt", arrayOf(cr, "mock_location", 1)) { 1 }.result)
            val appOps = Mockito.mock(AppOpsManager::class.java)
            assertEquals(AppOpsManager.MODE_IGNORED, backend.invokeMethod(AppOpsManager::class.java, "checkOpNoThrow", appOps, arrayOf("android:mock_location", 1001, targetPkg)) { AppOpsManager.MODE_ALLOWED }.result)
            assertEquals(AppOpsManager.MODE_IGNORED, backend.invokeMethod(AppOpsManager::class.java, "unsafeCheckOpNoThrow", appOps, arrayOf("android:mock_location", 1001, targetPkg)) { AppOpsManager.MODE_ALLOWED }.result)
            assertEquals(AppOpsManager.MODE_IGNORED, backend.invokeMethod(AppOpsManager::class.java, "noteOpNoThrow", appOps, arrayOf("android:mock_location", 1001, targetPkg, null, null)) { AppOpsManager.MODE_ALLOWED }.result)
            assertEquals(false, backend.invokeMethod(AppOpsManager::class.java, "isOpActive", appOps, arrayOf("android:mock_location", 1001, targetPkg)) { true }.result)

            // 探针 3: TelephonyManager / WifiManager / NetworkCapabilities / WifiInfo / GnssStatus / NMEA
            val tmMock = Mockito.mock(TelephonyManager::class.java)
            assertTrue((backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tmMock) { listOf(Mockito.mock(CellInfo::class.java)) }.result as List<*>).isEmpty())
            assertNotNull(backend.invokeMethod(TelephonyManager::class.java, "getCellLocation", tmMock) { null }.result)
            val cellInfoCb = ProbeCellInfoCallback()
            backend.invokeMethod(TelephonyManager::class.java, "requestCellInfoUpdate", tmMock, arrayOf(Executor { it.run() }, cellInfoCb))
            backend.invokeMethod(ProbeCellInfoCallback::class.java, "onCellInfo", cellInfoCb, arrayOf(mutableListOf(Mockito.mock(CellInfo::class.java)))) { args ->
                @Suppress("UNCHECKED_CAST")
                val listArg = (args[0] as List<CellInfo>).toMutableList()
                cellInfoCb.onCellInfo(listArg)
                null
            }
            assertTrue(cellInfoCb.lastCells!!.isEmpty())

            val wmMock = Mockito.mock(WifiManager::class.java)
            assertTrue((backend.invokeMethod(WifiManager::class.java, "getScanResults", wmMock) { listOf("RealSSID") }.result as List<*>).isEmpty())
            assertEquals(true, backend.invokeMethod(WifiManager::class.java, "startScan", wmMock) { false }.result)
            val realWifi = Mockito.mock(WifiInfo::class.java)
            val connWifi = backend.invokeMethod(WifiManager::class.java, "getConnectionInfo", wmMock) { realWifi }.result as WifiInfo
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, connWifi.bssid)
            val ncMock = Mockito.mock(NetworkCapabilities::class.java)
            val ncWifi = backend.invokeMethod(NetworkCapabilities::class.java, "getTransportInfo", ncMock) { realWifi }.result as WifiInfo
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, ncWifi.bssid)
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, backend.invokeMethod(WifiInfo::class.java, "getBSSID", realWifi) { "aa:bb:cc:dd:ee:ff" }.result)
            assertEquals(NetworkAntiDetectHook.SANITIZED_SSID, backend.invokeMethod(WifiInfo::class.java, "getSSID", realWifi) { "\"CorpWifi\"" }.result)
            assertEquals(null, backend.invokeMethod(WifiInfo::class.java, "getApMldMacAddress", realWifi) { "11:22:33:44:55:66" }.result)

            val gnssCb = ProbeGnssCallback()
            backend.invokeMethod(LocationManager::class.java, "registerGnssStatusCallback", lmMock, arrayOf(Executor { it.run() }, gnssCb))
            assertTrue(gnssCb.started)
            val gnssSatRes = backend.invokeMethod(ProbeGnssCallback::class.java, "onSatelliteStatusChanged", gnssCb, arrayOf(Mockito.mock(GnssStatus::class.java))) {
                gnssCb.onSatelliteStatusChanged(it[0] as GnssStatus); null
            }
            assertTrue(gnssSatRes.earlyReturned)
            assertFalse(gnssCb.satelliteCalled)

            val nmeaListener = ProbeNmeaListener()
            backend.invokeMethod(LocationManager::class.java, "addNmeaListener", lmMock, arrayOf(nmeaListener))
            backend.invokeMethod(ProbeNmeaListener::class.java, "onNmeaMessage", nmeaListener, arrayOf("\$GPGGA,REAL,0,0*00", 1L)) {
                nmeaListener.onNmeaMessage(it[0] as String, it[1] as Long); null
            }
            assertTrue(nmeaListener.lastNmea!!.startsWith("\$GPGGA,"))
            assertFalse(nmeaListener.lastNmea!!.contains("REAL"))

            // 探针 4: 第三方 SDK 专属风控与旧地址清零
            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", amapClient, arrayOf(amapListener))
            backend.invokeMethod(E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java, "onLocationChanged", amapListener, arrayOf(E2eLbsSdkDoubles.AMapLocation())) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation); null
            }
            assertEquals(0, amapListener.lastLocation!!.getErrorCode())
            assertEquals("", amapListener.lastLocation!!.getAddress())
            assertFalse(amapListener.lastLocation!!.isMock())
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T4-10 extreme UI inputs dark mode EdgeToEdge and APK delivery verification`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 40L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )
                val whitelistRepo = WhitelistRepository(
                    configRepo, metaPrefs, { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO
                )
                val gateway = ControllerEngineGateway(controller)

                fun createVm() = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = gateway,
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope),
                        routeRepository = RouteRepository(routePrefs, Dispatchers.IO, ioScope),
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentStatus(true, 93, true, "Magisk", true, true, 0, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO
                    )
                )

                val vm1 = createVm()
                val events = CopyOnWriteArrayList<MainUiEvent>()
                val evJob = ioScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    vm1.events.collect { events.add(it) }
                }

                // 1. 极端非法经纬度、空路线巡航、非法速度/抖动半径均防崩溃
                for (badLat in listOf("", "abc", "91.0", "-90.5", "NaN")) {
                    val res = vm1.applyCoordinateInputs(badLat, "116.3975", "50.0", "3.0")
                    assertEquals(null, res)
                    assertNotNull(vm1.uiState.value.latError)
                }
                for (badLon in listOf("", "xyz", "181.0", "-180.5", "Infinity")) {
                    val res = vm1.applyCoordinateInputs("39.9087", badLon, "50.0", "3.0")
                    assertEquals(null, res)
                    assertNotNull(vm1.uiState.value.lonError)
                }

                vm1.clearDraftWaypoints()
                vm1.onStartCruiseClicked()
                waitUntil { events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("航点") } }

                vm1.updateCustomSpeed(-5.0)
                vm1.updateCustomSpeed(0.0)
                vm1.updateCustomSpeed(9999.0)
                assertFalse(vm1.updateJitterRadiusInput("-1.0"))
                assertFalse(vm1.updateJitterRadiusInput("600.0"))
                assertNotNull(vm1.uiState.value.jitterRadiusError)

                // 快速切换模式并重建 ViewModel 验证状态立即同步
                vm1.setMockMode(MockMode.LSPOSED_ONLY)
                vm1.setMockMode(MockMode.ROOT_ONLY)
                vm1.applyCoordinateInputs("31.2397", "121.4998", "15.0", "2.5")
                vm1.onStartSingleClicked()
                waitUntil { vm1.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }

                val vm2 = createVm()
                waitUntil { vm2.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }
                assertEquals(MockMode.ROOT_ONLY, vm2.uiState.value.config.mode)
                assertTrue(vm2.uiState.value.runtimeState.status is MockRunStatus.SingleRunning)
                assertEquals(31.2397, vm2.uiState.value.runtimeState.currentPoint!!.latitude, 1e-4)

                vm2.stopMock()
                waitUntil { vm2.uiState.value.runtimeState.status is MockRunStatus.Idle }
                evJob.cancel()

                // 2. WhitelistViewModel 极端搜索字符防崩溃
                val wlVm = cleanup.track(
                    WhitelistViewModel({ whitelistRepo }, PackageInstallChecker { false }, Dispatchers.IO)
                )
                waitUntil { !wlVm.uiState.value.isLoading }
                wlVm.updateSearchQuery("[com.(*?")
                assertTrue(wlVm.uiState.value.filteredApps.isEmpty())

                // 3. EdgeToEdgeHelper 与 values-night / Manifest / 资源强审计
                val mockView = Mockito.mock(View::class.java)
                val lp = ViewGroup.MarginLayoutParams(100, 100)
                Mockito.`when`(mockView.layoutParams).thenReturn(lp)
                EdgeToEdgeHelper.applyTopMarginInsets(mockView)
                EdgeToEdgeHelper.applyTopPaddingInsets(mockView)
                EdgeToEdgeHelper.applyBottomPaddingInsets(mockView, includeIme = true)
                EdgeToEdgeHelper.applyRightMarginInsets(mockView)

                val mainJavaDir = WorkspacePathResolver.mainJavaDir()
                val mainActivitySrc = File(mainJavaDir, "presentation/MainActivity.kt").readText(Charsets.UTF_8)
                val whitelistActivitySrc = File(mainJavaDir, "presentation/WhitelistActivity.kt").readText(Charsets.UTF_8)
                assertTrue(mainActivitySrc.contains("EdgeToEdgeHelper"))
                assertTrue(mainActivitySrc.contains("enableEdgeToEdge"))
                assertTrue(whitelistActivitySrc.contains("EdgeToEdgeHelper"))
                assertTrue(whitelistActivitySrc.contains("enableEdgeToEdge"))

                val resDir = WorkspacePathResolver.resDir()
                val resReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resDir)
                assertTrue("values-night/resources violations: ${resReport.violations}", resReport.passed)

                val manifestXml = WorkspacePathResolver.manifestFile().readText(Charsets.UTF_8)
                val manifestReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(manifestXml)
                assertTrue("Manifest violations: ${manifestReport.violations}", manifestReport.passed)

                // 4. 若本地已生成 app-debug.apk 则校验内部 DEX / AndroidManifest.xml / assets/xposed_init（禁止递归调用 ./gradlew）
                val appDir = WorkspacePathResolver.resolveAppDir()
                val apkFile = File(appDir, "build/outputs/apk/debug/app-debug.apk")
                if (apkFile.isFile) {
                    assertTrue("APK file must be non-empty", apkFile.length() > 0L)
                    ZipFile(apkFile).use { zip ->
                        val entryNames = zip.entries().asSequence().map { it.name }.toSet()
                        assertTrue("APK missing classes*.dex", entryNames.any { it.matches(Regex("^classes\\d*\\.dex$")) })
                        assertTrue("APK missing AndroidManifest.xml", "AndroidManifest.xml" in entryNames)
                        val xposedEntry = zip.getEntry("assets/xposed_init")
                        assertNotNull("APK missing assets/xposed_init", xposedEntry)
                        val initText = zip.getInputStream(xposedEntry).bufferedReader(Charsets.UTF_8).readText().trim()
                        assertEquals(XposedInit::class.java.name, initText)
                    }
                }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }
}
