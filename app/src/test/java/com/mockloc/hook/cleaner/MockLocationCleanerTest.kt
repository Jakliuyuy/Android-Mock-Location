package com.mockloc.hook.cleaner

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * 带有 AOSP 核心字段模拟的测试用 Location 类。
 * 在 JVM 宿主单元测试环境下模拟 Android 系统 Location 的内存布局与行为。
 */
open class TestableMockLocation(provider: String = LocationManager.GPS_PROVIDER) : Location(provider) {
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var alt: Double = 0.0
    private var spd: Float = 0.0f
    private var brg: Float = 0.0f
    private var acc: Float = 0.0f
    private var t: Long = 0L
    private var elapsedNanos: Long = 0L
    private var extrasBundle: Bundle? = null

    // 真实模拟 AOSP Location 类的内部私有标志字段
    var mFieldsMask: Int = MockLocationCleaner.HAS_MOCK_PROVIDER_MASK // 默认设置为 16 (模拟开启)
    var mIsFromMockProvider: Boolean = true

    override fun setLatitude(latitude: Double) { this.lat = latitude }
    override fun getLatitude(): Double = lat

    override fun setLongitude(longitude: Double) { this.lng = longitude }
    override fun getLongitude(): Double = lng

    override fun setAltitude(altitude: Double) { this.alt = altitude }
    override fun getAltitude(): Double = alt

    override fun setSpeed(speed: Float) { this.spd = speed }
    override fun getSpeed(): Float = spd

    override fun setBearing(bearing: Float) { this.brg = bearing }
    override fun getBearing(): Float = brg

    override fun setAccuracy(accuracy: Float) { this.acc = accuracy }
    override fun getAccuracy(): Float = acc

    override fun setTime(time: Long) { this.t = time }
    override fun getTime(): Long = t

    override fun setElapsedRealtimeNanos(time: Long) { this.elapsedNanos = time }
    override fun getElapsedRealtimeNanos(): Long = elapsedNanos

    override fun setExtras(extras: Bundle?) { this.extrasBundle = extras }
    override fun getExtras(): Bundle? = extrasBundle

    @Suppress("DEPRECATION")
    override fun isFromMockProvider(): Boolean = (mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0 || mIsFromMockProvider

    override fun isMock(): Boolean = (mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK) != 0

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
 * MockLocationCleaner 核心三重清洗机制单元测试。
 */
class MockLocationCleanerTest {

    private val delta = 0.000001
    private val floatDelta = 0.01f

    @Test
    fun testSanitizeFullAttributesAndCoordinates() {
        val location = TestableMockLocation(LocationManager.GPS_PROVIDER)
        val targetLat = 39.908722
        val targetLng = 116.397496
        val targetAlt = 52.3
        val targetSpeed = 4.2f
        val targetBearing = 135.5f
        val targetAccuracy = 2.8f

        val beforeTime = System.currentTimeMillis()
        MockLocationCleaner.sanitize(
            location = location,
            lat = targetLat,
            lng = targetLng,
            alt = targetAlt,
            speed = targetSpeed,
            bearing = targetBearing,
            accuracy = targetAccuracy
        )
        val afterTime = System.currentTimeMillis()

        // 验证坐标与物理属性设置准确
        assertEquals(targetLat, location.latitude, delta)
        assertEquals(targetLng, location.longitude, delta)
        assertEquals(targetAlt, location.altitude, delta)
        assertEquals(targetSpeed, location.speed, floatDelta)
        assertEquals(targetBearing, location.bearing, floatDelta)
        assertEquals(targetAccuracy, location.accuracy, floatDelta)
        assertTrue(location.time in beforeTime..afterTime)
        assertTrue(location.elapsedRealtimeNanos > 0)
    }

    @Test
    fun testMockFlagStrippedViaMethodAndReflection() {
        val location = TestableMockLocation(LocationManager.GPS_PROVIDER)

        // 验证清洗前：处于 Mock 状态，mFieldsMask 含 bit 4 (16)
        assertEquals(16, location.mFieldsMask and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)
        @Suppress("DEPRECATION")
        assertTrue(location.isFromMockProvider())
        assertTrue(location.isMock())

        // 执行清洗
        MockLocationCleaner.sanitize(location, 31.2304, 121.4737)

        // 1. 断言 isFromMockProvider() 变为 false
        @Suppress("DEPRECATION")
        assertFalse("isFromMockProvider must be false after sanitize", location.isFromMockProvider())
        assertFalse("isMock must be false after sanitize", location.isMock())

        // 2. 通过反射断言 (mFieldsMask & 16) == 0
        val maskField = location.javaClass.getDeclaredField("mFieldsMask")
        maskField.isAccessible = true
        val maskValue = maskField.getInt(location)
        assertEquals("mFieldsMask bit 4 must be stripped", 0, maskValue and MockLocationCleaner.HAS_MOCK_PROVIDER_MASK)

        // 3. 验证旧版布尔标志位
        val legacyField = location.javaClass.getDeclaredField("mIsFromMockProvider")
        legacyField.isAccessible = true
        assertFalse("mIsFromMockProvider must be false", legacyField.getBoolean(location))
    }

    @Test
    fun testExtrasScrubbed() {
        val location = TestableMockLocation()
        val bundle = Mockito.mock(Bundle::class.java)
        val map = mutableMapOf<String, Any?>(
            "mockLocation" to true,
            "mock" to "true",
            "isFromMockProvider" to true,
            "legitimateKey" to "legitimateValue"
        )

        Mockito.`when`(bundle.remove(Mockito.anyString())).thenAnswer { invocation ->
            map.remove(invocation.getArgument(0) as String)
        }
        Mockito.`when`(bundle.containsKey(Mockito.anyString())).thenAnswer { invocation ->
            map.containsKey(invocation.getArgument(0) as String)
        }
        location.extras = bundle

        MockLocationCleaner.sanitize(location, 22.5431, 114.0579)

        // 验证所有的 mock 标记均已从 Bundle 移除
        Mockito.verify(bundle).remove("mockLocation")
        Mockito.verify(bundle).remove("mock")
        Mockito.verify(bundle).remove("isFromMockProvider")
        assertFalse("mockLocation must be removed from extras", map.containsKey("mockLocation"))
        assertFalse("mock must be removed from extras", map.containsKey("mock"))
        assertFalse("isFromMockProvider must be removed from extras", map.containsKey("isFromMockProvider"))
        assertTrue("legitimateKey must be retained", map.containsKey("legitimateKey"))
    }

    @Test
    fun testDefaultAccuracyFallback() {
        val location = TestableMockLocation()
        // 传入非法的负数或 0 精度
        MockLocationCleaner.sanitize(location, 39.9, 116.4, accuracy = 0.0f)
        assertEquals(3.0f, location.accuracy, floatDelta)

        MockLocationCleaner.sanitize(location, 39.9, 116.4, accuracy = -5.0f)
        assertEquals(3.0f, location.accuracy, floatDelta)
    }

    @Test
    fun testNullLocationSafe() {
        // 传入 null 不发生空指针异常
        MockLocationCleaner.sanitize(null, 39.9, 116.4)
    }

    @Test
    fun testDirectStandardLocationInstance() {
        // 直接使用标准 Location 对象，验证执行无异常
        val standardLoc = Location(LocationManager.GPS_PROVIDER)
        MockLocationCleaner.sanitize(standardLoc, 39.9042, 116.4074)
        assertNotNull(standardLoc)
    }
}
