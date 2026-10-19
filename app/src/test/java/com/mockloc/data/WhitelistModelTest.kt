package com.mockloc.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 目标应用白名单数据模型与仓库单元测试。
 * 验证 AppInfo 状态变动、系统应用过滤、关键字搜索、持久化同步以及严格 opt-in 语义。
 */
class WhitelistModelTest {

    private lateinit var repository: WhitelistRepository

    private val sampleApps = listOf(
        AppInfo("com.tencent.mm", "微信", isSystemApp = false, isSelected = false),
        AppInfo("com.eg.android.AlipayGphone", "支付宝", isSystemApp = false, isSelected = false),
        AppInfo("com.android.settings", "系统设置", isSystemApp = true, isSelected = false),
        AppInfo("com.android.phone", "电话服务", isSystemApp = true, isSelected = false),
        AppInfo("com.autonavi.minimap", "高德地图", isSystemApp = false, isSelected = false)
    )

    @Before
    fun setUp() {
        // 重置 MockConfigManager 状态
        MockConfigManager.isMockEnabled = false
        MockConfigManager.setTargetPackages(emptyList())

        // 实例化无 prefs 的 WhitelistRepository (纯内存测试模式)
        repository = WhitelistRepository(prefs = null, context = null)
    }

    @Test
    fun testAppInfoModel_propertiesAndSelectionState() {
        val app = AppInfo(
            packageName = "com.sample.app",
            appName = "Sample App",
            isSystemApp = false,
            isSelected = false
        )

        assertEquals("com.sample.app", app.packageName)
        assertEquals("Sample App", app.appName)
        assertFalse(app.isSystemApp)
        assertFalse(app.isSelected)

        // 修改选中状态
        app.isSelected = true
        assertTrue("App selection should be mutable", app.isSelected)
    }

    @Test
    fun testWhitelistRepository_filterSystemApps() {
        // 当 showSystemApps = false 时，未勾选的系统应用应被过滤
        val userAppsOnly = repository.filterApps(sampleApps, query = null, showSystemApps = false)
        assertEquals(3, userAppsOnly.size)
        assertTrue(userAppsOnly.none { it.isSystemApp })

        // 当 showSystemApps = true 时，应包含全部应用
        val allApps = repository.filterApps(sampleApps, query = null, showSystemApps = true)
        assertEquals(5, allApps.size)
        assertTrue(allApps.any { it.isSystemApp })

        // 即使 showSystemApps = false，如果某个系统应用被勾选，也应当保留显示
        val selectedSystemApp = AppInfo("com.android.settings", "系统设置", isSystemApp = true, isSelected = true)
        val mixedApps = sampleApps + selectedSystemApp
        val filtered = repository.filterApps(mixedApps, query = null, showSystemApps = false)
        assertTrue("Selected system app should still be visible even if showSystemApps is false",
            filtered.any { it.packageName == "com.android.settings" && it.isSelected })
    }

    @Test
    fun testWhitelistRepository_filterByQuery() {
        // 按应用名模糊查询
        val wechatResult = repository.filterApps(sampleApps, query = "微", showSystemApps = true)
        assertEquals(1, wechatResult.size)
        assertEquals("com.tencent.mm", wechatResult.first().packageName)

        // 按包名模糊查询（大小写不敏感）
        val alipayResult = repository.filterApps(sampleApps, query = "ALIPAY", showSystemApps = true)
        assertEquals(1, alipayResult.size)
        assertEquals("com.eg.android.AlipayGphone", alipayResult.first().packageName)

        // 查询不存在的应用
        val noneResult = repository.filterApps(sampleApps, query = "xyz999", showSystemApps = true)
        assertTrue(noneResult.isEmpty())

        // 空串或空格应返回全部（受 showSystemApps 约束）
        val emptyQueryResult = repository.filterApps(sampleApps, query = "   ", showSystemApps = true)
        assertEquals(5, emptyQueryResult.size)
    }

    @Test
    fun testWhitelistRepository_persistenceAndSync() {
        assertTrue("Initial selected packages must be empty", repository.loadSelectedPackages().isEmpty())

        // 勾选单个应用
        repository.toggleAppSelection("com.tencent.mm", true)
        assertEquals(setOf("com.tencent.mm"), repository.loadSelectedPackages())
        assertEquals("MockConfigManager should sync immediately", setOf("com.tencent.mm"), MockConfigManager.getTargetPackages())

        // 再勾选一个应用
        repository.toggleAppSelection("com.autonavi.minimap", true)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), repository.loadSelectedPackages())
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), MockConfigManager.getTargetPackages())

        // 取消勾选一个应用
        repository.toggleAppSelection("com.tencent.mm", false)
        assertEquals(setOf("com.autonavi.minimap"), repository.loadSelectedPackages())
        assertEquals(setOf("com.autonavi.minimap"), MockConfigManager.getTargetPackages())
    }

    @Test
    fun testWhitelistRepository_setAllSelected() {
        val testList = listOf(
            AppInfo("com.app.a", "App A", false),
            AppInfo("com.app.b", "App B", false)
        )

        // 全选
        repository.setAllSelected(testList, true)
        assertTrue(testList.all { it.isSelected })
        assertEquals(setOf("com.app.a", "com.app.b"), repository.loadSelectedPackages())
        assertEquals(setOf("com.app.a", "com.app.b"), MockConfigManager.getTargetPackages())

        // 取消全选
        repository.setAllSelected(testList, false)
        assertTrue(testList.all { !it.isSelected })
        assertTrue(repository.loadSelectedPackages().isEmpty())
        assertTrue(MockConfigManager.getTargetPackages().isEmpty())
    }

    @Test
    fun testStrictOptInWhitelistSemantics() {
        // 验证用户需求 §R1: 必须显式命中白名单，绝不滥伤无辜

        // 1. 全局开启，但白名单为空 -> 任何应用均不生效
        MockConfigManager.isMockEnabled = true
        MockConfigManager.setTargetPackages(emptySet())
        assertFalse("Empty whitelist must never mock any app", MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))
        assertFalse(MockConfigManager.isMockEnabledForPackage("com.eg.android.AlipayGphone"))
        assertFalse(MockConfigManager.isMockEnabledForPackage(null))

        // 2. 将微信加入白名单
        repository.toggleAppSelection("com.tencent.mm", true)
        assertTrue("Selected app should be mocked", MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))
        assertFalse("Unselected app should NOT be mocked", MockConfigManager.isMockEnabledForPackage("com.eg.android.AlipayGphone"))

        // 3. 全局关闭模拟
        MockConfigManager.isMockEnabled = false
        assertFalse("When global mock is disabled, even whitelisted app returns false",
            MockConfigManager.isMockEnabledForPackage("com.tencent.mm"))
    }
}
