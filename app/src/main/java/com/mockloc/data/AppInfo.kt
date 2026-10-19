package com.mockloc.data

/**
 * 已安装应用信息数据模型，用于目标应用白名单分流配置。
 *
 * @property packageName 应用程序包名
 * @property appName 应用程序显示名称
 * @property isSystemApp 是否为系统预装应用
 * @property isSelected 是否被选中加入模拟定位生效白名单（严格 opt-in 语义）
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    var isSelected: Boolean = false
)
