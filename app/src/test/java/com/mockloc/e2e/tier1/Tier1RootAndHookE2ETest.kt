package com.mockloc.e2e.tier1

import android.app.AppOpsManager
import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.Criteria
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellLocation
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.mockloc.data.config.MockConfig
import com.mockloc.domain.model.CoordinateType
import com.mockloc.e2e.factory.E2eFakeLocation
import com.mockloc.e2e.factory.E2eHookBackend
import com.mockloc.e2e.factory.E2eLbsSdkDoubles
import com.mockloc.e2e.factory.FakeShellExecutor
import com.mockloc.e2e.factory.FakeShellResult
import com.mockloc.e2e.factory.FakeTestProviderBackend
import com.mockloc.e2e.factory.HiddenFieldsLocationStub
import com.mockloc.e2e.factory.InMemorySharedPreferences
import com.mockloc.e2e.factory.StatefulFakeBundle
import com.mockloc.e2e.factory.TestConfigPayloads
import com.mockloc.e2e.factory.TestCoordinates
import com.mockloc.e2e.factory.buildTestAppContext
import com.mockloc.e2e.factory.toMockConfig
import com.mockloc.e2e.factory.toShellExecutor
import com.mockloc.e2e.infra.GeoCoordinateMathReference
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
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import com.mockloc.root.ShellUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.math.abs

/**
 * E2E Tier 1 功能等价类测试套件（F6–F11，共 30 用例）。
 */
class Tier1RootAndHookE2ETest {

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

    private fun buildInMemoryPrefsReader(
        initialConfig: MockConfig,
        changedFlag: AtomicBoolean = AtomicBoolean(true)
    ): Pair<InMemorySharedPreferences, PrefsReader> {
        val prefs = InMemorySharedPreferences()
        ConfigSyncSerializer.writeToPrefs(prefs.edit(), initialConfig).commit()
        val reader = object : PrefsReader {
            override fun hasFileChanged(): Boolean = changedFlag.get()
            override fun reload() {
                changedFlag.set(false)
            }

            override fun readConfig(fallback: MockConfig): MockConfig =
                ConfigSyncSerializer.fromPrefs(prefs, fallback)
        }
        return prefs to reader
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

    open class StubBaseDexClassLoader(parent: ClassLoader? = null) : ClassLoader(parent)

    open class StubLoadedApk(private val loader: ClassLoader?) {
        open fun getClassLoader(): ClassLoader? = loader
    }

    open class FakeLocationManagerService {
        open fun getLastLocation(packageName: String?): Location? = null
    }

    open class ConcreteNativeLocationListener : LocationListener {
        val singleLocations = CopyOnWriteArrayList<Location>()
        val batchLocations = CopyOnWriteArrayList<List<Location>>()
        val enabledProviders = CopyOnWriteArrayList<String>()
        val flushCodes = CopyOnWriteArrayList<Int>()

        override fun onLocationChanged(location: Location) {
            singleLocations.add(location)
        }

        override fun onLocationChanged(locations: MutableList<Location>) {
            batchLocations.add(locations.toList())
        }

        override fun onProviderEnabled(provider: String) {
            enabledProviders.add(provider)
        }

        override fun onFlushComplete(requestCode: Int) {
            flushCodes.add(requestCode)
        }
    }

    open class StubGnssStatusCallback : GnssStatus.Callback() {
        var startedCount = 0
        var firstFixTtff = -1
        var satelliteCallbackCount = 0

        override fun onStarted() {
            startedCount++
        }

        override fun onFirstFix(ttffMillis: Int) {
            firstFixTtff = ttffMillis
        }

        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satelliteCallbackCount++
        }
    }

    open class StubNmeaListener : OnNmeaMessageListener {
        val messages = CopyOnWriteArrayList<String>()
        override fun onNmeaMessage(message: String, timestamp: Long) {
            messages.add(message)
        }
    }

    open class ConcreteCellInfoCallback : TelephonyManager.CellInfoCallback() {
        val received = CopyOnWriteArrayList<List<CellInfo>>()
        override fun onCellInfo(cellInfo: List<CellInfo>) {
            received.add(cellInfo.toList())
        }
    }

    open class BaseTelephonyCellCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        val received = CopyOnWriteArrayList<List<CellInfo>>()
        override fun onCellInfoChanged(cellInfo: List<CellInfo>) {
            received.add(cellInfo.toList())
        }
    }

    class DerivedTelephonyCellCallback : BaseTelephonyCellCallback()

    open class ConcreteWifiScanResultsCallback : WifiManager.ScanResultsCallback() {
        var availableCount = 0
        override fun onScanResultsAvailable() {
            availableCount++
        }
    }

    // ==================== F6: Root 模式异步探测与注入重构 ====================

    @Test
    fun `T1-F06-01 EnvironmentDetector detectEnvironment asynchronously probes Magisk KernelSU APatch and LSPosed`() {
        try {
            val ctx = buildTestAppContext(
                hasFineLocation = true,
                hasCoarseLocation = true,
                hasNotification = true,
                installedPackages = setOf("com.tencent.mm", "com.autonavi.minimap"),
                xposedScopeArray = arrayOf(
                    "com.mockloc",
                    "com.tencent.mm",
                    "com.autonavi.minimap",
                    "com.baidu.BaiduMap"
                )
            )
            val callerThreadId = Thread.currentThread().id
            val probeThreads = CopyOnWriteArrayList<String>()

            val magiskShell = FakeShellExecutor().apply {
                stubCommand(Regex("^id$"), FakeShellResult(0, "uid=0(root) gid=0(root)"))
                stubCommand(Regex("su -v"), FakeShellResult(0, "27.0:MAGISKSU"))
            }
            val magiskExecutor = object : com.mockloc.root.ShellExecutor {
                private val delegate = magiskShell.toShellExecutor()
                override suspend fun execRoot(command: String, timeoutMs: Long) =
                    delegate.execRoot(command, timeoutMs).also { probeThreads.add(Thread.currentThread().name) }

                override suspend fun execShell(command: String, timeoutMs: Long) =
                    delegate.execShell(command, timeoutMs).also { probeThreads.add(Thread.currentThread().name) }

                override suspend fun hasRootAccess(timeoutMs: Long) =
                    delegate.hasRootAccess(timeoutMs).also { probeThreads.add(Thread.currentThread().name) }

                override suspend fun detectRootProvider(timeoutMs: Long) =
                    delegate.detectRootProvider(timeoutMs).also { probeThreads.add(Thread.currentThread().name) }
            }
            val ksuShell = FakeShellExecutor().apply {
                stubCommand(Regex("^id$"), FakeShellResult(0, "uid=0(root)"))
                stubCommand(Regex("su -v"), FakeShellResult(0, "/data/adb/ksu ksud"))
            }
            val apatchShell = FakeShellExecutor().apply {
                stubCommand(Regex("^id$"), FakeShellResult(0, "uid=0(root)"))
                stubCommand(Regex("su -v"), FakeShellResult(0, "APatch apd"))
            }

            runBlocking {
                val magiskEnv = EnvironmentDetector.detectEnvironment(
                    context = ctx,
                    shellExecutor = magiskExecutor,
                    sdkInt = 35
                )
                assertEquals(callerThreadId, Thread.currentThread().id)
                assertTrue("Expected Dispatchers.IO execution, got $probeThreads", probeThreads.any { it.contains("worker") || it != Thread.currentThread().name })
                assertTrue(magiskEnv.isRootAvailable)
                assertEquals("Magisk", magiskEnv.rootProviderName)
                assertTrue(magiskEnv.hasLocationPermission)
                assertTrue(magiskEnv.hasNotificationPermission)
                assertEquals(2, magiskEnv.recommendedScopeInstalledCount)
                assertEquals(4, magiskEnv.recommendedScopeTotalCount)
                assertEquals(EnvironmentDetector.isModuleActive(), magiskEnv.isLSPosedActive)

                val ksuEnv = EnvironmentDetector.detectEnvironment(
                    context = ctx,
                    shellExecutor = ksuShell.toShellExecutor(),
                    sdkInt = 35
                )
                assertTrue(ksuEnv.isRootAvailable)
                assertEquals("KernelSU", ksuEnv.rootProviderName)

                val apatchEnv = EnvironmentDetector.detectEnvironment(
                    context = ctx,
                    shellExecutor = apatchShell.toShellExecutor(),
                    sdkInt = 35
                )
                assertTrue(apatchEnv.isRootAvailable)
                assertEquals("APatch", apatchEnv.rootProviderName)
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T1-F06-02 ShellUtil validates package names builds commands and drives compliant appops invocation`() {
        try {
            assertTrue(ShellUtil.isValidPackageName("com.mockloc"))
            assertTrue(ShellUtil.isValidPackageName("com.tencent.mm"))
            assertEquals(listOf("su", "-c", "id"), ShellUtil.buildProcessCommand(useRoot = true, "id"))
            assertEquals(listOf("sh", "-c", "id"), ShellUtil.buildProcessCommand(useRoot = false, "id"))

            val fakeShell = FakeShellExecutor()
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = false)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = fakeShell.toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 },
                userIdProvider = { 0 },
                uidProvider = { 10200 }
            )

            runBlocking {
                val r1 = manager.ensureMockPermission("com.mockloc")
                val r2 = manager.ensureMockPermission("com.tencent.mm")
                assertTrue(r1.isSuccess)
                assertTrue(r2.isSuccess)
            }

            assertEquals(2, fakeShell.executedCommands.size)
            assertEquals("appops set --user 0 com.mockloc android:mock_location allow", fakeShell.executedCommands[0])
            assertEquals("appops set --user 0 com.tencent.mm android:mock_location allow", fakeShell.executedCommands[1])
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T1-F06-03 RootProviderManager ensureMockPermission succeeds with non-blocking fallback chain`() {
        try {
            val fakeShell = FakeShellExecutor().apply {
                stubCommand(Regex("--user 0"), FakeShellResult(1, stderr = "Unknown option --user"))
                stubCommand(Regex("--user current"), FakeShellResult(1, stderr = "Unknown user"))
                stubCommand(Regex("^appops set com\\.mockloc"), FakeShellResult(0, stdout = ""))
            }
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = false)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = fakeShell.toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 },
                userIdProvider = { 0 },
                uidProvider = { 10200 }
            )

            runBlocking {
                val result = manager.ensureMockPermission("com.mockloc")
                assertTrue(result.isSuccess)
            }

            assertEquals(
                listOf(
                    "appops set --user 0 com.mockloc android:mock_location allow",
                    "appops set --user current com.mockloc android:mock_location allow",
                    "appops set com.mockloc android:mock_location allow"
                ),
                fakeShell.executedCommands.toList()
            )
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T1-F06-04 RootProviderManager startOrUpdate registers gps network fused for API 35 and updates coordinates`() {
        try {
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = true)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = FakeShellExecutor().toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                val first = manager.startOrUpdate(39.9087, 116.3975, 50.0, 3.0f, 90.0f, 1.5f)
                assertTrue(first.isSuccess)
                assertEquals(setOf("gps", "network", "fused"), providerBackend.registeredProviders.keys)
                assertEquals(35, providerBackend.registeredProviders["gps"])
                assertEquals(35, providerBackend.registeredProviders["network"])
                assertEquals(35, providerBackend.registeredProviders["fused"])
                assertTrue(providerBackend.enabledProviders.values.all { it })
                assertEquals(39.9087, providerBackend.lastLocations["gps"]!!.latitude, 1e-6)
                assertEquals(116.3975, providerBackend.lastLocations["fused"]!!.longitude, 1e-6)
                assertEquals(50.0, providerBackend.lastLocations["network"]!!.altitude, 1e-6)

                val addCountBeforeSecond = providerBackend.operations.count { it.startsWith("addTestProvider:") }
                val second = manager.startOrUpdate(31.2397, 121.4998, 15.0, 2.5f, 180.0f, 4.5f)
                assertTrue(second.isSuccess)
                val addCountAfterSecond = providerBackend.operations.count { it.startsWith("addTestProvider:") }
                assertEquals(addCountBeforeSecond, addCountAfterSecond)
                assertEquals(31.2397, providerBackend.lastLocations["gps"]!!.latitude, 1e-6)
                assertEquals(121.4998, providerBackend.lastLocations["fused"]!!.longitude, 1e-6)
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    @Test
    fun `T1-F06-05 RootProviderManager stop disables and removes all registered test providers`() {
        try {
            val providerBackend = FakeTestProviderBackend(initialHasMockAppOp = true)
            val manager = RootProviderManager(
                context = buildTestAppContext("com.mockloc"),
                shellExecutor = FakeShellExecutor().toShellExecutor(),
                backendFactory = { providerBackend },
                sdkIntProvider = { 35 }
            )

            runBlocking {
                assertTrue(manager.startOrUpdate(39.9087, 116.3975, 44.0, 3.0f, 0f, 0f).isSuccess)
                assertEquals(3, providerBackend.registeredProviders.size)

                val stopRes = manager.stop()
                assertTrue(stopRes.isSuccess)
                assertTrue(providerBackend.registeredProviders.isEmpty())
                assertTrue(providerBackend.enabledProviders.isEmpty())
                for (prov in listOf("gps", "network", "fused")) {
                    assertTrue(providerBackend.operations.contains("setTestProviderEnabled:$prov:false"))
                    assertTrue(providerBackend.operations.contains("removeTestProvider:$prov"))
                }
            }
        } finally {
            RootProviderManager.setInstanceForTest(null)
        }
    }

    // ==================== F7: LSPosed 入口与动态 ClassLoader ====================

    @Test
    fun `T1-F07-01 xposed_init points to XposedInit and self package hooks activate EnvironmentDetector`() {
        withCleanHookEnvironment { backend, configStore, locationHook, networkHook, thirdPartyHook, classLoaderHelper ->
            val xposedInitText = WorkspacePathResolver.xposedInitFile().readText().trim()
            assertEquals("com.mockloc.hook.XposedInit", xposedInitText)

            backend.registerClass(EnvironmentDetector::class.java)
            val cl = newIsolatedClassLoader()
            XposedInit().handlePackageInternal(
                packageName = "com.mockloc",
                classLoader = cl,
                backend = backend,
                configStore = configStore,
                locationHook = locationHook,
                networkHook = networkHook,
                thirdPartyHook = thirdPartyHook,
                classLoaderHelper = classLoaderHelper,
                xposedVersionProvider = { 93 }
            )

            val activeRes = backend.invokeStatic(
                clazz = EnvironmentDetector::class.java,
                methodName = "isModuleActive",
                originalImpl = { EnvironmentDetector.isModuleActive() }
            )
            val versionRes = backend.invokeStatic(
                clazz = EnvironmentDetector::class.java,
                methodName = "getXposedApiVersion",
                originalImpl = { EnvironmentDetector.getXposedApiVersion() }
            )

            assertTrue(activeRes.earlyReturned)
            assertEquals(true, activeRes.result)
            assertTrue(versionRes.earlyReturned)
            assertEquals(93, versionRes.result)
            assertFalse(backend.hasInterfaceHookAllMethods())
        }
    }

    @Test
    fun `T1-F07-02 HookConfigStore refreshes repeatedly via L1 throttle and L2 broadcast without single-sync lock`() {
        val clockMs = AtomicLong(1_000L)
        val changedFlag = AtomicBoolean(true)
        val cfgV1 = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
            latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
            longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
            targetPackages = setOf("com.target.a"),
            updatedAtMs = 100L,
            version = 1L
        )
        val (prefs, reader) = buildInMemoryPrefsReader(cfgV1, changedFlag)
        val store = HookConfigStore(
            prefsReaderFactory = { reader },
            providerFetcher = object : ProviderConfigFetcher {
                override fun fetchGlobalConfig(context: Context): Bundle? = null
            },
            elapsedRealtimeProvider = { clockMs.get() },
            sdkIntProvider = { 35 }
        )

        try {
            val loaded1 = store.getConfig()
            assertEquals(1L, loaded1.version)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, loaded1.latitude, 1e-6)

            // 模拟 L1 文件更新并超过 400ms 节流窗口
            val cfgV2 = cfgV1.copy(
                latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
                updatedAtMs = 200L,
                version = 2L
            )
            ConfigSyncSerializer.writeToPrefs(prefs.edit(), cfgV2).commit()
            changedFlag.set(true)
            clockMs.addAndGet(500L)

            val loaded2 = store.getConfig()
            assertEquals(2L, loaded2.version)
            assertEquals(TestCoordinates.SHANGHAI_LUJIAZUI.latitude, loaded2.latitude, 1e-6)

            // 再通过 L2 广播热推到 version=3
            val cfgV3 = cfgV2.copy(
                latitude = TestCoordinates.SHENZHEN_NANSHAN.latitude,
                longitude = TestCoordinates.SHENZHEN_NANSHAN.longitude,
                updatedAtMs = 300L,
                version = 3L
            )
            val bundle = StatefulFakeBundle().asAndroidBundle()
            ConfigSyncSerializer.toBundle(cfgV3, bundle)
            val intent = Mockito.mock(Intent::class.java)
            Mockito.`when`(intent.action).thenReturn(IpcProtocol.ACTION_CONFIG_CHANGED)
            Mockito.`when`(intent.extras).thenReturn(bundle)

            assertTrue(store.onBroadcastReceived(intent))
            val loaded3 = store.peekCurrentConfig()
            assertEquals(3L, loaded3.version)
            assertEquals(TestCoordinates.SHENZHEN_NANSHAN.latitude, loaded3.latitude, 1e-6)
        } finally {
            store.resetForTest()
        }
    }

    @Test
    fun `T1-F07-03 ClassLoaderHookHelper detects dynamic secondary ClassLoader and deduplicates instances`() {
        withCleanHookEnvironment { backend, _, _, _, _, helper ->
            backend.registerClass("dalvik.system.BaseDexClassLoader", StubBaseDexClassLoader::class.java)
            backend.registerClass("android.app.LoadedApk", StubLoadedApk::class.java)
            backend.registerClass("android.content.ContextWrapper", ContextWrapper::class.java)
            backend.registerClass("android.app.Application", Application::class.java)

            val initialCl = newIsolatedClassLoader()
            val dynamicCl1 = newIsolatedClassLoader()
            val dynamicCl2 = newIsolatedClassLoader()
            val deliveredLoaders = CopyOnWriteArrayList<ClassLoader>()

            helper.install(
                initialClassLoader = initialCl,
                onContextReady = {},
                onClassLoaderReady = { cl -> deliveredLoaders.add(cl) }
            )

            // 通过 BaseDexClassLoader 构造函数触发二次 ClassLoader 通知
            val stubDex = StubBaseDexClassLoader(initialCl)
            backend.invokeConstructor(StubBaseDexClassLoader::class.java, dynamicCl1)
            // 通过 LoadedApk.getClassLoader 再次返回同一 dynamicCl1（应被去重）与新 dynamicCl2
            val loadedApk = StubLoadedApk(dynamicCl1)
            backend.invokeMethod(
                clazz = StubLoadedApk::class.java,
                methodName = "getClassLoader",
                thisObject = loadedApk,
                originalImpl = { dynamicCl1 }
            )
            backend.invokeMethod(
                clazz = StubLoadedApk::class.java,
                methodName = "getClassLoader",
                thisObject = StubLoadedApk(dynamicCl2),
                originalImpl = { dynamicCl2 }
            )
            assertNotNull(stubDex)

            assertEquals(3, deliveredLoaders.size)
            assertSame(initialCl, deliveredLoaders[0])
            assertSame(dynamicCl1, deliveredLoaders[1])
            assertSame(dynamicCl2, deliveredLoaders[2])
        }
    }

    @Test
    fun `T1-F07-04 Whitelist package isolation intercepts target package and passes through non-target package`() {
        withCleanHookEnvironment { backend, configStore, _, _, _, _ ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                targetPackages = setOf("com.target.a"),
                updatedAtMs = 500L,
                version = 5L
            )
            configStore.applyIfNewer(cfg)

            val hookTarget = LocationHook(backend, configStore, locationFactory = ::fakeLocationFactory)
            val hookOther = LocationHook(backend, configStore, locationFactory = ::fakeLocationFactory)
            try {
                val clTarget = newIsolatedClassLoader()
                val clOther = newIsolatedClassLoader()
                val realSystemLoc = E2eFakeLocation(
                    provider = "gps",
                    latitude = 22.5431,
                    longitude = 114.0579
                ).asAndroidLocation()

                hookTarget.installSystemHooks(clTarget, "com.target.a")
                val targetRes = backend.invokeMethod(
                    clazz = LocationManager::class.java,
                    methodName = "getLastKnownLocation",
                    args = arrayOf("gps"),
                    originalImpl = { realSystemLoc }
                )
                val targetLoc = targetRes.result as Location
                assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, targetLoc.latitude, 1e-6)
                assertEquals(TestCoordinates.BEIJING_TIANANMEN.longitude, targetLoc.longitude, 1e-6)

                backend.clearHooks()
                hookOther.installSystemHooks(clOther, "com.other.b")
                val otherRes = backend.invokeMethod(
                    clazz = LocationManager::class.java,
                    methodName = "getLastKnownLocation",
                    args = arrayOf("gps"),
                    originalImpl = { realSystemLoc }
                )
                val otherLoc = otherRes.result as Location
                assertEquals(22.5431, otherLoc.latitude, 1e-6)
                assertEquals(114.0579, otherLoc.longitude, 1e-6)
            } finally {
                hookTarget.clearForTest()
                hookOther.clearForTest()
            }
        }
    }

    @Test
    fun `T1-F07-05 system_server safe hook avoids pulse threads and only cleans whitelisted package Location`() {
        withCleanHookEnvironment { backend, _, locationHook, networkHook, thirdPartyHook, classLoaderHelper ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                targetPackages = setOf("com.target.a"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            val (_, reader) = buildInMemoryPrefsReader(cfg)
            val l1Store = HookConfigStore(
                prefsReaderFactory = { reader },
                elapsedRealtimeProvider = { 2_000L },
                sdkIntProvider = { 35 }
            )
            try {
                backend.registerClass("com.android.server.location.LocationManagerService", FakeLocationManagerService::class.java)
                val cl = newIsolatedClassLoader()
                XposedInit().handlePackageInternal(
                    packageName = XposedInit.SYSTEM_SERVER_PACKAGE,
                    classLoader = cl,
                    backend = backend,
                    configStore = l1Store,
                    locationHook = locationHook,
                    networkHook = networkHook,
                    thirdPartyHook = thirdPartyHook,
                    classLoaderHelper = classLoaderHelper,
                    xposedVersionProvider = { 93 }
                )

                assertFalse(locationHook.isPulseSchedulerActive())
                assertEquals(0, backend.hookInstallCount(ContextWrapper::class.java))
                assertEquals(0, backend.hookInstallCount(Application::class.java))

                val targetRaw = E2eFakeLocation(provider = "gps", mockFlag = true).asAndroidLocation()
                val otherRaw = E2eFakeLocation(provider = "gps", mockFlag = true).asAndroidLocation()

                val targetRes = backend.invokeMethod(
                    clazz = FakeLocationManagerService::class.java,
                    methodName = "getLastLocation",
                    thisObject = FakeLocationManagerService(),
                    args = arrayOf("com.target.a"),
                    originalImpl = { targetRaw }
                )
                val otherRes = backend.invokeMethod(
                    clazz = FakeLocationManagerService::class.java,
                    methodName = "getLastLocation",
                    thisObject = FakeLocationManagerService(),
                    args = arrayOf("com.other.b"),
                    originalImpl = { otherRaw }
                )

                assertFalse((targetRes.result as Location).isFromMockProvider)
                assertFalse((targetRes.result as Location).isMock)
                assertTrue((otherRes.result as Location).isFromMockProvider)
                assertTrue((otherRes.result as Location).isMock)
            } finally {
                l1Store.resetForTest()
            }
        }
    }

    // ==================== F8: 系统原生定位 Hook 全覆盖 ====================

    @Test
    fun `T1-F08-01 LocationHook proxies LocationListener for single batch and passthrough callbacks`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cfg)
            val cl = newIsolatedClassLoader()
            locationHook.installSystemHooks(cl, "com.target.app")

            val originalListener = ConcreteNativeLocationListener()
            val directExecutor = Executor { it.run() }
            val regRes = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "requestLocationUpdates",
                args = arrayOf("gps", 1000L, 0f, originalListener)
            )
            val proxyListener = regRes.args[3] as LocationListener
            assertNotNull(proxyListener)

            val realLoc = E2eFakeLocation(provider = "gps", latitude = 22.5, longitude = 114.0).asAndroidLocation()
            proxyListener.onLocationChanged(realLoc)
            proxyListener.onLocationChanged(mutableListOf(realLoc))
            proxyListener.onProviderEnabled("gps")
            proxyListener.onFlushComplete(7)
            assertNotNull(directExecutor)

            assertTrue(originalListener.singleLocations.isNotEmpty())
            val firstSingle = originalListener.singleLocations.last()
            assertEquals(TestCoordinates.SHANGHAI_LUJIAZUI.latitude, firstSingle.latitude, 1e-6)
            assertEquals(TestCoordinates.SHANGHAI_LUJIAZUI.longitude, firstSingle.longitude, 1e-6)
            assertFalse(firstSingle.isFromMockProvider)

            assertEquals(1, originalListener.batchLocations.size)
            val batchLoc = originalListener.batchLocations[0][0]
            assertEquals(TestCoordinates.SHANGHAI_LUJIAZUI.latitude, batchLoc.latitude, 1e-6)
            assertFalse(batchLoc.isFromMockProvider)

            assertEquals(listOf("gps"), originalListener.enabledProviders.toList())
            assertEquals(listOf(7), originalListener.flushCodes.toList())
        }
    }

    @Test
    fun `T1-F08-02 LocationHook intercepts getCurrentLocation Consumer and getLastKnownLocation LastLocationRequest`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                altitude = 44.0,
                accuracy = 2.5f,
                speed = 4.5f,
                bearing = 180.0f,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cfg)
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            val captured = AtomicReference<Location?>()
            val consumer = Consumer<Location> { loc -> captured.set(loc) }
            val directExecutor = Executor { it.run() }

            val curRes = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "getCurrentLocation",
                args = arrayOf("fused", null, directExecutor, consumer)
            )
            assertTrue(curRes.earlyReturned)
            val currentLoc = captured.get()
            assertNotNull(currentLoc)
            assertEquals("fused", currentLoc!!.provider)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, currentLoc.latitude, 1e-6)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.longitude, currentLoc.longitude, 1e-6)
            assertEquals(44.0, currentLoc.altitude, 1e-6)
            assertEquals(2.5f, currentLoc.accuracy, 1e-6f)
            assertEquals(4.5f, currentLoc.speed, 1e-6f)
            assertEquals(180.0f, currentLoc.bearing, 1e-6f)

            val lastReq = Any()
            val lastRes = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "getLastKnownLocation",
                args = arrayOf("gps", lastReq)
            )
            val lastLoc = lastRes.result as Location
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, lastLoc.latitude, 1e-6)
            assertEquals(TestCoordinates.BEIJING_TIANANMEN.longitude, lastLoc.longitude, 1e-6)

            // 进一步验证 2 参 LastLocationRequest 重载在自定义 LocationManager 声明上同样被 hookAllMethods 拦截
            val twoArgBackend = E2eHookBackend()
            class StubLastLocationRequest
            class StubLocationManagerWithLastReq {
                @Suppress("UNUSED_PARAMETER")
                fun getLastKnownLocation(provider: String, req: StubLastLocationRequest): Location? = null
            }
            twoArgBackend.registerClass("android.location.LocationManager", StubLocationManagerWithLastReq::class.java)
            val twoArgHook = LocationHook(twoArgBackend, configStore, locationFactory = ::fakeLocationFactory)
            try {
                twoArgHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")
                val twoArgRes = twoArgBackend.invokeMethod(
                    clazz = StubLocationManagerWithLastReq::class.java,
                    methodName = "getLastKnownLocation",
                    thisObject = StubLocationManagerWithLastReq(),
                    args = arrayOf("gps", StubLastLocationRequest())
                )
                val twoArgLoc = twoArgRes.result as Location
                assertEquals(TestCoordinates.BEIJING_TIANANMEN.latitude, twoArgLoc.latitude, 1e-6)
                assertEquals(TestCoordinates.BEIJING_TIANANMEN.longitude, twoArgLoc.longitude, 1e-6)
            } finally {
                twoArgHook.clearForTest()
                twoArgBackend.clearHooks()
            }
        }
    }

    @Test
    fun `T1-F08-03 LocationHook intercepts PendingIntent requestLocationUpdates and fused provider queries`() {
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

            val pendingIntent = Mockito.mock(PendingIntent::class.java)
            backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "requestLocationUpdates",
                args = arrayOf("fused", 1000L, 0f, pendingIntent)
            )
            Mockito.verify(pendingIntent, Mockito.atLeastOnce())
                .send(Mockito.eq(mockCtx), Mockito.eq(0), Mockito.any(Intent::class.java))

            val enabledFused = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "isProviderEnabled",
                args = arrayOf("fused"),
                originalImpl = { false }
            ).result as Boolean
            assertTrue(enabledFused)

            val hasFused = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "hasProvider",
                args = arrayOf("fused"),
                originalImpl = { false }
            ).result as Boolean
            assertTrue(hasFused)

            @Suppress("UNCHECKED_CAST")
            val allProviders = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "getAllProviders",
                originalImpl = { listOf("passive") }
            ).result as List<String>
            assertTrue(allProviders.containsAll(listOf("gps", "network", "fused")))

            val bestProvider = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "getBestProvider",
                args = arrayOf(Mockito.mock(Criteria::class.java), true),
                originalImpl = { "network" }
            ).result as String
            assertEquals("gps", bestProvider)
        } finally {
            locationHook.clearForTest()
            configStore.resetForTest()
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T1-F08-04 LocationHook preserves proxy identity equals hashCode and restores proxy on removeUpdates`() {
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

            val original = ConcreteNativeLocationListener()
            val r1 = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "requestLocationUpdates",
                args = arrayOf("gps", 1000L, 0f, original)
            )
            val r2 = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "requestLocationUpdates",
                args = arrayOf("network", 1000L, 0f, original)
            )
            val proxy1 = r1.args[3] as LocationListener
            val proxy2 = r2.args[3] as LocationListener

            assertSame(proxy1, proxy2)
            assertEquals(proxy1, original)
            assertEquals(System.identityHashCode(original), proxy1.hashCode())
            assertEquals(1, locationHook.trackedProxyCountForTest())

            val remRes = backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "removeUpdates",
                args = arrayOf(original)
            )
            assertSame(proxy1, remRes.args[0])
            assertEquals(0, locationHook.trackedProxyCountForTest())
            assertFalse(locationHook.isPulseSchedulerActive())
        }
    }

    @Test
    fun `T1-F08-05 LocationHook delivers initial and periodic pulses and masks GNSS and NMEA`() {
        withCleanHookEnvironment { backend, configStore, locationHook, _, _, _ ->
            val cfg = TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                enabled = true,
                latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
                longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
                altitude = 44.0,
                antiDetectGnssEnabled = true,
                targetPackages = setOf("com.target.app"),
                updatedAtMs = 1_000L,
                version = 1L
            )
            configStore.applyIfNewer(cfg)
            locationHook.installSystemHooks(newIsolatedClassLoader(), "com.target.app")

            val listener = ConcreteNativeLocationListener()
            val executor = Executor { r -> r.run() }
            val method = LocationManager::class.java.getMethod(
                "requestLocationUpdates",
                String::class.java,
                Long::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Executor::class.java,
                LocationListener::class.java
            )
            backend.invokeMethod(
                member = method,
                args = arrayOf("gps", 300L, 0f, executor, listener)
            )
            assertEquals("Expected immediate initial pulse", 1, listener.singleLocations.size)
            assertTrue("Expected pulse scheduler active", locationHook.isPulseSchedulerActive())
            val tickDispatched = locationHook.dispatchPulseTick("com.target.app", forceImmediate = true)
            assertEquals(1, tickDispatched)
            assertEquals("Expected periodic pulse delivery", 2, listener.singleLocations.size)

            // GNSS 屏蔽测试
            val gnssCallback = StubGnssStatusCallback()
            backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "registerGnssStatusCallback",
                args = arrayOf(Executor { it.run() }, gnssCallback)
            )
            assertEquals(1, gnssCallback.startedCount)
            assertEquals(45, gnssCallback.firstFixTtff)

            val satRes = backend.invokeMethod(
                clazz = StubGnssStatusCallback::class.java,
                methodName = "onSatelliteStatusChanged",
                thisObject = gnssCallback,
                args = arrayOf(Mockito.mock(GnssStatus::class.java)),
                originalImpl = { gnssCallback.satelliteCallbackCount++ }
            )
            assertTrue(satRes.earlyReturned)
            assertEquals(0, gnssCallback.satelliteCallbackCount)

            // NMEA 替换与校验和测试
            val nmeaListener = StubNmeaListener()
            backend.invokeMethod(
                clazz = LocationManager::class.java,
                methodName = "addNmeaListener",
                args = arrayOf(Executor { it.run() }, nmeaListener)
            )
            backend.invokeMethod(
                clazz = StubNmeaListener::class.java,
                methodName = "onNmeaMessage",
                thisObject = nmeaListener,
                args = arrayOf("\$GPGGA,000000.00,2230.0000,N,11400.0000,E,1,08,1.0,10.0,M,0.0,M,,*00\r\n", 100L),
                originalImpl = { a ->
                    nmeaListener.onNmeaMessage(a[0] as String, a[1] as Long)
                }
            )
            assertEquals(1, nmeaListener.messages.size)
            val sentence = nmeaListener.messages[0]
            assertTrue(sentence.startsWith("\$GPGGA,"))
            assertTrue(sentence.endsWith("\r\n"))
            assertTrue(sentence.contains(",3954.5220,N,11623.8500,E,"))
            val body = sentence.substringAfter('$').substringBefore('*')
            val hex = sentence.substringAfter('*').trim()
            var expectedXor = 0
            for (ch in body) expectedXor = expectedXor xor ch.code
            assertEquals(String.format("%02X", expectedXor and 0xFF), hex)
        }
    }

    // ==================== F9: 模拟位置标记多层清理 ====================

    @Test
    fun `T1-F09-01 MockLocationCleaner clears bit 4 of mFieldsMask while preserving bits 0 to 3`() {
        val stub = HiddenFieldsLocationStub(mFieldsMask = 0b11111, mIsFromMockProvider = true)
        MockLocationCleaner.cleanHiddenFields(stub)
        assertEquals(0, stub.mFieldsMask and 16)
        assertEquals(15, stub.mFieldsMask and 15)
        assertFalse(stub.mIsFromMockProvider)

        val loc = E2eFakeLocation(
            provider = "gps",
            latitude = 31.2397,
            longitude = 121.4998,
            altitude = 15.0,
            accuracy = 2.5f,
            speed = 4.5f,
            bearing = 180f,
            time = 1_700_000_000_000L
        ).asAndroidLocation()
        MockLocationCleaner.sanitize(loc)
        assertEquals(31.2397, loc.latitude, 1e-6)
        assertEquals(121.4998, loc.longitude, 1e-6)
        assertTrue(loc.hasAltitude())
        assertTrue(loc.hasSpeed())
        assertTrue(loc.hasBearing())
        assertTrue(loc.hasAccuracy())
        assertFalse(loc.isFromMockProvider)
        assertFalse(loc.isMock)
    }

    @Test
    fun `T1-F09-02 MockLocationCleaner clears setMock false and legacy mIsFromMockProvider field`() {
        val fake = E2eFakeLocation(mockFlag = true, mIsFromMockProvider = true)
        val loc = fake.asAndroidLocation()
        assertTrue(loc.isMock)
        assertTrue(loc.isFromMockProvider)

        MockLocationCleaner.sanitize(loc)
        assertFalse(loc.isMock)
        assertFalse(loc.isFromMockProvider)
        assertFalse(fake.mIsFromMockProvider)

        val hiddenCarrier = HiddenFieldsLocationStub(mFieldsMask = 16, mIsFromMockProvider = true)
        MockLocationCleaner.cleanHiddenFields(hiddenCarrier)
        assertFalse(hiddenCarrier.mIsFromMockProvider)
    }

    @Test
    fun `T1-F09-03 MockLocationCleaner strips mock extra keys while preserving business keys`() {
        val fake = E2eFakeLocation(
            extras = linkedMapOf(
                "mockLocation" to true,
                "mock" to true,
                "isFromMockProvider" to true,
                "spoof" to 1,
                "fake" to "yes",
                "satellites" to 12,
                "provider_tag" to "gnss"
            )
        )
        val loc = fake.asAndroidLocation()
        MockLocationCleaner.sanitize(loc)

        val cleanedExtras = loc.extras!!
        assertFalse(cleanedExtras.containsKey("mockLocation"))
        assertFalse(cleanedExtras.containsKey("mock"))
        assertFalse(cleanedExtras.containsKey("isFromMockProvider"))
        assertFalse(cleanedExtras.containsKey("spoof"))
        assertFalse(cleanedExtras.containsKey("fake"))
        assertEquals(12, cleanedExtras.getInt("satellites"))
        assertEquals("gnss", cleanedExtras.getString("provider_tag"))
    }

    @Test
    fun `T1-F09-04 MockLocationCleaner intercepts Settings Secure mock_location for whitelisted apps only`() {
        val backend = E2eHookBackend()
        val activeFlag = AtomicBoolean(true)
        try {
            MockLocationCleaner.installHooks(newIsolatedClassLoader(), backend, sdkInt = 35) {
                activeFlag.get()
            }
            val cr = Mockito.mock(ContentResolver::class.java)

            val strActive = backend.invokeStatic(
                Settings.Secure::class.java,
                "getString",
                arrayOf(cr, "mock_location"),
                originalImpl = { "1" }
            )
            val intActive = backend.invokeStatic(
                Settings.Secure::class.java,
                "getInt",
                arrayOf(cr, "mock_location", 1),
                originalImpl = { 1 }
            )
            assertTrue(strActive.earlyReturned)
            assertEquals("0", strActive.result)
            assertTrue(intActive.earlyReturned)
            assertEquals(0, intActive.result)

            activeFlag.set(false)
            val strPassthrough = backend.invokeStatic(
                Settings.Secure::class.java,
                "getString",
                arrayOf(cr, "mock_location"),
                originalImpl = { "1" }
            )
            assertFalse(strPassthrough.earlyReturned)
            assertEquals("1", strPassthrough.result)
        } finally {
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T1-F09-05 MockLocationCleaner intercepts AppOpsManager mock_location checks for target package`() {
        val backend = E2eHookBackend()
        val activeFlag = AtomicBoolean(true)
        try {
            MockLocationCleaner.installHooks(newIsolatedClassLoader(), backend, sdkInt = 36) {
                activeFlag.get()
            }
            val appOps = Mockito.mock(AppOpsManager::class.java)

            val checkStr = backend.invokeMethod(
                AppOpsManager::class.java,
                "checkOpNoThrow",
                appOps,
                arrayOf("android:mock_location", 1000, "com.target.app"),
                originalImpl = { AppOpsManager.MODE_ALLOWED }
            )
            val unsafeCheck = backend.invokeMethod(
                AppOpsManager::class.java,
                "unsafeCheckOpNoThrow",
                appOps,
                arrayOf("android:mock_location", 1000, "com.target.app"),
                originalImpl = { AppOpsManager.MODE_ALLOWED }
            )
            val noteOpCode = backend.invokeMethod(
                AppOpsManager::class.java,
                "noteOpNoThrow",
                appOps,
                arrayOf(58, 1000, "com.target.app"),
                originalImpl = { AppOpsManager.MODE_ALLOWED }
            )
            val isOpActive = backend.invokeMethod(
                AppOpsManager::class.java,
                "isOpActive",
                appOps,
                arrayOf("android:mock_location", 1000, "com.target.app"),
                originalImpl = { true }
            )

            assertEquals(AppOpsManager.MODE_IGNORED, checkStr.result)
            assertEquals(AppOpsManager.MODE_IGNORED, unsafeCheck.result)
            assertEquals(AppOpsManager.MODE_IGNORED, noteOpCode.result)
            assertEquals(false, isOpActive.result)

            activeFlag.set(false)
            val passthrough = backend.invokeMethod(
                AppOpsManager::class.java,
                "unsafeCheckOpNoThrow",
                appOps,
                arrayOf("android:mock_location", 1000, "com.target.app"),
                originalImpl = { AppOpsManager.MODE_ALLOWED }
            )
            assertEquals(AppOpsManager.MODE_ALLOWED, passthrough.result)
        } finally {
            MockLocationCleaner.clearForTest()
            backend.clearHooks()
        }
    }

    // ==================== F10: 基站与 WiFi 反检测拦截 ====================

    @Test
    fun `T1-F10-01 NetworkAntiDetectHook intercepts TelephonyManager sync and requestCellInfoUpdate callbacks`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    antiDetectCellEnabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val tm = Mockito.mock(TelephonyManager::class.java)
            val realCell = Mockito.mock(CellInfo::class.java)
            val realCellList = listOf(realCell)

            @Suppress("UNCHECKED_CAST")
            val allCells = backend.invokeMethod(
                TelephonyManager::class.java,
                "getAllCellInfo",
                tm,
                originalImpl = { realCellList }
            ).result as List<CellInfo>
            assertTrue(allCells.isEmpty())

            val fakeCellLoc = Mockito.mock(CellLocation::class.java)
            val virtualHook = NetworkAntiDetectHook(
                backend = backend,
                configProvider = configStore,
                cellLocationFactory = { fakeCellLoc }
            )
            backend.clearHooks()
            virtualHook.install(newIsolatedClassLoader(), "com.target.app")

            val cellLocRes = backend.invokeMethod(
                TelephonyManager::class.java,
                "getCellLocation",
                tm,
                originalImpl = { Mockito.mock(CellLocation::class.java) }
            ).result
            assertSame(fakeCellLoc, cellLocRes)
            val (lac, cid) = NetworkAntiDetectHook.deriveDeterministicVirtualCell(
                TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
                TestCoordinates.SHANGHAI_LUJIAZUI.longitude
            )
            assertTrue(lac in 1000..60999 && cid in 1000..60999)

            val callback = ConcreteCellInfoCallback()
            backend.invokeMethod(
                TelephonyManager::class.java,
                "requestCellInfoUpdate",
                tm,
                args = arrayOf(Executor { it.run() }, callback)
            )
            backend.invokeMethod(
                ConcreteCellInfoCallback::class.java,
                "onCellInfo",
                callback,
                args = arrayOf(realCellList.toMutableList()),
                originalImpl = { a ->
                    @Suppress("UNCHECKED_CAST")
                    callback.onCellInfo(a[0] as List<CellInfo>)
                }
            )
            assertEquals(1, callback.received.size)
            assertTrue(callback.received[0].isEmpty())
            assertFalse(backend.hasInterfaceHookAllMethods())
            virtualHook.clearForTest()
        }
    }

    @Test
    fun `T1-F10-02 NetworkAntiDetectHook dynamically hooks concrete TelephonyCallback subclass without hooking base`() {
        val backend = E2eHookBackend(declaredOnly = true)
        val configStore = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1_000L },
            sdkIntProvider = { 35 }
        )
        val networkHook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = configStore,
            sdkIntProvider = { 35 }
        )
        try {
            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    antiDetectCellEnabled = true,
                    targetPackages = setOf("com.target.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            networkHook.install(newIsolatedClassLoader(), "com.target.app")

            val tm = Mockito.mock(TelephonyManager::class.java)
            val callback = DerivedTelephonyCellCallback()
            backend.invokeMethod(
                TelephonyManager::class.java,
                "registerTelephonyCallback",
                tm,
                args = arrayOf(Executor { it.run() }, callback)
            )

            assertFalse(backend.hasInterfaceHookAllMethods())
            assertEquals(0, backend.hookAllMethodsInstallCount(TelephonyCallback::class.java))
            assertEquals(0, backend.hookAllMethodsInstallCount(TelephonyCallback.CellInfoListener::class.java))

            val realCell = Mockito.mock(CellInfo::class.java)
            backend.invokeMethod(
                DerivedTelephonyCellCallback::class.java,
                "onCellInfoChanged",
                callback,
                args = arrayOf(mutableListOf(realCell)),
                originalImpl = { a ->
                    @Suppress("UNCHECKED_CAST")
                    callback.onCellInfoChanged(a[0] as List<CellInfo>)
                }
            )
            assertEquals(1, callback.received.size)
            assertTrue(callback.received[0].isEmpty())
        } finally {
            networkHook.clearForTest()
            configStore.resetForTest()
            backend.clearHooks()
        }
    }

    @Test
    fun `T1-F10-03 NetworkAntiDetectHook intercepts WifiManager getScanResults startScan and ScanResultsCallback`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
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

            val wm = Mockito.mock(WifiManager::class.java)
            val realScans = listOf(Any(), Any())
            @Suppress("UNCHECKED_CAST")
            val scans = backend.invokeMethod(
                WifiManager::class.java,
                "getScanResults",
                wm,
                originalImpl = { realScans }
            ).result as List<Any>
            assertTrue(scans.isEmpty())

            val startScanCalled = AtomicBoolean(false)
            val scanResult = backend.invokeMethod(
                WifiManager::class.java,
                "startScan",
                wm,
                originalImpl = {
                    startScanCalled.set(true)
                    false
                }
            ).result as Boolean
            assertTrue(scanResult)

            val scanCb = ConcreteWifiScanResultsCallback()
            backend.invokeMethod(
                WifiManager::class.java,
                "registerScanResultsCallback",
                wm,
                args = arrayOf(Executor { it.run() }, scanCb)
            )
            backend.invokeMethod(
                ConcreteWifiScanResultsCallback::class.java,
                "onScanResultsAvailable",
                scanCb,
                originalImpl = { scanCb.onScanResultsAvailable() }
            )
            assertEquals(1, scanCb.availableCount)
            @Suppress("UNCHECKED_CAST")
            val scansAfterCallback = backend.invokeMethod(
                WifiManager::class.java,
                "getScanResults",
                wm,
                originalImpl = { realScans }
            ).result as List<Any>
            assertTrue(scansAfterCallback.isEmpty())
        }
    }

    @Test
    fun `T1-F10-04 NetworkAntiDetectHook sanitizes WifiInfo BSSID SSID MacAddress IpAddress and ApMldMacAddress`() {
        withCleanHookEnvironment { backend, configStore, _, networkHook, _, _ ->
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

            val wifiInfo = Mockito.mock(WifiInfo::class.java)
            assertEquals(
                NetworkAntiDetectHook.SANITIZED_BSSID,
                backend.invokeMethod(WifiInfo::class.java, "getBSSID", wifiInfo) { "aa:bb:cc:dd:ee:ff" }.result
            )
            assertEquals(
                NetworkAntiDetectHook.SANITIZED_SSID,
                backend.invokeMethod(WifiInfo::class.java, "getSSID", wifiInfo) { "\"CorpSecretWiFi\"" }.result
            )
            assertEquals(
                NetworkAntiDetectHook.SANITIZED_BSSID,
                backend.invokeMethod(WifiInfo::class.java, "getMacAddress", wifiInfo) { "11:22:33:44:55:66" }.result
            )
            assertEquals(
                0,
                backend.invokeMethod(WifiInfo::class.java, "getIpAddress", wifiInfo) { 0x0101A8C0 }.result
            )
            assertNull(
                backend.invokeMethod(WifiInfo::class.java, "getApMldMacAddress", wifiInfo) { "77:88:99:aa:bb:cc" }.result
            )
        }
    }

    @Test
    fun `T1-F10-05 NetworkAntiDetectHook sanitizes WifiInfo returned by NetworkCapabilities getTransportInfo`() {
        val backend = E2eHookBackend()
        val configStore = HookConfigStore(
            prefsReaderFactory = { null },
            elapsedRealtimeProvider = { 1_000L },
            sdkIntProvider = { 35 }
        )
        val rawWifi = Mockito.mock(WifiInfo::class.java)
        val sanitizedWifi = Mockito.mock(WifiInfo::class.java)
        val networkHook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = configStore,
            wifiInfoSanitizer = { orig, _ ->
                if (orig === rawWifi) sanitizedWifi else orig
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
            val transport = backend.invokeMethod(
                NetworkCapabilities::class.java,
                "getTransportInfo",
                netCaps,
                originalImpl = { rawWifi }
            ).result
            assertSame(sanitizedWifi, transport)

            val bssid = backend.invokeMethod(
                WifiInfo::class.java,
                "getBSSID",
                transport,
                originalImpl = { "aa:bb:cc:dd:ee:ff" }
            ).result
            assertEquals(NetworkAntiDetectHook.SANITIZED_BSSID, bssid)
        } finally {
            networkHook.clearForTest()
            configStore.resetForTest()
            backend.clearHooks()
        }
    }

    // ==================== F11: 国产第三方 SDK 与 GMS 拦截 ====================

    @Test
    fun `T1-F11-01 ThirdPartyLbsSdkHook intercepts Tencent SDK converts WGS84 to GCJ02 and unregisters proxy`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerTencent(backend)
            val wgs = TestCoordinates.BEIJING_TIANANMEN
            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(wgs.latitude, wgs.longitude)

            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = wgs.latitude,
                    longitude = wgs.longitude,
                    altitude = wgs.altitude,
                    targetPackages = setOf("com.tencent.mm"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.tencent.mm")

            val mgr = E2eLbsSdkDoubles.TencentLocationManager()
            val listener = E2eLbsSdkDoubles.ConcreteTencentLocationListener()

            val reqMethod = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.TencentLocationListener::class.java
            )
            backend.invokeMethod(
                member = reqMethod,
                thisObject = mgr,
                args = arrayOf(null, listener),
                originalImpl = { a ->
                    mgr.requestLocationUpdates(a[0], a[1] as E2eLbsSdkDoubles.TencentLocationListener?)
                }
            )
            val registeredProxy = mgr.registeredListener as E2eLbsSdkDoubles.TencentLocationListener
            registeredProxy.onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 7, "FAIL")

            val delivered = listener.lastLocation!!
            assertEquals(expectedGcj.lat, delivered.getLatitude(), 1e-6)
            assertEquals(expectedGcj.lon, delivered.getLongitude(), 1e-6)
            assertNotEquals(wgs.latitude, delivered.getLatitude(), 1e-4)
            assertEquals(0, listener.lastError)
            assertEquals("OK", listener.lastReason)
            assertEquals(0, delivered.getFakeReason())
            assertEquals(0, delivered.getFake())
            assertFalse(delivered.isMockGps())
            assertEquals("", delivered.getAddress())

            val remMethod = E2eLbsSdkDoubles.TencentLocationManager::class.java.getMethod(
                "removeUpdates",
                E2eLbsSdkDoubles.TencentLocationListener::class.java
            )
            backend.invokeMethod(
                member = remMethod,
                thisObject = mgr,
                args = arrayOf(listener),
                originalImpl = { a ->
                    mgr.removeUpdates(a[0] as E2eLbsSdkDoubles.TencentLocationListener?)
                }
            )
            assertSame(registeredProxy, mgr.removedListener)
            assertFalse(backend.hasInterfaceHookAllMethods())
        }
    }

    @Test
    fun `T1-F11-02 ThirdPartyLbsSdkHook intercepts AMap SDK converts WGS84 to GCJ02 and cleans risk fields`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerAmap(backend)
            val wgs = TestCoordinates.SHANGHAI_LUJIAZUI
            val expectedGcj = GeoCoordinateMathReference.wgs84ToGcj02(wgs.latitude, wgs.longitude)

            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = wgs.latitude,
                    longitude = wgs.longitude,
                    altitude = wgs.altitude,
                    targetPackages = setOf("com.autonavi.minimap"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.autonavi.minimap")

            val client = E2eLbsSdkDoubles.AMapLocationClient()
            val listener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.AMapLocationClient::class.java,
                methodName = "setLocationListener",
                thisObject = client,
                args = arrayOf(listener),
                originalImpl = { a -> client.setLocationListener(a[0] as? E2eLbsSdkDoubles.AMapLocationListener) }
            )

            val rawLoc = E2eLbsSdkDoubles.AMapLocation("lbs")
            backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                methodName = "onLocationChanged",
                thisObject = listener,
                args = arrayOf(rawLoc),
                originalImpl = { a -> listener.onLocationChanged(a[0] as? E2eLbsSdkDoubles.AMapLocation) }
            )

            val out = listener.lastLocation!!
            assertEquals(expectedGcj.lat, out.getLatitude(), 1e-6)
            assertEquals(expectedGcj.lon, out.getLongitude(), 1e-6)
            assertEquals(0, out.getErrorCode())
            assertEquals(1, out.getLocationType())
            assertEquals("GCJ02", out.getCoordType())
            assertFalse(out.isMock())
            assertEquals(1, out.getTrustedLevel())
            assertEquals("", out.getAddress())
            assertEquals("", out.getPoiName())

            val report = E2eLbsSdkDoubles.AMapLocationQualityReport()
            val mockAppDetected = backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.AMapLocationQualityReport::class.java,
                methodName = "isInstalledHighDangerMockApp",
                thisObject = report,
                originalImpl = { report.isInstalledHighDangerMockApp() }
            ).result as Boolean
            assertFalse(mockAppDetected)
            assertFalse(backend.hasInterfaceHookAllMethods())
        }
    }

    @Test
    fun `T1-F11-03 ThirdPartyLbsSdkHook intercepts Baidu SDK converts WGS84 to BD09 and cleans risk fields`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerBaidu(backend)
            val wgs = TestCoordinates.SHENZHEN_NANSHAN
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

            val client = E2eLbsSdkDoubles.LocationClient()
            val listener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            val regMethod = E2eLbsSdkDoubles.LocationClient::class.java.getMethod(
                "registerLocationListener",
                E2eLbsSdkDoubles.BDAbstractLocationListener::class.java
            )
            backend.invokeMethod(
                member = regMethod,
                thisObject = client,
                args = arrayOf(listener),
                originalImpl = { a ->
                    client.registerLocationListener(a[0] as? E2eLbsSdkDoubles.BDAbstractLocationListener)
                }
            )

            val rawBd = E2eLbsSdkDoubles.BDLocation()
            backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                methodName = "onReceiveLocation",
                thisObject = listener,
                args = arrayOf(rawBd),
                originalImpl = { a -> listener.onReceiveLocation(a[0] as? E2eLbsSdkDoubles.BDLocation) }
            )

            val out = listener.lastLocation!!
            assertEquals(expectedBd.lat, out.getLatitude(), 1e-6)
            assertEquals(expectedBd.lon, out.getLongitude(), 1e-6)
            assertEquals(61, out.getLocType())
            assertEquals("bd09ll", out.getCoorType())
            assertEquals(0, out.mMockGpsStrategy)
            assertEquals(0, out.mMockGpsProbability)
            assertEquals("", out.getAddrStr())
            assertTrue(out.getPoiList().isEmpty())

            val isMockGps = backend.invokeMethod(
                E2eLbsSdkDoubles.BDLocation::class.java,
                "isMockGps",
                out,
                originalImpl = { out.isMockGps() }
            ).result as Boolean
            val distToReal = backend.invokeMethod(
                E2eLbsSdkDoubles.BDLocation::class.java,
                "getDisToRealLocation",
                out,
                originalImpl = { out.getDisToRealLocation() }
            ).result as Float
            assertFalse(isMockGps)
            assertEquals(0.0f, distToReal, 1e-6f)
            assertFalse(backend.hasInterfaceHookAllMethods())
        }
    }

    @Test
    fun `T1-F11-04 ThirdPartyLbsSdkHook intercepts GMS FusedLocationProviderClient callbacks and Tasks in WGS84`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerGms(backend)
            val wgs = TestCoordinates.BEIJING_TIANANMEN

            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = wgs.latitude,
                    longitude = wgs.longitude,
                    altitude = wgs.altitude,
                    targetPackages = setOf("com.google.target"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.google.target")

            val client = backend.invokeStatic(
                E2eLbsSdkDoubles.LocationServices::class.java,
                "getFusedLocationProviderClient",
                args = arrayOf(null),
                originalImpl = { E2eLbsSdkDoubles.LocationServices.getFusedLocationProviderClient(null) }
            ).result as E2eLbsSdkDoubles.FusedLocationProviderClient

            val callback = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
            val reqCbMethod = E2eLbsSdkDoubles.FusedLocationProviderClient::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.LocationCallback::class.java,
                Any::class.java
            )
            backend.invokeMethod(
                member = reqCbMethod,
                thisObject = client,
                args = arrayOf(null, callback, null)
            )

            val fakeLoc1 = E2eFakeLocation(provider = "fused", latitude = 10.0, longitude = 20.0).asAndroidLocation()
            val locResult = E2eLbsSdkDoubles.LocationResult(listOf(fakeLoc1))
            backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java,
                methodName = "onLocationResult",
                thisObject = callback,
                args = arrayOf(locResult),
                originalImpl = { a -> callback.onLocationResult(a[0] as E2eLbsSdkDoubles.LocationResult) }
            )
            val deliveredCbLoc = callback.lastResult!!.getLastLocation()!!
            assertEquals(wgs.latitude, deliveredCbLoc.latitude, 1e-6)
            assertEquals(wgs.longitude, deliveredCbLoc.longitude, 1e-6)
            assertFalse(deliveredCbLoc.isMock)

            val fakeLoc2 = E2eFakeLocation(provider = "fused", latitude = 10.0, longitude = 20.0).asAndroidLocation()
            val task = E2eLbsSdkDoubles.Task(fakeLoc2)
            backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.FusedLocationProviderClient::class.java,
                methodName = "getLastLocation",
                thisObject = client,
                originalImpl = { task }
            )
            val taskLoc = backend.invokeMethod(
                clazz = E2eLbsSdkDoubles.Task::class.java,
                methodName = "getResult",
                thisObject = task,
                originalImpl = { task.getResult() }
            ).result as Location
            assertEquals(wgs.latitude, taskLoc.latitude, 1e-6)
            assertEquals(wgs.longitude, taskLoc.longitude, 1e-6)
            assertFalse(taskLoc.isMock)
            assertFalse(backend.hasInterfaceHookAllMethods())
        }
    }

    @Test
    fun `T1-F11-05 ThirdPartyLbsSdkHook keeps overseas coordinates unshifted across all four SDKs`() {
        withCleanHookEnvironment { backend, configStore, _, _, thirdPartyHook, _ ->
            E2eLbsSdkDoubles.registerAll(backend)
            val ny = TestCoordinates.NEW_YORK_TIMES_SQUARE

            configStore.applyIfNewer(
                TestConfigPayloads.LSPOSED_ACTIVE_CONFIG.toMockConfig().copy(
                    enabled = true,
                    latitude = ny.latitude,
                    longitude = ny.longitude,
                    altitude = ny.altitude,
                    targetPackages = setOf("com.overseas.app"),
                    updatedAtMs = 1_000L,
                    version = 1L
                )
            )
            thirdPartyHook.installForClassLoader(newIsolatedClassLoader(), "com.overseas.app")

            // 1. 腾讯
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
            (tMgr.registeredListener as E2eLbsSdkDoubles.TencentLocationListener)
                .onLocationChanged(E2eLbsSdkDoubles.DefaultTencentLocation(), 1, "ERR")
            assertEquals(ny.latitude, tListener.lastLocation!!.getLatitude(), 1e-9)
            assertEquals(ny.longitude, tListener.lastLocation!!.getLongitude(), 1e-9)

            // 2. 高德
            val aClient = E2eLbsSdkDoubles.AMapLocationClient()
            val aListener = E2eLbsSdkDoubles.ConcreteAMapLocationListener()
            backend.invokeMethod(
                E2eLbsSdkDoubles.AMapLocationClient::class.java,
                "setLocationListener",
                aClient,
                arrayOf(aListener)
            )
            val aLoc = E2eLbsSdkDoubles.AMapLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteAMapLocationListener::class.java,
                "onLocationChanged",
                aListener,
                arrayOf(aLoc)
            ) { a -> aListener.onLocationChanged(a[0] as? E2eLbsSdkDoubles.AMapLocation) }
            assertEquals(ny.latitude, aListener.lastLocation!!.getLatitude(), 1e-9)
            assertEquals(ny.longitude, aListener.lastLocation!!.getLongitude(), 1e-9)

            // 3. 百度
            val bClient = E2eLbsSdkDoubles.LocationClient()
            val bListener = E2eLbsSdkDoubles.ConcreteBaiduLocationListener()
            val bReq = E2eLbsSdkDoubles.LocationClient::class.java.getMethod(
                "registerLocationListener",
                E2eLbsSdkDoubles.BDAbstractLocationListener::class.java
            )
            backend.invokeMethod(bReq, bClient, arrayOf(bListener))
            val bLoc = E2eLbsSdkDoubles.BDLocation()
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteBaiduLocationListener::class.java,
                "onReceiveLocation",
                bListener,
                arrayOf(bLoc)
            ) { a -> bListener.onReceiveLocation(a[0] as? E2eLbsSdkDoubles.BDLocation) }
            assertEquals(ny.latitude, bListener.lastLocation!!.getLatitude(), 1e-9)
            assertEquals(ny.longitude, bListener.lastLocation!!.getLongitude(), 1e-9)

            // 4. GMS
            val gClient = E2eLbsSdkDoubles.FusedLocationProviderClient()
            val gCallback = E2eLbsSdkDoubles.ConcreteGmsLocationCallback()
            val gReq = E2eLbsSdkDoubles.FusedLocationProviderClient::class.java.getMethod(
                "requestLocationUpdates",
                Any::class.java,
                E2eLbsSdkDoubles.LocationCallback::class.java,
                Any::class.java
            )
            backend.invokeMethod(gReq, gClient, arrayOf(null, gCallback, null))
            val gFakeLoc = E2eFakeLocation(provider = "fused").asAndroidLocation()
            val gRes = E2eLbsSdkDoubles.LocationResult(listOf(gFakeLoc))
            backend.invokeMethod(
                E2eLbsSdkDoubles.ConcreteGmsLocationCallback::class.java,
                "onLocationResult",
                gCallback,
                arrayOf(gRes)
            ) { a -> gCallback.onLocationResult(a[0] as E2eLbsSdkDoubles.LocationResult) }
            assertEquals(ny.latitude, gCallback.lastResult!!.getLastLocation()!!.latitude, 1e-9)
            assertEquals(ny.longitude, gCallback.lastResult!!.getLastLocation()!!.longitude, 1e-9)
        }
    }
}
