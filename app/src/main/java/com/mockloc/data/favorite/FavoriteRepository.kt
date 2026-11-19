package com.mockloc.data.favorite

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.domain.model.GeoPoint
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
import kotlin.math.abs

data class FavoriteUsageStats(
    val lastUsedAtMs: Long = 0L,
    val useCount: Int = 0
)

class FavoriteRepository(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private data class FavoriteRecord(
        val location: FavoriteLocation,
        val lastUsedAtMs: Long = 0L,
        val useCount: Int = 0
    )

    private val mutex = Mutex()
    private val records = ArrayList<FavoriteRecord>()
    private var initialized = false

    private val _favoritesFlow = MutableStateFlow<List<FavoriteLocation>>(emptyList())
    val favoritesFlow: StateFlow<List<FavoriteLocation>> = _favoritesFlow.asStateFlow()

    private val _usageStatsFlow = MutableStateFlow<Map<String, FavoriteUsageStats>>(emptyMap())
    val usageStatsFlow: StateFlow<Map<String, FavoriteUsageStats>> = _usageStatsFlow.asStateFlow()

    init {
        ioScope.launch {
            runCatching { ensureInitialized() }
        }
    }

    fun observeFavorites(): StateFlow<List<FavoriteLocation>> = favoritesFlow

    fun observeUsageStats(): StateFlow<Map<String, FavoriteUsageStats>> = usageStatsFlow

    suspend fun ensureInitialized() {
        withContext(ioDispatcher) {
            mutex.withLock {
                ensureInitializedLocked()
            }
        }
    }

    suspend fun getAll(): List<FavoriteLocation> = withContext(ioDispatcher) {
        mutex.withLock {
            ensureInitializedLocked()
            snapshotLocationsLocked()
        }
    }

    suspend fun getById(id: String): FavoriteLocation? = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext null
        mutex.withLock {
            ensureInitializedLocked()
            records.firstOrNull { it.location.id == cleanId }?.location
        }
    }

    suspend fun getUsageStats(id: String): FavoriteUsageStats? = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext null
        mutex.withLock {
            ensureInitializedLocked()
            records.firstOrNull { it.location.id == cleanId }?.let {
                FavoriteUsageStats(lastUsedAtMs = it.lastUsedAtMs, useCount = it.useCount)
            }
        }
    }

    suspend fun search(query: String): List<FavoriteLocation> = withContext(ioDispatcher) {
        val trimmed = query.trim()
        mutex.withLock {
            ensureInitializedLocked()
            val all = snapshotLocationsLocked()
            if (trimmed.isEmpty()) return@withLock all
            val tokens = WHITESPACE_REGEX.split(trimmed).filter { it.isNotEmpty() }
            all.filter { item ->
                tokens.all { token ->
                    item.name.contains(token, ignoreCase = true) ||
                        item.address.contains(token, ignoreCase = true)
                }
            }
        }
    }

    suspend fun add(
        name: String,
        address: String = "",
        latitude: Double,
        longitude: Double,
        altitude: Double = 0.0,
        coordinateType: CoordinateType = CoordinateType.WGS84
    ): Result<FavoriteLocation> {
        val loc = FavoriteLocation(
            id = "",
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            coordinateType = coordinateType,
            createdAtMs = timeProvider().coerceAtLeast(0L)
        )
        return add(loc)
    }

    suspend fun add(location: FavoriteLocation): Result<FavoriteLocation> = withContext(ioDispatcher) {
        val cleanName = location.name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("收藏名称不能为空"))
        }
        if (cleanName.length > MAX_NAME_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("收藏名称过长"))
        }
        if (!GeoPoint.isValid(location.latitude, location.longitude) || !location.altitude.isFinite()) {
            return@withContext Result.failure(IllegalArgumentException("坐标超出有效范围"))
        }

        val cleanAddress = location.address.trim()
        val requestedId = location.id.trim()
        val now = timeProvider().coerceAtLeast(0L)

        mutex.withLock {
            ensureInitializedLocked()

            val idMatchIndex = if (requestedId.isNotEmpty()) {
                records.indexOfFirst { it.location.id == requestedId }
            } else {
                -1
            }
            val duplicateIndex = if (idMatchIndex >= 0) {
                idMatchIndex
            } else {
                records.indexOfFirst { existing ->
                    isSamePlace(existing.location, cleanName, location.latitude, location.longitude)
                }
            }

            val nextList = ArrayList(records)
            val resultLocation: FavoriteLocation

            if (duplicateIndex >= 0) {
                val existing = nextList[duplicateIndex]
                val hasOtherConflict = nextList.any { other ->
                    other.location.id != existing.location.id &&
                        isSamePlace(other.location, cleanName, location.latitude, location.longitude)
                }
                if (hasOtherConflict) {
                    return@withContext Result.failure(IllegalArgumentException("已存在相同或极近的收藏地点"))
                }
                val mergedAddress = if (cleanAddress.isNotEmpty()) cleanAddress else existing.location.address
                resultLocation = existing.location.copy(
                    name = cleanName,
                    address = mergedAddress,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    coordinateType = location.coordinateType
                )
                nextList[duplicateIndex] = existing.copy(location = resultLocation)
            } else {
                val assignedId = requestedId.ifEmpty { generateUniqueIdLocked(nextList) }
                val createdAt = if (location.createdAtMs > 0L) location.createdAtMs else now
                resultLocation = FavoriteLocation(
                    id = assignedId,
                    name = cleanName,
                    address = cleanAddress,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = location.altitude,
                    coordinateType = location.coordinateType,
                    createdAtMs = createdAt
                )
                nextList.add(FavoriteRecord(location = resultLocation, lastUsedAtMs = 0L, useCount = 0))
            }

            sortRecordsInPlace(nextList)
            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("写入收藏夹存储失败"))
            }
            applySnapshotLocked(nextList)
            Result.success(resultLocation)
        }
    }

    suspend fun update(
        id: String,
        name: String,
        address: String? = null,
        point: GeoPoint? = null
    ): Result<FavoriteLocation> = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) {
            return@withContext Result.failure(NoSuchElementException("收藏 ID 不能为空"))
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("收藏名称不能为空"))
        }
        if (cleanName.length > MAX_NAME_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("收藏名称过长"))
        }
        if (point != null && (!GeoPoint.isValid(point.latitude, point.longitude) || !point.altitude.isFinite())) {
            return@withContext Result.failure(IllegalArgumentException("坐标超出有效范围"))
        }

        mutex.withLock {
            ensureInitializedLocked()

            val targetIndex = records.indexOfFirst { it.location.id == cleanId }
            if (targetIndex < 0) {
                return@withContext Result.failure(NoSuchElementException("未找到收藏记录: $cleanId"))
            }

            val existing = records[targetIndex]
            val newLat = point?.latitude ?: existing.location.latitude
            val newLon = point?.longitude ?: existing.location.longitude
            val newAlt = point?.altitude ?: existing.location.altitude
            val newAddress = address?.trim() ?: existing.location.address

            val conflict = records.any { other ->
                other.location.id != cleanId &&
                    isSamePlace(other.location, cleanName, newLat, newLon)
            }
            if (conflict) {
                return@withContext Result.failure(IllegalArgumentException("已存在相同的收藏地点"))
            }

            val updatedLocation = existing.location.copy(
                name = cleanName,
                address = newAddress,
                latitude = newLat,
                longitude = newLon,
                altitude = newAlt,
                coordinateType = if (point != null) CoordinateType.WGS84 else existing.location.coordinateType
            )

            val nextList = ArrayList(records)
            nextList[targetIndex] = existing.copy(location = updatedLocation)
            sortRecordsInPlace(nextList)

            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("更新收藏夹存储失败"))
            }
            applySnapshotLocked(nextList)
            Result.success(updatedLocation)
        }
    }

    suspend fun remove(id: String): Boolean = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext false

        mutex.withLock {
            ensureInitializedLocked()
            val idx = records.indexOfFirst { it.location.id == cleanId }
            if (idx < 0) return@withContext false

            val nextList = ArrayList(records)
            nextList.removeAt(idx)
            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext false
            }
            applySnapshotLocked(nextList)
            true
        }
    }

    suspend fun clearAll(): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureInitializedLocked()
            val empty = emptyList<FavoriteRecord>()
            if (!persistLocked(empty, markInitialized = true)) {
                return@withContext false
            }
            applySnapshotLocked(empty)
            true
        }
    }

    suspend fun markUsed(id: String): Boolean = withContext(ioDispatcher) {
        val cleanId = id.trim()
        if (cleanId.isEmpty()) return@withContext false

        mutex.withLock {
            ensureInitializedLocked()
            val idx = records.indexOfFirst { it.location.id == cleanId }
            if (idx < 0) return@withContext false

            val old = records[idx]
            val now = timeProvider().coerceAtLeast(1L)
            val updated = old.copy(
                lastUsedAtMs = now,
                useCount = (old.useCount + 1).coerceAtLeast(1)
            )
            val nextList = ArrayList(records)
            nextList[idx] = updated
            sortRecordsInPlace(nextList)

            if (!persistLocked(nextList, markInitialized = true)) {
                return@withContext false
            }
            applySnapshotLocked(nextList)
            true
        }
    }

    suspend fun resetToDefaults(): Result<List<FavoriteLocation>> = withContext(ioDispatcher) {
        mutex.withLock {
            val defaults = buildDefaultRecords(timeProvider().coerceAtLeast(1L))
            sortRecordsInPlace(defaults)
            if (!persistLocked(defaults, markInitialized = true)) {
                return@withContext Result.failure(IllegalStateException("重置默认收藏失败"))
            }
            initialized = true
            applySnapshotLocked(defaults)
            Result.success(snapshotLocationsLocked())
        }
    }

    private fun ensureInitializedLocked() {
        if (initialized) return

        val wasInitialized = readBooleanSafe(KEY_INITIALIZED, false)
        val rawJson = readStringSafe(KEY_FAVORITES_JSON)

        if (wasInitialized) {
            val parsed = parseJsonArray(rawJson)
            if (parsed != null) {
                sortRecordsInPlace(parsed)
                applySnapshotLocked(parsed)
                initialized = true
            } else {
                // 已初始化但存储损坏，安全恢复空列表并清理损坏 JSON，不复活默认地点
                val empty = emptyList<FavoriteRecord>()
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
            sortRecordsInPlace(migrated)
            if (persistLocked(migrated, markInitialized = true)) {
                applySnapshotLocked(migrated)
                initialized = true
            } else {
                applySnapshotLocked(emptyList())
                initialized = false
            }
            return
        }

        val defaults = buildDefaultRecords(timeProvider().coerceAtLeast(1L))
        sortRecordsInPlace(defaults)
        if (persistLocked(defaults, markInitialized = true)) {
            applySnapshotLocked(defaults)
            initialized = true
        } else {
            applySnapshotLocked(emptyList())
            initialized = false
        }
    }

    private fun isSamePlace(
        existing: FavoriteLocation,
        name: String,
        lat: Double,
        lon: Double
    ): Boolean {
        val dLat = abs(existing.latitude - lat)
        val dLon = abs(existing.longitude - lon)
        if (dLat < COORD_EXACT_EPSILON && dLon < COORD_EXACT_EPSILON) {
            return true
        }
        return existing.name.trim().equals(name, ignoreCase = true) &&
            dLat < COORD_NEAR_EPSILON &&
            dLon < COORD_NEAR_EPSILON
    }

    private fun generateUniqueIdLocked(list: List<FavoriteRecord>): String {
        val existingIds = list.map { it.location.id }.toSet()
        var candidate = idGenerator().trim()
        var attempt = 0
        while (candidate.isEmpty() || candidate in existingIds) {
            candidate = UUID.randomUUID().toString()
            attempt++
            if (attempt > 10) break
        }
        return candidate
    }

    private fun sortRecordsInPlace(list: MutableList<FavoriteRecord>) {
        list.sortWith(
            Comparator { a, b ->
                val usedCmp = b.lastUsedAtMs.compareTo(a.lastUsedAtMs)
                if (usedCmp != 0) return@Comparator usedCmp
                val countCmp = b.useCount.compareTo(a.useCount)
                if (countCmp != 0) return@Comparator countCmp
                val createdCmp = b.location.createdAtMs.compareTo(a.location.createdAtMs)
                if (createdCmp != 0) return@Comparator createdCmp
                a.location.id.compareTo(b.location.id)
            }
        )
    }

    private fun applySnapshotLocked(next: List<FavoriteRecord>) {
        records.clear()
        records.addAll(next)
        _favoritesFlow.value = next.map { it.location }.toList()
        _usageStatsFlow.value = next.associate {
            it.location.id to FavoriteUsageStats(
                lastUsedAtMs = it.lastUsedAtMs,
                useCount = it.useCount
            )
        }
    }

    private fun snapshotLocationsLocked(): List<FavoriteLocation> {
        return records.map { it.location }.toList()
    }

    private fun persistLocked(list: List<FavoriteRecord>, markInitialized: Boolean): Boolean {
        val json = serializeToJson(list)
        return try {
            prefs.edit()
                .putString(KEY_FAVORITES_JSON, json)
                .putBoolean(KEY_INITIALIZED, markInitialized)
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .commit()
        } catch (_: Throwable) {
            false
        }
    }

    private fun serializeToJson(list: List<FavoriteRecord>): String {
        val array = JSONArray()
        for (record in list) {
            val loc = record.location
            if (!GeoPoint.isValid(loc.latitude, loc.longitude) || !loc.altitude.isFinite()) {
                continue
            }
            val obj = JSONObject().apply {
                put("id", loc.id)
                put("name", loc.name)
                put("address", loc.address)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("altitude", loc.altitude)
                put("coordinateType", loc.coordinateType.name)
                put("createdAtMs", loc.createdAtMs)
                put("lastUsedAtMs", record.lastUsedAtMs)
                put("useCount", record.useCount)
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseJsonArray(rawJson: String?): MutableList<FavoriteRecord>? {
        if (rawJson == null) return null
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return mutableListOf()
        if (!trimmed.startsWith("[")) return null

        val array = try {
            JSONArray(trimmed)
        } catch (_: Throwable) {
            return null
        }

        val parsed = ArrayList<FavoriteRecord>(array.length())
        val seenIds = HashSet<String>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val record = parseSingleRecord(item) ?: continue
            if (seenIds.add(record.location.id)) {
                parsed.add(record)
            }
        }
        return parsed
    }

    private fun parseSingleRecord(obj: JSONObject): FavoriteRecord? {
        return runCatching {
            val id = optNonBlankString(obj, "id") ?: return@runCatching null
            val name = optNonBlankString(obj, "name") ?: return@runCatching null
            val lat = optFiniteDouble(obj, "latitude") ?: return@runCatching null
            val lon = optFiniteDouble(obj, "longitude") ?: return@runCatching null
            if (!GeoPoint.isValid(lat, lon)) return@runCatching null

            val alt = optFiniteDouble(obj, "altitude") ?: 0.0
            val address = optStringSafe(obj, "address")
            val coordType = CoordinateType.fromString(optStringSafe(obj, "coordinateType"))
            val createdAtMs = optLongSafe(obj, "createdAtMs", 0L).coerceAtLeast(0L)
            val lastUsedAtMs = optLongSafe(obj, "lastUsedAtMs", 0L).coerceAtLeast(0L)
            val useCount = optIntSafe(obj, "useCount", 0).coerceAtLeast(0)

            FavoriteRecord(
                location = FavoriteLocation(
                    id = id,
                    name = name,
                    address = address,
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    coordinateType = coordType,
                    createdAtMs = createdAtMs
                ),
                lastUsedAtMs = lastUsedAtMs,
                useCount = useCount
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

    private fun optIntSafe(obj: JSONObject, key: String, defaultValue: Int): Int {
        if (!obj.has(key) || obj.isNull(key)) return defaultValue
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun buildDefaultRecords(baseTimeMs: Long): MutableList<FavoriteRecord> {
        return mutableListOf(
            FavoriteRecord(
                location = FavoriteLocation(
                    id = "default_beijing_tiananmen",
                    name = "北京天安门",
                    address = "北京市东城区东长安街",
                    latitude = 39.9087,
                    longitude = 116.3975,
                    altitude = 44.0,
                    coordinateType = CoordinateType.WGS84,
                    createdAtMs = baseTimeMs
                )
            ),
            FavoriteRecord(
                location = FavoriteLocation(
                    id = "default_shanghai_lujiazui",
                    name = "上海东方明珠（陆家嘴）",
                    address = "上海市浦东新区世纪大道1号",
                    latitude = 31.2397,
                    longitude = 121.4998,
                    altitude = 15.0,
                    coordinateType = CoordinateType.WGS84,
                    createdAtMs = (baseTimeMs - 1L).coerceAtLeast(0L)
                )
            ),
            FavoriteRecord(
                location = FavoriteLocation(
                    id = "default_guangzhou_tower",
                    name = "广州塔",
                    address = "广州市海珠区阅江西路222号",
                    latitude = 23.1065,
                    longitude = 113.3245,
                    altitude = 20.0,
                    coordinateType = CoordinateType.WGS84,
                    createdAtMs = (baseTimeMs - 2L).coerceAtLeast(0L)
                )
            ),
            FavoriteRecord(
                location = FavoriteLocation(
                    id = "default_shenzhen_civic",
                    name = "深圳市民中心",
                    address = "深圳市福田区福中三路1号",
                    latitude = 22.5446,
                    longitude = 114.0579,
                    altitude = 25.0,
                    coordinateType = CoordinateType.WGS84,
                    createdAtMs = (baseTimeMs - 3L).coerceAtLeast(0L)
                )
            ),
            FavoriteRecord(
                location = FavoriteLocation(
                    id = "default_hangzhou_westlake",
                    name = "杭州西湖（断桥残雪）",
                    address = "浙江省杭州市西湖区北山街",
                    latitude = 30.2590,
                    longitude = 120.1515,
                    altitude = 18.0,
                    coordinateType = CoordinateType.WGS84,
                    createdAtMs = (baseTimeMs - 4L).coerceAtLeast(0L)
                )
            )
        )
    }

    companion object {
        const val PREFS_NAME = "mockloc_favorites"
        const val KEY_FAVORITES_JSON = "favorites_json"
        const val KEY_INITIALIZED = "favorites_initialized"
        const val KEY_SCHEMA_VERSION = "favorites_schema_version"
        const val SCHEMA_VERSION = 1

        private const val MAX_NAME_LENGTH = 100
        private const val COORD_EXACT_EPSILON = 1e-6
        private const val COORD_NEAR_EPSILON = 1e-4
        private val WHITESPACE_REGEX = Regex("""\s+""")

        @Volatile
        private var instance: FavoriteRepository? = null

        fun getInstance(context: Context): FavoriteRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext ?: context
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                FavoriteRepository(prefs = prefs).also { instance = it }
            }
        }

        fun setInstanceForTest(repository: FavoriteRepository?) {
            synchronized(this) {
                instance = repository
            }
        }
    }
}
