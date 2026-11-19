package com.mockloc.hook

import android.location.Location
import com.mockloc.data.config.MockConfig
import com.mockloc.domain.math.CoordinateConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Serializable
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

class ThirdPartyLbsSdkHookTest {

    // ==================== 模拟第三方 SDK 类型体系 ====================

    interface TencentLocation {
        fun getLatitude(): Double
        fun getLongitude(): Double
        fun getAltitude(): Double
        fun getAccuracy(): Float
        fun getSpeed(): Float
        fun getBearing(): Float
        fun getProvider(): String
        fun getCoordinateType(): Int
        fun getFakeReason(): Int
        fun isMockGps(): Boolean
        fun getAddress(): String
        fun getProvince(): String
        fun getCity(): String
        fun getDistrict(): String
        fun getPoiList(): List<Any>
    }

    interface TencentLocationListener {
        fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?)
    }

    class FakeTencentLocationManager {
        var registeredListener: TencentLocationListener? = null
        var removedListener: TencentLocationListener? = null
        fun getCoordinateType(): Int = 0
        fun requestLocationUpdates(req: Any?, listener: TencentLocationListener) {
            registeredListener = listener
        }
        fun removeUpdates(listener: TencentLocationListener) {
            removedListener = listener
        }
        fun getLastKnownLocation(): TencentLocation? = null
    }

    interface AMapLocationListener {
        fun onLocationChanged(location: FakeAMapLocation?)
    }

    class FakeAMapLocation {
        var latitude: Double = 0.0
        var longitude: Double = 0.0
        var altitude: Double = 0.0
        var accuracy: Float = 0f
        var speed: Float = 0f
        var bearing: Float = 0f
        var time: Long = 0L
        var provider: String = ""
        var errorCode: Int = 12
        var errorInfo: String = "error"
        var locationType: Int = 5
        var coordType: String = "WGS84"
        var mock: Boolean = true
        var trustedLevel: Int = 4
        var address: String = "真实街道1号"
        var city: String = "真实城市"
    }

    class FakeAMapLocationClient {
        fun setLocationListener(listener: AMapLocationListener) = Unit
        fun getLastKnownLocation(): FakeAMapLocation? = null
    }

    class FakeAMapQualityReport {
        fun isInstalledHighDangerMockApp(): Boolean = true
        fun getGPSStatus(): Int = 1
        fun getGPSSatellites(): Int = 0
    }

    abstract class BDAbstractLocationListener {
        abstract fun onReceiveLocation(location: FakeBDLocation?)
    }

    class FakeBDLocation {
        var latitude: Double = 0.0
        var longitude: Double = 0.0
        var coorType: String = ""
        var altitude: Double = 0.0
        var radius: Float = 0f
        var speed: Float = 0f
        var direction: Float = 0f
        var locType: Int = 161
        var time: String = ""
        var mockGpsStrategy: Int = 1
        var mockGpsProbability: Int = 100
        var addrStr: String = "真实百度地址"
        var city: String = "真实城市"

        fun isMockGps(): Boolean = true
        fun getDisToRealLocation(): Float = 1500f
    }

    class FakeBaiduLocationClient {
        fun registerLocationListener(listener: BDAbstractLocationListener) = Unit
        fun getLastKnownLocation(): FakeBDLocation? = null
    }

    class FakeLocationResult(private val list: List<Location>) {
        fun getLocations(): List<Location> = list
        fun getLastLocation(): Location? = list.lastOrNull()
    }

    interface GmsLocationListener {
        fun onLocationChanged(location: Location)
    }

    abstract class FakeGmsLocationCallback {
        open fun onLocationResult(result: FakeLocationResult) = Unit
        open fun onLocationAvailability(availability: Any?) = Unit
    }

    class FakeFusedClientImpl {
        fun requestLocationUpdates(req: Any?, callback: FakeGmsLocationCallback, looper: Any?) = Unit
        fun requestLocationUpdates(req: Any?, listener: GmsLocationListener, looper: Any?) = Unit
        fun removeLocationUpdates(listener: GmsLocationListener) = Unit
        fun removeLocationUpdates(callback: FakeGmsLocationCallback) = Unit
    }

    class FakeLocationServices {
        fun getFusedLocationProviderClient(ctx: Any?): FakeFusedClientImpl = FakeFusedClientImpl()
    }

    private fun registerAllFakeSdks(backend: FakeHookBackend) {
        backend.classRegistry["com.tencent.map.geolocation.TencentLocation"] = TencentLocation::class.java
        backend.classRegistry["com.tencent.map.geolocation.TencentLocationListener"] = TencentLocationListener::class.java
        backend.classRegistry["com.tencent.map.geolocation.TencentLocationManager"] = FakeTencentLocationManager::class.java

        backend.classRegistry["com.amap.api.location.AMapLocationClient"] = FakeAMapLocationClient::class.java
        backend.classRegistry["com.amap.api.location.AMapLocation"] = FakeAMapLocation::class.java
        backend.classRegistry["com.amap.api.location.AMapLocationQualityReport"] = FakeAMapQualityReport::class.java

        backend.classRegistry["com.baidu.location.LocationClient"] = FakeBaiduLocationClient::class.java
        backend.classRegistry["com.baidu.location.BDLocation"] = FakeBDLocation::class.java

        backend.classRegistry["com.google.android.gms.location.LocationServices"] = FakeLocationServices::class.java
        backend.classRegistry["com.google.android.gms.location.LocationCallback"] = FakeGmsLocationCallback::class.java
        backend.classRegistry["com.google.android.gms.location.LocationListener"] = GmsLocationListener::class.java
    }

    private fun activeConfig(pkg: String = "com.tencent.mm") = MockConfig(
        enabled = true,
        latitude = 39.9087,
        longitude = 116.3975,
        altitude = 66.0,
        accuracy = 4.0f,
        speed = 3.5f,
        bearing = 180f,
        targetPackages = setOf(pkg),
        updatedAtMs = 100L,
        version = 1L
    )

    @Test
    fun `Tencent SDK proxies listener to GCJ02 clears risk fields and unregisters matching proxy`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val coordTypeInv = backend.invokeSimulated(null, FakeTencentLocationManager::class.java, "getCoordinateType") { 0 }
        assertEquals(1, coordTypeInv.result)

        var receivedLoc: TencentLocation? = null
        var receivedErr = -1
        var receivedReason: String? = null
        val origListener = object : TencentLocationListener {
            override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) {
                receivedLoc = location
                receivedErr = error
                receivedReason = reason
            }
        }

        val reqInv = backend.invokeSimulated(
            null,
            FakeTencentLocationManager::class.java,
            "requestLocationUpdates",
            null,
            origListener
        )
        val wrappedProxy = reqInv.args[1] as TencentLocationListener
        assertNotSame(origListener, wrappedProxy)

        // 触发系统定位回调，验证被 Proxy 拦截转换为 GCJ-02 且脱敏地址/风控字段
        wrappedProxy.onLocationChanged(null, 404, "TIMEOUT")
        val expectedGcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)
        assertEquals(0, receivedErr)
        assertEquals("OK", receivedReason)
        assertEquals(expectedGcj.latitude, receivedLoc!!.getLatitude(), 1e-6)
        assertEquals(expectedGcj.longitude, receivedLoc!!.getLongitude(), 1e-6)
        assertEquals("gps", receivedLoc!!.getProvider())
        assertEquals(1, receivedLoc!!.getCoordinateType())
        assertEquals(0, receivedLoc!!.getFakeReason())
        assertFalse(receivedLoc!!.isMockGps())
        assertEquals("", receivedLoc!!.getAddress())
        assertEquals("", receivedLoc!!.getCity())
        assertTrue(receivedLoc!!.getPoiList().isEmpty())

        // removeUpdates(origListener) 必须能还原出同一个 wrappedProxy
        val removeInv = backend.invokeSimulated(
            null,
            FakeTencentLocationManager::class.java,
            "removeUpdates",
            origListener
        )
        assertSame(wrappedProxy, removeInv.args[0])
    }

    @Test
    fun `AMap SDK hooks concrete listener converts to GCJ02 and clears quality report flags`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.autonavi.minimap")))
        hook.installForClassLoader(javaClass.classLoader, "com.autonavi.minimap")

        val listener = object : AMapLocationListener {
            override fun onLocationChanged(location: FakeAMapLocation?) = Unit
        }
        backend.invokeSimulated(null, FakeAMapLocationClient::class.java, "setLocationListener", listener)
        // 重复注册同一具体类只 Hook 一次
        backend.invokeSimulated(null, FakeAMapLocationClient::class.java, "setLocationListener", listener)
        assertEquals(1, backend.hookCountFor(listener.javaClass, "onLocationChanged"))

        val amapLoc = FakeAMapLocation()
        backend.invokeSimulated(listener, listener.javaClass, "onLocationChanged", amapLoc)

        val expectedGcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)
        assertEquals(expectedGcj.latitude, amapLoc.latitude, 1e-6)
        assertEquals(expectedGcj.longitude, amapLoc.longitude, 1e-6)
        assertEquals(0, amapLoc.errorCode)
        assertEquals(1, amapLoc.locationType)
        assertEquals("GCJ02", amapLoc.coordType)
        assertFalse(amapLoc.mock)
        assertEquals(1, amapLoc.trustedLevel)
        assertEquals("", amapLoc.address)
        assertEquals("", amapLoc.city)

        assertEquals(
            false,
            backend.invokeSimulated(null, FakeAMapQualityReport::class.java, "isInstalledHighDangerMockApp") { true }.result
        )
    }

    @Test
    fun `Baidu SDK hooks concrete listener converts to BD09 and clears mockGps flags`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.baidu.BaiduMap")))
        hook.installForClassLoader(javaClass.classLoader, "com.baidu.BaiduMap")

        val listener = object : BDAbstractLocationListener() {
            override fun onReceiveLocation(location: FakeBDLocation?) = Unit
        }
        backend.invokeSimulated(null, FakeBaiduLocationClient::class.java, "registerLocationListener", listener)
        assertEquals(1, backend.hookCountFor(listener.javaClass, "onReceiveLocation"))

        val bdLoc = FakeBDLocation()
        backend.invokeSimulated(listener, listener.javaClass, "onReceiveLocation", bdLoc)

        val expectedBd = CoordinateConverter.wgs84ToBd09(39.9087, 116.3975)
        assertEquals(expectedBd.latitude, bdLoc.latitude, 1e-6)
        assertEquals(expectedBd.longitude, bdLoc.longitude, 1e-6)
        assertEquals("bd09ll", bdLoc.coorType)
        assertEquals(61, bdLoc.locType)
        assertEquals(0, bdLoc.mockGpsStrategy)
        assertEquals(0, bdLoc.mockGpsProbability)
        assertEquals("", bdLoc.addrStr)
        assertEquals("", bdLoc.city)

        assertEquals(false, backend.invokeSimulated(bdLoc, FakeBDLocation::class.java, "isMockGps") { true }.result)
        assertEquals(0.0f, backend.invokeSimulated(bdLoc, FakeBDLocation::class.java, "getDisToRealLocation") { 999f }.result)
    }

    @Test
    fun `GMS Fused captures concrete client and LocationCallback keeping WGS84`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.ss.android.lark")))
        hook.installForClassLoader(javaClass.classLoader, "com.ss.android.lark")

        val client = FakeFusedClientImpl()
        backend.invokeSimulated(null, FakeLocationServices::class.java, "getFusedLocationProviderClient", null) { client }
        assertEquals(1, backend.hookCountFor(FakeFusedClientImpl::class.java, "requestLocationUpdates"))

        val cb = object : FakeGmsLocationCallback() {
            override fun onLocationResult(result: FakeLocationResult) = Unit
        }
        backend.invokeSimulated(client, FakeFusedClientImpl::class.java, "requestLocationUpdates", null, cb, null)
        assertEquals(1, backend.hookCountFor(cb.javaClass, "onLocationResult"))

        val rawLoc = createStatefulMockLocation(initialLat = 1.0, initialLon = 2.0, initialMock = true)
        val resultObj = FakeLocationResult(listOf(rawLoc))
        backend.invokeSimulated(cb, cb.javaClass, "onLocationResult", resultObj)

        assertEquals(39.9087, rawLoc.latitude, 1e-6)
        assertEquals(116.3975, rawLoc.longitude, 1e-6)
        assertFalse(rawLoc.isMock)
    }

    @Test
    fun `disabled or non-whitelisted package passes third party locations untouched`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val cfgProvider = StaticConfigSnapshotProvider(activeConfig("com.tencent.mm").copy(enabled = false))
        val hook = ThirdPartyLbsSdkHook(backend, cfgProvider)
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val listener = object : AMapLocationListener {
            override fun onLocationChanged(location: FakeAMapLocation?) = Unit
        }
        backend.invokeSimulated(null, FakeAMapLocationClient::class.java, "setLocationListener", listener)
        val amapLoc = FakeAMapLocation().apply {
            latitude = 25.0
            longitude = 102.0
            city = "昆明"
        }
        backend.invokeSimulated(listener, listener.javaClass, "onLocationChanged", amapLoc)

        assertEquals(25.0, amapLoc.latitude, 1e-6)
        assertEquals(102.0, amapLoc.longitude, 1e-6)
        assertEquals("昆明", amapLoc.city)
    }

    @Test
    fun `Tencent early registration while disabled masks via L1 hot-enable without L2 broadcast`() {
        val nowMs = AtomicLong(1_000L)
        val initialCfg = activeConfig("com.tencent.mm").copy(enabled = false)
        val fakeReader = FakePrefsReader(initialCfg)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            elapsedRealtimeProvider = { nowMs.get() },
            throttleMs = 400L
        )
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, store)
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val received = mutableListOf<TencentLocation?>()
        val orig = object : TencentLocationListener {
            override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) {
                received.add(location)
            }
        }
        val reqInv = backend.invokeSimulated(null, FakeTencentLocationManager::class.java, "requestLocationUpdates", null, orig)
        val proxy = reqInv.args[1] as TencentLocationListener
        assertNotSame(orig, proxy)

        // 未启用时原样透传 null/原始对象
        proxy.onLocationChanged(null, 0, "RAW")
        assertEquals(1, received.size)
        assertNull(received[0])

        // L1 热开启（无 L2 广播）推进 500ms 后触发回调，应切换为模拟 GCJ-02 位置
        fakeReader.writeConfig(initialCfg.copy(enabled = true, version = 2L, updatedAtMs = 200L), markChanged = true)
        nowMs.set(1_600L)
        proxy.onLocationChanged(null, 404, "TIMEOUT")
        assertEquals(2, received.size)
        val expectedGcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)
        assertEquals(expectedGcj.latitude, received[1]!!.getLatitude(), 1e-6)
        assertEquals(expectedGcj.longitude, received[1]!!.getLongitude(), 1e-6)
    }

    @Test
    fun `Tencent and GMS listener WeakHashMap proxies allow GC of original and GMS removeLocationUpdates restores proxy`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val client = FakeFusedClientImpl()
        backend.invokeSimulated(null, FakeLocationServices::class.java, "getFusedLocationProviderClient", null) { client }

        // 在独立作用域内创建并注册原始监听器，仅保留 proxy（不保留 orig 强引用）
        val (tencentOrigRef, tencentProxy) = registerTencentInFrame(backend)
        val (gmsOrigRef, gmsProxy) = registerGmsInFrame(backend, client)
        assertTrue(java.lang.reflect.Proxy.isProxyClass(tencentProxy.javaClass))
        assertTrue(java.lang.reflect.Proxy.isProxyClass(gmsProxy.javaClass))

        for (i in 0 until 25) {
            System.gc()
            Thread.sleep(10)
            if (tencentOrigRef.get() == null && gmsOrigRef.get() == null) break
        }
        assertNull("Tencent WeakHashMap proxy must not strongly retain original", tencentOrigRef.get())
        assertNull("GMS WeakHashMap proxy must not strongly retain original", gmsOrigRef.get())

        // 验证活跃状态下 GMS removeLocationUpdates(orig) 精确还原为 gmsProxy
        val activeOrig = object : GmsLocationListener {
            override fun onLocationChanged(location: Location) = Unit
        }
        val gmsReq = backend.invokeSimulated(client, FakeFusedClientImpl::class.java, "requestLocationUpdates", null, activeOrig, null)
        val expectedGmsProxy = gmsReq.args[1] as GmsLocationListener
        val gmsRem = backend.invokeSimulated(client, FakeFusedClientImpl::class.java, "removeLocationUpdates", activeOrig)
        assertSame(expectedGmsProxy, gmsRem.args[0])
    }

    private fun registerTencentInFrame(backend: FakeHookBackend): Pair<WeakReference<TencentLocationListener>, TencentLocationListener> {
        val orig: TencentLocationListener = object : TencentLocationListener {
            override fun onLocationChanged(location: TencentLocation?, error: Int, reason: String?) = Unit
        }
        val reqInv = backend.invokeSimulated(null, FakeTencentLocationManager::class.java, "requestLocationUpdates", null, orig)
        return WeakReference<TencentLocationListener>(orig) to (reqInv.args[1] as TencentLocationListener)
    }

    private fun registerGmsInFrame(backend: FakeHookBackend, client: FakeFusedClientImpl): Pair<WeakReference<GmsLocationListener>, GmsLocationListener> {
        val orig: GmsLocationListener = object : GmsLocationListener {
            override fun onLocationChanged(location: Location) = Unit
        }
        val reqInv = backend.invokeSimulated(client, FakeFusedClientImpl::class.java, "requestLocationUpdates", null, orig, null)
        return WeakReference<GmsLocationListener>(orig) to (reqInv.args[1] as GmsLocationListener)
    }

    private open class BaseAmapListener : AMapLocationListener {
        override fun onLocationChanged(location: FakeAMapLocation?) = Unit
    }

    private class DerivedAmapListenerNoOverride : BaseAmapListener()

    private open class BaseBaiduListener : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: FakeBDLocation?) = Unit
    }

    private class DerivedBaiduListenerNoOverride : BaseBaiduListener()

    private open class BaseGmsCallback : FakeGmsLocationCallback() {
        override fun onLocationResult(result: FakeLocationResult) = Unit
    }

    private class DerivedGmsCallbackNoOverride : BaseGmsCallback()

    @Test
    fun `inherited non-overridden AMap Baidu and GMS callbacks are hooked under getDeclaredMethods-only backend`() {
        val backend = DeclaredOnlyHookBackend()
        backend.classRegistry["com.amap.api.location.AMapLocationClient"] = FakeAMapLocationClient::class.java
        backend.classRegistry["com.baidu.location.LocationClient"] = FakeBaiduLocationClient::class.java
        backend.classRegistry["com.google.android.gms.location.LocationServices"] = FakeLocationServices::class.java
        backend.classRegistry["com.google.android.gms.location.LocationCallback"] = FakeGmsLocationCallback::class.java

        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        // AMap 继承父类未重写
        val amapDerived = DerivedAmapListenerNoOverride()
        backend.invokeSimulated(null, FakeAMapLocationClient::class.java, "setLocationListener", amapDerived)
        val amapLoc = FakeAMapLocation()
        backend.invokeSimulated(amapDerived, DerivedAmapListenerNoOverride::class.java, "onLocationChanged", amapLoc)
        assertEquals(CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975).latitude, amapLoc.latitude, 1e-6)

        // Baidu 继承父类未重写
        val bdDerived = DerivedBaiduListenerNoOverride()
        backend.invokeSimulated(null, FakeBaiduLocationClient::class.java, "registerLocationListener", bdDerived)
        val bdLoc = FakeBDLocation()
        backend.invokeSimulated(bdDerived, DerivedBaiduListenerNoOverride::class.java, "onReceiveLocation", bdLoc)
        assertEquals(CoordinateConverter.wgs84ToBd09(39.9087, 116.3975).latitude, bdLoc.latitude, 1e-6)

        // GMS 继承父类未重写
        val client = FakeFusedClientImpl()
        backend.invokeSimulated(null, FakeLocationServices::class.java, "getFusedLocationProviderClient", null) { client }
        val gmsDerived = DerivedGmsCallbackNoOverride()
        backend.invokeSimulated(client, FakeFusedClientImpl::class.java, "requestLocationUpdates", null, gmsDerived, null)
        val rawLoc = createStatefulMockLocation(initialLat = 1.0, initialLon = 2.0, initialMock = true)
        backend.invokeSimulated(gmsDerived, DerivedGmsCallbackNoOverride::class.java, "onLocationResult", FakeLocationResult(listOf(rawLoc)))
        assertEquals(39.9087, rawLoc.latitude, 1e-6)
        assertFalse(rawLoc.isMock)
    }

    private class NonInterfaceTencentLocation {
        var latitude: Double = 0.0
        var longitude: Double = 0.0
        var provider: String = ""
        var address: String = "原始地址"
    }

    private class NonInterfaceSerializableTencentLoc(
        var latitude: Double = 22.1234,
        var longitude: Double = 113.5678,
        var provider: String = "network",
        private val address: String = "REAL_PRIVATE_ADDR"
    ) : Serializable {
        fun getAddress(): String = address
    }

    private interface NonIfaceSerializableTencentListener {
        fun onLocationChanged(location: NonInterfaceSerializableTencentLoc?, error: Int, reason: String?)
    }

    private class NonIfaceConcreteTencentListener(
        val received: MutableList<NonInterfaceSerializableTencentLoc?> = mutableListOf()
    ) {
        fun onLocationChanged(location: NonInterfaceSerializableTencentLoc?, error: Int, reason: String?) {
            received.add(location)
        }
    }

    private class FakeNonIfaceTencentLocationManager {
        fun requestLocationUpdates(req: Any?, listener: NonIfaceSerializableTencentListener) = Unit
        fun requestLocationUpdates(req: Any?, listener: NonIfaceConcreteTencentListener) = Unit
        fun getLastKnownLocation(): NonInterfaceSerializableTencentLoc? = null
    }

    private class IntZzaAvailability {
        @JvmField
        var zza: Int = 42
        @JvmField
        var isLocationAvailable: Boolean = false
    }

    @Test
    fun `Tencent non-interface getLastKnownLocation degrades safely proxy hashCode equals and availability type guard work`() {
        val backend = FakeHookBackend()
        registerAllFakeSdks(backend)
        backend.classRegistry["com.tencent.map.geolocation.TencentLocation"] = NonInterfaceTencentLocation::class.java
        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val rawConcrete = NonInterfaceTencentLocation()
        val lastInv = backend.invokeSimulated(null, FakeTencentLocationManager::class.java, "getLastKnownLocation") { rawConcrete }
        val resObj = lastInv.result as NonInterfaceTencentLocation
        val expectedGcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)
        assertEquals(expectedGcj.latitude, resObj.latitude, 1e-6)
        assertEquals(expectedGcj.longitude, resObj.longitude, 1e-6)
        assertEquals("", resObj.address)

        // 验证接口版 TencentLocation 代理的 hashCode / equals 语义
        val backend2 = FakeHookBackend()
        registerAllFakeSdks(backend2)
        val hook2 = ThirdPartyLbsSdkHook(backend2, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook2.installForClassLoader(javaClass.classLoader, "com.tencent.mm")
        val tl1 = backend2.invokeSimulated(null, FakeTencentLocationManager::class.java, "getLastKnownLocation") { null }.result!!
        val tl2 = backend2.invokeSimulated(null, FakeTencentLocationManager::class.java, "getLastKnownLocation") { null }.result!!
        assertTrue(tl1 == tl1)
        assertFalse(tl1 == tl2)
        assertNotEquals(tl1.hashCode(), tl2.hashCode())
        assertNotEquals(System.identityHashCode(hook2), tl1.hashCode())

        // 验证 GMS onLocationAvailability 仅写 Boolean 字段，不将 true 写入 Int 型 zza
        val client = FakeFusedClientImpl()
        backend2.invokeSimulated(null, FakeLocationServices::class.java, "getFusedLocationProviderClient", null) { client }
        val cb = object : FakeGmsLocationCallback() {
            override fun onLocationAvailability(availability: Any?) = Unit
        }
        backend2.invokeSimulated(client, FakeFusedClientImpl::class.java, "requestLocationUpdates", null, cb, null)
        val avail = IntZzaAvailability()
        backend2.invokeSimulated(cb, cb.javaClass, "onLocationAvailability", avail)
        assertEquals(42, avail.zza)
        assertTrue(avail.isLocationAvailable)
    }

    @Test
    fun `Tencent non-interface Serializable location preserves concrete type and sanitizes both getLastKnownLocation and onLocationChanged callbacks`() {
        val backend = FakeHookBackend()
        backend.classRegistry["com.tencent.map.geolocation.TencentLocation"] = NonInterfaceSerializableTencentLoc::class.java
        backend.classRegistry["com.tencent.map.geolocation.TencentLocationListener"] = NonIfaceSerializableTencentListener::class.java
        backend.classRegistry["com.tencent.map.geolocation.TencentLocationManager"] = FakeNonIfaceTencentLocationManager::class.java

        val hook = ThirdPartyLbsSdkHook(backend, StaticConfigSnapshotProvider(activeConfig("com.tencent.mm")))
        hook.installForClassLoader(javaClass.classLoader, "com.tencent.mm")

        val expectedGcj = CoordinateConverter.wgs84ToGcj02(39.9087, 116.3975)

        // 1. getLastKnownLocation 严禁抓取 Serializable 生成 $Proxy，必须保留原类型且脱敏
        val rawLastLoc = NonInterfaceSerializableTencentLoc()
        val lastInv = backend.invokeSimulated(
            null,
            FakeNonIfaceTencentLocationManager::class.java,
            "getLastKnownLocation"
        ) { rawLastLoc }
        assertFalse(Proxy.isProxyClass(lastInv.result!!.javaClass))
        assertTrue(lastInv.result is NonInterfaceSerializableTencentLoc)
        val sanitizedLast = lastInv.result as NonInterfaceSerializableTencentLoc
        assertSame(rawLastLoc, sanitizedLast)
        assertEquals(expectedGcj.latitude, sanitizedLast.latitude, 1e-6)
        assertEquals(expectedGcj.longitude, sanitizedLast.longitude, 1e-6)
        assertEquals("", sanitizedLast.getAddress())

        // 2. 接口代理监听器收到 onLocationChanged(nonIfaceLoc, 0, "OK") 时保持原类型且脱敏
        var cbReceivedViaProxy: NonInterfaceSerializableTencentLoc? = null
        val ifaceListener = object : NonIfaceSerializableTencentListener {
            override fun onLocationChanged(location: NonInterfaceSerializableTencentLoc?, error: Int, reason: String?) {
                cbReceivedViaProxy = location
            }
        }
        val reqProxyInv = backend.invokeSimulated(
            null,
            FakeNonIfaceTencentLocationManager::class.java,
            "requestLocationUpdates",
            null,
            ifaceListener
        )
        val wrappedProxy = reqProxyInv.args[1] as NonIfaceSerializableTencentListener
        val cbLoc1 = NonInterfaceSerializableTencentLoc()
        wrappedProxy.onLocationChanged(cbLoc1, 0, "OK")
        assertSame(cbLoc1, cbReceivedViaProxy)
        assertFalse(Proxy.isProxyClass(cbReceivedViaProxy!!.javaClass))
        assertEquals(expectedGcj.latitude, cbReceivedViaProxy!!.latitude, 1e-6)
        assertEquals(expectedGcj.longitude, cbReceivedViaProxy!!.longitude, 1e-6)
        assertEquals("", cbReceivedViaProxy!!.getAddress())

        // 3. 具体 Listener 类回调收到 onLocationChanged(nonIfaceLoc, 0, "OK") 时保持原类型且脱敏
        val concreteListener = NonIfaceConcreteTencentListener()
        backend.invokeSimulated(
            null,
            FakeNonIfaceTencentLocationManager::class.java,
            "requestLocationUpdates",
            null,
            concreteListener
        )
        val cbLoc2 = NonInterfaceSerializableTencentLoc()
        backend.invokeSimulated(
            concreteListener,
            NonIfaceConcreteTencentListener::class.java,
            "onLocationChanged",
            cbLoc2,
            0,
            "OK"
        )
        assertEquals(expectedGcj.latitude, cbLoc2.latitude, 1e-6)
        assertEquals(expectedGcj.longitude, cbLoc2.longitude, 1e-6)
        assertEquals("", cbLoc2.getAddress())
    }
}
