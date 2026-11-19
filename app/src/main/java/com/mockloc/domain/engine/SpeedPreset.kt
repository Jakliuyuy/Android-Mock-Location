package com.mockloc.domain.engine

enum class SpeedPreset(val speedMps: Double, val displayName: String) {
    WALK(1.4, "步行 (5 km/h)"),
    BIKE(4.2, "骑行 (15 km/h)"),
    DRIVE(16.7, "驾车 (60 km/h)"),
    CUSTOM(5.0, "自定义")
}
