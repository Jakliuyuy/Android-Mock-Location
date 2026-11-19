package com.mockloc.data.whitelist

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.domain.model.AppScopeInfo
import com.mockloc.ipc.ConfigSyncSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WhitelistRepositoryTest {

    private class ThreadSafeTestPrefs : SharedPreferences {
        private val lock = Any()
        private val map = LinkedHashMap<String, Any?>()
        val commitCount = AtomicInteger(0)

        override fun getAll(): MutableMap<String, *> = synchronized(lock) { LinkedHashMap(map) }
        override fun getString(key: String?, defValue: String?): String? = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as String?
        }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(lock) {
                if (key == null || !map.containsKey(key)) defValues
                else (map[key] as? Set<String>)?.let { HashSet(it) } ?: defValues
            }
        override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Int
        }
        override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Long
        }
        override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Float
        }
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) {
            if (key == null || !map.containsKey(key)) defValue else map[key] as Boolean
        }
        override fun contains(key: String?): Boolean = synchronized(lock) { key != null && map.containsKey(key) }
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.let { HashSet(it) }
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVED }
            override fun clear() = apply { clearFlag = true }
            override fun commit(): Boolean {
                commitCount.incrementAndGet()
                applyChanges()
                return true
            }
            override fun apply() {
                applyChanges()
            }
            private fun applyChanges() = synchronized(lock) {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v === REMOVED) map.remove(k) else map[k] = v
                }
            }
        }
        private object REMOVED
    }

    @Test
    fun `first init prepopulates defaults and user clearAll stays empty across restart`() = runTest {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs, ioScope = TestScope(StandardTestDispatcher(testScheduler)))

        val repo1 = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            ioDispatcher = Dispatchers.Unconfined
        )

        val initialSelected = repo1.getSelectedPackages()
        assertEquals(WhitelistRepository.DEFAULT_PRESET_PACKAGES, initialSelected)
        assertTrue(metaPrefs.getBoolean(WhitelistRepository.KEY_WHITELIST_INITIALIZED, false))

        // 清空全部并重建实例，不得回退默认预设
        val cleared = repo1.clearAll()
        assertTrue(cleared.isEmpty())
        assertTrue(repo1.getSelectedPackages().isEmpty())

        val reloadedConfigRepo = MockConfigRepository(prefs = configPrefs, ioScope = TestScope(StandardTestDispatcher(testScheduler)))
        val repo2 = WhitelistRepository(
            configRepository = reloadedConfigRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            ioDispatcher = Dispatchers.Unconfined
        )
        assertTrue("Cleared whitelist must stay empty after cold restart", repo2.getSelectedPackages().isEmpty())
    }

    @Test
    fun `upgrade with existing non-empty config keeps existing packages without overwriting`() = runTest {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs, ioScope = TestScope(StandardTestDispatcher(testScheduler)))
        configRepo.updateTargetPackages(setOf("com.tencent.mm"))

        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals(setOf("com.tencent.mm"), repo.getSelectedPackages())
        assertTrue(metaPrefs.getBoolean(WhitelistRepository.KEY_WHITELIST_INITIALIZED, false))
    }

    @Test
    fun `getInstalledApps filters self unselected system apps keeps selected system apps and sorts properly`() = runTest {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs, ioScope = TestScope(StandardTestDispatcher(testScheduler)))
        configRepo.updateTargetPackages(setOf("com.sys.selected"))

        val records = listOf(
            InstalledAppRecord("com.mockloc", "MockLocation", false),
            InstalledAppRecord("android", "Android System", true),
            InstalledAppRecord("com.sys.unselected", "未选系统应用", true),
            InstalledAppRecord("com.sys.selected", "已选系统应用", true),
            InstalledAppRecord("com.tencent.mm", "微信", false),
            InstalledAppRecord("com.example.zeta", "普通应用Z", false)
        )

        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { records },
            ioDispatcher = Dispatchers.Unconfined
        )

        // includeSystem = false: 过滤 self/android 与未选系统应用，但保留已选系统应用
        val userAndSelected = repo.getInstalledApps(includeSystem = false)
        val pkgList = userAndSelected.map { it.packageName }
        assertFalse("com.mockloc" in pkgList)
        assertFalse("android" in pkgList)
        assertFalse("com.sys.unselected" in pkgList)
        assertTrue("com.sys.selected" in pkgList)
        assertTrue("com.tencent.mm" in pkgList)
        assertTrue("com.example.zeta" in pkgList)

        // 排序规则：已选在前 -> 推荐在前 -> 普通按名称升序
        assertEquals("com.sys.selected", userAndSelected[0].packageName)
        assertTrue(userAndSelected[0].isSelected)
        assertEquals("com.tencent.mm", userAndSelected[1].packageName)
        assertTrue(userAndSelected[1].isRecommended)
        assertEquals("com.example.zeta", userAndSelected[2].packageName)

        // includeSystem = true: 未选系统应用可见
        val allApps = repo.getInstalledApps(includeSystem = true)
        assertTrue(allApps.any { it.packageName == "com.sys.unselected" })
    }

    @Test
    fun `filterApps performs literal substring search immune to regex characters`() {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)
        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            ioDispatcher = Dispatchers.Unconfined
        )

        val apps = listOf(
            AppScopeInfo("com.tencent.mm", "微信[WeChat]", isSystemApp = false, isSelected = false),
            AppScopeInfo("com.autonavi.minimap", "高德地图", isSystemApp = false, isSelected = true),
            AppScopeInfo("com.sys.tool", "系统(工具)*", isSystemApp = true, isSelected = false)
        )

        assertEquals(listOf("com.tencent.mm"), repo.filterApps(apps, "[WeChat]").map { it.packageName })
        assertEquals(listOf("com.sys.tool"), repo.filterApps(apps, "(工具)*", includeSystem = true).map { it.packageName })
        assertTrue(repo.filterApps(apps, "\\[com.*?", includeSystem = true).isEmpty())
        assertEquals(2, repo.filterApps(apps, "  ", includeSystem = false).size)
    }

    @Test
    fun `batch operations selectAllFiltered clearFiltered toggle add remove work accurately`() = runTest {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)
        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.saveSelectedPackages(setOf("com.autonavi.minimap"))

        val filteredWechat = listOf(
            AppScopeInfo("com.tencent.mm", "微信"),
            AppScopeInfo("com.tencent.wework", "企业微信")
        )
        // selectAllFiltered 不误删未在过滤列表中的高德
        val afterSelect = repo.selectAllFiltered(filteredWechat)
        assertEquals(
            setOf("com.autonavi.minimap", "com.tencent.mm", "com.tencent.wework"),
            afterSelect
        )

        // clearFiltered 仅移除过滤视图内的微信和企微，保留高德
        val afterClearFiltered = repo.clearFiltered(filteredWechat)
        assertEquals(setOf("com.autonavi.minimap"), afterClearFiltered)

        // togglePackage 翻转状态
        assertEquals(setOf("com.autonavi.minimap", "com.tencent.mm"), repo.togglePackage("com.tencent.mm"))
        assertEquals(setOf("com.autonavi.minimap"), repo.togglePackage("com.tencent.mm"))

        // addPackages / removePackages
        assertEquals(setOf("com.autonavi.minimap", "com.baidu.BaiduMap"), repo.addPackages(listOf("com.baidu.BaiduMap")))
        assertEquals(setOf("com.baidu.BaiduMap"), repo.removePackages(listOf("com.autonavi.minimap")))

        // selectedPackagesFlow 反映最新状态
        assertEquals(setOf("com.baidu.BaiduMap"), repo.selectedPackagesFlow.first())
    }

    @Test
    fun `invalid package names self and android framework are rejected`() {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)
        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            ioDispatcher = Dispatchers.Unconfined
        )

        val invalidPackages = listOf(
            "",
            "   ",
            "com.mockloc",
            "android",
            "singleword",
            "com.app; rm -rf /",
            "123.invalid.pkg"
        )
        repo.saveSelectedPackages(invalidPackages.toSet() + "com.tencent.mm")

        assertEquals(setOf("com.tencent.mm"), repo.getSelectedPackages())
        for (bad in invalidPackages) {
            assertFalse("Invalid package $bad must not be selected", repo.isPackageSelected(bad))
            assertEquals(setOf("com.tencent.mm"), repo.togglePackage(bad))
        }
        assertFalse(repo.isPackageSelected(null))
        assertTrue(repo.isPackageSelected("com.tencent.mm"))
    }

    @Test
    fun `persistence and onPackagesSynced callback notify both added and removed packages`() {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)
        val syncEvents = CopyOnWriteArrayList<Pair<MockConfig, Set<String>>>()

        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            onPackagesSynced = { config, affected ->
                syncEvents.add(config to affected)
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        repo.saveSelectedPackages(setOf("com.tencent.mm", "com.autonavi.minimap"))
        assertEquals(1, syncEvents.size)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), syncEvents[0].second)
        assertEquals(
            setOf("com.tencent.mm", "com.autonavi.minimap"),
            ConfigSyncSerializer.fromPrefs(configPrefs).targetPackages
        )

        // 动态移除 com.tencent.mm，affectedPackages 必须同时包含被移除包名以便通知其恢复真实位置
        repo.togglePackage("com.tencent.mm")
        assertEquals(2, syncEvents.size)
        val lastEvent = syncEvents.last()
        assertEquals(setOf("com.autonavi.minimap"), lastEvent.first.targetPackages)
        assertTrue("Removed package must be in affectedPackages", "com.tencent.mm" in lastEvent.second)
        assertTrue("com.autonavi.minimap" in lastEvent.second)
        assertEquals(setOf("com.autonavi.minimap"), ConfigSyncSerializer.fromPrefs(configPrefs).targetPackages)
    }

    @Test
    fun `concurrent location updates and whitelist mutations never lose updates or deadlock`() = runBlocking {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val configRepo = MockConfigRepository(
            prefs = configPrefs,
            ioScope = ioScope,
            debounceMs = 10L
        )
        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            ioDispatcher = Dispatchers.IO
        )

        val locJob = async(Dispatchers.IO) {
            for (i in 1..40) {
                configRepo.updateDebounced { it.copy(latitude = 30.0 + i * 0.01, longitude = 120.0 + i * 0.01) }
            }
        }
        val wlJob = async(Dispatchers.IO) {
            for (i in 1..40) {
                repo.addPackages(listOf("com.pkg.test$i"))
            }
        }
        awaitAll(locJob, wlJob)
        configRepo.flushPendingSync()

        val finalConfig = configRepo.currentConfig
        assertEquals(30.40, finalConfig.latitude, 1e-4)
        assertEquals(40, finalConfig.targetPackages.size)
        assertEquals(40, ConfigSyncSerializer.fromPrefs(configPrefs).targetPackages.size)
    }

    @Test
    fun `AndroidPackageQuerySource handles PackageManager exceptions null fields and system flags`() = runTest {
        // 1) PackageManager 抛异常安全兜底返回 emptyList
        val throwingPm = mock<PackageManager>()
        @Suppress("DEPRECATION")
        doThrow(SecurityException("denied")).whenever(throwingPm).getInstalledPackages(any<Int>())
        val failingSource = AndroidPackageQuerySource(throwingPm, Dispatchers.Unconfined)
        assertTrue(failingSource.queryInstalledApps().isEmpty())

        // 2) 正常解析、去重、null 保护、FLAG_SYSTEM & FLAG_UPDATED_SYSTEM_APP 识别与 loadLabel 兜底
        val pm = mock<PackageManager>()
        val sysAppInfo = spy(ApplicationInfo()).apply {
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        doReturn("系统设置").whenever(sysAppInfo).loadLabel(pm)

        val updatedSysInfo = spy(ApplicationInfo()).apply {
            flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        }
        doThrow(RuntimeException("label error")).whenever(updatedSysInfo).loadLabel(pm)

        val userAppInfo = spy(ApplicationInfo()).apply {
            flags = 0
        }
        doReturn("微信").whenever(userAppInfo).loadLabel(pm)

        val pkgList = listOf(
            mock<PackageInfo>(), // packageName 默认为 null 且 applicationInfo 默认为 null
            PackageInfo().apply { packageName = "   "; applicationInfo = null },
            PackageInfo().apply { packageName = "com.android.settings"; applicationInfo = sysAppInfo },
            PackageInfo().apply { packageName = "com.android.settings"; applicationInfo = sysAppInfo }, // 重复包名
            PackageInfo().apply { packageName = "com.google.android.gms"; applicationInfo = updatedSysInfo },
            PackageInfo().apply { packageName = "com.tencent.mm"; applicationInfo = userAppInfo }
        )
        @Suppress("DEPRECATION")
        doReturn(pkgList).whenever(pm).getInstalledPackages(any<Int>())

        val source = AndroidPackageQuerySource(pm, Dispatchers.Unconfined)
        val records = source.queryInstalledApps()
        assertEquals(3, records.size)
        assertEquals(InstalledAppRecord("com.android.settings", "系统设置", true), records[0])
        assertEquals(InstalledAppRecord("com.google.android.gms", "com.google.android.gms", true), records[1])
        assertEquals(InstalledAppRecord("com.tencent.mm", "微信", false), records[2])
    }

    @Test
    fun `mutateTargetPackages avoids redundant metaPrefs commit when already initialized`() {
        val configPrefs = ThreadSafeTestPrefs()
        val metaPrefs = ThreadSafeTestPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)

        val repo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            ioDispatcher = Dispatchers.Unconfined
        )

        // 首次初始化写入 1 次 KEY_WHITELIST_INITIALIZED
        assertEquals(1, metaPrefs.commitCount.get())

        // 后续多次白名单变更不应再次写入 metaPrefs
        repo.togglePackage("com.tencent.mm")
        repo.togglePackage("com.tencent.mm")
        repo.saveSelectedPackages(setOf("com.autonavi.minimap"))
        assertEquals(1, metaPrefs.commitCount.get())
    }
}
