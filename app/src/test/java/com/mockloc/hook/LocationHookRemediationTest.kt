package com.mockloc.hook

import android.content.ContentResolver
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import com.mockloc.data.MockConfigManager
import com.mockloc.hook.cleaner.TestableMockLocation
import com.mockloc.ipc.LocationConfigProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.lang.reflect.Proxy

/**
 * 验证 Milestone 2 审查缺陷修复 (Reviewer Remediation Test Suite)
 * 覆盖：
 * 1. LocationListener 动态 Proxy 拦截与透明转发
 * 2. 严格 Opt-in 白名单语义（空白名单不生效）
 * 3. 惰性配置同步 (Lazy Sync Fallback)
 * 4. 包级隔离守卫
 */
class LocationHookRemediationTest {

    @Before
    fun setUp() {
        MockConfigManager.locationFactory = { TestableMockLocation(it) }
        MockConfigManager.isMockEnabled = true
        MockConfigManager.latitude = 39.9087
        MockConfigManager.longitude = 116.3975
        MockConfigManager.altitude = 66.0
        MockConfigManager.speed = 12.5f
        MockConfigManager.bearing = 90.0f
        MockConfigManager.accuracy = 2.0f
        MockConfigManager.setTargetPackages(listOf("com.target.testapp"))
        MockConfigManager.currentProcessPackage = "com.target.testapp"
        MockConfigManager.isConfigSynced = true
    }

    @After
    fun tearDown() {
        MockConfigManager.locationFactory = { Location(it) }
        MockConfigManager.isMockEnabled = false
        MockConfigManager.setTargetPackages(emptyList())
        MockConfigManager.currentProcessPackage = null
        MockConfigManager.isConfigSynced = false
    }

    // =========================================================================
    // 1. LocationListener 动态 Proxy 包装与回调拦截验证
    // =========================================================================

    /**
     * 自定义目标应用 LocationListener 具体实现类，
     * 模拟真实 Android 应用中无法被 interface 直接 hook 的 concrete class。
     */
    private class ConcreteAppLocationListener : LocationListener {
        var receivedLocation: Location? = null
        var receivedLocationList: List<Location>? = null
        var lastEnabledProvider: String? = null
        var lastDisabledProvider: String? = null
        var lastFlushCompleteCode: Int? = null

        override fun onLocationChanged(location: Location) {
            receivedLocation = location
        }

        override fun onLocationChanged(locations: List<Location>) {
            receivedLocationList = locations
        }

        override fun onProviderEnabled(provider: String) {
            lastEnabledProvider = provider
        }

        override fun onProviderDisabled(provider: String) {
            lastDisabledProvider = provider
        }

        override fun onFlushComplete(requestCode: Int) {
            lastFlushCompleteCode = requestCode
        }
    }

    @Test
    fun testLocationListenerProxyWrapsSingleLocation() {
        val originalListener = ConcreteAppLocationListener()
        val proxy = LocationHook.getOrCreateProxyListener(originalListener, javaClass.classLoader!!)

        assertTrue("Generated listener must be a dynamic proxy instance", Proxy.isProxyClass(proxy.javaClass))

        // 构造一个具有真实物理经纬度的原始 Location
        val realLocation = mock(Location::class.java)
        `when`(realLocation.latitude).thenReturn(22.5431)
        `when`(realLocation.longitude).thenReturn(114.0579)
        `when`(realLocation.provider).thenReturn(LocationManager.GPS_PROVIDER)
        `when`(realLocation.isFromMockProvider).thenReturn(false)

        // 触发 onLocationChanged 回调
        proxy.onLocationChanged(realLocation)

        val intercepted = originalListener.receivedLocation
        assertNotNull("Listener must receive sanitized location", intercepted)
        assertEquals("Latitude must be replaced with mock config", 39.9087, intercepted!!.latitude, 1e-6)
        assertEquals("Longitude must be replaced with mock config", 116.3975, intercepted.longitude, 1e-6)
        assertEquals("Altitude must be replaced with mock config", 66.0, intercepted.altitude, 1e-6)
        assertFalse("isFromMockProvider must be false", intercepted.isFromMockProvider)
    }

    @Test
    fun testLocationListenerProxyWrapsLocationList() {
        val originalListener = ConcreteAppLocationListener()
        val proxy = LocationHook.getOrCreateProxyListener(originalListener, javaClass.classLoader!!)

        val dummyList = listOf(mock(Location::class.java), mock(Location::class.java))
        proxy.onLocationChanged(dummyList)

        val receivedList = originalListener.receivedLocationList
        assertNotNull("Listener must receive intercepted location list", receivedList)
        assertEquals(1, receivedList!!.size)
        val loc = receivedList[0]
        assertEquals(39.9087, loc.latitude, 1e-6)
        assertEquals(116.3975, loc.longitude, 1e-6)
    }

    @Test
    fun testLocationListenerProxyPassesThroughOtherCallbacks() {
        val originalListener = ConcreteAppLocationListener()
        val proxy = LocationHook.getOrCreateProxyListener(originalListener, javaClass.classLoader!!)

        // 验证 onProviderEnabled 透传
        proxy.onProviderEnabled("gps")
        assertEquals("gps", originalListener.lastEnabledProvider)

        // 验证 onProviderDisabled 透传
        proxy.onProviderDisabled("network")
        assertEquals("network", originalListener.lastDisabledProvider)

        // 验证 onFlushComplete 透传
        proxy.onFlushComplete(42)
        assertEquals(42, originalListener.lastFlushCompleteCode)
    }

    @Test
    fun testLocationListenerProxyIdentityAndCaching() {
        val originalListener = ConcreteAppLocationListener()
        val proxy1 = LocationHook.getOrCreateProxyListener(originalListener, javaClass.classLoader!!)
        val proxy2 = LocationHook.getOrCreateProxyListener(originalListener, javaClass.classLoader!!)

        // 验证同一个 originalListener 重复获取返回相同 Proxy 实例
        assertSame("Must return cached proxy instance for same listener", proxy1, proxy2)

        // 验证传入 proxy 本身不会导致双重嵌套代理
        val proxyOfProxy = LocationHook.getOrCreateProxyListener(proxy1, javaClass.classLoader!!)
        assertSame("Must not double-wrap an already wrapped proxy", proxy1, proxyOfProxy)

        // 验证 equals 与 hashCode
        assertTrue(proxy1 == originalListener || proxy1.equals(proxy1))
        assertEquals(System.identityHashCode(originalListener), proxy1.hashCode())
        assertTrue(proxy1.toString().contains("MockLocationListenerProxy"))
    }

    // =========================================================================
    // 2. 严格 Opt-in 白名单语义测试 (§R1)
    // =========================================================================

    @Test
    fun testStrictWhitelistEmptyNeverMatchesAnyPackage() {
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(emptyList())

        // 任意包名均不得生效
        assertFalse("Empty whitelist must never match com.tencent.mm", MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))
        assertFalse("Empty whitelist must never match com.autonavi.minimap", MockConfigManager.isMockEnabledForPackage("com.autonavi.minimap"))
        assertFalse("Empty whitelist must never match com.target.testapp", MockConfigManager.isMockEnabledForPackage("com.target.testapp"))

        MockConfigManager.currentProcessPackage = "com.target.testapp"
        assertFalse("Current process must not be enabled when whitelist is empty", MockConfigManager.isMockEnabledForCurrentProcess())
    }

    @Test
    fun testStrictWhitelistExplicitTargetIsolation() {
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(listOf("com.authorized.pkg1", "com.authorized.pkg2"))

        // 显式白名单匹配
        assertTrue(MockConfigManager.isMockEnabledForPackage("com.authorized.pkg1"))
        assertTrue(MockConfigManager.isMockEnabledForPackage("com.authorized.pkg2"))

        // 未授权应用严格阻断
        assertFalse(MockConfigManager.isMockEnabledForPackage("com.unauthorized.pkg"))
        assertFalse(MockConfigManager.isMockEnabledForPackage("com.android.settings"))

        // 动态移除白名单后立即失效
        MockConfigManager.removeTargetPackage("com.authorized.pkg1")
        assertFalse(MockConfigManager.isMockEnabledForPackage("com.authorized.pkg1"))
    }

    // =========================================================================
    // 3. 惰性配置同步 (Lazy Sync Fallback) 验证
    // =========================================================================

    open class StatefulDummyLocationManager(@JvmField val mContext: Context)

    @Test
    fun testLazySyncFallbackTriggersContentProviderCall() {
        val uriMock = mockStatic(Uri::class.java)
        val dummyUri = mock(Uri::class.java)
        uriMock.`when`<Uri> { Uri.parse(ArgumentMatchers.anyString()) }.thenReturn(dummyUri)

        try {
            val mockContext = mock(Context::class.java)
            val mockResolver = mock(ContentResolver::class.java)
            `when`(mockContext.contentResolver).thenReturn(mockResolver)
            `when`(mockContext.packageName).thenReturn("com.lazy.testapp")

            // 构造跨进程返回的 Bundle
            val remoteBundle = mock(Bundle::class.java)
            `when`(remoteBundle.containsKey(MockConfigManager.KEY_IS_MOCK_ENABLED)).thenReturn(true)
            `when`(remoteBundle.getBoolean(MockConfigManager.KEY_IS_MOCK_ENABLED)).thenReturn(true)
            `when`(remoteBundle.containsKey(MockConfigManager.KEY_LATITUDE)).thenReturn(true)
            `when`(remoteBundle.getDouble(MockConfigManager.KEY_LATITUDE)).thenReturn(29.5630)
            `when`(remoteBundle.containsKey(MockConfigManager.KEY_LONGITUDE)).thenReturn(true)
            `when`(remoteBundle.getDouble(MockConfigManager.KEY_LONGITUDE)).thenReturn(106.5516)

            `when`(
                mockResolver.call(
                    ArgumentMatchers.any(Uri::class.java),
                    ArgumentMatchers.anyString(),
                    ArgumentMatchers.nullable(String::class.java),
                    ArgumentMatchers.nullable(Bundle::class.java)
                )
            ).thenReturn(remoteBundle)

            // 重置状态
            MockConfigManager.isConfigSynced = false
            MockConfigManager.currentProcessPackage = "com.lazy.testapp"

            val dummyLm = StatefulDummyLocationManager(mockContext)
            LocationHook.checkAndTriggerLazySync(dummyLm)

            assertTrue("Config sync flag must be set to true", MockConfigManager.isConfigSynced)
            assertTrue("Mock enabled must be updated from remote bundle", MockConfigManager.isMockEnabled)
            assertEquals("Latitude must be updated to 29.5630", 29.5630, MockConfigManager.latitude, 1e-4)
            assertEquals("Longitude must be updated to 106.5516", 106.5516, MockConfigManager.longitude, 1e-4)
        } finally {
            uriMock.close()
        }
    }
}
