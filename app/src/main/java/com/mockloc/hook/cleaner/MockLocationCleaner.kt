package com.mockloc.hook.cleaner

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 模拟定位特征深度剥离与清洗器 (Mock Location Triple-Sanitization Cleaner)
 *
 * 实施三层深度脱敏与特征清洗：
 * 1. 业务层/API 层脱敏：调用 Android 12+ (API 31+) 官方隐藏/公开方法 `setMock(false)`。
 * 2. 底层位图与反射脱敏：直接反射抹除 AOSP `Location.mFieldsMask` 第 4 位标志位（HAS_MOCK_PROVIDER_MASK = 16），
 *    并清除旧版 Android (4.x - 7.x) 的 `mIsFromMockProvider` 私有布尔标志位。
 * 3. 附加包与元数据清洗：清除 Extras 中的 `"mockLocation"`, `"mock"`, `"isFromMockProvider"` 键，
 *    并注入逼真的精度、速度、方位角、系统时钟与纳秒级相对时间戳。
 */
object MockLocationCleaner {

    /**
     * AOSP Location.java HAS_MOCK_PROVIDER_MASK = (1 << 4) = 16
     */
    const val HAS_MOCK_PROVIDER_MASK: Int = 16

    private var setMockMethod: Method? = null
    private var fieldsMaskField: Field? = null
    private var isFromMockProviderField: Field? = null
    private var reflectionInitialized: Boolean = false

    init {
        initReflection()
    }

    @Synchronized
    private fun initReflection() {
        if (reflectionInitialized) return

        // 1. Android 12+ (API 31+) setMock(boolean)
        try {
            val method = Location::class.java.getDeclaredMethod("setMock", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            setMockMethod = method
        } catch (_: Throwable) {}

        // 2. Android 8.0 - 15.0 mFieldsMask
        try {
            val field = findFieldInHierarchy(Location::class.java, "mFieldsMask")
            field?.isAccessible = true
            fieldsMaskField = field
        } catch (_: Throwable) {}

        // 3. Legacy Android 4.x - 7.x mIsFromMockProvider
        try {
            val field = findFieldInHierarchy(Location::class.java, "mIsFromMockProvider")
            field?.isAccessible = true
            isFromMockProviderField = field
        } catch (_: Throwable) {}

        reflectionInitialized = true
    }

    /**
     * 沿着类继承体系向上递归查找指定名称的字段，兼容定制 ROM 或派生类
     */
    private fun findFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /**
     * 清洗并注入拟真坐标与状态
     *
     * @param location 待清洗的 Android Location 实例
     * @param lat 目标纬度
     * @param lng 目标经度
     * @param alt 目标海拔（米）
     * @param speed 目标速度（米/秒）
     * @param bearing 目标方位角（度）
     * @param accuracy 目标精度（米）
     */
    @JvmStatic
    @JvmOverloads
    fun sanitize(
        location: Location?,
        lat: Double,
        lng: Double,
        alt: Double = 0.0,
        speed: Float = 0.0f,
        bearing: Float = 0.0f,
        accuracy: Float = 3.0f
    ) {
        if (location == null) return

        // 1. 注入核心物理坐标与拟真属性
        location.latitude = lat
        location.longitude = lng
        location.altitude = alt
        location.speed = speed
        location.bearing = bearing
        location.accuracy = if (accuracy > 0.0f) accuracy else 3.0f
        location.time = System.currentTimeMillis()

        var elapsedNanos: Long = 0L
        try {
            elapsedNanos = SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {}
        if (elapsedNanos <= 0L) {
            elapsedNanos = System.nanoTime()
        }
        try {
            location.elapsedRealtimeNanos = elapsedNanos
        } catch (_: Throwable) {}

        // 2. 官方 API 清除：调用 API 31+ setMock(false)
        setMockMethod?.let { method ->
            try {
                method.invoke(location, false)
            } catch (_: Throwable) {}
        }

        // 3. 底层位图反射剥除：抹除 mFieldsMask 的 HAS_MOCK_PROVIDER_MASK 位 (bit 4)
        stripFieldsMaskBit(location)

        // 4. 清除旧版本布尔标志位 mIsFromMockProvider
        stripLegacyMockFlag(location)

        // 5. 彻底清洗 Extras Bundle
        cleanExtras(location)
    }

    /**
     * 清除 Location 内部的 mFieldsMask 模拟位
     */
    private fun stripFieldsMaskBit(location: Location) {
        val field = fieldsMaskField ?: findFieldInHierarchy(location.javaClass, "mFieldsMask")
        if (field != null) {
            try {
                field.isAccessible = true
                when (field.type) {
                    Int::class.javaPrimitiveType, Int::class.javaObjectType -> {
                        val currentMask = field.getInt(location)
                        field.setInt(location, currentMask and HAS_MOCK_PROVIDER_MASK.inv())
                    }
                    Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> {
                        val currentMask = field.getByte(location).toInt()
                        field.setByte(location, (currentMask and HAS_MOCK_PROVIDER_MASK.inv()).toByte())
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * 清除旧版 Android mIsFromMockProvider 私有标志
     */
    private fun stripLegacyMockFlag(location: Location) {
        val field = isFromMockProviderField ?: findFieldInHierarchy(location.javaClass, "mIsFromMockProvider")
        if (field != null) {
            try {
                field.isAccessible = true
                field.setBoolean(location, false)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 移除 Bundle 中常见的模拟定位标志 key
     */
    private fun cleanExtras(location: Location) {
        try {
            val extras: Bundle? = location.extras
            if (extras != null) {
                extras.remove("mockLocation")
                extras.remove("mock")
                extras.remove("isFromMockProvider")
            }
        } catch (_: Throwable) {}
    }
}
