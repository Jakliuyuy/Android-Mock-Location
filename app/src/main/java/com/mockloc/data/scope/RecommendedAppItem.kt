package com.mockloc.data.scope

data class RecommendedAppItem(
    val packageName: String,
    val appName: String,
    val category: String,
    val description: String,
    val isRequiredSystemScope: Boolean,
    val isInstalled: Boolean
)
