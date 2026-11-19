package com.mockloc.hook

import android.location.Location
import com.mockloc.hook.cleaner.MockLocationCleaner
import com.mockloc.root.EnvironmentDetector
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val lp = lpparam ?: return
        val pkg = lp.packageName ?: return
        val cl = lp.classLoader

        handlePackageInternal(
            packageName = pkg,
            classLoader = cl,
            backend = XposedHookBackend,
            configStore = HookConfigStore.shared,
            locationHook = LocationHook.shared,
            networkHook = NetworkAntiDetectHook.shared,
            thirdPartyHook = ThirdPartyLbsSdkHook.shared,
            classLoaderHelper = ClassLoaderHookHelper.shared,
            xposedVersionProvider = ::safeGetXposedVersion
        )
    }

    internal fun handlePackageInternal(
        packageName: String,
        classLoader: ClassLoader?,
        backend: HookBackend,
        configStore: HookConfigStore,
        locationHook: LocationHook,
        networkHook: NetworkAntiDetectHook,
        thirdPartyHook: ThirdPartyLbsSdkHook,
        classLoaderHelper: ClassLoaderHookHelper,
        xposedVersionProvider: () -> Int
    ) {
        if (packageName == HookConfigStore.MOCKLOC_PACKAGE) {
            installSelfCheckHooks(classLoader, backend, xposedVersionProvider)
            return
        }

        if (packageName == SYSTEM_SERVER_PACKAGE) {
            installSystemServerSafeHooks(classLoader, backend, configStore)
            return
        }

        // 预热 L1 XSharedPreferences 配置（非阻塞且无一次性锁死标志）
        runCatching { configStore.getConfig(null, forceRefresh = true) }

        LocationHook.currentTargetPackage = packageName

        runCatching {
            locationHook.installSystemHooks(classLoader, packageName)
        }
        runCatching {
            MockLocationCleaner.installHooks(classLoader, backend) {
                configStore.getConfig(LocationHook.currentAppContext).isEnabledForPackage(packageName)
            }
        }
        runCatching {
            networkHook.install(classLoader, packageName)
        }
        runCatching {
            thirdPartyHook.installForClassLoader(classLoader, packageName)
        }

        classLoaderHelper.install(
            initialClassLoader = classLoader,
            onContextReady = { ctx ->
                LocationHook.currentAppContext = ctx.applicationContext ?: ctx
                runCatching {
                    configStore.registerConfigReceiverOnce(ctx) { updated ->
                        locationHook.onConfigChanged(updated, packageName)
                    }
                }
                runCatching {
                    configStore.getConfig(ctx, forceRefresh = false)
                }
            },
            onClassLoaderReady = { newCl ->
                runCatching {
                    thirdPartyHook.installForClassLoader(newCl, packageName)
                }
                runCatching {
                    networkHook.install(newCl, packageName)
                }
            }
        )
    }

    private fun installSelfCheckHooks(
        classLoader: ClassLoader?,
        backend: HookBackend,
        xposedVersionProvider: () -> Int
    ) {
        runCatching {
            val detectorCls = backend.findClassIfExists(
                EnvironmentDetector::class.java.name,
                classLoader
            ) ?: EnvironmentDetector::class.java

            val apiVersion = xposedVersionProvider()
            backend.replaceMethod(detectorCls, "isModuleActive") { true }
            backend.replaceMethod(detectorCls, "getXposedApiVersion") { apiVersion }
        }
    }

    /**
     * system_server (android) 保护分支：
     * 绝不阻塞 android.fg、绝不注册广播接收器、绝不启动脉冲调度线程、绝不执行同步 IPC。
     * 仅尝试对系统定位服务分发的 Location 做轻量可选清理；未授权或找不到内部类时静默安全降级。
     */
    internal fun installSystemServerSafeHooks(
        classLoader: ClassLoader?,
        backend: HookBackend,
        configStore: HookConfigStore
    ) {
        try {
            // 仅在入口做一次非阻塞、严格 try-catch 的 L1 冷加载，绝不阻塞 android.fg 或注册广播/启动线程
            runCatching { configStore.reloadFromL1(forceReload = true) }

            val lmsCls = backend.findClassIfExists(
                "com.android.server.location.LocationManagerService",
                classLoader
            ) ?: return

            backend.hookAllMethods(
                lmsCls,
                "getLastLocation",
                after = MethodHookHandler { inv ->
                    val cfg = configStore.peekCurrentConfig()
                    if (!cfg.enabled) return@MethodHookHandler
                    val pkgArg = inv.args.firstOrNull { it is String } as? String
                    if (pkgArg != null && cfg.isEnabledForPackage(pkgArg)) {
                        val loc = inv.result as? Location ?: return@MethodHookHandler
                        MockLocationCleaner.cleanLocation(loc)
                    }
                }
            )
        } catch (t: Throwable) {
            backend.log("system_server optional hook skipped safely", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun safeGetXposedVersion(): Int {
        return try {
            val v = XposedBridge.getXposedVersion()
            if (v > 0) v else XposedBridge.XPOSED_BRIDGE_VERSION
        } catch (_: Throwable) {
            93
        }
    }

    companion object {
        const val SYSTEM_SERVER_PACKAGE = "android"
    }
}
