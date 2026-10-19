package com.mockloc.data

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import com.mockloc.hook.cleaner.MockLocationCleaner

/**
 * 模拟定位与防检测全局配置管理器。
 * 维护当前进程与跨进程同步的坐标、开关、目标白名单应用等状态。
 */
object MockConfigManager {

    const val AUTHORITY = "com.mockloc.configprovider"
    const val METHOD_GET_CONFIG = "getConfig"
    const val METHOD_SET_CONFIG = "setConfig"
    const val ACTION_CONFIG_CHANGED = "com.mockloc.ACTION_CONFIG_CHANGED"

    // Bundle Key 约定
    const val KEY_IS_MOCK_ENABLED = "key_is_mock_enabled"
    const val KEY_LATITUDE = "key_latitude"
    const val KEY_LONGITUDE = "key_longitude"
    const val KEY_ALTITUDE = "key_altitude"
    const val KEY_SPEED = "key_speed"
    const val KEY_BEARING = "key_bearing"
    const val KEY_ACCURACY = "key_accuracy"
    const val KEY_BLOCK_CELL = "key_block_cell"
    const val KEY_BLOCK_WIFI = "key_block_wifi"
    const val KEY_TARGET_PACKAGES = "key_target_packages"

    // 默认北京天安门坐标 (39.9087, 116.3975)
    @Volatile
    var isMockEnabled: Boolean = false

    @Volatile
    var latitude: Double = 39.9087

    @Volatile
    var longitude: Double = 116.3975

    @Volatile
    var altitude: Double = 50.0

    @Volatile
    var speed: Float = 0.0f

    @Volatile
    var bearing: Float = 0.0f

    @Volatile
    var accuracy: Float = 3.0f

    @Volatile
    var isCellInterceptionEnabled: Boolean = true

    @Volatile
    var isWifiInterceptionEnabled: Boolean = true

    private val targetPackagesSet: MutableSet<String> = mutableSetOf()

    @Volatile
    var currentProcessPackage: String? = null

    @Volatile
    var isConfigSynced: Boolean = false

    /**
     * 设置或更新目标应用白名单
     */
    @Synchronized
    fun setTargetPackages(packages: Collection<String>) {
        targetPackagesSet.clear()
        targetPackagesSet.addAll(packages)
    }

    /**
     * 获取目标应用白名单副本
     */
    @Synchronized
    fun getTargetPackages(): Set<String> {
        return HashSet(targetPackagesSet)
    }

    /**
     * 添加单个目标应用
     */
    @Synchronized
    fun addTargetPackage(pkg: String) {
        targetPackagesSet.add(pkg)
    }

    /**
     * 移除单个目标应用
     */
    @Synchronized
    fun removeTargetPackage(pkg: String) {
        targetPackagesSet.remove(pkg)
    }

    /**
     * 检查指定包名是否在生效列表中且模拟定位已开启。
     * 严格遵循 §R1 要求：必须显式命中白名单，空白名单绝不匹配任何应用。
     */
    @Synchronized
    fun isMockEnabledForPackage(packageName: String?): Boolean {
        if (!isMockEnabled || packageName.isNullOrEmpty()) return false
        return targetPackagesSet.contains(packageName)
    }

    /**
     * 检查当前进程是否需要拦截与伪造
     */
    fun isMockEnabledForCurrentProcess(): Boolean {
        val current = currentProcessPackage
        return if (current != null) {
            isMockEnabledForPackage(current)
        } else {
            false
        }
    }

    @Volatile
    var locationFactory: (String) -> Location = { Location(it) }

    /**
     * 创建经过彻底脱敏的伪造 Location 实例
     */
    @JvmOverloads
    fun createMockLocation(provider: String? = LocationManager.GPS_PROVIDER): Location {
        val effectiveProvider = provider ?: LocationManager.GPS_PROVIDER
        val loc = locationFactory(effectiveProvider)
        MockLocationCleaner.sanitize(
            location = loc,
            lat = latitude,
            lng = longitude,
            alt = altitude,
            speed = speed,
            bearing = bearing,
            accuracy = accuracy
        )
        return loc
    }

    /**
     * 导出为 Bundle 供跨进程 ContentProvider 传递
     */
    @Synchronized
    fun toBundle(): Bundle {
        val bundle = Bundle()
        bundle.putBoolean(KEY_IS_MOCK_ENABLED, isMockEnabled)
        bundle.putDouble(KEY_LATITUDE, latitude)
        bundle.putDouble(KEY_LONGITUDE, longitude)
        bundle.putDouble(KEY_ALTITUDE, altitude)
        bundle.putFloat(KEY_SPEED, speed)
        bundle.putFloat(KEY_BEARING, bearing)
        bundle.putFloat(KEY_ACCURACY, accuracy)
        bundle.putBoolean(KEY_BLOCK_CELL, isCellInterceptionEnabled)
        bundle.putBoolean(KEY_BLOCK_WIFI, isWifiInterceptionEnabled)
        bundle.putStringArrayList(KEY_TARGET_PACKAGES, ArrayList(targetPackagesSet))
        return bundle
    }

    /**
     * 从跨进程 Bundle 同步更新本地配置
     */
    @Synchronized
    fun updateFromBundle(bundle: Bundle?) {
        if (bundle == null) return
        if (bundle.containsKey(KEY_IS_MOCK_ENABLED)) {
            isMockEnabled = bundle.getBoolean(KEY_IS_MOCK_ENABLED)
        }
        if (bundle.containsKey(KEY_LATITUDE)) {
            latitude = bundle.getDouble(KEY_LATITUDE)
        }
        if (bundle.containsKey(KEY_LONGITUDE)) {
            longitude = bundle.getDouble(KEY_LONGITUDE)
        }
        if (bundle.containsKey(KEY_ALTITUDE)) {
            altitude = bundle.getDouble(KEY_ALTITUDE)
        }
        if (bundle.containsKey(KEY_SPEED)) {
            speed = bundle.getFloat(KEY_SPEED)
        }
        if (bundle.containsKey(KEY_BEARING)) {
            bearing = bundle.getFloat(KEY_BEARING)
        }
        if (bundle.containsKey(KEY_ACCURACY)) {
            accuracy = bundle.getFloat(KEY_ACCURACY)
        }
        if (bundle.containsKey(KEY_BLOCK_CELL)) {
            isCellInterceptionEnabled = bundle.getBoolean(KEY_BLOCK_CELL)
        }
        if (bundle.containsKey(KEY_BLOCK_WIFI)) {
            isWifiInterceptionEnabled = bundle.getBoolean(KEY_BLOCK_WIFI)
        }
        if (bundle.containsKey(KEY_TARGET_PACKAGES)) {
            val list = bundle.getStringArrayList(KEY_TARGET_PACKAGES)
            if (list != null) {
                targetPackagesSet.clear()
                targetPackagesSet.addAll(list)
            }
        }
        isConfigSynced = true
    }
}

/**
 * 兼容规范引用的 ConfigManager 类型别名
 */
typealias ConfigManager = MockConfigManager
