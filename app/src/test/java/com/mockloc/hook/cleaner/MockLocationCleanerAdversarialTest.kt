package com.mockloc.hook.cleaner

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 带有全套 AOSP 核心字段与状态模拟的高级对抗测试 Location 类。
 */
open class BaseAdversarialLocation(provider: String = LocationManager.GPS_PROVIDER) : Location(provider) {
    var latVal: Double = 0.0
    var lngVal: Double = 0.0
    var altVal: Double = 0.0
    var spdVal: Float = 0.0f
    var brgVal: Float = 0.0f
    var accVal: Float = 0.0f
    var timeVal: Long = 0L
    var elapsedNanosVal: Long = 0L
    var extrasBundleVal: Bundle? = null

    // AOSP Location 核心标志位与旧版标志位
    var mFieldsMask: Int = MockLocationCleaner.HAS_MOCK_PROVIDER_MASK // 默认设置 bit 4 = 16
    var mIsFromMockProvider: Boolean = true

    override fun setLatitude(latitude: Double) { this.latVal = latitude }
    override fun getLatitude(): Double = latVal

    override fun setLongitude(longitude: Double) { this.lngVal = longitude }
    override fun getLongitude(): Double = lngVal

    override fun setAltitude(altitude: Double) { this.altVal = altitude }
    override fun getAltitude(): Double = altVal

    override fun setSpeed(speed: Float) { this.spdVal = speed }
    override fun getSpeed(): Float = spdVal

    override fun setBearing(bearing: Float) { this.brgVal = bearing }
    override fun getBearing(): Float = brgVal

    override fun setAccuracy(accuracy: Float) { this.accVal = accuracy }
    override fun getAccuracy(): Float = accVal

    override fun setTime(time: Long) { this.timeVal = time }
    override fun getTime(): Long = timeVal

    override fun setElapsedRealtimeNanos(time: Long) { this.elapsedNanosVal = time }
    override fun getElapsedRealtimeNanos(): Long = elapsedNanosVal

    override fun setExtras(extras: Bundle?) { this.extrasBundleVal = extras }
    override fun getExtras(): Bundle? = extrasBundleVal

    @Suppress("DEPRECATION")
    override fun isFromMockProvider(): Boolean =
        (mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0 || mIsFromMockProvider

    override fun isMock(): Boolean =
        (mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0

    override fun setMock(isMock: Boolean) {
        if (isMock) {
            mFieldsMask = mFieldsMask or MockLocationCleaner.HAS_MOCK_PROVIDER_MASK
        } else {
            mFieldsMask = mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK.inv()
        }
        mIsFromMockProvider = isMock
    }
}

/**
 * 具有深层继承结构的子类，测试字段溯源反射
 */
open class MidLevelLocation : BaseAdversarialLocation()
class DeepInheritanceLocation : MidLevelLocation()

/**
 * 模拟特定定制 ROM 中 mFieldsMask 为 Byte 类型的场景
 */
class ByteMaskLocation : Location(LocationManager.GPS_PROVIDER) {
    var mFieldsMask: Byte = 16.toByte()
    var mIsFromMockProvider: Boolean = true
}

/**
 * 完全不包含 mFieldsMask 的纯轻量级 Location，测试平稳降级
 */
class BareLocation : Location(LocationManager.GPS_PROVIDER)

/**
 * MockLocationCleaner 对抗性严苛压力测试集 (Empirical Challenger Harness)
 */
class MockLocationCleanerAdversarialTest {

    private val delta = 0.000001
    private val floatDelta = 0.01f

    // =========================================================================
    // 维度 1: 极端与边缘坐标抗压测试 (Extreme & Boundary Coordinates)
    // =========================================================================

    @Test
    fun testExtremeCoordinates_BoundariesAndEdgeValues() {
        val testCoords = listOf(
            Pair(90.0, 0.0),                      // 北极极点
            Pair(-90.0, 0.0),                     // 南极极点
            Pair(0.0, 180.0),                     // 国际日期变更线
            Pair(0.0, -180.0),                    // 负向经度极值
            Pair(0.0, 0.0),                       // 本初子午线与赤道交点
            Pair(90.0, 180.0),                    // 东北对角极点
            Pair(-90.0, -180.0),                  // 西南对角极点
            Pair(90.000000001, 180.000000001),    // 微弱溢出边界
            Pair(-90.000000001, -180.000000001),  // 微弱负溢出边界
            Pair(1000.0, 2000.0),                 // 严重超界值
            Pair(-9999.0, -8888.0),               // 严重负超界值
            Pair(Double.MIN_VALUE, Double.MAX_VALUE), // 浮点极值
            Pair(-Double.MAX_VALUE, -Double.MIN_VALUE),
            Pair(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY), // 无穷大
            Pair(Double.NaN, Double.NaN)          // NaN
        )

        for ((lat, lng) in testCoords) {
            val loc = BaseAdversarialLocation()
            loc.mFieldsMask = 16
            loc.mIsFromMockProvider = true

            // 执行清洗
            MockLocationCleaner.sanitize(loc, lat, lng)

            // 断言脱敏成功，位标志绝对清零
            assertEquals("mFieldsMask bit 4 must be stripped for lat=$lat, lng=$lng",
                0, loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
            assertFalse("isFromMockProvider must be false for lat=$lat, lng=$lng", loc.isFromMockProvider)
            assertFalse("isMock must be false for lat=$lat, lng=$lng", loc.isMock)
            assertEquals("Latitude must match input", lat, loc.latitude, delta)
            assertEquals("Longitude must match input", lng, loc.longitude, delta)
        }
    }

    @Test
    fun testExtremeAltitudeSpeedBearingAndAccuracy() {
        val loc = BaseAdversarialLocation()

        // 1. 负数精度和 0 精度自动回退到 3.0f
        MockLocationCleaner.sanitize(loc, 39.9, 116.4, accuracy = 0.0f)
        assertEquals(3.0f, loc.accuracy, floatDelta)

        MockLocationCleaner.sanitize(loc, 39.9, 116.4, accuracy = -100.0f)
        assertEquals(3.0f, loc.accuracy, floatDelta)

        MockLocationCleaner.sanitize(loc, 39.9, 116.4, accuracy = -0.0001f)
        assertEquals(3.0f, loc.accuracy, floatDelta)

        // 2. 合法正数精度保持原值
        MockLocationCleaner.sanitize(loc, 39.9, 116.4, accuracy = 0.5f)
        assertEquals(0.5f, loc.accuracy, floatDelta)

        MockLocationCleaner.sanitize(loc, 39.9, 116.4, accuracy = 150.0f)
        assertEquals(150.0f, loc.accuracy, floatDelta)

        // 3. 极端海拔、速度与方位角
        MockLocationCleaner.sanitize(
            location = loc,
            lat = 31.23,
            lng = 121.47,
            alt = -11034.0, // 马里亚纳海沟
            speed = 340.0f, // 音速
            bearing = 359.9f,
            accuracy = 1.0f
        )
        assertEquals(-11034.0, loc.altitude, delta)
        assertEquals(340.0f, loc.speed, floatDelta)
        assertEquals(359.9f, loc.bearing, floatDelta)
        assertEquals(0, loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
    }

    // =========================================================================
    // 维度 2: 重复调用与状态幂等性压力测试 (Repeated Calls & State Idempotency)
    // =========================================================================

    @Test
    fun testRepeatedSanitizeOnSameInstance_10000Cycles() {
        val loc = BaseAdversarialLocation()

        for (cycle in 1..10_000) {
            // 每轮模拟恶意或系统层重新打上 Mock 标记
            loc.mFieldsMask = loc.mFieldsMask or MockLocationCleaner.HAS_MOCK_PROVIDER_MASK
            loc.mIsFromMockProvider = true

            MockLocationCleaner.sanitize(loc, 22.54 + (cycle % 100) * 0.001, 114.05)

            // 断言每一次调用都能彻底抹除标志
            if ((loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0) {
                fail("Mock flag leaked on cycle $cycle! mFieldsMask=${loc.mFieldsMask}")
            }
            if (loc.isFromMockProvider) {
                fail("isFromMockProvider true on cycle $cycle")
            }
            if (loc.isMock) {
                fail("isMock true on cycle $cycle")
            }
        }
    }

    @Test
    fun testMaskPreservation_OnlyBit4Cleared() {
        val loc = BaseAdversarialLocation()
        // 构造掩码：全部 8 个常用标志位均置位 (0b11111111 = 255)
        // bit 0: altitude (1)
        // bit 1: speed (2)
        // bit 2: bearing (4)
        // bit 3: accuracy (8)
        // bit 4: mock provider (16)
        // bit 5: vertical accuracy (32)
        // bit 6: speed accuracy (64)
        // bit 7: bearing accuracy (128)
        loc.mFieldsMask = 255

        MockLocationCleaner.sanitize(loc, 30.0, 120.0)

        // 断言：bit 4 (16) 被剥除，而其余所有标志位 (255 - 16 = 239) 必须精确保留！
        val expectedMask = 255 and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK.inv()
        assertEquals("Only bit 4 should be cleared, all other bits preserved", expectedMask, loc.mFieldsMask)
        assertEquals("Remaining mask must equal 239", 239, loc.mFieldsMask)
    }

    @Test
    fun testMaskPreservation_InitialZeroRemainsZero() {
        val loc = BaseAdversarialLocation()
        loc.mFieldsMask = 0
        loc.mIsFromMockProvider = false

        MockLocationCleaner.sanitize(loc, 30.0, 120.0)

        assertEquals("Zero mask should remain zero", 0, loc.mFieldsMask)
        assertFalse(loc.isFromMockProvider)
        assertFalse(loc.isMock)
    }

    // =========================================================================
    // 维度 3: 多线程并发安全与竞争测试 (Multithreading & Concurrency Stress)
    // =========================================================================

    @Test
    fun testConcurrentSanitize_SharedLocation_RaceCondition() {
        val loc = BaseAdversarialLocation()
        val threadCount = 16
        val iterationsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (j in 0 until iterationsPerThread) {
                        MockLocationCleaner.sanitize(
                            loc,
                            39.0 + (j % 50) * 0.01,
                            116.0 + (j % 50) * 0.01
                        )
                        // 校验位图
                        if ((loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0) {
                            failureCount.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Concurrency test timed out", doneLatch.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Zero failures allowed in shared location concurrent sanitize", 0, failureCount.get())
        assertEquals(0, loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
        assertFalse(loc.isFromMockProvider)
    }

    @Test
    fun testConcurrentSanitize_HighThroughput_DistinctInstances() {
        val threadCount = 20
        val instancesPerThread = 500
        val totalInstances = threadCount * instancesPerThread // 10,000 instances
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until instancesPerThread) {
                        val loc = BaseAdversarialLocation()
                        loc.mFieldsMask = 16
                        loc.mIsFromMockProvider = true

                        MockLocationCleaner.sanitize(loc, 23.0 + i * 0.0001, 113.0 + i * 0.0001)

                        if ((loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0 ||
                            loc.isFromMockProvider ||
                            loc.isMock) {
                            failureCount.incrementAndGet()
                        }
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("High throughput test timed out", doneLatch.await(15, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("All $totalInstances instances must be cleanly stripped with 0 failures", 0, failureCount.get())
    }

    @Test
    fun testConcurrentInterleaved_MockReinjectionAndSanitize() {
        val loc = BaseAdversarialLocation()
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val errorDetected = java.util.concurrent.atomic.AtomicBoolean(false)

        // 注入线程：持续尝试设置 Mock
        val injectorThread = Thread {
            while (active.get()) {
                loc.mFieldsMask = loc.mFieldsMask or 16
                loc.mIsFromMockProvider = true
            }
        }

        // 清洗线程：持续清洗
        val cleanerThread = Thread {
            var counter = 0
            while (active.get() && counter < 5000) {
                MockLocationCleaner.sanitize(loc, 31.0, 121.0)
                counter++
            }
        }

        injectorThread.start()
        cleanerThread.start()

        cleanerThread.join(3000)
        active.set(false)
        injectorThread.join(2000)

        // 停止竞争后，最后执行一次确定性清洗
        MockLocationCleaner.sanitize(loc, 31.0, 121.0)

        assertEquals("Final state must be completely stripped of mock flag",
            0, loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
        assertFalse(loc.isFromMockProvider)
        assertFalse(errorDetected.get())
    }

    // =========================================================================
    // 维度 4: 恶意 Extras 注入与异常防御测试 (Malicious Extras & Edge Values)
    // =========================================================================

    @Test
    fun testMaliciousExtras_DiverseKeysAndValues() {
        val loc = BaseAdversarialLocation()
        val bundle = Mockito.mock(Bundle::class.java)

        val bundleStorage = mutableMapOf<String, Any?>(
            // 标准 mock 标志（布尔、字符串、数值、空值）
            "mockLocation" to true,
            "mock" to "true",
            "isFromMockProvider" to 1,
            // 其它业务字段（应当受保护）
            "satellites" to 14,
            "source" to "fused",
            "level" to "high_accuracy"
        )

        Mockito.`when`(bundle.remove(Mockito.anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument(0) as String
            bundleStorage.remove(key)
        }
        Mockito.`when`(bundle.containsKey(Mockito.anyString())).thenAnswer { invocation ->
            bundleStorage.containsKey(invocation.getArgument(0) as String)
        }

        loc.extras = bundle
        MockLocationCleaner.sanitize(loc, 39.9, 116.4)

        // 断言三大 mock 键被彻底清除
        assertFalse("mockLocation must be scrubbed", bundleStorage.containsKey("mockLocation"))
        assertFalse("mock must be scrubbed", bundleStorage.containsKey("mock"))
        assertFalse("isFromMockProvider must be scrubbed", bundleStorage.containsKey("isFromMockProvider"))

        // 断言合法业务字段完好保留
        assertTrue("satellites must be preserved", bundleStorage.containsKey("satellites"))
        assertTrue("source must be preserved", bundleStorage.containsKey("source"))
        assertTrue("level must be preserved", bundleStorage.containsKey("level"))
    }

    @Test
    fun testMaliciousExtras_UnmodifiableOrThrowingBundle_DoesNotCrash() {
        val loc = BaseAdversarialLocation()
        val maliciousBundle = Mockito.mock(Bundle::class.java)

        // 模拟恶意或不可变 Bundle，调用 remove 时抛出各种异常
        Mockito.`when`(maliciousBundle.remove(Mockito.anyString())).thenThrow(
            UnsupportedOperationException("Immutable Bundle injected by hardened target app")
        )

        loc.extras = maliciousBundle

        // 验证 sanitize 具备健壮的异常防御，绝不导致主流程崩溃
        try {
            MockLocationCleaner.sanitize(loc, 39.9, 116.4)
        } catch (t: Throwable) {
            fail("MockLocationCleaner.sanitize crashed on throwing bundle: ${t.message}")
        }
    }

    @Test
    fun testNullExtrasAndNullLocation() {
        val loc = BaseAdversarialLocation()
        loc.extras = null

        // 验证 extras 为 null 时执行顺畅
        MockLocationCleaner.sanitize(loc, 39.9, 116.4)
        assertEquals(0, loc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)

        // 验证 Location 为 null 时完全空指针安全
        MockLocationCleaner.sanitize(null, 39.9, 116.4)
    }

    // =========================================================================
    // 维度 5: 深层继承与类型多态反射测试 (Polymorphism & Class Hierarchy)
    // =========================================================================

    @Test
    fun testDeepInheritanceHierarchyReflection() {
        // DeepInheritanceLocation -> MidLevelLocation -> BaseAdversarialLocation -> Location
        val deepLoc = DeepInheritanceLocation()
        deepLoc.mFieldsMask = 16
        deepLoc.mIsFromMockProvider = true

        MockLocationCleaner.sanitize(deepLoc, 34.0522, -118.2437)

        assertEquals("mFieldsMask in deep hierarchy must be stripped",
            0, deepLoc.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
        assertFalse(deepLoc.isFromMockProvider)
        assertFalse(deepLoc.isMock)
    }

    @Test
    fun testByteTypeFieldsMask() {
        val byteLoc = ByteMaskLocation()
        assertEquals(16.toByte(), byteLoc.mFieldsMask)

        MockLocationCleaner.sanitize(byteLoc, 40.7128, -74.0060)

        assertEquals("Byte type mFieldsMask must be stripped",
            0, (byteLoc.mFieldsMask.toInt() and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK))
        assertFalse(byteLoc.mIsFromMockProvider)
    }

    @Test
    fun testBareLocation_NoFieldsGracefulDegradation() {
        // 验证当 Location 实例完全不含 mFieldsMask 与 mIsFromMockProvider 字段时，平稳降级不崩溃
        val bareLoc = BareLocation()
        try {
            MockLocationCleaner.sanitize(bareLoc, 51.5074, -0.1278)
        } catch (t: Throwable) {
            fail("Bare location caused crash: ${t.message}")
        }
    }
}
