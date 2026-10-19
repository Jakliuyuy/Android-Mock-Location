package com.mockloc.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 模拟定位核心配置本地持久化工具
 */
object MockPrefs {

    private const val PREFS_NAME = "mockloc_core_prefs"
    private const val KEY_IS_MOCK_ENABLED = "key_is_mock_enabled"
    private const val KEY_LATITUDE = "key_latitude"
    private const val KEY_LONGITUDE = "key_longitude"
    private const val KEY_ALTITUDE = "key_altitude"
    private const val KEY_SPEED = "key_speed"
    private const val KEY_BEARING = "key_bearing"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveConfig(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_IS_MOCK_ENABLED, MockConfigManager.isMockEnabled)
            .putString(KEY_LATITUDE, MockConfigManager.latitude.toString())
            .putString(KEY_LONGITUDE, MockConfigManager.longitude.toString())
            .putString(KEY_ALTITUDE, MockConfigManager.altitude.toString())
            .putFloat(KEY_SPEED, MockConfigManager.speed)
            .putFloat(KEY_BEARING, MockConfigManager.bearing)
            .apply()
    }

    fun loadConfig(context: Context) {
        val prefs = getPrefs(context)
        if (prefs.contains(KEY_LATITUDE)) {
            val latStr = prefs.getString(KEY_LATITUDE, "39.9087")
            val lngStr = prefs.getString(KEY_LONGITUDE, "116.3975")
            val altStr = prefs.getString(KEY_ALTITUDE, "50.0")

            MockConfigManager.latitude = latStr?.toDoubleOrNull() ?: 39.9087
            MockConfigManager.longitude = lngStr?.toDoubleOrNull() ?: 116.3975
            MockConfigManager.altitude = altStr?.toDoubleOrNull() ?: 50.0
            MockConfigManager.speed = prefs.getFloat(KEY_SPEED, 0.0f)
            MockConfigManager.bearing = prefs.getFloat(KEY_BEARING, 0.0f)
            MockConfigManager.isMockEnabled = prefs.getBoolean(KEY_IS_MOCK_ENABLED, false)
        }
    }
}
