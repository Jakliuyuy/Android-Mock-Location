package com.mockloc.domain.engine

/**
 * 常用模拟移动速度预设档位。
 *
 * @property speedMps 速度值（米/秒）
 * @property label 预设标签描述
 */
enum class SpeedPreset(val speedMps: Float, val label: String) {
    /** 步行模式：1.2 m/s (~4.3 km/h) */
    WALK(1.2f, "步行 (~4.3 km/h)"),

    /** 骑行模式：4.5 m/s (~16.2 km/h) */
    BIKE(4.5f, "骑行 (~16.2 km/h)"),

    /** 驾车模式：12.0 m/s (~43.2 km/h) */
    DRIVE(12.0f, "驾车 (~43.2 km/h)"),

    /** 自定义模式：可自由配置速度 */
    CUSTOM(0.0f, "自定义");

    /** 对应的千米/小时数值 */
    val speedKmh: Float
        get() = speedMps * 3.6f
}
