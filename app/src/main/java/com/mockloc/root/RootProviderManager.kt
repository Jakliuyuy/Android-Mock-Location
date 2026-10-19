package com.mockloc.root

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.SystemClock

/**
 * Root 原生 Provider 模拟定位引擎管理器。
 * 通过 Root 权限授予系统 ACCESS_MOCK_LOCATION 权限，并利用 LocationManager 的 TestProvider 接口向系统注册和投递模拟位置。
 */
class RootProviderManager(
    private val context: Context,
    private val locationManager: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager,
    var locationFactory: (String) -> Location = { Location(it) }
) {

    @Volatile
    private var _isRunning: Boolean = false

    val isRunning: Boolean
        get() = _isRunning


    /**
     * 检查设备是否存在 su 命令及有效 Root 提权环境。
     */
    fun checkSuAvailable(): Boolean = ShellUtil.checkSuAvailable()

    /**
     * 使用 Root 权限为指定应用授予 AppOps 模拟定位权限。
     */
    fun grantMockPermissionWithSu(packageName: String): Boolean =
        ShellUtil.grantMockLocationPermission(packageName)

    /**
     * 启动原生 TestProvider 模拟定位。
     *
     * @param lat 目标纬度
     * @param lng 目标经度
     * @param alt 目标海拔高度（米）
     * @param speed 移动速度（米/秒）
     * @param bearing 移动航向/方位角（度）
     * @param accuracy 定位精度（米）
     * @param provider 定位提供者名称，默认 LocationManager.GPS_PROVIDER
     * @throws SecurityException 当缺少模拟定位权限（未授予 appops 或未开启开发者模拟定位）时抛出
     */
    @Synchronized
    fun startMock(
        lat: Double,
        lng: Double,
        alt: Double = 0.0,
        speed: Float = 0.0f,
        bearing: Float = 0.0f,
        accuracy: Float = 3.0f,
        provider: String = LocationManager.GPS_PROVIDER
    ) {
        try {
            // 1. 尝试清理系统中可能遗留的陈旧 TestProvider
            try {
                locationManager.removeTestProvider(provider)
            } catch (_: Throwable) {
                // 若不存在该 TestProvider 则忽略
            }

            // 2. 注册系统 TestProvider
            locationManager.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )

            // 3. 启用 TestProvider
            locationManager.setTestProviderEnabled(provider, true)

            // 4. 设置 Provider 状态为可用（兼容低版本 API，高版本安全容错）
            try {
                @Suppress("DEPRECATION")
                locationManager.setTestProviderStatus(
                    provider,
                    LocationProvider.AVAILABLE,
                    null,
                    System.currentTimeMillis()
                )
            } catch (_: Throwable) {
                // Android 11+ 部分系统废弃该方法，忽略不支持异常
            }

            _isRunning = true

            // 5. 立即投递第一批模拟坐标
            updateLocation(lat, lng, alt, speed, bearing, accuracy, provider)
        } catch (e: SecurityException) {
            _isRunning = false
            throw e
        } catch (e: Throwable) {
            _isRunning = false
            throw e
        }
    }

    /**
     * 更新当前 TestProvider 的模拟定位坐标。
     *
     * @param lat 目标纬度
     * @param lng 目标经度
     * @param alt 目标海拔高度（米）
     * @param speed 移动速度（米/秒）
     * @param bearing 移动航向/方位角（度）
     * @param accuracy 定位精度（米）
     * @param provider 定位提供者名称，默认 LocationManager.GPS_PROVIDER
     */
    @Synchronized
    fun updateLocation(
        lat: Double,
        lng: Double,
        alt: Double = 0.0,
        speed: Float = 0.0f,
        bearing: Float = 0.0f,
        accuracy: Float = 3.0f,
        provider: String = LocationManager.GPS_PROVIDER
    ) {
        if (!_isRunning) return

        val location = locationFactory(provider).apply {
            latitude = lat
            longitude = lng
            altitude = alt
            this.speed = speed
            this.bearing = bearing
            this.accuracy = if (accuracy > 0.0f) accuracy else 3.0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        locationManager.setTestProviderLocation(provider, location)
    }

    /**
     * 停止原生 TestProvider 模拟定位并从系统中注销。
     */
    @Synchronized
    fun stopMock(provider: String = LocationManager.GPS_PROVIDER) {
        if (!_isRunning) return

        try {
            locationManager.setTestProviderEnabled(provider, false)
        } catch (_: Throwable) {
            // 忽略禁用异常
        }

        try {
            locationManager.removeTestProvider(provider)
        } catch (_: Throwable) {
            // 忽略移除异常
        }

        _isRunning = false
    }
}
