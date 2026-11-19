package com.mockloc.ipc

import android.net.Uri

object IpcProtocol {
    const val PREFS_NAME = "mockloc_xposed_config"
    const val ACTION_CONFIG_CHANGED = "com.mockloc.ACTION_CONFIG_CHANGED"
    const val PROVIDER_AUTHORITY = "com.mockloc.configprovider"
    const val CONTENT_URI_STRING = "content://com.mockloc.configprovider"

    val CONTENT_URI: Uri
        get() = Uri.parse(CONTENT_URI_STRING)

    const val METHOD_GET_CONFIG = "getConfig"
    const val METHOD_SET_CONFIG = "setConfig"
    const val METHOD_PING = "ping"

    const val KEY_IS_MOCK_ENABLED = "key_is_mock_enabled"
    const val KEY_GLOBAL_MOCK_ENABLED = "key_global_mock_enabled"
    const val KEY_MOCK_MODE = "key_mock_mode"
    const val KEY_LATITUDE = "key_latitude"
    const val KEY_LONGITUDE = "key_longitude"
    const val KEY_ALTITUDE = "key_altitude"
    const val KEY_LATITUDE_BITS = "key_latitude_bits"
    const val KEY_LONGITUDE_BITS = "key_longitude_bits"
    const val KEY_ALTITUDE_BITS = "key_altitude_bits"
    const val KEY_COORDINATE_TYPE = "key_coordinate_type"
    const val KEY_ACCURACY = "key_accuracy"
    const val KEY_SPEED = "key_speed"
    const val KEY_BEARING = "key_bearing"
    const val KEY_JITTER_ENABLED = "key_jitter_enabled"
    const val KEY_JITTER_RADIUS_METERS = "key_jitter_radius_meters"
    const val KEY_JITTER_RADIUS_BITS = "key_jitter_radius_bits"
    const val KEY_ANTI_DETECT_CELL = "key_anti_detect_cell"
    const val KEY_ANTI_DETECT_WIFI = "key_anti_detect_wifi"
    const val KEY_ANTI_DETECT_GNSS = "key_anti_detect_gnss"
    const val KEY_TARGET_PACKAGES = "key_target_packages"
    const val KEY_UPDATED_AT_MS = "key_updated_at_ms"
    const val KEY_VERSION = "key_version"
    const val KEY_CONFIG_JSON = "key_config_json"

    const val RESULT_OK = "ok"
    const val RESULT_ERROR = "error"
}
