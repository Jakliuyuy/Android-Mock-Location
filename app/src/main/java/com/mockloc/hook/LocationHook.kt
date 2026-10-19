package com.mockloc.hook

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import com.mockloc.data.MockConfigManager
import com.mockloc.ipc.LocationConfigProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 核心定位服务与监听器拦截引擎 (Core Location Interception Hook)
 * 拦截 LocationManager 的单次/连续定位、最佳 Provider 查询、当前位置 API，
 * 并通过动态代理全面拦截 requestLocationUpdates / requestSingleUpdate 传递的 LocationListener，
 * 彻底透明篡改目标进程的定位数据。
 */
class LocationHook {

    companion object {

        private val listenerProxyMap = Collections.synchronizedMap(WeakHashMap<LocationListener, LocationListener>())

        @JvmStatic
        fun initHooks(classLoader: ClassLoader) {
            hookLocationMockMethods()
            hookLocationManagerGetters()
            hookGetCurrentLocation(classLoader)
            hookRequestLocationUpdates(classLoader)
            hookLocationListeners(classLoader)
        }

        /**
         * 跨进程配置惰性同步兜底：
         * 针对在 Application 生命周期前便调用 LocationManager 的场景，
         * 通过反射提取 LocationManager.mContext 触发早期配置同步。
         */
        @JvmStatic
        fun checkAndTriggerLazySync(target: Any?) {
            if (MockConfigManager.isConfigSynced) return
            val context = try {
                if (target is Context) {
                    target
                } else if (target != null) {
                    try {
                        XposedHelpers.getObjectField(target, "mContext") as? Context
                    } catch (_: Throwable) {
                        try {
                            val f = target.javaClass.getDeclaredField("mContext")
                            f.isAccessible = true
                            f.get(target) as? Context
                        } catch (_: Throwable) {
                            null
                        }
                    }
                } else null
            } catch (_: Throwable) {
                null
            }
            if (context != null) {
                try {
                    val pkg = MockConfigManager.currentProcessPackage ?: context.packageName
                    val bundle = LocationConfigProvider.fetchConfig(context, pkg)
                    if (bundle != null) {
                        MockConfigManager.updateFromBundle(bundle)
                        try {
                            XposedBridge.log("[MockLoc] Lazy config synced for $pkg, enabled=${MockConfigManager.isMockEnabled}")
                        } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    try {
                        XposedBridge.log("[MockLoc] Lazy config sync failed: ${t.message}")
                    } catch (_: Throwable) {}
                }
            }
        }

        /**
         * 1. 深度拦截 Location.isFromMockProvider() 与 isMock()
         * 确保调用任何 Location 对象的此方法均强制返回 false，同时受应用级白名单保护
         */
        private fun hookLocationMockMethods() {
            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "isFromMockProvider",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            param.result = false
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook Location.isFromMockProvider failed: ${t.message}")
            }

            try {
                XposedHelpers.findAndHookMethod(
                    Location::class.java,
                    "isMock",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            param.result = false
                        }
                    }
                )
            } catch (_: Throwable) {
                // API < 31 可能不存在 isMock()
            }
        }

        /**
         * 2. 拦截 LocationManager 基础状态获取与缓存定位
         */
        private fun hookLocationManagerGetters() {
            // getLastKnownLocation(String)
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getLastKnownLocation",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val provider = (param.args[0] as? String) ?: LocationManager.GPS_PROVIDER
                            param.result = MockConfigManager.createMockLocation(provider)
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook getLastKnownLocation failed: ${t.message}")
            }

            // isProviderEnabled(String)
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "isProviderEnabled",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val provider = param.args[0] as? String
                            if (LocationManager.GPS_PROVIDER == provider ||
                                LocationManager.NETWORK_PROVIDER == provider
                            ) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook isProviderEnabled failed: ${t.message}")
            }

            // getBestProvider(Criteria, boolean)
            try {
                XposedHelpers.findAndHookMethod(
                    LocationManager::class.java,
                    "getBestProvider",
                    Criteria::class.java,
                    Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            param.result = LocationManager.GPS_PROVIDER
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook getBestProvider failed: ${t.message}")
            }
        }

        /**
         * 3. 拦截 LocationManager.getCurrentLocation (Android 11+ / API 30+)
         * 动态代理 java.util.function.Consumer 回调
         */
        private fun hookGetCurrentLocation(classLoader: ClassLoader) {
            try {
                val consumerClass = Class.forName("java.util.function.Consumer", false, classLoader)
                XposedBridge.hookAllMethods(
                    LocationManager::class.java,
                    "getCurrentLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val args = param.args ?: return
                            for (i in args.indices) {
                                val arg = args[i] ?: continue
                                if (consumerClass.isInstance(arg)) {
                                    val originalConsumer = arg
                                    val proxyConsumer = Proxy.newProxyInstance(
                                        consumerClass.classLoader ?: classLoader,
                                        arrayOf(consumerClass),
                                        object : InvocationHandler {
                                            override fun invoke(proxy: Any?, method: Method, mArgs: Array<out Any>?): Any? {
                                                if (method.name == "accept" && mArgs != null && mArgs.isNotEmpty()) {
                                                    val modifiedArgs = Array<Any?>(mArgs.size) { idx ->
                                                        if (idx == 0) MockConfigManager.createMockLocation(LocationManager.GPS_PROVIDER)
                                                        else mArgs[idx]
                                                     }
                                                    return method.invoke(originalConsumer, *modifiedArgs)
                                                }
                                                return if (mArgs != null) {
                                                    method.invoke(originalConsumer, *mArgs)
                                                } else {
                                                    method.invoke(originalConsumer)
                                                }
                                            }
                                        }
                                    )
                                    args[i] = proxyConsumer
                                    break
                                }
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                // 低版本系统无 java.util.function.Consumer
            }
        }

        /**
         * 4. 核心：动态代理包装 LocationManager.requestLocationUpdates 与 requestSingleUpdate
         * 通过 Proxy 包装目标应用传递的 LocationListener，彻底解决 ART interface 虚方法分发无法拦截具体类的问题。
         */
        private fun hookRequestLocationUpdates(classLoader: ClassLoader) {
            try {
                XposedBridge.hookAllMethods(
                    LocationManager::class.java,
                    "requestLocationUpdates",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val args = param.args ?: return
                            for (i in args.indices) {
                                val arg = args[i] ?: continue
                                if (arg is LocationListener) {
                                    args[i] = getOrCreateProxyListener(arg, classLoader)
                                    break
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook requestLocationUpdates failed: ${t.message}")
            }

            try {
                XposedBridge.hookAllMethods(
                    LocationManager::class.java,
                    "requestSingleUpdate",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            checkAndTriggerLazySync(param.thisObject)
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val args = param.args ?: return
                            for (i in args.indices) {
                                val arg = args[i] ?: continue
                                if (arg is LocationListener) {
                                    args[i] = getOrCreateProxyListener(arg, classLoader)
                                    break
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook requestSingleUpdate failed: ${t.message}")
            }

            // 拦截 removeUpdates，将 originalListener 还原为 proxyListener 进行注销
            try {
                XposedBridge.hookAllMethods(
                    LocationManager::class.java,
                    "removeUpdates",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args ?: return
                            for (i in args.indices) {
                                val arg = args[i] ?: continue
                                if (arg is LocationListener) {
                                    val proxy = listenerProxyMap.remove(arg)
                                    if (proxy != null) {
                                        args[i] = proxy
                                    }
                                    break
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook removeUpdates failed: ${t.message}")
            }
        }

        /**
         * 获取或创建针对 LocationListener 的动态代理
         */
        @JvmStatic
        fun getOrCreateProxyListener(
            originalListener: LocationListener,
            classLoader: ClassLoader
        ): LocationListener {
            if (Proxy.isProxyClass(originalListener.javaClass)) {
                return originalListener
            }
            return listenerProxyMap.getOrPut(originalListener) {
                Proxy.newProxyInstance(
                    LocationListener::class.java.classLoader ?: classLoader,
                    arrayOf(LocationListener::class.java),
                    object : InvocationHandler {
                        override fun invoke(proxy: Any?, method: Method, mArgs: Array<out Any>?): Any? {
                            if (method.name == "onLocationChanged" && mArgs != null && mArgs.isNotEmpty()) {
                                val firstArg = mArgs[0]
                                if (firstArg is Location) {
                                    val provider = firstArg.provider ?: LocationManager.GPS_PROVIDER
                                    val mockLoc = MockConfigManager.createMockLocation(provider)
                                    val newArgs = Array<Any?>(mArgs.size) { idx ->
                                        if (idx == 0) mockLoc else mArgs[idx]
                                    }
                                    return method.invoke(originalListener, *newArgs)
                                } else if (firstArg is List<*>) {
                                    val mockLoc = MockConfigManager.createMockLocation(LocationManager.GPS_PROVIDER)
                                    val newArgs = Array<Any?>(mArgs.size) { idx ->
                                        if (idx == 0) Collections.singletonList(mockLoc) else mArgs[idx]
                                    }
                                    return method.invoke(originalListener, *newArgs)
                                }
                            }
                            if (method.name == "equals" && mArgs?.size == 1) {
                                val other = mArgs[0]
                                return proxy === other || originalListener == other
                            }
                            if (method.name == "hashCode" && (mArgs == null || mArgs.isEmpty())) {
                                return System.identityHashCode(originalListener)
                            }
                            if (method.name == "toString" && (mArgs == null || mArgs.isEmpty())) {
                                return "MockLocationListenerProxy for $originalListener"
                            }
                            return if (mArgs != null) {
                                method.invoke(originalListener, *mArgs)
                            } else {
                                method.invoke(originalListener)
                            }
                        }
                    }
                ) as LocationListener
            }
        }

        /**
         * 5. 防御兜底：拦截 LocationListener 接口方法
         */
        private fun hookLocationListeners(classLoader: ClassLoader) {
            try {
                XposedBridge.hookAllMethods(
                    LocationListener::class.java,
                    "onLocationChanged",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            val args = param.args ?: return
                            if (args.isEmpty() || args[0] == null) return

                            val firstArg = args[0]
                            if (firstArg is Location) {
                                val provider = firstArg.provider ?: LocationManager.GPS_PROVIDER
                                args[0] = MockConfigManager.createMockLocation(provider)
                            } else if (firstArg is List<*>) {
                                val mockLoc = MockConfigManager.createMockLocation(LocationManager.GPS_PROVIDER)
                                args[0] = Collections.singletonList(mockLoc)
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook LocationListener.onLocationChanged failed: ${t.message}")
            }
        }
    }
}
