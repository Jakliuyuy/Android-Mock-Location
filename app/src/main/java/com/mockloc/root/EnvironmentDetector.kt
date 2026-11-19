package com.mockloc.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.mockloc.R
import com.mockloc.service.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EnvironmentStatus(
    val isLSPosedActive: Boolean,
    val xposedApiVersion: Int,
    val isRootAvailable: Boolean,
    val rootProviderName: String,
    val hasLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val recommendedScopeInstalledCount: Int,
    val recommendedScopeTotalCount: Int
)

object EnvironmentDetector {

    private val FALLBACK_RECOMMENDED_PACKAGES = listOf(
        "com.mockloc",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.tencent.map",
        "com.tencent.mm",
        "com.alibaba.android.rimet",
        "com.tencent.wework",
        "com.ss.android.lark",
        "com.sankuai.meituan",
        "com.dianping.v1"
    )

    @Volatile
    private var defaultActiveState: Boolean = false

    @Volatile
    private var defaultApiVersion: Int = 0

    // 由 XposedInit 在加载 com.mockloc 包时 Hook 这两个非内联静态方法
    @JvmStatic
    fun isModuleActive(): Boolean {
        return defaultActiveState
    }

    @JvmStatic
    fun getXposedApiVersion(): Int {
        return defaultApiVersion
    }

    suspend fun detectEnvironment(context: Context): EnvironmentStatus =
        detectEnvironment(context, ShellUtil)

    suspend fun detectEnvironment(
        context: Context,
        shellExecutor: ShellExecutor,
        sdkInt: Int = Build.VERSION.SDK_INT,
        packageInstalledChecker: ((PackageManager, String, Int) -> Boolean)? = null
    ): EnvironmentStatus = withContext(Dispatchers.IO) {
        val lsposedActive = runCatching { isModuleActive() }.getOrDefault(false)
        val xposedApiVersion = runCatching { getXposedApiVersion() }.getOrDefault(0).coerceAtLeast(0)

        val isRootAvailable = runCatching { shellExecutor.hasRootAccess() }.getOrDefault(false)
        val effectiveRootProvider = if (!isRootAvailable) {
            "None"
        } else {
            val detected = runCatching {
                shellExecutor.detectRootProvider()
            }.getOrDefault("None")
            if (detected == "None" || detected.isBlank()) "Root" else detected
        }

        val hasLocationPermission = runCatching {
            PermissionChecker.hasLocationPermission(context)
        }.getOrDefault(false)
        val hasNotificationPermission = runCatching {
            PermissionChecker.hasNotificationPermission(context, sdkInt)
        }.getOrDefault(false)

        val scopePackages = resolveRecommendedPackages(context)
        val pm = runCatching { context.packageManager }.getOrNull()
        val checker = packageInstalledChecker ?: ::defaultIsPackageInstalled
        var installedCount = 0
        if (pm != null) {
            for (pkg in scopePackages) {
                val installed = runCatching { checker(pm, pkg, sdkInt) }.getOrDefault(false)
                if (installed) installedCount++
            }
        }

        EnvironmentStatus(
            isLSPosedActive = lsposedActive,
            xposedApiVersion = xposedApiVersion,
            isRootAvailable = isRootAvailable,
            rootProviderName = effectiveRootProvider,
            hasLocationPermission = hasLocationPermission,
            hasNotificationPermission = hasNotificationPermission,
            recommendedScopeInstalledCount = installedCount,
            recommendedScopeTotalCount = scopePackages.size
        )
    }

    private fun resolveRecommendedPackages(context: Context): List<String> {
        val fromResources = runCatching {
            context.resources?.getStringArray(R.array.xposed_scope)?.toList()
        }.getOrNull()

        val raw = if (!fromResources.isNullOrEmpty()) fromResources else FALLBACK_RECOMMENDED_PACKAGES
        return raw.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    private fun defaultIsPackageInstalled(
        pm: PackageManager,
        packageName: String,
        sdkInt: Int
    ): Boolean {
        return try {
            val info = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info != null
        } catch (_: Throwable) {
            false
        }
    }
}
