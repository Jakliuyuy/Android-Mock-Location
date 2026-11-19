package com.mockloc.data.scope

import android.content.pm.PackageManager
import android.os.Build
import com.mockloc.data.whitelist.WhitelistRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface PackageInstallChecker {
    fun isPackageInstalled(packageName: String): Boolean
}

class AndroidPackageInstallChecker(
    private val packageManager: PackageManager
) : PackageInstallChecker {
    override fun isPackageInstalled(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(trimmed, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(trimmed, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }
}

data class ScopeSummary(
    val installedTargetCount: Int,
    val totalTargetCount: Int,
    val installedAllCount: Int,
    val totalAllCount: Int
)

data class WhitelistBatchImportResult(
    val importedPackages: Set<String>,
    val totalSelectedPackages: Set<String>,
    val skippedSpecialPackages: Set<String>
)

object RecommendedScopeRepository {

    const val SELF_PACKAGE_NAME = "com.mockloc"
    const val ANDROID_FRAMEWORK_PACKAGE = "android"

    private data class ScopeSpec(
        val packageName: String,
        val appName: String,
        val category: String,
        val description: String,
        val isRequiredSystemScope: Boolean,
        val includeInXposedScopeArray: Boolean
    )

    private val RECOMMENDED_SPECS: List<ScopeSpec> = listOf(
        ScopeSpec(
            packageName = SELF_PACKAGE_NAME,
            appName = "MockLocation（本模块）",
            category = "模块自身",
            description = "用于 LSPosed 模块激活状态自检与配置读取；仅在 LSPosed 作用域勾选，不加入目标模拟白名单",
            isRequiredSystemScope = true,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.autonavi.minimap",
            appName = "高德地图",
            category = "地图导航",
            description = "拦截系统原生及高德 AMap SDK 定位，自动将 WGS-84 坐标转换为 GCJ-02",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.baidu.BaiduMap",
            appName = "百度地图",
            category = "地图导航",
            description = "拦截系统原生及百度 BD SDK 定位，自动将 WGS-84 坐标转换为 BD-09",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.tencent.map",
            appName = "腾讯地图",
            category = "地图导航",
            description = "拦截系统原生及腾讯定位 SDK，自动映射 GCJ-02 坐标系",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.autonavi.amapauto",
            appName = "高德地图车机版",
            category = "地图导航",
            description = "拦截车载版高德导航与 AMap SDK 定位，自动映射 GCJ-02 坐标系",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.tencent.mm",
            appName = "微信",
            category = "社交办公",
            description = "拦截微信发送位置、共享实时位置、朋友圈与小程序腾讯定位 SDK（GCJ-02）",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.tencent.wework",
            appName = "企业微信",
            category = "社交办公",
            description = "拦截企业微信外勤与考勤打卡定位，建议同时开启基站与 Wi-Fi 反检测",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.alibaba.android.rimet",
            appName = "钉钉",
            category = "社交办公",
            description = "拦截钉钉考勤打卡定位，配合高德 SDK 与基站/Wi-Fi/GNSS 反检测",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.ss.android.lark",
            appName = "飞书",
            category = "社交办公",
            description = "拦截飞书考勤打卡与办公定位接口，配合基站/Wi-Fi 反检测",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.sankuai.meituan",
            appName = "美团",
            category = "出行生活",
            description = "拦截美团外卖配送地址、团购及周边商家定位（GCJ-02）",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.dianping.v1",
            appName = "大众点评",
            category = "出行生活",
            description = "拦截大众点评门店距离计算与签到打卡定位（GCJ-02）",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.sdu.didi.psnger",
            appName = "滴滴出行",
            category = "出行生活",
            description = "拦截滴滴出行上下车点定位与周边位置服务",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "me.ele",
            appName = "饿了么",
            category = "出行生活",
            description = "拦截饿了么外卖收货定位与附近商户检索",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.xingin.xhs",
            appName = "小红书",
            category = "内容社区",
            description = "拦截小红书同城推荐、笔记位置标签与门店打卡定位",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = "com.ss.android.ugc.aweme",
            appName = "抖音",
            category = "内容社区",
            description = "拦截抖音同城频道、作品发布位置与团购定位",
            isRequiredSystemScope = false,
            includeInXposedScopeArray = true
        ),
        ScopeSpec(
            packageName = ANDROID_FRAMEWORK_PACKAGE,
            appName = "Android 系统框架",
            category = "系统框架",
            description = "仅特定 Root/系统定位服务增强场景才涉及；普通目标应用仅需勾选对应目标应用即可，无需全局勾选系统框架",
            isRequiredSystemScope = true,
            includeInXposedScopeArray = false
        )
    )

    val DEFAULT_TARGET_RECOMMENDED_PACKAGES: Set<String> = RECOMMENDED_SPECS
        .asSequence()
        .filter { !it.isRequiredSystemScope }
        .map { it.packageName }
        .toSet()

    fun getRecommendedScopes(pm: PackageManager): List<RecommendedAppItem> {
        return getRecommendedScopes(AndroidPackageInstallChecker(pm))
    }

    fun getRecommendedScopes(checker: PackageInstallChecker): List<RecommendedAppItem> {
        return RECOMMENDED_SPECS.map { spec ->
            val installed = runCatching {
                checker.isPackageInstalled(spec.packageName)
            }.getOrDefault(false)
            RecommendedAppItem(
                packageName = spec.packageName,
                appName = spec.appName,
                category = spec.category,
                description = spec.description,
                isRequiredSystemScope = spec.isRequiredSystemScope,
                isInstalled = installed
            )
        }
    }

    suspend fun getRecommendedScopesAsync(
        pm: PackageManager,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): List<RecommendedAppItem> = withContext(ioDispatcher) {
        getRecommendedScopes(pm)
    }

    fun getXposedScopeArrayPackages(): List<String> {
        return RECOMMENDED_SPECS
            .asSequence()
            .filter { it.includeInXposedScopeArray }
            .map { it.packageName }
            .toList()
    }

    fun summarizeScopeStats(items: List<RecommendedAppItem>): ScopeSummary {
        val targetItems = items.filter { !it.isRequiredSystemScope }
        return ScopeSummary(
            installedTargetCount = targetItems.count { it.isInstalled },
            totalTargetCount = targetItems.size,
            installedAllCount = items.count { it.isInstalled },
            totalAllCount = items.size
        )
    }

    fun addAllInstalledToWhitelist(
        pm: PackageManager,
        whitelistRepository: WhitelistRepository
    ): WhitelistBatchImportResult {
        return addAllInstalledToWhitelist(AndroidPackageInstallChecker(pm), whitelistRepository)
    }

    fun addAllInstalledToWhitelist(
        checker: PackageInstallChecker,
        whitelistRepository: WhitelistRepository
    ): WhitelistBatchImportResult {
        val scopes = getRecommendedScopes(checker)
        val toImport = LinkedHashSet<String>()
        val skipped = LinkedHashSet<String>()

        for (item in scopes) {
            if (!item.isInstalled) continue
            if (item.isRequiredSystemScope ||
                item.packageName == SELF_PACKAGE_NAME ||
                item.packageName == ANDROID_FRAMEWORK_PACKAGE
            ) {
                skipped.add(item.packageName)
            } else {
                toImport.add(item.packageName)
            }
        }

        val finalSelected = if (toImport.isNotEmpty()) {
            whitelistRepository.addPackages(toImport)
        } else {
            whitelistRepository.getSelectedPackages()
        }

        return WhitelistBatchImportResult(
            importedPackages = toImport.toSet(),
            totalSelectedPackages = finalSelected,
            skippedSpecialPackages = skipped.toSet()
        )
    }
}
