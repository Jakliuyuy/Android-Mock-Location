package com.mockloc.hook

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.CellInfo
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.mockloc.data.MockConfigManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.ArrayList
import java.util.Collections

/**
 * 纯逻辑网络与基站反检测过滤器。
 * 解耦 Xposed 运行时，可在 JVM 单元测试中直接覆盖验证。
 */
object NetworkAntiDetectFilter {

    const val MASKED_BSSID: String = "02:00:00:00:00:00"

    /**
     * 过滤基站列表：若防检测拦截生效，返回空列表，阻断基站指纹泄露
     */
    fun <T> filterCellInfo(cells: List<T>?, isEnabled: Boolean): List<T> {
        return if (isEnabled) {
            emptyList()
        } else {
            cells ?: emptyList()
        }
    }

    /**
     * 过滤 WiFi 扫描热点列表：若防检测拦截生效，返回空列表，阻断周边物理 AP 泄露
     */
    fun <T> filterScanResults(results: List<T>?, isEnabled: Boolean): List<T> {
        return if (isEnabled) {
            emptyList()
        } else {
            results ?: emptyList()
        }
    }

    /**
     * 抹除/伪造 WifiInfo 中的真实物理热点 MAC 地址 (BSSID)
     * 通过反射将 mBSSID 字段重写为标准虚拟掩码 "02:00:00:00:00:00"
     */
    fun sanitizeWifiInfoBssid(wifiInfo: Any?, isEnabled: Boolean): Boolean {
        if (!isEnabled || wifiInfo == null) return false

        var success = false
        var currentClass: Class<*>? = wifiInfo.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (fieldName in arrayOf("mBSSID", "bssid", "mBssid")) {
                try {
                    val field = currentClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(wifiInfo, MASKED_BSSID)
                    success = true
                    break
                } catch (_: NoSuchFieldException) {
                } catch (_: Throwable) {}
            }
            if (success) break
            currentClass = currentClass.superclass
        }
        return success
    }

    /**
     * 判断是否应阻断硬件级主动 WiFi 扫描
     */
    fun shouldSuppressWifiScan(isEnabled: Boolean): Boolean = isEnabled
}

/**
 * 网络特征反定位 Hook 引擎 (Telephony & WiFi Anti-Detection Hook)
 * 拦截 TelephonyManager 与 WifiManager，彻底切断应用通过基站与 WiFi 扫描获取真实物理位置的途径。
 */
class NetworkAntiDetectHook {

    companion object {

        @JvmStatic
        fun initHooks(classLoader: ClassLoader) {
            hookTelephony(classLoader)
            hookWifi(classLoader)
        }

        private fun hookTelephony(classLoader: ClassLoader) {
            // 1. TelephonyManager.getAllCellInfo -> 置空真实基站列表
            try {
                XposedHelpers.findAndHookMethod(
                    TelephonyManager::class.java,
                    "getAllCellInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isCellInterceptionEnabled) {
                                param.result = Collections.emptyList<CellInfo>()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook TelephonyManager.getAllCellInfo failed: ${t.message}")
            }

            // 2. TelephonyManager.getCellLocation -> 返回 null 阻断基站定位
            try {
                XposedHelpers.findAndHookMethod(
                    TelephonyManager::class.java,
                    "getCellLocation",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isCellInterceptionEnabled) {
                                param.result = null
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook TelephonyManager.getCellLocation failed: ${t.message}")
            }

            // 3. PhoneStateListener.onCellInfoChanged(List<CellInfo>)
            try {
                XposedHelpers.findAndHookMethod(
                    PhoneStateListener::class.java,
                    "onCellInfoChanged",
                    List::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isCellInterceptionEnabled) {
                                param.args[0] = Collections.emptyList<CellInfo>()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook PhoneStateListener.onCellInfoChanged failed: ${t.message}")
            }

            // 4. API 31+ TelephonyCallback.CellInfoListener (若存在)
            try {
                val cellInfoListenerClass = Class.forName(
                    "android.telephony.TelephonyCallback\$CellInfoListener",
                    false,
                    classLoader
                )
                XposedHelpers.findAndHookMethod(
                    cellInfoListenerClass,
                    "onCellInfoChanged",
                    List::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isCellInterceptionEnabled) {
                                param.args[0] = Collections.emptyList<CellInfo>()
                            }
                        }
                    }
                )
            } catch (_: Throwable) {}
        }

        private fun hookWifi(classLoader: ClassLoader) {
            // 1. WifiManager.getScanResults -> 置空物理 AP 列表
            try {
                XposedHelpers.findAndHookMethod(
                    WifiManager::class.java,
                    "getScanResults",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isWifiInterceptionEnabled) {
                                param.result = ArrayList<ScanResult>()
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook WifiManager.getScanResults failed: ${t.message}")
            }

            // 2. WifiManager.getConnectionInfo -> 抹除当前连接热点 BSSID 为 02:00:00:00:00:00
            try {
                XposedHelpers.findAndHookMethod(
                    WifiManager::class.java,
                    "getConnectionInfo",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isWifiInterceptionEnabled) {
                                val info = param.result
                                if (info != null) {
                                    NetworkAntiDetectFilter.sanitizeWifiInfoBssid(info, true)
                                }
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook WifiManager.getConnectionInfo failed: ${t.message}")
            }

            // 3. WifiManager.startScan -> 抑制真实硬件广播扫描并直接返回 true
            try {
                XposedHelpers.findAndHookMethod(
                    WifiManager::class.java,
                    "startScan",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!MockConfigManager.isMockEnabledForCurrentProcess()) return
                            if (MockConfigManager.isWifiInterceptionEnabled) {
                                param.result = true
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("[MockLoc] Hook WifiManager.startScan failed: ${t.message}")
            }
        }
    }
}
