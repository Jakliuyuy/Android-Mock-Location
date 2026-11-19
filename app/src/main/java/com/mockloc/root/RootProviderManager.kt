package com.mockloc.root

import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.mockloc.domain.model.GeoPoint
import com.mockloc.service.RootLocationDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RootProviderManager internal constructor(
    private val context: Context,
    private val shellExecutor: ShellExecutor = ShellUtil,
    private val backendFactory: (Context) -> TestProviderBackend = { ctx -> AndroidTestProviderBackend(ctx) },
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val userIdProvider: () -> Int = { Process.myUid() / 100_000 },
    private val uidProvider: () -> Int = { Process.myUid() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RootLocationDriver {

    internal interface TestProviderBackend {
        fun addTestProvider(provider: String, sdkInt: Int)
        fun setTestProviderEnabled(provider: String, enabled: Boolean)
        fun setTestProviderLocation(
            provider: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            accuracy: Float,
            bearing: Float,
            speed: Float
        )
        fun removeTestProvider(provider: String)
        fun clearTestProviderLocation(provider: String) {}
        fun hasMockLocationAppOp(packageName: String, uid: Int, sdkInt: Int): Boolean
    }

    private val mutex = Mutex()
    private val registeredProviders = LinkedHashSet<String>()
    private val backend: TestProviderBackend by lazy { backendFactory(context) }

    suspend fun ensureMockPermission(packageName: String = context.packageName): Result<Unit> =
        withContext(ioDispatcher) {
            ensureMockPermissionInternal(packageName)
        }

    private suspend fun ensureMockPermissionInternal(packageName: String): Result<Unit> {
        val trimmedPkg = packageName.trim()
        if (!ShellUtil.isValidPackageName(trimmedPkg)) {
            return Result.failure(IllegalArgumentException("非法应用包名: $packageName"))
        }

        val sdkInt = sdkIntProvider()
        val myUid = uidProvider()
        if (runCatching { backend.hasMockLocationAppOp(trimmedPkg, myUid, sdkInt) }.getOrDefault(false)) {
            return Result.success(Unit)
        }

        val userId = userIdProvider().coerceAtLeast(0)
        val commands = listOf(
            "appops set --user $userId $trimmedPkg android:mock_location allow",
            "appops set --user current $trimmedPkg android:mock_location allow",
            "appops set $trimmedPkg android:mock_location allow",
            "cmd appops set $trimmedPkg android:mock_location allow"
        )

        var lastError = "无法获取 Root 权限或 appops 命令执行失败"
        for (cmd in commands) {
            val res = shellExecutor.execRoot(cmd, ShellUtil.DEFAULT_TIMEOUT_MS)
            if (res.isSuccess) {
                return Result.success(Unit)
            }
            if (runCatching { backend.hasMockLocationAppOp(trimmedPkg, myUid, sdkInt) }.getOrDefault(false)) {
                return Result.success(Unit)
            }
            if (res.stderr.isNotBlank()) {
                lastError = res.stderr
            } else if (res.timedOut) {
                lastError = "appops 命令执行超时"
            }
        }

        return Result.failure(SecurityException("无法通过 Root 授予模拟位置权限: $lastError"))
    }

    override suspend fun startOrUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ): Result<Unit> = withContext(ioDispatcher) {
        if (!GeoPoint.isValid(latitude, longitude)) {
            return@withContext Result.failure(
                IllegalArgumentException("非法坐标: ($latitude, $longitude)")
            )
        }
        val safeAlt = if (altitude.isFinite()) altitude else 0.0
        val safeAccuracy = if (accuracy.isFinite() && accuracy > 0f) accuracy else 3.0f
        val safeBearing = if (bearing.isFinite()) ((bearing % 360f) + 360f) % 360f else 0f
        val safeSpeed = if (speed.isFinite() && speed >= 0f) speed else 0f

        mutex.withLock {
            val sdkInt = sdkIntProvider()
            val pkg = context.packageName ?: ""
            var permChecked = false
            var gpsSucceeded = false
            var anySucceeded = false
            var firstFailure: Throwable? = null

            for (provider in TARGET_PROVIDERS) {
                val attempt = pushSingleProvider(
                    provider = provider,
                    sdkInt = sdkInt,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = safeAlt,
                    accuracy = safeAccuracy,
                    bearing = safeBearing,
                    speed = safeSpeed
                )

                val finalAttempt = if (attempt.exceptionOrNull() is SecurityException && !permChecked) {
                    permChecked = true
                    val permResult = ensureMockPermissionInternal(pkg)
                    if (permResult.isFailure) {
                        rollbackRegisteredProvidersLocked()
                        return@withLock Result.failure(
                            permResult.exceptionOrNull()
                                ?: SecurityException("Root 授权模拟位置权限失败")
                        )
                    }
                    pushSingleProvider(
                        provider = provider,
                        sdkInt = sdkInt,
                        latitude = latitude,
                        longitude = longitude,
                        altitude = safeAlt,
                        accuracy = safeAccuracy,
                        bearing = safeBearing,
                        speed = safeSpeed
                    )
                } else {
                    attempt
                }

                if (finalAttempt.isSuccess) {
                    anySucceeded = true
                    if (provider == LocationManager.GPS_PROVIDER) {
                        gpsSucceeded = true
                    }
                } else {
                    val err = finalAttempt.exceptionOrNull()
                    if (err is SecurityException) {
                        rollbackRegisteredProvidersLocked()
                        return@withLock Result.failure(err)
                    }
                    if (firstFailure == null) {
                        firstFailure = err
                    }
                }
            }

            if (!gpsSucceeded && !anySucceeded) {
                rollbackRegisteredProvidersLocked()
                return@withLock Result.failure(
                    firstFailure ?: IllegalStateException("所有 Root TestProvider 注入均失败")
                )
            }
            if (!gpsSucceeded) {
                rollbackRegisteredProvidersLocked()
                return@withLock Result.failure(
                    firstFailure ?: IllegalStateException("核心 GPS TestProvider 注入失败")
                )
            }

            Result.success(Unit)
        }
    }

    private fun rollbackRegisteredProvidersLocked() {
        val toRemove = registeredProviders.toList()
        for (provider in toRemove) {
            runCatching { backend.clearTestProviderLocation(provider) }
            runCatching { backend.setTestProviderEnabled(provider, false) }
            runCatching { backend.removeTestProvider(provider) }
        }
        registeredProviders.clear()
    }

    private fun pushSingleProvider(
        provider: String,
        sdkInt: Int,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ): Result<Unit> {
        var addedThisAttempt = false
        return try {
            if (!registeredProviders.contains(provider)) {
                runCatching { backend.removeTestProvider(provider) }
                backend.addTestProvider(provider, sdkInt)
                addedThisAttempt = true
                backend.setTestProviderEnabled(provider, true)
                registeredProviders.add(provider)
            }
            try {
                backend.setTestProviderLocation(
                    provider = provider,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracy = accuracy,
                    bearing = bearing,
                    speed = speed
                )
            } catch (e: IllegalArgumentException) {
                // 部分 ROM 可能回收未活跃的 TestProvider，重注册后再推送一次
                registeredProviders.remove(provider)
                runCatching { backend.removeTestProvider(provider) }
                addedThisAttempt = false
                backend.addTestProvider(provider, sdkInt)
                addedThisAttempt = true
                backend.setTestProviderEnabled(provider, true)
                registeredProviders.add(provider)
                backend.setTestProviderLocation(
                    provider = provider,
                    latitude = latitude,
                    longitude = longitude,
                    altitude = altitude,
                    accuracy = accuracy,
                    bearing = bearing,
                    speed = speed
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            val wasInRegistered = registeredProviders.remove(provider)
            if (addedThisAttempt || wasInRegistered) {
                runCatching { backend.clearTestProviderLocation(provider) }
                runCatching { backend.setTestProviderEnabled(provider, false) }
                runCatching { backend.removeTestProvider(provider) }
            }
            Result.failure(t)
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            val targets = (registeredProviders + TARGET_PROVIDERS).distinct()
            var unexpectedFailure: Throwable? = null
            for (provider in targets) {
                val wasRegistered = registeredProviders.contains(provider)
                try {
                    runCatching { backend.clearTestProviderLocation(provider) }
                    runCatching { backend.setTestProviderEnabled(provider, false) }
                    backend.removeTestProvider(provider)
                } catch (e: IllegalArgumentException) {
                    // 未注册或系统已移除时忽略
                } catch (t: Throwable) {
                    if (wasRegistered && unexpectedFailure == null) {
                        unexpectedFailure = t
                    }
                }
            }
            registeredProviders.clear()
            if (unexpectedFailure != null) {
                Result.failure(unexpectedFailure)
            } else {
                Result.success(Unit)
            }
        }
    }

    private class AndroidTestProviderBackend(context: Context) : TestProviderBackend {
        private val appContext = context.applicationContext ?: context
        private val locationManager: LocationManager? by lazy {
            appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }
        private val appOpsManager: AppOpsManager? by lazy {
            appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        }

        override fun addTestProvider(provider: String, sdkInt: Int) {
            val lm = locationManager ?: throw IllegalStateException("LocationManager 不可用")
            if (sdkInt >= Build.VERSION_CODES.S) {
                val isNetwork = provider == LocationManager.NETWORK_PROVIDER
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasSatelliteRequirement(false)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(
                        if (isNetwork) ProviderProperties.ACCURACY_COARSE else ProviderProperties.ACCURACY_FINE
                    )
                    .build()
                lm.addTestProvider(provider, props)
            } else {
                @Suppress("DEPRECATION")
                val criteriaAccuracy = if (provider == LocationManager.NETWORK_PROVIDER) {
                    Criteria.ACCURACY_COARSE
                } else {
                    Criteria.ACCURACY_FINE
                }
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    provider,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    criteriaAccuracy
                )
            }
        }

        override fun setTestProviderEnabled(provider: String, enabled: Boolean) {
            val lm = locationManager ?: throw IllegalStateException("LocationManager 不可用")
            lm.setTestProviderEnabled(provider, enabled)
        }

        override fun setTestProviderLocation(
            provider: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            accuracy: Float,
            bearing: Float,
            speed: Float
        ) {
            val lm = locationManager ?: throw IllegalStateException("LocationManager 不可用")
            val location = buildCompleteLocation(
                provider = provider,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                accuracy = accuracy,
                bearing = bearing,
                speed = speed
            )
            lm.setTestProviderLocation(provider, location)
        }

        @Suppress("DEPRECATION")
        override fun clearTestProviderLocation(provider: String) {
            val lm = locationManager ?: return
            runCatching { lm.clearTestProviderLocation(provider) }
        }

        override fun removeTestProvider(provider: String) {
            val lm = locationManager ?: return
            lm.removeTestProvider(provider)
        }

        override fun hasMockLocationAppOp(packageName: String, uid: Int, sdkInt: Int): Boolean {
            val ops = appOpsManager ?: return false
            return try {
                val mode = if (sdkInt >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName)
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Throwable) {
                false
            }
        }

        private fun buildCompleteLocation(
            provider: String,
            latitude: Double,
            longitude: Double,
            altitude: Double,
            accuracy: Float,
            bearing: Float,
            speed: Float
        ): Location {
            return Location(provider).apply {
                this.latitude = latitude
                this.longitude = longitude
                this.altitude = altitude
                this.accuracy = accuracy
                this.bearing = bearing
                this.speed = speed
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                this.verticalAccuracyMeters = accuracy
                this.speedAccuracyMetersPerSecond = 0.5f
                this.bearingAccuracyDegrees = 1.0f
                runCatching {
                    Location::class.java.getMethod("makeComplete").invoke(this)
                }
            }
        }
    }

    companion object {
        val TARGET_PROVIDERS: List<String> = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused"
        )

        @Volatile
        private var instance: RootProviderManager? = null

        fun getInstance(context: Context): RootProviderManager {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext ?: context
                RootProviderManager(appContext).also { instance = it }
            }
        }

        internal fun setInstanceForTest(manager: RootProviderManager?) {
            synchronized(this) {
                instance = manager
            }
        }
    }
}
