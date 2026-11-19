package com.mockloc.data.scope

import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.whitelist.PackageQuerySource
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.e2e.infra.StaticManifestAndResourceChecker
import com.mockloc.e2e.infra.WorkspacePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.xml.parsers.DocumentBuilderFactory

class RecommendedScopeRepositoryTest {

    private class SimpleMemoryPrefs : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? =
            if (key == null || !map.containsKey(key)) defValue else map[key] as String?
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (key == null || !map.containsKey(key)) defValues else (map[key] as? Set<String>)?.let { HashSet(it) } ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            if (key == null || !map.containsKey(key)) defValue else map[key] as Int
        override fun getLong(key: String?, defValue: Long): Long =
            if (key == null || !map.containsKey(key)) defValue else map[key] as Long
        override fun getFloat(key: String?, defValue: Float): Float =
            if (key == null || !map.containsKey(key)) defValue else map[key] as Float
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            if (key == null || !map.containsKey(key)) defValue else map[key] as Boolean
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
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
            override fun remove(key: String?) = apply { if (key != null) pending[key] = this }
            override fun clear() = apply { clearFlag = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v === this) map.remove(k) else map[k] = v
                }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    @Test
    fun `recommended scope catalog contains all 16 structured entries with proper system flags`() {
        val items = RecommendedScopeRepository.getRecommendedScopes(PackageInstallChecker { false })
        assertEquals(16, items.size)

        val expectedPackages = setOf(
            "com.mockloc",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
            "com.autonavi.amapauto",
            "com.tencent.mm",
            "com.tencent.wework",
            "com.alibaba.android.rimet",
            "com.ss.android.lark",
            "com.sankuai.meituan",
            "com.dianping.v1",
            "com.sdu.didi.psnger",
            "me.ele",
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme",
            "android"
        )
        assertEquals(expectedPackages, items.map { it.packageName }.toSet())

        for (item in items) {
            assertTrue(item.packageName.isNotBlank())
            assertTrue(item.appName.isNotBlank())
            assertTrue(item.category.isNotBlank())
            assertTrue(item.description.isNotBlank())
        }

        val selfItem = items.first { it.packageName == "com.mockloc" }
        val androidItem = items.first { it.packageName == "android" }
        assertTrue(selfItem.isRequiredSystemScope)
        assertTrue(androidItem.isRequiredSystemScope)
        assertTrue(androidItem.description.contains("普通目标应用"))

        val targetItems = items.filter { !it.isRequiredSystemScope }
        assertEquals(14, targetItems.size)
    }

    @Test
    fun `getRecommendedScopes detects installed status via PackageManager and handles SecurityException`() {
        val pm = mock<PackageManager>()
        @Suppress("DEPRECATION")
        doThrow(PackageManager.NameNotFoundException("not installed"))
            .whenever(pm).getPackageInfo(any<String>(), any<Int>())

        @Suppress("DEPRECATION")
        doReturn(PackageInfo()).whenever(pm).getPackageInfo(eq("com.autonavi.minimap"), any<Int>())

        @Suppress("DEPRECATION")
        doThrow(SecurityException("visibility restricted"))
            .whenever(pm).getPackageInfo(eq("com.tencent.mm"), any<Int>())

        val items = RecommendedScopeRepository.getRecommendedScopes(pm)
        assertTrue(items.first { it.packageName == "com.autonavi.minimap" }.isInstalled)
        assertFalse(items.first { it.packageName == "com.tencent.mm" }.isInstalled)
        assertFalse(items.first { it.packageName == "com.baidu.BaiduMap" }.isInstalled)
    }

    @Test
    fun `all uninstalled checker returns 0 installed counts without crashing`() {
        val items = RecommendedScopeRepository.getRecommendedScopes(PackageInstallChecker { false })
        val summary = RecommendedScopeRepository.summarizeScopeStats(items)
        assertEquals(0, summary.installedTargetCount)
        assertEquals(14, summary.totalTargetCount)
        assertEquals(0, summary.installedAllCount)
        assertEquals(16, summary.totalAllCount)
    }

    @Test
    fun `arrays xml matches getXposedScopeArrayPackages 1 to 1 and passes E2E compliance check`() {
        val arraysFile = WorkspacePathResolver.xposedScopeArraysFile()
        assertTrue("arrays.xml must exist", arraysFile.isFile)
        val xmlContent = arraysFile.readText(Charsets.UTF_8)

        val report = StaticManifestAndResourceChecker.checkXposedScopeArrayXml(xmlContent)
        assertTrue("arrays.xml compliance violations: ${report.violations}", report.passed)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(arraysFile)
        val items = doc.getElementsByTagName("item")
        val xmlPackages = (0 until items.length).map { items.item(it).textContent.trim() }

        assertEquals(15, xmlPackages.size)
        assertFalse("android must not appear in arrays.xml", "android" in xmlPackages)
        assertEquals(RecommendedScopeRepository.getXposedScopeArrayPackages(), xmlPackages)
    }

    @Test
    fun `addAllInstalledToWhitelist imports only installed target apps and skips self and android`() {
        val configPrefs = SimpleMemoryPrefs()
        val metaPrefs = SimpleMemoryPrefs()
        val configRepo = MockConfigRepository(prefs = configPrefs)
        val syncCallbacks = mutableListOf<Set<String>>()

        val whitelistRepo = WhitelistRepository(
            configRepository = configRepo,
            metaPrefs = metaPrefs,
            packageQuerySource = PackageQuerySource { emptyList() },
            prepopulateOnFirstInit = false,
            onPackagesSynced = { _, affected -> syncCallbacks.add(affected) },
            ioDispatcher = Dispatchers.Unconfined
        )

        val installed = setOf(
            "com.mockloc",
            "android",
            "com.tencent.mm",
            "com.autonavi.minimap"
        )
        val result = RecommendedScopeRepository.addAllInstalledToWhitelist(
            checker = PackageInstallChecker { pkg -> pkg in installed },
            whitelistRepository = whitelistRepo
        )

        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), result.importedPackages)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), result.totalSelectedPackages)
        assertEquals(setOf("com.mockloc", "android"), result.skippedSpecialPackages)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), whitelistRepo.getSelectedPackages())
        assertEquals(1, syncCallbacks.size)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), syncCallbacks[0])
    }

    @Test
    fun `getRecommendedScopesAsync returns identical catalog on IO dispatcher`() = runTest {
        val pm = mock<PackageManager>()
        @Suppress("DEPRECATION")
        doReturn(PackageInfo()).whenever(pm).getPackageInfo(eq("com.tencent.mm"), any<Int>())
        @Suppress("DEPRECATION")
        doThrow(PackageManager.NameNotFoundException()).whenever(pm).getPackageInfo(eq("com.baidu.BaiduMap"), any<Int>())

        val asyncItems = RecommendedScopeRepository.getRecommendedScopesAsync(pm, Dispatchers.Unconfined)
        assertEquals(16, asyncItems.size)
        assertTrue(asyncItems.first { it.packageName == "com.tencent.mm" }.isInstalled)
        assertFalse(asyncItems.first { it.packageName == "com.baidu.BaiduMap" }.isInstalled)
    }
}
