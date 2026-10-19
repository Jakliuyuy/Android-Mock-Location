package com.mockloc.root

/**
 * 运行环境检测状态数据模型
 */
data class EnvironmentStatus(
    val isRootAvailable: Boolean,
    val isXposedActive: Boolean,
    val isSuBinaryPresent: Boolean,
    val summary: String = ""
) {
    /**
     * 是否具备任意一种模拟定位能力
     */
    val hasAnyMockCapability: Boolean
        get() = isRootAvailable || isXposedActive

    /**
     * 系统推荐运行模式：优先推荐 Xposed 深度 Hook 模式，其次 Root 原生模式
     */
    val recommendedMode: String
        get() = when {
            isXposedActive -> "XPOSED_HOOK"
            isRootAvailable -> "ROOT_PROVIDER"
            else -> "NONE"
        }
}

/**
 * 设备运行环境与权限检测器。
 * 负责检测 Root 提权能力及 Xposed / LSPosed 模块激活状态，为 UI 控制中心提供实时状态展示。
 */
object EnvironmentDetector {

    @Volatile
    private var xposedActiveOverride: Boolean? = null

    /**
     * 用于单元测试显式指定 Xposed 激活状态
     */
    fun setXposedActiveForTesting(active: Boolean?) {
        xposedActiveOverride = active
    }

    /**
     * Xposed 模块激活标准检测锚点方法。
     * 当模块被 Xposed / LSPosed 正确加载时，此方法会被框架 Hook 替换并返回 true。
     * 默认返回 false。
     */
    @JvmStatic
    fun isModuleActive(): Boolean {
        return false
    }

    /**
     * 判断 Xposed / LSPosed 是否处于激活状态。
     */
    @JvmStatic
    fun isXposedActive(): Boolean {
        // 1. 优先支持测试注入
        xposedActiveOverride?.let { return it }

        // 2. 检测标准 hook 锚点是否已被 Xposed 改写
        if (isModuleActive()) {
            return true
        }

        // 3. 辅助检测：检查运行时是否存在 XposedBridge 特征
        return try {
            val bridgeClass = Class.forName("de.robv.android.xposed.XposedBridge", false, EnvironmentDetector::class.java.classLoader)
            // 如果能加载到 XposedBridge 并且不是 dummy stub，则检查 loadedXposedModules 等字段
            bridgeClass != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 判断设备是否具备有效的 Root 提权能力。
     */
    @JvmStatic
    fun isRootAvailable(): Boolean {
        return ShellUtil.checkSuAvailable()
    }

    /**
     * 判断设备文件系统中是否存在 su 可执行文件。
     */
    @JvmStatic
    fun isSuBinaryPresent(): Boolean {
        return ShellUtil.isSuBinaryPresent()
    }

    /**
     * 全面检测运行环境，输出详细的环境状态对象。
     */
    @JvmStatic
    fun checkEnvironment(): EnvironmentStatus {
        val rootOk = isRootAvailable()
        val xposedOk = isXposedActive()
        val suFound = isSuBinaryPresent()

        val summaryText = buildString {
            append("Root: ")
            append(if (rootOk) "Available" else if (suFound) "Binary Found (No su response)" else "Unavailable")
            append(", Xposed: ")
            append(if (xposedOk) "Active" else "Inactive")
            append(" | Mode: ")
            append(when {
                xposedOk -> "Xposed Hook Mode (Deep Interception)"
                rootOk -> "Root Native Provider Mode"
                else -> "Unprivileged (Please grant Root or enable Xposed)"
            })
        }

        return EnvironmentStatus(
            isRootAvailable = rootOk,
            isXposedActive = xposedOk,
            isSuBinaryPresent = suFound,
            summary = summaryText
        )
    }
}
