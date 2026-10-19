package com.mockloc.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.mockloc.ipc.LocationConfigProvider

/**
 * 目标应用白名单数据仓库。
 * 负责已安装应用列表的获取、系统应用过滤、关键字检索、选中状态持久化，
 * 以及与 MockConfigManager / LocationConfigProvider 的实时跨进程同步。
 */
class WhitelistRepository(
    private val prefs: SharedPreferences? = null,
    private val context: Context? = null
) {

    companion object {
        const val PREFS_NAME = "mockloc_whitelist_prefs"
        const val KEY_WHITELIST_PACKAGES = "key_whitelist_packages"

        @Volatile
        private var instance: WhitelistRepository? = null

        fun getInstance(context: Context): WhitelistRepository {
            return instance ?: synchronized(this) {
                instance ?: WhitelistRepository(
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    context = context.applicationContext
                ).also { instance = it }
            }
        }
    }

    // 内存存储备份（供测试或无 SharedPreferences 场景使用）
    private val inMemorySelectedPackages = mutableSetOf<String>()

    init {
        // 初始化时将持久化存储中的白名单同步到 MockConfigManager
        val initialPackages = loadSelectedPackages()
        MockConfigManager.setTargetPackages(initialPackages)
    }

    /**
     * 从 PackageManager 查询系统中所有已安装应用。
     */
    fun loadInstalledApps(packageManager: PackageManager, excludeSelf: String? = null): List<AppInfo> {
        val selectedSet = loadSelectedPackages()
        val installedPackages = try {
            packageManager.getInstalledPackages(0)
        } catch (t: Throwable) {
            emptyList()
        }

        val result = mutableListOf<AppInfo>()
        for (pkgInfo in installedPackages) {
            val pkgName = pkgInfo.packageName ?: continue
            if (excludeSelf != null && pkgName == excludeSelf) {
                continue
            }
            val appInfo = pkgInfo.applicationInfo
            val isSystemApp = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } else {
                false
            }
            val appName = try {
                appInfo?.loadLabel(packageManager)?.toString() ?: pkgName
            } catch (_: Throwable) {
                pkgName
            }

            result.add(
                AppInfo(
                    packageName = pkgName,
                    appName = appName,
                    isSystemApp = isSystemApp,
                    isSelected = selectedSet.contains(pkgName)
                )
            )
        }

        // 默认按应用名称字母顺序排序，已选中的优先排在前面
        return result.sortedWith(
            compareByDescending<AppInfo> { it.isSelected }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        )
    }

    /**
     * 过滤应用列表：支持根据关键字（应用名或包名）与是否显示系统应用进行筛选。
     */
    fun filterApps(
        apps: List<AppInfo>,
        query: String?,
        showSystemApps: Boolean
    ): List<AppInfo> {
        val trimmedQuery = query?.trim()?.lowercase().orEmpty()

        return apps.filter { app ->
            // 系统应用过滤：若不显示系统应用，则仅保留非系统应用（已被选中的除外，保证已选应用不丢失）
            val systemAppAllowed = showSystemApps || !app.isSystemApp || app.isSelected
            if (!systemAppAllowed) {
                return@filter false
            }

            if (trimmedQuery.isEmpty()) {
                true
            } else {
                app.appName.lowercase().contains(trimmedQuery) ||
                        app.packageName.lowercase().contains(trimmedQuery)
            }
        }
    }

    /**
     * 批量切换全选 / 取消全选。
     */
    fun setAllSelected(apps: List<AppInfo>, selected: Boolean): List<AppInfo> {
        apps.forEach { it.isSelected = selected }
        val updatedSet = if (selected) {
            val current = loadSelectedPackages().toMutableSet()
            current.addAll(apps.map { it.packageName })
            current
        } else {
            val current = loadSelectedPackages().toMutableSet()
            current.removeAll(apps.map { it.packageName }.toSet())
            current
        }
        saveSelectedPackages(updatedSet)
        return apps
    }

    /**
     * 切换单个应用选中状态并持久化。
     */
    fun toggleAppSelection(packageName: String, isSelected: Boolean) {
        val current = loadSelectedPackages().toMutableSet()
        if (isSelected) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        saveSelectedPackages(current)
    }

    /**
     * 读取已持久化的目标应用包名白名单。
     */
    fun loadSelectedPackages(): Set<String> {
        return if (prefs != null) {
            val set = prefs.getStringSet(KEY_WHITELIST_PACKAGES, null)
            set?.toSet() ?: emptySet()
        } else {
            inMemorySelectedPackages.toSet()
        }
    }

    /**
     * 持久化目标应用包名白名单，并即时同步到 MockConfigManager。
     */
    fun saveSelectedPackages(packages: Set<String>) {
        if (prefs != null) {
            prefs.edit().putStringSet(KEY_WHITELIST_PACKAGES, packages).apply()
        } else {
            inMemorySelectedPackages.clear()
            inMemorySelectedPackages.addAll(packages)
        }
        // 即时同步全局配置
        MockConfigManager.setTargetPackages(packages)

        // 若具备 Context，向 ContentProvider 触发同步
        context?.let { ctx ->
            try {
                val bundle = MockConfigManager.toBundle()
                ctx.contentResolver.call(
                    LocationConfigProvider.CONTENT_URI,
                    MockConfigManager.METHOD_SET_CONFIG,
                    null,
                    bundle
                )
            } catch (_: Throwable) {
                // Ignore in pure test or uninitialized provider environment
            }
        }
    }
}
