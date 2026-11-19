package com.mockloc.e2e.tier2

import android.app.AppOpsManager
import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellLocation
import android.telephony.TelephonyManager
import com.mockloc.data.config.MockConfig
import com.mockloc.e2e.factory.ByteFieldsMaskLocationStub
import com.mockloc.e2e.factory.ChildHiddenFieldsLocationStub
import com.mockloc.e2e.factory.E2eFakeLocation
import com.mockloc.e2e.factory.E2eHookBackend
import com.mockloc.e2e.factory.E2eLbsSdkDoubles
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.FakeTestProviderBackend
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.XposedParamFactory
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.infra.GeoCoordinateMathReference
import com.mockloc.hook.ClassLoaderHookHelper
import com.mockloc.hook.HookConfigStore
import com.mockloc.hook.LocationHook
import com.mockloc.hook.NetworkAntiDetectHook
import com.mockloc.hook.ProviderConfigFetcher
import com.mockloc.hook.ThirdPartyLbsSdkHook
import com.mockloc.hook.XposedInit
import com.mockloc.hook.cleaner.MockLocationCleaner
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import com.mockloc.root.ShellExecutor
import com.mockloc.root.ShellResult
import com.mockloc.root.ShellUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * E2E Tier 2 边界、异常与安全容错测试套件（F6–F11，共 30 用例）。
 */
class Tier2RootAndHookE2ETest {

    private fun newIsolatedClassLoader(parent: ClassLoader? = javaClass.classLoader): ClassLoader =
        object : ClassLoader(parent) {}

    private fun fakeLocationFactory(provider: String, cfg: MockConfig): Location {
        return E2eFakeLocation(
            provider = provider,
            latitude = cfg.latitude,
            longitude = cfg.longitude,
            altitude = cfg.altitude,
            accuracy = cfg.accuracy.coerceAtLeast(1.0f),
            bearing = cfg.bearing,
            speed = cfg.speed.coerceAtLeast(0.0f)
        ).asAndroidLocation()
    }

    private inline fun withCleanHookEnvironment(
        block: (
            backend: E2eHookBackend,
            configStore: HookConfigStore,
            locationHook: LocationHook,
            networkHook: NetworkAntiDetectHook,
            thirdPartyHook: ThirdPartyLbsSdkHook,
            classLoaderHelper: ClassLoaderHookHelper
        ) -> Unit
    ) {
        val backend = E2eHookBackend()
        val clockMs = AtomicLong(1_000L)
        val configStore = HookConfigStore(
            prefsReaderFactory = { null },
            providerFetcher = object : ProviderConfigFetcher {
                override fun fetchGlobalConfig(context: Context): Bundle? = null
            },
            elapsedRealtimeProvider = { clockMs.get() },
            sdkIntProvider = { 35 }
        )
        val locationHook = LocationHook(
            backend = backend,
            configProvider = configStore,
            locationFactory = ::fakeLocationFactory
        )
        val networkHook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = configStore,
            sdkIntProvider = { 35 }
        )
        val thirdPartyHook = ThirdPartyLbsSdkHook(
            backend = backend,
            configProvider = configStore
        )
        val classLoaderHelper = ClassLoaderHookHelper(backend)
        try {
            block(backend, configStore, locationHook, networkHook, thirdPartyHook, classLoaderHelper)
        } finally {
            locationHook.clearForTest()
            networkHook.clearForTest()
            thirdPartyHook.clearForTest()
            classLoaderHelper.resetForTest()
            configStore.resetForTest()
            LocationHook.shared.clearForTest()
            NetworkAntiDetectHook.shared.clearForTest()
            ThirdPartyLbsSdkHook.shared.clearForTest()
            MockLocationCleaner.clearForTest()
            ClassLoaderHookHelper.shared.resetForTest()
            HookConfigStore.shared.resetForTest()
            LocationHook.currentAppContext = null
            LocationHook.currentTargetPackage = ""
            RootProviderManager.setInstanceForTest(null)
            backend.clearHooks()
        }
    }

    // ==================== 辅助替身类 ====================

    private class FakeExitProcess(
        private val exitCodeValue: Int,
        stdoutText: String = "",
        stderrText: String = ""
    ) : Process() {
        private val outStream = ByteArrayInputStream(stdoutText.toByteArray())
        private val errStream = ByteArrayInputStream(stderrText.toByteArray())
        private val inStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = inStream
        override fun getInputStream(): InputStream = outStream
        override fun getErrorStream(): InputStream = errStream
        override fun waitFor(): Int = exitCodeValue
        override fun exitValue(): Int = exitCodeValue
        override fun destroy() {}
    }

    open class ConcreteNativeListener : LocationListener {
        val locations = CopyOnWriteArrayList<Location>()
        override fun onLocationChanged(location: Location) {
            locations.add(location)
        }
    }

    class VpnTransportInfo(val ifaceName: String = "tun0")

    class NonMatchingWifiStub(@JvmField var mBSSID: Int = 123)

    // ==================== F6: Root 模式边界与异常 ====================

    @Test
    fun `T2-F06-01 ShellUtil executeCommand kills timed out process and propagates non-zero exit codes`() {
        runBlocking {
            val start = System.currentTimeMillis()
            val timeoutRes = ShellUtil.executeCommand(
                cmd = listOf("sh", "-c", "sleep 5 & sleep 5"),
                timeoutMs = 150L
            )
            val elapsed = System.currentTimeMillis() - start
            assertFalse(timeoutRes.isSuccess)
            assertTrue(timeoutRes.timedOut)
            assertEquals(-1, timeoutRes.exitCode)
            assertTrue("Should complete promptly after timeout, elapsed=$elapsed", elapsed < 3_000L)

            val killedRes = ShellUtil.executeCommand(
                cmd = listOf("su", "-c", "id"),
                timeoutMs = 1_000L,
                processFactory = { FakeExitProcess(137, "", "Permission denied") }
            )
            assertFalse(killedRes.isSuccess)
            assertFalse(killedRes.timedOut)
            assertEquals(137, killedRes.exitCode)
            assertEquals("Permission denied", killedRes.stderr)

            val emptyRes = ShellUtil.execRoot("   ", timeoutMs = 200L)
            assertFalse(emptyRes.isSuccess)
        }
    }

    @Test
    fun `T2-F06-02 ShellUtil and RootProviderManager reject shell injection package names without executing commands`() {
        try {
            val fakeShell = FakeShellExecutor()
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = false)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = fakeShell.toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                for (badPkg in TestConfigPayloads.INJECTION_PACKAGE_NAMES) {
                    assertFalse("Should reject invalid pkg: $badPkg", ShellUtil.isValidPackageName(badPkg))
                    val res = manager.ensureMockPermission(badPkg ?: "")
                    assertTrue("Expected failure for $badPkg", res.isFailure)
                    assertTrue(
                        "Expected IllegalArgumentException for $badPkg, got ${res.exceptionOrNull()}",
                        res.exceptionOrNull() is IllegalArgumentException
                    )
                }
            }
            assertTrue(
                "No shell commands should be executed for injection payloads",
                fakeShell.executedCommands.isEmpty()
            )
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T2-F06-03 RootProviderManager ensureMockPermission returns failure on denied su or IOException`() {
        try {
            val deniedShell = FakeShellExecutor().apply {
                stubCommand(Regex(".*"), FakeShellResult(1, "", "su: permission denied"))
            }
            val backend = FakeTestProviderBackend(initialHasMockAppOp = false)
            val deniedMgr = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = deniedShell.toShellExecutor(),
                backendFactory = { backend },
                sdkIntProvider = { 35 }
            )

            val ioFailingExecutor = object : ShellExecutor {
                override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult =
                    ShellUtil.executeCommand(listOf("su", "-c", command), timeoutMs) {
                        throw IOException("Cannot run program su")
                    }

                override suspend fun execShell(command: String, timeoutMs: Long): ShellResult =
                    ShellUtil.executeCommand(listOf("sh", "-c", command), timeoutMs) {
                        throw IOException("Cannot run program sh")
                    }

                override suspend fun hasRootAccess(timeoutMs: Long): Boolean = false
                override suspend fun detectRootProvider(timeoutMs: Long): String = "None"
            }
            val ioFailMgr = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = ioFailingExecutor,
                backendFactory = { backend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                val resDenied = deniedMgr.ensureMockPermission("com.mockloc")
                assertTrue(resDenied.isFailure)
                assertTrue(resDenied.exceptionOrNull() is SecurityException)

                val resIo = ioFailMgr.ensureMockPermission("com.mockloc")
                assertTrue(resIo.isFailure)
                assertTrue(resIo.exceptionOrNull() is SecurityException)
                assertTrue(resIo.exceptionOrNull()!!.message!!.contains("Cannot run program su"))

                val env = EnvironmentDetector.detectEnvironment(
                    context = buildTestAppContext("com.mockloc"),
                    shellExecutor = ioFailingExecutor,
                    sdkInt = 35
                )
                assertFalse(env.isRootAvailable)
                assertEquals("None", env.rootProviderName)
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T2-F06-04 RootProviderManager startOrUpdate rolls back providers and surfaces SecurityException`() {
        try {
            val deniedShell = FakeShellExecutor().apply {
                stubCommand(Regex(".*"), FakeShellResult(1, "", "su denied"))
            }
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = false).apply {
                throwOnAdd["network"] = SecurityException("not allowed to perform MOCK_LOCATION")
            }
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = deniedShell.toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                val res = manager.startOrUpdate(39.9087, 116.3975, 44.0, 3.0f, 0f, 0f)
                assertTrue(res.isFailure)
                assertTrue(res.exceptionOrNull() is SecurityException)
                assertTrue(
                    "gps should be rolled back when network throws SecurityException",
                    providerBackend.registeredProviders.isEmpty()
                )
                assertTrue(providerBackend.operations.contains("removeTestProvider:gps"))
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T2-F06-05 RootProviderManager handles idempotent stop partial fused failure and fatal gps failure`() {
        try {
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = true)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = FakeShellExecutor().toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                // 未启动直接 stop()
                assertTrue(manager.stop().isSuccess)

                // fused 失败但 gps 成功 -> 局部降级仍返回成功
                providerBackend.throwOnAdd["fused"] = IllegalArgumentException("Unknown provider: fused")
                val partialRes = manager.startOrUpdate(39.9087, 116.3975, 44.0, 3.0f, 0f, 0f)
                assertTrue(partialRes.isSuccess)
                assertEquals(setOf("gps", "network"), providerBackend.registeredProviders.keys)
                assertTrue(manager.stop().isSuccess)

                // gps 自身失败 -> 整体失败并回滚
                providerBackend.reset()
                providerBackend.throwOnAdd["gps"] = IllegalArgumentException("gps unavailable")
                val gpsFailRes = manager.startOrUpdate(39.9087, 116.3975, 44.0, 3.0f, 0f, 0f)
                assertTrue(gpsFailRes.isFailure)
                assertTrue(providerBackend.registeredProviders.isEmpty())
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    // ==================== F7: LSPosed 入口与动态 ClassLoader 边界与异常 ====================

    @Test
    fun `T2-F07-01 XposedInit safely handles null lpparam null or blank packageName and null classLoader`() {
        try {
            val xposedInit = XposedInit()
            val cl = newIsolatedClassLoader()

            xposedInit.handleLoadPackage(null)
            xposedInit.handleLoadPackage(XposedParamFactory.createLoadPackageParam(null, cl))
            xposedInit.handleLoadPackage(XposedParamFactory.createLoadPackageParam("", cl))
            xposedInit.handleLoadPackage(XposedParamFactory.createLoadPackageParam("com.mockloc", null))
            xposedInit.handleLoadPackage(XposedParamFactory.createLoadPackageParam("   ", null))

            assertFalse(HookConfigStore.shared.isActiveFor(""))
            val enabledCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig()
            assertFalse(enabledCfg.isEnabledForPackage(""))
            assertFalse(enabledCfg.isEnabledForPackage("   "))
        } finally {
            LocationHook.shared.clearForTest()
            NetworkAntiDetectHook.shared.clearForTest()
            ThirdPartyLbsSdkHook.shared.clearForTest()
            MockLocationCleaner.clearForTest()
            ClassLoaderHookHelper.shared.resetForTest()
            HookConfigStore.shared.resetForTest()
            LocationHook.currentAppContext = null
            LocationHook.currentTargetPackage = ""
        }
    }

    @Test
    fun `T2-F07-02 XposedInit isolates missing or throwing third-party SDK classes while keeping system hooks active`() {
        withCleanHookEnvironment { backend, configStore, locationHook, networkHook, thirdPartyHook, classLoaderHelper ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                    longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                    targetPackages = setOf("com.missing.sdks"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            backend.blockedClassNames.addAll(
                listOf(
                    "com.tencent.map.geolocation.TencentLocationManager",
                    "com.amap.api.location.AMapLocationClient",
                    "com.baidu.location.LocationClient",
                    "com.google.android.gms.location.LocationServices",
                    "com.google.android.gms.location.FusedLocationProviderClient"
                )
            )
            val emptyClassLoader = object : ClassLoader(null) {
                override fun loadClass(name: String?, resolve: Boolean): Class<*> {
                    throw ClassNotFoundException(name)
                }
            }

            XposedInit().handlePackageInternal(
                packageName = "com.missing.sdks",
                classLoader = emptyClassLoader,
                backend = backend,
                configStore = configStore,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = thirdPartyHook,
                classLoaderHelper = classLoaderHelper,
                xposedVersionProvider = { 93 }
            )

            // 尽管空 ClassLoader 缺失所有三方 SDK，原生 LocationHook 仍然正常生效
            val res = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                args = arrayOf("gps")
            ) { E2eFakeLocation().asAndroidLocation() }
            val loc = res.result as Location
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, loc.latitude, 1e-6)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.longitude, loc.longitude, 1e-6)
        }
    }

    @Test
    fun `T2-F07-03 ClassLoaderHookHelper is thread-safe and deduplicates under concurrent ClassLoader dispatches`() {
        withCleanHookEnvironment { _, _, _, _, _, helper ->
            val sharedLoader = newIsolatedClassLoader()
            val delivered = CopyOnWriteArrayList<ClassLoader>()
            val onReady: (ClassLoader) -> Unit = { cl -> delivered.add(cl) }

            val threadCount = 10
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadCount)
            val errors = CopyOnWriteArrayList<Throwable>()
            val pool = Executors.newFixedThreadPool(threadCount)

            try {
                for (i in 0 until threadCount) {
                    val uniqueLoader = newIsolatedClassLoader()
                    pool.submit {
                        try {
                            startLatch.await(2, TimeUnit.SECONDS)
                            helper.dispatchClassLoader(sharedLoader, onReady)
                            helper.dispatchClassLoader(uniqueLoader, onReady)
                            helper.dispatchClassLoader(sharedLoader, onReady)
                        } catch (t: Throwable) {
                            errors.add(t)
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }
                startLatch.countDown()
                assertTrue(doneLatch.await(5, TimeUnit.SECONDS))
                assertTrue("Concurrent dispatch threw: $errors", errors.isEmpty())
                assertEquals(1, delivered.count { it === sharedLoader })
                assertEquals(1 + threadCount, delivered.size)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `T2-F07-04 ClassLoaderHookHelper falls back to Application onCreate when attachBaseContext target is non-Application`() {
        withCleanHookEnvironment { backend, _, _, _, _, helper ->
            backend.registerClass("android.content.ContextWrapper", ContextWrapper::class.java)
            backend.registerClass("android.app.Application", Application::class.java)

            val contextCount = AtomicInteger(0)
            val capturedCtx = AtomicReference<Context?>()
            helper.install(
                initialClassLoader = newIsolatedClassLoader(),
                onContextReady = { ctx ->
                    contextCount.incrementAndGet()
                    capturedCtx.set(ctx)
                },
                onClassLoaderReady = {}
            )

            // attachBaseContext target 为非 Application（普通 ContextWrapper）不应触发交付
            val nonAppWrapper = Mockito.mock(ContextWrapper::class.java)
            backend.invokeMethod(
                ContextWrapper::class.java,
                "attachBaseContext",
                thisObject = nonAppWrapper,
                args = arrayOf(buildTestAppContext())
            )
            assertEquals(0, contextCount.get())

            // Application.onCreate 兜底触发且只交付一次
            val appMock = Mockito.mock(Application::class.java)
            val appCtx = buildTestAppContext("com.packed.app")
            Mockito.`when`(appMock.applicationContext).thenReturn(appCtx)
            backend.invokeMethod(Application::class.java, "onCreate", thisObject = appMock)
            backend.invokeMethod(Application::class.java, "onCreate", thisObject = appMock)

            assertEquals(1, contextCount.get())
            assertSame(appCtx, capturedCtx.get())
        }
    }

    @Test
    fun `T2-F07-05 HookConfigStore recovers from SecurityException on registerReceiver and guards against duplicate registration`() {
        val store = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1_000L },
            sdkIntProvider = { 35 }
        )
        try {
            val failingCtx = Mockito.mock(Context::class.java)
            Mockito.`when`(failingCtx.applicationContext).thenReturn(failingCtx)
            Mockito.`when`(
                failingCtx.registerReceiver(
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.anyInt()
                )
            ).thenThrow(SecurityException("Broadcast registration denied"))
            Mockito.`when`(
                failingCtx.registerReceiver(
                    Mockito.any(),
                    Mockito.any()
                )
            ).thenThrow(SecurityException("Broadcast registration denied"))

            store.registerConfigReceiverOnce(failingCtx) {}

            // 失败后 receiverRegistered 应复位，允许后续正常 Context 重试注册且仅成功一次
            val registerCount = AtomicInteger(0)
            val okCtx = Mockito.mock(Context::class.java)
            Mockito.`when`(okCtx.applicationContext).thenReturn(okCtx)
            Mockito.`when`(
                okCtx.registerReceiver(
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.anyInt()
                )
            ).thenAnswer {
                registerCount.incrementAndGet()
                null
            }

            store.registerConfigReceiverOnce(okCtx) {}
            store.registerConfigReceiverOnce(okCtx) {}
            assertEquals(1, registerCount.get())
        } finally {
            store.resetForTest()
        }
    }

    // ==================== F8: 系统原生定位 Hook 边界与异常 ====================

    @Test
    fun `T2-F08-01 LocationHook safely handles null provider null listener and empty location list`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                    longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            val lastNullProv = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                args = arrayOf(null)
            ).result as Location
            assertEquals(LocationManager.GPS_PROVIDER, lastNullProv.provider)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, lastNullProv.latitude, 1e-6)

            val captured = AtomicReference<Location?>()
            backend.invokeMethod(
                LocationManager::class.java,
                "getCurrentLocation",
                args = arrayOf(null, null, Executor { it.run() }, Consumer<Location> { captured.set(it) })
            )
            assertEquals(LocationManager.GPS_PROVIDER, captured.get()!!.provider)

            backend.invokeMethod(
                LocationManager::class.java,
                "removeUpdates",
                args = arrayOf<Any?>(null)
            )

            val batchRecords = CopyOnWriteArrayList<List<Location>>()
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {}
                override fun onLocationChanged(locations: MutableList<Location>) {
                    batchRecords.add(locations.toList())
                }
            }
            val reg = backend.invokeMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                args = arrayOf("gps", 1000L, 0f, listener)
            )
            val proxy = reg.args[3] as LocationListener
            proxy.onLocationChanged(mutableListOf())
            assertEquals(1, batchRecords.size)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, batchRecords[0][0].latitude, 1e-6)
        }
    }

    @Test
    fun `T2-F08-02 LocationHook transparently passes through real data when package is not whitelisted`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    targetPackages = setOf("com.other.whitelisted"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.unlisted.app")

            val realLoc = E2eFakeLocation(
                provider = "gps",
                latitude = 22.5431,
                longitude = 114.0579,
                mockFlag = false,
                mIsFromMockProvider = false
            ).asAndroidLocation()

            val lastRes = backend.invokeMethod(
                LocationManager::class.java,
                "getLastKnownLocation",
                args = arrayOf("gps"),
                originalImpl = { realLoc }
            )
            assertSame(realLoc, lastRes.result)
            assertEquals(22.5431, (lastRes.result as Location).latitude, 1e-6)

            val listener = ConcreteNativeListener()
            val regRes = backend.invokeMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                args = arrayOf("gps", 1000L, 0f, listener)
            )
            val proxy = regRes.args[3] as LocationListener
            proxy.onLocationChanged(realLoc)
            assertEquals(1, listener.locations.size)
            assertEquals(22.5431, listener.locations[0].latitude, 1e-6)

            val curRes = backend.invokeMethod(
                LocationManager::class.java,
                "getCurrentLocation",
                args = arrayOf("gps", null, Executor { it.run() }, Consumer<Location> {})
            )
            assertFalse(curRes.earlyReturned)

            val fusedEnabled = backend.invokeMethod(
                LocationManager::class.java,
                "isProviderEnabled",
                args = arrayOf("fused"),
                originalImpl = { false }
            ).result as Boolean
            assertFalse(fusedEnabled)
        }
    }

    @Test
    fun `T2-F08-03 LocationHook stops pulse and restores passthrough on disable then resumes on re-enable`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            val enabledCfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(enabledCfg)
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            val listener = ConcreteNativeListener()
            val regRes = backend.invokeMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                args = arrayOf("gps", 500L, 0f, listener)
            )
            val proxy = regRes.args[3] as LocationListener
            assertTrue(locationHook.isPulseSchedulerActive())
            val countAfterStart = listener.locations.size

            // 禁用后应停止脉冲并透传真实位置
            val disabledCfg = enabledCfg.copy(enabled = false, updatedAtMs = 2_000L, version = 2L)
            configStore.applyIfNewer(disabledCfg)
            locationHook.onConfigChanged(disabledCfg, "com.target.app")
            assertFalse(locationHook.isPulseSchedulerActive())

            val realLoc = E2eFakeLocation(latitude = 22.5, longitude = 114.0, mockFlag = false).asAndroidLocation()
            proxy.onLocationChanged(realLoc)
            assertEquals(22.5, listener.locations.last().latitude, 1e-6)

            // 重新开启后未注销的旧监听器立即收到新脉冲并重启调度器
            val reEnabledCfg = enabledCfg.copy(
                enabled = true,
                latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
                updatedAtMs = 3_000L,
                version = 3L
            )
            configStore.applyIfNewer(reEnabledCfg)
            locationHook.onConfigChanged(reEnabledCfg, "com.target.app")
            assertTrue(locationHook.isPulseSchedulerActive())
            assertTrue(listener.locations.size > countAfterStart + 1)
            assertEquals(TestCoordinates.SHANGHAI_LUJIAZUI.latitude, listener.locations.last().latitude, 1e-6)
        }
    }

    @Test
    fun `T2-F08-04 LocationHook handles immutable or canceled PendingIntent safely and cleans subscription`() {
        val mockCtx = buildTestAppContext("com.target.app")
        val backend = E2eHookBackend()
        val configStore = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1_000L },
            sdkIntProvider = { 35 }
        )
        val locationHook = LocationHook(
            backend = backend,
            configProvider = configStore,
            contextProvider = { mockCtx },
            locationFactory = ::fakeLocationFactory
        )
        try {
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            val canceledPi = Mockito.mock(PendingIntent::class.java)
            Mockito.doThrow(PendingIntent.CanceledException("canceled"))
                .`when`(canceledPi)
                .send(Mockito.any(), Mockito.anyInt(), Mockito.any())

            backend.invokeMethod(
                LocationManager::class.java,
                "requestLocationUpdates",
                args = arrayOf("gps", 500L, 0f, canceledPi)
            )
            // CanceledException 后订阅应自动被清理，后续脉冲不再向其调用 send
            val dispatched = locationHook.dispatchPulseTick("com.target.app", forceImmediate = true)
            assertEquals(0, dispatched)
            Mockito.verify(canceledPi, Mockito.times(1))
                .send(Mockito.eq(mockCtx), Mockito.eq(0), Mockito.any(Intent::class.java))
        } finally {
            locationHook.clearForTest()
            configStore.resetForTest()
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T2-F08-05 LocationHook WeakReference proxy cache allows GC of unregistered LocationListener`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            fun registerEphemeralListener(): WeakReference<LocationListener> {
                val ephemeral = object : LocationListener {
                    override fun onLocationChanged(location: Location) {}
                }
                backend.invokeMethod(
                    LocationManager::class.java,
                    "requestSingleUpdate",
                    args = arrayOf("gps", ephemeral, null)
                )
                return WeakReference(ephemeral)
            }

            val ref = registerEphemeralListener()
            for (attempt in 0 until 25) {
                if (ref.get() == null) break
                val churn = ByteArray(1024 * 256)
                if (churn.isNotEmpty()) {
                    System.gc()
                }
                Thread.sleep(20)
            }

            assertNull("Ephemeral LocationListener should be garbage collected", ref.get())
            assertEquals(0, locationHook.trackedProxyCountForTest())
        }
    }

    // ==================== F9: 模拟位置标记多层清理边界与异常 ====================

    @Test
    fun `T2-F09-01 MockLocationCleaner handles null Location and MockConfig sanitizes invalid accuracy`() {
        assertNull(MockLocationCleaner.sanitize(null))
        assertNull(MockLocationCleaner.cleanLocation(null))

        val negativeAccCfg = MockConfig(accuracy = -10f).sanitized()
        val zeroAccCfg = MockConfig(accuracy = 0f).sanitized()
        val nanAccCfg = MockConfig(accuracy = Float.NaN).sanitized()

        assertTrue(negativeAccCfg.accuracy > 0f)
        assertEquals(MockConfig.DEFAULT_ACCURACY, negativeAccCfg.accuracy, 1e-6f)
        assertEquals(MockConfig.DEFAULT_ACCURACY, zeroAccCfg.accuracy, 1e-6f)
        assertEquals(MockConfig.DEFAULT_ACCURACY, nanAccCfg.accuracy, 1e-6f)
    }

    @Test
    fun `T2-F09-02 MockLocationCleaner clears bit 4 of inherited Int mFieldsMask and Byte mFieldsMask`() {
        val childStub = ChildHiddenFieldsLocationStub()
        MockLocationCleaner.cleanHiddenFields(childStub)
        assertEquals(0, childStub.mFieldsMask and 16)
        assertEquals(15, childStub.mFieldsMask and 15)
        assertFalse(childStub.mIsFromMockProvider)

        val byteStub = ByteFieldsMaskLocationStub(
            mFieldsMask = 0b11111.toByte(),
            mIsFromMockProvider = true
        )
        MockLocationCleaner.cleanHiddenFields(byteStub)
        // 严格契约断言：ROM 若将 mFieldsMask 定义为 Byte，bit 4 (16) 同样必须被清零且保留 bits 0..3
        assertEquals(0, byteStub.mFieldsMask.toInt() and 16)
        assertEquals(15, byteStub.mFieldsMask.toInt() and 15)
        assertFalse(byteStub.mIsFromMockProvider)
    }

    @Test
    fun `T2-F09-03 MockLocationCleaner tolerates null or read-only throwing extras Bundle`() {
        val nullExtrasLoc = E2eFakeLocation(extras = linkedMapOf()).asAndroidLocation()
        nullExtrasLoc.extras = null
        val sanitizedNullExtras = MockLocationCleaner.sanitize(nullExtrasLoc)
        assertNotNull(sanitizedNullExtras)
        assertFalse(sanitizedNullExtras!!.isMock)

        val throwingBundle = Mockito.mock(Bundle::class.java)
        Mockito.`when`(throwingBundle.keySet()).thenThrow(UnsupportedOperationException("Read-only bundle"))

        val fakeCarrier = E2eFakeLocation(mockFlag = true)
        val baseLoc = fakeCarrier.asAndroidLocation()
        val readOnlyLoc = Mockito.mock(Location::class.java)
        Mockito.`when`(readOnlyLoc.extras).thenReturn(throwingBundle)
        Mockito.doAnswer { inv ->
            baseLoc.setMock(inv.getArgument(0))
            null
        }.`when`(readOnlyLoc).setMock(Mockito.anyBoolean())

        val result = MockLocationCleaner.sanitize(readOnlyLoc)
        assertSame(readOnlyLoc, result)
        Mockito.verify(readOnlyLoc).setMock(false)
        assertFalse(fakeCarrier.isMock())
    }

    @Test
    fun `T2-F09-04 MockLocationCleaner falls back cleanly when Hidden API reflection is restricted`() {
        val backend = E2eHookBackend()
        try {
            val restrictedLoc = Mockito.mock(Location::class.java)
            Mockito.`when`(restrictedLoc.isMock).thenReturn(true)
            Mockito.`when`(restrictedLoc.isFromMockProvider).thenReturn(true)

            // sanitize 面对无 mFieldsMask 字段且 setMock 为空操作的对象不抛异常
            assertSame(restrictedLoc, MockLocationCleaner.sanitize(restrictedLoc))

            // 方法级 Hook 依然兜底强制返回 false
            MockLocationCleaner.installHooks(newIsolatedClassLoader(), backend, sdkInt = 35) { true }
            val isMockRes = backend.invokeMethod(Location::class.java, "isMock", restrictedLoc) { true }
            val isFromMockRes = backend.invokeMethod(Location::class.java, "isFromMockProvider", restrictedLoc) { true }
            assertEquals(false, isMockRes.result)
            assertEquals(false, isFromMockRes.result)
        } finally {
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T2-F09-05 MockLocationCleaner passes through non-mock_location Settings Secure keys and non-mock AppOps`() {
        val backend = E2eHookBackend()
        try {
            MockLocationCleaner.installHooks(newIsolatedClassLoader(), backend, sdkInt = 35) { true }
            val cr = Mockito.mock(ContentResolver::class.java)
            val imeRes = backend.invokeStatic(
                Settings.Secure::class.java,
                "getString",
                arrayOf(cr, "default_input_method"),
                originalImpl = { "com.example.ime/.Service" }
            )
            val androidIdRes = backend.invokeStatic(
                Settings.Secure::class.java,
                "getString",
                arrayOf(cr, "android_id"),
                originalImpl = { "deadbeef12345678" }
            )
            assertFalse(imeRes.earlyReturned)
            assertEquals("com.example.ime/.Service", imeRes.result)
            assertFalse(androidIdRes.earlyReturned)
            assertEquals("deadbeef12345678", androidIdRes.result)

            val appOps = Mockito.mock(AppOpsManager::class.java)
            val fineLocOp = backend.invokeMethod(
                AppOpsManager::class.java,
                "checkOpNoThrow",
                appOps,
                arrayOf("android:fine_location", 1000, "com.target.app"),
                originalImpl = { AppOpsManager.MODE_ALLOWED }
            )
            assertEquals(AppOpsManager.MODE_ALLOWED, fineLocOp.result)
        } finally {
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    // ==================== F10: 基站与 WiFi 反检测边界与异常 ====================

    @Test
    fun `T2-F10-01 NetworkAntiDetectHook respects independent antiDetectCellEnabled and antiDetectWifiEnabled flags`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
            val cellOnlyDisabled = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                antiDetectCellEnabled = false,
                antiDetectWifiEnabled = true,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cellOnlyDisabled)
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val tm = Mockito.mock(TelephonyManager::class.java)
            val realCells = listOf(Mockito.mock(CellInfo::class.java))
            val realCellLoc = Mockito.mock(CellLocation::class.java)
            val wm = Mockito.mock(WifiManager::class.java)
            val wifiInfo = Mockito.mock(WifiInfo::class.java)

            assertEquals(
                realCells,
                backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tm) { realCells }.result
            )
            assertSame(
                realCellLoc,
                backend.invokeMethod(TelephonyManager::class.java, "getCellLocation", tm) { realCellLoc }.result
            )
            @Suppress("UNCHECKED_CAST")
            val scans = backend.invokeMethod(WifiManager::class.java, "getScanResults", wm) { listOf(Any()) }.result as List<Any>
            assertTrue(scans.isEmpty())
            assertEquals(
                NetworkAntiDetectHook.SANITIZED_BSSID,
                backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiInfo) { "aa:bb:cc:dd:ee:ff" }.result
            )

            // 翻转开关：开基站反检测、关 WiFi 反检测
            val wifiDisabled = cellOnlyDisabled.copy(
                antiDetectCellEnabled = true,
                antiDetectWifiEnabled = false,
                updatedAtMs = 2_000L,
                version = 2L
            )
            configStore.applyIfNewer(wifiDisabled)
            @Suppress("UNCHECKED_CAST")
            val cellsAfterFlip = backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tm) { realCells }.result as List<CellInfo>
            assertTrue(cellsAfterFlip.isEmpty())
            assertEquals(
                "aa:bb:cc:dd:ee:ff",
                backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiInfo) { "aa:bb:cc:dd:ee:ff" }.result
            )
        }
    }

    @Test
    fun `T2-F10-02 NetworkAntiDetectHook safely overrides null unmodifiable and 10000-element cell and scan lists`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    antiDetectCellEnabled = true,
                    antiDetectWifiEnabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val tm = Mockito.mock(TelephonyManager::class.java)
            val wm = Mockito.mock(WifiManager::class.java)
            val largeList = Collections.unmodifiableList(MutableList<CellInfo?>(10_000) { null })

            for (raw in listOf(null, emptyList<Any>(), Collections.unmodifiableList(listOf(Any())), largeList)) {
                @Suppress("UNCHECKED_CAST")
                val cells = backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tm) { raw }.result as List<*>
                assertTrue(cells.isEmpty())

                @Suppress("UNCHECKED_CAST")
                val scans = backend.invokeMethod(WifiManager::class.java, "getScanResults", wm) { raw }.result as List<*>
                assertTrue(scans.isEmpty())
            }
        }
    }

    @Test
    fun `T2-F10-03 NetworkAntiDetectHook safely handles null WifiInfo non-matching object and null getConnectionInfo`() {
        withCleanHookEnvironment { backend, configStore, _, _, _, _ ->
            val sanitizerCalls = AtomicInteger(0)
            val hook = NetworkAntiDetectHook(
                backend = backend,
                configProvider = configStore,
                wifiInfoSanitizer = { orig, _ ->
                    sanitizerCalls.incrementAndGet()
                    orig
                },
                sdkIntProvider = { 35 }
            )
            try {
                val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    antiDetectWifiEnabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
                configStore.applyIfNewer(cfg)
                hook.install(newIsolatedClassLoader(), "com.target.app")

                assertNull(NetworkAntiDetectHook.defaultSanitizeWifiInfo(null, cfg))
                val weird = NonMatchingWifiStub(123)
                assertSame(weird, NetworkAntiDetectHook.defaultSanitizeWifiInfo(weird, cfg))
                assertEquals(123, weird.mBSSID)

                val wm = Mockito.mock(WifiManager::class.java)
                val connRes = backend.invokeMethod(WifiManager::class.java, "getConnectionInfo", wm) { null }
                assertNull(connRes.result)
                assertEquals(0, sanitizerCalls.get())
            } finally {
                hook.clearForTest()
            }
        }
    }

    @Test
    fun `T2-F10-04 NetworkAntiDetectHook ignores non-WifiInfo TransportInfo without ClassCastException`() {
        val backend = E2eHookBackend()
        val configStore = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1_000L },
            sdkIntProvider = { 35 }
        )
        val sanitizerCalls = AtomicInteger(0)
        val networkHook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = configStore,
            wifiInfoSanitizer = { orig, _ ->
                sanitizerCalls.incrementAndGet()
                orig
            },
            sdkIntProvider = { 35 }
        )
        try {
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    antiDetectWifiEnabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val netCaps = Mockito.mock(NetworkCapabilities::class.java)
            val nullRes = backend.invokeMethod(NetworkCapabilities::class.java, "getTransportInfo", netCaps) { null }
            assertNull(nullRes.result)

            val vpnInfo = VpnTransportInfo("tun0")
            val vpnRes = backend.invokeMethod(NetworkCapabilities::class.java, "getTransportInfo", netCaps) { vpnInfo }
            assertSame(vpnInfo, vpnRes.result)
            assertEquals(0, sanitizerCalls.get())
        } finally {
            networkHook.clearForTest()
            configStore.resetForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T2-F10-05 NetworkAntiDetectHook is thread-safe under 30 concurrent threads with zero MAC leaks`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                antiDetectCellEnabled = true,
                antiDetectWifiEnabled = true,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cfg)
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val tm = Mockito.mock(TelephonyManager::class.java)
            val wifiInfo = Mockito.mock(WifiInfo::class.java)
            val realCellList = listOf(Mockito.mock(CellInfo::class.java))

            val threads = 30
            val iterations = 100
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threads)
            val errors = CopyOnWriteArrayList<Throwable>()
            val pool = Executors.newFixedThreadPool(threads)

            try {
                for (t in 0 until threads) {
                    pool.submit {
                        try {
                            startLatch.await(3, TimeUnit.SECONDS)
                            for (i in 0 until iterations) {
                                val mac = String.format("aa:bb:cc:dd:%02x:%02x", t, i % 255)
                                val bssid = backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiInfo) { mac }.result as String
                                assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, bssid)

                                val hwMac = backend.invokeMethod(WifiInfo::class.java, "getMacAddress", wifiInfo) { mac }.result as String
                                assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, hwMac)

                                @Suppress("UNCHECKED_CAST")
                                val cells = backend.invokeMethod(TelephonyManager::class.java, "getAllCellInfo", tm) { realCellList }.result as List<*>
                                assertTrue(cells.isEmpty())
                            }
                        } catch (err: Throwable) {
                            errors.add(err)
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }
                startLatch.countDown()
                assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
                assertTrue("Concurrent network anti-detect errors: $errors", errors.isEmpty())
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // ==================== F11: 国产第三方 SDK 与 GMS 边界与异常 ====================

    @Test
    fun `T2-F11-01 ThirdPartyLbsSdkHook skips missing SDK classes cleanly while hooking present SDK`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    targetPackages = setOf("com.partial.sdk"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            // 1. 全缺失 SDK 的空 ClassLoader 不崩溃
            val emptyCl = object : ClassLoader(null) {
                override fun loadClass(name: String?, resolve: Boolean): Class<*> {
                    throw ClassNotFoundException(name)
                }
            }
            thirdPartyHook.installForClassLoader(emptyCl, "com.partial.sdk")

            // 2. 仅存在腾讯 SDK，显式阻塞高德/百度/GMS
            backend.blockedClassNames.addAll(
                listOf(
                    "com.amap.api.location.AMapLocationClient",
                    "com.amap.api.location.AMapLocationQualityReport",
                    "com.baidu.location.LocationClient",
                    "com.baidu.location.BDLocation",
                    "com.google.android.gms.location.LocationServices",
                    "com.google.android.gms.location.FusedLocationProviderClient"
                )
            )
            E2eLbsSdkDoubles.registerTencent(backend)
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.partial.sdk")

            val tMgr = E2eLbsSdkDoubles.TencentLocationManager()
            val tListener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
            val reqMethod = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.TencentLocationListener::class.java
            )
            backend.invokeMethod(reqMethod, tMgr, arrayOf(null, tListener)) { a ->
                tMgr.requestLocationUpdates(a[0], a[1] as E2eLbsSdkDoubles.TencentLocationListener?)
            }
            (tMgr.registeredListener as E2eLbsSdkDoubles.TencentLocationListener)
                .onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 5, "ERR")
            assertEquals(0, tListener.lastError)
        }
    }

    @Test
    fun `T2-F11-02 ThirdPartyLbsSdkHook handles null listener and null location payload without NPE`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerAll(backend)
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    targetPackages = setOf("com.null.safe"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.null.safe")

            val tMgr = E2eLbsSdkDoubles.TencentLocationManager()
            val tReq = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.TencentLocationListener::class.java
            )
            backend.invokeMethod(tReq, tMgr, arrayOf(null, null))
            backend.invokeMethod(
                E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                    "removeUpdates",
                    E2eLbsSdkDoubles.TencentLocationListener::class.java
                ),
                tMgr,
                arrayOf<Any?>(null)
            )
            val tListener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
            backend.invokeMethod(tReq, tMgr, arrayOf(null, tListener)) { a ->
                tMgr.requestLocationUpdates(a[0], a[1] as E2eLbsSdkDoubles.TencentLocationListener?)
            }
            (tMgr.registeredListener as E2eLbsSdkDoubles.TencentLocationListener)
                .onLocationChanged(null, 0, "OK")
            assertEquals(0, tListener.lastError)
            assertEquals("OK", tListener.lastReason)

            val aClient = E2eLbsSdkDoubles.AMapLocationClient()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", aClient, arrayOf<Any?>(null))
            val aListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", aClient, arrayOf(aListener))
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                aListener,
                arrayOf<Any?>(null)
            ) { a -> aListener.onLocationChanged(a[0] as? E2eLbsSdkDoubles.AMapLocation) }
            assertNull(aListener.lastLocation)

            val bClient = E2eLbsSdkDoubles.LocationClient()
            val bReg = E2eLbsSdkDoubles.LocationClient::class.java.getMethod(
                "registerLocationListener",
                E2eLbsSdkDoubles.BDAbstractLocationListener::class.java
            )
            backend.invokeMethod(bReg, bClient, arrayOf<Any?>(null))
            val bListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            backend.invokeMethod(bReg, bClient, arrayOf(bListener))
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                "onReceiveLocation",
                bListener,
                arrayOf<Any?>(null)
            ) { a -> bListener.onReceiveLocation(a[0] as? E2eLbsSdkDoubles.BDLocation) }
            assertNull(bListener.lastLocation)

            val gCb = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
            val gClient = E2eLbsSdkDoubles.FusedLocationProviderClient()
            val gReq = E2eLbsSdkDoubles.FusedLocationProviderClient::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.LocationCallback::class.java,
                Any::class.java
            )
            backend.invokeMethod(gReq, gClient, arrayOf(null, gCb, null))
            val emptyGmsRes = E2eLbsSdkDoubles.LocationResult(emptyList())
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java,
                "onLocationResult",
                gCb,
                arrayOf(emptyGmsRes)
            ) { a -> gCb.onLocationResult(a[0] as E2eLbsSdkDoubles.LocationResult) }
            assertNull(gCb.lastResult!!.getLastLocation())
        }
    }

    @Test
    fun `T2-F11-03 ThirdPartyLbsSdkHook passes through real coordinates and address when disabled or non-whitelisted`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerAll(backend)
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = false,
                    targetPackages = setOf("com.disabled.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.disabled.app")

            // 腾讯原样透传
            val tMgr = E2eLbsSdkDoubles.TencentLocationManager()
            val tListener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()
            val tReq = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.TencentLocationListener::class.java
            )
            backend.invokeMethod(tReq, tMgr, arrayOf(null, tListener)) { a ->
                tMgr.requestLocationUpdates(a[0], a[1] as E2eLbsSdkDoubles.TencentLocationListener?)
            }
            val rawTencent = E2eLbsSdkDoubles.DefaultTencentLocation(lat = 22.5431, lon = 114.0579, addr = "广东省深圳市真实物理地址")
            (tMgr.registeredListener as E2eLbsSdkDoubles.TencentLocationListener).onLocationChanged(rawTencent, 2, "REAL_ERR")
            assertEquals(22.5431, tListener.lastLocation!!.getLatitude(), 1e-6)
            assertEquals("广东省深圳市真实物理地址", tListener.lastLocation!!.getAddress())
            assertEquals(2, tListener.lastError)

            // 高德原样透传
            val aClient = E2eLbsSdkDoubles.AMapLocationClient()
            val aListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(E2eLbsSdkDoubles.AMapLocationClient::class.java, "setLocationListener", aClient, arrayOf(aListener))
            val rawAmap = E2eLbsSdkDoubles.AMapLocation().apply { setAddress("上海市某真实街道") }
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                aListener,
                arrayOf(rawAmap)
            ) { a -> aListener.onLocationChanged(a[0] as? E2eLbsSdkDoubles.AMapLocation) }
            assertEquals(22.5431, aListener.lastLocation!!.getLatitude(), 1e-6)
            assertEquals("上海市某真实街道", aListener.lastLocation!!.getAddress())
            assertEquals(7, aListener.lastLocation!!.getErrorCode())

            // 百度原样透传
            val bClient = E2eLbsSdkDoubles.LocationClient()
            val bListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            val bReg = E2eLbsSdkDoubles.LocationClient::class.java.getMethod(
                "registerLocationListener",
                E2eLbsSdkDoubles.BDAbstractLocationListener::class.java
            )
            backend.invokeMethod(bReg, bClient, arrayOf(bListener))
            val rawBd = E2eLbsSdkDoubles.BDLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                "onReceiveLocation",
                bListener,
                arrayOf(rawBd)
            ) { a -> bListener.onReceiveLocation(a[0] as? E2eLbsSdkDoubles.BDLocation) }
            assertEquals(22.5431, bListener.lastLocation!!.getLatitude(), 1e-6)
            assertEquals("真实百度物理地址", bListener.lastLocation!!.getAddrStr())
            assertEquals(161, bListener.lastLocation!!.getLocType())
        }
    }

    @Test
    fun `T2-F11-04 Baidu SDK outputs BD09 GCJ02 or WGS84 matching LocationClientOption coorType`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerBaidu(backend)
            val wgs = TestCoordinates.BEIJING_TIANANMEN
            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(wgs.latitude, wgs.longitude)
            val expectedBd = GeoCoordinateMathReference.wgs84ToBd09(wgs.latitude, wgs.longitude)

            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = wgs.latitude,
                    longitude = wgs.longitude,
                    altitude = wgs.altitude,
                    targetPackages = setOf("com.baidu.BaiduMap"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.baidu.BaiduMap")

            fun runBaiduScenario(requestedCoorType: String): E2eLbsSdkDoubles.BDLocation {
                val client = E2eLbsSdkDoubles.LocationClient()
                val option = E2eLbsSdkDoubles.LocationClientOption(requestedCoorType)
                backend.invokeMethod(
                    clazz = E2eLbsSdkDoubles.LocationClient::class.java,
                    methodName = "setLocOption",
                    thisObject = client,
                    args = arrayOf(option),
                    originalImpl = { a ->
                        client.setLocOption(a[0] as? E2eLbsSdkDoubles.LocationClientOption)
                    }
                )
                val listener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
                val regMethod = E2eLbsSdkDoubles.LocationClient::class.java.getMethod(
                    "registerLocationListener",
                    E2eLbsSdkDoubles.BDAbstractLocationListener::class.java
                )
                backend.invokeMethod(regMethod, client, arrayOf(listener)) { a ->
                    client.registerLocationListener(a[0] as? E2eLbsSdkDoubles.BDAbstractLocationListener)
                }
                val bdLoc = E2eLbsSdkDoubles.BDLocation()
                backend.invokeMethod(
                    E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                    "onReceiveLocation",
                    listener,
                    arrayOf(bdLoc)
                ) { a -> listener.onReceiveLocation(a[0] as? E2eLbsSdkDoubles.BDLocation) }
                return listener.lastLocation!!
            }

            // 1. bd09ll -> BD-09
            val bdOut = runBaiduScenario("bd09ll")
            assertEquals("bd09ll", bdOut.getCoorType())
            assertEquals(expectedBd.lat, bdOut.getLatitude(), 1e-6)
            assertEquals(expectedBd.lon, bdOut.getLongitude(), 1e-6)

            // 2. gcj02 -> GCJ-02（严格契约断言：禁止硬编码返回 BD-09）
            val gcjOut = runBaiduScenario("gcj02")
            assertEquals("gcj02", gcjOut.getCoorType())
            assertEquals(expectedGcj.lat, gcjOut.getLatitude(), 1e-6)
            assertEquals(expectedGcj.lon, gcjOut.getLongitude(), 1e-6)

            // 3. wgs84 -> WGS-84（严格契约断言：禁止硬编码返回 BD-09）
            val wgsOut = runBaiduScenario("wgs84")
            assertEquals("wgs84", wgsOut.getCoorType())
            assertEquals(wgs.latitude, wgsOut.getLatitude(), 1e-6)
            assertEquals(wgs.longitude, wgsOut.getLongitude(), 1e-6)
        }
    }

    @Test
    fun `T2-F11-05 ThirdPartyLbsSdkHook tolerates obfuscated minimal SDK stubs and NonInterfaceTencentLocation`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerAll(backend)
            val wgs = TestCoordinates.SHANGHAI_LUJIAZUI
            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(wgs.latitude, wgs.longitude)
            val expectedBd = GeoCoordinateMathReference.wgs84ToBd09(wgs.latitude, wgs.longitude)

            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = wgs.latitude,
                longitude = wgs.longitude,
                altitude = wgs.altitude,
                targetPackages = setOf("com.obfuscated.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cfg)
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.obfuscated.app")

            // 1. 精简混淆版高德
            val obfAmap = E2eLbsSdkDoubles.ObfuscatedAmapLocationStub()
            thirdPartyHook.sanitizeAmapLocation(obfAmap, cfg)
            assertEquals(expectedGcj.lat, obfAmap.getLatitude(), 1e-6)
            assertEquals(expectedGcj.lon, obfAmap.getLongitude(), 1e-6)
            assertEquals(0, obfAmap.getErrorCode())
            assertEquals("", obfAmap.getAddress())

            // 2. 精简混淆版百度
            val obfBaidu = E2eLbsSdkDoubles.ObfuscatedBaiduLocationStub()
            thirdPartyHook.sanitizeBaiduLocation(obfBaidu, cfg)
            assertEquals(expectedBd.lat, obfBaidu.getLatitude(), 1e-6)
            assertEquals(expectedBd.lon, obfBaidu.getLongitude(), 1e-6)
            assertEquals(61, obfBaidu.getLocType())
            assertEquals("", obfBaidu.getAddrStr())

            // 3. 混淆版 GMS LocationResult (仅 zzb 字段)
            val gmsFakeLoc = E2eFakeLocation(provider = "fused").asAndroidLocation()
            val obfGmsResult = E2eLbsSdkDoubles.ObfuscatedGmsLocationResultStub(listOf(gmsFakeLoc))
            thirdPartyHook.sanitizeGmsLocationResult(obfGmsResult, cfg)
            assertEquals(wgs.latitude, gmsFakeLoc.latitude, 1e-6)
            assertEquals(wgs.longitude, gmsFakeLoc.longitude, 1e-6)
            assertFalse(gmsFakeLoc.isMock)

            // 4. 非接口版腾讯定位（仅实现标记接口 Serializable，而无 TencentLocation 接口）+ 非接口监听器
            val nonIfaceBackend = E2eHookBackend().apply {
                E2eLbsSdkDoubles.registerTencent(this)
                blockedClassNames.add("com.tencent.map.geolocation.TencentLocation")
            }
            val nonIfaceHook = ThirdPartyLbsSdkHook(nonIfaceBackend, configStore)
            try {
                nonIfaceHook.installForClassLoader(newIsolatedClassLoader(), "com.obfuscated.app")
                val tMgr = E2eLbsSdkDoubles.TencentLocationManager()
                val nonIfaceListener = E2eLbsSdkDoubles.NonInterfaceTencentLocationListener()
                val reqAnyMethod = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                    "requestLocationUpdates",
                    Any::class.java,
                    Any::class.java
                )
                nonIfaceBackend.invokeMethod(reqAnyMethod, tMgr, arrayOf(null, nonIfaceListener))
                val nonIfaceLoc = E2eLbsSdkDoubles.NonInterfaceTencentLocation()
                nonIfaceBackend.invokeMethod(
                    E2eLbsSdkDoubles.NonInterfaceTencentLocationListener::class.java,
                    "onLocationChanged",
                    nonIfaceListener,
                    arrayOf(nonIfaceLoc, 9, "FAIL")
                ) { a ->
                    nonIfaceListener.onLocationChanged(a[0], a[1] as Int, a[2] as String?)
                }
                assertSame(nonIfaceLoc, nonIfaceListener.lastLocation)
                assertEquals(expectedGcj.lat, nonIfaceLoc.latitude, 1e-6)
                assertEquals(expectedGcj.lon, nonIfaceLoc.longitude, 1e-6)
                assertEquals(0, nonIfaceListener.lastError)
                assertEquals("OK", nonIfaceListener.lastReason)
                assertEquals("", nonIfaceLoc.address)
                assertEquals(0, nonIfaceLoc.mFieldsMask and 16)
                assertFalse(nonIfaceLoc.mIsFromMockProvider)
                assertFalse(nonIfaceLoc.isMockGps)
            } finally {
                nonIfaceHook.clearForTest()
                nonIfaceBackend.clearHooks()
            }
        }
    }
}
