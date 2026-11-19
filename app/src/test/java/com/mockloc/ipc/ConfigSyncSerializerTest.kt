package com.mockloc.ipc

import com.mockloc.data.config.MockConfig
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.MockMode
import com.mockloc.testing.InMemorySharedPreferences
import com.mockloc.testing.createStatefulMockBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSyncSerializerTest {

    @Test
    fun `lossless round trip across Bundle JSON and SharedPreferences preserves full double precision`() {
        val original = MockConfig(
            enabled = true,
            mode = MockMode.LSPOSED_ONLY,
            latitude = 39.90872212345678,
            longitude = 116.39749987654321,
            altitude = 128.75,
            coordinateType = CoordinateType.GCJ02,
            accuracy = 2.5f,
            speed = 12.34f,
            bearing = 275.5f,
            jitterEnabled = false,
            jitterRadiusMeters = 4.25,
            antiDetectCellEnabled = false,
            antiDetectWifiEnabled = true,
            antiDetectGnssEnabled = false,
            targetPackages = setOf("com.tencent.mm", "com.autonavi.minimap"),
            updatedAtMs = 1730000000123L,
            version = 42L
        )

        // 1. Bundle 往返
        val bundle = createStatefulMockBundle()
        ConfigSyncSerializer.toBundle(original, bundle)
        val fromBundle = ConfigSyncSerializer.fromBundle(bundle)
        assertEquals(original, fromBundle)

        // 2. JSON 往返
        val json = ConfigSyncSerializer.toJson(original)
        val fromJson = ConfigSyncSerializer.fromJson(json)
        assertEquals(original, fromJson)

        // 3. SharedPreferences 无损往返（14位小数双精度不丢失）
        val prefs = InMemorySharedPreferences()
        val editor = prefs.edit()
        ConfigSyncSerializer.writeToPrefs(editor, original)
        editor.commit()
        val fromPrefs = ConfigSyncSerializer.fromPrefs(prefs)
        assertEquals(original.latitude, fromPrefs.latitude, 0.0)
        assertEquals(original.longitude, fromPrefs.longitude, 0.0)
        assertEquals(original, fromPrefs)
    }

    @Test
    fun `out-of-range NaN Infinity and corrupted inputs are safely clamped without exceptions`() {
        val corrupted = MockConfig(
            enabled = true,
            latitude = Double.NaN,
            longitude = Double.POSITIVE_INFINITY,
            altitude = Double.NEGATIVE_INFINITY,
            accuracy = -10f,
            speed = Float.NaN,
            bearing = 725.0f,
            jitterRadiusMeters = -5.0,
            targetPackages = setOf("", "  ", "com.tencent.mm")
        )
        val sanitized = corrupted.sanitized()
        assertEquals(MockConfig.DEFAULT_LATITUDE, sanitized.latitude, 0.0)
        assertEquals(MockConfig.DEFAULT_LONGITUDE, sanitized.longitude, 0.0)
        assertEquals(MockConfig.DEFAULT_ALTITUDE, sanitized.altitude, 0.0)
        assertEquals(MockConfig.DEFAULT_ACCURACY, sanitized.accuracy, 0f)
        assertEquals(0.0f, sanitized.speed, 0f)
        assertEquals(5.0f, sanitized.bearing, 1e-4f)
        assertEquals(0.0, sanitized.jitterRadiusMeters, 0.0)
        assertEquals(setOf("com.tencent.mm"), sanitized.targetPackages)

        // 极端越界数值经 Bundle 传入应被安全钳制至 [-90, 90] & [-180, 180]
        val extremeBundle = createStatefulMockBundle().apply {
            putBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, true)
            putDouble(IpcProtocol.KEY_LATITUDE, 150.0)
            putDouble(IpcProtocol.KEY_LONGITUDE, -250.0)
            putDouble(IpcProtocol.KEY_ALTITUDE, Double.NaN)
        }
        val parsedBundle = ConfigSyncSerializer.fromBundle(extremeBundle)
        assertEquals(90.0, parsedBundle.latitude, 0.0)
        assertEquals(-180.0, parsedBundle.longitude, 0.0)
        assertEquals(MockConfig.DEFAULT_ALTITUDE, parsedBundle.altitude, 0.0)

        // 损坏 JSON 与 null 输入绝不抛异常
        assertEquals(MockConfig.DEFAULT, ConfigSyncSerializer.fromJson("{broken json!!!"))
        assertEquals(MockConfig.DEFAULT, ConfigSyncSerializer.fromJson(null))
        assertEquals(MockConfig.DEFAULT, ConfigSyncSerializer.fromBundle(null))
        assertEquals(MockConfig.DEFAULT, ConfigSyncSerializer.fromPrefs(null))

        // SharedPreferences 字段类型损坏（例如纬度被篡改为非法字符串）仍安全兜底并保留有效开关
        val brokenPrefs = InMemorySharedPreferences().apply {
            putRaw(IpcProtocol.KEY_IS_MOCK_ENABLED, true)
            putRaw(IpcProtocol.KEY_LATITUDE, "not_a_number")
            putRaw(IpcProtocol.KEY_LONGITUDE, 999.0)
        }
        val parsedPrefs = ConfigSyncSerializer.fromPrefs(brokenPrefs)
        assertTrue(parsedPrefs.enabled)
        assertEquals(MockConfig.DEFAULT_LATITUDE, parsedPrefs.latitude, 0.0)
        assertEquals(180.0, parsedPrefs.longitude, 0.0)

        // JSONArray 含 JSON null 元素时兼容过滤，绝不混入 "null" 字符串
        val jsonWithNullPkg =
            """{"${IpcProtocol.KEY_IS_MOCK_ENABLED}":true,"${IpcProtocol.KEY_TARGET_PACKAGES}":["com.tencent.mm",null,"  ","com.autonavi.minimap"]}"""
        val parsedJsonNull = ConfigSyncSerializer.fromJson(jsonWithNullPkg)
        assertTrue(parsedJsonNull.enabled)
        assertEquals(setOf("com.tencent.mm", "com.autonavi.minimap"), parsedJsonNull.targetPackages)

        // JSON 显式包含 null 枚举字段时保留 fallback，不被 optString("null") 重置为默认值
        val fallbackForNullEnums = MockConfig(
            mode = MockMode.ROOT_ONLY,
            coordinateType = CoordinateType.BD09
        )
        val jsonWithNullEnums =
            """{"${IpcProtocol.KEY_MOCK_MODE}":null,"${IpcProtocol.KEY_COORDINATE_TYPE}":null}"""
        val parsedNullEnums = ConfigSyncSerializer.fromJson(jsonWithNullEnums, fallbackForNullEnums)
        assertEquals(MockMode.ROOT_ONLY, parsedNullEnums.mode)
        assertEquals(CoordinateType.BD09, parsedNullEnums.coordinateType)
    }

    @Test
    fun `isEnabledForPackage enforces strict opt-in whitelist`() {
        val cfg = MockConfig(
            enabled = true,
            targetPackages = setOf("com.tencent.mm")
        )
        assertTrue(cfg.isEnabledForPackage("com.tencent.mm"))
        assertFalse(cfg.isEnabledForPackage("com.other.app"))
        assertFalse(cfg.isEnabledForPackage(""))
        assertFalse(cfg.isEnabledForPackage(null))

        val disabledCfg = cfg.copy(enabled = false)
        assertFalse(disabledCfg.isEnabledForPackage("com.tencent.mm"))
    }
}
