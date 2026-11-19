package com.mockloc.e2e.factory

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.LocationManager
import com.mockloc.R
import com.mockloc.data.config.MockConfig
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.geocoding.GeocodingHttpClient
import com.mockloc.domain.geocoding.GeocodingHttpResponse
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.root.EnvironmentStatus
import com.mockloc.root.ShellExecutor
import com.mockloc.root.ShellResult
import com.mockloc.root.ShellUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.mockito.Mockito
import java.util.concurrent.Executors

// ==================== 模型映射扩展 ====================

fun TestCoordType.toCoordinateType(): CoordinateType = when (this) {
    TestCoordType.WGS84 -> CoordinateType.WGS84
    TestCoordType.GCJ02 -> CoordinateType.GCJ02
    TestCoordType.BD09 -> CoordinateType.BD09
}

fun TestMockMode.toMockMode(): MockMode = when (this) {
    TestMockMode.AUTO -> MockMode.AUTO
    TestMockMode.LSPOSED_ONLY -> MockMode.LSPOSED_ONLY
    TestMockMode.ROOT_ONLY -> MockMode.ROOT_ONLY
}

fun TestLoopMode.toLoopMode(): LoopMode = when (this) {
    TestLoopMode.ONE_WAY -> LoopMode.ONE_WAY
    TestLoopMode.PING_PONG -> LoopMode.PING_PONG
    TestLoopMode.LOOP -> LoopMode.LOOP
}

fun TestGeoPoint.toGeoPoint(
    accuracy: Float = 5.0f,
    timestamp: Long = System.currentTimeMillis()
): GeoPoint = GeoPoint(
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    accuracy = accuracy,
    timestamp = timestamp
)

fun TestWaypointSpec.toWaypoint(): Waypoint = Waypoint(
    point = point.toGeoPoint(),
    name = label,
    dwellTimeSeconds = dwellTimeSeconds,
    targetSpeedMps = speedMps
)

fun TestRouteSpec.toPlannedRoute(
    createdAtMs: Long = System.currentTimeMillis()
): PlannedRoute = PlannedRoute(
    id = id,
    name = name,
    waypoints = waypoints.map { it.toWaypoint() },
    createdAtMs = createdAtMs
)

fun TestRouteSpec.toCruiseConfig(
    speedMps: Double = SpeedPreset.WALK.speedMps,
    enableSpeedNoise: Boolean = false,
    speedNoiseRatio: Double = 0.08,
    enableMicroDrift: Boolean = false,
    maxDriftRadiusMeters: Double = 1.5
): CruiseConfig = CruiseConfig(
    speedMps = speedMps,
    loopMode = loopMode.toLoopMode(),
    enableSpeedNoise = enableSpeedNoise,
    speedNoiseRatio = speedNoiseRatio,
    enableMicroDrift = enableMicroDrift,
    maxDriftRadiusMeters = maxDriftRadiusMeters
)

fun TestConfigSpec.toMockConfig(): MockConfig = MockConfig(
    enabled = enabled,
    mode = mode.toMockMode(),
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    coordinateType = coordinateType.toCoordinateType(),
    accuracy = accuracy,
    speed = speed,
    bearing = bearing,
    jitterEnabled = jitterEnabled,
    jitterRadiusMeters = jitterRadiusMeters,
    antiDetectCellEnabled = antiDetectCellEnabled,
    antiDetectWifiEnabled = antiDetectWifiEnabled,
    antiDetectGnssEnabled = antiDetectGnssEnabled,
    targetPackages = targetPackages.toSet(),
    updatedAtMs = updatedAtMs,
    version = version
)

fun TestEnvironmentPreset.toEnvironmentStatus(): EnvironmentStatus = EnvironmentStatus(
    isLSPosedActive = isLSPosedActive,
    xposedApiVersion = xposedApiVersion,
    isRootAvailable = isRootAvailable,
    rootProviderName = rootProviderName,
    hasLocationPermission = hasFineLocationPermission || hasCoarseLocationPermission,
    hasNotificationPermission = hasNotificationPermission,
    recommendedScopeInstalledCount = recommendedScopeInstalledCount,
    recommendedScopeTotalCount = recommendedScopeTotalCount
)

// ==================== 生产接口适配器 ====================

fun FakeShellResult.toShellResult(): ShellResult = ShellResult(
    exitCode = exitCode,
    stdout = stdout,
    stderr = stderr,
    timedOut = timedOut,
    isSuccess = isSuccess
)

fun FakeShellExecutor.toShellExecutor(): ShellExecutor = object : ShellExecutor {
    override suspend fun execRoot(command: String, timeoutMs: Long): ShellResult {
        return this@toShellExecutor.execute(command, timeoutMs).toShellResult()
    }

    override suspend fun execShell(command: String, timeoutMs: Long): ShellResult {
        return this@toShellExecutor.execute(command, timeoutMs).toShellResult()
    }

    override suspend fun hasRootAccess(timeoutMs: Long): Boolean {
        val result = execRoot("id", timeoutMs)
        return result.isSuccess && result.stdout.contains("uid=0")
    }

    override suspend fun detectRootProvider(timeoutMs: Long): String {
        return ShellUtil.detectRootProviderInternal(
            timeoutMs = timeoutMs,
            shellRunner = { cmd, t -> execShell(cmd, t) },
            rootChecker = { t -> hasRootAccess(t) },
            rootRunner = { cmd, t -> execRoot(cmd, t) }
        )
    }
}

fun FakeGeocodingHttpRequester.toGeocodingHttpClient(): GeocodingHttpClient =
    GeocodingHttpClient { url ->
        val resp = this@toGeocodingHttpClient.request(url)
        GeocodingHttpResponse(statusCode = resp.statusCode, body = resp.body)
    }

// ==================== Main 调度器与 Context 测试工厂 ====================

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> withMainDispatcher(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "E2E-MainDispatcher").apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()
    Dispatchers.setMain(dispatcher)
    return try {
        block()
    } finally {
        Dispatchers.resetMain()
        runCatching { dispatcher.close() }
        executor.shutdownNow()
    }
}

@Suppress("DEPRECATION")
fun buildTestAppContext(
    packageName: String = "com.mockloc",
    prefsOpener: InMemoryPreferencesOpener = InMemoryPreferencesOpener(),
    hasFineLocation: Boolean = true,
    hasCoarseLocation: Boolean = true,
    hasNotification: Boolean = true,
    installedPackages: Set<String> = emptySet(),
    xposedScopeArray: Array<String>? = null,
    contentResolver: ContentResolver = Mockito.mock(ContentResolver::class.java),
    locationManager: LocationManager = Mockito.mock(LocationManager::class.java)
): Context {
    val ctx = Mockito.mock(Context::class.java)
    val pm = Mockito.mock(PackageManager::class.java)
    val res = Mockito.mock(Resources::class.java)

    Mockito.`when`(ctx.packageName).thenReturn(packageName)
    Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
    Mockito.`when`(ctx.packageManager).thenReturn(pm)
    Mockito.`when`(ctx.resources).thenReturn(res)
    Mockito.`when`(ctx.contentResolver).thenReturn(contentResolver)
    Mockito.`when`(ctx.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)

    Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
        val name = inv.getArgument<String>(0)
        val mode = inv.getArgument<Int>(1)
        prefsOpener.open(name, mode)
    }

    fun permissionResultFor(perm: String?): Int {
        val granted = when (perm) {
            Manifest.permission.ACCESS_FINE_LOCATION -> hasFineLocation
            Manifest.permission.ACCESS_COARSE_LOCATION -> hasCoarseLocation
            Manifest.permission.POST_NOTIFICATIONS -> hasNotification
            else -> false
        }
        return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    Mockito.`when`(ctx.checkPermission(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt()))
        .thenAnswer { inv -> permissionResultFor(inv.getArgument(0)) }
    Mockito.`when`(ctx.checkSelfPermission(Mockito.anyString()))
        .thenAnswer { inv -> permissionResultFor(inv.getArgument(0)) }
    Mockito.`when`(ctx.checkCallingOrSelfPermission(Mockito.anyString()))
        .thenAnswer { inv -> permissionResultFor(inv.getArgument(0)) }

    if (xposedScopeArray != null) {
        Mockito.`when`(res.getStringArray(R.array.xposed_scope)).thenReturn(xposedScopeArray)
    } else {
        Mockito.`when`(res.getStringArray(R.array.xposed_scope)).thenReturn(emptyArray())
    }

    fun resolvePackageInfo(pkg: String): PackageInfo {
        if (pkg in installedPackages) {
            return PackageInfo().apply { this.packageName = pkg }
        }
        throw PackageManager.NameNotFoundException(pkg)
    }

    Mockito.`when`(pm.getPackageInfo(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
        resolvePackageInfo(inv.getArgument(0))
    }
    runCatching {
        Mockito.`when`(
            pm.getPackageInfo(
                Mockito.anyString(),
                Mockito.nullable(PackageManager.PackageInfoFlags::class.java)
            )
        ).thenAnswer { inv ->
            resolvePackageInfo(inv.getArgument(0))
        }
    }

    return ctx
}
