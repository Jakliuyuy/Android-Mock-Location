package com.mockloc.e2e.factory

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Bundle
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

// ==================== 纯 E2E 领域规格模型（零生产类依赖） ====================

data class TestGeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0
)

enum class TestCoordType { WGS84, GCJ02, BD09 }
enum class TestMockMode { AUTO, LSPOSED_ONLY, ROOT_ONLY }
enum class TestLoopMode { ONE_WAY, PING_PONG, LOOP }

data class TestWaypointSpec(
    val point: TestGeoPoint,
    val label: String = "",
    val speedMps: Float? = null,
    val dwellTimeSeconds: Double = 0.0
)

data class TestRouteSpec(
    val id: String,
    val name: String,
    val waypoints: List<TestWaypointSpec>,
    val loopMode: TestLoopMode = TestLoopMode.ONE_WAY
)

data class TestConfigSpec(
    val enabled: Boolean,
    val mode: TestMockMode,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val coordinateType: TestCoordType,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val jitterEnabled: Boolean,
    val jitterRadiusMeters: Double,
    val antiDetectCellEnabled: Boolean,
    val antiDetectWifiEnabled: Boolean,
    val antiDetectGnssEnabled: Boolean,
    val targetPackages: Set<String>,
    val updatedAtMs: Long,
    val version: Long
)

data class TestEnvironmentPreset(
    val name: String,
    val isLSPosedActive: Boolean,
    val xposedApiVersion: Int,
    val isRootAvailable: Boolean,
    val rootProviderName: String,
    val hasFineLocationPermission: Boolean,
    val hasCoarseLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val recommendedScopeInstalledCount: Int,
    val recommendedScopeTotalCount: Int
)

// ==================== §6.1 标准境内外测试坐标点集 ====================

object TestCoordinates {
    val BEIJING_TIANANMEN = TestGeoPoint(39.9087, 116.3975, 44.0)
    val BEIJING_BIRDS_NEST = TestGeoPoint(39.9929, 116.3965, 40.0)
    val BEIJING_FORBIDDEN_CITY = TestGeoPoint(39.9163, 116.3972, 45.0)
    val SHANGHAI_LUJIAZUI = TestGeoPoint(31.2397, 121.4998, 15.0)
    val SHENZHEN_NANSHAN = TestGeoPoint(22.5332, 113.9512, 10.0)
    val SHENZHEN_CIVIC_CENTER = TestGeoPoint(22.5446, 114.0579, 25.0)
    val LHASA_POTALA = TestGeoPoint(29.6555, 91.1189, 3650.0)

    val TOKYO_STATION = TestGeoPoint(35.6812, 139.7671, 10.0)
    val NEW_YORK_TIMES_SQUARE = TestGeoPoint(40.7580, -73.9855, 15.0)
    val LONDON = TestGeoPoint(51.5074, -0.1278, 11.0)

    val EQUATOR_PRIME_MERIDIAN = TestGeoPoint(0.0, 0.0, 0.0)
    val NORTH_POLE = TestGeoPoint(90.0, 0.0, 0.0)
    val SOUTH_POLE = TestGeoPoint(-90.0, 0.0, 0.0)
    val ANTIMERIDIAN_POS = TestGeoPoint(0.0, 180.0, 0.0)
    val ANTIMERIDIAN_NEG = TestGeoPoint(0.0, -180.0, 0.0)

    val CHINA_RECT_INNER = TestGeoPoint(39.9, 73.67, 0.0)
    val CHINA_RECT_OUTER = TestGeoPoint(39.9, 73.65, 0.0)
    val CHINA_RECT_NORTH_OUTER = TestGeoPoint(53.56, 120.0, 0.0)
    val CHINA_RECT_SW_CORNER = TestGeoPoint(3.86, 73.66, 0.0)
    val CHINA_RECT_NE_CORNER = TestGeoPoint(53.55, 135.05, 0.0)
}

// ==================== §6.2 标准多航点路线集 ====================

object TestRouteSpecs {
    val EMPTY_ROUTE = TestRouteSpec(
        id = "route_empty",
        name = "空路线",
        waypoints = emptyList(),
        loopMode = TestLoopMode.ONE_WAY
    )

    val SINGLE_POINT_ROUTE = TestRouteSpec(
        id = "route_single",
        name = "单点驻留路线",
        waypoints = listOf(
            TestWaypointSpec(TestCoordinates.BEIJING_TIANANMEN, label = "天安门", dwellTimeSeconds = 2.0)
        ),
        loopMode = TestLoopMode.ONE_WAY
    )

    val COINCIDENT_POINTS_ROUTE = TestRouteSpec(
        id = "route_coincident",
        name = "重合航点测试路线",
        waypoints = listOf(
            TestWaypointSpec(TestCoordinates.BEIJING_TIANANMEN, label = "重合点1"),
            TestWaypointSpec(TestCoordinates.BEIJING_TIANANMEN, label = "重合点2"),
            TestWaypointSpec(TestCoordinates.BEIJING_FORBIDDEN_CITY, label = "故宫")
        ),
        loopMode = TestLoopMode.ONE_WAY
    )

    val BEIJING_COMMUTE_3WP = TestRouteSpec(
        id = "route_beijing_commute",
        name = "北京通勤3航点",
        waypoints = listOf(
            TestWaypointSpec(TestCoordinates.BEIJING_TIANANMEN, label = "天安门", speedMps = 1.4f, dwellTimeSeconds = 0.0),
            TestWaypointSpec(TestCoordinates.BEIJING_FORBIDDEN_CITY, label = "故宫", speedMps = 4.5f, dwellTimeSeconds = 1.5),
            TestWaypointSpec(TestCoordinates.BEIJING_BIRDS_NEST, label = "鸟巢", speedMps = 4.5f, dwellTimeSeconds = 0.0)
        ),
        loopMode = TestLoopMode.ONE_WAY
    )

    val CROSS_BORDER_2WP = TestRouteSpec(
        id = "route_cross_border",
        name = "跨中国边界路线",
        waypoints = listOf(
            TestWaypointSpec(TestCoordinates.CHINA_RECT_INNER, label = "边界内侧"),
            TestWaypointSpec(TestCoordinates.CHINA_RECT_OUTER, label = "边界外侧")
        ),
        loopMode = TestLoopMode.ONE_WAY
    )

    val CLOSED_LOOP_4WP = TestRouteSpec(
        id = "route_closed_loop",
        name = "闭合矩形循环路线",
        waypoints = listOf(
            TestWaypointSpec(TestGeoPoint(39.9080, 116.3970, 44.0), label = "角1"),
            TestWaypointSpec(TestGeoPoint(39.9090, 116.3970, 44.0), label = "角2"),
            TestWaypointSpec(TestGeoPoint(39.9090, 116.3985, 44.0), label = "角3"),
            TestWaypointSpec(TestGeoPoint(39.9080, 116.3985, 44.0), label = "角4")
        ),
        loopMode = TestLoopMode.LOOP
    )

    fun createLargeRoute(waypointCount: Int = 500): TestRouteSpec {
        require(waypointCount > 0) { "waypointCount must be positive" }
        val points = (0 until waypointCount).map { idx ->
            val lat = 39.9000 + idx * 0.0001
            val lon = 116.3900 + (idx % 20) * 0.0001
            TestWaypointSpec(
                point = TestGeoPoint(lat, lon, 40.0 + (idx % 10)),
                label = "WP_$idx",
                speedMps = 2.0f + (idx % 5),
                dwellTimeSeconds = if (idx % 50 == 0) 1.0 else 0.0
            )
        }
        return TestRouteSpec(
            id = "route_large_$waypointCount",
            name = "大规模${waypointCount}航点压测路线",
            waypoints = points,
            loopMode = TestLoopMode.ONE_WAY
        )
    }

    val LARGE_500WP_ROUTE: TestRouteSpec by lazy { createLargeRoute(500) }
}

// ==================== §6.3 标准与异常配置/载荷集 ====================

object TestConfigPayloads {

    val DEFAULT_IDLE_CONFIG = TestConfigSpec(
        enabled = false,
        mode = TestMockMode.AUTO,
        latitude = TestCoordinates.BEIJING_TIANANMEN.latitude,
        longitude = TestCoordinates.BEIJING_TIANANMEN.longitude,
        altitude = TestCoordinates.BEIJING_TIANANMEN.altitude,
        coordinateType = TestCoordType.WGS84,
        accuracy = 3.0f,
        speed = 0.0f,
        bearing = 0.0f,
        jitterEnabled = false,
        jitterRadiusMeters = 2.0,
        antiDetectCellEnabled = true,
        antiDetectWifiEnabled = true,
        antiDetectGnssEnabled = true,
        targetPackages = emptySet(),
        updatedAtMs = 1_700_000_000_000L,
        version = 1L
    )

    val LSPOSED_ACTIVE_CONFIG = TestConfigSpec(
        enabled = true,
        mode = TestMockMode.LSPOSED_ONLY,
        latitude = TestCoordinates.SHANGHAI_LUJIAZUI.latitude,
        longitude = TestCoordinates.SHANGHAI_LUJIAZUI.longitude,
        altitude = TestCoordinates.SHANGHAI_LUJIAZUI.altitude,
        coordinateType = TestCoordType.WGS84,
        accuracy = 2.5f,
        speed = 4.5f,
        bearing = 180.0f,
        jitterEnabled = true,
        jitterRadiusMeters = 3.0,
        antiDetectCellEnabled = true,
        antiDetectWifiEnabled = true,
        antiDetectGnssEnabled = true,
        targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap", "com.baidu.BaiduMap"),
        updatedAtMs = 1_700_000_010_000L,
        version = 10L
    )

    val ROOT_ACTIVE_CONFIG = TestConfigSpec(
        enabled = true,
        mode = TestMockMode.ROOT_ONLY,
        latitude = TestCoordinates.SHENZHEN_NANSHAN.latitude,
        longitude = TestCoordinates.SHENZHEN_NANSHAN.longitude,
        altitude = TestCoordinates.SHENZHEN_NANSHAN.altitude,
        coordinateType = TestCoordType.WGS84,
        accuracy = 3.5f,
        speed = 1.5f,
        bearing = 90.0f,
        jitterEnabled = true,
        jitterRadiusMeters = 3.5,
        antiDetectCellEnabled = true,
        antiDetectWifiEnabled = true,
        antiDetectGnssEnabled = true,
        targetPackages = setOf("com.tencent.mm"),
        updatedAtMs = 1_700_000_020_000L,
        version = 20L
    )

    fun generatePackageSet(count: Int = 5000): Set<String> {
        require(count >= 0) { "count must be non-negative" }
        val set = LinkedHashSet<String>(count)
        for (i in 0 until count) {
            set.add("com.mockloc.target.app$i")
        }
        return set
    }

    fun createLargeWhitelistConfig(packageCount: Int = 5000): TestConfigSpec =
        LSPOSED_ACTIVE_CONFIG.copy(
            targetPackages = generatePackageSet(packageCount),
            version = 99L
        )

    val CORRUPTED_JSON_PAYLOADS: List<String> = listOf(
        "",
        "   ",
        "{bad",
        "null",
        "{}",
        """{"latitude":"NaN","longitude":999.0,"mode":"INVALID_ENUM","targetPackages":"not_an_array"}""",
        """{"enabled":"not_bool","accuracy":-5.0,"speed":"oops"}"""
    )

    val OUT_OF_RANGE_RAW_MAPS: List<Map<String, Any?>> = listOf(
        mapOf("latitude" to 95.0, "longitude" to 116.4),
        mapOf("latitude" to -91.0, "longitude" to 116.4),
        mapOf("latitude" to 39.9, "longitude" to 185.0),
        mapOf("latitude" to Double.NaN, "longitude" to Double.NEGATIVE_INFINITY),
        mapOf("latitude" to 39.9, "longitude" to Double.POSITIVE_INFINITY),
        mapOf("accuracy" to -10.0f, "speed" to -5.0f, "jitterRadiusMeters" to -1.0, "bearing" to 725.0f)
    )

    val INJECTION_PACKAGE_NAMES: List<String?> = listOf(
        "",
        "   ",
        "com.app; rm -rf /",
        "com.app && id",
        "com.app | cat /etc/passwd",
        "com.app`id`",
        "invalid_single_segment",
        null
    )
}

// ==================== §6.4 环境变量预设矩阵 ====================

object TestEnvironmentPresets {
    val ENV_DUAL_READY = TestEnvironmentPreset(
        name = "ENV_DUAL_READY",
        isLSPosedActive = true,
        xposedApiVersion = 93,
        isRootAvailable = true,
        rootProviderName = "Magisk",
        hasFineLocationPermission = true,
        hasCoarseLocationPermission = true,
        hasNotificationPermission = true,
        recommendedScopeInstalledCount = 4,
        recommendedScopeTotalCount = 10
    )

    val ENV_ROOT_ONLY = TestEnvironmentPreset(
        name = "ENV_ROOT_ONLY",
        isLSPosedActive = false,
        xposedApiVersion = 0,
        isRootAvailable = true,
        rootProviderName = "KernelSU",
        hasFineLocationPermission = true,
        hasCoarseLocationPermission = true,
        hasNotificationPermission = true,
        recommendedScopeInstalledCount = 2,
        recommendedScopeTotalCount = 10
    )

    val ENV_LSPOSED_ONLY = TestEnvironmentPreset(
        name = "ENV_LSPOSED_ONLY",
        isLSPosedActive = true,
        xposedApiVersion = 100,
        isRootAvailable = false,
        rootProviderName = "None",
        hasFineLocationPermission = true,
        hasCoarseLocationPermission = true,
        hasNotificationPermission = true,
        recommendedScopeInstalledCount = 5,
        recommendedScopeTotalCount = 10
    )

    val ENV_NO_LOCATION_PERM = TestEnvironmentPreset(
        name = "ENV_NO_LOCATION_PERM",
        isLSPosedActive = true,
        xposedApiVersion = 93,
        isRootAvailable = true,
        rootProviderName = "Magisk",
        hasFineLocationPermission = false,
        hasCoarseLocationPermission = false,
        hasNotificationPermission = true,
        recommendedScopeInstalledCount = 1,
        recommendedScopeTotalCount = 10
    )

    val ENV_NO_NOTIFICATION_PERM = TestEnvironmentPreset(
        name = "ENV_NO_NOTIFICATION_PERM",
        isLSPosedActive = true,
        xposedApiVersion = 93,
        isRootAvailable = false,
        rootProviderName = "None",
        hasFineLocationPermission = true,
        hasCoarseLocationPermission = true,
        hasNotificationPermission = false,
        recommendedScopeInstalledCount = 1,
        recommendedScopeTotalCount = 10
    )

    val ENV_UNPRIVILEGED = TestEnvironmentPreset(
        name = "ENV_UNPRIVILEGED",
        isLSPosedActive = false,
        xposedApiVersion = 0,
        isRootAvailable = false,
        rootProviderName = "None",
        hasFineLocationPermission = false,
        hasCoarseLocationPermission = false,
        hasNotificationPermission = false,
        recommendedScopeInstalledCount = 0,
        recommendedScopeTotalCount = 10
    )
}

// ==================== 纯 JVM 线程安全测试替身 ====================

/**
 * 线程安全的内存 SharedPreferences 替身，脱离 android.jar 默认空返回陷阱。
 * 支持通过 [injectRawValue] 注入错误类型以验证反序列化类型损坏容错。
 */
class InMemorySharedPreferences : SharedPreferences {

    private val store = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val lock = Any()

    fun injectRawValue(key: String, value: Any?) {
        synchronized(lock) {
            if (value == null) {
                store.remove(key)
            } else {
                store[key] = value
            }
        }
    }

    fun clearAndReset() {
        synchronized(lock) {
            store.clear()
        }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        HashMap(store)
    }

    override fun getString(key: String?, defValue: String?): String? = synchronized(lock) {
        if (key == null) return defValue
        val v = store[key] ?: return defValue
        return v as String
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        if (key == null) return defValues
        val v = store[key] ?: return defValues
        return LinkedHashSet(v as Set<String>)
    }

    override fun getInt(key: String?, defValue: Int): Int = synchronized(lock) {
        if (key == null) return defValue
        val v = store[key] ?: return defValue
        return v as Int
    }

    override fun getLong(key: String?, defValue: Long): Long = synchronized(lock) {
        if (key == null) return defValue
        val v = store[key] ?: return defValue
        return v as Long
    }

    override fun getFloat(key: String?, defValue: Float): Float = synchronized(lock) {
        if (key == null) return defValue
        val v = store[key] ?: return defValue
        return v as Float
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = synchronized(lock) {
        if (key == null) return defValue
        val v = store[key] ?: return defValue
        return v as Boolean
    }

    override fun contains(key: String?): Boolean = synchronized(lock) {
        key != null && store.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = values?.let { LinkedHashSet(it) }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearRequested = true
        }

        override fun commit(): Boolean {
            val changedKeys: Set<String>
            val listenersSnapshot: List<SharedPreferences.OnSharedPreferenceChangeListener>
            synchronized(lock) {
                val previous = HashMap(store)
                if (clearRequested) {
                    store.clear()
                }
                for ((k, v) in pending) {
                    if (v == null) {
                        store.remove(k)
                    } else {
                        store[k] = v
                    }
                }
                pending.clear()
                clearRequested = false

                val keys = LinkedHashSet<String>()
                for ((k, oldVal) in previous) {
                    val newVal = store[k]
                    if (newVal == null || newVal != oldVal) {
                        keys.add(k)
                    }
                }
                for ((k, newVal) in store) {
                    val oldVal = previous[k]
                    if (oldVal == null || oldVal != newVal) {
                        keys.add(k)
                    }
                }
                changedKeys = keys
                listenersSnapshot = listeners.toList()
            }

            // 完全退出 synchronized(lock) 后再派发外部回调，彻底消除与外部锁之间的 AB-BA 死锁
            if (changedKeys.isNotEmpty() && listenersSnapshot.isNotEmpty()) {
                for (k in changedKeys) {
                    for (listener in listenersSnapshot) {
                        if (listener in listeners) {
                            listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, k)
                        }
                    }
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

/**
 * 模拟 `Context.getSharedPreferences(name, mode)`，支持：
 * - `throwOnWorldReadable = true` 时在 `MODE_WORLD_READABLE` (`1`) 下抛出 `SecurityException`（验证 SafePrefs 降级）
 * - `throwOnAllModes = true` 时所有模式均抛 `RuntimeException`（验证极限异常兜底）
 */
class InMemoryPreferencesOpener(
    var throwOnWorldReadable: Boolean = false,
    var throwOnAllModes: Boolean = false
) {
    private val prefsByName = ConcurrentHashMap<String, InMemorySharedPreferences>()
    val requestedModes = CopyOnWriteArrayList<Int>()

    @Suppress("DEPRECATION")
    fun open(name: String, mode: Int): SharedPreferences {
        requestedModes.add(mode)
        if (throwOnAllModes) {
            throw RuntimeException("Simulated storage I/O failure for $name")
        }
        if (throwOnWorldReadable && mode == Context.MODE_WORLD_READABLE) {
            throw SecurityException("MODE_WORLD_READABLE is unavailable on non-LSPosed environment")
        }
        return prefsByName.computeIfAbsent(name) { InMemorySharedPreferences() }
    }

    fun getBackingPrefs(name: String): InMemorySharedPreferences =
        prefsByName.computeIfAbsent(name) { InMemorySharedPreferences() }

    fun asMockContext(packageName: String = "com.mockloc"): Context {
        val ctx = Mockito.mock(Context::class.java)
        Mockito.`when`(ctx.packageName).thenReturn(packageName)
        Mockito.`when`(ctx.applicationContext).thenReturn(ctx)
        Mockito.`when`(ctx.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenAnswer { inv ->
            val name = inv.getArgument<String>(0)
            val mode = inv.getArgument<Int>(1)
            open(name, mode)
        }
        return ctx
    }
}

/**
 * 线程安全的纯 JVM 键值对 Bundle 仿真，完全规避 android.os.Bundle 的 stub 空返回。
 * 严格遵循 Android Bundle 类型匹配语义（不进行跨数字类型隐式强转），并支持通过 [asAndroidBundle] 桥接。
 */
class StatefulFakeBundle {

    private object NullSentinel

    companion object {
        private val bundleBridgeMap: MutableMap<Bundle, StatefulFakeBundle> =
            Collections.synchronizedMap(WeakHashMap())

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        fun fromAndroidBundle(source: Bundle): StatefulFakeBundle {
            val mapped = bundleBridgeMap[source]
            if (mapped != null) {
                return mapped.deepCopy()
            }
            val copy = StatefulFakeBundle()
            val keys = source.keySet()?.toList().orEmpty()
            for (k in keys) {
                if (k == null) continue
                copy.putAnyValue(k, source.get(k))
            }
            return copy
        }
    }

    private val map = ConcurrentHashMap<String, Any>()
    private val mutationListeners = CopyOnWriteArrayList<() -> Unit>()
    private val proxyCacheLock = Any()
    @Volatile
    private var cachedAndroidProxy: Bundle? = null

    internal fun addMutationListener(listener: () -> Unit) {
        mutationListeners.add(listener)
    }

    private fun notifyMutated() {
        mutationListeners.forEach { it() }
    }

    private fun putRaw(key: String?, value: Any?) {
        if (key == null) return
        map[key] = value ?: NullSentinel
        notifyMutated()
    }

    private fun getRaw(key: String?): Any? {
        if (key == null) return null
        val v = map[key]
        return if (v === NullSentinel) null else v
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun putAnyValue(key: String, value: Any?, notify: Boolean = false) {
        val stored: Any = when (value) {
            null -> NullSentinel
            is StatefulFakeBundle -> value.deepCopy()
            is Bundle -> fromAndroidBundle(value)
            is ArrayList<*> -> ArrayList(value.filterIsInstance<String>())
            is Array<*> -> value.filterIsInstance<String>().toTypedArray()
            is Set<*> -> LinkedHashSet(value.filterIsInstance<String>())
            else -> value
        }
        map[key] = stored
        if (notify) notifyMutated()
    }

    fun deepCopy(): StatefulFakeBundle {
        val copy = StatefulFakeBundle()
        for ((k, v) in map.entries) {
            when (v) {
                NullSentinel -> copy.map[k] = NullSentinel
                is StatefulFakeBundle -> copy.map[k] = v.deepCopy()
                is ArrayList<*> -> copy.map[k] = ArrayList(v.filterIsInstance<String>())
                is Set<*> -> copy.map[k] = LinkedHashSet(v.filterIsInstance<String>())
                is Array<*> -> copy.map[k] = v.filterIsInstance<String>().toTypedArray()
                else -> copy.map[k] = v
            }
        }
        return copy
    }

    fun putString(key: String?, value: String?) { putRaw(key, value) }
    fun getString(key: String?, defaultValue: String? = null): String? =
        if (key == null) defaultValue else (getRaw(key) as? String) ?: defaultValue

    fun putDouble(key: String?, value: Double) { putRaw(key, value) }
    fun getDouble(key: String?, defaultValue: Double = 0.0): Double =
        if (key == null) defaultValue else (getRaw(key) as? Double) ?: defaultValue

    fun putFloat(key: String?, value: Float) { putRaw(key, value) }
    fun getFloat(key: String?, defaultValue: Float = 0f): Float =
        if (key == null) defaultValue else (getRaw(key) as? Float) ?: defaultValue

    fun putBoolean(key: String?, value: Boolean) { putRaw(key, value) }
    fun getBoolean(key: String?, defaultValue: Boolean = false): Boolean =
        if (key == null) defaultValue else (getRaw(key) as? Boolean) ?: defaultValue

    fun putLong(key: String?, value: Long) { putRaw(key, value) }
    fun getLong(key: String?, defaultValue: Long = 0L): Long =
        if (key == null) defaultValue else (getRaw(key) as? Long) ?: defaultValue

    fun putInt(key: String?, value: Int) { putRaw(key, value) }
    fun getInt(key: String?, defaultValue: Int = 0): Int =
        if (key == null) defaultValue else (getRaw(key) as? Int) ?: defaultValue

    fun putStringArrayList(key: String?, value: ArrayList<String>?) {
        putRaw(key, value?.let { ArrayList(it) })
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringArrayList(key: String?): ArrayList<String>? =
        if (key == null) null else (getRaw(key) as? List<String>)?.let { ArrayList(it) }

    fun putStringArray(key: String?, value: Array<String>?) {
        putRaw(key, value?.copyOf())
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringArray(key: String?): Array<String>? =
        if (key == null) null else (getRaw(key) as? Array<String>)?.copyOf()

    fun putStringSet(key: String?, value: Set<String>?) {
        putRaw(key, value?.let { LinkedHashSet(it) })
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String?): Set<String>? =
        if (key == null) null else (getRaw(key) as? Set<String>)?.let { LinkedHashSet(it) }

    fun putBundle(key: String?, value: StatefulFakeBundle?) {
        putRaw(key, value)
    }

    fun getBundle(key: String?): StatefulFakeBundle? =
        if (key == null) null else getRaw(key) as? StatefulFakeBundle

    fun putAll(other: StatefulFakeBundle?) {
        if (other == null || other === this) return
        for ((k, v) in other.toMap()) {
            putAnyValue(k, v, notify = false)
        }
        notifyMutated()
    }

    @Suppress("DEPRECATION")
    fun putAll(source: Bundle?) {
        if (source == null) return
        val mapped = bundleBridgeMap[source]
        if (mapped != null) {
            if (mapped === this) return
            putAll(mapped)
            return
        }
        val keys = source.keySet()?.toList().orEmpty()
        for (k in keys) {
            if (k == null) continue
            putAnyValue(k, source.get(k), notify = false)
        }
        notifyMutated()
    }

    fun containsKey(key: String?): Boolean = key != null && map.containsKey(key)
    fun keySet(): Set<String> = LinkedHashSet(map.keys)
    fun remove(key: String?) {
        if (key == null) return
        map.remove(key)
        notifyMutated()
    }
    fun clear() {
        map.clear()
        notifyMutated()
    }
    fun size(): Int = map.size
    fun isEmpty(): Boolean = map.isEmpty()

    fun toMap(): Map<String, Any?> {
        val snapshot = LinkedHashMap<String, Any?>()
        for ((k, v) in map.entries) {
            snapshot[k] = if (v === NullSentinel) null else v
        }
        return snapshot
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun asAndroidBundle(): Bundle {
        cachedAndroidProxy?.let { return it }
        synchronized(proxyCacheLock) {
            cachedAndroidProxy?.let { return it }
            lateinit var proxy: Bundle
            val answer = Answer<Any?> { inv ->
                val args = inv.arguments
                when (inv.method.name) {
                    "putString" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putString(key, args.getOrNull(1) as? String)
                        null
                    }
                    "getString" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as? String else null
                        if (key == null) def else getString(key, def)
                    }
                    "putDouble" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putDouble(key, args[1] as Double)
                        null
                    }
                    "getDouble" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as Double else 0.0
                        if (key == null) def else getDouble(key, def)
                    }
                    "putFloat" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putFloat(key, args[1] as Float)
                        null
                    }
                    "getFloat" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as Float else 0f
                        if (key == null) def else getFloat(key, def)
                    }
                    "putBoolean" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putBoolean(key, args[1] as Boolean)
                        null
                    }
                    "getBoolean" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as Boolean else false
                        if (key == null) def else getBoolean(key, def)
                    }
                    "putLong" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putLong(key, args[1] as Long)
                        null
                    }
                    "getLong" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as Long else 0L
                        if (key == null) def else getLong(key, def)
                    }
                    "putInt" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putInt(key, args[1] as Int)
                        null
                    }
                    "getInt" -> {
                        val key = args.getOrNull(0) as? String
                        val def = if (args.size >= 2) args[1] as Int else 0
                        if (key == null) def else getInt(key, def)
                    }
                    "putStringArrayList" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putStringArrayList(key, args.getOrNull(1) as? ArrayList<String>)
                        null
                    }
                    "getStringArrayList" -> {
                        val key = args.getOrNull(0) as? String
                        if (key == null) null else getStringArrayList(key)
                    }
                    "putStringArray" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) putStringArray(key, args.getOrNull(1) as? Array<String>)
                        null
                    }
                    "getStringArray" -> {
                        val key = args.getOrNull(0) as? String
                        if (key == null) null else getStringArray(key)
                    }
                    "putBundle" -> {
                        val key = args.getOrNull(0) as? String
                        if (key != null) {
                            val b = args.getOrNull(1) as? Bundle
                            if (b == null) {
                                putBundle(key, null)
                            } else {
                                val mapped = bundleBridgeMap[b]
                                if (mapped != null) {
                                    putBundle(key, mapped)
                                } else {
                                    putBundle(key, fromAndroidBundle(b))
                                }
                            }
                        }
                        null
                    }
                    "getBundle" -> {
                        val key = args.getOrNull(0) as? String ?: return@Answer null
                        when (val raw = getRaw(key)) {
                            is StatefulFakeBundle -> raw.asAndroidBundle()
                            is Bundle -> raw
                            else -> null
                        }
                    }
                    "putAll" -> {
                        when (val src = args.getOrNull(0)) {
                            is Bundle -> putAll(src)
                            is StatefulFakeBundle -> putAll(src)
                        }
                        null
                    }
                    "get" -> {
                        val key = args.getOrNull(0) as? String ?: return@Answer null
                        when (val raw = getRaw(key)) {
                            is StatefulFakeBundle -> raw.asAndroidBundle()
                            else -> raw
                        }
                    }
                    "containsKey" -> {
                        val key = args.getOrNull(0) as? String
                        containsKey(key)
                    }
                    "remove" -> {
                        val key = args.getOrNull(0) as? String
                        remove(key)
                        null
                    }
                    "clear" -> {
                        clear()
                        null
                    }
                    "keySet" -> keySet()
                    "size" -> size()
                    "isEmpty" -> isEmpty()
                    "equals" -> proxy === args.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "AndroidBundleBridge(${toMap()})"
                    else -> Mockito.RETURNS_DEFAULTS.answer(inv)
                }
            }
            proxy = Mockito.mock(Bundle::class.java, Mockito.withSettings().defaultAnswer(answer))
            bundleBridgeMap[proxy] = this
            cachedAndroidProxy = proxy
            return proxy
        }
    }
}

/**
 * 可编程 Shell 命令执行替身，支持成功/失败退出码、模拟超时与异常抛出。
 */
data class FakeShellResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

class FakeShellExecutor {
    private sealed interface Handler {
        data class Static(val result: FakeShellResult) : Handler
        data class Delay(val delayMs: Long, val result: FakeShellResult) : Handler
        data class Failure(val throwable: Throwable) : Handler
    }

    private val patternHandlers = CopyOnWriteArrayList<Pair<Regex, Handler>>()
    val executedCommands = CopyOnWriteArrayList<String>()
    private val defaultResult = FakeShellResult(exitCode = 0, stdout = "", stderr = "")

    fun stubCommand(pattern: Regex, result: FakeShellResult) {
        patternHandlers.add(0, pattern to Handler.Static(result))
    }

    fun stubCommandPrefix(prefix: String, result: FakeShellResult) {
        stubCommand(Regex("^" + Regex.escape(prefix)), result)
    }

    fun stubTimeout(pattern: Regex, delayMs: Long = 500L) {
        patternHandlers.add(0, pattern to Handler.Delay(delayMs, FakeShellResult(-1, "", "Command timed out", timedOut = true)))
    }

    fun stubException(pattern: Regex, throwable: Throwable = IOException("Cannot run program su")) {
        patternHandlers.add(0, pattern to Handler.Failure(throwable))
    }

    fun execute(command: String, timeoutMs: Long = 5000L): FakeShellResult {
        executedCommands.add(command)
        val matched = patternHandlers.firstOrNull { (regex, _) -> regex.containsMatchIn(command) }?.second
            ?: return defaultResult
        return when (matched) {
            is Handler.Static -> matched.result
            is Handler.Delay -> {
                if (matched.delayMs > timeoutMs) {
                    Thread.sleep(minOf(timeoutMs, 50L))
                    FakeShellResult(exitCode = -1, stdout = "", stderr = "Timed out after ${timeoutMs}ms", timedOut = true)
                } else {
                    Thread.sleep(matched.delayMs)
                    matched.result
                }
            }
            is Handler.Failure -> throw matched.throwable
        }
    }

    fun clearHistory() {
        executedCommands.clear()
    }
}

/**
 * 可编程地理编码 HTTP 请求替身，支持正常国内 POI 响应、HTTP 错误码、畸形 JSON 与超时异常。
 */
data class FakeHttpResponse(
    val statusCode: Int,
    val body: String
)

class FakeGeocodingHttpRequester {
    private sealed interface Outcome {
        data class Response(val resp: FakeHttpResponse) : Outcome
        data class Error(val exception: Exception) : Outcome
    }

    private val rules = CopyOnWriteArrayList<Pair<Regex, Outcome>>()
    val requestedUrls = CopyOnWriteArrayList<String>()
    val requestCount = AtomicInteger(0)

    fun stubSuccess(urlPattern: Regex, jsonBody: String, statusCode: Int = 200) {
        rules.add(0, urlPattern to Outcome.Response(FakeHttpResponse(statusCode, jsonBody)))
    }

    fun stubHttpError(urlPattern: Regex, statusCode: Int = 502, body: String = "Bad Gateway") {
        rules.add(0, urlPattern to Outcome.Response(FakeHttpResponse(statusCode, body)))
    }

    fun stubTimeout(urlPattern: Regex, message: String = "connect timed out") {
        rules.add(0, urlPattern to Outcome.Error(SocketTimeoutException(message)))
    }

    fun stubNetworkException(urlPattern: Regex, exception: IOException = IOException("Network unreachable")) {
        rules.add(0, urlPattern to Outcome.Error(exception))
    }

    fun request(url: String): FakeHttpResponse {
        requestedUrls.add(url)
        requestCount.incrementAndGet()
        val outcome = rules.firstOrNull { (regex, _) -> regex.containsMatchIn(url) }?.second
            ?: return FakeHttpResponse(200, "{}")
        return when (outcome) {
            is Outcome.Response -> outcome.resp
            is Outcome.Error -> throw outcome.exception
        }
    }
}

/**
 * 纯 JVM 仿真 Android `Location` 对象，暴露 `mFieldsMask`、`mIsFromMockProvider`、`setMock` 与 `extras`
 * 以便精确校验 `MockLocationCleaner` 的多层清理语义。
 */
class E2eFakeLocation(
    var provider: String = "gps",
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var altitude: Double = 0.0,
    var accuracy: Float = 5.0f,
    var bearing: Float = 0.0f,
    var speed: Float = 0.0f,
    var time: Long = System.currentTimeMillis(),
    var elapsedRealtimeNanos: Long = System.nanoTime(),
    @JvmField var mFieldsMask: Int = HAS_ALTITUDE_MASK or HAS_SPEED_MASK or HAS_BEARING_MASK or HAS_HORIZONTAL_ACCURACY_MASK or HAS_MOCK_PROVIDER_MASK,
    @JvmField var mIsFromMockProvider: Boolean = true,
    private var mockFlag: Boolean = true,
    val extras: MutableMap<String, Any?> = linkedMapOf(
        "mockLocation" to true,
        "mock" to true,
        "isFromMockProvider" to true,
        "satellites" to 12
    )
) {
    companion object {
        const val HAS_ALTITUDE_MASK = 1 shl 0        // 1
        const val HAS_SPEED_MASK = 1 shl 1           // 2
        const val HAS_BEARING_MASK = 1 shl 2         // 4
        const val HAS_HORIZONTAL_ACCURACY_MASK = 1 shl 3 // 8
        const val HAS_MOCK_PROVIDER_MASK = 1 shl 4   // 16
    }

    fun isFromMockProvider(): Boolean = mIsFromMockProvider || mockFlag || (mFieldsMask and HAS_MOCK_PROVIDER_MASK) != 0

    fun isMock(): Boolean = mockFlag || mIsFromMockProvider || (mFieldsMask and HAS_MOCK_PROVIDER_MASK) != 0

    fun setMock(mock: Boolean) {
        this.mockFlag = mock
        this.mIsFromMockProvider = mock
        this.mFieldsMask = if (mock) {
            mFieldsMask or HAS_MOCK_PROVIDER_MASK
        } else {
            mFieldsMask and HAS_MOCK_PROVIDER_MASK.inv()
        }
    }

    fun clearMockMarkersForTestReference() {
        setMock(false)
        extras.remove("mockLocation")
        extras.remove("mock")
        extras.remove("isFromMockProvider")
    }

    /**
     * 桥接为强类型 `android.location.Location`（基于 mockito-core 委托字段与 `StatefulFakeBundle` extras），
     * 供 Phase 2 直接测试生产方法 `MockLocationCleaner.sanitize(Location)` 与 `LocationHook`。
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun asAndroidLocation(): Location {
        val extrasSyncLock = Any()
        var isSyncingExtras = false
        var lastSyncedSnapshot = LinkedHashMap<String, Any?>(extras)

        fun putEntryIntoBundle(k: String, v: Any?, fb: StatefulFakeBundle) {
            when (v) {
                is String -> fb.putString(k, v)
                is Boolean -> fb.putBoolean(k, v)
                is Int -> fb.putInt(k, v)
                is Long -> fb.putLong(k, v)
                is Float -> fb.putFloat(k, v)
                is Double -> fb.putDouble(k, v)
                is ArrayList<*> -> fb.putStringArrayList(k, ArrayList(v.filterIsInstance<String>()))
                is Array<*> -> fb.putStringArray(k, v.filterIsInstance<String>().toTypedArray())
                is Set<*> -> fb.putStringSet(k, v.filterIsInstance<String>().toSet())
                is StatefulFakeBundle -> fb.putBundle(k, v.deepCopy())
                is Bundle -> fb.putBundle(k, StatefulFakeBundle.fromAndroidBundle(v))
                null -> fb.putString(k, null)
            }
        }

        fun buildBundleFromExtras(): StatefulFakeBundle {
            val fb = StatefulFakeBundle()
            for ((k, v) in extras) {
                putEntryIntoBundle(k, v, fb)
            }
            return fb
        }

        fun syncExtrasFromBundle(fb: StatefulFakeBundle?) {
            synchronized(extrasSyncLock) {
                if (isSyncingExtras) return
                isSyncingExtras = true
                try {
                    extras.clear()
                    if (fb != null) {
                        extras.putAll(fb.toMap())
                    }
                    lastSyncedSnapshot = LinkedHashMap(extras)
                } finally {
                    isSyncingExtras = false
                }
            }
        }

        fun syncDirectExtrasChangesToBundle(fb: StatefulFakeBundle) {
            synchronized(extrasSyncLock) {
                if (isSyncingExtras) return
                isSyncingExtras = true
                try {
                    val currentDirect = LinkedHashMap<String, Any?>(extras)
                    val removedKeys = lastSyncedSnapshot.keys.filter { it !in currentDirect }
                    for (rk in removedKeys) {
                        fb.remove(rk)
                    }
                    for ((k, v) in currentDirect) {
                        val hadKey = k in lastSyncedSnapshot
                        val prevVal = lastSyncedSnapshot[k]
                        if (!hadKey || prevVal != v) {
                            putEntryIntoBundle(k, v, fb)
                        }
                    }
                    lastSyncedSnapshot = LinkedHashMap(fb.toMap())
                    extras.clear()
                    extras.putAll(lastSyncedSnapshot)
                } finally {
                    isSyncingExtras = false
                }
            }
        }

        var currentFakeBundle: StatefulFakeBundle? = buildBundleFromExtras().apply {
            addMutationListener { syncExtrasFromBundle(this) }
        }
        var currentAndroidBundle: Bundle? = currentFakeBundle?.asAndroidBundle()

        lateinit var locProxy: Location
        val answer = Answer<Any?> { inv ->
            val args = inv.arguments
            when (inv.method.name) {
                "getProvider" -> provider
                "setProvider" -> {
                    provider = (args[0] as? String) ?: "gps"
                    null
                }
                "getLatitude" -> latitude
                "setLatitude" -> {
                    latitude = args[0] as Double
                    null
                }
                "getLongitude" -> longitude
                "setLongitude" -> {
                    longitude = args[0] as Double
                    null
                }
                "getAltitude" -> altitude
                "setAltitude" -> {
                    altitude = args[0] as Double
                    mFieldsMask = mFieldsMask or HAS_ALTITUDE_MASK
                    null
                }
                "hasAltitude" -> (mFieldsMask and HAS_ALTITUDE_MASK) != 0
                "getAccuracy" -> accuracy
                "setAccuracy" -> {
                    accuracy = args[0] as Float
                    mFieldsMask = mFieldsMask or HAS_HORIZONTAL_ACCURACY_MASK
                    null
                }
                "hasAccuracy" -> (mFieldsMask and HAS_HORIZONTAL_ACCURACY_MASK) != 0
                "getSpeed" -> speed
                "setSpeed" -> {
                    speed = args[0] as Float
                    mFieldsMask = mFieldsMask or HAS_SPEED_MASK
                    null
                }
                "hasSpeed" -> (mFieldsMask and HAS_SPEED_MASK) != 0
                "getBearing" -> bearing
                "setBearing" -> {
                    bearing = args[0] as Float
                    mFieldsMask = mFieldsMask or HAS_BEARING_MASK
                    null
                }
                "hasBearing" -> (mFieldsMask and HAS_BEARING_MASK) != 0
                "getTime" -> time
                "setTime" -> {
                    time = args[0] as Long
                    null
                }
                "getElapsedRealtimeNanos" -> elapsedRealtimeNanos
                "setElapsedRealtimeNanos" -> {
                    elapsedRealtimeNanos = args[0] as Long
                    null
                }
                "isMock" -> isMock()
                "isFromMockProvider" -> isFromMockProvider()
                "setMock" -> {
                    setMock(args[0] as Boolean)
                    null
                }
                "getExtras" -> {
                    // 增量将直接写入 fake.extras 的变更合并至 currentFakeBundle，绝不反向抹除
                    val fb = currentFakeBundle
                    if (fb != null) {
                        syncDirectExtrasChangesToBundle(fb)
                    }
                    currentAndroidBundle
                }
                "setExtras" -> {
                    val newB = args.getOrNull(0) as? Bundle
                    if (newB == null) {
                        currentFakeBundle = null
                        currentAndroidBundle = null
                        synchronized(extrasSyncLock) {
                            extras.clear()
                            lastSyncedSnapshot = LinkedHashMap()
                        }
                    } else {
                        val fb = StatefulFakeBundle.fromAndroidBundle(newB)
                        fb.addMutationListener { syncExtrasFromBundle(fb) }
                        currentFakeBundle = fb
                        currentAndroidBundle = fb.asAndroidBundle()
                        syncExtrasFromBundle(fb)
                    }
                    null
                }
                "equals" -> locProxy === args.getOrNull(0)
                "hashCode" -> System.identityHashCode(locProxy)
                "toString" -> "E2eAndroidLocationBridge($provider, $latitude, $longitude)"
                else -> Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }
        locProxy = Mockito.mock(Location::class.java, Mockito.withSettings().defaultAnswer(answer))
        return locProxy
    }
}
