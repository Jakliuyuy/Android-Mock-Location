package com.mockloc.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.mockloc.data.MockConfigManager
import com.mockloc.ipc.LocationConfigProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed / LSPosed 模块主入口点。
 * 实现 IXposedHookLoadPackage，在目标进程被加载时注入定位拦截与防检测 Hook，
 * 并通过 ContentProvider + 动态广播建立跨进程配置与巡航坐标同步机制。
 */
class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName ?: return

        // 过滤系统底层服务与自身主控进程
        if (packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.mockloc"
        ) {
            return
        }

        MockConfigManager.currentProcessPackage = packageName
        XposedBridge.log("[MockLoc] Injecting hooks into target package: $packageName")

        // 1. 初始化定位核心拦截引擎与监听器代理
        LocationHook.initHooks(lpparam.classLoader)

        // 2. 初始化基站与 WiFi 反检测拦截引擎
        NetworkAntiDetectHook.initHooks(lpparam.classLoader)

        // 3. 挂钩 Application.attachBaseContext 获取 Context，在最早生命周期拉取跨进程配置
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = (param.args[0] as? Context) ?: (param.thisObject as? Context) ?: return
                        initIpcSync(ctx, packageName)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Hook Application.attachBaseContext failed: ${t.message}")
        }

        // 4. 挂钩 Application.onCreate 获取 Context 作为生命周期双重保障
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        initIpcSync(app, packageName)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Hook Application.onCreate failed: ${t.message}")
        }
    }

    private val isReceiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 建立跨进程配置拉取与实时广播同步管道
     */
    private fun initIpcSync(context: Context, packageName: String) {
        // 冷启动从 ContentProvider 拉取当前配置
        try {
            val bundle = LocationConfigProvider.fetchConfig(context, packageName)
            if (bundle != null) {
                MockConfigManager.updateFromBundle(bundle)
                XposedBridge.log("[MockLoc] Initial config synced for $packageName, enabled=${MockConfigManager.isMockEnabled}")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[MockLoc] Initial config sync failed: ${t.message}")
        }

        // 注册动态广播接收器以实现实时巡航与坐标同步 (仅注册一次)
        if (isReceiverRegistered.compareAndSet(false, true)) {
            try {
                val filter = IntentFilter(MockConfigManager.ACTION_CONFIG_CHANGED)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != MockConfigManager.ACTION_CONFIG_CHANGED) return
                        val extras = intent.extras ?: return
                        MockConfigManager.updateFromBundle(extras)
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                XposedBridge.log("[MockLoc] Dynamic broadcast receiver registered for $packageName")
            } catch (t: Throwable) {
                isReceiverRegistered.set(false)
                XposedBridge.log("[MockLoc] Register broadcast receiver failed: ${t.message}")
            }
        }
    }
}
