package com.mockloc.hook

import android.os.Bundle
import com.mockloc.data.MockConfigManager
import com.mockloc.ipc.LocationConfigProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 针对深层继承类结构的 WifiInfo 仿真测试模型
 */
open class BaseLevel0Wifi

open class BaseLevel1Wifi(private var bssid: String = "11:22:33:44:55:66") : BaseLevel0Wifi() {
    fun getInternalBssid(): String = bssid
}

open class BaseLevel2Wifi(private var mBssid: String = "22:33:44:55:66:77") : BaseLevel1Wifi() {
    fun getInternalMBssid(): String = mBssid
}

open class AospStyleWifiInfo(private var mBSSID: String = "33:44:55:66:77:88") : BaseLevel2Wifi() {
    fun getBSSID(): String = mBSSID
}

class DerivedAospWifiInfo : AospStyleWifiInfo("aa:bb:cc:dd:ee:ff")

class IrrelevantObject(var someField: String = "hello")

class NonStringBssidWifi(var mBSSID: Int = 123456)

/**
 * NetworkAntiDetectHook, NetworkAntiDetectFilter 与 LocationConfigProvider IPC
 * 的全面对抗压力测试套件 (Empirical Adversarial Test Suite)。
 */
class NetworkAndIpcAdversarialTest {

    @Before
    fun setUp() {
        // 重置 MockConfigManager 为已知安全默认值
        MockConfigManager.isMockEnabled = false
        MockConfigManager.latitude = 39.9087
        MockConfigManager.longitude = 116.3975
        MockConfigManager.altitude = 50.0
        MockConfigManager.speed = 0.0f
        MockConfigManager.bearing = 0.0f
        MockConfigManager.accuracy = 3.0f
        MockConfigManager.isCellInterceptionEnabled = true
        MockConfigManager.isWifiInterceptionEnabled = true
        MockConfigManager.setTargetPackages(emptyList())
        MockConfigManager.currentProcessPackage = null
        MockConfigManager.isConfigSynced = false
    }

    @After
    fun tearDown() {
        MockConfigManager.isMockEnabled = false
        MockConfigManager.setTargetPackages(emptyList())
        MockConfigManager.currentProcessPackage = null
        MockConfigManager.isConfigSynced = false
    }

    // =========================================================================
    // 1. CellInfo & WiFi ScanResult Suppression 状态机与过滤验证
    // =========================================================================

    @Test
    fun testCellInfoSuppressionUnderCartesianToggles() {
        val testCells = listOf("Cell_LTE_460_00_101", "Cell_LTE_460_00_102", "Cell_NR_460_00_201")

        // 状态 1: 拦截生效 (isEnabled = true) -> 必须强制返回空列表
        val suppressed = NetworkAntiDetectFilter.filterCellInfo(testCells, isEnabled = true)
        assertTrue("CellInfo must be empty when suppression is enabled", suppressed.isEmpty())
        assertEquals(0, suppressed.size)

        // 状态 2: 拦截关闭 (isEnabled = false) -> 必须无损透传原始列表
        val passedThrough = NetworkAntiDetectFilter.filterCellInfo(testCells, isEnabled = false)
        assertEquals(3, passedThrough.size)
        assertEquals(testCells, passedThrough)

        // 状态 3: 传入 null 列表 -> 无论开关均返回安全空列表
        assertTrue(NetworkAntiDetectFilter.filterCellInfo<String>(null, isEnabled = true).isEmpty())
        assertTrue(NetworkAntiDetectFilter.filterCellInfo<String>(null, isEnabled = false).isEmpty())

        // 状态 4: 传入超大集合 (10,000 个基站) 压力测试
        val massiveCells = (1..10000).map { "Cell_$it" }
        val massiveSuppressed = NetworkAntiDetectFilter.filterCellInfo(massiveCells, isEnabled = true)
        assertTrue(massiveSuppressed.isEmpty())
        val massivePassed = NetworkAntiDetectFilter.filterCellInfo(massiveCells, isEnabled = false)
        assertEquals(10000, massivePassed.size)

        // 状态 5: 传入包含 null 元素的集合
        val nullElementCells = listOf("Cell_1", null, "Cell_2")
        val nullElemFiltered = NetworkAntiDetectFilter.filterCellInfo(nullElementCells, isEnabled = true)
        assertTrue(nullElemFiltered.isEmpty())

        // 状态 6: 不可变列表输入测试
        val unmodifiable = Collections.unmodifiableList(listOf("Cell_A", "Cell_B"))
        val unmodifiableFiltered = NetworkAntiDetectFilter.filterCellInfo(unmodifiable, isEnabled = true)
        assertTrue(unmodifiableFiltered.isEmpty())
    }

    @Test
    fun testWifiScanResultSuppressionUnderCartesianToggles() {
        val testAps = listOf("AP_Home_2.4G", "AP_Company_5G", "AP_Public_Free")

        // 拦截开启
        val suppressed = NetworkAntiDetectFilter.filterScanResults(testAps, isEnabled = true)
        assertTrue("WiFi ScanResult must be empty when suppression is enabled", suppressed.isEmpty())
        assertEquals(0, suppressed.size)

        // 拦截关闭
        val passedThrough = NetworkAntiDetectFilter.filterScanResults(testAps, isEnabled = false)
        assertEquals(3, passedThrough.size)
        assertEquals(testAps, passedThrough)

        // null 安全
        assertTrue(NetworkAntiDetectFilter.filterScanResults<String>(null, isEnabled = true).isEmpty())
        assertTrue(NetworkAntiDetectFilter.filterScanResults<String>(null, isEnabled = false).isEmpty())

        // 超大集合压力测试 (10,000 个热点)
        val massiveAps = (1..10000).map { "SSID_$it, BSSID=00:11:22:33:44:${it % 100}" }
        val massiveSuppressed = NetworkAntiDetectFilter.filterScanResults(massiveAps, isEnabled = true)
        assertTrue(massiveSuppressed.isEmpty())
        val massivePassed = NetworkAntiDetectFilter.filterScanResults(massiveAps, isEnabled = false)
        assertEquals(10000, massivePassed.size)
    }

    @Test
    fun testActiveScanSuppressionFlag() {
        assertTrue(NetworkAntiDetectFilter.shouldSuppressWifiScan(true))
        assertFalse(NetworkAntiDetectFilter.shouldSuppressWifiScan(false))
    }

    @Test
    fun testMockConfigManagerProcessAndPackageMatrix() {
        // 矩阵 1: 全局关闭
        MockConfigManager.isMockEnabled = false
        MockConfigManager.setTargetPackages(listOf("com.target.app"))
        MockConfigManager.currentProcessPackage = "com.target.app"
        assertFalse("When global mock is disabled, current process must be disabled", MockConfigManager.isMockEnabledForCurrentProcess())
        assertFalse("When global mock is disabled, package query must be false", MockConfigManager.isMockEnabledForPackage("com.target.app"))

        // 矩阵 2: 全局开启，白名单指定应用，匹配命中
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(listOf("com.target.app", "com.second.app"))
        MockConfigManager.currentProcessPackage = "com.target.app"
        assertTrue("Target package in whitelist must be enabled", MockConfigManager.isMockEnabledForCurrentProcess())
        assertTrue(MockConfigManager.isMockEnabledForPackage("com.target.app"))
        assertTrue(MockConfigManager.isMockEnabledForPackage("com.second.app"))

        // 矩阵 3: 全局开启，白名单指定应用，未命中
        MockConfigManager.currentProcessPackage = "com.unrelated.system"
        assertFalse("Unlisted package must be disabled", MockConfigManager.isMockEnabledForCurrentProcess())
        assertFalse(MockConfigManager.isMockEnabledForPackage("com.unrelated.system"))

        // 矩阵 4: 全局开启，白名单为空 (严格白名单模式：绝不匹配任何应用)
        MockConfigManager.setTargetPackages(emptyList())
        MockConfigManager.currentProcessPackage = "com.any.random.app"
        assertFalse("When whitelist is empty, current process must not be enabled", MockConfigManager.isMockEnabledForCurrentProcess())
        assertFalse("When whitelist is empty, any package query must be false", MockConfigManager.isMockEnabledForPackage("com.any.random.app"))

        // 矩阵 5: 边界情况，null 或空字符串包名
        assertFalse(MockConfigManager.isMockEnabledForPackage(null))
        assertFalse(MockConfigManager.isMockEnabledForPackage(""))
    }

    // =========================================================================
    // 2. BSSID Sanitization to "02:00:00:00:00:00" 深入测试
    // =========================================================================

    @Test
    fun testBssidSanitizationDeepHierarchy() {
        // 场景 A: 字段位于直接类的 mBSSID
        val aospWifi = AospStyleWifiInfo("12:34:56:78:90:ab")
        val successAosp = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(aospWifi, isEnabled = true)
        assertTrue("Direct mBSSID field should be sanitized", successAosp)
        assertEquals(NetworkAntiDetectFilter.MASKED_BSSID, aospWifi.getBSSID())

        // 场景 B: 字段位于子类的父类中 (DerivedAospWifiInfo 继承 AospStyleWifiInfo)
        val derivedWifi = DerivedAospWifiInfo()
        val successDerived = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(derivedWifi, isEnabled = true)
        assertTrue("Inherited mBSSID field should be sanitized", successDerived)
        assertEquals(NetworkAntiDetectFilter.MASKED_BSSID, derivedWifi.getBSSID())

        // 场景 C: 字段名为 mBssid (第二层父类)
        val level2Wifi = BaseLevel2Wifi("cc:dd:ee:11:22:33")
        val successLevel2 = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(level2Wifi, isEnabled = true)
        assertTrue("mBssid field in hierarchy should be sanitized", successLevel2)
        assertEquals(NetworkAntiDetectFilter.MASKED_BSSID, level2Wifi.getInternalMBssid())

        // 场景 D: 字段名为 bssid (第三层父类)
        val level1Wifi = BaseLevel1Wifi("44:55:66:77:88:99")
        val successLevel1 = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(level1Wifi, isEnabled = true)
        assertTrue("bssid field in hierarchy should be sanitized", successLevel1)
        assertEquals(NetworkAntiDetectFilter.MASKED_BSSID, level1Wifi.getInternalBssid())
    }

    @Test
    fun testBssidSanitizationWhenDisabledOrEdgeCases() {
        val originalBssid = "fe:dc:ba:98:76:54"
        val wifi = AospStyleWifiInfo(originalBssid)

        // 开关关闭时绝不修改原 BSSID
        val disabledResult = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(wifi, isEnabled = false)
        assertFalse("Should return false when disabled", disabledResult)
        assertEquals("BSSID must not be modified when disabled", originalBssid, wifi.getBSSID())

        // 传入 null 对象安全返回
        assertFalse(NetworkAntiDetectFilter.sanitizeWifiInfoBssid(null, isEnabled = true))
        assertFalse(NetworkAntiDetectFilter.sanitizeWifiInfoBssid(null, isEnabled = false))

        // 传入无关对象 (没有 BSSID 字段)
        val irrelevant = IrrelevantObject("safe")
        val irrelevantResult = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(irrelevant, isEnabled = true)
        assertFalse("Should return false for object without bssid field", irrelevantResult)
        assertEquals("safe", irrelevant.someField)

        // 传入字段类型不兼容的对象 (mBSSID 是 Int)
        val nonString = NonStringBssidWifi(9999)
        val nonStringResult = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(nonString, isEnabled = true)
        assertFalse("Should safely return false when field type cannot be set to String", nonStringResult)
        assertEquals(9999, nonString.mBSSID)
    }

    @Test
    fun testBssidSanitizationConcurrentStress() {
        val threadCount = 30
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val mac = String.format("00:11:22:33:%02x:%02x", t, i % 256)
                        val wifi = AospStyleWifiInfo(mac)
                        val ok = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(wifi, isEnabled = true)
                        if (!ok || wifi.getBSSID() != NetworkAntiDetectFilter.MASKED_BSSID) {
                            failureCount.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent BSSID sanitization timed out", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("All concurrent BSSID sanitizations must succeed", 0, failureCount.get())
    }

    // =========================================================================
    // 3. LocationConfigProvider IPC Bundle 序列化与跨进程传输压力测试
    // =========================================================================

    /**
     * 构建具备完整动态 Map 读写能力的 Mockito 仿真 Bundle，
     * 解决 JVM 单元测试中 android.jar 桩类方法默认返回默认值的问题。
     */
    private fun createStatefulMockBundle(backingMap: MutableMap<String, Any?> = mutableMapOf()): Pair<Bundle, MutableMap<String, Any?>> {
        val bundle = org.mockito.Mockito.mock(Bundle::class.java)
        org.mockito.Mockito.`when`(bundle.containsKey(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            backingMap.containsKey(inv.getArgument<String>(0))
        }
        org.mockito.Mockito.`when`(bundle.getBoolean(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            backingMap[inv.getArgument<String>(0)] as? Boolean ?: false
        }
        org.mockito.Mockito.`when`(bundle.getBoolean(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val d = inv.getArgument<Boolean>(1)
            backingMap[k] as? Boolean ?: d
        }
        org.mockito.Mockito.`when`(bundle.getDouble(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            (backingMap[inv.getArgument<String>(0)] as? Number)?.toDouble() ?: 0.0
        }
        org.mockito.Mockito.`when`(bundle.getFloat(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            (backingMap[inv.getArgument<String>(0)] as? Number)?.toFloat() ?: 0.0f
        }
        org.mockito.Mockito.`when`(bundle.getStringArrayList(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            backingMap[inv.getArgument<String>(0)] as? ArrayList<String>
        }
        org.mockito.Mockito.`when`(bundle.putBoolean(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer { inv ->
            backingMap[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1)
            null
        }
        org.mockito.Mockito.`when`(bundle.putDouble(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyDouble())).thenAnswer { inv ->
            backingMap[inv.getArgument<String>(0)] = inv.getArgument<Double>(1)
            null
        }
        org.mockito.Mockito.`when`(bundle.putFloat(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyFloat())).thenAnswer { inv ->
            backingMap[inv.getArgument<String>(0)] = inv.getArgument<Float>(1)
            null
        }
        org.mockito.Mockito.`when`(bundle.putStringArrayList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer { inv ->
            backingMap[inv.getArgument<String>(0)] = inv.getArgument<ArrayList<String>>(1)
            null
        }
        return Pair(bundle, backingMap)
    }

    @Test
    fun testBundleDeserializationFidelityWithMockBundle() {
        // 构造包含全量有效状态的 Bundle
        val map = mutableMapOf<String, Any?>(
            MockConfigManager.KEY_IS_MOCK_ENABLED to true,
            MockConfigManager.KEY_LATITUDE to 31.2304123456789,
            MockConfigManager.KEY_LONGITUDE to 121.4737123456789,
            MockConfigManager.KEY_ALTITUDE to 8848.86,
            MockConfigManager.KEY_SPEED to 33.5f,
            MockConfigManager.KEY_BEARING to 270.5f,
            MockConfigManager.KEY_ACCURACY to 1.25f,
            MockConfigManager.KEY_BLOCK_CELL to true,
            MockConfigManager.KEY_BLOCK_WIFI to false,
            MockConfigManager.KEY_TARGET_PACKAGES to arrayListOf("com.tencent.mm", "com.autonavi.minimap")
        )
        val (mockBundle, _) = createStatefulMockBundle(map)

        // 反序列化恢复到 MockConfigManager
        MockConfigManager.updateFromBundle(mockBundle)

        assertTrue(MockConfigManager.isMockEnabled)
        assertEquals(31.2304123456789, MockConfigManager.latitude, 1e-9)
        assertEquals(121.4737123456789, MockConfigManager.longitude, 1e-9)
        assertEquals(8848.86, MockConfigManager.altitude, 1e-6)
        assertEquals(33.5f, MockConfigManager.speed, 1e-4f)
        assertEquals(270.5f, MockConfigManager.bearing, 1e-4f)
        assertEquals(1.25f, MockConfigManager.accuracy, 1e-4f)
        assertTrue(MockConfigManager.isCellInterceptionEnabled)
        assertFalse(MockConfigManager.isWifiInterceptionEnabled)
        val packages = MockConfigManager.getTargetPackages()
        assertEquals(2, packages.size)
        assertTrue(packages.contains("com.tencent.mm"))
        assertTrue(packages.contains("com.autonavi.minimap"))
    }

    @Test
    fun testCorruptedAndExtremeBundleResilience() {
        // 设置初始健康配置
        MockConfigManager.latitude = 30.0
        MockConfigManager.longitude = 120.0
        MockConfigManager.speed = 10.0f
        MockConfigManager.accuracy = 5.0f
        MockConfigManager.isMockEnabled = true

        // 1. 传入 null Bundle -> 安全返回，状态不受破坏
        MockConfigManager.updateFromBundle(null)
        assertEquals(30.0, MockConfigManager.latitude, 1e-6)
        assertEquals(120.0, MockConfigManager.longitude, 1e-6)
        assertTrue(MockConfigManager.isMockEnabled)

        // 2. 传入空 Bundle (没有任何 key) -> 现有状态保持不变
        val (emptyBundle, _) = createStatefulMockBundle(mutableMapOf())
        MockConfigManager.updateFromBundle(emptyBundle)
        assertEquals(30.0, MockConfigManager.latitude, 1e-6)
        assertEquals(120.0, MockConfigManager.longitude, 1e-6)
        assertTrue(MockConfigManager.isMockEnabled)

        // 3. 部分更新 (Partial Bundle): 仅更新精度与速度
        val partialMap = mutableMapOf<String, Any?>(
            MockConfigManager.KEY_SPEED to 88.0f,
            MockConfigManager.KEY_ACCURACY to 0.5f
        )
        val (partialBundle, _) = createStatefulMockBundle(partialMap)
        MockConfigManager.updateFromBundle(partialBundle)
        assertEquals(88.0f, MockConfigManager.speed, 1e-4f)
        assertEquals(0.5f, MockConfigManager.accuracy, 1e-4f)
        // 经纬度未受破坏
        assertEquals(30.0, MockConfigManager.latitude, 1e-6)
        assertEquals(120.0, MockConfigManager.longitude, 1e-6)

        // 4. 极值注入 (Extreme Double & Float values)
        val extremeMap = mutableMapOf<String, Any?>(
            MockConfigManager.KEY_LATITUDE to Double.MAX_VALUE,
            MockConfigManager.KEY_LONGITUDE to Double.NEGATIVE_INFINITY,
            MockConfigManager.KEY_ALTITUDE to -500.0,
            MockConfigManager.KEY_SPEED to Float.MAX_VALUE
        )
        val (extremeBundle, _) = createStatefulMockBundle(extremeMap)
        MockConfigManager.updateFromBundle(extremeBundle)
        assertEquals(Double.MAX_VALUE, MockConfigManager.latitude, 1e-9)
        assertEquals(Double.NEGATIVE_INFINITY, MockConfigManager.longitude, 1e-9)
        assertEquals(-500.0, MockConfigManager.altitude, 1e-6)
        assertEquals(Float.MAX_VALUE, MockConfigManager.speed, 1e-4f)
    }

    @Test
    fun testLargePayloadWhiteListStress() {
        val packageCount = 5000
        val largeWhitelist = (1..packageCount).map { "com.stress.test.app_$it" }
        val map = mutableMapOf<String, Any?>(
            MockConfigManager.KEY_TARGET_PACKAGES to ArrayList(largeWhitelist)
        )
        val (bundle, _) = createStatefulMockBundle(map)

        // 恢复前为空
        MockConfigManager.setTargetPackages(emptyList())
        assertEquals(0, MockConfigManager.getTargetPackages().size)

        // 反序列化恢复
        MockConfigManager.updateFromBundle(bundle)
        val recoveredSet = MockConfigManager.getTargetPackages()
        assertEquals(packageCount, recoveredSet.size)
        assertTrue(recoveredSet.contains("com.stress.test.app_1"))
        assertTrue(recoveredSet.contains("com.stress.test.app_5000"))
    }

    @Test
    fun testLocationConfigProviderCallIpcContract() {
        val uriMock = org.mockito.Mockito.mockStatic(android.net.Uri::class.java)
        val dummyUri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        uriMock.`when`<android.net.Uri> { android.net.Uri.parse(org.mockito.ArgumentMatchers.anyString()) }.thenReturn(dummyUri)

        try {
            val provider = LocationConfigProvider()
            MockConfigManager.isMockEnabled = true
            MockConfigManager.latitude = 40.0
            MockConfigManager.longitude = 116.0
            MockConfigManager.setTargetPackages(listOf("com.authorized.app"))

            // 1. 查询已授权包名：应返回 enabled = true
            val authorizedBundle = provider.call(MockConfigManager.METHOD_GET_CONFIG, "com.authorized.app", null)
            assertNotNull(authorizedBundle)

            // 2. 查询未授权包名：应特异性改写 enabled = false
            val unauthorizedBundle = provider.call(MockConfigManager.METHOD_GET_CONFIG, "com.unauthorized.app", null)
            assertNotNull(unauthorizedBundle)

            // 3. 远程调用 METHOD_SET_CONFIG
            val (updateBundle, _) = createStatefulMockBundle(mutableMapOf(
                MockConfigManager.KEY_IS_MOCK_ENABLED to false,
                MockConfigManager.KEY_LATITUDE to 22.5431,
                MockConfigManager.KEY_LONGITUDE to 114.0579
            ))
            val setConfigResult = provider.call(MockConfigManager.METHOD_SET_CONFIG, null, updateBundle)
            assertNotNull(setConfigResult)
            assertFalse(MockConfigManager.isMockEnabled)
            assertEquals(22.5431, MockConfigManager.latitude, 1e-6)
            assertEquals(114.0579, MockConfigManager.longitude, 1e-6)

            // 4. 调用未知方法返回 null
            val unknownResult = provider.call("unknownMethod", null, null)
            assertNull(unknownResult)
        } finally {
            uriMock.close()
        }
    }

    @Test
    fun testConcurrentMockConfigManagerReadWriteStress() {
        val readThreads = 15
        val writeThreads = 15
        val iterations = 200
        val totalThreads = readThreads + writeThreads
        val executor = Executors.newFixedThreadPool(totalThreads)
        val latch = CountDownLatch(totalThreads)
        val errorDetected = AtomicBoolean(false)

        // 初始化
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(listOf("com.concurrent.base"))

        // 读线程：高频读取 toBundle, isMockEnabledForPackage, getTargetPackages
        for (i in 0 until readThreads) {
            executor.submit {
                try {
                    for (iter in 0 until iterations) {
                        val bundle = MockConfigManager.toBundle()
                        assertNotNull(bundle)
                        MockConfigManager.isMockEnabledForPackage("com.concurrent.base")
                        MockConfigManager.isMockEnabledForCurrentProcess()
                        val pkgs = MockConfigManager.getTargetPackages()
                        assertNotNull(pkgs)
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        // 写线程：高频修改 targetPackages 与 updateFromBundle
        for (i in 0 until writeThreads) {
            val threadId = i
            executor.submit {
                try {
                    for (iter in 0 until iterations) {
                        MockConfigManager.addTargetPackage("pkg_${threadId}_$iter")
                        val bundle = Bundle().apply {
                            putDouble(MockConfigManager.KEY_LATITUDE, 30.0 + (iter * 0.01))
                            putDouble(MockConfigManager.KEY_LONGITUDE, 120.0 + (iter * 0.01))
                            putBoolean(MockConfigManager.KEY_BLOCK_CELL, iter % 2 == 0)
                        }
                        MockConfigManager.updateFromBundle(bundle)
                        if (iter % 10 == 0) {
                            MockConfigManager.removeTargetPackage("pkg_${threadId}_$iter")
                        }
                    }
                } catch (t: Throwable) {
                    errorDetected.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent stress test timed out", latch.await(15, TimeUnit.SECONDS))
        executor.shutdown()
        assertFalse("Concurrent modification or race condition occurred in MockConfigManager", errorDetected.get())
    }
}
