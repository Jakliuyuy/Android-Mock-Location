package com.mockloc.hook

import android.location.Location
import com.mockloc.data.MockConfigManager
import com.mockloc.hook.cleaner.MockLocationCleaner
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 国内主流第三方地图定位 SDK 深度拦截引擎 (Tencent / AMap / Baidu LBS SDK Hook)
 * 专门攻克微信、高德地图、企业微信、美团、百度地图等国内绝大部分使用第三方高阶定位 SDK 的应用。
 */
object ThirdPartyLbsSdkHook {

    @JvmStatic
    fun initHooks(classLoader: ClassLoader) {
        hookTencentLocationSdk(classLoader)
        hookAmapLocationSdk(classLoader)
        hookBaiduLocationSdk(classLoader)
    }

    /**
     * 1. 深度拦截腾讯地图定位 SDK (Tencent Location SDK)
     * 核心针对：微信 (WeChat)、企业微信、QQ、腾讯地图及各类腾讯 LBS 组件
     */
    private fun hookTencentLocationSdk(classLoader: ClassLoader) {
        try {
            val tencentListenerClass = try {
                Class.forName("com.tencent.map.geolocation.TencentLocationListener", false, classLoader)
            } catch (_: ClassNotFoundException) {
                return
            }
            val tencentLocationClass = try {
                Class.forName("com.tencent.map.geolocation.TencentLocation", false, classLoader)
            } catch (_: ClassNotFoundException) {
                return
            }

            // Hook onLocationChanged(TencentLocation location, int error, String reason)
            XposedBridge.hookAllMethods(
                tencentListenerClass,
                "onLocationChanged",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                        val args = param.args ?: return
                        if (args.isEmpty()) return

                        val rawLoc = args[0]
                        if (rawLoc != null && tencentLocationClass.isInstance(rawLoc)) {
                            // 使用动态代理包装 TencentLocation 接口
                            val proxyLocation = Proxy.newProxyInstance(
                                tencentLocationClass.classLoader ?: classLoader,
                                arrayOf(tencentLocationClass),
                                TencentLocationInvocationHandler(rawLoc)
                            )
                            args[0] = proxyLocation
                            // 确保错误码为 0 (成功)
                            if (args.size >= 2) {
                                args[1] = 0
                            }
                        }
                    }
                }
            )

            // Hook TencentLocationManager.getLastKnownLocation()
            try {
                val tencentManagerClass = Class.forName("com.tencent.map.geolocation.TencentLocationManager", false, classLoader)
                XposedBridge.hookAllMethods(
                    tencentManagerClass,
                    "getLastKnownLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val originalResult = param.result
                            if (originalResult != null && tencentLocationClass.isInstance(originalResult)) {
                                param.result = Proxy.newProxyInstance(
                                    tencentLocationClass.classLoader ?: classLoader,
                                    arrayOf(tencentLocationClass),
                                    TencentLocationInvocationHandler(originalResult)
                                )
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            XposedBridge.log("[MockLoc] Successfully installed Tencent Location SDK Hook")
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Hook Tencent Location SDK failed: ${t.message}")
        }
    }

    /**
     * 腾讯定位对象的动态代理处理器，全面拦截坐标、海拔、精度、方位与反检测字段
     */
    private class TencentLocationInvocationHandler(private val original: Any) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            return when (method.name) {
                "getLatitude" -> MockConfigManager.latitude
                "getLongitude" -> MockConfigManager.longitude
                "getAltitude" -> MockConfigManager.altitude
                "getAccuracy" -> MockConfigManager.accuracy
                "getBearing" -> MockConfigManager.bearing
                "getSpeed" -> MockConfigManager.speed
                "getTime" -> System.currentTimeMillis()
                "getElapsedRealtime" -> android.os.SystemClock.elapsedRealtime()
                "getSourceProvider" -> "gps"
                "getProvider" -> "gps"
                "getFake" -> 0 // 0 代表不是模拟位置
                "isMockGps" -> 0
                "getGps" -> 1
                "getCoordinateType" -> 1 // 1: GCJ02, 0: WGS84
                else -> {
                    if (args != null) method.invoke(original, *args)
                    else method.invoke(original)
                }
            }
        }
    }

    /**
     * 2. 深度拦截高德地图定位 SDK (AMap Location SDK)
     * 核心针对：高德地图主 App、各类外卖骑手客户端、打车出行等使用高德定位 SDK 的应用
     */
    private fun hookAmapLocationSdk(classLoader: ClassLoader) {
        try {
            val amapLocationClass = try {
                Class.forName("com.amap.api.location.AMapLocation", false, classLoader)
            } catch (_: ClassNotFoundException) {
                return
            }

            // Hook AMapLocationListener.onLocationChanged(AMapLocation)
            val amapListenerClass = try {
                Class.forName("com.amap.api.location.AMapLocationListener", false, classLoader)
            } catch (_: ClassNotFoundException) {
                null
            }

            if (amapListenerClass != null) {
                XposedBridge.hookAllMethods(
                    amapListenerClass,
                    "onLocationChanged",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val args = param.args ?: return
                            if (args.isEmpty()) return
                            val amapLoc = args[0]
                            if (amapLoc != null && amapLocationClass.isInstance(amapLoc)) {
                                sanitizeAmapLocation(amapLoc)
                            }
                        }
                    }
                )
            }

            // Hook AMapLocationClient.getLastKnownLocation()
            try {
                val amapClientClass = Class.forName("com.amap.api.location.AMapLocationClient", false, classLoader)
                XposedBridge.hookAllMethods(
                    amapClientClass,
                    "getLastKnownLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val result = param.result
                            if (result != null && amapLocationClass.isInstance(result)) {
                                sanitizeAmapLocation(result)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}

            XposedBridge.log("[MockLoc] Successfully installed AMap Location SDK Hook")
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Hook AMap Location SDK failed: ${t.message}")
        }
    }

    /**
     * 深度净化并修改 AMapLocation 的属性
     */
    private fun sanitizeAmapLocation(amapLoc: Any) {
        try {
            XposedHelpers.callMethod(amapLoc, "setLatitude", MockConfigManager.latitude)
            XposedHelpers.callMethod(amapLoc, "setLongitude", MockConfigManager.longitude)
            XposedHelpers.callMethod(amapLoc, "setAltitude", MockConfigManager.altitude)
            XposedHelpers.callMethod(amapLoc, "setSpeed", MockConfigManager.speed)
            XposedHelpers.callMethod(amapLoc, "setBearing", MockConfigManager.bearing)
            XposedHelpers.callMethod(amapLoc, "setAccuracy", MockConfigManager.accuracy)
            XposedHelpers.callMethod(amapLoc, "setTime", System.currentTimeMillis())
            XposedHelpers.callMethod(amapLoc, "setErrorCode", 0)
            XposedHelpers.callMethod(amapLoc, "setLocationType", 1) // 1 = GPS 定位

            if (amapLoc is Location) {
                MockLocationCleaner.sanitize(
                    location = amapLoc,
                    lat = MockConfigManager.latitude,
                    lng = MockConfigManager.longitude,
                    alt = MockConfigManager.altitude,
                    speed = MockConfigManager.speed,
                    bearing = MockConfigManager.bearing,
                    accuracy = MockConfigManager.accuracy
                )
            }
        } catch (_: Throwable) {}
    }

    /**
     * 3. 深度拦截百度地图定位 SDK (Baidu Location SDK)
     * 核心针对：百度地图主 App、各类使用百度定位 SDK 的应用
     */
    private fun hookBaiduLocationSdk(classLoader: ClassLoader) {
        try {
            val bdLocationClass = try {
                Class.forName("com.baidu.location.BDLocation", false, classLoader)
            } catch (_: ClassNotFoundException) {
                return
            }

            val listenerClasses = arrayOf(
                "com.baidu.location.BDAbstractLocationListener",
                "com.baidu.location.BDLocationListener"
            )

            for (listenerName in listenerClasses) {
                try {
                    val lClass = Class.forName(listenerName, false, classLoader)
                    XposedBridge.hookAllMethods(
                        lClass,
                        "onReceiveLocation",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                                val args = param.args ?: return
                                if (args.isEmpty()) return
                                val bdLoc = args[0]
                                if (bdLoc != null && bdLocationClass.isInstance(bdLoc)) {
                                    sanitizeBaiduLocation(bdLoc)
                                }
                            }
                        }
                    )
                } catch (_: Throwable) {}
            }

            XposedBridge.log("[MockLoc] Successfully installed Baidu Location SDK Hook")
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Hook Baidu Location SDK failed: ${t.message}")
        }
    }

    private fun sanitizeBaiduLocation(bdLoc: Any) {
        try {
            XposedHelpers.callMethod(bdLoc, "setLatitude", MockConfigManager.latitude)
            XposedHelpers.callMethod(bdLoc, "setLongitude", MockConfigManager.longitude)
            XposedHelpers.callMethod(bdLoc, "setAltitude", MockConfigManager.altitude)
            XposedHelpers.callMethod(bdLoc, "setSpeed", MockConfigManager.speed)
            XposedHelpers.callMethod(bdLoc, "setRadius", MockConfigManager.accuracy)
            XposedHelpers.callMethod(bdLoc, "setLocType", 61) // 61 代表 GPS 定位成功
        } catch (_: Throwable) {}
    }
}
