package com.mockloc.e2e.tier3

import android.app.AppOpsManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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
import com.mockloc.e2e.factory.InMemoryPreferencesOpener
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toCruiseConfig
import com.mockloc.e2e.factory.toGeocodingHttpClient
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toPlannedRoute
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
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.PermissionCheckResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.abs

/**
 * Tier 3 跨功能 Pairwise 组合集成测试 (T3-01..T3-20, 共 20 个场景)。
 *
 * 严格遵循 Clean-Slate 规范与真端到端串联要求，全量使用真实生产模块与 e2e/factory 基础设施。
 */
class Tier3PairwiseIntegrationE2ETest {

    // ==================== 内部通用清理与辅助 ====================

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

    private fun buildMemoryPrefsReader(prefs: InMemorySharedPreferences): PrefsReader = object : PrefsReader {
        @Volatile
        private var lastSeenVersion = -1L

        override fun hasFileChanged(): Boolean {
            val ver = prefs.getLong(IpcProtocol.KEY_VERSION, 0L)
            return ver != lastSeenVersion
        }

        override fun reload() {
            lastSeenVersion = prefs.getLong(IpcProtocol.KEY_VERSION, 0L)
        }

        override fun readConfig(fallback: MockConfig): MockConfig {
            return ConfigSyncSerializer.fromPrefs(prefs, fallback)
        }
    }

    private fun buildBroadcastIntent(config: MockConfig, targetPackage: String): Intent {
        val bundle = StatefulFakeBundle()
        ConfigSyncSerializer.toBundle(config, bundle.asAndroidBundle())
        val androidBundle = bundle.asAndroidBundle()
        val intent = Mockito.mock(Intent::class.java)
        Mockito.`when`(intent.action).thenReturn(IpcProtocol.ACTION_CONFIG_CHANGED)
        Mockito.`when`(intent.`package`).thenReturn(targetPackage)
        Mockito.`when`(intent.extras).thenReturn(androidBundle)
        // 验证生产 buildConfigBroadcastIntent 同样能构建等价 Intent
        val builtByService = MockLocationForegroundService.buildConfigBroadcastIntent(
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
        assertEquals(IpcProtocol.ACTION_CONFIG_CHANGED, builtByService.action)
        assertEquals(targetPackage, builtByService.`package`)
        return builtByService
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

    private fun waitUntil(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(15L)
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", predicate())
    }

    private fun grantedPermissions(): PermissionCheckResult = PermissionCheckResult(
        hasLocationPermission = true,
        hasNotificationPermission = true,
        canStartForegroundLocation = true,
        errorMessage = null
    )

    private fun deniedLocationPermissions(): PermissionCheckResult = PermissionCheckResult(
        hasLocationPermission = false,
        hasNotificationPermission = true,
        canStartForegroundLocation = false,
        errorMessage = "缺少定位权限，禁止启动前台定位服务"
    )

    private class TestTelephonyCellCallback :
        TelephonyCallback(),
        TelephonyCallback.CellInfoListener {
        var receivedCells: List<CellInfo>? = null
        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            receivedCells = cellInfo
        }
    }

    private class TestGnssCallback : android.location.GnssStatus.Callback() {
        var startedCount = 0
        var firstFixTtff = -1
        var satelliteCallbackInvoked = false
        override fun onStarted() {
            startedCount++
        }
        override fun onFirstFix(ttffMillis: Int) {
            firstFixTtff = ttffMillis
        }
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            satelliteCallbackInvoked = true
        }
    }

    private class TestNmeaListener : android.location.OnNmeaMessageListener {
        var lastMessage: String? = null
        override fun onNmeaMessage(message: String, timestamp: Long) {
            lastMessage = message
        }
    }

    open class FakeLoadedApk(private val cl: ClassLoader) {
        open fun getClassLoader(): ClassLoader = cl
    }

    // ==================== T3-01 .. T3-20 ====================

    @Test
    fun `T3-01 config persistence IPC L1-L2-L3 and multi third-party SDK coordinate mapping`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val repoScope = cleanup.track(newCleanScope())
            val repo = MockConfigRepository(prefs, isWorldReadable = true, ioScope = repoScope, debounceMs = 10L)

            val tiananmen = TestCoordinates.BEIJING_TIANANMEN
            val savedCfg = repo.updateImmediate {
                it.copy(
                    enabled = true,
                    mode = MockMode.LSPOSED_ONLY,
                    latitude = tiananmen.latitude,
                    longitude = tiananmen.longitude,
                    altitude = tiananmen.altitude,
                    accuracy = 2.0f,
                    targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap")
                )
            }

            // L3 Provider 验证
            val provider = LocationConfigProvider(
                repositoryResolver = { repo },
                bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
            )
            val appCtx = buildTestAppContext(packageName = "com.tencent.mm")

            // L1 + L2 + L3 接入 HookConfigStore
            val store = cleanup.track(
                HookConfigStore(
                    prefsReaderFactory = { buildMemoryPrefsReader(prefs) },
                    providerFetcher = object : ProviderConfigFetcher {
                        override fun fetchGlobalConfig(context: Context): Bundle? {
                            return provider.call(IpcProtocol.METHOD_GET_CONFIG, context.packageName, null)
                        }
                    },
                    throttleMs = 0L
                )
            )
            val backend = E2eHookBackend(declaredOnly = false)
            E2eLbsSdkDoubles.registerAll(backend)

            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = store, locationFactory = buildLocationFactory())
            )
            val networkHook = cleanup.track(NetworkAntiDetectHook(backend = backend, configProvider = store))
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend = backend, configProvider = store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend = backend))

            XposedInit().handlePackageInternal(
                packageName = "com.tencent.mm",
                classLoader = javaClass.classLoader,
                backend = backend,
                configStore = store,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 先用纯 L3 视图验证 LocationConfigProvider 独立同步，再推进 L2 广播升级至 v2Cfg
            val l3OnlyStore = cleanup.track(
                HookConfigStore(
                    prefsReaderFactory = { null },
                    providerFetcher = object : ProviderConfigFetcher {
                        override fun fetchGlobalConfig(context: Context): Bundle? {
                            return provider.call(IpcProtocol.METHOD_GET_CONFIG, context.packageName, null)
                        }
                    },
                    throttleMs = 0L
                )
            )
            assertTrue(l3OnlyStore.fetchFromL3ProviderSafe(appCtx))
            val v2Cfg = repo.updateImmediate { it.copy(accuracy = 1.5f) }
            assertTrue(store.onBroadcastReceived(buildBroadcastIntent(v2Cfg, "com.tencent.mm")))
            assertEquals(tiananmen.latitude, store.currentConfig().latitude, 1e-6)
            assertEquals(tiananmen.longitude, store.currentConfig().longitude, 1e-6)

            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(tiananmen.latitude, tiananmen.longitude)
            val expectedBd = GeoCoordinateMathReference.wgs84ToBd09(tiananmen.latitude, tiananmen.longitude)

            // 1. 原生 LocationManager -> WGS-84
            val lmMock = Mockito.mock(LocationManager::class.java)
            val nativeRes = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                lmMock,
                arrayOf("gps")
            ) { null }
            val nativeLoc = nativeRes.result as Location
            assertEquals(tiananmen.latitude, nativeLoc.latitude, 1e-5)
            assertEquals(tiananmen.longitude, nativeLoc.longitude, 1e-5)

            // 2. 腾讯 SDK -> GCJ-02
            val tencentMgr = E2eLbsSdkDoubles.TencentLocationManager()
            val tencentListener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
            val regTencent = backend.invokeMethod(
                E2eLbsSdkDoubles.TencentLocationManager::class.java,
                "requestLocationUpdates",
                tencentMgr,
                arrayOf("req", tencentListener)
            )
            val proxiedTencent = regTencent.args[1] as E2eLbsSdkDoubles.TencentLocationListener
            proxiedTencent.onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 1, "FAIL")
            assertEquals(expectedGcj.lat, tencentListener.lastLocation!!.getLatitude(), 1e-5)
            assertEquals(expectedGcj.lon, tencentListener.lastLocation!!.getLongitude(), 1e-5)
            assertEquals(0, tencentListener.lastError)

            // 3. 高德 SDK -> GCJ-02
            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                amapClient,
                arrayOf(amapListener)
            )
            val amapLoc = E2eLbsSdkDoubles.AMapLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                amapListener,
                arrayOf(amapLoc)
            ) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                null
            }
            assertEquals(expectedGcj.lat, amapListener.lastLocation!!.getLatitude(), 1e-5)
            assertEquals(expectedGcj.lon, amapListener.lastLocation!!.getLongitude(), 1e-5)
            assertEquals(0, amapListener.lastLocation!!.getErrorCode())

            // 4. 百度 SDK (bd09ll) -> BD-09
            val bdClient = E2eLbsSdkDoubles.LocationClient()
            val bdListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.LocationClient::class.java,
                "registerLocationListener",
                bdClient,
                arrayOf(bdListener)
            )
            val bdLoc = E2eLbsSdkDoubles.BDLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                "onReceiveLocation",
                bdListener,
                arrayOf(bdLoc)
            ) {
                bdListener.onReceiveLocation(it[0] as E2eLbsSdkDoubles.BDLocation)
                null
            }
            assertEquals(expectedBd.lat, bdListener.lastLocation!!.getLatitude(), 1e-5)
            assertEquals(expectedBd.lon, bdListener.lastLocation!!.getLongitude(), 1e-5)
            assertEquals(61, bdListener.lastLocation!!.getLocType())

            // 5. GMS Fused -> WGS-84
            val gmsClient = E2eLbsSdkDoubles.FusedLocationProviderClient()
            val gmsCallback = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
            backend.invokeMethod(
                E2eLbsSdkDoubles.FusedLocationProviderClient::class.java,
                "requestLocationUpdates",
                gmsClient,
                arrayOf("req", gmsCallback, null)
            )
            val gmsRawLoc = E2eFakeLocation(latitude = 22.0, longitude = 114.0).asAndroidLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java,
                "onLocationResult",
                gmsCallback,
                arrayOf(E2eLbsSdkDoubles.LocationResult(listOf(gmsRawLoc)))
            ) {
                gmsCallback.onLocationResult(it[0] as E2eLbsSdkDoubles.LocationResult)
                null
            }
            val gmsOut = gmsCallback.lastResult!!.getLastLocation()!!
            assertEquals(tiananmen.latitude, gmsOut.latitude, 1e-5)
            assertEquals(tiananmen.longitude, gmsOut.longitude, 1e-5)
            assertFalse(gmsOut.isMock)
            assertTrue(savedCfg.version > 0L)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-02 overseas New York coordinates keep identity with full anti-detect and cleaner`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val ny = TestCoordinates.NEW_YORK_TIMES_SQUARE
            val prefs = InMemorySharedPreferences()
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                latitude = ny.latitude,
                longitude = ny.longitude,
                altitude = ny.altitude,
                antiDetectCellEnabled = true,
                antiDetectWifiEnabled = true,
                antiDetectGnssEnabled = true,
                targetPackages = setOf("com.overseas.app"),
                version = 12L
            ).toMockConfig()
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfg).commit()

            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend(declaredOnly = false)
            E2eLbsSdkDoubles.registerAll(backend)

            val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
            Mockito.`when`(sanitizedWifi.bssid).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)
            Mockito.`when`(sanitizedWifi.ssid).thenReturn(NetworkAntiDetectHook.SANITIZED_SSID)

            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = store, locationFactory = buildLocationFactory())
            )
            val networkHook = cleanup.track(
                NetworkAntiDetectHook(
                    backend = backend,
                    configProvider = store,
                    wifiInfoSanitizer = { _, _ -> sanitizedWifi },
                    sdkIntProvider = { 35 }
                )
            )
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend = backend, configProvider = store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend = backend))

            XposedInit().handlePackageInternal(
                packageName = "com.overseas.app",
                classLoader = javaClass.classLoader,
                backend = backend,
                configStore = store,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 1. 原生位置保持纽约 WGS-84 且 4 层标记清除
            val lmMock = Mockito.mock(LocationManager::class.java)
            val loc = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                lmMock,
                arrayOf("gps")
            ) { null }.result as Location
            assertEquals(ny.latitude, loc.latitude, 1e-7)
            assertEquals(ny.longitude, loc.longitude, 1e-7)
            assertFalse(loc.isMock)
            assertFalse(loc.isFromMockProvider)
            assertNull(loc.extras?.get("mockLocation"))
            assertEquals(12, loc.extras?.getInt("satellites"))

            val hiddenCarrier = HiddenFieldsLocationStub()
            MockLocationCleaner.cleanHiddenFields(hiddenCarrier)
            assertEquals(0, hiddenCarrier.mFieldsMask and 16)
            assertEquals(15, hiddenCarrier.mFieldsMask and 15)
            assertFalse(hiddenCarrier.mIsFromMockProvider)

            // 2. 高德在境外也保持 WGS-84 恒等（不偏转）
            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                amapClient,
                arrayOf(amapListener)
            )
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                amapListener,
                arrayOf(E2eLbsSdkDoubles.AMapLocation())
            ) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                null
            }
            assertEquals(ny.latitude, amapListener.lastLocation!!.getLatitude(), 1e-7)
            assertEquals(ny.longitude, amapListener.lastLocation!!.getLongitude(), 1e-7)

            // 3. 基站/WiFi/GNSS 反检测全生效
            val tmMock = Mockito.mock(TelephonyManager::class.java)
            val cellRes = backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tmMock) {
                listOf(Mockito.mock(CellInfo::class.java))
            }
            assertTrue((cellRes.result as List<*>).isEmpty())

            val wmMock = Mockito.mock(WifiManager::class.java)
            val scanRes = backend.invokeMethod(WifiManager::class.java, "getScanResults", wmMock) {
                listOf("RealAP")
            }
            assertTrue((scanRes.result as List<*>).isEmpty())

            val wifiMock = Mockito.mock(WifiInfo::class.java)
            val bssidRes = backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiMock) { "aa:bb:cc:dd:ee:ff" }
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, bssidRes.result)

            val nmeaListener = TestNmeaListener()
            backend.invokeMethod(
                LocationManager::class.java,
                "addNmeaListener",
                lmMock,
                arrayOf(nmeaListener)
            )
            val nmeaInvoke = backend.invokeMethod(
                TestNmeaListener::class.java,
                "onNmeaMessage",
                nmeaListener,
                arrayOf("\$GPGGA,000000.00,2232.0000,N,11403.0000,E,1,08,1.0,10.0,M,0.0,M,,*00\r\n", 100L)
            ) {
                nmeaListener.onNmeaMessage(it[0] as String, it[1] as Long)
                null
            }
            assertTrue(nmeaListener.lastMessage!!.contains(",W,")) // 西半球负经度 -> W
            assertFalse(nmeaInvoke.earlyReturned)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-03 foreground cruise dynamic speed and jitter updates propagate via L2 broadcast`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val repoScope = cleanup.track(newCleanScope())
            val repo = MockConfigRepository(prefs, ioScope = repoScope, debounceMs = 10L)

            val hookStore = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val receivedVersions = CopyOnWriteArrayList<Long>()
            val receivedSpeeds = CopyOnWriteArrayList<Float>()
            hookStore.addListener { cfg ->
                receivedVersions.add(cfg.version)
                receivedSpeeds.add(cfg.speed)
            }

            val backend = E2eHookBackend()
            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = hookStore, locationFactory = buildLocationFactory())
            )
            val targetPkg = "com.tencent.mm"
            locationHook.installSystemHooks(javaClass.classLoader, targetPkg)

            val coordScope = cleanup.track(newCleanScope())
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { grantedPermissions() },
                broadcastDispatcher = { cfg, pkgs ->
                    if (targetPkg in pkgs) {
                        val intent = buildBroadcastIntent(cfg, targetPkg)
                        if (hookStore.onBroadcastReceived(intent)) {
                            locationHook.onConfigChanged(hookStore.currentConfig(), targetPkg)
                        }
                    }
                },
                foregroundLauncher = { true },
                scope = coordScope,
                tickIntervalMs = 25L,
                motionSimulator = MotionSimulator(kotlin.random.Random(42))
            )
            val controller = cleanup.track(
                MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
            )

            val route = PlannedRoute(
                id = "t3_03_route",
                name = "调速测试3航点",
                waypoints = listOf(
                    Waypoint(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), "天安门"),
                    Waypoint(TestCoordinates.BEIJING_FORBIDDEN_CITY.toGeoPoint(), "故宫"),
                    Waypoint(TestCoordinates.BEIJING_BIRDS_NEST.toGeoPoint(), "鸟巢")
                )
            )
            val cruiseCfg = CruiseConfig(
                speedMps = 2.0,
                loopMode = LoopMode.LOOP,
                enableSpeedNoise = false,
                enableMicroDrift = true,
                maxDriftRadiusMeters = 2.0
            )
            val baseCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                targetPackages = setOf(targetPkg),
                version = 1L
            ).toMockConfig()

            controller.startCruise(route, cruiseCfg, baseCfg)
            waitUntil { controller.runtimeState.value.status is MockRunStatus.CruiseRunning }
            Thread.sleep(90L)
            val pointBeforeSpeedBoost = hookStore.currentConfig().toGeoPoint()
            val versionBeforeBoost = hookStore.currentConfig().version

            controller.updateSpeed(12.0)
            controller.updateJitter(false)

            waitUntil {
                val st = controller.runtimeState.value
                abs(st.speedMps - 12.0) < 0.5 && hookStore.currentConfig().version > versionBeforeBoost
            }
            Thread.sleep(90L)
            val afterBoostCfg = hookStore.currentConfig()

            assertEquals(12.0, controller.runtimeState.value.speedMps, 0.5)
            assertFalse(afterBoostCfg.jitterEnabled)
            assertTrue(afterBoostCfg.version > versionBeforeBoost)
            assertTrue(receivedSpeeds.any { abs(it - 12.0f) < 0.5f })
            val movedMeters = GeoCoordinateMathReference.haversineDistanceMeters(
                pointBeforeSpeedBoost.latitude,
                pointBeforeSpeedBoost.longitude,
                afterBoostCfg.latitude,
                afterBoostCfg.longitude
            )
            assertTrue("Expected cruise movement after speed boost, moved=$movedMeters", movedMeters > 0.2)

            controller.stopMock()
            waitUntil { controller.runtimeState.value.status is MockRunStatus.Idle }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-04 recommended scope whitelist isolates selected apps from unlisted third-party map`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val metaPrefs = InMemorySharedPreferences()
            val repoScope = cleanup.track(newCleanScope())
            val configRepo = MockConfigRepository(prefs, ioScope = repoScope, debounceMs = 10L)

            val installed = setOf("com.tencent.mm", "com.autonavi.minimap", "com.unlisted.nav")
            val installChecker = PackageInstallChecker { pkg -> pkg in installed }

            val recItems = RecommendedScopeRepository.getRecommendedScopes(installChecker)
            val installedRecommended = recItems.filter { !it.isRequiredSystemScope && it.isInstalled }.map { it.packageName }.toSet()
            assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), installedRecommended)

            val whitelistRepo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource {
                    listOf(
                        InstalledAppRecord("com.tencent.mm", "微信", false),
                        InstalledAppRecord("com.autonavi.minimap", "高德地图", false),
                        InstalledAppRecord("com.unlisted.nav", "未选第三方导航", false)
                    )
                },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )
            whitelistRepo.saveSelectedPackages(installedRecommended)

            val shanghai = TestCoordinates.SHANGHAI_LUJIAZUI
            val activeCfg = configRepo.updateImmediate {
                it.copy(
                    enabled = true,
                    mode = MockMode.LSPOSED_ONLY,
                    latitude = shanghai.latitude,
                    longitude = shanghai.longitude,
                    altitude = shanghai.altitude
                )
            }

            fun runProcessSimulation(pkg: String): Pair<Double, Double> {
                val store = cleanup.track(
                    HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
                )
                val backend = E2eHookBackend()
                E2eLbsSdkDoubles.registerAll(backend)
                val locHook = cleanup.track(LocationHook(backend, store, locationFactory = buildLocationFactory()))
                val netHook = cleanup.track(NetworkAntiDetectHook(backend, store))
                val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, store))
                val clHelper = cleanup.track(ClassLoaderHookHelper(backend))
                XposedInit().handlePackageInternal(
                    packageName = pkg,
                    classLoader = javaClass.classLoader,
                    backend = backend,
                    configStore = store,
                    locationHook = locHook,
                    networkHook = netHook,
                    thirdPartyHook = sdkHook,
                    classLoaderHelper = clHelper,
                    xposedVersionProvider = { 93 }
                )
                store.onBroadcastReceived(buildBroadcastIntent(activeCfg, pkg))

                val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
                val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
                backend.invokeMethod(
                    E2eLbsSdkDoubles.AMapLocationClient::class.java,
                    "setLocationListener",
                    amapClient,
                    arrayOf(amapListener)
                )
                val physicalLoc = E2eLbsSdkDoubles.AMapLocation().apply {
                    setLatitude(30.0)
                    setLongitude(120.0)
                }
                backend.invokeMethod(
                    E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                    "onLocationChanged",
                    amapListener,
                    arrayOf(physicalLoc)
                ) {
                    amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                    null
                }
                return amapListener.lastLocation!!.getLatitude() to amapListener.lastLocation!!.getLongitude()
            }

            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(shanghai.latitude, shanghai.longitude)
            val (wxLat, wxLon) = runProcessSimulation("com.tencent.mm")
            val (amapLat, amapLon) = runProcessSimulation("com.autonavi.minimap")
            val (unlistedLat, unlistedLon) = runProcessSimulation("com.unlisted.nav")

            assertEquals(expectedGcj.lat, wxLat, 1e-5)
            assertEquals(expectedGcj.lon, wxLon, 1e-5)
            assertEquals(expectedGcj.lat, amapLat, 1e-5)
            assertEquals(expectedGcj.lon, amapLon, 1e-5)
            assertEquals(30.0, unlistedLat, 1e-7)
            assertEquals(120.0, unlistedLon, 1e-7)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-05 search favorite rename one-click select and start single mock updates MainViewModel StateFlow`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val favRepo = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope)
                val routeRepo = RouteRepository(routePrefs, Dispatchers.IO, ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = metaPrefs,
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 30L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )
                val requester = FakeGeocodingHttpRequester().apply { stubTimeout(Regex(".*")) }
                val geocoding = DomesticGeocodingService(
                    httpClient = requester.toGeocodingHttpClient(),
                    fallbackService = OfflineFallbackGeocodingService(),
                    ioDispatcher = Dispatchers.IO
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
                            EnvironmentDetector.detectEnvironment(
                                context = buildTestAppContext(),
                                shellExecutor = FakeShellExecutor().toShellExecutor(),
                                sdkInt = 35
                            )
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO,
                        searchDebounceMs = 10L
                    )
                )

                vm.onSearchQueryChanged("陆家嘴")
                vm.submitSearchNow()
                waitUntil { vm.uiState.value.searchResults.isNotEmpty() }

                val lujiazuiItem = vm.uiState.value.searchResults.first()
                vm.selectSearchResult(lujiazuiItem)
                assertEquals(lujiazuiItem.latitude, vm.uiState.value.selectedPoint.latitude, 1e-5)

                vm.addFavorite("陆家嘴", "上海浦东")
                waitUntil { vm.uiState.value.favorites.any { it.name == "陆家嘴" } }
                val fav = vm.uiState.value.favorites.first { it.name == "陆家嘴" }

                vm.updateFavorite(fav.id, "陆家嘴打卡点", "上海市浦东新区陆家嘴")
                waitUntil { vm.uiState.value.favorites.any { it.id == fav.id && it.name == "陆家嘴打卡点" } }
                val renamedFav = vm.uiState.value.favorites.first { it.id == fav.id }

                val labelHistory = Collections.synchronizedList(mutableListOf<String>())
                val events = Collections.synchronizedList(mutableListOf<MainUiEvent>())
                val stateCollectorJob = ioScope.launch(
                    context = Dispatchers.Unconfined,
                    start = CoroutineStart.UNDISPATCHED
                ) {
                    vm.uiState.collect { state ->
                        labelHistory.add(state.selectedAddressLabel)
                    }
                }
                val eventCollectorJob = ioScope.launch(
                    context = Dispatchers.Unconfined,
                    start = CoroutineStart.UNDISPATCHED
                ) {
                    vm.events.collect { event ->
                        events.add(event)
                    }
                }
                try {
                    vm.useFavoriteAsSingle(renamedFav)
                    waitUntil {
                        val hasLabel = synchronized(labelHistory) {
                            labelHistory.any { it.contains("陆家嘴打卡点") }
                        }
                        val hasSnackbar = synchronized(events) {
                            events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("陆家嘴打卡点") }
                        }
                        hasLabel && hasSnackbar &&
                            abs(vm.uiState.value.selectedPoint.latitude - 31.2397) < 1e-5 &&
                            abs(vm.uiState.value.selectedPoint.longitude - 121.4998) < 1e-5 &&
                            vm.uiState.value.latInput.isNotBlank() &&
                            vm.uiState.value.lonInput.isNotBlank()
                    }
                    assertTrue(synchronized(labelHistory) { labelHistory.any { it.contains("陆家嘴打卡点") } })
                    assertTrue(
                        synchronized(events) {
                            events.any { it is MainUiEvent.ShowSnackbar && it.message.contains("陆家嘴打卡点") }
                        }
                    )
                    assertEquals(31.2397, vm.uiState.value.selectedPoint.latitude, 1e-5)
                    assertEquals(121.4998, vm.uiState.value.selectedPoint.longitude, 1e-5)
                    assertEquals(31.2397, vm.uiState.value.latInput.toDouble(), 1e-5)
                    assertEquals(121.4998, vm.uiState.value.lonInput.toDouble(), 1e-5)
                } finally {
                    stateCollectorJob.cancelAndJoin()
                    eventCollectorJob.cancelAndJoin()
                }

                vm.onStartSingleClicked()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning }
                assertEquals(renamedFav.latitude, vm.uiState.value.runtimeState.currentPoint!!.latitude, 1e-5)
                assertTrue(configRepo.currentConfig.enabled)

                vm.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle && vm.uiState.value.environmentStatus != null }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-06 search route persistence ONE_WAY cruise completion and clean restart`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)
                val favRepo = FavoriteRepository(favPrefs, Dispatchers.IO, ioScope)
                val routeRepo1 = RouteRepository(routePrefs, Dispatchers.IO, ioScope)
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = metaPrefs,
                    packageQuerySource = PackageQuerySource { emptyList() },
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )

                val requester = FakeGeocodingHttpRequester().apply { stubTimeout(Regex(".*")) }
                val geocoding = DomesticGeocodingService(
                    httpClient = requester.toGeocodingHttpClient(),
                    fallbackService = OfflineFallbackGeocodingService(),
                    ioDispatcher = Dispatchers.IO
                )
                val tiananmenRes = geocoding.search("天安门").getOrThrow().first()
                // 构造近距两个航点以便极速跑完 ONE_WAY 终点并在真实调度器内验证重启
                val pStart = tiananmenRes.toGeoPoint()
                val pPalace = GeoPoint(pStart.latitude + 0.00018, pStart.longitude, pStart.altitude)

                val routeSaved = routeRepo1.saveRoute(
                    PlannedRoute(
                        id = "t3_06_route",
                        name = "天安门到故宫",
                        waypoints = listOf(
                            Waypoint(pStart, "天安门"),
                            Waypoint(pPalace, "故宫")
                        )
                    )
                ).getOrThrow()

                // 重建 RouteRepository 验证无损恢复
                val routeRepo2 = RouteRepository(routePrefs, Dispatchers.IO, ioScope)
                val reloaded = routeRepo2.getRouteById(routeSaved.id)!!
                assertEquals("天安门到故宫", reloaded.name)
                assertEquals(2, reloaded.waypoints.size)

                val coordinator = MockEngineCoordinator(
                    configRepository = configRepo,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 20L,
                    motionSimulator = MotionSimulator(kotlin.random.Random(7))
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
                        routeRepository = routeRepo2,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            TestCoordinates.BEIJING_TIANANMEN.let {
                                com.mockloc.root.EnvironmentStatus(true, 93, false, "None", true, true, 0, 14)
                            }
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO,
                        searchDebounceMs = 10L
                    )
                )

                vm.loadRouteToDraft(reloaded)
                vm.setLoopMode(LoopMode.ONE_WAY)
                vm.setSpeedNoiseEnabled(false)
                vm.setMicroDriftEnabled(false)
                vm.updateCustomSpeed(150.0) // ~20m 距离在 20ms tick 下约 0.14s 内完结

                vm.onStartCruiseClicked()
                waitUntil(timeoutMs = 4_000L) { vm.uiState.value.isOneWayCompleted }
                assertTrue(vm.uiState.value.isOneWayCompleted)
                assertEquals(1.0f, vm.uiState.value.runtimeState.progressFraction, 0.01f)

                // 再次启动必须从起点干净复位重新运行
                vm.updateCustomSpeed(10.0)
                vm.restartCruiseFromCompleted()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.CruiseRunning }
                assertTrue(vm.uiState.value.runtimeState.progressFraction < 0.9f)

                // 同步验证底层 CruiseEngine 状态机 COMPLETED -> start() 回正
                val engine = CruiseEngine(MotionSimulator(kotlin.random.Random(7)))
                engine.loadRoute(reloaded, CruiseConfig(speedMps = 50.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false))
                engine.start()
                while (!engine.isFinished()) {
                    engine.advanceStep(0.2)
                }
                assertEquals(CruiseEngine.State.COMPLETED, engine.state)
                engine.start()
                assertEquals(CruiseEngine.State.RUNNING, engine.state)
                assertEquals(0f, engine.currentSnapshot().progressFraction, 1e-4f)

                vm.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle && vm.uiState.value.environmentStatus != null }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-07 ROOT_ONLY mode with EnvironmentDetector RootProviderManager and MainViewModel`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val fakeShell = FakeShellExecutor().apply {
                    stubCommand(Regex("^id$"), FakeShellResult(0, "uid=0(root) gid=0(root)"))
                    stubCommand(Regex("magisk|ksud|apd|su -v"), FakeShellResult(0, "27.0:MAGISKSU"))
                    stubCommand(Regex("appops set"), FakeShellResult(0, ""))
                }
                val shellExec = fakeShell.toShellExecutor()
                val testProviderBackend = FakeTestProviderBackend(initialHasMockAppOp = false)
                val appCtx = buildTestAppContext(
                    packageName = "com.mockloc",
                    hasFineLocation = true,
                    hasCoarseLocation = true,
                    hasNotification = true
                )
                val rootManager = RootProviderManager(
                    context = appCtx,
                    shellExecutor = shellExec,
                    backendFactory = { testProviderBackend },
                    sdkIntProvider = { 35 },
                    userIdProvider = { 0 },
                    uidProvider = { 10123 },
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
                        favoriteRepository = favRepo,
                        routeRepository = routeRepo,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentDetector.detectEnvironment(appCtx, shellExec, sdkInt = 35)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO,
                        stopRootProvider = { rootManager.stop() }
                    )
                )

                waitUntil { vm.uiState.value.environmentStatus != null }
                val env = vm.uiState.value.environmentStatus!!
                assertTrue(env.isRootAvailable)
                assertFalse(env.isLSPosedActive)
                assertEquals("Magisk", env.rootProviderName)

                vm.setMockMode(MockMode.ROOT_ONLY)
                assertTrue(rootManager.ensureMockPermission("com.mockloc").isSuccess)
                val sz = TestCoordinates.SHENZHEN_NANSHAN
                vm.applyCoordinateInputs(sz.latitude.toString(), sz.longitude.toString(), sz.altitude.toString(), "3.0")
                vm.onStartSingleClicked()

                waitUntil {
                    vm.uiState.value.runtimeState.status is MockRunStatus.SingleRunning &&
                        testProviderBackend.lastLocations.size == 3
                }
                assertTrue(testProviderBackend.registeredProviders.keys.containsAll(listOf("gps", "network", "fused")))
                assertEquals(sz.latitude, testProviderBackend.lastLocations["gps"]!!.latitude, 1e-4)
                assertTrue(fakeShell.executedCommands.any { it.contains("appops set") && it.contains("android:mock_location allow") })

                vm.stopMock()
                waitUntil {
                    vm.uiState.value.runtimeState.status is MockRunStatus.Idle &&
                        testProviderBackend.registeredProviders.isEmpty()
                }
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-08 permission denial guides cleanly and ROOT_ONLY su failure exposes Error without crash`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

                val permGrantedFlag = AtomicBoolean(false)
                val permCheck = {
                    if (permGrantedFlag.get()) grantedPermissions() else deniedLocationPermissions()
                }

                val failingShell = FakeShellExecutor().apply {
                    stubCommand(Regex(".*"), FakeShellResult(1, "", "su: permission denied"))
                }
                val failingBackend = FakeTestProviderBackend(initialHasMockAppOp = false).apply {
                    throwOnAdd["gps"] = SecurityException("not allowed to perform MOCK_LOCATION")
                }
                val appCtx = buildTestAppContext(packageName = "com.mockloc")
                val rootManager = RootProviderManager(
                    context = appCtx,
                    shellExecutor = failingShell.toShellExecutor(),
                    backendFactory = { failingBackend },
                    sdkIntProvider = { 35 },
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
                    rootDriver = rootManager,
                    permissionProvider = permCheck,
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = ioScope,
                    tickIntervalMs = 40L
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, permCheck, Dispatchers.Unconfined)
                )

                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = favRepo,
                        routeRepository = routeRepo,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            EnvironmentDetector.detectEnvironment(appCtx, failingShell.toShellExecutor(), sdkInt = 35)
                        },
                        permissionProvider = PermissionStatusProvider { permCheck() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO
                    )
                )

                val events = CopyOnWriteArrayList<MainUiEvent>()
                val eventJob = ioScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    vm.events.collect { events.add(it) }
                }

                // 1. 拒绝权限启动 -> 发射 RequestPermissions 引导且状态保持 Idle 不闪退
                vm.setMockMode(MockMode.ROOT_ONLY)
                vm.onStartSingleClicked()
                waitUntil { events.any { it is MainUiEvent.RequestPermissions } }
                assertTrue(events.any { it is MainUiEvent.RequestPermissions && it.needLocation })
                assertTrue(vm.uiState.value.runtimeState.status is MockRunStatus.Idle)

                // 直接调用 Coordinator 在无权限时也会安全进入 Error 而不是崩溃
                coordinator.startSingleMock(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), configRepo.currentConfig)
                assertTrue(coordinator.runtimeState.value.status is MockRunStatus.Error)
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Error }
                coordinator.stopMock()
                waitUntil { vm.uiState.value.runtimeState.status is MockRunStatus.Idle }

                // 2. 授予权限但 su/addTestProvider 失败 -> 显式上抛 Root 失败原因至 Error
                permGrantedFlag.set(true)
                vm.onPermissionsResult(locationGranted = true, notificationGranted = true)
                waitUntil {
                    val st = vm.uiState.value.runtimeState.status
                    st is MockRunStatus.Error && !st.reason.isNullOrBlank()
                }
                val errStatus = vm.uiState.value.runtimeState.status as MockRunStatus.Error
                assertTrue(errStatus.reason.contains("Root"))
                assertNotNull(vm.uiState.value.runtimeState.errorMessage)
                eventJob.cancel()
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-09 queries isolation blocks L3 while L1 XSP serves early LocationHook without lockup`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val v1Cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
                targetPackages = setOf("com.early.app"),
                updatedAtMs = 1_700_000_001_000L,
                version = 1L
            ).toMockConfig()
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), v1Cfg).commit()

            val fakeNow = AtomicLong(1_000L)
            val l3Calls = AtomicInteger(0)
            val store = cleanup.track(
                HookConfigStore(
                    prefsReaderFactory = { buildMemoryPrefsReader(prefs) },
                    providerFetcher = object : ProviderConfigFetcher {
                        override fun fetchGlobalConfig(context: Context): Bundle? {
                            l3Calls.incrementAndGet()
                            throw SecurityException("Blocked by Android 11+ <queries> visibility")
                        }
                    },
                    elapsedRealtimeProvider = { fakeNow.get() },
                    throttleMs = 400L
                )
            )
            val backend = E2eHookBackend()
            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = store, locationFactory = buildLocationFactory())
            )
            val networkHook = cleanup.track(NetworkAntiDetectHook(backend = backend, configProvider = store))
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend = backend, configProvider = store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend = backend))

            // 模拟 Application.onCreate 之前即调用 getLastKnownLocation
            XposedInit().handlePackageInternal(
                packageName = "com.early.app",
                classLoader = javaClass.classLoader,
                backend = backend,
                configStore = store,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 模拟 L3 在 attachBaseContext 时被阻断且不崩溃
            val ctxWithBlockedL3 = buildTestAppContext(packageName = "com.early.app")
            assertFalse(store.fetchFromL3ProviderSafe(ctxWithBlockedL3))
            assertTrue(l3Calls.get() >= 1)

            val lmMock = Mockito.mock(LocationManager::class.java)
            val locV1 = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                lmMock,
                arrayOf("gps")
            ) { null }.result as Location
            assertEquals(v1Cfg.latitude, locV1.latitude, 1e-6)
            assertEquals(v1Cfg.longitude, locV1.longitude, 1e-6)

            // 验证无 isConfigSynced 永久锁死：更新 L1 配置至 v2 并超过 400ms 节流窗口
            val v2Cfg = v1Cfg.copy(
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                updatedAtMs = 1_700_000_002_000L,
                version = 2L
            )
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), v2Cfg).commit()
            fakeNow.set(2_000L)

            val locV2 = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                lmMock,
                arrayOf("gps")
            ) { null }.result as Location
            assertEquals(v2Cfg.latitude, locV2.latitude, 1e-6)
            assertEquals(v2Cfg.longitude, locV2.longitude, 1e-6)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-10 dynamic secondary ClassLoader attaches AMap SDK and TelephonyCallback hooks`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                antiDetectCellEnabled = true,
                targetPackages = setOf("com.packed.app"),
                version = 3L
            ).toMockConfig()
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfg).commit()

            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend(declaredOnly = true)
            // 初始空壳 ClassLoader 阶段：屏蔽高德 SDK 与 TelephonyCallback 相关类，仅放行 LoadedApk
            backend.registerClass("android.app.LoadedApk", FakeLoadedApk::class.java)
            backend.blockedClassNames.addAll(
                listOf(
                    "com.amap.api.location.AMapLocationClient",
                    "com.amap.api.location.AMapLocationListener",
                    "com.amap.api.location.AMapLocation",
                    "com.amap.api.location.AMapLocationQualityReport",
                    "android.telephony.TelephonyManager",
                    "android.telephony.TelephonyCallback"
                )
            )

            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = store, locationFactory = buildLocationFactory())
            )
            val networkHook = cleanup.track(NetworkAntiDetectHook(backend = backend, configProvider = store))
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend = backend, configProvider = store))
            val clHelper = cleanup.track(ClassLoaderHookHelper(backend = backend))

            val shellCl = object : ClassLoader(javaClass.classLoader) {}
            XposedInit().handlePackageInternal(
                packageName = "com.packed.app",
                classLoader = shellCl,
                backend = backend,
                configStore = store,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = sdkHook,
                classLoaderHelper = clHelper,
                xposedVersionProvider = { 93 }
            )

            // 壳阶段解除屏蔽并加载包含高德 SDK 与基站回调的插件 ClassLoader
            backend.blockedClassNames.clear()
            E2eLbsSdkDoubles.registerAmap(backend)
            val pluginCl = object : ClassLoader(javaClass.classLoader) {}
            val loadedApk = FakeLoadedApk(pluginCl)

            val clResult = backend.invokeMethod(
                FakeLoadedApk::class.java,
                "getClassLoader",
                loadedApk
            ) { loadedApk.getClassLoader() }
            assertEquals(pluginCl, clResult.result)

            // 验证高德 SDK 已补装生效
            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                amapClient,
                arrayOf(amapListener)
            )
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                amapListener,
                arrayOf(E2eLbsSdkDoubles.AMapLocation())
            ) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                null
            }
            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(cfg.latitude, cfg.longitude)
            assertEquals(expectedGcj.lat, amapListener.lastLocation!!.getLatitude(), 1e-5)
            assertEquals(expectedGcj.lon, amapListener.lastLocation!!.getLongitude(), 1e-5)

            // 验证 TelephonyManager.registerTelephonyCallback 具体子类已补装生效
            val tmMock = Mockito.mock(TelephonyManager::class.java)
            val cellCallback = TestTelephonyCellCallback()
            backend.invokeMethod(
                TelephonyManager::class.java,
                "registerTelephonyCallback",
                tmMock,
                arrayOf(Executor { it.run() }, cellCallback)
            )
            backend.invokeMethod(
                TestTelephonyCellCallback::class.java,
                "onCellInfoChanged",
                cellCallback,
                arrayOf(mutableListOf(Mockito.mock(CellInfo::class.java)))
            ) { args ->
                @Suppress("UNCHECKED_CAST")
                val listArg = (args[0] as List<CellInfo>).toMutableList()
                cellCallback.onCellInfoChanged(listArg)
                null
            }
            assertNotNull(cellCallback.receivedCells)
            assertTrue(cellCallback.receivedCells!!.isEmpty())
            assertFalse(backend.hasInterfaceHookAllMethods())
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-11 Android 14-17 getCurrentLocation Settings Secure mock_location and NetworkCapabilities`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.probe.a15"
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                latitude = TestCoordinates.BEIJING_FORBIDDEN_CITY.latitude,
                longitude = TestCoordinates.BEIJING_FORBIDDEN_CITY.longitude,
                targetPackages = setOf(targetPkg),
                antiDetectWifiEnabled = true,
                version = 8L
            ).toMockConfig()
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfg).commit()

            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
            Mockito.`when`(sanitizedWifi.bssid).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)

            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = store, locationFactory = buildLocationFactory())
            )
            val networkHook = cleanup.track(
                NetworkAntiDetectHook(
                    backend = backend,
                    configProvider = store,
                    wifiInfoSanitizer = { _, _ -> sanitizedWifi },
                    sdkIntProvider = { 35 }
                )
            )
            val testCl = object : ClassLoader(javaClass.classLoader) {}
            locationHook.installSystemHooks(testCl, targetPkg)
            MockLocationCleaner.installHooks(testCl, backend, sdkInt = 35) {
                store.currentConfig().isEnabledForPackage(targetPkg)
            }
            networkHook.install(testCl, targetPkg)

            // 1. getCurrentLocation("fused", ..., Consumer)
            val lmMock = Mockito.mock(LocationManager::class.java)
            val receivedLoc = AtomicReference<Location?>(null)
            val consumer = Consumer<Location> { loc -> receivedLoc.set(loc) }
            backend.invokeMethod(
                LocationManager::class.java,
                "getCurrentLocation",
                lmMock,
                arrayOf("fused", null as CancellationSignal?, Executor { it.run() }, consumer)
            )
            assertNotNull(receivedLoc.get())
            assertEquals(cfg.latitude, receivedLoc.get()!!.latitude, 1e-6)
            assertFalse(receivedLoc.get()!!.isMock)
            assertFalse(receivedLoc.get()!!.isFromMockProvider)

            // 2. Settings.Secure("mock_location") -> "0" / 0
            val cr = Mockito.mock(ContentResolver::class.java)
            val secureStr = backend.invokeStatic(
                Settings.Secure::class.java,
                "getString",
                arrayOf(cr, "mock_location")
            ) { "1" }
            assertEquals("0", secureStr.result)
            val secureInt = backend.invokeStatic(
                Settings.Secure::class.java,
                "getInt",
                arrayOf(cr, "mock_location", 1)
            ) { 1 }
            assertEquals(0, secureInt.result)

            // 3. AppOpsManager -> MODE_IGNORED
            val appOpsMock = Mockito.mock(AppOpsManager::class.java)
            val opRes = backend.invokeMethod(
                AppOpsManager::class.java,
                "checkOpNoThrow",
                appOpsMock,
                arrayOf("android:mock_location", 10123, targetPkg)
            ) { AppOpsManager.MODE_ALLOWED }
            assertEquals(AppOpsManager.MODE_IGNORED, opRes.result)

            // 4. NetworkCapabilities.getTransportInfo() -> 清洗后 WifiInfo
            val realWifi = Mockito.mock(WifiInfo::class.java)
            Mockito.`when`(realWifi.bssid).thenReturn("11:22:33:44:55:66")
            val ncMock = Mockito.mock(NetworkCapabilities::class.java)
            val ncRes = backend.invokeMethod(
                NetworkCapabilities::class.java,
                "getTransportInfo",
                ncMock
            ) { realWifi }
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, (ncRes.result as WifiInfo).bssid)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-12 concurrent whitelist and cruise coordinate updates serialize without data loss`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val metaPrefs = InMemorySharedPreferences()
            val ioScope = cleanup.track(newCleanScope())
            val configRepo = MockConfigRepository(prefs, ioScope = ioScope, debounceMs = 15L)

            val syncedEvents = CopyOnWriteArrayList<MockConfig>()
            val whitelistRepo = WhitelistRepository(
                configRepository = configRepo,
                metaPrefs = metaPrefs,
                packageQuerySource = PackageQuerySource {
                    (1..10).map { i -> InstalledAppRecord("com.app.test$i", "App $i", false) }
                },
                onPackagesSynced = { cfg, _ -> syncedEvents.add(cfg) },
                prepopulateOnFirstInit = false,
                ioDispatcher = Dispatchers.IO
            )

            val expectedPackages = setOf("com.app.test1", "com.app.test5", "com.app.test9")
            val coordJob = async(Dispatchers.Default) {
                for (step in 1..60) {
                    val lat = 39.9000 + step * 0.0001
                    val lon = 116.3900 + step * 0.0001
                    configRepo.updateImmediate { it.copy(enabled = true, latitude = lat, longitude = lon) }
                }
            }
            val wlJob = async(Dispatchers.IO) {
                whitelistRepo.togglePackage("com.app.test1")
                whitelistRepo.togglePackage("com.app.test2")
                whitelistRepo.removePackages(listOf("com.app.test2"))
                whitelistRepo.addPackages(listOf("com.app.test5", "com.app.test9"))
                whitelistRepo.saveSelectedPackages(expectedPackages)
            }
            coordJob.await()
            wlJob.await()
            configRepo.flushPendingSync()

            val finalCfg = configRepo.currentConfig
            assertEquals(expectedPackages, finalCfg.targetPackages)
            assertEquals(39.9060, finalCfg.latitude, 1e-5)
            assertEquals(116.3960, finalCfg.longitude, 1e-5)
            assertTrue(finalCfg.enabled)

            // 跨进程序列化校验
            val roundTrip = ConfigSyncSerializer.fromBundle(
                ConfigSyncSerializer.toBundle(finalCfg, StatefulFakeBundle().asAndroidBundle())
            )
            assertEquals(finalCfg.targetPackages, roundTrip.targetPackages)
            assertEquals(finalCfg.latitude, roundTrip.latitude, 1e-6)
            assertEquals(finalCfg.version, roundTrip.version)
            assertTrue(syncedEvents.isNotEmpty())
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-13 cross-border cruise toggles GCJ-02 offset off upon exiting China rectangle`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.autonavi.minimap"
            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            E2eLbsSdkDoubles.registerAmap(backend)
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend, store))
            sdkHook.installForClassLoader(javaClass.classLoader, targetPkg)

            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                amapClient,
                arrayOf(amapListener)
            )

            // 跨中国西部边界路线: (39.9, 73.70) 境内 -> (39.9, 73.60) 境外
            val route = PlannedRoute(
                id = "cross_border_west",
                name = "跨边界测试",
                waypoints = listOf(
                    Waypoint(GeoPoint(39.9, 73.70), "境内"),
                    Waypoint(GeoPoint(39.9, 73.60), "境外")
                )
            )
            val engine = CruiseEngine(MotionSimulator(kotlin.random.Random(1)))
            engine.loadRoute(
                route,
                CruiseConfig(speedMps = 1000.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
            )
            engine.start()

            var sawInnerOffset = false
            var sawOuterIdentity = false
            var ver = 1L
            val initialPoint = engine.currentSnapshot().point
            assertFalse(CoordinateConverter.isOutOfChina(initialPoint.latitude, initialPoint.longitude))

            for (i in 0 until 30) {
                val out = if (i == 0) engine.currentSnapshot() else engine.advanceStep(1.0)
                val cfg = MockConfig(
                    enabled = true,
                    latitude = out.point.latitude,
                    longitude = out.point.longitude,
                    targetPackages = setOf(targetPkg),
                    updatedAtMs = 1_700_000_000_000L + i,
                    version = ver++
                )
                store.onBroadcastReceived(buildBroadcastIntent(cfg, targetPkg))

                backend.invokeMethod(
                    E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                    "onLocationChanged",
                    amapListener,
                    arrayOf(E2eLbsSdkDoubles.AMapLocation())
                ) {
                    amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                    null
                }

                val delivered = amapListener.lastLocation!!
                assertTrue(delivered.getLatitude().isFinite() && delivered.getLongitude().isFinite())
                if (!CoordinateConverter.isOutOfChina(out.point.latitude, out.point.longitude)) {
                    val shiftMeters = GeoCoordinateMathReference.haversineDistanceMeters(
                        out.point.latitude,
                        out.point.longitude,
                        delivered.getLatitude(),
                        delivered.getLongitude()
                    )
                    if (shiftMeters > 50.0) sawInnerOffset = true
                } else {
                    if (abs(out.point.latitude - delivered.getLatitude()) < 1e-9 &&
                        abs(out.point.longitude - delivered.getLongitude()) < 1e-9
                    ) {
                        sawOuterIdentity = true
                    }
                }
                if (out.isCompleted) break
            }

            assertTrue("Expected GCJ-02 offset inside China border", sawInnerOffset)
            assertTrue("Expected identity WGS-84 outside China border", sawOuterIdentity)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-14 cruise pause resume stop drive LocationHook active pulse and MockRuntimeState`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val repoScope = cleanup.track(newCleanScope())
            val repo = MockConfigRepository(prefs, ioScope = repoScope, debounceMs = 10L)
            val hookStore = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = hookStore, locationFactory = buildLocationFactory())
            )
            val targetPkg = "com.cruise.target"
            locationHook.installSystemHooks(javaClass.classLoader, targetPkg)

            val receivedByTarget = CopyOnWriteArrayList<Location>()
            val listener = LocationListener { loc -> receivedByTarget.add(loc) }
            val lmMock = Mockito.mock(LocationManager::class.java)
            backend.invokeMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                lmMock,
                arrayOf("gps", 300L, 0f, listener)
            )

            val coordScope = cleanup.track(newCleanScope())
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { grantedPermissions() },
                broadcastDispatcher = { cfg, pkgs ->
                    if (targetPkg in pkgs) {
                        hookStore.onBroadcastReceived(buildBroadcastIntent(cfg, targetPkg))
                        locationHook.onConfigChanged(hookStore.currentConfig(), targetPkg)
                    }
                },
                foregroundLauncher = { true },
                scope = coordScope,
                tickIntervalMs = 30L,
                motionSimulator = MotionSimulator(kotlin.random.Random(10))
            )
            val controller = cleanup.track(
                MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
            )

            val route = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute()
            val cruiseCfg = CruiseConfig(speedMps = 8.0, loopMode = LoopMode.LOOP, enableSpeedNoise = false, enableMicroDrift = false)
            val baseCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(targetPackages = setOf(targetPkg)).toMockConfig()

            controller.startCruise(route, cruiseCfg, baseCfg)
            waitUntil { controller.runtimeState.value.status is MockRunStatus.CruiseRunning && receivedByTarget.isNotEmpty() }
            Thread.sleep(80L)

            controller.pauseCruise()
            waitUntil { controller.runtimeState.value.status is MockRunStatus.CruisePaused }
            val pausedPoint = controller.runtimeState.value.currentPoint!!
            assertEquals(0.0, controller.runtimeState.value.speedMps, 1e-6)
            Thread.sleep(100L)
            assertEquals(pausedPoint.latitude, controller.runtimeState.value.currentPoint!!.latitude, 1e-7)
            assertEquals(pausedPoint.longitude, controller.runtimeState.value.currentPoint!!.longitude, 1e-7)

            controller.resumeCruise()
            waitUntil { controller.runtimeState.value.status is MockRunStatus.CruiseRunning }
            waitUntil {
                val cur = controller.runtimeState.value.currentPoint!!
                GeoCoordinateMathReference.haversineDistanceMeters(
                    pausedPoint.latitude, pausedPoint.longitude,
                    cur.latitude, cur.longitude
                ) > 0.2
            }
            val resumedPoint = controller.runtimeState.value.currentPoint!!
            val movedAfterResume = GeoCoordinateMathReference.haversineDistanceMeters(
                pausedPoint.latitude, pausedPoint.longitude,
                resumedPoint.latitude, resumedPoint.longitude
            )
            assertTrue("Expected cruise movement after resume, moved=$movedAfterResume", movedAfterResume > 0.2)

            controller.stopMock()
            waitUntil { controller.runtimeState.value.status is MockRunStatus.Idle }
            assertFalse(locationHook.isPulseSchedulerActive())
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-15 AUTO mode coordinates Root TestProvider injection and LSPosed cleaner`() = runBlocking {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.auto.target"
            val ioScope = cleanup.track(newCleanScope())
            val repo = MockConfigRepository(prefs, ioScope = ioScope, debounceMs = 10L)

            val fakeShell = FakeShellExecutor().apply {
                stubCommand(Regex("appops set"), FakeShellResult(0, ""))
            }
            val testProviderBackend = FakeTestProviderBackend(initialHasMockAppOp = true)
            val appCtx = buildTestAppContext(packageName = "com.mockloc")
            val rootManager = RootProviderManager(
                context = appCtx,
                shellExecutor = fakeShell.toShellExecutor(),
                backendFactory = { testProviderBackend },
                sdkIntProvider = { 35 },
                ioDispatcher = Dispatchers.IO
            )

            val hookStore = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            val locationHook = cleanup.track(
                LocationHook(backend = backend, configProvider = hookStore, locationFactory = buildLocationFactory())
            )
            locationHook.installSystemHooks(javaClass.classLoader, targetPkg)
            MockLocationCleaner.installHooks(javaClass.classLoader, backend, sdkInt = 35) {
                hookStore.currentConfig().isEnabledForPackage(targetPkg)
            }

            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                rootDriver = rootManager,
                permissionProvider = { grantedPermissions() },
                broadcastDispatcher = { cfg, pkgs ->
                    if (targetPkg in pkgs) {
                        hookStore.onBroadcastReceived(buildBroadcastIntent(cfg, targetPkg))
                    }
                },
                foregroundLauncher = { true },
                scope = ioScope,
                tickIntervalMs = 40L
            )
            val controller = cleanup.track(
                MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
            )

            val lhasa = TestCoordinates.LHASA_POTALA.toGeoPoint()
            val cfg = TestConfigPayloads.DEFAULT_IDLE_CONFIG.copy(
                mode = com.mockloc.e2e.factory.TestMockMode.AUTO,
                targetPackages = setOf(targetPkg)
            ).toMockConfig()

            controller.startSingleMock(lhasa, cfg)
            waitUntil {
                controller.runtimeState.value.status is MockRunStatus.SingleRunning &&
                    testProviderBackend.lastLocations.containsKey("gps")
            }

            // 1. Root TestProvider 注入系统定位
            val rootLoc = testProviderBackend.lastLocations["gps"]!!
            assertEquals(lhasa.latitude, rootLoc.latitude, 1e-4)
            assertEquals(lhasa.altitude, rootLoc.altitude, 1e-2)

            // 2. LSPosed LocationHook + Cleaner 抹除系统 TestProvider 产生的 mock 标记
            val lmMock = Mockito.mock(LocationManager::class.java)
            val rawRootLoc = E2eFakeLocation(
                provider = "gps",
                latitude = rootLoc.latitude,
                longitude = rootLoc.longitude,
                mockFlag = true,
                mIsFromMockProvider = true,
                mFieldsMask = 31
            ).asAndroidLocation()
            val appLoc = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                lmMock,
                arrayOf("gps")
            ) { rawRootLoc }.result as Location

            assertEquals(lhasa.latitude, appLoc.latitude, 1e-4)
            assertFalse(appLoc.isMock)
            assertFalse(appLoc.isFromMockProvider)

            val isMockHookRes = backend.invokeMethod(Location::class.java, "isMock", rawRootLoc) { true }
            assertEquals(false, isMockHookRes.result)

            controller.stopMock()
            waitUntil {
                controller.runtimeState.value.status is MockRunStatus.Idle &&
                    testProviderBackend.registeredProviders.isEmpty()
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-16 GCJ-02 input and favorite save maintain WGS-84 canonical storage without double offset`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())

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
                    scope = ioScope
                )
                val controller = cleanup.track(
                    MockEngineController(coordinator, { grantedPermissions() }, Dispatchers.Unconfined)
                )

                val vm = cleanup.track(
                    MainViewModel(
                        configRepository = configRepo,
                        engineGateway = ControllerEngineGateway(controller),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = favRepo,
                        routeRepository = routeRepo,
                        whitelistRepositoryProvider = { whitelistRepo },
                        environmentDetector = EnvironmentDetectorGateway {
                            com.mockloc.root.EnvironmentStatus(true, 93, false, "None", true, true, 0, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO
                    )
                )

                val wgsTruth = TestCoordinates.BEIJING_TIANANMEN
                val gcjInput = GeoCoordinateMathReference.wgs84ToGcj02(wgsTruth.latitude, wgsTruth.longitude)

                vm.setInputCoordinateType(CoordinateType.GCJ02)
                val applied = vm.applyCoordinateInputs(
                    latText = gcjInput.lat.toString(),
                    lonText = gcjInput.lon.toString(),
                    altText = "44.0",
                    accuracyText = "3.0"
                )
                assertNotNull(applied)
                assertEquals(wgsTruth.latitude, applied!!.latitude, 1e-5)
                assertEquals(wgsTruth.longitude, applied.longitude, 1e-5)
                assertEquals(wgsTruth.latitude, configRepo.currentConfig.latitude, 1e-5)
                assertEquals(wgsTruth.longitude, configRepo.currentConfig.longitude, 1e-5)

                vm.addFavorite("天安门高德输入", "北京市东城区")
                waitUntil { vm.uiState.value.favorites.any { it.name == "天安门高德输入" } }

                vm.setInputCoordinateType(CoordinateType.WGS84)
                val latDisplay = vm.uiState.value.latInput.toDouble()
                val lonDisplay = vm.uiState.value.lonInput.toDouble()
                assertEquals(wgsTruth.latitude, latDisplay, 1e-4)
                assertEquals(wgsTruth.longitude, lonDisplay, 1e-4)

                val savedFav = favRepo.getAll().first { it.name == "天安门高德输入" }
                assertEquals(wgsTruth.latitude, savedFav.latitude, 1e-5)
                assertEquals(wgsTruth.longitude, savedFav.longitude, 1e-5)
                assertEquals(CoordinateType.WGS84, savedFav.coordinateType)
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-17 WhitelistViewModel filtering search keeps selected system apps and syncs recommended scope`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val ioScope = cleanup.track(newCleanScope())
                val configRepo = MockConfigRepository(configPrefs, ioScope = ioScope, debounceMs = 10L)

                val querySource = PackageQuerySource {
                    listOf(
                        InstalledAppRecord("com.tencent.mm", "微信", isSystemApp = false),
                        InstalledAppRecord("com.tencent.wework", "企业微信", isSystemApp = false),
                        InstalledAppRecord("com.sys.selected", "已选系统组件", isSystemApp = true),
                        InstalledAppRecord("com.sys.unselected", "未选系统组件", isSystemApp = true),
                        InstalledAppRecord("com.autonavi.minimap", "高德地图", isSystemApp = false)
                    )
                }
                val whitelistRepo = WhitelistRepository(
                    configRepository = configRepo,
                    metaPrefs = metaPrefs,
                    packageQuerySource = querySource,
                    prepopulateOnFirstInit = false,
                    ioDispatcher = Dispatchers.IO
                )
                whitelistRepo.saveSelectedPackages(setOf("com.sys.selected"))

                val checker = PackageInstallChecker { pkg ->
                    pkg in setOf("com.tencent.mm", "com.tencent.wework", "com.autonavi.minimap")
                }

                val wlVm = cleanup.track(
                    WhitelistViewModel(
                        whitelistRepositoryProvider = { whitelistRepo },
                        packageInstallChecker = checker,
                        ioDispatcher = Dispatchers.IO
                    )
                )

                waitUntil { !wlVm.uiState.value.isLoading && wlVm.uiState.value.allApps.isNotEmpty() }
                // 默认 includeSystemApps = false：仍保留已选中的系统应用 com.sys.selected，但过滤 com.sys.unselected
                val filteredInitial = wlVm.uiState.value.filteredApps.map { it.packageName }.toSet()
                assertTrue("com.sys.selected" in filteredInitial)
                assertFalse("com.sys.unselected" in filteredInitial)

                wlVm.updateSearchQuery("tencent")
                val tencentMatches = wlVm.uiState.value.filteredApps.map { it.packageName }.toSet()
                assertEquals(setOf("com.tencent.mm", "com.tencent.wework"), tencentMatches)

                wlVm.togglePackage("com.tencent.mm")
                waitUntil { "com.tencent.mm" in wlVm.uiState.value.selectedPackages }

                wlVm.updateSearchQuery("")
                wlVm.importRecommendedInstalled()
                waitUntil {
                    setOf("com.tencent.mm", "com.tencent.wework", "com.autonavi.minimap", "com.sys.selected")
                        .all { it in wlVm.uiState.value.selectedPackages }
                }

                assertEquals(wlVm.uiState.value.selectedPackages, configRepo.currentConfig.targetPackages)
                assertEquals(3, wlVm.uiState.value.recommendedSummary?.installedTargetCount)
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-18 process kill and restart recovers config via L1 and L3 for target and controller`() = withMainDispatcher {
        val cleanup = CleanupRegistry()
        try {
            runBlocking {
                val configPrefs = InMemorySharedPreferences()
                val favPrefs = InMemorySharedPreferences()
                val routePrefs = InMemorySharedPreferences()
                val metaPrefs = InMemorySharedPreferences()
                val scope1 = cleanup.track(newCleanScope())

                // 第 1 阶段：主控写入持久化配置 (version >= 5)
                val repo1 = MockConfigRepository(configPrefs, isWorldReadable = true, ioScope = scope1, debounceMs = 10L)
                var latest = repo1.currentConfig
                val sz = TestCoordinates.SHENZHEN_CIVIC_CENTER
                for (v in 1..5) {
                    latest = repo1.updateImmediate {
                        it.copy(
                            enabled = true,
                            mode = MockMode.LSPOSED_ONLY,
                            latitude = sz.latitude,
                            longitude = sz.longitude,
                            altitude = sz.altitude,
                            targetPackages = setOf("com.tencent.mm")
                        )
                    }
                }
                assertTrue(latest.version >= 5L)

                // 模拟主控进程被杀：取消 scope1 并清空单例引用
                scope1.cancel()
                MockConfigRepository.setInstanceForTest(null)

                // 第 2 阶段：目标进程冷启动，先走 L1 恢复；随后主控重建，L3 LocationConfigProvider 也从持久化恢复
                val l1Store = cleanup.track(
                    HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(configPrefs) }, throttleMs = 0L)
                )
                val l1Restored = l1Store.getConfig(null, forceRefresh = true)
                assertTrue(l1Restored.enabled)
                assertEquals(sz.latitude, l1Restored.latitude, 1e-6)
                assertEquals(latest.version, l1Restored.version)

                // 重建主控仓储与 MainViewModel
                val scope2 = cleanup.track(newCleanScope())
                val repo2 = MockConfigRepository(configPrefs, isWorldReadable = true, ioScope = scope2, debounceMs = 10L)
                val provider2 = LocationConfigProvider(
                    repositoryResolver = { repo2 },
                    bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
                )
                val l3OnlyStore = cleanup.track(
                    HookConfigStore(
                        prefsReaderFactory = { null },
                        providerFetcher = object : ProviderConfigFetcher {
                            override fun fetchGlobalConfig(context: Context): Bundle? {
                                return provider2.call(IpcProtocol.METHOD_GET_CONFIG, context.packageName, null)
                            }
                        },
                        throttleMs = 0L
                    )
                )
                val l3Fetched = l3OnlyStore.fetchFromL3ProviderSafe(buildTestAppContext(packageName = "com.tencent.mm"))
                assertTrue(l3Fetched)
                assertEquals(sz.latitude, l3OnlyStore.currentConfig().latitude, 1e-6)

                val coordinator2 = MockEngineCoordinator(
                    configRepository = repo2,
                    permissionProvider = { grantedPermissions() },
                    broadcastDispatcher = { _, _ -> },
                    foregroundLauncher = { true },
                    scope = scope2
                )
                val controller2 = cleanup.track(
                    MockEngineController(coordinator2, { grantedPermissions() }, Dispatchers.Unconfined)
                )
                val vm2 = cleanup.track(
                    MainViewModel(
                        configRepository = repo2,
                        engineGateway = ControllerEngineGateway(controller2),
                        geocodingService = OfflineFallbackGeocodingService(),
                        favoriteRepository = FavoriteRepository(favPrefs, Dispatchers.IO, scope2),
                        routeRepository = RouteRepository(routePrefs, Dispatchers.IO, scope2),
                        whitelistRepositoryProvider = {
                            WhitelistRepository(repo2, metaPrefs, { emptyList() }, prepopulateOnFirstInit = false, ioDispatcher = Dispatchers.IO)
                        },
                        environmentDetector = EnvironmentDetectorGateway {
                            com.mockloc.root.EnvironmentStatus(true, 93, false, "None", true, true, 0, 14)
                        },
                        permissionProvider = PermissionStatusProvider { grantedPermissions() },
                        packageInstallChecker = PackageInstallChecker { false },
                        ioDispatcher = Dispatchers.IO
                    )
                )
                assertEquals(sz.latitude, vm2.uiState.value.selectedPoint.latitude, 1e-6)
                assertTrue(vm2.uiState.value.config.enabled)
                assertEquals(latest.version, vm2.uiState.value.config.version)
            }
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-19 AMap Baidu NetworkAntiDetect and MockLocationCleaner sanitize risk fields together`() {
        val cleanup = CleanupRegistry()
        try {
            val prefs = InMemorySharedPreferences()
            val targetPkg = "com.risk.combined"
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.copy(
                latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
                targetPackages = setOf(targetPkg),
                antiDetectWifiEnabled = true,
                antiDetectCellEnabled = true,
                version = 7L
            ).toMockConfig()
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfg).commit()

            val store = cleanup.track(
                HookConfigStore(prefsReaderFactory = { buildMemoryPrefsReader(prefs) }, throttleMs = 0L)
            )
            val backend = E2eHookBackend()
            E2eLbsSdkDoubles.registerAll(backend)

            val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
            Mockito.`when`(sanitizedWifi.bssid).thenReturn(NetworkAntiDetectHook.SANITIZED_BSSID)
            val netHook = cleanup.track(
                NetworkAntiDetectHook(
                    backend = backend,
                    configProvider = store,
                    wifiInfoSanitizer = { _, _ -> sanitizedWifi },
                    sdkIntProvider = { 35 }
                )
            )
            val sdkHook = cleanup.track(ThirdPartyLbsSdkHook(backend = backend, configProvider = store))

            val testCl = object : ClassLoader(javaClass.classLoader) {}
            netHook.install(testCl, targetPkg)
            sdkHook.installForClassLoader(testCl, targetPkg)
            MockLocationCleaner.installHooks(testCl, backend, sdkInt = 35) {
                store.currentConfig().isEnabledForPackage(targetPkg)
            }

            // 1. 高德多字段风控清洗
            val amapClient = E2eLbsSdkDoubles.AMapLocationClient()
            val amapListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                amapClient,
                arrayOf(amapListener)
            )
            val rawAmap = E2eLbsSdkDoubles.AMapLocation().apply {
                setAddress("真实物理高德街道1号")
                setMock(true)
                mFieldsMask = 31
                mIsFromMockProvider = true
            }
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                amapListener,
                arrayOf(rawAmap)
            ) {
                amapListener.onLocationChanged(it[0] as E2eLbsSdkDoubles.AMapLocation)
                null
            }
            val cleanedAmap = amapListener.lastLocation!!
            assertFalse(cleanedAmap.isMock())
            assertEquals(0, cleanedAmap.mFieldsMask and 16)
            assertFalse(cleanedAmap.mIsFromMockProvider)
            assertEquals("", cleanedAmap.getAddress())
            assertEquals("", cleanedAmap.getPoiName())
            assertEquals(0, cleanedAmap.getErrorCode())

            // 2. 百度多字段风控清洗
            val bdClient = E2eLbsSdkDoubles.LocationClient()
            val bdListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.LocationClient::class.java,
                "registerLocationListener",
                bdClient,
                arrayOf(bdListener)
            )
            val rawBd = E2eLbsSdkDoubles.BDLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                "onReceiveLocation",
                bdListener,
                arrayOf(rawBd)
            ) {
                bdListener.onReceiveLocation(it[0] as E2eLbsSdkDoubles.BDLocation)
                null
            }
            val cleanedBd = bdListener.lastLocation!!
            assertEquals(61, cleanedBd.getLocType())
            assertEquals("", cleanedBd.getAddrStr())
            assertTrue(cleanedBd.getPoiList().isEmpty())
            assertEquals(0, cleanedBd.mMockGpsStrategy)
            assertEquals(0, cleanedBd.mMockGpsProbability)
            val isMockGpsRes = backend.invokeMethod(
                E2eLbsSdkDoubles.BDLocation::class.java,
                "isMockGps",
                cleanedBd
            ) { cleanedBd.isMockGps() }
            assertEquals(false, isMockGpsRes.result)
            val disToRealRes = backend.invokeMethod(
                E2eLbsSdkDoubles.BDLocation::class.java,
                "getDisToRealLocation",
                cleanedBd
            ) { cleanedBd.getDisToRealLocation() }
            assertEquals(0.0f, disToRealRes.result as Float, 1e-6f)

            // 3. WiFi BSSID 掩码
            val wifiMock = Mockito.mock(WifiInfo::class.java)
            val bssidRes = backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiMock) { "aa:bb:cc:dd:ee:ff" }
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, bssidRes.result)
        } finally {
            cleanup.cleanupAll()
        }
    }

    @Test
    fun `T3-20 static build manifest resources and runtime IPC constants form closed-loop consistency`() {
        val manifestFile = WorkspacePathResolver.manifestFile()
        val xposedInitFile = WorkspacePathResolver.xposedInitFile()
        val arraysFile = WorkspacePathResolver.xposedScopeArraysFile()
        val resDir = WorkspacePathResolver.resDir()

        val manifestXml = manifestFile.readText(Charsets.UTF_8)
        val xposedInitContent = xposedInitFile.readText(Charsets.UTF_8)
        val arraysXml = arraysFile.readText(Charsets.UTF_8)

        val manifestReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(manifestXml)
        assertTrue("Manifest violations: ${manifestReport.violations}", manifestReport.passed)

        val initReport = StaticManifestAndResourceChecker.checkXposedInitContent(xposedInitContent)
        assertTrue("xposed_init violations: ${initReport.violations}", initReport.passed)
        assertEquals(XposedInit::class.java.name, xposedInitContent.trim())

        val arraysReport = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(arraysXml)
        assertTrue("arrays.xml violations: ${arraysReport.violations}", arraysReport.passed)

        val resReport = StaticManifestAndResourceChecker.checkResourcesAndNightSupport(resDir)
        assertTrue("Resource/night violations: ${resReport.violations}", resReport.passed)

        // 交叉核对代码运行时常量与 Manifest/arrays.xml 一致性
        assertEquals("mockloc_xposed_config", IpcProtocol.PREFS_NAME)
        assertEquals("com.mockloc.configprovider", IpcProtocol.PROVIDER_AUTHORITY)
        assertEquals("com.mockloc.ACTION_CONFIG_CHANGED", IpcProtocol.ACTION_CONFIG_CHANGED)
        assertEquals("com.mockloc", HookConfigStore.MOCKLOC_PACKAGE)
        assertTrue(manifestXml.contains(IpcProtocol.PROVIDER_AUTHORITY))
        assertTrue(manifestXml.contains("xposedsharedprefs"))

        val scopePackages = RecommendedScopeRepository.getXposedScopeArrayPackages()
        for (pkg in scopePackages) {
            assertTrue("arrays.xml missing $pkg", arraysXml.contains("<item>$pkg</item>"))
        }
        assertTrue(StaticManifestAndResourceChecker.CORE_RECOMMENDED_SCOPE_PACKAGES.all { it in scopePackages })
    }
}
