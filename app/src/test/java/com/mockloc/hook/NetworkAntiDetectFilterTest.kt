package com.mockloc.hook

import com.mockloc.data.MockConfigManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模拟用于测试 WifiInfo BSSID 掩码清洗的测试对象
 */
class TestWifiInfo(var mBSSID: String = "aa:bb:cc:dd:ee:ff")

/**
 * 基站与 WiFi 扫描反检测过滤器单元测试
 */
class NetworkAntiDetectFilterTest {

    @Test
    fun testCellInfoBlockingWhenEnabled() {
        val fakeCells = listOf("CellTower_MCC460_MNC00_LAC1234_CID5678", "CellTower_MCC460_MNC00_LAC1234_CID5679")
        val filtered = NetworkAntiDetectFilter.filterCellInfo(fakeCells, isEnabled = true)

        // 拦截开启时，基站列表必须强制返回空列表，阻断物理基站泄露
        assertTrue("CellInfo list must be empty when interception is enabled", filtered.isEmpty())
        assertEquals(0, filtered.size)
    }

    @Test
    fun testCellInfoPassthroughWhenDisabled() {
        val fakeCells = listOf("CellTower_1", "CellTower_2")
        val result = NetworkAntiDetectFilter.filterCellInfo(fakeCells, isEnabled = false)

        // 拦截关闭时，保持透传
        assertEquals(2, result.size)
        assertEquals("CellTower_1", result[0])
    }

    @Test
    fun testCellInfoNullSafe() {
        val resultEnabled = NetworkAntiDetectFilter.filterCellInfo<String>(null, isEnabled = true)
        assertTrue(resultEnabled.isEmpty())

        val resultDisabled = NetworkAntiDetectFilter.filterCellInfo<String>(null, isEnabled = false)
        assertTrue(resultDisabled.isEmpty())
    }

    @Test
    fun testWifiScanResultsBlockingWhenEnabled() {
        val fakeAps = listOf("AP_Home_Router", "AP_Office_5G", "AP_Starbucks")
        val filtered = NetworkAntiDetectFilter.filterScanResults(fakeAps, isEnabled = true)

        // WiFi 拦截开启时，周边物理热点列表必须置空
        assertTrue("WiFi ScanResult list must be empty when interception is enabled", filtered.isEmpty())
        assertEquals(0, filtered.size)
    }

    @Test
    fun testWifiScanResultsPassthroughWhenDisabled() {
        val fakeAps = listOf("AP_Router_1", "AP_Router_2")
        val result = NetworkAntiDetectFilter.filterScanResults(fakeAps, isEnabled = false)

        assertEquals(2, result.size)
        assertEquals("AP_Router_1", result[0])
    }

    @Test
    fun testWifiScanResultsNullSafe() {
        val resultEnabled = NetworkAntiDetectFilter.filterScanResults<String>(null, isEnabled = true)
        assertTrue(resultEnabled.isEmpty())

        val resultDisabled = NetworkAntiDetectFilter.filterScanResults<String>(null, isEnabled = false)
        assertTrue(resultDisabled.isEmpty())
    }

    @Test
    fun testWifiBssidSanitizationWhenEnabled() {
        val originalBssid = "12:34:56:78:9a:bc"
        val wifiInfo = TestWifiInfo(mBSSID = originalBssid)

        val success = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(wifiInfo, isEnabled = true)

        assertTrue("Sanitization should report success", success)
        assertEquals(
            "BSSID must be sanitized to standard virtual mask 02:00:00:00:00:00",
            NetworkAntiDetectFilter.MASKED_BSSID,
            wifiInfo.mBSSID
        )
    }

    @Test
    fun testWifiBssidSanitizationWhenDisabled() {
        val originalBssid = "12:34:56:78:9a:bc"
        val wifiInfo = TestWifiInfo(mBSSID = originalBssid)

        val success = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(wifiInfo, isEnabled = false)

        assertFalse("Sanitization should report false when disabled", success)
        assertEquals("BSSID should remain unchanged when disabled", originalBssid, wifiInfo.mBSSID)
    }

    @Test
    fun testWifiBssidSanitizationNullSafe() {
        val success = NetworkAntiDetectFilter.sanitizeWifiInfoBssid(null, isEnabled = true)
        assertFalse(success)
    }

    @Test
    fun testWifiScanSuppression() {
        assertTrue(NetworkAntiDetectFilter.shouldSuppressWifiScan(true))
        assertFalse(NetworkAntiDetectFilter.shouldSuppressWifiScan(false))
    }

    @Test
    fun testMockConfigManagerWhitelistAndToggles() {
        // 重置状态
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(listOf("com.tencent.mm", "com.autonavi.minimap"))

        assertTrue("Target package in whitelist should be enabled", MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))
        assertTrue("Target package in whitelist should be enabled", MockConfigManager.isMockEnabledForPackage("com.autonavi.minimap"))
        assertFalse("Unlisted package should not be enabled", MockConfigManager.isMockEnabledForPackage("com.eg.android.AlipayGphone"))

        // 全局关闭
        MockConfigManager.isMockEnabled = false
        assertFalse("When global mock is disabled, target package must be disabled", MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))

        // 严格白名单语义：空白名单不匹配任何应用
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(emptyList())
        assertFalse("When whitelist is empty, no package should be enabled", MockConfigManager.isMockEnabledForPackage("com.any.app"))
    }
}
