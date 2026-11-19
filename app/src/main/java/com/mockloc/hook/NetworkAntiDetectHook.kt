package com.mockloc.hook

import android.net.wifi.WifiInfo
import android.os.Build
import android.telephony.CellInfo
import android.telephony.gsm.GsmCellLocation
import com.mockloc.data.config.MockConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class NetworkAntiDetectHook(
    private val backend: HookBackend = XposedHookBackend,
    private val configProvider: ConfigSnapshotProvider = HookConfigStore.shared,
    private val cellLocationFactory: (MockConfig) -> Any? = ::defaultSanitizedCellLocation,
    private val wifiInfoSanitizer: (Any?, MockConfig) -> Any? = ::defaultSanitizeWifiInfo,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {
    private val installedLoaders = ConcurrentHashMap.newKeySet<String>()
    private val hookedConcreteCallbacks = ConcurrentHashMap.newKeySet<String>()

    fun install(classLoader: ClassLoader?, targetPackage: String) {
        val loaderKey = "${System.identityHashCode(classLoader)}#$targetPackage"
        if (!installedLoaders.add(loaderKey)) return

        hookTelephony(classLoader, targetPackage)
        hookWifiAndConnectivity(classLoader, targetPackage)
    }

    private fun shouldSpoofCell(targetPackage: String): Boolean {
        val cfg = configProvider.currentConfig()
        return cfg.isEnabledForPackage(targetPackage) && cfg.antiDetectCellEnabled
    }

    private fun shouldSpoofWifi(targetPackage: String): Boolean {
        val cfg = configProvider.currentConfig()
        return cfg.isEnabledForPackage(targetPackage) && cfg.antiDetectWifiEnabled
    }

    private fun hookTelephony(classLoader: ClassLoader?, targetPackage: String) {
        val telephonyCls = backend.findClassIfExists("android.telephony.TelephonyManager", classLoader)
            ?: return

        backend.hookAllMethods(
            telephonyCls,
            "getAllCellInfo",
            after = MethodHookHandler { inv ->
                if (shouldSpoofCell(targetPackage)) {
                    inv.result = emptyList<CellInfo>()
                }
            }
        )

        backend.hookAllMethods(
            telephonyCls,
            "getCellLocation",
            after = MethodHookHandler { inv ->
                if (shouldSpoofCell(targetPackage)) {
                    inv.result = cellLocationFactory(configProvider.currentConfig())
                }
            }
        )

        // API 34+ 已移除 getNeighboringCellInfo，必须使用运行时方法名字符串探测
        val hasNeighboring = runCatching {
            telephonyCls.methods.any { it.name == "getNeighboringCellInfo" }
        }.getOrDefault(false)
        if (hasNeighboring) {
            backend.hookAllMethods(
                telephonyCls,
                "getNeighboringCellInfo",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofCell(targetPackage)) {
                        inv.result = emptyList<Any>()
                    }
                }
            )
        }

        backend.hookAllMethods(
            telephonyCls,
            "requestCellInfoUpdate",
            before = MethodHookHandler { inv ->
                val callback = inv.args.getOrNull(1) ?: return@MethodHookHandler
                val implClass = callback.javaClass
                if (implClass.isInterface ||
                    implClass.name == "android.telephony.TelephonyManager\$CellInfoCallback"
                ) {
                    return@MethodHookHandler
                }
                HookMethodUtils.hookConcreteMethodHierarchy(
                    backend = backend,
                    hookedMethodKeys = hookedConcreteCallbacks,
                    implClass = implClass,
                    methodName = "onCellInfo",
                    before = MethodHookHandler { cbInv ->
                        if (shouldSpoofCell(targetPackage) && cbInv.args.isNotEmpty()) {
                            cbInv.args[0] = emptyList<CellInfo>()
                        }
                    }
                )
            }
        )

        val telephonyCallbackBase = backend.findClassIfExists(
            "android.telephony.TelephonyCallback",
            classLoader
        )
        backend.hookAllMethods(
            telephonyCls,
            "registerTelephonyCallback",
            before = MethodHookHandler { inv ->
                val callback = inv.args.lastOrNull { arg ->
                    arg != null && (telephonyCallbackBase == null || telephonyCallbackBase.isInstance(arg))
                } ?: return@MethodHookHandler

                val implClass = callback.javaClass
                if (implClass.isInterface || implClass.name == "android.telephony.TelephonyCallback") {
                    return@MethodHookHandler
                }
                hookCellCallbackConcreteClass(implClass, targetPackage)
            }
        )

        val phoneStateListenerBase = backend.findClassIfExists(
            "android.telephony.PhoneStateListener",
            classLoader
        )
        backend.hookAllMethods(
            telephonyCls,
            "listen",
            before = MethodHookHandler { inv ->
                val listener = inv.args.firstOrNull() ?: return@MethodHookHandler
                if (phoneStateListenerBase != null && !phoneStateListenerBase.isInstance(listener)) {
                    return@MethodHookHandler
                }
                val implClass = listener.javaClass
                if (implClass.isInterface || implClass.name == "android.telephony.PhoneStateListener") {
                    return@MethodHookHandler
                }
                hookCellCallbackConcreteClass(implClass, targetPackage)
            }
        )
    }

    private fun hookCellCallbackConcreteClass(implClass: Class<*>, targetPackage: String) {
        if (implClass.isInterface) return
        HookMethodUtils.hookConcreteMethodHierarchy(
            backend = backend,
            hookedMethodKeys = hookedConcreteCallbacks,
            implClass = implClass,
            methodName = "onCellInfoChanged",
            before = MethodHookHandler { cbInv ->
                if (shouldSpoofCell(targetPackage) && cbInv.args.isNotEmpty()) {
                    cbInv.args[0] = emptyList<CellInfo>()
                }
            }
        )

        HookMethodUtils.hookConcreteMethodHierarchy(
            backend = backend,
            hookedMethodKeys = hookedConcreteCallbacks,
            implClass = implClass,
            methodName = "onCellLocationChanged",
            before = MethodHookHandler { cbInv ->
                if (shouldSpoofCell(targetPackage) && cbInv.args.isNotEmpty()) {
                    cbInv.args[0] = cellLocationFactory(configProvider.currentConfig())
                }
            }
        )
    }

    private fun hookWifiAndConnectivity(classLoader: ClassLoader?, targetPackage: String) {
        val wifiMgrCls = backend.findClassIfExists("android.net.wifi.WifiManager", classLoader)
        if (wifiMgrCls != null) {
            backend.hookAllMethods(
                wifiMgrCls,
                "getScanResults",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) {
                        inv.result = emptyList<Any>()
                    }
                }
            )

            backend.hookAllMethods(
                wifiMgrCls,
                "startScan",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) {
                        inv.result = true
                    }
                }
            )

            backend.hookAllMethods(
                wifiMgrCls,
                "getConnectionInfo",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage) && inv.result != null) {
                        inv.result = wifiInfoSanitizer(inv.result, configProvider.currentConfig())
                    }
                }
            )

            backend.hookAllMethods(
                wifiMgrCls,
                "registerScanResultsCallback",
                before = MethodHookHandler { inv ->
                    val callback = inv.args.getOrNull(1) ?: return@MethodHookHandler
                    val implClass = callback.javaClass
                    if (implClass.isInterface ||
                        implClass.name == "android.net.wifi.WifiManager\$ScanResultsCallback"
                    ) {
                        return@MethodHookHandler
                    }
                    HookMethodUtils.hookConcreteMethodHierarchy(
                        backend = backend,
                        hookedMethodKeys = hookedConcreteCallbacks,
                        implClass = implClass,
                        methodName = "onScanResultsAvailable",
                        before = MethodHookHandler { _ ->
                            // 保持回调通知不阻塞，实际数据通过 getScanResults() 返回空列表脱敏
                        }
                    )
                }
            )
        }

        val netCapsCls = backend.findClassIfExists("android.net.NetworkCapabilities", classLoader)
        val wifiInfoCls = backend.findClassIfExists("android.net.wifi.WifiInfo", classLoader)

        if (netCapsCls != null) {
            backend.hookAllMethods(
                netCapsCls,
                "getTransportInfo",
                after = MethodHookHandler { inv ->
                    if (!shouldSpoofWifi(targetPackage)) return@MethodHookHandler
                    val info = inv.result ?: return@MethodHookHandler
                    if (wifiInfoCls == null || wifiInfoCls.isInstance(info)) {
                        inv.result = wifiInfoSanitizer(info, configProvider.currentConfig())
                    }
                }
            )
        }

        if (wifiInfoCls != null) {
            backend.hookAllMethods(
                wifiInfoCls,
                "getBSSID",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) inv.result = SANITIZED_BSSID
                }
            )
            backend.hookAllMethods(
                wifiInfoCls,
                "getSSID",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) inv.result = SANITIZED_SSID
                }
            )
            backend.hookAllMethods(
                wifiInfoCls,
                "getMacAddress",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) inv.result = SANITIZED_BSSID
                }
            )
            backend.hookAllMethods(
                wifiInfoCls,
                "getIpAddress",
                after = MethodHookHandler { inv ->
                    if (shouldSpoofWifi(targetPackage)) inv.result = 0
                }
            )
            if (sdkIntProvider() >= Build.VERSION_CODES.TIRAMISU) {
                backend.hookAllMethods(
                    wifiInfoCls,
                    "getApMldMacAddress",
                    after = MethodHookHandler { inv ->
                        if (shouldSpoofWifi(targetPackage)) inv.result = null
                    }
                )
            }
        }
    }

    fun clearForTest() {
        installedLoaders.clear()
        hookedConcreteCallbacks.clear()
    }

    companion object {
        const val SANITIZED_BSSID = "02:00:00:00:00:00"
        const val SANITIZED_SSID = "<unknown ssid>"

        val shared: NetworkAntiDetectHook = NetworkAntiDetectHook()

        fun deriveDeterministicVirtualCell(latitude: Double, longitude: Double): Pair<Int, Int> {
            val latInt = abs((latitude * 10000.0).toInt())
            val lonInt = abs((longitude * 10000.0).toInt())
            val lac = (latInt % 60_000) + 1000
            val cid = (lonInt % 60_000) + 1000
            return lac to cid
        }

        @Suppress("DEPRECATION")
        fun defaultSanitizedCellLocation(config: MockConfig): Any? {
            return runCatching {
                val (lac, cid) = deriveDeterministicVirtualCell(config.latitude, config.longitude)
                GsmCellLocation().apply { setLacAndCid(lac, cid) }
            }.getOrNull()
        }

        fun defaultSanitizeWifiInfo(original: Any?, @Suppress("UNUSED_PARAMETER") config: MockConfig): Any? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val built = runCatching {
                    WifiInfo.Builder()
                        .setBssid(SANITIZED_BSSID)
                        .setSsid(ByteArray(0))
                        .setRssi(-127)
                        .setNetworkId(-1)
                        .build()
                }.getOrNull()
                if (built != null) return built
            }
            return original
        }
    }
}
