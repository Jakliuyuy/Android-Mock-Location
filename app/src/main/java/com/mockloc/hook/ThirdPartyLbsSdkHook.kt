package com.mockloc.hook

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.mockloc.data.config.MockConfig
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.hook.cleaner.MockLocationCleaner
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class ThirdPartyLbsSdkHook(
    private val backend: HookBackend = XposedHookBackend,
    private val configProvider: ConfigSnapshotProvider = HookConfigStore.shared
) {
    private val installedSdks = ConcurrentHashMap.newKeySet<String>()
    private val hookedConcreteClasses = ConcurrentHashMap.newKeySet<String>()
    private val proxyLock = Any()
    private val tencentListenerProxies = WeakHashMap<Any, WeakReference<Any>>()
    private val gmsListenerProxies = WeakHashMap<Any, WeakReference<Any>>()
    private val baiduClientCoorTypes = WeakHashMap<Any, String>()
    private val baiduListenerCoorTypes = WeakHashMap<Any, String>()
    private val baiduClientListeners = WeakHashMap<Any, MutableList<WeakReference<Any>>>()

    fun installForClassLoader(classLoader: ClassLoader?, targetPackage: String) {
        if (classLoader == null) return
        installTencentHook(classLoader, targetPackage)
        installAmapHook(classLoader, targetPackage)
        installBaiduHook(classLoader, targetPackage)
        installGmsFusedHook(classLoader, targetPackage)
    }

    internal fun resolveSdkPoint(config: MockConfig, targetCoord: CoordinateType): GeoPoint {
        val wgsLat = config.latitude
        val wgsLon = config.longitude
        return when (targetCoord) {
            CoordinateType.WGS84 -> GeoPoint.createSafe(
                latitude = wgsLat,
                longitude = wgsLon,
                altitude = config.altitude,
                accuracy = config.accuracy
            )
            CoordinateType.GCJ02 -> CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon).copy(
                altitude = config.altitude,
                accuracy = config.accuracy
            )
            CoordinateType.BD09 -> CoordinateConverter.wgs84ToBd09(wgsLat, wgsLon).copy(
                altitude = config.altitude,
                accuracy = config.accuracy
            )
        }
    }

    // ==================== 1. 腾讯定位 / 微信 (GCJ-02) ====================

    private fun installTencentHook(classLoader: ClassLoader, targetPackage: String) {
        val mgrCls = backend.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocationManager",
            classLoader
        ) ?: return
        val sdkKey = "tencent#${System.identityHashCode(mgrCls)}#$targetPackage"
        if (!installedSdks.add(sdkKey)) return

        val locIface = backend.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocation",
            classLoader
        )
        val listenerIface = backend.findClassIfExists(
            "com.tencent.map.geolocation.TencentLocationListener",
            classLoader
        )

        backend.hookAllMethods(
            mgrCls,
            "getCoordinateType",
            after = MethodHookHandler { inv ->
                if (configProvider.isActiveFor(targetPackage)) {
                    inv.result = TENCENT_COORDINATE_TYPE_GCJ02
                }
            }
        )

        val requestBefore = MethodHookHandler { inv ->
            for (i in inv.args.indices) {
                val arg = inv.args[i] ?: continue
                val ifaceToProxy = findTencentListenerInterface(arg, listenerIface)
                if (ifaceToProxy != null) {
                    val proxy = getOrCreateTencentListenerProxy(
                        original = arg,
                        listenerInterface = ifaceToProxy,
                        locationIface = locIface,
                        classLoader = classLoader,
                        targetPackage = targetPackage
                    )
                    inv.args[i] = proxy
                } else if (looksLikeTencentListenerObject(arg)) {
                    val implClass = arg.javaClass
                    if (!implClass.isInterface) {
                        HookMethodUtils.hookConcreteMethodHierarchy(
                            backend = backend,
                            hookedMethodKeys = hookedConcreteClasses,
                            implClass = implClass,
                            methodName = "onLocationChanged",
                            before = MethodHookHandler { cbInv ->
                                handleTencentCallbackArgs(cbInv.args, locIface, classLoader, targetPackage)
                            }
                        )
                    }
                }
            }
        }

        backend.hookAllMethods(mgrCls, "requestLocationUpdates", before = requestBefore)
        backend.hookAllMethods(mgrCls, "requestSingleFreshLocation", before = requestBefore)

        backend.hookAllMethods(
            mgrCls,
            "removeUpdates",
            before = MethodHookHandler { inv ->
                val arg0 = inv.args.firstOrNull() ?: return@MethodHookHandler
                val mapped = synchronized(proxyLock) { tencentListenerProxies.remove(arg0)?.get() }
                if (mapped != null) {
                    inv.args[0] = mapped
                }
            }
        )

        backend.hookAllMethods(
            mgrCls,
            "getLastKnownLocation",
            after = MethodHookHandler { inv ->
                val cfg = configProvider.currentConfig()
                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                val targetIface = resolveTencentLocationInterface(locIface, inv.result)
                val gcjPoint = resolveSdkPoint(cfg, CoordinateType.GCJ02)
                if (targetIface != null) {
                    inv.result = createTencentLocationProxy(inv.result, cfg, gcjPoint, targetIface, classLoader)
                } else if (inv.result != null) {
                    sanitizeTencentLocationObject(inv.result!!, cfg, gcjPoint)
                }
            }
        )
    }

    private fun sanitizeTencentLocationObject(locObj: Any, config: MockConfig, gcjPoint: GeoPoint) {
        if (locObj is Location) {
            MockLocationCleaner.cleanLocation(locObj)
            runCatching {
                locObj.latitude = gcjPoint.latitude
                locObj.longitude = gcjPoint.longitude
                locObj.altitude = config.altitude
                locObj.accuracy = config.accuracy
                locObj.speed = config.speed
                locObj.bearing = config.bearing
                locObj.provider = "gps"
            }
            return
        }
        MockLocationCleaner.cleanHiddenFields(locObj)
        invokeMethodIfExists(locObj, listOf("setLatitude"), arrayOf(Double::class.javaPrimitiveType!!), gcjPoint.latitude)
        invokeMethodIfExists(locObj, listOf("setLongitude"), arrayOf(Double::class.javaPrimitiveType!!), gcjPoint.longitude)
        invokeMethodIfExists(locObj, listOf("setAltitude"), arrayOf(Double::class.javaPrimitiveType!!), config.altitude)
        invokeMethodIfExists(locObj, listOf("setAccuracy"), arrayOf(Float::class.javaPrimitiveType!!), config.accuracy)
        invokeMethodIfExists(locObj, listOf("setSpeed"), arrayOf(Float::class.javaPrimitiveType!!), config.speed)
        invokeMethodIfExists(locObj, listOf("setBearing"), arrayOf(Float::class.javaPrimitiveType!!), config.bearing)
        invokeMethodIfExists(locObj, listOf("setProvider"), arrayOf(String::class.java), "gps")
        invokeMethodIfExists(locObj, listOf("setCoordinateType"), arrayOf(Int::class.javaPrimitiveType!!), TENCENT_COORDINATE_TYPE_GCJ02)
        setFieldIfExists(locObj, listOf("latitude", "mLatitude"), gcjPoint.latitude)
        setFieldIfExists(locObj, listOf("longitude", "mLongitude"), gcjPoint.longitude)
        setFieldIfExists(locObj, listOf("altitude", "mAltitude"), config.altitude)
        setFieldIfExists(locObj, listOf("accuracy", "mAccuracy"), config.accuracy)
        setFieldIfExists(locObj, listOf("speed", "mSpeed"), config.speed)
        setFieldIfExists(locObj, listOf("bearing", "mBearing"), config.bearing)
        setFieldIfExists(locObj, listOf("provider", "mProvider"), "gps")
        setFieldIfExists(locObj, listOf("coordinateType", "mCoordinateType"), TENCENT_COORDINATE_TYPE_GCJ02)
        setFieldIfExists(locObj, listOf("fakeReason", "mFakeReason", "fake", "mFake"), 0)
        setFieldIfExists(locObj, listOf("mockGps", "mMockGps", "isMockGps"), false)
        val clearStrTargets = listOf(
            "setAddress" to listOf("address", "mAddress"),
            "setNation" to listOf("nation", "mNation"),
            "setProvince" to listOf("province", "mProvince"),
            "setCity" to listOf("city", "mCity"),
            "setDistrict" to listOf("district", "mDistrict"),
            "setTown" to listOf("town", "mTown"),
            "setVillage" to listOf("village", "mVillage"),
            "setStreet" to listOf("street", "mStreet"),
            "setStreetNo" to listOf("streetNo", "mStreetNo"),
            "setName" to listOf("name", "mName"),
            "setCityCode" to listOf("cityCode", "mCityCode")
        )
        for ((setter, fields) in clearStrTargets) {
            invokeMethodIfExists(locObj, listOf(setter), arrayOf(String::class.java), "")
            setStringFieldIfExists(locObj, fields, "")
        }
    }

    internal fun resolveTencentLocationInterface(
        locationIface: Class<*>?,
        instance: Any?
    ): Class<*>? {
        if (locationIface != null && locationIface.isInterface) {
            return locationIface
        }
        if (instance == null) return null
        val excludedMarkerInterfaces = setOf(
            "java.io.Serializable",
            "android.os.Parcelable",
            "java.lang.Cloneable",
            "java.lang.Comparable"
        )
        var cursor: Class<*>? = instance.javaClass
        while (cursor != null && cursor !== Any::class.java) {
            for (iface in cursor.interfaces) {
                if (!iface.isInterface || iface.name in excludedMarkerInterfaces) continue
                val isTencentLoc = iface.name.contains("TencentLocation") ||
                    iface.methods.any { m -> m.name == "getLatitude" && m.parameterTypes.isEmpty() }
                if (isTencentLoc) {
                    return iface
                }
            }
            cursor = cursor.superclass
        }
        return null
    }

    private fun findTencentListenerInterface(arg: Any, knownIface: Class<*>?): Class<*>? {
        if (knownIface != null && knownIface.isInterface && knownIface.isInstance(arg)) {
            return knownIface
        }
        return arg.javaClass.interfaces.firstOrNull { iface ->
            iface.name.contains("TencentLocationListener") ||
                iface.methods.any { m ->
                    m.name == "onLocationChanged" && m.parameterTypes.size == 3
                }
        }
    }

    private fun looksLikeTencentListenerObject(arg: Any): Boolean {
        return arg.javaClass.methods.any { m ->
            m.name == "onLocationChanged" && m.parameterTypes.size == 3
        }
    }

    private fun getOrCreateTencentListenerProxy(
        original: Any,
        listenerInterface: Class<*>,
        locationIface: Class<*>?,
        classLoader: ClassLoader,
        targetPackage: String
    ): Any {
        synchronized(proxyLock) {
            tencentListenerProxies[original]?.get()?.let { return it }
            val loader = original.javaClass.classLoader ?: classLoader
            val origRef = WeakReference(original)
            val proxy = Proxy.newProxyInstance(
                loader,
                arrayOf(listenerInterface),
                InvocationHandler { proxyObj, method, args ->
                    val orig = origRef.get()
                    when (method.name) {
                        "equals" -> {
                            val other = args?.firstOrNull()
                            return@InvocationHandler other === proxyObj || (orig != null && other === orig)
                        }
                        "hashCode" -> {
                            return@InvocationHandler if (orig != null) {
                                System.identityHashCode(orig)
                            } else {
                                System.identityHashCode(proxyObj)
                            }
                        }
                        "toString" -> {
                            return@InvocationHandler orig?.toString() ?: "MockLocTencentListenerProxy(recycled)"
                        }
                        "onLocationChanged" -> {
                            if (args != null) {
                                handleTencentCallbackArgs(args, locationIface, loader, targetPackage)
                            }
                        }
                    }
                    if (orig == null) return@InvocationHandler null
                    if (args == null || args.isEmpty()) method.invoke(orig) else method.invoke(orig, *args)
                }
            )
            tencentListenerProxies[original] = WeakReference(proxy)
            return proxy
        }
    }

    private fun handleTencentCallbackArgs(
        args: Array<Any?>,
        locationIface: Class<*>?,
        classLoader: ClassLoader,
        targetPackage: String
    ) {
        val cfg = configProvider.currentConfig()
        if (!cfg.isEnabledForPackage(targetPackage)) return
        if (args.isEmpty()) return

        val origLoc = args[0]
        val iface = resolveTencentLocationInterface(locationIface, origLoc)
        val gcjPoint = resolveSdkPoint(cfg, CoordinateType.GCJ02)
        if (iface != null) {
            args[0] = createTencentLocationProxy(origLoc, cfg, gcjPoint, iface, classLoader)
        } else if (origLoc != null) {
            sanitizeTencentLocationObject(origLoc, cfg, gcjPoint)
        }
        if (args.size >= 2 && (args[1] == null || args[1] is Int)) {
            args[1] = 0
        }
        if (args.size >= 3 && (args[2] == null || args[2] is String)) {
            args[2] = "OK"
        }
    }

    private fun createTencentLocationProxy(
        original: Any?,
        config: MockConfig,
        gcjPoint: GeoPoint,
        locationInterface: Class<*>,
        classLoader: ClassLoader
    ): Any {
        val loader = locationInterface.classLoader ?: classLoader
        return Proxy.newProxyInstance(
            loader,
            arrayOf(locationInterface),
            InvocationHandler { proxyObj, method, args ->
                val name = method.name
                val retType = method.returnType
                when (name) {
                    "getLatitude" -> gcjPoint.latitude
                    "getLongitude" -> gcjPoint.longitude
                    "getAltitude" -> config.altitude
                    "getAccuracy" -> config.accuracy
                    "getSpeed" -> config.speed
                    "getBearing" -> config.bearing
                    "getTime" -> System.currentTimeMillis()
                    "getElapsedRealtime" -> SystemClock.elapsedRealtime()
                    "getProvider", "getSourceProvider" -> "gps"
                    "getCoordinateType" -> TENCENT_COORDINATE_TYPE_GCJ02
                    "getFakeReason", "getFake" -> zeroByReturnType(retType)
                    "isMockGps" -> zeroByReturnType(retType)
                    "getAddress", "getNation", "getProvince", "getCity", "getDistrict",
                    "getTown", "getVillage", "getStreet", "getStreetNo", "getName",
                    "getCityCode", "getCityPhoneCode" -> ""
                    "getPoiList" -> emptyList<Any>()
                    "getExtra" -> Bundle()
                    "toString" -> "TencentLocation[lat=${gcjPoint.latitude},lon=${gcjPoint.longitude}]"
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "equals" -> proxyObj === args?.firstOrNull()
                    else -> {
                        if (original != null && method.declaringClass.isInstance(original)) {
                            runCatching {
                                if (args == null || args.isEmpty()) method.invoke(original) else method.invoke(original, *args)
                            }.getOrElse { defaultByReturnType(retType) }
                        } else {
                            defaultByReturnType(retType)
                        }
                    }
                }
            }
        )
    }

    // ==================== 2. 高德定位 (GCJ-02) ====================

    private fun installAmapHook(classLoader: ClassLoader, targetPackage: String) {
        val clientCls = backend.findClassIfExists(
            "com.amap.api.location.AMapLocationClient",
            classLoader
        )
        if (clientCls != null) {
            val sdkKey = "amap#${System.identityHashCode(clientCls)}#$targetPackage"
            if (installedSdks.add(sdkKey)) {
                backend.hookAllMethods(
                    clientCls,
                    "setLocationListener",
                    before = MethodHookHandler { inv ->
                        val listener = inv.args.firstOrNull() ?: return@MethodHookHandler
                        val implClass = listener.javaClass
                        if (implClass.isInterface || implClass.name == "com.amap.api.location.AMapLocationListener") {
                            return@MethodHookHandler
                        }
                        HookMethodUtils.hookConcreteMethodHierarchy(
                            backend = backend,
                            hookedMethodKeys = hookedConcreteClasses,
                            implClass = implClass,
                            methodName = "onLocationChanged",
                            before = MethodHookHandler { cbInv ->
                                val cfg = configProvider.currentConfig()
                                if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                                val locObj = cbInv.args.firstOrNull() ?: return@MethodHookHandler
                                sanitizeAmapLocation(locObj, cfg)
                            }
                        )
                    }
                )

                backend.hookAllMethods(
                    clientCls,
                    "getLastKnownLocation",
                    after = MethodHookHandler { inv ->
                        val cfg = configProvider.currentConfig()
                        if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                        val res = inv.result
                        if (res != null) {
                            sanitizeAmapLocation(res, cfg)
                        } else {
                            val amapCls = backend.findClassIfExists("com.amap.api.location.AMapLocation", classLoader)
                            val created = amapCls?.let { cls ->
                                runCatching { cls.getConstructor(String::class.java).newInstance("gps") }.getOrNull()
                            }
                            if (created != null) {
                                sanitizeAmapLocation(created, cfg)
                                inv.result = created
                            }
                        }
                    }
                )
            }
        }

        val qualityCls = backend.findClassIfExists(
            "com.amap.api.location.AMapLocationQualityReport",
            classLoader
        )
        if (qualityCls != null) {
            val qKey = "amap_quality#${System.identityHashCode(qualityCls)}#$targetPackage"
            if (installedSdks.add(qKey)) {
                backend.hookAllMethods(
                    qualityCls,
                    "isInstalledHighDangerMockApp",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) {
                            inv.result = false
                        }
                    }
                )
                backend.hookAllMethods(
                    qualityCls,
                    "getGPSStatus",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) {
                            inv.result = 0
                        }
                    }
                )
                backend.hookAllMethods(
                    qualityCls,
                    "getGPSSatellites",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) {
                            inv.result = 12
                        }
                    }
                )
            }
        }
    }

    internal fun sanitizeAmapLocation(locObj: Any, config: MockConfig) {
        val gcjPoint = resolveSdkPoint(config, CoordinateType.GCJ02)
        if (locObj is Location) {
            MockLocationCleaner.cleanLocation(locObj)
            runCatching {
                locObj.latitude = gcjPoint.latitude
                locObj.longitude = gcjPoint.longitude
                locObj.altitude = config.altitude
                locObj.accuracy = config.accuracy
                locObj.speed = config.speed
                locObj.bearing = config.bearing
                locObj.time = System.currentTimeMillis()
                locObj.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                locObj.provider = "gps"
            }
        } else {
            MockLocationCleaner.cleanHiddenFields(locObj)
            invokeMethodIfExists(locObj, listOf("setLatitude"), arrayOf(Double::class.javaPrimitiveType!!), gcjPoint.latitude)
            invokeMethodIfExists(locObj, listOf("setLongitude"), arrayOf(Double::class.javaPrimitiveType!!), gcjPoint.longitude)
            invokeMethodIfExists(locObj, listOf("setAltitude"), arrayOf(Double::class.javaPrimitiveType!!), config.altitude)
            invokeMethodIfExists(locObj, listOf("setAccuracy"), arrayOf(Float::class.javaPrimitiveType!!), config.accuracy)
            invokeMethodIfExists(locObj, listOf("setSpeed"), arrayOf(Float::class.javaPrimitiveType!!), config.speed)
            invokeMethodIfExists(locObj, listOf("setBearing"), arrayOf(Float::class.javaPrimitiveType!!), config.bearing)
            invokeMethodIfExists(locObj, listOf("setTime"), arrayOf(Long::class.javaPrimitiveType!!), System.currentTimeMillis())
            invokeMethodIfExists(locObj, listOf("setProvider"), arrayOf(String::class.java), "gps")
        }

        invokeMethodIfExists(locObj, listOf("setErrorCode"), arrayOf(Int::class.javaPrimitiveType!!), 0)
        invokeMethodIfExists(locObj, listOf("setErrorInfo"), arrayOf(String::class.java), "success")
        invokeMethodIfExists(locObj, listOf("setLocationType"), arrayOf(Int::class.javaPrimitiveType!!), 1)
        invokeMethodIfExists(locObj, listOf("setCoordType"), arrayOf(String::class.java), "GCJ02")
        invokeMethodIfExists(locObj, listOf("setMock"), arrayOf(Boolean::class.javaPrimitiveType!!), false)
        invokeMethodIfExists(locObj, listOf("setTrustedLevel"), arrayOf(Int::class.javaPrimitiveType!!), 1)
        setFieldIfExists(locObj, listOf("trustedLevel", "mTrustedLevel"), 1)

        val emptyStringSetterNames = listOf(
            "setAddress", "setCountry", "setProvince", "setCity", "setDistrict",
            "setStreet", "setStreetNum", "setAoiName", "setPoiName", "setAdCode",
            "setCityCode", "setBuildingId", "setFloor", "setDescription", "setRoad",
            "setLocationDetail"
        )
        for (setter in emptyStringSetterNames) {
            invokeMethodIfExists(locObj, listOf(setter), arrayOf(String::class.java), "")
        }
    }

    // ==================== 3. 百度定位 (BD-09) ====================

    private fun installBaiduHook(classLoader: ClassLoader, targetPackage: String) {
        val clientCls = backend.findClassIfExists("com.baidu.location.LocationClient", classLoader)
        if (clientCls != null) {
            val sdkKey = "baidu#${System.identityHashCode(clientCls)}#$targetPackage"
            if (installedSdks.add(sdkKey)) {
                backend.hookAllMethods(
                    clientCls,
                    "setLocOption",
                    before = MethodHookHandler { inv ->
                        val client = inv.thisObject ?: return@MethodHookHandler
                        val option = inv.args.firstOrNull() ?: return@MethodHookHandler
                        val coorType = extractBaiduOptionCoorType(option) ?: return@MethodHookHandler
                        synchronized(proxyLock) {
                            baiduClientCoorTypes[client] = coorType
                            val refs = baiduClientListeners[client]
                            if (refs != null) {
                                val iter = refs.iterator()
                                while (iter.hasNext()) {
                                    val listener = iter.next().get()
                                    if (listener == null) {
                                        iter.remove()
                                    } else {
                                        baiduListenerCoorTypes[listener] = coorType
                                    }
                                }
                            }
                        }
                    }
                )

                val registerBefore = MethodHookHandler { inv ->
                    val listener = inv.args.firstOrNull() ?: return@MethodHookHandler
                    val client = inv.thisObject
                    if (client != null) {
                        synchronized(proxyLock) {
                            val refs = baiduClientListeners.getOrPut(client) { mutableListOf() }
                            refs.removeAll { it.get() == null || it.get() === listener }
                            refs.add(WeakReference(listener))
                            val clientCoor = baiduClientCoorTypes[client]
                            if (clientCoor != null) {
                                baiduListenerCoorTypes[listener] = clientCoor
                            }
                        }
                    }
                    val implClass = listener.javaClass
                    if (implClass.isInterface ||
                        implClass.name == "com.baidu.location.BDLocationListener" ||
                        implClass.name == "com.baidu.location.BDAbstractLocationListener"
                    ) {
                        return@MethodHookHandler
                    }
                    HookMethodUtils.hookConcreteMethodHierarchy(
                        backend = backend,
                        hookedMethodKeys = hookedConcreteClasses,
                        implClass = implClass,
                        methodName = "onReceiveLocation",
                        before = MethodHookHandler { cbInv ->
                            val cfg = configProvider.currentConfig()
                            if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                            val bdLoc = cbInv.args.firstOrNull() ?: return@MethodHookHandler
                            val listenerObj = cbInv.thisObject
                            val requestedCoorType = if (listenerObj != null) {
                                synchronized(proxyLock) { baiduListenerCoorTypes[listenerObj] }
                            } else {
                                null
                            }
                            sanitizeBaiduLocation(bdLoc, cfg, requestedCoorType)
                        }
                    )
                }

                backend.hookAllMethods(clientCls, "registerLocationListener", before = registerBefore)
                backend.hookAllMethods(clientCls, "registerNotifyLocationListener", before = registerBefore)

                backend.hookAllMethods(
                    clientCls,
                    "getLastKnownLocation",
                    after = MethodHookHandler { inv ->
                        val cfg = configProvider.currentConfig()
                        if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                        val res = inv.result ?: return@MethodHookHandler
                        val client = inv.thisObject
                        val requestedCoorType = if (client != null) {
                            synchronized(proxyLock) { baiduClientCoorTypes[client] }
                        } else {
                            null
                        }
                        sanitizeBaiduLocation(res, cfg, requestedCoorType)
                    }
                )
            }
        }

        val bdLocCls = backend.findClassIfExists("com.baidu.location.BDLocation", classLoader)
        if (bdLocCls != null && !bdLocCls.isInterface) {
            val bdKey = "bdloc#${System.identityHashCode(bdLocCls)}#$targetPackage"
            if (installedSdks.add(bdKey)) {
                backend.hookAllMethods(
                    bdLocCls,
                    "isMockGps",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) inv.result = false
                    }
                )
                backend.hookAllMethods(
                    bdLocCls,
                    "getMockGpsStrategy",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) inv.result = 0
                    }
                )
                backend.hookAllMethods(
                    bdLocCls,
                    "getMockGpsProbability",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) inv.result = 0
                    }
                )
                backend.hookAllMethods(
                    bdLocCls,
                    "getDisToRealLocation",
                    after = MethodHookHandler { inv ->
                        if (configProvider.isActiveFor(targetPackage)) inv.result = 0.0f
                    }
                )
            }
        }
    }

    private fun extractBaiduOptionCoorType(option: Any): String? {
        val fromMethod = runCatching {
            val method = option.javaClass.getMethod("getCoorType")
            (method.invoke(option) as? String)?.trim()
        }.getOrNull()
        if (!fromMethod.isNullOrBlank()) return fromMethod

        var cursor: Class<*>? = option.javaClass
        while (cursor != null && cursor !== Any::class.java) {
            for (fieldName in listOf("coorType", "mCoorType")) {
                val value = runCatching {
                    val f = cursor.getDeclaredField(fieldName)
                    f.isAccessible = true
                    (f.get(option) as? String)?.trim()
                }.getOrNull()
                if (!value.isNullOrBlank()) return value
            }
            cursor = cursor.superclass
        }
        return null
    }

    internal fun sanitizeBaiduLocation(
        bdLocObj: Any,
        config: MockConfig,
        requestedCoorType: String? = null
    ) {
        val rawCoor = requestedCoorType?.trim()?.takeIf { it.isNotEmpty() } ?: "bd09ll"

        val normalizedCoor = rawCoor.lowercase(Locale.US)
        val (targetCoordType, outputCoorLabel) = when (normalizedCoor) {
            "gcj02" -> CoordinateType.GCJ02 to "gcj02"
            "wgs84" -> CoordinateType.WGS84 to "wgs84"
            "bd09" -> CoordinateType.BD09 to "bd09"
            else -> CoordinateType.BD09 to "bd09ll"
        }

        val resolvedPoint = resolveSdkPoint(config, targetCoordType)
        invokeMethodIfExists(bdLocObj, listOf("setLatitude"), arrayOf(Double::class.javaPrimitiveType!!), resolvedPoint.latitude)
        invokeMethodIfExists(bdLocObj, listOf("setLongitude"), arrayOf(Double::class.javaPrimitiveType!!), resolvedPoint.longitude)
        invokeMethodIfExists(bdLocObj, listOf("setCoorType"), arrayOf(String::class.java), outputCoorLabel)
        invokeMethodIfExists(bdLocObj, listOf("setAltitude"), arrayOf(Double::class.javaPrimitiveType!!), config.altitude)
        invokeMethodIfExists(bdLocObj, listOf("setRadius"), arrayOf(Float::class.javaPrimitiveType!!), config.accuracy)
        invokeMethodIfExists(bdLocObj, listOf("setSpeed"), arrayOf(Float::class.javaPrimitiveType!!), config.speed)
        invokeMethodIfExists(bdLocObj, listOf("setDirection"), arrayOf(Float::class.javaPrimitiveType!!), config.bearing)
        invokeMethodIfExists(bdLocObj, listOf("setLocType"), arrayOf(Int::class.javaPrimitiveType!!), 61)
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        invokeMethodIfExists(bdLocObj, listOf("setTime"), arrayOf(String::class.java), formattedTime)

        invokeMethodIfExists(bdLocObj, listOf("setMockGpsStrategy"), arrayOf(Int::class.javaPrimitiveType!!), 0)
        invokeMethodIfExists(bdLocObj, listOf("setMockGpsProbability"), arrayOf(Int::class.javaPrimitiveType!!), 0)
        setFieldIfExists(bdLocObj, listOf("mMockGpsStrategy", "mockGpsStrategy"), 0)
        setFieldIfExists(bdLocObj, listOf("mMockGpsProbability", "mockGpsProbability"), 0)

        val clearStrSetters = listOf(
            "setAddrStr", "setProvince", "setCity", "setDistrict",
            "setStreet", "setStreetNumber", "setCityCode", "setAdCode", "setLocationDescribe"
        )
        for (setter in clearStrSetters) {
            invokeMethodIfExists(bdLocObj, listOf(setter), arrayOf(String::class.java), "")
        }
        invokeMethodIfExists(bdLocObj, listOf("setPoiList"), arrayOf(List::class.java), emptyList<Any>())
    }

    // ==================== 4. GMS Fused (WGS-84) ====================

    private fun installGmsFusedHook(classLoader: ClassLoader, targetPackage: String) {
        val locServicesCls = backend.findClassIfExists(
            "com.google.android.gms.location.LocationServices",
            classLoader
        )
        if (locServicesCls != null) {
            val gmsKey = "gms_services#${System.identityHashCode(locServicesCls)}#$targetPackage"
            if (installedSdks.add(gmsKey)) {
                backend.hookAllMethods(
                    locServicesCls,
                    "getFusedLocationProviderClient",
                    after = MethodHookHandler { inv ->
                        val client = inv.result ?: return@MethodHookHandler
                        val clientClass = client.javaClass
                        if (!clientClass.isInterface) {
                            hookGmsClientClass(clientClass, classLoader, targetPackage)
                        }
                    }
                )
            }
        }

        val fusedClientCls = backend.findClassIfExists(
            "com.google.android.gms.location.FusedLocationProviderClient",
            classLoader
        )
        if (fusedClientCls != null && !fusedClientCls.isInterface) {
            hookGmsClientClass(fusedClientCls, classLoader, targetPackage)
        }
    }

    private fun hookGmsClientClass(
        clientClass: Class<*>,
        classLoader: ClassLoader,
        targetPackage: String
    ) {
        val clientKey = "${System.identityHashCode(clientClass)}#${clientClass.name}#gmsClient"
        if (!hookedConcreteClasses.add(clientKey)) return

        val locCallbackBase = backend.findClassIfExists(
            "com.google.android.gms.location.LocationCallback",
            classLoader
        )
        val gmsListenerIface = backend.findClassIfExists(
            "com.google.android.gms.location.LocationListener",
            classLoader
        )

        backend.hookAllMethods(
            clientClass,
            "requestLocationUpdates",
            before = MethodHookHandler { inv ->
                for (i in inv.args.indices) {
                    val arg = inv.args[i] ?: continue
                    val isCallback = (locCallbackBase != null && locCallbackBase.isInstance(arg)) ||
                        hasMethod(arg.javaClass, "onLocationResult")
                    if (isCallback) {
                        val implClass = arg.javaClass
                        if (!implClass.isInterface &&
                            implClass.name != "com.google.android.gms.location.LocationCallback"
                        ) {
                            HookMethodUtils.hookConcreteMethodHierarchy(
                                backend = backend,
                                hookedMethodKeys = hookedConcreteClasses,
                                implClass = implClass,
                                methodName = "onLocationResult",
                                before = MethodHookHandler { cbInv ->
                                    val cfg = configProvider.currentConfig()
                                    if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                                    val resultObj = cbInv.args.firstOrNull() ?: return@MethodHookHandler
                                    sanitizeGmsLocationResult(resultObj, cfg)
                                }
                            )
                            HookMethodUtils.hookConcreteMethodHierarchy(
                                backend = backend,
                                hookedMethodKeys = hookedConcreteClasses,
                                implClass = implClass,
                                methodName = "onLocationAvailability",
                                before = MethodHookHandler { cbInv ->
                                    val cfg = configProvider.currentConfig()
                                    if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                                    val avail = cbInv.args.firstOrNull() ?: return@MethodHookHandler
                                    setBooleanFieldIfExists(avail, listOf("zza", "isLocationAvailable", "locationAvailable"), true)
                                }
                            )
                        }
                        continue
                    }

                    val iface = when {
                        gmsListenerIface != null && gmsListenerIface.isInterface && gmsListenerIface.isInstance(arg) -> gmsListenerIface
                        else -> arg.javaClass.interfaces.firstOrNull {
                            it.name == "com.google.android.gms.location.LocationListener"
                        }
                    }
                    if (iface != null) {
                        inv.args[i] = getOrCreateGmsListenerProxy(arg, iface, classLoader, targetPackage)
                    } else if (hasMethod(arg.javaClass, "onLocationChanged")) {
                        val implClass = arg.javaClass
                        if (!implClass.isInterface) {
                            HookMethodUtils.hookConcreteMethodHierarchy(
                                backend = backend,
                                hookedMethodKeys = hookedConcreteClasses,
                                implClass = implClass,
                                methodName = "onLocationChanged",
                                before = MethodHookHandler { cbInv ->
                                    val cfg = configProvider.currentConfig()
                                    if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                                    val loc = cbInv.args.firstOrNull() as? Location ?: return@MethodHookHandler
                                    sanitizeWgs84AndroidLocation(loc, cfg)
                                }
                            )
                        }
                    }
                }
            }
        )

        backend.hookAllMethods(
            clientClass,
            "removeLocationUpdates",
            before = MethodHookHandler { inv ->
                for (i in inv.args.indices) {
                    val arg = inv.args[i] ?: continue
                    val mapped = synchronized(proxyLock) {
                        gmsListenerProxies.remove(arg)?.get()
                    }
                    if (mapped != null) {
                        inv.args[i] = mapped
                    }
                }
            }
        )

        val taskAfter = MethodHookHandler { inv ->
            val taskObj = inv.result ?: return@MethodHookHandler
            val taskClass = taskObj.javaClass
            if (!taskClass.isInterface &&
                hookedConcreteClasses.add("${System.identityHashCode(taskClass)}#${taskClass.name}#taskHooks")
            ) {
                HookMethodUtils.hookConcreteMethodHierarchy(
                    backend = backend,
                    hookedMethodKeys = hookedConcreteClasses,
                    implClass = taskClass,
                    methodName = "getResult",
                    after = MethodHookHandler { resInv ->
                        val cfg = configProvider.currentConfig()
                        if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                        val loc = resInv.result as? Location ?: return@MethodHookHandler
                        sanitizeWgs84AndroidLocation(loc, cfg)
                    }
                )
                HookMethodUtils.hookConcreteMethodHierarchy(
                    backend = backend,
                    hookedMethodKeys = hookedConcreteClasses,
                    implClass = taskClass,
                    methodName = "addOnSuccessListener",
                    before = MethodHookHandler { addInv ->
                        val listener = addInv.args.lastOrNull { it != null && hasMethod(it.javaClass, "onSuccess") }
                            ?: return@MethodHookHandler
                        val implClass = listener.javaClass
                        if (!implClass.isInterface) {
                            HookMethodUtils.hookConcreteMethodHierarchy(
                                backend = backend,
                                hookedMethodKeys = hookedConcreteClasses,
                                implClass = implClass,
                                methodName = "onSuccess",
                                before = MethodHookHandler { sInv ->
                                    val cfg = configProvider.currentConfig()
                                    if (!cfg.isEnabledForPackage(targetPackage)) return@MethodHookHandler
                                    val loc = sInv.args.firstOrNull() as? Location ?: return@MethodHookHandler
                                    sanitizeWgs84AndroidLocation(loc, cfg)
                                }
                            )
                        }
                    }
                )
            }
        }
        backend.hookAllMethods(clientClass, "getLastLocation", after = taskAfter)
        backend.hookAllMethods(clientClass, "getCurrentLocation", after = taskAfter)
    }

    private fun getOrCreateGmsListenerProxy(
        original: Any,
        listenerInterface: Class<*>,
        classLoader: ClassLoader,
        targetPackage: String
    ): Any {
        synchronized(proxyLock) {
            gmsListenerProxies[original]?.get()?.let { return it }
            val loader = original.javaClass.classLoader ?: classLoader
            val origRef = WeakReference(original)
            val proxy = Proxy.newProxyInstance(
                loader,
                arrayOf(listenerInterface),
                InvocationHandler { proxyObj, method, args ->
                    val orig = origRef.get()
                    when (method.name) {
                        "equals" -> {
                            val other = args?.firstOrNull()
                            return@InvocationHandler other === proxyObj || (orig != null && other === orig)
                        }
                        "hashCode" -> {
                            return@InvocationHandler if (orig != null) {
                                System.identityHashCode(orig)
                            } else {
                                System.identityHashCode(proxyObj)
                            }
                        }
                        "toString" -> {
                            return@InvocationHandler orig?.toString() ?: "MockLocGmsListenerProxy(recycled)"
                        }
                        "onLocationChanged" -> {
                            val cfg = configProvider.currentConfig()
                            if (cfg.isEnabledForPackage(targetPackage) && args != null && args.isNotEmpty()) {
                                val loc = args[0] as? Location
                                if (loc != null) {
                                    sanitizeWgs84AndroidLocation(loc, cfg)
                                }
                            }
                        }
                    }
                    if (orig == null) return@InvocationHandler null
                    if (args == null || args.isEmpty()) method.invoke(orig) else method.invoke(orig, *args)
                }
            )
            gmsListenerProxies[original] = WeakReference(proxy)
            return proxy
        }
    }

    internal fun sanitizeGmsLocationResult(resultObj: Any, config: MockConfig) {
        val listFromGetter = runCatching {
            resultObj.javaClass.getMethod("getLocations").invoke(resultObj) as? List<*>
        }.getOrNull()

        val listFromField = listFromGetter ?: listOf("locations", "zzb", "zza").firstNotNullOfOrNull { fname ->
            runCatching {
                val f = resultObj.javaClass.getDeclaredField(fname)
                f.isAccessible = true
                f.get(resultObj) as? List<*>
            }.getOrNull()
        }

        if (listFromField != null) {
            for (item in listFromField) {
                if (item is Location) {
                    sanitizeWgs84AndroidLocation(item, config)
                }
            }
        }

        val lastLoc = runCatching {
            resultObj.javaClass.getMethod("getLastLocation").invoke(resultObj) as? Location
        }.getOrNull()
        if (lastLoc != null) {
            sanitizeWgs84AndroidLocation(lastLoc, config)
        }
    }

    private fun sanitizeWgs84AndroidLocation(loc: Location, config: MockConfig) {
        val wgsPoint = resolveSdkPoint(config, CoordinateType.WGS84)
        MockLocationCleaner.cleanLocation(loc)
        runCatching {
            loc.latitude = wgsPoint.latitude
            loc.longitude = wgsPoint.longitude
            loc.altitude = config.altitude
            loc.accuracy = config.accuracy
            loc.speed = config.speed
            loc.bearing = config.bearing
            loc.time = System.currentTimeMillis()
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            loc.provider = "gps"
        }
    }

    // ==================== 通用反射工具 ====================

    private fun hasMethod(clazz: Class<*>, methodName: String): Boolean {
        return runCatching {
            clazz.methods.any { it.name == methodName }
        }.getOrDefault(false)
    }

    private fun invokeMethodIfExists(
        target: Any,
        names: List<String>,
        paramTypes: Array<Class<*>>,
        vararg args: Any?
    ): Boolean {
        for (name in names) {
            try {
                val method = target.javaClass.getMethod(name, *paramTypes)
                method.invoke(target, *args)
                return true
            } catch (_: Throwable) {
            }
            try {
                val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
                method.isAccessible = true
                method.invoke(target, *args)
                return true
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun setBooleanFieldIfExists(target: Any, names: List<String>, value: Boolean): Boolean {
        for (name in names) {
            var cursor: Class<*>? = target.javaClass
            while (cursor != null && cursor !== Any::class.java) {
                try {
                    val field = cursor.getDeclaredField(name)
                    if (field.type == Boolean::class.javaPrimitiveType ||
                        field.type == java.lang.Boolean::class.java
                    ) {
                        field.isAccessible = true
                        field.set(target, value)
                        return true
                    }
                    break
                } catch (_: Throwable) {
                    cursor = cursor.superclass
                }
            }
        }
        return false
    }

    private fun setStringFieldIfExists(target: Any, names: List<String>, value: String): Boolean {
        for (name in names) {
            var cursor: Class<*>? = target.javaClass
            while (cursor != null && cursor !== Any::class.java) {
                try {
                    val field = cursor.getDeclaredField(name)
                    if (field.type == String::class.java) {
                        field.isAccessible = true
                        field.set(target, value)
                        return true
                    }
                    break
                } catch (_: Throwable) {
                    cursor = cursor.superclass
                }
            }
        }
        return false
    }

    private fun setFieldIfExists(target: Any, names: List<String>, value: Any?): Boolean {
        for (name in names) {
            var cursor: Class<*>? = target.javaClass
            while (cursor != null && cursor !== Any::class.java) {
                try {
                    val field = cursor.getDeclaredField(name)
                    field.isAccessible = true
                    field.set(target, value)
                    return true
                } catch (_: Throwable) {
                    cursor = cursor.superclass
                }
            }
        }
        return false
    }

    private fun zeroByReturnType(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0.0f
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
            else -> 0
        }
    }

    private fun defaultByReturnType(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0.0f
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
            String::class.java -> ""
            List::class.java -> emptyList<Any>()
            else -> null
        }
    }

    fun clearForTest() {
        installedSdks.clear()
        hookedConcreteClasses.clear()
        synchronized(proxyLock) {
            tencentListenerProxies.clear()
            gmsListenerProxies.clear()
        }
    }

    companion object {
        private const val TENCENT_COORDINATE_TYPE_GCJ02 = 1

        val shared: ThirdPartyLbsSdkHook = ThirdPartyLbsSdkHook()
    }
}
