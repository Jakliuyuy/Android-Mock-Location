package com.mockloc.data.route

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RouteRepository(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutex = Mutex()
    private val routes = ArrayList<PlannedRoute>()
    private var initialized = false

    private val _routesFlow = MutableStateFlow<List<PlannedRoute>>(emptyList())
    val routesFlow: StateFlow<List<PlannedRoute>> = _routesFlow.asStateFlow()

    init {
        ioScope.launch {
            runCatching { ensureInitialized() }
        }
    }

    fun observeRoutes(): StateFlow<List<PlannedRoute>> = routesFlow

    suspend fun ensureInitialized() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureInitializedLocked()
            }
        }
    }

    suspend fun getAllRoutes(): List<PlannedRoute> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureInitializedLocked()
            snapshotRoutesLocked()
        }
    }

    suspend fun getRouteById(id: String): PlannedRoute? = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext null
        mutex.withLock {
            ensureInitializedLocked()
            routes.firstOrNull { it.id == cleanId }?.let { deepCopyRoute(it) }
        }
    }

    suspend fun saveRoute(route: PlannedRoute): Result<PlannedRoute> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureInitializedLocked()

            val existingIndex = routes.indexOfFirst { it.id == route.id.trim() }
            val normalizedRes = validateAndNormalizeRoute(
                route = route,
                assignIdIfBlank = existingIndex < 0,
                fallbackCreatedAtMs = if (existingIndex >= 0) routes[existingIndex].createdAtMs else timeProvider().coerceAtLeast(0L)
            )
            val normalized = normalizedRes.getOrElse { return@withContext Result.failure(it) }

            val nextList = ArrayList(routes)
            if (existingIndex >= 0) {
                nextList[existingIndex] = normalized
            } else {
                nextList.add(0, normalized)
            }

            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("写入路线存储失败"))
            }
            applySnapshotLocked(nextList)
            Result.success(deepCopyRoute(normalized))
        }
    }

    suspend fun updateRoute(route: PlannedRoute): Result<PlannedRoute> = withContext(ioDispatcher) {
        val cleanId = route.id.trim()
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(NoSuchElementException("路线 ID 不能为空"))
        }

        mutex.withLock {
            ensureInitializedLocked()
            val existingIndex = routes.indexOfFirst { it.id == cleanId }
            if (existingIndex < 0) {
                return@withContext Result.failure(NoSuchElementException("未找到路线: $cleanId"))
            }

            val existing = routes[existingIndex]
            val normalized = validateAndNormalizeRoute(
                route = route.copy(id = cleanId),
                assignIdIfBlank = false,
                fallbackCreatedAtMs = existing.createdAtMs
            ).getOrElse { return@withContext Result.failure(it) }

            val nextList = ArrayList(routes)
            nextList[existingIndex] = normalized
            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("更新路线存储失败"))
            }
            applySnapshotLocked(nextList)
            Result.success(deepCopyRoute(normalized))
        }
    }

    suspend fun renameRoute(id: String, name: String): Result<PlannedRoute> = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(NoSuchElementException("路线 ID 不能为空"))
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("路线名称不能为空"))
        }
        if (cleanName.length > MAX_ROUTE_NAME_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("路线名称过长"))
        }

        mutex.withLock {
            ensureInitializedLocked()
            val existingIndex = routes.indexOfFirst { it.id == cleanId }
            if (existingIndex < 0) {
                return@withContext Result.failure(NoSuchElementException("未找到路线: $cleanId"))
            }
            val updated = routes[existingIndex].copy(name = cleanName)
            val nextList = ArrayList(routes)
            nextList[existingIndex] = updated

            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("重命名路线存储失败"))
            }
            applySnapshotLocked(nextList)
            Result.success(deepCopyRoute(updated))
        }
    }

    suspend fun deleteRoute(id: String): Boolean = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext false

        mutex.withLock {
            ensureInitializedLocked()
            val idx = routes.indexOfFirst { it.id == cleanId }
            if (idx < 0) return@withContext false

            val nextList = ArrayList(routes)
            nextList.removeAt(idx)
            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext false
            }
            applySnapshotLocked(nextList)
            true
        }
    }

    suspend fun clearAllRoutes(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureInitializedLocked()
            val empty = emptyList<PlannedRoute>()
            if (!persistLocked(empty, markInitialized = true)) {
                return@withContext false
            }
            applySnapshotLocked(empty)
            true
        }
    }

    fun getDefaultSampleRoutes(): List<PlannedRoute> {
        val baseTime = 1_700_000_000_000L
        return listOf(
            PlannedRoute(
                id = "default_route_beijing_axis",
                name = "北京中轴线观光路线",
                createdAtMs = baseTime,
                waypoints = listOf(
                    Waypoint(
                        point = GeoPoint(39.9087, 116.3975, 44.0, 5.0f, baseTime),
                        name = "天安门广场",
                        dwellTimeSeconds = 2.0,
                        targetSpeedMps = 1.5f
                    ),
                    Waypoint(
                        point = GeoPoint(39.9140, 116.3972, 45.0, 5.0f, baseTime),
                        name = "故宫午门",
                        dwellTimeSeconds = 1.5,
                        targetSpeedMps = 1.5f
                    ),
                    Waypoint(
                        point = GeoPoint(39.9245, 116.3969, 60.0, 5.0f, baseTime),
                        name = "景山万春亭",
                        dwellTimeSeconds = 2.0,
                        targetSpeedMps = 2.0f
                    ),
                    Waypoint(
                        point = GeoPoint(39.9929, 116.3965, 40.0, 5.0f, baseTime),
                        name = "奥林匹克公园鸟巢",
                        dwellTimeSeconds = 0.0,
                        targetSpeedMps = 4.2f
                    )
                )
            ),
            PlannedRoute(
                id = "default_route_hangzhou_westlake",
                name = "杭州西湖环湖骑行",
                createdAtMs = baseTime - 1L,
                waypoints = listOf(
                    Waypoint(
                        point = GeoPoint(30.2590, 120.1515, 18.0, 5.0f, baseTime),
                        name = "断桥残雪",
                        dwellTimeSeconds = 1.0,
                        targetSpeedMps = 4.2f
                    ),
                    Waypoint(
                        point = GeoPoint(30.2512, 120.1436, 20.0, 5.0f, baseTime),
                        name = "孤山公园",
                        dwellTimeSeconds = 1.0,
                        targetSpeedMps = 4.2f
                    ),
                    Waypoint(
                        point = GeoPoint(30.2368, 120.1370, 16.0, 5.0f, baseTime),
                        name = "苏堤春晓",
                        dwellTimeSeconds = 1.5,
                        targetSpeedMps = 4.2f
                    ),
                    Waypoint(
                        point = GeoPoint(30.2312, 120.1489, 25.0, 5.0f, baseTime),
                        name = "雷峰塔",
                        dwellTimeSeconds = 0.0,
                        targetSpeedMps = 4.2f
                    )
                )
            ),
            PlannedRoute(
                id = "default_route_shanghai_lujiazui",
                name = "上海陆家嘴金融区漫步",
                createdAtMs = baseTime - 2L,
                waypoints = listOf(
                    Waypoint(
                        point = GeoPoint(31.2397, 121.4998, 15.0, 5.0f, baseTime),
                        name = "东方明珠",
                        dwellTimeSeconds = 1.0,
                        targetSpeedMps = 1.4f
                    ),
                    Waypoint(
                        point = GeoPoint(31.2335, 121.5055, 16.0, 5.0f, baseTime),
                        name = "上海中心大厦",
                        dwellTimeSeconds = 1.0,
                        targetSpeedMps = 1.4f
                    ),
                    Waypoint(
                        point = GeoPoint(31.2382, 121.4956, 12.0, 5.0f, baseTime),
                        name = "陆家嘴滨江大道",
                        dwellTimeSeconds = 0.0,
                        targetSpeedMps = 1.4f
                    )
                )
            )
        )
    }

    suspend fun resetToDefaultSampleRoutes(): Result<List<PlannedRoute>> = withContext(ioDispatcher) {
        mutex.withLock {
            val defaults = getDefaultSampleRoutes()
            if (!persistLocked(defaults, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("恢复默认示例路线失败"))
            }
            initialized = true
            applySnapshotLocked(defaults)
            Result.success(snapshotRoutesLocked())
        }
    }

    private fun ensureInitializedLocked() {
        if (initialized) return

        val wasInitialized = readBooleanSafe(KEY_INITIALIZED, false)
        val rawJson = readStringSafe(KEY_ROUTES_JSON)

        if (wasInitialized) {
            val parsed = parseJsonArray(rawJson)
            if (parsed != null) {
                applySnapshotLocked(parsed)
                initialized = true
            } else {
                val empty = emptyList<PlannedRoute>()
                if (persistLocked(empty, markInitialized = true)) {
                    applySnapshotLocked(empty)
                    initialized = true
                } else {
                    applySnapshotLocked(empty)
                    initialized = false
                }
            }
            return
        }

        val migrated = parseJsonArray(rawJson)
        if (migrated != null && migrated.isNotEmpty()) {
            if (persistLocked(migrated, markInitialized = true)) {
                applySnapshotLocked(migrated)
                initialized = true
            } else {
                applySnapshotLocked(emptyList())
                initialized = false
            }
            return
        }

        val defaults = getDefaultSampleRoutes()
        if (persistLocked(defaults, markInitialized = true)) {
            applySnapshotLocked(defaults)
            initialized = true
        } else {
            applySnapshotLocked(emptyList())
            initialized = false
        }
    }

    private fun validateAndNormalizeRoute(
        route: PlannedRoute,
        assignIdIfBlank: Boolean,
        fallbackCreatedAtMs: Long
    ): Result<PlannedRoute> {
        val cleanName = route.name.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("路线名称不能为空"))
        }
        if (cleanName.length > MAX_ROUTE_NAME_LENGTH) {
            return Result.failure(IllegalArgumentException("路线名称过长"))
        }
        if (route.waypoints.isEmpty()) {
            return Result.failure(IllegalArgumentException("路线至少需要 1 个航点"))
        }
        if (route.waypoints.size > MAX_WAYPOINTS_PER_ROUTE) {
            return Result.failure(IllegalArgumentException("路线航点数量不能超过 $MAX_WAYPOINTS_PER_ROUTE"))
        }

        val cleanId = route.id.trim().ifEmpty {
            if (assignIdIfBlank) idGenerator().trim().ifEmpty { UUID.randomUUID().toString() }
            else return Result.failure(IllegalArgumentException("路线 ID 不能为空"))
        }

        val cleanWaypoints = ArrayList<Waypoint>(route.waypoints.size)
        for ((index, wp) in route.waypoints.withIndex()) {
            val pt = wp.point
            if (!GeoPoint.isValid(pt.latitude, pt.longitude)) {
                return Result.failure(IllegalArgumentException("第 ${index + 1} 个航点经纬度超出有效范围"))
            }
            if (!pt.altitude.isFinite() || !pt.accuracy.isFinite() || pt.accuracy < 0f) {
                return Result.failure(IllegalArgumentException("第 ${index + 1} 个航点海拔或精度非法"))
            }
            if (!wp.dwellTimeSeconds.isFinite() || wp.dwellTimeSeconds < 0.0 || wp.dwellTimeSeconds > MAX_DWELL_TIME_SECONDS) {
                return Result.failure(IllegalArgumentException("第 ${index + 1} 个航点停留时间非法"))
            }
            val speed = wp.targetSpeedMps
            if (speed != null && (!speed.isFinite() || speed <= 0f || speed > MAX_WAYPOINT_SPEED_MPS)) {
                return Result.failure(IllegalArgumentException("第 ${index + 1} 个航点目标速度必须在 (0, $MAX_WAYPOINT_SPEED_MPS] m/s 范围内"))
            }
            cleanWaypoints.add(
                Waypoint(
                    point = pt.copy(),
                    name = wp.name.trim(),
                    dwellTimeSeconds = wp.dwellTimeSeconds,
                    targetSpeedMps = speed
                )
            )
        }

        val createdAt = if (route.createdAtMs > 0L) route.createdAtMs else fallbackCreatedAtMs
        return Result.success(
            PlannedRoute(
                id = cleanId,
                name = cleanName,
                waypoints = cleanWaypoints.toList(),
                createdAtMs = createdAt
            )
        )
    }

    private fun applySnapshotLocked(next: List<PlannedRoute>) {
        routes.clear()
        for (route in next) {
            routes.add(deepCopyRoute(route))
        }
        _routesFlow.value = snapshotRoutesLocked()
    }

    private fun snapshotRoutesLocked(): List<PlannedRoute> {
        return routes.map { deepCopyRoute(it) }.toList()
    }

    private fun deepCopyRoute(route: PlannedRoute): PlannedRoute {
        return PlannedRoute(
            id = route.id,
            name = route.name,
            waypoints = route.waypoints.map { wp ->
                wp.copy(point = wp.point.copy())
            }.toList(),
            createdAtMs = route.createdAtMs
        )
    }

    private fun persistLocked(list: List<PlannedRoute>, markInitialized: Boolean): Boolean {
        val json = serializeToJson(list)
        return try {
            prefs.edit()
                .putString(KEY_ROUTES_JSON, json)
                .putBoolean(KEY_INITIALIZED, markInitialized)
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .commit()
        } catch (_: Throwable) {
            false
        }
    }

    private fun serializeToJson(list: List<PlannedRoute>): String {
        val rootArray = JSONArray()
        for (route in list) {
            val wpArray = JSONArray()
            for (wp in route.waypoints) {
                val pt = wp.point
                if (!GeoPoint.isValid(pt.latitude, pt.longitude) || !pt.altitude.isFinite() || !pt.accuracy.isFinite()) {
                    continue
                }
                val ptObj = JSONObject().apply {
                    put("latitude", pt.latitude)
                    put("longitude", pt.longitude)
                    put("altitude", pt.altitude)
                    put("accuracy", pt.accuracy.toDouble())
                    put("timestamp", pt.timestamp)
                }
                val wpObj = JSONObject().apply {
                    put("name", wp.name)
                    put("dwellTimeSeconds", wp.dwellTimeSeconds)
                    if (wp.targetSpeedMps != null && wp.targetSpeedMps.isFinite()) {
                        put("targetSpeedMps", wp.targetSpeedMps.toDouble())
                    } else {
                        put("targetSpeedMps", JSONObject.NULL)
                    }
                    put("point", ptObj)
                }
                wpArray.put(wpObj)
            }

            if (wpArray.length() == 0) continue

            val routeObj = JSONObject().apply {
                put("id", route.id)
                put("name", route.name)
                put("createdAtMs", route.createdAtMs)
                put("waypoints", wpArray)
            }
            rootArray.put(routeObj)
        }
        return rootArray.toString()
    }

    private fun parseJsonArray(rawJson: String?): MutableList<PlannedRoute>? {
        if (rawJson == null) return null
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return mutableListOf()
        if (!trimmed.startsWith("[")) return null

        val array = try {
            JSONArray(trimmed)
        } catch (_: Throwable) {
            return null
        }

        val parsed = ArrayList<PlannedRoute>(array.length())
        val seenIds = HashSet<String>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val route = parseSingleRoute(item) ?: continue
            if (seenIds.add(route.id)) {
                parsed.add(route)
            }
        }
        return parsed
    }

    private fun parseSingleRoute(obj: JSONObject): PlannedRoute? {
        return runCatching {
            val id = optNonBlankString(obj, "id") ?: return@runCatching null
            val name = optNonBlankString(obj, "name")?.take(MAX_ROUTE_NAME_LENGTH) ?: return@runCatching null
            val createdAtMs = optLongSafe(obj, "createdAtMs", 0L).coerceAtLeast(0L)

            val wpArray = obj.optJSONArray("waypoints") ?: return@runCatching null
            if (wpArray.length() == 0 || wpArray.length() > MAX_WAYPOINTS_PER_ROUTE) {
                return@runCatching null
            }

            val waypoints = ArrayList<Waypoint>(wpArray.length())
            for (i in 0 until wpArray.length()) {
                val wpObj = wpArray.optJSONObject(i) ?: return@runCatching null
                val wp = parseSingleWaypoint(wpObj) ?: return@runCatching null
                waypoints.add(wp)
            }

            PlannedRoute(
                id = id,
                name = name,
                waypoints = waypoints.toList(),
                createdAtMs = createdAtMs
            )
        }.getOrNull()
    }

    private fun parseSingleWaypoint(obj: JSONObject): Waypoint? {
        val ptObj = obj.optJSONObject("point") ?: return null

        val lat = optFiniteDouble(ptObj, "latitude") ?: return null
        val lon = optFiniteDouble(ptObj, "longitude") ?: return null
        if (!GeoPoint.isValid(lat, lon)) return null

        val alt = optFiniteDouble(ptObj, "altitude") ?: 0.0
        val accDouble = optFiniteDouble(ptObj, "accuracy") ?: 5.0
        val acc = accDouble.toFloat()
        if (!acc.isFinite() || acc < 0f) return null
        val ts = optLongSafe(ptObj, "timestamp", 0L).coerceAtLeast(0L)

        val dwell = optFiniteDouble(obj, "dwellTimeSeconds") ?: 0.0
        if (dwell < 0.0 || dwell > MAX_DWELL_TIME_SECONDS) return null

        val targetSpeed: Float? = if (!obj.has("targetSpeedMps") || obj.isNull("targetSpeedMps")) {
            null
        } else {
            val speedDouble = optFiniteDouble(obj, "targetSpeedMps") ?: return null
            val speed = speedDouble.toFloat()
            if (!speed.isFinite() || speed <= 0f || speed > MAX_WAYPOINT_SPEED_MPS) {
                return null
            }
            speed
        }

        val wpName = optStringSafe(obj, "name")

        return runCatching {
            val geoPoint = GeoPoint(
                latitude = lat,
                longitude = lon,
                altitude = alt,
                accuracy = acc,
                timestamp = ts
            )
            Waypoint(
                point = geoPoint,
                name = wpName,
                dwellTimeSeconds = dwell,
                targetSpeedMps = targetSpeed
            )
        }.getOrNull()
    }

    private fun readStringSafe(key: String): String? {
        return try {
            prefs.getString(key, null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBooleanSafe(key: String, defaultValue: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun optNonBlankString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.opt(key)
        if (value !is String) return null
        val str = value.trim()
        return str.ifEmpty { null }
    }

    private fun optStringSafe(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        val value = obj.opt(key)
        if (value is JSONArray || value is JSONObject) return ""
        return value?.toString()?.trim().orEmpty()
    }

    private fun optFiniteDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.opt(key)
        val num = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return num?.takeIf { it.isFinite() }
    }

    private fun optLongSafe(obj: JSONObject, key: String, defaultValue: Long): Long {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    companion object {
        const val PREFS_NAME = "mockloc_routes"
        const val KEY_ROUTES_JSON = "routes_json"
        const val KEY_INITIALIZED = "routes_initialized"
        const val KEY_SCHEMA_VERSION = "routes_schema_version"
        const val SCHEMA_VERSION = 1

        const val MAX_WAYPOINTS_PER_ROUTE = 1000
        const val MAX_WAYPOINT_SPEED_MPS = 300.0f
        const val MAX_DWELL_TIME_SECONDS = 86_400.0
        const val MAX_ROUTE_NAME_LENGTH = 100

        @Volatile
        private var instance: RouteRepository? = null

        fun getInstance(context: Context): RouteRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext ?: context
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                RouteRepository(prefs = prefs).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: RouteRepository?) {
            synchronized(this) {
                instance = repository
            }
        }
    }
}
