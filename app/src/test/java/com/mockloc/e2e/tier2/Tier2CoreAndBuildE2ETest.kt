package com.mockloc.e2e.tier2

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import com.mockloc.e2e.factory.TestRouteSpecs
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toGeoPoint
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toPlannedRoute
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import com.mockloc.hook.AndroidProviderConfigFetcher
import com.mockloc.hook.HookConfigStore
import com.mockloc.hook.PrefsReader as HookPrefsReader
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.service.MockEngineController
import com.mockloc.service.MockEngineCoordinator
import com.mockloc.service.MockLocationForegroundService
import com.mockloc.service.NoOpRootLocationDriver
import com.mockloc.service.PermissionChecker
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class Tier2CoreAndBuildE2ETest {

    @After
    fun tearDown() {
        MockConfigRepository.setInstanceForTest(null)
        HookConfigStore.shared.resetForTest()
    }

    private fun mockIntent(action: String?, extras: android.os.Bundle? = null): Intent {
        val intent = Mockito.mock(Intent::class.java)
        Mockito.`when`(intent.action).thenReturn(action)
        Mockito.`when`(intent.extras).thenReturn(extras)
        return intent
    }

    private fun loadMainKotlinSources(): Map<String, String> {
        val mainJavaDir = WorkspacePathResolver.mainJavaDir()
        assertTrue("Expected main java dir at ${mainJavaDir.absolutePath}", mainJavaDir.isDirectory)
        return mainJavaDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { file ->
                file.relativeTo(mainJavaDir).path.replace('\\', '/') to file.readText(Charsets.UTF_8)
            }
    }

    // ==================== F1: 核心配置模型与持久化边界与异常 (5 tests) ====================

    @Test
    fun `T2-F01-01 out-of-range and non-finite coordinates sanitize safely across model, bundle, and json`() {
        val outOfBounds = MockConfig(
            enabled = true,
            latitude = 90.0001,
            longitude = -180.5,
            altitude = 100.0
        )
        val nonFinite = MockConfig(
            enabled = true,
            latitude = Double.NaN,
            longitude = Double.POSITIVE_INFINITY,
            altitude = Double.NEGATIVE_INFINITY
        )

        val sanitizedBounds = outOfBounds.sanitized()
        assertTrue(sanitizedBounds.latitude in -90.0..90.0)
        assertTrue(sanitizedBounds.longitude in -180.0..180.0)

        val sanitizedNonFinite = nonFinite.sanitized()
        assertTrue(sanitizedNonFinite.latitude.isFinite() && sanitizedNonFinite.latitude in -90.0..90.0)
        assertTrue(sanitizedNonFinite.longitude.isFinite() && sanitizedNonFinite.longitude in -180.0..180.0)
        assertTrue(sanitizedNonFinite.altitude.isFinite())

        // Bundle 注入越界/NaN 反序列化校验
        val badBundle = StatefulFakeBundle().asAndroidBundle().apply {
            putBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, true)
            putDouble(IpcProtocol.KEY_LATITUDE, 90.0001)
            putDouble(IpcProtocol.KEY_LONGITUDE, -180.5)
        }
        val fromBadBundle = ConfigSyncSerializer.fromBundle(badBundle)
        assertTrue(fromBadBundle.latitude in -90.0..90.0)
        assertTrue(fromBadBundle.longitude in -180.0..180.0)

        val nanBundle = StatefulFakeBundle().asAndroidBundle().apply {
            putDouble(IpcProtocol.KEY_LATITUDE, Double.NaN)
            putDouble(IpcProtocol.KEY_LONGITUDE, Double.POSITIVE_INFINITY)
        }
        val fromNanBundle = ConfigSyncSerializer.fromBundle(nanBundle)
        assertTrue(fromNanBundle.latitude.isFinite() && fromNanBundle.latitude in -90.0..90.0)
        assertTrue(fromNanBundle.longitude.isFinite() && fromNanBundle.longitude in -180.0..180.0)

        // JSON 往返校验
        val fromJson = ConfigSyncSerializer.fromJson(ConfigSyncSerializer.toJson(nonFinite))
        assertTrue(fromJson.latitude.isFinite() && fromJson.latitude in -90.0..90.0)
        assertTrue(fromJson.longitude.isFinite() && fromJson.longitude in -180.0..180.0)
    }

    @Test
    fun `T2-F01-02 corrupted SharedPreferences value types and unknown enum names fall back safely`() {
        val prefs = InMemorySharedPreferences()
        prefs.injectRawValue(IpcProtocol.KEY_ACCURACY, "not_a_float")
        prefs.injectRawValue(IpcProtocol.KEY_LATITUDE, "invalid_double")
        prefs.injectRawValue(IpcProtocol.KEY_LONGITUDE, true)
        prefs.injectRawValue(IpcProtocol.KEY_IS_MOCK_ENABLED, 12345)
        prefs.injectRawValue(IpcProtocol.KEY_MOCK_MODE, "UNKNOWN_MODE")
        prefs.injectRawValue(IpcProtocol.KEY_COORDINATE_TYPE, "UNKNOWN_COORD")
        prefs.injectRawValue(IpcProtocol.KEY_TARGET_PACKAGES, 999)
        prefs.injectRawValue(IpcProtocol.KEY_VERSION, "bad_long")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = MockConfigRepository(prefs = prefs, ioScope = scope)
            val cfg = repo.currentConfig
            assertEquals(MockMode.AUTO, cfg.mode)
            assertEquals(CoordinateType.WGS84, cfg.coordinateType)
            assertEquals(MockConfig.DEFAULT_ACCURACY, cfg.accuracy, 1e-6f)
            assertEquals(MockConfig.DEFAULT_LATITUDE, cfg.latitude, 1e-6)
            assertEquals(MockConfig.DEFAULT_LONGITUDE, cfg.longitude, 1e-6)
            assertFalse(cfg.enabled)
            assertTrue(cfg.targetPackages.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `T2-F01-03 negative accuracy speed jitterRadiusMeters and out-of-range bearing clamp safely`() {
        val cfg = MockConfig(
            accuracy = -5.0f,
            speed = -10.0f,
            jitterRadiusMeters = -2.0,
            bearing = 725.0f
        ).sanitized()

        assertTrue("accuracy must be > 0, was ${cfg.accuracy}", cfg.accuracy > 0f)
        assertTrue("speed must be >= 0, was ${cfg.speed}", cfg.speed >= 0f)
        assertTrue("jitterRadiusMeters must be >= 0, was ${cfg.jitterRadiusMeters}", cfg.jitterRadiusMeters >= 0.0)
        assertTrue("bearing must be in [0, 360), was ${cfg.bearing}", cfg.bearing >= 0f && cfg.bearing < 360f)
        assertEquals(5.0f, cfg.bearing, 1e-4f)

        val cfg2 = MockConfig(
            accuracy = 0.0f,
            speed = Float.NaN,
            jitterRadiusMeters = Double.NaN,
            bearing = -45.0f
        ).sanitized()
        assertTrue(cfg2.accuracy > 0f)
        assertEquals(0.0f, cfg2.speed, 1e-6f)
        assertEquals(0.0, cfg2.jitterRadiusMeters, 1e-6)
        assertEquals(315.0f, cfg2.bearing, 1e-4f)
    }

    @Test
    fun `T2-F01-04 20 concurrent threads x 100 operations on MockConfigRepository maintain consistency`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = Executors.newFixedThreadPool(20)
        try {
            val prefs = InMemorySharedPreferences()
            val repo = MockConfigRepository(prefs = prefs, ioScope = scope, debounceMs = 5L)

            val threadCount = 20
            val opsPerThread = 100
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val firstError = AtomicReference<Throwable?>(null)

            for (t in 0 until threadCount) {
                executor.submit {
                    try {
                        startLatch.await()
                        for (i in 0 until opsPerThread) {
                            val lat = 30.0 + ((t * 100 + i) % 1000) * 0.001
                            val pkg = "com.test.app$t"
                            if (i % 3 == 0) {
                                repo.updateImmediate { cur ->
                                    cur.copy(
                                        enabled = true,
                                        latitude = lat,
                                        longitude = 120.0 + t * 0.01,
                                        targetPackages = cur.targetPackages + pkg
                                    )
                                }
                            } else if (i % 3 == 1) {
                                repo.updateDebounced { cur ->
                                    cur.copy(latitude = lat)
                                }
                            } else {
                                val snap = repo.configFlow.value
                                assertTrue(snap.latitude in -90.0..90.0)
                                assertTrue(snap.longitude in -180.0..180.0)
                                // 迭代 targetPackages 验证无并发修改异常
                                snap.targetPackages.forEach { assertTrue(it.isNotBlank()) }
                            }
                        }
                    } catch (err: Throwable) {
                        firstError.compareAndSet(null, err)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            startLatch.countDown()
            assertTrue("Concurrent stress test timed out", doneLatch.await(15, TimeUnit.SECONDS))
            firstError.get()?.let { throw AssertionError("Concurrent error occurred", it) }

            runBlocking { repo.flushPendingSync() }
            val finalCfg = repo.currentConfig
            assertTrue(finalCfg.version > 0L)
            assertTrue(finalCfg.targetPackages.isNotEmpty())
        } finally {
            executor.shutdownNow()
            scope.cancel()
        }
    }

    @Test
    fun `T2-F01-05 MockConfigRepository survives RuntimeException from SharedPreferences reads and writes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val brokenEditor = Mockito.mock(SharedPreferences.Editor::class.java) {
                throw RuntimeException("Simulated disk write failure")
            }
            val brokenPrefs = Mockito.mock(SharedPreferences::class.java) { inv ->
                when (inv.method.name) {
                    "edit" -> brokenEditor
                    else -> throw RuntimeException("Simulated prefs read failure")
                }
            }

            val repo = MockConfigRepository(prefs = brokenPrefs, ioScope = scope, debounceMs = 10L)
            assertEquals(MockConfig.DEFAULT, repo.currentConfig)

            val updated = repo.updateImmediate { it.copy(enabled = true, latitude = 31.2397) }
            assertTrue(updated.enabled)
            assertEquals(31.2397, updated.latitude, 1e-6)

            // reloadFromPrefs 遇到读取异常应保持当前安全状态不崩溃
            val reloaded = repo.reloadFromPrefs()
            assertEquals(31.2397, reloaded.latitude, 1e-6)

            // 同时验证 SafePrefs 在双模式均抛出 RuntimeException 时的行为边界
            val openerAllFail = InMemoryPreferencesOpener(throwOnWorldReadable = true, throwOnAllModes = true)
            var safePrefsThrown: Throwable? = null
            try {
                SafePrefs.open(openerAllFail.asMockContext())
            } catch (t: Throwable) {
                safePrefsThrown = t
            }
            assertNotNull("InMemoryPreferencesOpener(throwOnAllModes=true) should propagate RuntimeException if MODE_PRIVATE fails", safePrefsThrown)
        } finally {
            scope.cancel()
        }
    }

    // ==================== F2: 多层跨进程配置同步边界与异常 (5 tests) ====================

    @Test
    fun `T2-F02-01 L3 ContentProvider queries isolation exceptions degrade safely to null and preserve L1 config`() {
        val crSec = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(
            crSec.call(
                Mockito.nullable(Uri::class.java),
                Mockito.anyString(),
                Mockito.nullable(String::class.java),
                Mockito.nullable(android.os.Bundle::class.java)
            )
        ).thenThrow(SecurityException("Provider invisible under Android 11+ <queries>"))
        val ctxSec = buildTestAppContext(contentResolver = crSec)
        assertNull(AndroidProviderConfigFetcher.fetchGlobalConfig(ctxSec))

        val crArg = Mockito.mock(ContentResolver::class.java)
        Mockito.`when`(
            crArg.call(
                Mockito.nullable(Uri::class.java),
                Mockito.anyString(),
                Mockito.nullable(String::class.java),
                Mockito.nullable(android.os.Bundle::class.java)
            )
        ).thenThrow(IllegalArgumentException("Unknown authority"))
        val ctxArg = buildTestAppContext(contentResolver = crArg)
        assertNull(AndroidProviderConfigFetcher.fetchGlobalConfig(ctxArg))

        // L1 有有效配置时，L3 抛异常不崩溃且保留 L1 配置
        val l1Cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig()
        val l1Reader = object : HookPrefsReader {
            override fun hasFileChanged(): Boolean = false
            override fun reload() {}
            override fun readConfig(fallback: MockConfig): MockConfig = l1Cfg
        }
        val store = HookConfigStore(prefsReaderFactory = { l1Reader })
        assertFalse(store.fetchFromL3ProviderSafe(ctxSec))
        val resolved = store.getConfig(ctxSec, forceRefresh = true)
        assertEquals(l1Cfg.latitude, resolved.latitude, 1e-6)
        assertEquals(l1Cfg.targetPackages, resolved.targetPackages)
    }

    @Test
    fun `T2-F02-02 corrupted JSON payloads never throw JSONException and fall back to safe MockConfig`() {
        for (badJson in TestConfigPayloads.CORRUPTED_JSON_PAYLOADS) {
            val parsed = ConfigSyncSerializer.fromJson(badJson)
            assertFalse("Corrupted JSON must default enabled=false: $badJson", parsed.enabled)
            assertTrue(parsed.latitude.isFinite() && parsed.latitude in -90.0..90.0)
            assertTrue(parsed.longitude.isFinite() && parsed.longitude in -180.0..180.0)
            assertTrue(parsed.accuracy > 0f)
        }
    }

    @Test
    fun `T2-F02-03 null empty partial and extreme Bundle inputs degrade safely without corrupting fallback`() {
        val healthy = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig()

        assertEquals(healthy, ConfigSyncSerializer.fromBundle(null, healthy))
        assertEquals(healthy, ConfigSyncSerializer.fromBundle(StatefulFakeBundle().asAndroidBundle(), healthy))

        val partialBundle = StatefulFakeBundle().asAndroidBundle().apply {
            putFloat(IpcProtocol.KEY_SPEED, 88.0f)
        }
        val partialMerged = ConfigSyncSerializer.fromBundle(partialBundle, healthy)
        assertEquals(88.0f, partialMerged.speed, 1e-5f)
        assertEquals(healthy.latitude, partialMerged.latitude, 1e-6)
        assertEquals(healthy.targetPackages, partialMerged.targetPackages)

        val extremeBundle = StatefulFakeBundle().asAndroidBundle().apply {
            putDouble(IpcProtocol.KEY_LATITUDE, Double.MAX_VALUE)
            putDouble(IpcProtocol.KEY_LONGITUDE, -Double.MAX_VALUE)
        }
        val extremeMerged = ConfigSyncSerializer.fromBundle(extremeBundle, healthy)
        assertEquals(90.0, extremeMerged.latitude, 1e-6)
        assertEquals(-180.0, extremeMerged.longitude, 1e-6)
    }

    @Test
    fun `T2-F02-04 5000-package whitelist round-trips cleanly across Bundle JSON and SharedPreferences`() {
        val largeCfg = TestConfigPayloads.createLargeWhitelistConfig(5000).toMockConfig()
        assertEquals(5000, largeCfg.targetPackages.size)

        val fromBundle = ConfigSyncSerializer.fromBundle(
            ConfigSyncSerializer.toBundle(largeCfg, StatefulFakeBundle().asAndroidBundle())
        )
        assertEquals(5000, fromBundle.targetPackages.size)
        assertEquals(largeCfg.targetPackages, fromBundle.targetPackages)

        val fromJson = ConfigSyncSerializer.fromJson(ConfigSyncSerializer.toJson(largeCfg))
        assertEquals(5000, fromJson.targetPackages.size)
        assertEquals(largeCfg.targetPackages, fromJson.targetPackages)

        val prefs = InMemorySharedPreferences()
        prefs.edit().also {
            ConfigSyncSerializer.writeToPrefs(it, largeCfg)
            assertTrue(it.commit())
        }
        val fromPrefs = ConfigSyncSerializer.fromPrefs(prefs)
        assertEquals(5000, fromPrefs.targetPackages.size)
        assertEquals(largeCfg.targetPackages, fromPrefs.targetPackages)
    }

    @Test
    fun `T2-F02-05 broadcast receiver ignores null extras wrong action and older config versions`() {
        val store = HookConfigStore(prefsReaderFactory = { null })
        val highCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
            latitude = 31.2397,
            longitude = 121.4998,
            updatedAtMs = 2_000_000L,
            version = 20L
        )
        assertTrue(store.applyIfNewer(highCfg))

        // 1. null intent & 空 extras & 错误 action
        assertFalse(store.onBroadcastReceived(null))
        assertFalse(store.onBroadcastReceived(mockIntent(IpcProtocol.ACTION_CONFIG_CHANGED, null)))
        assertFalse(
            store.onBroadcastReceived(
                mockIntent(
                    "com.other.ACTION",
                    ConfigSyncSerializer.toBundle(
                        highCfg.copy(updatedAtMs = 3_000_000L, version = 30L),
                        StatefulFakeBundle().asAndroidBundle()
                    )
                )
            )
        )

        // 2. 更小 updatedAtMs 即使 version 更大也必须忽略
        val staleTimeBundle = ConfigSyncSerializer.toBundle(
            highCfg.copy(latitude = 10.0, updatedAtMs = 1_000_000L, version = 99L),
            StatefulFakeBundle().asAndroidBundle()
        )
        assertFalse(store.onBroadcastReceived(mockIntent(IpcProtocol.ACTION_CONFIG_CHANGED, staleTimeBundle)))

        // 3. 相同 updatedAtMs 但更小 version 必须忽略
        val staleVersionBundle = ConfigSyncSerializer.toBundle(
            highCfg.copy(latitude = 10.0, updatedAtMs = 2_000_000L, version = 5L),
            StatefulFakeBundle().asAndroidBundle()
        )
        assertFalse(store.onBroadcastReceived(mockIntent(IpcProtocol.ACTION_CONFIG_CHANGED, staleVersionBundle)))

        val snap = store.peekCurrentConfig()
        assertEquals(20L, snap.version)
        assertEquals(31.2397, snap.latitude, 1e-6)
    }

    // ==================== F3: 前台保活与权限校验引擎边界与异常 (5 tests) ====================

    @Test
    fun `T2-F03-01 missing both FINE and COARSE location permissions enters Error without calling startForeground`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val noLocPerm = PermissionChecker.evaluateStartForegroundEligibility(
                hasFineLocation = false,
                hasCoarseLocation = false,
                hasNotification = true
            )
            assertFalse(noLocPerm.canStartForegroundLocation)
            assertNotNull(noLocPerm.errorMessage)

            val fgLaunchCount = AtomicInteger(0)
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { noLocPerm },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { start ->
                    if (start) fgLaunchCount.incrementAndGet()
                    true
                },
                scope = scope
            )
            controller = MockEngineController(coordinator, { noLocPerm }, Dispatchers.Unconfined)

            controller.startSingleMock(TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(), MockConfig())
            assertTrue(controller.runtimeState.value.status is MockRunStatus.Error)
            assertNotNull(controller.runtimeState.value.errorMessage)
            assertEquals(0, fgLaunchCount.get())

            controller.startCruise(
                TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute(),
                CruiseConfig(),
                MockConfig()
            )
            assertTrue(controller.runtimeState.value.status is MockRunStatus.Error)
            assertEquals(0, fgLaunchCount.get())
            assertFalse(repo.currentConfig.enabled)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T2-F03-02 missing POST_NOTIFICATIONS on Android 13+ is flagged by PermissionChecker and notification sync does not crash`() {
        val ctx = buildTestAppContext(
            hasFineLocation = true,
            hasCoarseLocation = true,
            hasNotification = false
        )

        val api34Perm = PermissionChecker.check(ctx, 34)
        assertTrue(api34Perm.hasLocationPermission)
        assertFalse(api34Perm.hasNotificationPermission)
        assertTrue(api34Perm.canStartForegroundLocation)

        // Android 12 及以下不需要运行时 POST_NOTIFICATIONS
        val api32Perm = PermissionChecker.check(ctx, 32)
        assertTrue(api32Perm.hasNotificationPermission)

        // 验证通知同步逻辑在非通知授权情况下无异常
        val updates = AtomicInteger(0)
        val sync = MockLocationForegroundService.ServiceNotificationSync(
            isForegroundActive = { true },
            onUpdateNotification = { _, _ -> updates.incrementAndGet() },
            onIdleCleanup = {}
        )
        val runningState = com.mockloc.domain.model.MockRuntimeState(
            status = MockRunStatus.SingleRunning,
            currentPoint = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
        )
        sync.onStateChanged(runningState)
        assertEquals(1, updates.get())
    }

    @Test
    fun `T2-F03-03 pauseCruise resumeCruise and stopMock in Idle are idempotent no-ops`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext()
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
            controller.pauseCruise()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
            controller.resumeCruise()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
            controller.stopMock()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T2-F03-04 empty route cruise and invalid NaN coordinates transition to Error without starting mock`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext()
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val baseline = repo.updateImmediate {
                it.copy(enabled = false, latitude = 39.9087, longitude = 116.3975)
            }

            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            // 1. 空航点路线启动巡航
            controller.startCruise(TestRouteSpecs.EMPTY_ROUTE.toPlannedRoute(), CruiseConfig(), baseline)
            assertTrue(controller.runtimeState.value.status is MockRunStatus.Error)
            assertNotNull(controller.runtimeState.value.errorMessage)
            assertFalse(repo.currentConfig.enabled)

            // 2. GeoPoint 构造阶段即拒绝 NaN
            var ctorThrew = false
            try {
                GeoPoint(Double.NaN, 116.3975)
            } catch (_: IllegalArgumentException) {
                ctorThrew = true
            }
            assertTrue("GeoPoint(NaN, ...) must throw IllegalArgumentException", ctorThrew)

            // 3. 若外部传入含 NaN 的伪造 GeoPoint 对象，Controller/Coordinator 也必须拦截进入 Error 而不污染配置
            val invalidPoint = Mockito.mock(GeoPoint::class.java)
            Mockito.`when`(invalidPoint.latitude).thenReturn(Double.NaN)
            Mockito.`when`(invalidPoint.longitude).thenReturn(116.3975)
            controller.startSingleMock(invalidPoint, baseline)

            assertTrue(controller.runtimeState.value.status is MockRunStatus.Error)
            assertFalse(repo.currentConfig.enabled)
            assertEquals(39.9087, repo.currentConfig.latitude, 1e-6)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    @Test
    fun `T2-F03-05 switching directly between SingleRunning and CruiseRunning cancels prior coroutines cleanly`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var controller: MockEngineController? = null
        try {
            val ctx = buildTestAppContext()
            val repo = MockConfigRepository(InMemorySharedPreferences(), ioScope = scope)
            val observedStatuses = mutableListOf<MockRunStatus>()
            val coordinator = MockEngineCoordinator(
                configRepository = repo,
                permissionProvider = { PermissionChecker.check(ctx, 34) },
                broadcastDispatcher = { _, _ -> },
                foregroundLauncher = { true },
                scope = scope,
                tickIntervalMs = 20L
            )
            controller = MockEngineController(coordinator, { PermissionChecker.check(ctx, 34) }, Dispatchers.Unconfined)

            controller.startSingleMock(
                TestCoordinates.BEIJING_TIANANMEN.toGeoPoint(),
                MockConfig(jitterEnabled = true, jitterRadiusMeters = 2.0)
            )
            Thread.sleep(60L)
            observedStatuses.add(controller.runtimeState.value.status)
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)

            // 直接切换至巡航
            val route = TestRouteSpecs.CLOSED_LOOP_4WP.toPlannedRoute()
            controller.startCruise(
                route,
                CruiseConfig(speedMps = 5.0, loopMode = LoopMode.LOOP, enableSpeedNoise = false, enableMicroDrift = false),
                MockConfig()
            )
            Thread.sleep(80L)
            assertEquals(MockRunStatus.CruiseRunning, controller.runtimeState.value.status)

            // 再直接切换回单点
            val targetLujiazui = TestCoordinates.SHANGHAI_LUJIAZUI.toGeoPoint()
            controller.startSingleMock(targetLujiazui, MockConfig(jitterEnabled = false))
            Thread.sleep(60L)
            assertEquals(MockRunStatus.SingleRunning, controller.runtimeState.value.status)
            val currentPt = controller.runtimeState.value.currentPoint!!
            assertEquals(targetLujiazui.latitude, currentPt.latitude, 1e-4)
            assertEquals(targetLujiazui.longitude, currentPt.longitude, 1e-4)

            controller.stopMock()
            assertEquals(MockRunStatus.Idle, controller.runtimeState.value.status)
        } finally {
            controller?.release()
            scope.cancel()
        }
    }

    // ==================== F4: 坐标系双向转换引擎边界与异常 (5 tests) ====================

    @Test
    fun `T2-F04-01 global extreme coordinates stay finite and valid across all conversions`() {
        val extremes = listOf(
            TestCoordinates.EQUATOR_PRIME_MERIDIAN,
            TestCoordinates.NORTH_POLE,
            TestCoordinates.SOUTH_POLE,
            TestCoordinates.ANTIMERIDIAN_POS,
            TestCoordinates.ANTIMERIDIAN_NEG
        )
        for (pt in extremes) {
            assertTrue(CoordinateConverter.isOutOfChina(pt.latitude, pt.longitude))
            val results = listOf(
                CoordinateConverter.wgs84ToGcj02(pt.latitude, pt.longitude),
                CoordinateConverter.gcj02ToWgs84(pt.latitude, pt.longitude),
                CoordinateConverter.gcj02ToBd09(pt.latitude, pt.longitude),
                CoordinateConverter.bd09ToGcj02(pt.latitude, pt.longitude),
                CoordinateConverter.wgs84ToBd09(pt.latitude, pt.longitude),
                CoordinateConverter.bd09ToWgs84(pt.latitude, pt.longitude)
            )
            for (res in results) {
                assertTrue(res.latitude.isFinite() && res.latitude in -90.0..90.0)
                assertTrue(res.longitude.isFinite() && res.longitude in -180.0..180.0)
            }
        }
    }

    @Test
    fun `T2-F04-02 China bounding box boundary value analysis distinguishes inside and outside`() {
        assertFalse(CoordinateConverter.isOutOfChina(39.9, 73.67))
        assertFalse(CoordinateConverter.isOutOfChina(3.86, 73.66))
        assertFalse(CoordinateConverter.isOutOfChina(53.55, 135.05))

        assertTrue(CoordinateConverter.isOutOfChina(39.9, 73.65))
        assertTrue(CoordinateConverter.isOutOfChina(53.56, 120.0))
        assertTrue(CoordinateConverter.isOutOfChina(3.85, 100.0))
        assertTrue(CoordinateConverter.isOutOfChina(30.0, 135.06))

        val outerGcj = CoordinateConverter.wgs84ToGcj02(39.9, 73.65)
        assertEquals(39.9, outerGcj.latitude, 0.0)
        assertEquals(73.65, outerGcj.longitude, 0.0)

        val innerGcj = CoordinateConverter.wgs84ToGcj02(39.9, 73.67)
        assertTrue(abs(innerGcj.latitude - 39.9) > 1e-5 || abs(innerGcj.longitude - 73.67) > 1e-5)
    }

    @Test
    fun `T2-F04-03 CoordinateConverter handles NaN Infinity and out-of-bounds inputs without uncaught exceptions`() {
        val badInputs = listOf(
            Double.NaN to 116.4,
            39.9 to Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY to Double.NaN,
            95.0 to 200.0,
            -95.0 to -200.0
        )

        for ((lat, lon) in badInputs) {
            assertTrue(CoordinateConverter.isOutOfChina(lat, lon))
            val outputs = listOf(
                CoordinateConverter.wgs84ToGcj02(lat, lon),
                CoordinateConverter.gcj02ToWgs84(lat, lon),
                CoordinateConverter.gcj02ToBd09(lat, lon),
                CoordinateConverter.bd09ToGcj02(lat, lon),
                CoordinateConverter.wgs84ToBd09(lat, lon),
                CoordinateConverter.bd09ToWgs84(lat, lon)
            )
            for (out in outputs) {
                assertTrue("Output lat must be finite and in [-90, 90], got ${out.latitude}", out.latitude.isFinite() && out.latitude in -90.0..90.0)
                assertTrue("Output lon must be finite and in [-180, 180], got ${out.longitude}", out.longitude.isFinite() && out.longitude in -180.0..180.0)
            }
        }
    }

    @Test
    fun `T2-F04-04 SpatialMath handles coincident points and antipodal points without NaN or zero division`() {
        val tiananmen = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
        assertEquals(0.0, SpatialMath.calculateHaversineDistance(tiananmen, tiananmen), 1e-9)
        val sameBearing = SpatialMath.calculateBearing(tiananmen, tiananmen)
        assertTrue(sameBearing.isFinite())
        assertEquals(0f, sameBearing, 1e-6f)
        val sameInterp = SpatialMath.interpolateGreatCircle(tiananmen, tiananmen, 0.5)
        assertEquals(tiananmen.latitude, sameInterp.latitude, 1e-9)
        assertEquals(tiananmen.longitude, sameInterp.longitude, 1e-9)

        val p1 = GeoPoint(0.0, 0.0)
        val p2 = GeoPoint(0.0, 180.0)
        val antipodalDist = SpatialMath.calculateHaversineDistance(p1, p2)
        val expectedHalfCircumference = PI * SpatialMath.EARTH_RADIUS_METERS
        assertEquals(expectedHalfCircumference, antipodalDist, 1.0)

        val midAntipodal = SpatialMath.interpolateGreatCircle(p1, p2, 0.5)
        assertTrue(midAntipodal.latitude.isFinite() && midAntipodal.latitude in -90.0..90.0)
        assertTrue(midAntipodal.longitude.isFinite() && midAntipodal.longitude in -180.0..180.0)
    }

    @Test
    fun `T2-F04-05 GeoPoint constructor accepts exact bounds and rejects out-of-range or non-finite values`() {
        val maxPt = GeoPoint(90.0, 180.0)
        val minPt = GeoPoint(-90.0, -180.0)
        assertEquals(90.0, maxPt.latitude, 0.0)
        assertEquals(-180.0, minPt.longitude, 0.0)

        val invalidPairs = listOf(
            90.0001 to 0.0,
            -90.0001 to 0.0,
            0.0 to 180.0001,
            0.0 to -180.0001,
            Double.NaN to 0.0,
            0.0 to Double.POSITIVE_INFINITY
        )
        for ((lat, lon) in invalidPairs) {
            var threw = false
            try {
                GeoPoint(lat, lon)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("Expected IllegalArgumentException for GeoPoint($lat, $lon)", threw)
        }
    }

    // ==================== F5: 巡航状态机边界与异常 (6 tests) ====================

    @Test
    fun `T2-F05-01 empty route throws IllegalStateException on start and advanceStep handles safely`() {
        val engine = CruiseEngine(MotionSimulator(Random(1L)))
        engine.loadRoute(TestRouteSpecs.EMPTY_ROUTE.toPlannedRoute(), CruiseConfig())

        var threwStart = false
        try {
            engine.start()
        } catch (_: IllegalStateException) {
            threwStart = true
        }
        assertTrue("start() on empty route must throw IllegalStateException", threwStart)

        val step = engine.advanceStep(1.0)
        assertEquals(CruiseEngine.State.STOPPED, engine.state)
        assertEquals(0, step.totalWaypoints)
        assertFalse(step.isCompleted)
    }

    @Test
    fun `T2-F05-02 single-waypoint route differentiates ONE_WAY dwell completion vs LOOP micro-drift`() {
        val singleRoute = TestRouteSpecs.SINGLE_POINT_ROUTE.toPlannedRoute()
        val anchor = singleRoute.waypoints.first().point

        val oneWay = CruiseEngine(MotionSimulator(Random(10L)))
        oneWay.loadRoute(singleRoute, CruiseConfig(loopMode = LoopMode.ONE_WAY, enableMicroDrift = false))
        oneWay.start()
        val firstStep = oneWay.advanceStep(1.0)
        assertEquals(CruiseEngine.State.RUNNING, oneWay.state)
        assertFalse(firstStep.isCompleted)
        val secondStep = oneWay.advanceStep(1.0)
        assertEquals(CruiseEngine.State.COMPLETED, oneWay.state)
        assertTrue(secondStep.isCompleted)

        val loop = CruiseEngine(MotionSimulator(Random(20L)))
        loop.loadRoute(
            singleRoute,
            CruiseConfig(loopMode = LoopMode.LOOP, enableMicroDrift = true, maxDriftRadiusMeters = 2.0)
        )
        loop.start()
        repeat(10) {
            val out = loop.advanceStep(1.0)
            assertEquals(CruiseEngine.State.RUNNING, loop.state)
            assertFalse(out.isCompleted)
            assertTrue(out.point.latitude.isFinite() && out.point.longitude.isFinite())
            val drift = SpatialMath.calculateHaversineDistance(anchor, out.point)
            assertTrue("Single-point LOOP drift $drift exceeded 2.05m", drift <= 2.05)
        }
    }

    @Test
    fun `T2-F05-03 zero speed stalls distance cleanly and negative speed fails CruiseConfig validation`() {
        val route = TestRouteSpecs.BEIJING_COMMUTE_3WP.toPlannedRoute()
        val engine = CruiseEngine(MotionSimulator(Random(3L)))
        engine.loadRoute(
            route,
            CruiseConfig(speedMps = 0.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()
        val startPt = route.waypoints.first().point

        // 注意：第一航点若 targetSpeedMps 非空会采用航点速度，测试明确构造无 targetSpeedMps 航点确保以 0.0 m/s 行进
        val zeroRoute = PlannedRoute(
            id = "r_zero",
            name = "零速路线",
            waypoints = listOf(
                Waypoint(startPt, "A"),
                Waypoint(SpatialMath.offsetByMeters(startPt, 50.0, 0.0), "B")
            )
        )
        engine.loadRoute(
            zeroRoute,
            CruiseConfig(speedMps = 0.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()
        val out = engine.advanceStep(10.0)
        assertEquals(0.0, out.distanceTraveledMeters, 1e-6)
        assertEquals(startPt.latitude, out.point.latitude, 1e-9)
        assertEquals(startPt.longitude, out.point.longitude, 1e-9)

        var threwNeg = false
        try {
            CruiseConfig(speedMps = -1.0)
        } catch (_: IllegalArgumentException) {
            threwNeg = true
        }
        assertTrue("CruiseConfig(speedMps = -1.0) must throw IllegalArgumentException", threwNeg)
    }

    @Test
    fun `T2-F05-04 coincident consecutive waypoints and huge deltaSeconds advance safely without infinite loop`() {
        val route = TestRouteSpecs.COINCIDENT_POINTS_ROUTE.toPlannedRoute()
        val engine = CruiseEngine(MotionSimulator(Random(4L)))
        engine.loadRoute(
            route,
            CruiseConfig(speedMps = 10.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()

        val out = engine.advanceStep(10_000.0)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        assertTrue(out.isCompleted)
        val lastWp = route.waypoints.last().point
        assertEquals(lastWp.latitude, out.point.latitude, 1e-6)
        assertEquals(lastWp.longitude, out.point.longitude, 1e-6)
    }

    @Test
    fun `T2-F05-05 MotionSimulator keeps 1000-step OU drift bounded and handles non-positive deltaSeconds`() {
        val sim = MotionSimulator(Random(42L))
        val anchor = TestCoordinates.BEIJING_TIANANMEN.toGeoPoint()
        val maxRadius = 4.0

        for (i in 0 until 1000) {
            val drifted = sim.applyMicroDrift(anchor, maxRadius, enableDrift = true, deltaSeconds = 1.0)
            val dist = SpatialMath.calculateHaversineDistance(anchor, drifted)
            assertTrue("Step $i OU drift $dist exceeded bound 4.05m", dist <= 4.05)

            val speed = sim.nextSpeed(baseSpeedMps = 10.0, noiseRatio = 0.5, enableNoise = true, deltaSeconds = 1.0)
            assertTrue("Step $i speed $speed out of [5.0, 15.0]", speed in 5.0..15.0)
        }

        // dt <= 0 边界返回原坐标不漂移
        assertEquals(anchor, sim.applyMicroDrift(anchor, maxRadius, enableDrift = true, deltaSeconds = 0.0))
        assertEquals(anchor, sim.applyMicroDrift(anchor, maxRadius, enableDrift = true, deltaSeconds = -1.0))
    }

    @Test
    fun `T2-F05-06 pause and resume after ONE_WAY COMPLETED do not corrupt terminal state until start or reset`() {
        val a = GeoPoint(39.90870, 116.39750)
        val b = SpatialMath.offsetByMeters(a, 15.0, 0.0)
        val route = PlannedRoute("r_terminal", "终态保护", listOf(Waypoint(a, "A"), Waypoint(b, "B")))
        val engine = CruiseEngine(MotionSimulator(Random(5L)))
        engine.loadRoute(
            route,
            CruiseConfig(speedMps = 20.0, loopMode = LoopMode.ONE_WAY, enableSpeedNoise = false, enableMicroDrift = false)
        )
        engine.start()
        engine.advanceStep(2.0)
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)

        engine.pause()
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)
        engine.resume()
        assertEquals(CruiseEngine.State.COMPLETED, engine.state)

        engine.start()
        assertEquals(CruiseEngine.State.RUNNING, engine.state)
        assertEquals(0.0, engine.currentSnapshot().distanceTraveledMeters, 1e-6)
    }

    // ==================== F18: 构建与合规审计边界 (5 tests) ====================

    @Test
    fun `T2-F18-01 static manifest audit passes real manifest and rejects exported service or missing exported attr`() {
        val realManifest = WorkspacePathResolver.manifestFile().readText(Charsets.UTF_8)
        val realReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(realManifest)
        assertTrue("Real manifest compliance violations: ${realReport.violations}", realReport.passed)

        val exposedServiceManifest = realManifest.replace(
            Regex("""(<service\b[^>]*MockLocationForegroundService[^>]*android:exported=")false(")"""),
            "$1true$2"
        )
        val exposedReport = StaticManifestAndResourceChecker.checkAndroid14To17ManifestCompliance(exposedServiceManifest)
        assertFalse(exposedReport.passed)
        assertTrue(exposedReport.violations.any { it.contains("MockLocationForegroundService") && it.contains("exported") })
    }

    @Test
    fun `T2-F18-02 static source audit enforces non-blocking suspend EnvironmentDetector and zero main-thread su or network`() {
        val realSources = loadMainKotlinSources()
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(realSources)
        assertTrue("Source compliance violations: ${report.violations}", report.passed)

        val envDetectorSource = realSources.entries.firstOrNull { it.key.endsWith("EnvironmentDetector.kt") }?.value
        assertNotNull("EnvironmentDetector.kt must exist", envDetectorSource)
        assertTrue(
            "EnvironmentDetector.detectEnvironment must be suspend",
            Regex("""suspend\s+fun\s+detectEnvironment\s*\(""").containsMatchIn(envDetectorSource!!)
        )
    }

    @Test
    fun `T2-F18-03 static source audit enforces RECEIVER_EXPORTED or NOT_EXPORTED and PendingIntent FLAG_IMMUTABLE`() {
        val realSources = loadMainKotlinSources()
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(realSources)
        assertTrue("Android 14-17 receiver/PendingIntent violations: ${report.violations}", report.passed)

        val badReceiver = mapOf(
            "hook/BadReceiver.kt" to """
                class BadReceiver {
                    fun reg(ctx: Context, r: BroadcastReceiver, f: IntentFilter) {
                        ctx.registerReceiver(r, f)
                    }
                    fun pi(ctx: Context, i: Intent) = PendingIntent.getBroadcast(ctx, 0, i, 0)
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(badReceiver)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("registerReceiver") })
        assertTrue(badReport.violations.any { it.contains("FLAG_IMMUTABLE") })
    }

    @Test
    fun `T2-F18-04 static source audit verifies zero stub placeholders and zero direct interface hookAllMethods`() {
        val realSources = loadMainKotlinSources()
        val report = StaticManifestAndResourceChecker.checkSourceComplianceRules(realSources)
        assertTrue("Stub/interface hook violations: ${report.violations}", report.passed)

        val stubBad = mapOf(
            "hook/BadHook.kt" to """
                class BadHook {
                    fun hook(cl: ClassLoader) {
                        val cls = XposedHelpers.findClass("com.amap.api.location.AMapLocationListener", cl)
                        XposedBridge.hookAllMethods(cls, "onLocationChanged", cb)
                    }
                    fun stub() { TODO("not implemented") }
                }
            """.trimIndent()
        )
        val badReport = StaticManifestAndResourceChecker.checkSourceComplianceRules(stubBad)
        assertFalse(badReport.passed)
        assertTrue(badReport.violations.any { it.contains("hookAllMethods") })
        assertTrue(badReport.violations.any { it.contains("Stub placeholder") })
    }

    @Test
    fun `T2-F18-05 build gradle configuration meets Android 14-17 SDK targets and required offline dependencies`() {
        val gradleText = WorkspacePathResolver.appBuildGradleFile().readText(Charsets.UTF_8)

        val compileSdk = Regex("""compileSdk\s*=\s*(\d+)""").find(gradleText)?.groupValues?.get(1)?.toIntOrNull()
        val targetSdk = Regex("""targetSdk\s*=\s*(\d+)""").find(gradleText)?.groupValues?.get(1)?.toIntOrNull()
        val minSdk = Regex("""minSdk\s*=\s*(\d+)""").find(gradleText)?.groupValues?.get(1)?.toIntOrNull()

        assertNotNull("compileSdk must be declared", compileSdk)
        assertNotNull("targetSdk must be declared", targetSdk)
        assertNotNull("minSdk must be declared", minSdk)
        assertTrue("compileSdk must be >= 35, was $compileSdk", compileSdk!! >= 35)
        assertTrue("targetSdk must be >= 34, was $targetSdk", targetSdk!! >= 34)
        assertTrue("minSdk must be >= 26, was $minSdk", minSdk!! >= 26)

        assertTrue(gradleText.contains("com.google.android.material:material"))
        assertTrue(gradleText.contains("kotlinx-coroutines-core"))
        assertTrue(gradleText.contains("de.robv.android.xposed:api"))
        assertTrue(gradleText.contains("junit:junit"))
        assertTrue(gradleText.contains("viewBinding = true"))
    }
}
