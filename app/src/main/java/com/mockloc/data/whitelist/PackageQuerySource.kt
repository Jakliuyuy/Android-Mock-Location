package com.mockloc.data.whitelist

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 轻量应用扫描记录，完全解耦 Android Framework，便于 JVM 单元测试。
 */
data class InstalledAppRecord(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

/**
 * 已安装应用查询抽象，默认实现委托给 [AndroidPackageQuerySource]。
 */
fun interface PackageQuerySource {
    suspend fun queryInstalledApps(): List<InstalledAppRecord>
}

class AndroidPackageQuerySource(
    private val packageManager: PackageManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PackageQuerySource {

    override suspend fun queryInstalledApps(): List<InstalledAppRecord> = withContext(ioDispatcher) {
        val rawPackages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(0)
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        } ?: return@withContext emptyList()

        val deduplicated = LinkedHashMap<String, InstalledAppRecord>(rawPackages.size)
        for (info in rawPackages) {
            val pkg = info?.packageName?.trim().orEmpty()
            if (pkg.isEmpty() || deduplicated.containsKey(pkg)) continue

            val appInfo = info.applicationInfo
            val isSystem = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } else {
                false
            }
            val label = try {
                appInfo?.loadLabel(packageManager)?.toString()?.trim()
            } catch (_: Throwable) {
                null
            }.takeUnless { it.isNullOrEmpty() } ?: pkg

            deduplicated[pkg] = InstalledAppRecord(
                packageName = pkg,
                appName = label,
                isSystemApp = isSystem
            )
        }
        deduplicated.values.toList()
    }
}
