package com.mockloc.domain.model

data class AppScopeInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean = false,
    val isRecommended: Boolean = false
)
