package com.mockloc.ipc

import android.content.SharedPreferences
import android.os.Bundle
import com.mockloc.data.config.MockConfig
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.MockMode
import org.json.JSONArray
import org.json.JSONObject

object ConfigSyncSerializer {

    fun toBundle(config: MockConfig, targetBundle: Bundle = Bundle()): Bundle {
        val safe = config.sanitized()
        return targetBundle.apply {
            putBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, safe.enabled)
            putBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, safe.enabled)
            putString(IpcProtocol.KEY_MOCK_MODE, safe.mode.name)
            putDouble(IpcProtocol.KEY_LATITUDE, safe.latitude)
            putDouble(IpcProtocol.KEY_LONGITUDE, safe.longitude)
            putDouble(IpcProtocol.KEY_ALTITUDE, safe.altitude)
            putString(IpcProtocol.KEY_COORDINATE_TYPE, safe.coordinateType.name)
            putFloat(IpcProtocol.KEY_ACCURACY, safe.accuracy)
            putFloat(IpcProtocol.KEY_SPEED, safe.speed)
            putFloat(IpcProtocol.KEY_BEARING, safe.bearing)
            putBoolean(IpcProtocol.KEY_JITTER_ENABLED, safe.jitterEnabled)
            putDouble(IpcProtocol.KEY_JITTER_RADIUS_METERS, safe.jitterRadiusMeters)
            putBoolean(IpcProtocol.KEY_ANTI_DETECT_CELL, safe.antiDetectCellEnabled)
            putBoolean(IpcProtocol.KEY_ANTI_DETECT_WIFI, safe.antiDetectWifiEnabled)
            putBoolean(IpcProtocol.KEY_ANTI_DETECT_GNSS, safe.antiDetectGnssEnabled)
            putStringArrayList(IpcProtocol.KEY_TARGET_PACKAGES, ArrayList(safe.targetPackages))
            putLong(IpcProtocol.KEY_UPDATED_AT_MS, safe.updatedAtMs)
            putLong(IpcProtocol.KEY_VERSION, safe.version)
            putString(IpcProtocol.KEY_CONFIG_JSON, toJson(safe))
        }
    }

    fun fromBundle(bundle: Bundle?, fallback: MockConfig = MockConfig.DEFAULT): MockConfig {
        val safeFallback = fallback.sanitized()
        if (bundle == null) return safeFallback

        // 若仅有 JSON 字段或缺少细粒度字段，先尝试从 JSON 初始化基准，再被显式键覆盖
        val baseFromJson = safeCall {
            val rawJson = bundle.getString(IpcProtocol.KEY_CONFIG_JSON)
            if (!rawJson.isNullOrBlank()) fromJson(rawJson, safeFallback) else safeFallback
        } ?: safeFallback

        val enabled = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_IS_MOCK_ENABLED)) {
                bundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, baseFromJson.enabled)
            } else {
                baseFromJson.enabled
            }
        } ?: baseFromJson.enabled

        val mode = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_MOCK_MODE)) {
                MockMode.fromString(bundle.getString(IpcProtocol.KEY_MOCK_MODE))
            } else {
                baseFromJson.mode
            }
        } ?: baseFromJson.mode

        val lat = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_LATITUDE)) {
                bundle.getDouble(IpcProtocol.KEY_LATITUDE, baseFromJson.latitude)
            } else {
                baseFromJson.latitude
            }
        } ?: baseFromJson.latitude

        val lon = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_LONGITUDE)) {
                bundle.getDouble(IpcProtocol.KEY_LONGITUDE, baseFromJson.longitude)
            } else {
                baseFromJson.longitude
            }
        } ?: baseFromJson.longitude

        val alt = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_ALTITUDE)) {
                bundle.getDouble(IpcProtocol.KEY_ALTITUDE, baseFromJson.altitude)
            } else {
                baseFromJson.altitude
            }
        } ?: baseFromJson.altitude

        val coordType = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_COORDINATE_TYPE)) {
                CoordinateType.fromString(bundle.getString(IpcProtocol.KEY_COORDINATE_TYPE))
            } else {
                baseFromJson.coordinateType
            }
        } ?: baseFromJson.coordinateType

        val accuracy = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_ACCURACY)) {
                bundle.getFloat(IpcProtocol.KEY_ACCURACY, baseFromJson.accuracy)
            } else {
                baseFromJson.accuracy
            }
        } ?: baseFromJson.accuracy

        val speed = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_SPEED)) {
                bundle.getFloat(IpcProtocol.KEY_SPEED, baseFromJson.speed)
            } else {
                baseFromJson.speed
            }
        } ?: baseFromJson.speed

        val bearing = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_BEARING)) {
                bundle.getFloat(IpcProtocol.KEY_BEARING, baseFromJson.bearing)
            } else {
                baseFromJson.bearing
            }
        } ?: baseFromJson.bearing

        val jitterEnabled = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_JITTER_ENABLED)) {
                bundle.getBoolean(IpcProtocol.KEY_JITTER_ENABLED, baseFromJson.jitterEnabled)
            } else {
                baseFromJson.jitterEnabled
            }
        } ?: baseFromJson.jitterEnabled

        val jitterRadius = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_JITTER_RADIUS_METERS)) {
                bundle.getDouble(IpcProtocol.KEY_JITTER_RADIUS_METERS, baseFromJson.jitterRadiusMeters)
            } else {
                baseFromJson.jitterRadiusMeters
            }
        } ?: baseFromJson.jitterRadiusMeters

        val cell = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_ANTI_DETECT_CELL)) {
                bundle.getBoolean(IpcProtocol.KEY_ANTI_DETECT_CELL, baseFromJson.antiDetectCellEnabled)
            } else {
                baseFromJson.antiDetectCellEnabled
            }
        } ?: baseFromJson.antiDetectCellEnabled

        val wifi = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_ANTI_DETECT_WIFI)) {
                bundle.getBoolean(IpcProtocol.KEY_ANTI_DETECT_WIFI, baseFromJson.antiDetectWifiEnabled)
            } else {
                baseFromJson.antiDetectWifiEnabled
            }
        } ?: baseFromJson.antiDetectWifiEnabled

        val gnss = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_ANTI_DETECT_GNSS)) {
                bundle.getBoolean(IpcProtocol.KEY_ANTI_DETECT_GNSS, baseFromJson.antiDetectGnssEnabled)
            } else {
                baseFromJson.antiDetectGnssEnabled
            }
        } ?: baseFromJson.antiDetectGnssEnabled

        val packages = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_TARGET_PACKAGES)) {
                bundle.getStringArrayList(IpcProtocol.KEY_TARGET_PACKAGES)?.toSet()
                    ?: baseFromJson.targetPackages
            } else {
                baseFromJson.targetPackages
            }
        } ?: baseFromJson.targetPackages

        val updatedAtMs = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_UPDATED_AT_MS)) {
                bundle.getLong(IpcProtocol.KEY_UPDATED_AT_MS, baseFromJson.updatedAtMs)
            } else {
                baseFromJson.updatedAtMs
            }
        } ?: baseFromJson.updatedAtMs

        val version = safeCall {
            if (bundle.containsKey(IpcProtocol.KEY_VERSION)) {
                bundle.getLong(IpcProtocol.KEY_VERSION, baseFromJson.version)
            } else {
                baseFromJson.version
            }
        } ?: baseFromJson.version

        return MockConfig(
            enabled = enabled,
            mode = mode,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            coordinateType = coordType,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing,
            jitterEnabled = jitterEnabled,
            jitterRadiusMeters = jitterRadius,
            antiDetectCellEnabled = cell,
            antiDetectWifiEnabled = wifi,
            antiDetectGnssEnabled = gnss,
            targetPackages = packages,
            updatedAtMs = updatedAtMs,
            version = version
        ).sanitized(safeFallback)
    }

    fun toJson(config: MockConfig): String {
        val safe = config.sanitized()
        val obj = JSONObject().apply {
            put(IpcProtocol.KEY_IS_MOCK_ENABLED, safe.enabled)
            put(IpcProtocol.KEY_MOCK_MODE, safe.mode.name)
            put(IpcProtocol.KEY_LATITUDE, safe.latitude)
            put(IpcProtocol.KEY_LONGITUDE, safe.longitude)
            put(IpcProtocol.KEY_ALTITUDE, safe.altitude)
            put(IpcProtocol.KEY_COORDINATE_TYPE, safe.coordinateType.name)
            put(IpcProtocol.KEY_ACCURACY, safe.accuracy.toDouble())
            put(IpcProtocol.KEY_SPEED, safe.speed.toDouble())
            put(IpcProtocol.KEY_BEARING, safe.bearing.toDouble())
            put(IpcProtocol.KEY_JITTER_ENABLED, safe.jitterEnabled)
            put(IpcProtocol.KEY_JITTER_RADIUS_METERS, safe.jitterRadiusMeters)
            put(IpcProtocol.KEY_ANTI_DETECT_CELL, safe.antiDetectCellEnabled)
            put(IpcProtocol.KEY_ANTI_DETECT_WIFI, safe.antiDetectWifiEnabled)
            put(IpcProtocol.KEY_ANTI_DETECT_GNSS, safe.antiDetectGnssEnabled)
            put(IpcProtocol.KEY_TARGET_PACKAGES, JSONArray(safe.targetPackages.toList()))
            put(IpcProtocol.KEY_UPDATED_AT_MS, safe.updatedAtMs)
            put(IpcProtocol.KEY_VERSION, safe.version)
        }
        return obj.toString()
    }

    fun fromJson(json: String?, fallback: MockConfig = MockConfig.DEFAULT): MockConfig {
        val safeFallback = fallback.sanitized()
        if (json.isNullOrBlank()) return safeFallback
        val obj = safeCall { JSONObject(json) } ?: return safeFallback

        val enabled = safeCall {
            if (obj.has(IpcProtocol.KEY_IS_MOCK_ENABLED) && !obj.isNull(IpcProtocol.KEY_IS_MOCK_ENABLED)) {
                obj.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED)
            } else {
                safeFallback.enabled
            }
        } ?: safeFallback.enabled

        val mode = safeCall {
            if (obj.has(IpcProtocol.KEY_MOCK_MODE) && !obj.isNull(IpcProtocol.KEY_MOCK_MODE)) {
                MockMode.fromString(obj.optString(IpcProtocol.KEY_MOCK_MODE))
            } else {
                safeFallback.mode
            }
        } ?: safeFallback.mode

        val lat = readJsonDouble(obj, IpcProtocol.KEY_LATITUDE, safeFallback.latitude)
        val lon = readJsonDouble(obj, IpcProtocol.KEY_LONGITUDE, safeFallback.longitude)
        val alt = readJsonDouble(obj, IpcProtocol.KEY_ALTITUDE, safeFallback.altitude)

        val coordType = safeCall {
            if (obj.has(IpcProtocol.KEY_COORDINATE_TYPE) && !obj.isNull(IpcProtocol.KEY_COORDINATE_TYPE)) {
                CoordinateType.fromString(obj.optString(IpcProtocol.KEY_COORDINATE_TYPE))
            } else {
                safeFallback.coordinateType
            }
        } ?: safeFallback.coordinateType

        val accuracy = readJsonDouble(obj, IpcProtocol.KEY_ACCURACY, safeFallback.accuracy.toDouble()).toFloat()
        val speed = readJsonDouble(obj, IpcProtocol.KEY_SPEED, safeFallback.speed.toDouble()).toFloat()
        val bearing = readJsonDouble(obj, IpcProtocol.KEY_BEARING, safeFallback.bearing.toDouble()).toFloat()

        val jitterEnabled = safeCall {
            if (obj.has(IpcProtocol.KEY_JITTER_ENABLED) && !obj.isNull(IpcProtocol.KEY_JITTER_ENABLED)) {
                obj.getBoolean(IpcProtocol.KEY_JITTER_ENABLED)
            } else {
                safeFallback.jitterEnabled
            }
        } ?: safeFallback.jitterEnabled

        val jitterRadius = readJsonDouble(
            obj,
            IpcProtocol.KEY_JITTER_RADIUS_METERS,
            safeFallback.jitterRadiusMeters
        )

        val cell = safeCall {
            if (obj.has(IpcProtocol.KEY_ANTI_DETECT_CELL) && !obj.isNull(IpcProtocol.KEY_ANTI_DETECT_CELL)) {
                obj.getBoolean(IpcProtocol.KEY_ANTI_DETECT_CELL)
            } else {
                safeFallback.antiDetectCellEnabled
            }
        } ?: safeFallback.antiDetectCellEnabled

        val wifi = safeCall {
            if (obj.has(IpcProtocol.KEY_ANTI_DETECT_WIFI) && !obj.isNull(IpcProtocol.KEY_ANTI_DETECT_WIFI)) {
                obj.getBoolean(IpcProtocol.KEY_ANTI_DETECT_WIFI)
            } else {
                safeFallback.antiDetectWifiEnabled
            }
        } ?: safeFallback.antiDetectWifiEnabled

        val gnss = safeCall {
            if (obj.has(IpcProtocol.KEY_ANTI_DETECT_GNSS) && !obj.isNull(IpcProtocol.KEY_ANTI_DETECT_GNSS)) {
                obj.getBoolean(IpcProtocol.KEY_ANTI_DETECT_GNSS)
            } else {
                safeFallback.antiDetectGnssEnabled
            }
        } ?: safeFallback.antiDetectGnssEnabled

        val packages = safeCall {
            if (obj.has(IpcProtocol.KEY_TARGET_PACKAGES) && !obj.isNull(IpcProtocol.KEY_TARGET_PACKAGES)) {
                val arr = obj.getJSONArray(IpcProtocol.KEY_TARGET_PACKAGES)
                buildSet {
                    for (i in 0 until arr.length()) {
                        if (arr.isNull(i)) continue
                        val pkg = arr.optString(i, "").trim()
                        if (pkg.isNotEmpty()) add(pkg)
                    }
                }
            } else {
                safeFallback.targetPackages
            }
        } ?: safeFallback.targetPackages

        val updatedAtMs = safeCall {
            if (obj.has(IpcProtocol.KEY_UPDATED_AT_MS) && !obj.isNull(IpcProtocol.KEY_UPDATED_AT_MS)) {
                obj.getLong(IpcProtocol.KEY_UPDATED_AT_MS)
            } else {
                safeFallback.updatedAtMs
            }
        } ?: safeFallback.updatedAtMs

        val version = safeCall {
            if (obj.has(IpcProtocol.KEY_VERSION) && !obj.isNull(IpcProtocol.KEY_VERSION)) {
                obj.getLong(IpcProtocol.KEY_VERSION)
            } else {
                safeFallback.version
            }
        } ?: safeFallback.version

        return MockConfig(
            enabled = enabled,
            mode = mode,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            coordinateType = coordType,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing,
            jitterEnabled = jitterEnabled,
            jitterRadiusMeters = jitterRadius,
            antiDetectCellEnabled = cell,
            antiDetectWifiEnabled = wifi,
            antiDetectGnssEnabled = gnss,
            targetPackages = packages,
            updatedAtMs = updatedAtMs,
            version = version
        ).sanitized(safeFallback)
    }

    fun writeToPrefs(editor: SharedPreferences.Editor, config: MockConfig): SharedPreferences.Editor {
        val safe = config.sanitized()
        editor.putBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, safe.enabled)
        editor.putString(IpcProtocol.KEY_MOCK_MODE, safe.mode.name)
        // 无损保存双精度坐标：同时写精确 IEEE-754 String 与 rawBits Long，严禁转 Float
        editor.putString(IpcProtocol.KEY_LATITUDE, safe.latitude.toString())
        editor.putLong(IpcProtocol.KEY_LATITUDE_BITS, java.lang.Double.doubleToRawLongBits(safe.latitude))
        editor.putString(IpcProtocol.KEY_LONGITUDE, safe.longitude.toString())
        editor.putLong(IpcProtocol.KEY_LONGITUDE_BITS, java.lang.Double.doubleToRawLongBits(safe.longitude))
        editor.putString(IpcProtocol.KEY_ALTITUDE, safe.altitude.toString())
        editor.putLong(IpcProtocol.KEY_ALTITUDE_BITS, java.lang.Double.doubleToRawLongBits(safe.altitude))
        editor.putString(IpcProtocol.KEY_COORDINATE_TYPE, safe.coordinateType.name)
        editor.putFloat(IpcProtocol.KEY_ACCURACY, safe.accuracy)
        editor.putFloat(IpcProtocol.KEY_SPEED, safe.speed)
        editor.putFloat(IpcProtocol.KEY_BEARING, safe.bearing)
        editor.putBoolean(IpcProtocol.KEY_JITTER_ENABLED, safe.jitterEnabled)
        editor.putString(IpcProtocol.KEY_JITTER_RADIUS_METERS, safe.jitterRadiusMeters.toString())
        editor.putLong(
            IpcProtocol.KEY_JITTER_RADIUS_BITS,
            java.lang.Double.doubleToRawLongBits(safe.jitterRadiusMeters)
        )
        editor.putBoolean(IpcProtocol.KEY_ANTI_DETECT_CELL, safe.antiDetectCellEnabled)
        editor.putBoolean(IpcProtocol.KEY_ANTI_DETECT_WIFI, safe.antiDetectWifiEnabled)
        editor.putBoolean(IpcProtocol.KEY_ANTI_DETECT_GNSS, safe.antiDetectGnssEnabled)
        // 每次传入全新 HashSet，规避 Android SharedPreferences getStringSet/putStringSet 同引用变更丢失 bug
        editor.putStringSet(IpcProtocol.KEY_TARGET_PACKAGES, HashSet(safe.targetPackages))
        editor.putLong(IpcProtocol.KEY_UPDATED_AT_MS, safe.updatedAtMs)
        editor.putLong(IpcProtocol.KEY_VERSION, safe.version)
        editor.putString(IpcProtocol.KEY_CONFIG_JSON, toJson(safe))
        return editor
    }

    fun fromPrefs(prefs: SharedPreferences?, fallback: MockConfig = MockConfig.DEFAULT): MockConfig {
        val safeFallback = fallback.sanitized()
        if (prefs == null) return safeFallback

        val base = safeCall {
            val rawJson = prefs.getString(IpcProtocol.KEY_CONFIG_JSON, null)
            if (!rawJson.isNullOrBlank()) fromJson(rawJson, safeFallback) else safeFallback
        } ?: safeFallback

        val enabled = safeCall {
            if (prefs.contains(IpcProtocol.KEY_IS_MOCK_ENABLED)) {
                prefs.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, base.enabled)
            } else {
                base.enabled
            }
        } ?: base.enabled

        val mode = safeCall {
            if (prefs.contains(IpcProtocol.KEY_MOCK_MODE)) {
                MockMode.fromString(prefs.getString(IpcProtocol.KEY_MOCK_MODE, base.mode.name))
            } else {
                base.mode
            }
        } ?: base.mode

        val lat = readPrefsDouble(
            prefs,
            IpcProtocol.KEY_LATITUDE,
            IpcProtocol.KEY_LATITUDE_BITS,
            base.latitude
        )
        val lon = readPrefsDouble(
            prefs,
            IpcProtocol.KEY_LONGITUDE,
            IpcProtocol.KEY_LONGITUDE_BITS,
            base.longitude
        )
        val alt = readPrefsDouble(
            prefs,
            IpcProtocol.KEY_ALTITUDE,
            IpcProtocol.KEY_ALTITUDE_BITS,
            base.altitude
        )

        val coordType = safeCall {
            if (prefs.contains(IpcProtocol.KEY_COORDINATE_TYPE)) {
                CoordinateType.fromString(
                    prefs.getString(IpcProtocol.KEY_COORDINATE_TYPE, base.coordinateType.name)
                )
            } else {
                base.coordinateType
            }
        } ?: base.coordinateType

        val accuracy = safeCall {
            if (prefs.contains(IpcProtocol.KEY_ACCURACY)) {
                prefs.getFloat(IpcProtocol.KEY_ACCURACY, base.accuracy)
            } else {
                base.accuracy
            }
        } ?: base.accuracy

        val speed = safeCall {
            if (prefs.contains(IpcProtocol.KEY_SPEED)) {
                prefs.getFloat(IpcProtocol.KEY_SPEED, base.speed)
            } else {
                base.speed
            }
        } ?: base.speed

        val bearing = safeCall {
            if (prefs.contains(IpcProtocol.KEY_BEARING)) {
                prefs.getFloat(IpcProtocol.KEY_BEARING, base.bearing)
            } else {
                base.bearing
            }
        } ?: base.bearing

        val jitterEnabled = safeCall {
            if (prefs.contains(IpcProtocol.KEY_JITTER_ENABLED)) {
                prefs.getBoolean(IpcProtocol.KEY_JITTER_ENABLED, base.jitterEnabled)
            } else {
                base.jitterEnabled
            }
        } ?: base.jitterEnabled

        val jitterRadius = readPrefsDouble(
            prefs,
            IpcProtocol.KEY_JITTER_RADIUS_METERS,
            IpcProtocol.KEY_JITTER_RADIUS_BITS,
            base.jitterRadiusMeters
        )

        val cell = safeCall {
            if (prefs.contains(IpcProtocol.KEY_ANTI_DETECT_CELL)) {
                prefs.getBoolean(IpcProtocol.KEY_ANTI_DETECT_CELL, base.antiDetectCellEnabled)
            } else {
                base.antiDetectCellEnabled
            }
        } ?: base.antiDetectCellEnabled

        val wifi = safeCall {
            if (prefs.contains(IpcProtocol.KEY_ANTI_DETECT_WIFI)) {
                prefs.getBoolean(IpcProtocol.KEY_ANTI_DETECT_WIFI, base.antiDetectWifiEnabled)
            } else {
                base.antiDetectWifiEnabled
            }
        } ?: base.antiDetectWifiEnabled

        val gnss = safeCall {
            if (prefs.contains(IpcProtocol.KEY_ANTI_DETECT_GNSS)) {
                prefs.getBoolean(IpcProtocol.KEY_ANTI_DETECT_GNSS, base.antiDetectGnssEnabled)
            } else {
                base.antiDetectGnssEnabled
            }
        } ?: base.antiDetectGnssEnabled

        val packages = safeCall {
            if (prefs.contains(IpcProtocol.KEY_TARGET_PACKAGES)) {
                prefs.getStringSet(IpcProtocol.KEY_TARGET_PACKAGES, null)?.let { HashSet(it) }
                    ?: base.targetPackages
            } else {
                base.targetPackages
            }
        } ?: base.targetPackages

        val updatedAtMs = safeCall {
            if (prefs.contains(IpcProtocol.KEY_UPDATED_AT_MS)) {
                prefs.getLong(IpcProtocol.KEY_UPDATED_AT_MS, base.updatedAtMs)
            } else {
                base.updatedAtMs
            }
        } ?: base.updatedAtMs

        val version = safeCall {
            if (prefs.contains(IpcProtocol.KEY_VERSION)) {
                prefs.getLong(IpcProtocol.KEY_VERSION, base.version)
            } else {
                base.version
            }
        } ?: base.version

        return MockConfig(
            enabled = enabled,
            mode = mode,
            latitude = lat,
            longitude = lon,
            altitude = alt,
            coordinateType = coordType,
            accuracy = accuracy,
            speed = speed,
            bearing = bearing,
            jitterEnabled = jitterEnabled,
            jitterRadiusMeters = jitterRadius,
            antiDetectCellEnabled = cell,
            antiDetectWifiEnabled = wifi,
            antiDetectGnssEnabled = gnss,
            targetPackages = packages,
            updatedAtMs = updatedAtMs,
            version = version
        ).sanitized(safeFallback)
    }

    private fun readJsonDouble(obj: JSONObject, key: String, fallback: Double): Double {
        return safeCall {
            if (!obj.has(key) || obj.isNull(key)) return@safeCall fallback
            val raw = obj.get(key)
            when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: fallback
                else -> fallback
            }
        } ?: fallback
    }

    private fun readPrefsDouble(
        prefs: SharedPreferences,
        key: String,
        bitsKey: String,
        fallback: Double
    ): Double {
        if (prefs.contains(bitsKey)) {
            val fromBits = safeCall {
                val bits = prefs.getLong(bitsKey, java.lang.Double.doubleToRawLongBits(fallback))
                java.lang.Double.longBitsToDouble(bits)
            }
            if (fromBits != null && fromBits.isFinite()) return fromBits
        }
        if (prefs.contains(key)) {
            val fromString = safeCall {
                prefs.getString(key, null)?.toDoubleOrNull()
            }
            if (fromString != null) return fromString

            val fromLongBits = safeCall {
                val allMap = prefs.all
                val raw = allMap[key]
                when (raw) {
                    is Double -> raw
                    is Float -> raw.toDouble()
                    is Long -> java.lang.Double.longBitsToDouble(raw)
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                }
            }
            if (fromLongBits != null) return fromLongBits
        }
        return fallback
    }

    private inline fun <T> safeCall(block: () -> T): T? {
        return try {
            block()
        } catch (_: Throwable) {
            null
        }
    }
}
