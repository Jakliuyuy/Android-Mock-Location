package com.mockloc.e2e.tier1

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.config.SafePrefs
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.CruiseEngine
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.MotionSimulator
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.math.SpatialMath
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.e2e.factory.InMemoryPreferencesOpener
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.infra.GeoCoordinateMathReference
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.hook.HookConfigStore
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.ipc.LocationConfigProvider
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.NoOpRootLocationDriver
import com.mockloc.service.PermissionChecker
import de.robv.android.xposed.IXposedHookLoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.random.Random

class Tier1CoreAndBuildE2ETest {

    @After
    fun tearDown() {
        MockConfigRepository.setInstanceForTest(null)
        HookConfigStore.shared.resetForTest()
    }

    private fun waitUntil(timeoutMs: Long = 3000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(10L)
        }
        return predicate()
    }

    private fun mockBroadcastIntent(action: String): Intent {
        val intent = Mockito.mock(Intent::class.java)
        var capturedAction: String? = action
        var capturedPackage: String? = null
        var capturedExtras: android.os.Bundle? = null

        Mockito.`when`(intent.action).thenAnswer { capturedAction }
        Mockito.`when`(intent.setAction(Mockito.any())).thenAnswer { inv ->
            capturedAction = inv.getArgument(0)
            intent
        }
        Mockito.`when`(intent.`package`).thenAnswer { capturedPackage }
        Mockito.`when`(intent.setPackage(Mockito.any())).thenAnswer { inv ->
            capturedPackage = inv.getArgument(0)
            intent
        }
        Mockito.`when`(intent.extras).thenAnswer { capturedExtras }
        Mockito.`when`(intent.putExtras(Mockito.any(android.os.Bundle::class.java))).thenAnswer { inv ->
            capturedExtras = inv.getArgument(0)
            intent
        }
        return intent
    }

    // ==================== F1: 核心配置模型与持久化 (5 tests) ====================

    @Test
    fun `T1-F01-01 MockConfig default construction satisfies WGS84 and domain contract`() {
        val config = MockConfig()

        assertFalse("Default enabled must be false", config.enabled)
        assertTrue("Default latitude out of range: ${config.latitude}", config.latitude in -90.0..90.0)
        assertTrue("Default longitude out of range: ${config.longitude}", config.longitude in -180.0..180.0)
        assertEquals(CoordinateType.WGS84, config.coordinateType)
        assertEquals(MockMode.AUTO, config.mode)
        assertNotNull(config.targetPackages)
        assertTrue("Default targetPackages should be empty safe set", config.targetPackages.isEmpty())
        assertTrue("Default version must be >= 0", config.version >= 0L)
        assertTrue("Default accuracy must be > 0", config.accuracy > 0f)
        assertTrue("Default jitterRadiusMeters must be >= 0", config.jitterRadiusMeters >= 0.0)
    }

    @Test
    fun `T1-F01-02 MockConfigRepository persists full non-default config and reloads losslessly`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val fixedTime = AtomicLong(1_700_123_456_000L)
            val repo1 = MockConfigRepository(
                prefs = prefs,
                isWorldReadable = true,
                ioScope = scope,
                debounceMs = 15L,
                timeProvider = { fixedTime.get() }
            )

            val saved = repo1.updateImmediate {
                MockConfig(
                    enabled = true,
                    mode = MockMode.LSPOSED_ONLY,
                    latitude = 31.2397,
                    longitude = 121.4998,
                    altitude = 25.0,
                    coordinateType = CoordinateType.WGS84,
                    accuracy = 2.5f,
                    speed = 4.5f,
                    bearing = 180.0f,
                    jitterEnabled = true,
                    jitterRadiusMeters = 3.0,
                    antiDetectCellEnabled = true,
                    antiDetectWifiEnabled = true,
                    antiDetectGnssEnabled = true,
                    targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap")
                )
            }

            assertTrue(saved.version >= 1L)
            assertTrue(saved.updatedAtMs > 0L)

            // 验证防抖更新及 flushPendingSync 持久化闭环
            fixedTime.set(1_700_123_457_000L)
            val debounced = repo1.updateDebounced { it.copy(bearing = 90.0f) }
            runBlocking { repo1.flushPendingSync() }

            val repo2 = MockConfigRepository(
                prefs = prefs,
                isWorldReadable = true,
                ioScope = scope,
                debounceMs = 15L
            )
            val loaded = repo2.currentConfig

            assertEquals(true, loaded.enabled)
            assertEquals(MockMode.LSPOSED_ONLY, loaded.mode)
            assertEquals(31.2397, loaded.latitude, 1e-6)
            assertEquals(121.4998, loaded.longitude, 1e-6)
            assertEquals(25.0, loaded.altitude, 1e-6)
            assertEquals(CoordinateType.WGS84, loaded.coordinateType)
            assertEquals(2.5f, loaded.accuracy, 1e-5f)
            assertEquals(4.5f, loaded.speed, 1e-5f)
            assertEquals(90.0f, loaded.bearing, 1e-5f)
            assertTrue(loaded.jitterEnabled)
            assertEquals(3.0, loaded.jitterRadiusMeters, 1e-6)
            assertTrue(loaded.antiDetectCellEnabled)
            assertTrue(loaded.antiDetectWifiEnabled)
            assertTrue(loaded.antiDetectGnssEnabled)
            assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), loaded.targetPackages)
            assertEquals(debounced.version, loaded.version)
            assertEquals(1_700_123_457_000L, loaded.updatedAtMs)
        } finally {
            scope.cancel()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `T1-F01-03 SafePrefs opens with MODE_WORLD_READABLE when LSPosed New XSP is supported`() {
        val opener = InMemoryPreferencesOpener(throwOnWorldReadable = false)
        val ctx = opener.asMockContext()

        val result = SafePrefs.open(ctx)

        assertTrue(result.isWorldReadable)
        assertEquals(listOf(Context.MODE_WORLD_READABLE), opener.requestedModes.toList())
        assertTrue(result.prefs.edit().putString("probe_key", "probe_val").commit())
        assertEquals("probe_val", result.prefs.getString("probe_key", null))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `T1-F01-04 SafePrefs gracefully falls back to MODE_PRIVATE when MODE_WORLD_READABLE throws SecurityException`() {
        val opener = InMemoryPreferencesOpener(throwOnWorldReadable = true)
        val ctx = opener.asMockContext()

        val result = SafePrefs.open(ctx)

        assertFalse(result.isWorldReadable)
        assertEquals(
            listOf(Context.MODE_WORLD_READABLE, Context.MODE_PRIVATE),
            opener.requestedModes.toList()
        )
        assertTrue(result.prefs.edit().putBoolean("fallback_ok", true).commit())
        assertTrue(result.prefs.getBoolean("fallback_ok", false))
    }

    @Test
    fun `T1-F01-05 MockConfigRepository authoritative WGS84 state and disable audit counters stay synchronized`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = MockConfigRepository(prefs = prefs, isWorldReadable = false, ioScope = scope)
            MockConfigRepository.setInstanceForTest(repo)

            val updated = repo.updateImmediate {
                it.copy(
                    enabled = true,
                    latitude = 22.5332,
                    longitude = 114.0545,
                    altitude = 10.0,
                    coordinateType = CoordinateType.WGS84,
                    targetPackages = setOf("com.tencent.mm")
                )
            }

            assertEquals(updated, repo.configFlow.value)
            assertEquals(updated, repo.currentConfig)
            val point = repo.currentConfig.toGeoPoint()
            assertEquals(22.5332, point.latitude, 1e-6)
            assertEquals(114.0545, point.longitude, 1e-6)
            assertTrue(repo.currentConfig.isEnabledForPackage("com.tencent.mm"))
            assertFalse(repo.currentConfig.isEnabledForPackage("com.other.app"))
            assertEquals(updated, ConfigSyncSerializer.fromPrefs(prefs))

            val prevDisableSeq = repo.engineDisableSeq
            val stopped = repo.updateImmediate { it.copy(enabled = false) }
            assertEquals(prevDisableSeq + 1L, repo.engineDisableSeq)
            assertEquals(stopped.version, repo.lastStopOrEngineDisabledVersion)
        } finally {
            scope.cancel()
        }
    }

    // ==================== F2: 多层跨进程配置同步 (5 tests) ====================

    @Test
    fun `T1-F02-01 ConfigSyncSerializer Bundle round-trip is lossless for non-default config`() {
        val original = MockConfig(
            enabled = true,
            mode = MockMode.ROOT_ONLY,
            latitude = 31.2397123456,
            longitude = 121.4998654321,
            altitude = 123.4567,
            coordinateType = CoordinateType.GCJ02,
            accuracy = 2.75f,
            speed = 8.25f,
            bearing = 275.5f,
            jitterEnabled = true,
            jitterRadiusMeters = 4.25,
            antiDetectCellEnabled = false,
            antiDetectWifiEnabled = true,
            antiDetectGnssEnabled = false,
            targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap"),
            updatedAtMs = 1_700_555_000_123L,
            version = 42L
        )

        val bundle = ConfigSyncSerializer.toBundle(original, StatefulFakeBundle().asAndroidBundle())
        val restored = ConfigSyncSerializer.fromBundle(bundle)

        assertEquals(original, restored)
    }

    @Test
    fun `T1-F02-02 ConfigSyncSerializer JSON round-trip is lossless including special characters`() {
        val original = TestConfigPayloads.ROOT_ACTIVE_CONFIG.toMockConfig().copy(
            coordinateType = CoordinateType.GCJ02,
            targetPackages = setOf("com.tencent.mm", "com.pkg.with_underscore", "com.example.a1"),
            updatedAtMs = 1_700_666_777_888L,
            version = 77L
        )

        val json = ConfigSyncSerializer.toJson(original)
        val restored = ConfigSyncSerializer.fromJson(json)

        assertEquals(original, restored)
    }

    @Test
    fun `T1-F02-03 ConfigSyncSerializer SharedPreferences round-trip uses PREFS_NAME and restores all fields`() {
        val opener = InMemoryPreferencesOpener()
        val prefs = opener.open(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
        val original = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
            mode = MockMode.ROOT_ONLY,
            coordinateType = CoordinateType.BD09,
            updatedAtMs = 1_700_888_999_000L,
            version = 88L
        )

        val editor = prefs.edit()
        ConfigSyncSerializer.writeToPrefs(editor, original)
        assertTrue(editor.commit())

        val restored = ConfigSyncSerializer.fromPrefs(prefs)
        assertEquals(original, restored)
    }

    @Test
    fun `T1-F02-04 LocationConfigProvider L3 METHOD_GET_CONFIG filters enabled per target package`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = MockConfigRepository(prefs = prefs, ioScope = scope)
            repo.updateImmediate {
                it.copy(
                    enabled = true,
                    latitude = 31.2397,
                    longitude = 121.4998,
                    targetPackages = setOf("com.tencent.mm")
                )
            }

            val provider = LocationConfigProvider(
                repositoryResolver = { repo },
                bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
            )
            val mockCtx = buildTestAppContext()
            provider.attachInfo(mockCtx, ProviderInfo().apply {
                authority = IpcProtocol.PROVIDER_AUTHORITY
            })

            val pingRes = provider.call(IpcProtocol.METHOD_PING, null, null)
            assertTrue(pingRes.getBoolean(IpcProtocol.RESULT_OK, false))
            assertEquals(repo.currentConfig.version, pingRes.getLong(IpcProtocol.KEY_VERSION, -1L))

            val allowedBundle = provider.call(IpcProtocol.METHOD_GET_CONFIG, "com.tencent.mm", null)
            assertTrue(allowedBundle.getBoolean(IpcProtocol.RESULT_OK, false))
            assertTrue(allowedBundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, false))
            assertTrue(allowedBundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, false))
            assertEquals(31.2397, allowedBundle.getDouble(IpcProtocol.KEY_LATITUDE, 0.0), 1e-6)
            assertEquals(121.4998, allowedBundle.getDouble(IpcProtocol.KEY_LONGITUDE, 0.0), 1e-6)

            val deniedBundle = provider.call(IpcProtocol.METHOD_GET_CONFIG, "com.other.app", null)
            assertTrue(deniedBundle.getBoolean(IpcProtocol.RESULT_OK, false))
            assertFalse(deniedBundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, true))
            assertTrue(deniedBundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, false))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `T1-F02-05 L2 explicit broadcast intent updates HookConfigStore idempotently by version`() {
        val store = HookConfigStore(prefsReaderFactory = { null })
        val cfg10 = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
            latitude = 31.2397,
            longitude = 121.4998,
            updatedAtMs = 1_700_010_000L,
            version = 10L
        )

        val spec = MockLocationForegroundService.buildConfigBroadcastSpec(cfg10, "com.tencent.mm")
        assertEquals(IpcProtocol.ACTION_CONFIG_CHANGED, spec.action)
        assertEquals("com.tencent.mm", spec.targetPackage)

        val intent10 = MockLocationForegroundService.buildConfigBroadcastIntent(
            config = cfg10,
            targetPackage = "com.tencent.mm",
            intentFactory = { action -> mockBroadcastIntent(action) },
            bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
        )
        assertEquals("com.tencent.mm", intent10.`package`)
        assertTrue(store.onBroadcastReceived(intent10))
        assertEquals(10L, store.peekCurrentConfig().version)
        assertEquals(31.2397, store.peekCurrentConfig().latitude, 1e-6)
        assertTrue(store.peekCurrentConfig().enabled)

        // 相同版本和内容再次收到不产生重复变更
        assertFalse(store.onBroadcastReceived(intent10))

        // 更高版本更新生效
        val cfg11 = cfg10.copy(latitude = 39.9087, longitude = 116.3975, version = 11L)
        val intent11 = MockLocationForegroundService.buildConfigBroadcastIntent(
            config = cfg11,
            targetPackage = "com.tencent.mm",
            intentFactory = { action -> mockBroadcastIntent(action) },
            bundleFactory = { StatefulFakeBundle().asAndroidBundle() }
        )
        assertTrue(store.onBroadcastReceived(intent11))
        assertEquals(11L, store.peekCurrentConfig().version)
        assertEquals(39.9087, store.peekCurrentConfig().latitude, 1e-6)
    }

    // ==================== F3: 前台保活与权限校验引擎 (5 tests) ====================

    @Test
    fun `T1-F03-01 startSingleMock enters SingleRunning when location and notification permissions are granted`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext(
                hasFineLocation = true,
                hasCoarseLocation = true,
                hasNotification = true
            )
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val fgStarted = AtomicBoolean(false)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                rootDriver = NoOpRootLocationDriver,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { start -> fgStarted.set(start); true },
                scope = scope,
                tickIntervalMs = 50L
            )
            controller = MockEngineController(
                coordinator = coordinator,
                permissionCheck = { PermissionChecker.check(ctx, 34) },
                dispatcher = Dispatchers.Unconfined
            )

            val tiananmen = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
            controller.startSingleMock(tiananmen, TestConfigPayloads.DEFAULT_IDLE_CONFIG.toMockConfig())

            val state = controller.runtimeState.value
            assertEquals(MockRunStatus.SingleRunning, state.status)
            assertEquals(tiananmen, state.currentPoint)
            assertNull(state.errorMessage)
            assertTrue(fgStarted.get())
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T1-F03-02 updateSingleTarget moves active single mock point and refreshes lastSyncTimeMs`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val clock = AtomicLong(10_000L)
            val ctx = buildTestAppContext(hasFineLocation = true, hasCoarseLocation = true, hasNotification = true)
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 200L,
                timeProvider = { clock.get() }
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            val tiananmen = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
            val lujiazui = TestCoordinates.SHANGHAI_LUJIAZUI.toGeoPoint()
            controller.startSingleMock(tiananmen, MockConfig(jitterEnabled = false))
            val sync1 = controller.runtimeState.value.lastSyncTimeMs

            clock.set(20_000L)
            controller.updateSingleTarget(lujiazui)

            val state = controller.runtimeState.value
            assertEquals(MockRunStatus.SingleRunning, state.status)
            assertEquals(lujiazui, state.currentPoint)
            assertTrue(state.lastSyncTimeMs > sync1)
            assertEquals(lujiazui.latitude, repo.currentConfig.latitude, 1e-6)
            assertEquals(lujiazui.longitude, repo.currentConfig.longitude, 1e-6)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T1-F03-03 ACCESS_COARSE_LOCATION alone satisfies PermissionChecker and starts single mock`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext(
                hasFineLocation = false,
                hasCoarseLocation = true,
                hasNotification = true
            )
            val perm = PermissionChecker.check(ctx, 34)
            assertTrue(perm.hasLocationPermission)
            assertTrue(perm.canStartForegroundLocation)
            assertNull(perm.errorMessage)

            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 100L
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            controller.startSingleMock(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), MockConfig())
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T1-F03-04 startCruise advances MockRuntimeState progressFraction and waypoint index`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext(hasFineLocation = true, hasCoarseLocation = true, hasNotification = true)
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 20L
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            // 构造总长约 20 米的 3 航点 PING_PONG 路线，以 10 m/s 行进确保多个 tick 内持续处于 CruiseRunning 并推进进度
            val p1 = GeoPoint(39.90870, 116.39750)
            val p2 = SpatialMath.offsetByMeters(p1, 10.0, 0.0)
            val p3 = SpatialMath.offsetByMeters(p2, 0.0, 10.0)
            val route = PlannedRoute(
                id = "t1_f03_04",
                name = "短距测试巡航",
                waypoints = listOf(Waypoint(p1, "A"), Waypoint(p2, "B"), Waypoint(p3, "C"))
            )
            val cruiseCfg = CruiseConfig(
                speedMps = 10.0,
                loopMode = LoopMode.PING_PONG,
                enableSpeedNoise = false,
                enableMicroDrift = false
            )

            controller.startCruise(route, cruiseCfg, MockConfig())
            assertEquals(MockRunStatus.CruiseRunning, controller.runtimeState.value.status)
            assertEquals(3, controller.runtimeState.value.totalWaypoints)

            val advanced = waitUntil(2000L) {
                val st = controller.runtimeState.value
                st.status == MockRunStatus.CruiseRunning && st.progressFraction > 0.05f && st.speedMps > 0.0
            }
            assertTrue("Cruise progress should advance in runtimeState", advanced)
            val state = controller.runtimeState.value
            assertEquals(MockRunStatus.CruiseRunning, state.status)
            assertTrue(state.activeWaypointIndex in 0..2)
            assertTrue(state.progressFraction in 0f..1f)
            assertTrue(state.speedMps > 0.0)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T1-F03-05 stopMock resets runtime state to Idle, zeroes speed and progress, and disables MockConfig`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext(hasFineLocation = true, hasCoarseLocation = true, hasNotification = true)
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val fgState = AtomicBoolean(false)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { start -> fgState.set(start); true },
                scope = scope,
                tickIntervalMs = 50L
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            controller.startSingleMock(
                TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(),
                MockConfig(speed = 3.0f)
            )
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)
            assertTrue(repo.currentConfig.enabled)

            controller.stopMock()

            val state = controller.runtimeState.value
            assertEquals(MockRunStatus.Idle, state.status)
            assertEquals(0.0, state.speedMps, 1e-6)
            assertEquals(0f, state.progressFraction, 1e-6f)
            assertFalse(repo.currentConfig.enabled)
            assertFalse(fgState.get())
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    // ==================== F4: 坐标系双向转换引擎 (5 tests) ====================

    @Test
    fun `T1-F04-01 isOutOfChina identifies major Chinese cities as inside China`() {
        val domesticPoints = listOf(
            TestCoordinates.BEIJING_TIANANMEN,
            TestCoordinates.SHANGHAI_LUJIAZUI,
            TestCoordinates.LHASA_POTALA,
            TestCoordinates.SHENZHEN_NANSHAN
        )
        for (pt in domesticPoints) {
            assertFalse(
                "Expected inside China for (${pt.latitude}, ${pt.longitude})",
                CoordinateConverter.isOutOfChina(pt.latitude, pt.longitude)
            )
        }
    }

    @Test
    fun `T1-F04-02 isOutOfChina identifies foreign cities and applies identity conversion`() {
        val foreignPoints = listOf(
            TestCoordinates.TOKYO_STATION,
            TestCoordinates.NEW_YORK_TIMES_SQUARE,
            TestCoordinates.LONDON
        )
        for (pt in foreignPoints) {
            assertTrue(CoordinateConverter.isOutOfChina(pt.latitude, pt.longitude))
            val gcj = CoordinateConverter.wgs84ToGcj02(pt.latitude, pt.longitude)
            val bd = CoordinateConverter.wgs84ToBd09(pt.latitude, pt.longitude)
            val backFromGcj = CoordinateConverter.gcj02ToWgs84(pt.latitude, pt.longitude)
            val backFromBd = CoordinateConverter.bd09ToWgs84(pt.latitude, pt.longitude)

            assertEquals(pt.latitude, gcj.latitude, 0.0)
            assertEquals(pt.longitude, gcj.longitude, 0.0)
            assertEquals(pt.latitude, bd.latitude, 0.0)
            assertEquals(pt.longitude, bd.longitude, 0.0)
            assertEquals(pt.latitude, backFromGcj.latitude, 0.0)
            assertEquals(pt.longitude, backFromGcj.longitude, 0.0)
            assertEquals(pt.latitude, backFromBd.latitude, 0.0)
            assertEquals(pt.longitude, backFromBd.longitude, 0.0)
        }
    }

    @Test
    fun `T1-F04-03 WGS84 and GCJ02 round-trip accuracy in China is within 1e-5 degrees and matches reference`() {
        val points = listOf(
            TestCoordinates.BEIJING_TIANANMEN,
            TestCoordinates.SHENZHEN_NANSHAN
        )
        for (pt in points) {
            val gcj = CoordinateConverter.wgs84ToGcj02(pt.latitude, pt.longitude)
            val refGcj = GeoCoordinateMathReference.wgs84ToGcj02(pt.latitude, pt.longitude)
            assertEquals(refGcj.lat, gcj.latitude, 1e-6)
            assertEquals(refGcj.lon, gcj.longitude, 1e-6)

            val offsetMeters = SpatialMath.calculateHaversineDistance(
                pt.latitude, pt.longitude, gcj.latitude, gcj.longitude
            )
            assertTrue("GCJ02 offset should be 100..800m, was $offsetMeters", offsetMeters in 100.0..800.0)

            val restored = CoordinateConverter.gcj02ToWgs84(gcj.latitude, gcj.longitude)
            assertTrue(abs(restored.latitude - pt.latitude) < 1e-5)
            assertTrue(abs(restored.longitude - pt.longitude) < 1e-5)
        }
    }

    @Test
    fun `T1-F04-04 GCJ02 and BD09 round-trip accuracy is within 1e-5 degrees and matches reference`() {
        val lujiazuiGcj = CoordinateConverter.wgs84ToGcj02(
            TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
            TestCoordinates.SHANGHAI_LUJIAZUI.longitude
        )
        val bd = CoordinateConverter.gcj02ToBd09(lujiazuiGcj.latitude, lujiazuiGcj.longitude)
        val refBd = GeoCoordinateMathReference.gcj02ToBd09(lujiazuiGcj.latitude, lujiazuiGcj.longitude)

        assertEquals(refBd.lat, bd.latitude, 1e-6)
        assertEquals(refBd.lon, bd.longitude, 1e-6)
        assertTrue(bd.latitude - lujiazuiGcj.latitude > 0.004)
        assertTrue(bd.longitude - lujiazuiGcj.longitude > 0.004)

        val restoredGcj = CoordinateConverter.bd09ToGcj02(bd.latitude, bd.longitude)
        assertTrue(abs(restoredGcj.latitude - lujiazuiGcj.latitude) < 1e-5)
        assertTrue(abs(restoredGcj.longitude - lujiazuiGcj.longitude) < 1e-5)
    }

    @Test
    fun `T1-F04-05 WGS84 to BD09 direct and stepped conversions agree within 1e-7 and invert accurately`() {
        val wgs = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
        val directBd = CoordinateConverter.wgs84ToBd09(wgs.latitude, wgs.longitude)
        val gcj = CoordinateConverter.wgs84ToGcj02(wgs.latitude, wgs.longitude)
        val steppedBd = CoordinateConverter.gcj02ToBd09(gcj.latitude, gcj.longitude)
        val viaConvert = CoordinateConverter.convert(wgs, CoordinateType.WGS84, CoordinateType.BD09)

        assertTrue(abs(directBd.latitude - steppedBd.latitude) < 1e-7)
        assertTrue(abs(directBd.longitude - steppedBd.longitude) < 1e-7)
        assertEquals(directBd.latitude, viaConvert.latitude, 1e-9)
        assertEquals(directBd.longitude, viaConvert.longitude, 1e-9)

        val restoredWgs = CoordinateConverter.bd09ToWgs84(directBd.latitude, directBd.longitude)
        assertTrue(abs(restoredWgs.latitude - wgs.latitude) < 1e-5)
        assertTrue(abs(restoredWgs.longitude - wgs.longitude) < 1e-5)
    }

    // ==================== F5: 巡航状态机修复与多航点支持 (5 tests) ====================

    @Test
    fun `T1-F05-01 ONE_WAY 4-waypoint cruise visits segments 0-1-2 and finishes in COMPLETED at D`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 30.0, 0.0)
        val c = SpatialMath.offsetByMeters(b, 0.0, 30.0)
        val d = SpatialMath.offsetByMeters(c, 30.0, 0.0)
        val route = PlannedRoute(
            id = "r_4wp",
            name = "4点测试路线",
            waypoints = listOf(Waypoint(a, "A"), Waypoint(b, "B"), Waypoint(c, "C"), Waypoint(d, "D"))
        )
        val engine = CruiseEngine(MotionSimulator(Random(1234L)))
        engine.loadRoute(
            route,
            CruiseConfig(
                speedMps = 10.0,
                loopMode = LoopMode.ONE_WAY,
                enableSpeedNoise = false,
                enableMicroDrift = false
            )
        )
        engine.start()

        val visitedIndices = linkedSetOf(engine.currentSnapshot().activeWaypointIndex)
        var steps = 0
        while (!engine.isFinished() && steps < 50) {
            val out = engine.advanceStep(1.0)
            visitedIndices.add(out.activeWaypointIndex)
            steps++
        }

        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertTrue(visitedIndices.containsAll(listOf(0, 1, 2)))
        val snap = engine.currentSnapshot()
        assertTrue(snap.isCompleted)
        assertEquals(1.0f, snap.progressFraction, 1e-5f)
        assertEquals(d.latitude, snap.point.latitude, 1e-6)
        assertEquals(d.longitude, snap.point.longitude, 1e-6)
    }

    @Test
    fun `T1-F05-02 ONE_WAY COMPLETED resets cleanly and restarts from waypoint A on subsequent start`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 50.0, 0.0)
        val route = PlannedRoute("r_restart", "重启路线", listOf(Waypoint(a, "A"), Waypoint(b, "B")))
        val engine = CruiseEngine(MotionSimulator(Random(42L)))
        engine.loadRoute(
            route,
            CruiseConfig(speedMps = 20.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()
        repeat(10) {
            if (!engine.isFinished()) engine.advanceStep(1.0)
        }
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)

        // 完结后直接再次 start() 必须自动复位从起点重新运行
        engine.start()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        val step1 = engine.advanceStep(1.0)
        assertFalse(step1.isCompleted)
        assertEquals(0, step1.activeWaypointIndex)
        assertEquals(20.0, step1.distanceTraveledMeters, 1e-3)
        assertTrue(step1.progressFraction in 0.3f..0.5f)
        assertTrue(SpatialMath.calculateHaversineDistance(a, step1.point) in 19.0..21.0)
    }

    @Test
    fun `T1-F05-03 PING_PONG reverses bearing and LOOP closes circuit without entering COMPLETED`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 20.0, 0.0)
        val c = SpatialMath.offsetByMeters(b, 20.0, 0.0)
        val route = PlannedRoute("r_cycle", "循环测试", listOf(Waypoint(a, "A"), Waypoint(b, "B"), Waypoint(c, "C")))

        // PING_PONG 测试
        val pingPong = CruiseEngine(MotionSimulator(Random(1L)))
        pingPong.loadRoute(
            route,
            CruiseConfig(speedMps = 10.0, loopMode = LoopMode.PING_PONG, enableSpeedNoise = false, enableMicroDrift = false)
        )
        pingPong.start()
        val bearings = mutableListOf<Float>()
        repeat(10) {
            val out = pingPong.advanceStep(1.0)
            bearings.add(out.bearingDeg)
            assertEquals(CruiseEngine.State.RUNNING, pingPong.state)
            assertFalse(out.isCompleted)
        }
        val hasNorth = bearings.any { it < 15f || it > 345f }
        val hasSouth = bearings.any { abs(it - 180f) < 15f }
        assertTrue("PING_PONG should travel north (~0 deg): $bearings", hasNorth)
        assertTrue("PING_PONG should reverse south (~180 deg): $bearings", hasSouth)

        // LOOP 测试
        val loop = CruiseEngine(MotionSimulator(Random(2L)))
        loop.loadRoute(
            route,
            CruiseConfig(speedMps = 10.0, loopMode = LoopMode.LOOP, enableSpeedNoise = false, enableMicroDrift = false)
        )
        loop.start()
        var wrappedToStart = false
        repeat(10) {
            val out = loop.advanceStep(1.0)
            assertEquals(CruiseEngine.State.RUNNING, loop.state)
            assertFalse(out.isCompleted)
            if (it >= 7 && SpatialMath.calculateHaversineDistance(a, out.point) < 15.0) {
                wrappedToStart = true
            }
        }
        assertTrue("LOOP mode should return toward waypoint A via closing segment", wrappedToStart)
    }

    @Test
    fun `T1-F05-04 pause resume and runtime speed and jitter updates take effect immediately`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 500.0, 0.0)
        val route = PlannedRoute("r_ctrl", "控制测试", listOf(Waypoint(a, "A"), Waypoint(b, "B")))
        val engine = CruiseEngine(MotionSimulator(Random(99L)))
        engine.loadRoute(
            route,
            CruiseConfig(
                speedMps = 4.0,
                loopMode = LoopMode.ONE_WAY,
                enableSpeedNoise = true,
                speedNoiseRatio = 0.5,
                enableMicroDrift = false
            )
        )
        engine.start()
        val beforePause = engine.advanceStep(1.0)
        assertTrue(beforePause.distanceTraveledMeters > 0.0)

        engine.pause()
        assertEquals(CruiseEngine.State.PAUSED, engine.state)
        val pausedOut = engine.advanceStep(2.0)
        assertEquals(0.0, pausedOut.speedMps, 1e-6)
        assertEquals(beforePause.distanceTraveledMeters, pausedOut.distanceTraveledMeters, 1e-6)

        engine.resume()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        engine.updateSpeed(12.0)
        engine.updateJitter(true, maxDriftRadiusMeters = 2.5)

        val resumedOut = engine.advanceStep(1.0)
        assertTrue("Speed after updateSpeed(12.0) should be in [6.0, 18.0], was ${resumedOut.speedMps}", resumedOut.speedMps in 6.0..18.0)
        assertTrue(resumedOut.distanceTraveledMeters > beforePause.distanceTraveledMeters + 5.0)
    }

    @Test
    fun `T1-F05-05 per-waypoint targetSpeedMps and dwellTimeSeconds control segment pace and stops`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 10.0, 0.0)
        val c = SpatialMath.offsetByMeters(b, 40.0, 0.0)
        val route = PlannedRoute(
            id = "r_dwell",
            name = "分段变速与停留",
            waypoints = listOf(
                Waypoint(a, "WP0", dwellTimeSeconds = 0.0, targetSpeedMps = 2.0f),
                Waypoint(b, "WP1", dwellTimeSeconds = 3.0, targetSpeedMps = 8.0f),
                Waypoint(c, "WP2", dwellTimeSeconds = 0.0, targetSpeedMps = null)
            )
        )
        val engine = CruiseEngine(MotionSimulator(Random(7L)))
        engine.loadRoute(
            route,
            CruiseConfig(speedMps = 5.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()

        // 0..4s: 按 2.0 m/s 走 8m
        repeat(4) {
            val out = engine.advanceStep(1.0)
            assertEquals(2.0, out.speedMps, 1e-6)
            assertEquals(0, out.activeWaypointIndex)
        }
        // 第 5s: 抵达 WP1，开始 3s 停留
        val arriveB = engine.advanceStep(1.0)
        assertEquals(1, arriveB.activeWaypointIndex)

        // 接下来 2 秒在 WP1 驻留（speedMps == 0.0）
        repeat(2) {
            val dwellOut = engine.advanceStep(1.0)
            assertEquals(0.0, dwellOut.speedMps, 1e-6)
            assertEquals(1, dwellOut.activeWaypointIndex)
            assertEquals(10.0, dwellOut.distanceTraveledMeters, 1e-2)
        }

        // 第 8s 耗尽剩余 1s 停留，第 9s 按 WP1 targetSpeedMps = 8.0 m/s 行进
        engine.advanceStep(1.0)
        val seg2Out = engine.advanceStep(1.0)
        assertEquals(8.0, seg2Out.speedMps, 1e-6)
        assertTrue(seg2Out.distanceTraveledMeters > 17.0)
    }

    // ==================== F18: 全量自动化测试与 APK 交付 (5 tests) ====================

    @Test
    fun `T1-F18-01 AndroidManifest declares all required Android 14-17 permissions without maxSdkVersion`() {
        val manifestXml = WorkspacePathResolver.manifestFile().readText(Charsets.UTF_8)
        val report = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(manifestXml)
        assertTrue("Manifest compliance violations: ${report.violations}", report.passed)

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(StringReader(manifestXml)))
        val perms = doc.getElementsByTagName("uses-permission")
        val declared = mutableSetOf<String>()
        val ns = "http://schemas.android.com/apk/res/android"
        for (i in 0 until perms.length) {
            val el = perms.item(i) as org.w3c.dom.Element
            val name = el.getAttributeNS(ns, "name").ifEmpty { el.getAttribute("android:name") }
            if (name.isNotEmpty()) declared.add(name)
        }
        val expected = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.INTERNET",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.ACCESS_NETWORK_STATE"
        )
        for (p in expected) {
            assertTrue("Missing permission: $p", p in declared)
        }
    }

    @Test
    fun `T1-F18-02 AndroidManifest declares foregroundServiceType location exported false and LocationConfigProvider`() {
        val manifestXml = WorkspacePathResolver.manifestFile().readText(Charsets.UTF_8)
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(StringReader(manifestXml)))

        val services = doc.getElementsByTagName("service")
        var fgService: org.w3c.dom.Element? = null
        for (i in 0 until services.length) {
            val el = services.item(i) as org.w3c.dom.Element
            val name = el.getAttributeNS(ns, "name").ifEmpty { el.getAttribute("android:name") }
            if (name.endsWith("MockLocationForegroundService")) {
                fgService = el
                break
            }
        }
        assertNotNull("MockLocationForegroundService must be declared in AndroidManifest.xml", fgService)
        assertEquals("false", fgService!!.getAttributeNS(ns, "exported").ifEmpty { fgService.getAttribute("android:exported") })
        assertTrue(
            fgService.getAttributeNS(ns, "foregroundServiceType")
                .ifEmpty { fgService.getAttribute("android:foregroundServiceType") }
                .contains("location")
        )

        val providers = doc.getElementsByTagName("provider")
        var cfgProvider: org.w3c.dom.Element? = null
        for (i in 0 until providers.length) {
            val el = providers.item(i) as org.w3c.dom.Element
            val name = el.getAttributeNS(ns, "name").ifEmpty { el.getAttribute("android:name") }
            if (name.endsWith("LocationConfigProvider")) {
                cfgProvider = el
                break
            }
        }
        assertNotNull("LocationConfigProvider must be declared in AndroidManifest.xml", cfgProvider)
        assertEquals(
            IpcProtocol.PROVIDER_AUTHORITY,
            cfgProvider!!.getAttributeNS(ns, "authorities").ifEmpty { cfgProvider.getAttribute("android:authorities") }
        )
        assertEquals("true", cfgProvider.getAttributeNS(ns, "exported").ifEmpty { cfgProvider.getAttribute("android:exported") })
    }

    @Test
    fun `T1-F18-03 AndroidManifest declares LSPosed New XSharedPreferences metadata`() {
        val manifestXml = WorkspacePathResolver.manifestFile().readText(Charsets.UTF_8)
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(StringReader(manifestXml)))

        val metas = doc.getElementsByTagName("meta-data")
        val metaMap = mutableMapOf<String, org.w3c.dom.Element>()
        for (i in 0 until metas.length) {
            val el = metas.item(i) as org.w3c.dom.Element
            val name = el.getAttributeNS(ns, "name").ifEmpty { el.getAttribute("android:name") }
            if (name.isNotEmpty()) metaMap[name] = el
        }

        fun attr(el: org.w3c.dom.Element?, key: String): String? =
            el?.let { it.getAttributeNS(ns, key).ifEmpty { it.getAttribute("android:$key") } }?.ifEmpty { null }

        assertEquals("true", attr(metaMap["xposedmodule"], "value"))
        assertEquals("true", attr(metaMap["xposedsharedprefs"], "value"))
        val minVer = attr(metaMap["xposedminversion"], "value")?.toIntOrNull()
        assertNotNull(minVer)
        assertTrue("xposedminversion must be >= 93, got $minVer", minVer!! >= 93)
        assertFalse(attr(metaMap["xposeddescription"], "value").isNullOrBlank())
        assertEquals("@array/xposed_scope", attr(metaMap["xposedscope"], "resource"))
    }

    @Test
    fun `T1-F18-04 assets xposed_init points to loadable IXposedHookLoadPackage class com_mockloc_hook_XposedInit`() {
        val xposedInitContent = WorkspacePathResolver.xposedInitFile().readText(Charsets.UTF_8)
        val report = StaticManifestAndResourceChecker.checkXposedInitContent(xposedInitContent)
        assertTrue("xposed_init violations: ${report.violations}", report.passed)

        val entryClassName = xposedInitContent.lineSequence()
            .map { it.trim() }
            .first { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals("com.mockloc.hook.XposedInit", entryClassName)

        val loadedCls = Class.forName(entryClassName)
        assertTrue(
            "XposedInit must implement IXposedHookLoadPackage",
            IXposedHookLoadPackage::class.java.isAssignableFrom(loadedCls)
        )
        val ctor = loadedCls.getDeclaredConstructor()
        assertNotNull(ctor)
    }

    @Test
    fun `T1-F18-05 build configuration, assets, sources, and debug APK artifact if built satisfy delivery contract`() {
        val appDir = WorkspacePathResolver.resolveAppDir()
        val gradleFile = WorkspacePathResolver.appBuildGradleFile()
        val mainJavaDir = WorkspacePathResolver.mainJavaDir()
        val xposedInit = WorkspacePathResolver.xposedInitFile()

        assertTrue("app/build.gradle.kts must exist", gradleFile.isFile)
        assertTrue("main java source dir must exist", mainJavaDir.isDirectory)
        assertTrue("assets/xposed_init must exist", xposedInit.isFile)
        assertEquals("com.mockloc.hook.XposedInit", xposedInit.readText(Charsets.UTF_8).trim())

        val gradleText = gradleFile.readText(Charsets.UTF_8)
        assertTrue(gradleText.contains("applicationId = \"com.mockloc\""))
        assertTrue(gradleText.contains("viewBinding = true"))

        val debugApk = File(appDir, "build/outputs/apk/debug/app-debug.apk")
        if (debugApk.isFile) {
            assertTrue("APK must be non-empty", debugApk.length() > 100_000L)
            ZipFile(debugApk).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toList()
                assertTrue("APK must contain AndroidManifest.xml", "AndroidManifest.xml" in entries)
                assertTrue("APK must contain assets/xposed_init", "assets/xposed_init" in entries)
                assertTrue(
                    "APK must contain compiled classes*.dex",
                    entries.any { it.matches(Regex("^classes\\d*\\.dex$")) }
                )
                val apkInitContent = zip.getInputStream(zip.getEntry("assets/xposed_init"))
                    .bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                assertEquals("com.mockloc.hook.XposedInit", apkInitContent)
            }
        }
    }
}
