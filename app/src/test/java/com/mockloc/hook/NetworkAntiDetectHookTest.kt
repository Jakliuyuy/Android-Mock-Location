package com.mockloc.hook

import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.mockloc.data.config.MockConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class NetworkAntiDetectHookTest {

    // 测试用具体回调子类（验证动态捕获具体子类并去重）
    private class ConcreteCellInfoCallback : TelephonyManager.CellInfoCallback() {
        override fun onCellInfo(cellInfo: MutableList<android.telephony.CellInfo>) = Unit
    }

    private class ConcreteTelephonyCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) = Unit
    }

    @Suppress("DEPRECATION")
    private class ConcretePhoneStateListener : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>?) = Unit
    }

    private class ConcreteScanResultsCallback : WifiManager.ScanResultsCallback() {
        override fun onScanResultsAvailable() = Unit
    }

    private class FakeLegacyTelephonyManager {
        @Suppress("unused")
        fun getNeighboringCellInfo(): List<String> = listOf("cell-1")
    }

    private fun enabledConfig(
        cell: Boolean = true,
        wifi: Boolean = true,
        targetPkg: String = "com.tencent.mm"
    ) = MockConfig(
        enabled = true,
        latitude = 39.9087,
        longitude = 116.3975,
        antiDetectCellEnabled = cell,
        antiDetectWifiEnabled = wifi,
        targetPackages = setOf(targetPkg),
        updatedAtMs = 100L,
        version = 1L
    )

    @Test
    fun `telephony anti-detect masks cell info and dynamically hooks concrete callbacks without duplicates`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(enabledConfig(cell = true, wifi = true))
        val fakeVirtualCell = Any()
        val hook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = cfgProvider,
            cellLocationFactory = { fakeVirtualCell },
            sdkIntProvider = { 35 }
        )

        hook.install(javaClass.classLoader, "com.tencent.mm")

        // getAllCellInfo -> emptyList
        val allCellsInv = backend.invokeSimulated(
            null,
            TelephonyManager::class.java,
            "getAllCellInfo"
        ) { listOf("real-cell") }
        assertEquals(emptyList<Any>(), allCellsInv.result)

        // getCellLocation -> 脱敏虚拟位置
        val cellLocInv = backend.invokeSimulated(
            null,
            TelephonyManager::class.java,
            "getCellLocation"
        ) { "real-gsm-loc" }
        assertEquals(fakeVirtualCell, cellLocInv.result)

        // requestCellInfoUpdate 捕获具体子类并只 Hook 一次
        val cb1 = ConcreteCellInfoCallback()
        val cb2 = ConcreteCellInfoCallback()
        backend.invokeSimulated(null, TelephonyManager::class.java, "requestCellInfoUpdate", null, cb1)
        backend.invokeSimulated(null, TelephonyManager::class.java, "requestCellInfoUpdate", null, cb2)
        assertEquals(1, backend.hookCountFor(ConcreteCellInfoCallback::class.java, "onCellInfo"))

        val cbDataInv = backend.invokeSimulated(
            cb1,
            ConcreteCellInfoCallback::class.java,
            "onCellInfo",
            listOf("real-cell-in-callback")
        )
        assertEquals(emptyList<Any>(), cbDataInv.args[0])

        // registerTelephonyCallback 动态捕获具体子类
        val telCb = ConcreteTelephonyCallback()
        backend.invokeSimulated(null, TelephonyManager::class.java, "registerTelephonyCallback", null, telCb)
        assertEquals(1, backend.hookCountFor(ConcreteTelephonyCallback::class.java, "onCellInfoChanged"))
        val telDataInv = backend.invokeSimulated(
            telCb,
            ConcreteTelephonyCallback::class.java,
            "onCellInfoChanged",
            listOf("real-cell-5g")
        )
        assertEquals(emptyList<Any>(), telDataInv.args[0])

        // listen(PhoneStateListener) 动态捕获具体子类
        val psl = ConcretePhoneStateListener()
        backend.invokeSimulated(null, TelephonyManager::class.java, "listen", psl, 1024)
        assertEquals(1, backend.hookCountFor(ConcretePhoneStateListener::class.java, "onCellInfoChanged"))

        // 绝不能直接 Hook 抽象/接口基类
        assertEquals(0, backend.hookCountFor(TelephonyCallback.CellInfoListener::class.java, "onCellInfoChanged"))
        assertEquals(0, backend.hookCountFor(TelephonyManager.CellInfoCallback::class.java, "onCellInfo"))
    }

    @Test
    fun `runtime getNeighboringCellInfo string lookup works when method exists`() {
        val backend = FakeHookBackend()
        backend.classRegistry["android.telephony.TelephonyManager"] = FakeLegacyTelephonyManager::class.java
        val hook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = StaticConfigSnapshotProvider(enabledConfig(cell = true))
        )
        hook.install(javaClass.classLoader, "com.tencent.mm")

        val inv = backend.invokeSimulated(
            FakeLegacyTelephonyManager(),
            FakeLegacyTelephonyManager::class.java,
            "getNeighboringCellInfo"
        ) { listOf("real-neighbor") }
        assertEquals(emptyList<Any>(), inv.result)
    }

    @Test
    fun `wifi and NetworkCapabilities getTransportInfo sanitize WiFi identifiers`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(enabledConfig(cell = true, wifi = true))
        val sanitizedWifi = Any()
        val hook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = cfgProvider,
            wifiInfoSanitizer = { _, _ -> sanitizedWifi },
            sdkIntProvider = { 35 }
        )
        hook.install(javaClass.classLoader, "com.tencent.mm")

        val scanInv = backend.invokeSimulated(
            null,
            WifiManager::class.java,
            "getScanResults"
        ) { listOf("real-ap") }
        assertEquals(emptyList<Any>(), scanInv.result)

        val startScanInv = backend.invokeSimulated(
            null,
            WifiManager::class.java,
            "startScan"
        ) { false }
        assertEquals(true, startScanInv.result)

        val scanCb = ConcreteScanResultsCallback()
        backend.invokeSimulated(null, WifiManager::class.java, "registerScanResultsCallback", null, scanCb)
        assertEquals(1, backend.hookCountFor(ConcreteScanResultsCallback::class.java, "onScanResultsAvailable"))
        assertEquals(0, backend.hookCountFor(WifiManager.ScanResultsCallback::class.java, "onScanResultsAvailable"))

        // NetworkCapabilities.getTransportInfo() 对 WifiInfo 脱敏
        val realWifi = org.mockito.kotlin.mock<WifiInfo>()
        val transportInv = backend.invokeSimulated(
            null,
            NetworkCapabilities::class.java,
            "getTransportInfo"
        ) { realWifi }
        assertEquals(sanitizedWifi, transportInv.result)

        // WifiInfo 方法级兜底脱敏
        assertEquals(
            NetworkAntiDetectHook.SANITIZED_BSSID,
            backend.invokeSimulated(realWifi, WifiInfo::class.java, "getBSSID") { "aa:bb:cc:dd:ee:ff" }.result
        )
        assertEquals(
            NetworkAntiDetectHook.SANITIZED_SSID,
            backend.invokeSimulated(realWifi, WifiInfo::class.java, "getSSID") { "\"MyHomeWifi\"" }.result
        )
        assertEquals(
            0,
            backend.invokeSimulated(realWifi, WifiInfo::class.java, "getIpAddress") { 0x0101A8C0 }.result
        )
        assertNull(
            backend.invokeSimulated(realWifi, WifiInfo::class.java, "getApMldMacAddress") { "mld" }.result
        )
    }

    @Test
    fun `disabled switches or non-whitelisted package pass through real network data untouched`() {
        val backend = FakeHookBackend()
        val cfgProvider = StaticConfigSnapshotProvider(
            enabledConfig(cell = false, wifi = false, targetPkg = "com.tencent.mm")
        )
        val hook = NetworkAntiDetectHook(backend = backend, configProvider = cfgProvider)
        hook.install(javaClass.classLoader, "com.tencent.mm")

        val realCells = listOf("real-lte-cell")
        assertEquals(
            realCells,
            backend.invokeSimulated(null, TelephonyManager::class.java, "getAllCellInfo") { realCells }.result
        )
        val realWifiList = listOf("real-wifi-ssid")
        assertEquals(
            realWifiList,
            backend.invokeSimulated(null, WifiManager::class.java, "getScanResults") { realWifiList }.result
        )

        // 非白名单即使全开也透传
        cfgProvider.config = enabledConfig(cell = true, wifi = true, targetPkg = "com.other.pkg")
        assertEquals(
            realCells,
            backend.invokeSimulated(null, TelephonyManager::class.java, "getAllCellInfo") { realCells }.result
        )
    }

    private open class BaseTelephonyCallback : TelephonyCallback(), TelephonyCallback.CellInfoListener {
        override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>) = Unit
    }

    private class DerivedTelephonyCallbackNoOverride : BaseTelephonyCallback()

    @Test
    fun `hot-enabling via L1 after early callback registration masks cell and wifi without leaks`() {
        val nowMs = AtomicLong(1_000L)
        val initialCfg = MockConfig(
            enabled = false,
            antiDetectCellEnabled = true,
            antiDetectWifiEnabled = true,
            targetPackages = setOf("com.tencent.mm"),
            updatedAtMs = 100L,
            version = 1L
        )
        val fakeReader = FakePrefsReader(initialCfg)
        val store = HookConfigStore(
            prefsReaderFactory = { fakeReader },
            elapsedRealtimeProvider = { nowMs.get() },
            throttleMs = 400L
        )
        val backend = FakeHookBackend()
        val hook = NetworkAntiDetectHook(backend = backend, configProvider = store)
        hook.install(javaClass.classLoader, "com.tencent.mm")

        // 先开 App（enabled=false）时注册三大回调，必须无条件完成挂载并在未启用期间透传
        val telCb = ConcreteTelephonyCallback()
        val cellInfoCb = ConcreteCellInfoCallback()
        val scanCb = ConcreteScanResultsCallback()
        backend.invokeSimulated(null, TelephonyManager::class.java, "registerTelephonyCallback", null, telCb)
        backend.invokeSimulated(null, TelephonyManager::class.java, "requestCellInfoUpdate", null, cellInfoCb)
        backend.invokeSimulated(null, WifiManager::class.java, "registerScanResultsCallback", null, scanCb)

        val rawCellList = listOf("REAL_CELL")
        assertEquals(
            rawCellList,
            backend.invokeSimulated(telCb, ConcreteTelephonyCallback::class.java, "onCellInfoChanged", rawCellList).args[0]
        )

        // 模拟无 L2 广播下的 L1 热启用并推进超过 400ms
        fakeReader.writeConfig(
            initialCfg.copy(enabled = true, updatedAtMs = 200L, version = 2L),
            markChanged = true
        )
        nowMs.set(1_600L)

        assertEquals(
            emptyList<Any>(),
            backend.invokeSimulated(telCb, ConcreteTelephonyCallback::class.java, "onCellInfoChanged", rawCellList).args[0]
        )
        assertEquals(
            emptyList<Any>(),
            backend.invokeSimulated(cellInfoCb, ConcreteCellInfoCallback::class.java, "onCellInfo", rawCellList).args[0]
        )
        assertEquals(
            emptyList<Any>(),
            backend.invokeSimulated(null, WifiManager::class.java, "getScanResults") { listOf("REAL_WIFI") }.result
        )
        assertNotSame(rawCellList, emptyList<Any>())
    }

    @Test
    fun `inherited non-overridden callback method is hooked under Xposed getDeclaredMethods-only semantics`() {
        val backend = DeclaredOnlyHookBackend()
        val hook = NetworkAntiDetectHook(
            backend = backend,
            configProvider = StaticConfigSnapshotProvider(enabledConfig(cell = true))
        )
        hook.install(javaClass.classLoader, "com.tencent.mm")

        val derived = DerivedTelephonyCallbackNoOverride()
        backend.invokeSimulated(null, TelephonyManager::class.java, "registerTelephonyCallback", null, derived)

        // 真实 Xposed 只检索 declaredMethods，因此必须成功定位并挂在声明类 BaseTelephonyCallback 上
        assertEquals(1, backend.hookCountFor(BaseTelephonyCallback::class.java, "onCellInfoChanged"))
        assertEquals(0, backend.hookCountFor(TelephonyCallback.CellInfoListener::class.java, "onCellInfoChanged"))

        val inv = backend.invokeSimulated(
            derived,
            DerivedTelephonyCallbackNoOverride::class.java,
            "onCellInfoChanged",
            listOf("LEAKED_CELL")
        )
        assertEquals(emptyList<Any>(), inv.args[0])
    }
}
