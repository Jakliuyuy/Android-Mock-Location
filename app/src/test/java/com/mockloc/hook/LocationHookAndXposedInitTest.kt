package com.mockloc.hook

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.mockloc.data.config.MockConfig
import com.mockloc.root.EnvironmentDetector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

class LocationHookAndXposedInitTest {

    @Before
    fun setUp() {
        LocationHook.currentAppContext = null
        LocationHook.currentTargetPackage = ""
    }

    @After
    fun tearDown() {
        LocationHook.currentAppContext = null
        LocationHook.currentTargetPackage = ""
    }

    @Test
    fun `XposedInit self-check branch hooks EnvironmentDetector and skips location hooks`() {
        val backend = FakeHookBackend()
        val store = HookConfigStore(prefsReaderFactory = { null })
        val locHook = LocationHook(backend = backend, configProvider = store)
        val netHook = NetworkAntiDetectHook(backend = backend, configProvider = store)
        val thirdHook = ThirdPartyLbsSdkHook(backend = backend, configProvider = store)
        val clHelper = ClassLoaderHookHelper(backend = backend)

        XposedInit().handlePackageInternal(
            packageName = "com.mockloc",
            classLoader = javaClass.classLoader,
            backend = backend,
            configStore = store,
            locationHook = locHook,
            networkHook = netHook,
            thirdPartyHook = thirdHook,
            classLoaderHelper = clHelper,
            xposedVersionProvider = { 100 }
        )

        // 自身进程 Hook 了自检方法，但未挂载 LocationManager 欺骗
        assertTrue(backend.hookCountFor(EnvironmentDetector::class.java, "isModuleActive") > 0)
        assertTrue(backend.hookCountFor(EnvironmentDetector::class.java, "getXposedApiVersion") > 0)
        assertEquals(0, backend.hookCountFor(LocationManager::class.java, "requestLocationUpdates"))
        assertEquals(true, backend.invokeSimulated(null, EnvironmentDetector::class.java, "isModuleActive").result)
        assertEquals(100, backend.invokeSimulated(null, EnvironmentDetector::class.java, "getXposedApiVersion").result)
    }

    @Test
    fun `XposedInit system_server branch registers no broadcast receivers or pulse schedulers`() {
        val backend = FakeHookBackend()
        val store = HookConfigStore(prefsReaderFactory = { null })
        val locHook = LocationHook(backend = backend, configProvider = store)
        val netHook = NetworkAntiDetectHook(backend = backend, configProvider = store)
        val thirdHook = ThirdPartyLbsSdkHook(backend = backend, configProvider = store)
        val clHelper = ClassLoaderHookHelper(backend = backend)

        XposedInit().handlePackageInternal(
            packageName = "android",
            classLoader = javaClass.classLoader,
            backend = backend,
            configStore = store,
            locationHook = locHook,
            networkHook = netHook,
            thirdPartyHook = thirdHook,
            classLoaderHelper = clHelper,
            xposedVersionProvider = { 93 }
        )

        // system_server 下不挂普通应用 ClassLoader/ContextWrapper 钩子、不启动脉冲线程
        assertEquals(0, backend.recordedConstructorHooks.size)
        assertEquals(0, backend.hookCountFor(LocationManager::class.java, "requestLocationUpdates"))
    }

    @Test
    fun `ClassLoaderHookHelper deduplicates ClassLoaders and dispatches dynamic BaseDexClassLoader`() {
        val backend = FakeHookBackend()
        val helper = ClassLoaderHookHelper(backend)
        val seenLoaders = mutableListOf<ClassLoader>()
        val initialCl = javaClass.classLoader!!

        helper.install(
            initialClassLoader = initialCl,
            onContextReady = {},
            onClassLoaderReady = { seenLoaders.add(it) }
        )
        assertEquals(1, seenLoaders.size)

        // 重复传入相同 ClassLoader 实例必须去重
        helper.dispatchClassLoader(initialCl) { seenLoaders.add(it) }
        assertEquals(1, seenLoaders.size)

        // 模拟新动态加载 ClassLoader 出现
        val pluginCl = object : ClassLoader(initialCl) {}
        helper.dispatchClassLoader(pluginCl) { seenLoaders.add(it) }
        assertEquals(2, seenLoaders.size)
        assertSame(pluginCl, seenLoaders[1])
    }

    @Test
    fun `LocationHook wraps LocationListener delivers first frame and removes updates consistently`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(
                enabled = true,
                latitude = 39.9087,
                longitude = 116.3975,
                altitude = 50.0,
                accuracy = 3.0f,
                targetPackages = setOf("com.tencent.mm"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(
                    initialProvider = provider,
                    initialLat = cfg.latitude,
                    initialLon = cfg.longitude,
                    initialAlt = cfg.altitude,
                    initialAcc = cfg.accuracy,
                    initialMock = false
                )
            }
        )

        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val listener = LocationListener { loc -> delivered.add(loc) }
        val directExecutor = Executor { it.run() }

        val reqInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestLocationUpdates",
            LocationManager.GPS_PROVIDER,
            1000L,
            1.0f,
            directExecutor,
            listener
        )

        val proxy = reqInv.args[4] as LocationListener
        assertNotSame(listener, proxy)
        // afterHookedMethod 立即触发首帧脉冲
        assertEquals(1, delivered.size)
        assertEquals(39.9087, delivered[0].latitude, 1e-6)
        assertEquals(116.3975, delivered[0].longitude, 1e-6)
        assertFalse(delivered[0].isMock)

        // 热更新坐标（无一次性锁死）后主动脉冲应推送新坐标
        cfgProvider.config = cfgProvider.config.copy(latitude = 31.2304, longitude = 121.4737, version = 2L)
        val tickCount = hook.dispatchPulseTick("com.tencent.mm", forceImmediate = true)
        assertEquals(1, tickCount)
        assertEquals(2, delivered.size)
        assertEquals(31.2304, delivered.last().latitude, 1e-6)

        // removeUpdates(original) 必须还原代理并注销脉冲订阅
        val removeInv = backend.invokeSimulated(null, LocationManager::class.java, "removeUpdates", listener)
        assertSame(proxy, removeInv.args[0])
        assertEquals(0, hook.dispatchPulseTick("com.tencent.mm", forceImmediate = true))

        hook.clearForTest()
    }

    @Test
    fun `LocationHook covers getCurrentLocation getLastKnownLocation provider queries and NMEA checksum`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(
                enabled = true,
                latitude = 39.9087,
                longitude = 116.3975,
                antiDetectGnssEnabled = true,
                targetPackages = setOf("com.autonavi.minimap"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(
                    initialProvider = provider,
                    initialLat = cfg.latitude,
                    initialLon = cfg.longitude
                )
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.autonavi.minimap")

        var currentLoc: Location? = null
        val consumer = Consumer<Location> { currentLoc = it }
        val directExecutor = Executor { it.run() }
        val getCurInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "getCurrentLocation",
            "fused",
            null,
            directExecutor,
            consumer
        )
        assertTrue(getCurInv.isEarlyReturnRequested)
        assertEquals(39.9087, currentLoc!!.latitude, 1e-6)
        assertEquals("fused", currentLoc!!.provider)

        // getLastKnownLocation (含 LastLocationRequest 2 参)
        val lastInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "getLastKnownLocation",
            "gps",
            Any()
        )
        assertEquals(39.9087, (lastInv.result as Location).latitude, 1e-6)

        // Provider 状态查询
        assertEquals(true, backend.invokeSimulated(null, LocationManager::class.java, "isLocationEnabled") { false }.result)
        assertEquals(true, backend.invokeSimulated(null, LocationManager::class.java, "isProviderEnabled", "gps") { false }.result)
        assertEquals("gps", backend.invokeSimulated(null, LocationManager::class.java, "getBestProvider", null, true) { "passive" }.result)
        @Suppress("UNCHECKED_CAST")
        val providers = backend.invokeSimulated(null, LocationManager::class.java, "getAllProviders") { listOf("passive") }.result as List<String>
        assertTrue(providers.containsAll(listOf("gps", "network", "fused")))

        // NMEA 校验和验证
        val nmea = hook.buildSyntheticGpggaNmea(cfgProvider.config)
        assertTrue(nmea.startsWith("\$GPGGA,"))
        val starIdx = nmea.indexOf('*')
        val body = nmea.substring(1, starIdx)
        val expectedChecksum = body.fold(0) { acc, ch -> acc xor ch.code } and 0xFF
        val actualHex = nmea.substring(starIdx + 1, starIdx + 3)
        assertEquals(String.format("%02X", expectedChecksum), actualHex)

        // PendingIntent 订阅登记并在取消异常时自动移除不崩溃
        val pi = mock<PendingIntent>()
        backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestLocationUpdates",
            "gps",
            1000L,
            0f,
            pi
        )
        // 未注入 Context 时返回 0 但不崩溃
        assertEquals(0, hook.dispatchPulseTick("com.autonavi.minimap", forceImmediate = true))

        hook.clearForTest()
    }

    @Test
    fun `LocationHook requestSingleUpdate delivers first frame on after hook without reentry double delivery`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(
                enabled = true,
                latitude = 39.9087,
                longitude = 116.3975,
                targetPackages = setOf("com.tencent.mm"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val listener = LocationListener { delivered.add(it) }
        val reqInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestSingleUpdate",
            LocationManager.GPS_PROVIDER,
            listener,
            null
        )

        val proxy = reqInv.args[1] as LocationListener
        assertNotSame(listener, proxy)
        // 修复先删后读 Critical Bug 后，afterHookedMethod 必须准确发出 1 次首帧
        assertEquals(1, delivered.size)
        assertEquals(39.9087, delivered[0].latitude, 1e-6)

        // 随后系统再回调 proxy.onLocationChanged 应防重入吞掉，不发生双倍投递
        val realLoc = createStatefulMockLocation(initialLat = 1.0, initialLon = 2.0)
        proxy.onLocationChanged(realLoc)
        assertEquals(1, delivered.size)

        hook.clearForTest()
    }

    @Test
    fun `LocationHook requestSingleUpdate synchronous callback inside original call delivers only once`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(
                enabled = true,
                latitude = 39.9087,
                longitude = 116.3975,
                targetPackages = setOf("com.tencent.mm"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val listener = LocationListener { delivered.add(it) }
        backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestSingleUpdate",
            LocationManager.GPS_PROVIDER,
            listener,
            null
        ) { args ->
            // 模拟底层在 requestSingleUpdate 原方法体内部同步回调 args[1].onLocationChanged(...)
            val passedProxy = args[1] as LocationListener
            passedProxy.onLocationChanged(createStatefulMockLocation(initialLat = 12.34, initialLon = 56.78))
            null
        }

        assertEquals("Synchronous callback inside requestSingleUpdate must deliver strictly once", 1, delivered.size)
        assertEquals(39.9087, delivered[0].latitude, 1e-6)
        assertEquals(116.3975, delivered[0].longitude, 1e-6)
        assertEquals(0, hook.dispatchPulseTick("com.tencent.mm", forceImmediate = true))

        hook.clearForTest()
    }

    @Test
    fun `LocationHook reusing singleShot listener in requestLocationUpdates upgrades mode and delivers continuous updates`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(
                enabled = true,
                latitude = 39.9087,
                longitude = 116.3975,
                targetPackages = setOf("com.tencent.mm"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val sharedListener = LocationListener { delivered.add(it) }
        backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestSingleUpdate",
            LocationManager.GPS_PROVIDER,
            sharedListener,
            null
        )
        assertEquals(1, delivered.size)

        // 未调 removeUpdates 直接复用同一 listener 发起持续定位
        val repeatInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestLocationUpdates",
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            Executor { it.run() },
            sharedListener
        )
        val proxy = repeatInv.args[4] as LocationListener
        assertEquals(2, delivered.size)

        // 后续连续系统回调必须全部按持续模式转换并投递，不得被旧 singleShotConsumed 吞掉
        repeat(3) {
            proxy.onLocationChanged(createStatefulMockLocation(initialLat = 10.0, initialLon = 20.0))
        }
        assertEquals(5, delivered.size)

        hook.clearForTest()
    }

    @Test
    fun `LocationHook supports opening target app before enabling mock and pause-resume cycle without losing listeners`() {
        val backend = FakeHookBackend()
        val disabledCfg = MockConfig(
            enabled = false,
            latitude = 39.9087,
            longitude = 116.3975,
            targetPackages = setOf("com.tencent.mm"),
            updatedAtMs = 100L,
            version = 1L
        )
        val cfgProvider = StaticConfigSnapshotProvider(disabledCfg)
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        // 先开目标 App：enabled=false 时注册，必须包裹代理且登记订阅，同时不推假帧、透传真实坐标
        val listener = LocationListener { delivered.add(it) }
        val reqInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestLocationUpdates",
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            Executor { it.run() },
            listener
        )
        val proxy = reqInv.args[4] as LocationListener
        assertNotSame(listener, proxy)
        assertEquals(0, delivered.size)
        assertFalse(hook.isPulseSchedulerActive())

        proxy.onLocationChanged(createStatefulMockLocation(initialLat = 12.34, initialLon = 56.78))
        assertEquals(1, delivered.size)
        assertEquals(12.34, delivered[0].latitude, 1e-6)

        // 后开模拟：切换 enabled=true -> 立即触发首帧脉冲并启动调度器
        val enabledCfg1 = disabledCfg.copy(enabled = true, updatedAtMs = 200L, version = 2L)
        cfgProvider.config = enabledCfg1
        hook.onConfigChanged(enabledCfg1, "com.tencent.mm")
        assertEquals(2, delivered.size)
        assertEquals(39.9087, delivered.last().latitude, 1e-6)
        assertTrue(hook.isPulseSchedulerActive())

        // 中途暂停：停用只暂停脉冲任务，绝不清空 activeListeners
        val pausedCfg = enabledCfg1.copy(enabled = false, updatedAtMs = 300L, version = 3L)
        cfgProvider.config = pausedCfg
        hook.onConfigChanged(pausedCfg, "com.tencent.mm")
        assertFalse(hook.isPulseSchedulerActive())
        proxy.onLocationChanged(createStatefulMockLocation(initialLat = 22.22, initialLon = 33.33))
        assertEquals(22.22, delivered.last().latitude, 1e-6)

        // 再次恢复：直接恢复推送新模拟坐标，绝不失联
        val resumedCfg = pausedCfg.copy(
            enabled = true,
            latitude = 31.2304,
            longitude = 121.4737,
            updatedAtMs = 400L,
            version = 4L
        )
        cfgProvider.config = resumedCfg
        hook.onConfigChanged(resumedCfg, "com.tencent.mm")
        assertEquals(31.2304, delivered.last().latitude, 1e-6)
        assertTrue(hook.isPulseSchedulerActive())

        hook.clearForTest()
    }

    @Test
    fun `LocationHook pulse delivers L1 throttled hot updates without L2 broadcast`() {
        val nowMs = AtomicLong(1_000L)
        val initialCfg = MockConfig(
            enabled = true,
            latitude = 39.9087,
            longitude = 116.3975,
            targetPackages = setOf("com.tencent.mm"),
            updatedAtMs = 100L,
            version = 1L
        )
        val fakeReader = FakePrefsReader(initialCfg)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            elapsedRealtimeProvider = { nowMs.get() },
            throttleMs = 400L
        )
        val backend = FakeHookBackend()
        val delivered = mutableListOf<Location>()
        val hook = LocationHook(
            backend = backend,
            configProvider = store,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val listener = LocationListener { delivered.add(it) }
        backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestLocationUpdates",
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            Executor { it.run() },
            listener
        )
        assertEquals(39.9087, delivered.last().latitude, 1e-6)

        // 模拟 L2 广播丢失：只写入 L1 文件并推进超过 400ms
        fakeReader.writeConfig(
            initialCfg.copy(latitude = 22.5431, longitude = 114.0579, updatedAtMs = 200L, version = 2L),
            markChanged = true
        )
        nowMs.set(1_600L)
        val dispatched = hook.dispatchPulseTick("com.tencent.mm", forceImmediate = true)
        assertEquals(1, dispatched)
        assertEquals(22.5431, delivered.last().latitude, 1e-6)

        hook.clearForTest()
    }

    @Test
    fun `LocationHook singleShot listeners do not leak via listenerProxies after GC`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            MockConfig(enabled = true, targetPackages = setOf("com.tencent.mm"), version = 1L)
        )
        val hook = LocationHook(
            backend = backend,
            configProvider = cfgProvider,
            locationFactory = { provider, cfg ->
                createStatefulMockLocation(initialProvider = provider, initialLat = cfg.latitude, initialLon = cfg.longitude)
            }
        )
        hook.installSystemHooks(javaClass.classLoader, "com.tencent.mm")

        val ref = registerSingleShotInIsolatedFrame(backend)
        for (i in 0 until 20) {
            System.gc()
            Thread.sleep(10)
            if (ref.get() == null) break
        }
        assertNull("singleShot LocationListener should be GC-able", ref.get())
        assertEquals(0, hook.trackedProxyCountForTest())

        hook.clearForTest()
    }

    private fun registerSingleShotInIsolatedFrame(backend: FakeHookBackend): WeakReference<LocationListener> {
        // 使用匿名对象实例而非无捕获 Lambda，避免 JVM 将无捕获 Lambda 缓存为静态单例 GC Root
        val listener: LocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) = Unit
        }
        val reqInv = backend.invokeSimulated(
            null,
            LocationManager::class.java,
            "requestSingleUpdate",
            LocationManager.GPS_PROVIDER,
            listener,
            null
        )
        assertTrue(reqInv.args[1] is LocationListener)
        return WeakReference(listener)
    }

    @Test
    fun `LocationHook buildSyntheticGpggaNmea carries minute rounding overflow to degrees`() {
        val hook = LocationHook(backend = FakeHookBackend(), configProvider = StaticConfigSnapshotProvider(MockConfig.DEFAULT))
        val nmea = hook.buildSyntheticGpggaNmea(
            MockConfig(latitude = 39.9999999, longitude = 116.9999999)
        )
        assertFalse("Minutes must never format as 60.0000: $nmea", nmea.contains("60.0000"))
        assertTrue("Expected carry to 40 degrees: $nmea", nmea.contains(",4000.0000,N,"))
        assertTrue("Expected carry to 117 degrees: $nmea", nmea.contains(",11700.0000,E,"))
    }

    private class FakeLocationManagerService {
        fun getLastLocation(pkg: String): Location? = null
    }

    @Test
    fun `XposedInit system_server cold-loads L1 config safely and cleans LocationManagerService getLastLocation`() {
        val backend = FakeHookBackend()
        backend.classRegistry["com.android.server.location.LocationManagerService"] =
            FakeLocationManagerService::class.java
        val reader = FakePrefsReader(
            MockConfig(
                enabled = true,
                targetPackages = setOf("com.tencent.mm"),
                updatedAtMs = 100L,
                version = 1L
            )
        )
        val store = HookConfigStore(prefsReaderFactory = { reader })
        XposedInit().installSystemServerSafeHooks(javaClass.classLoader, backend, store)

        val whitelistedLoc = createStatefulMockLocation(initialMock = true)
        backend.invokeSimulated(null, FakeLocationManagerService::class.java, "getLastLocation", "com.tencent.mm") {
            whitelistedLoc
        }
        assertFalse(whitelistedLoc.isMock)

        val nonWhitelistedLoc = createStatefulMockLocation(initialMock = true)
        backend.invokeSimulated(null, FakeLocationManagerService::class.java, "getLastLocation", "com.other.app") {
            nonWhitelistedLoc
        }
        assertTrue(nonWhitelistedLoc.isMock)
    }

    private class FakeBaseDexClassLoader

    @Test
    fun `ClassLoaderHookHelper installs system hooks only once per backend across multiple packages`() {
        val backend = FakeHookBackend()
        backend.classRegistry["dalvik.system.BaseDexClassLoader"] = FakeBaseDexClassLoader::class.java
        val helper1 = ClassLoaderHookHelper(backend)
        val helper2 = ClassLoaderHookHelper(backend)

        helper1.install(javaClass.classLoader, onContextReady = {}, onClassLoaderReady = {})
        helper2.install(javaClass.classLoader, onContextReady = {}, onClassLoaderReady = {})

        assertEquals(1, backend.hookCountFor(ContextWrapper::class.java, "attachBaseContext"))
        assertEquals(1, backend.hookCountFor(android.app.Application::class.java, "onCreate"))
        assertEquals(1, backend.recordedConstructorHooks.size)
        helper1.resetForTest()
        helper2.resetForTest()
    }
}
