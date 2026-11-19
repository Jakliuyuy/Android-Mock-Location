package com.mockloc.ipc

import android.content.Context
import android.content.pm.ProviderInfo
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.testing.InMemorySharedPreferences
import com.mockloc.testing.createStatefulMockBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class LocationConfigProviderTest {

    @Test
    fun `provider loads persisted config on cold start and separates global vs package enabled`() {
        val prefs = InMemorySharedPreferences()
        val saved = MockConfig(
            enabled = true,
            latitude = 22.543099,
            longitude = 114.057868,
            targetPackages = setOf("com.tencent.mm")
        )
        ConfigSyncSerializer.writeToPrefs(prefs.edit(), saved).commit()

        val repo = MockConfigRepository(prefs = prefs)
        val ctx = mock<Context>()
        var callerUid = 10001
        val provider = LocationConfigProvider(
            repositoryResolver = { repo },
            callingUidProvider = { callerUid },
            myUidProvider = { 10001 },
            bundleFactory = { createStatefulMockBundle() }
        )
        provider.attachInfo(ctx, ProviderInfo().apply { authority = IpcProtocol.PROVIDER_AUTHORITY })
        assertTrue(provider.onCreate())

        // 全局查询
        val globalBundle = provider.call(IpcProtocol.METHOD_GET_CONFIG, null, null)
        assertTrue(globalBundle.getBoolean(IpcProtocol.RESULT_OK, false))
        assertTrue(globalBundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, false))
        assertTrue(globalBundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, false))
        assertEquals(22.543099, globalBundle.getDouble(IpcProtocol.KEY_LATITUDE, 0.0), 1e-9)

        // 白名单命中包查询
        val hitBundle = provider.call(IpcProtocol.METHOD_GET_CONFIG, "com.tencent.mm", null)
        assertTrue(hitBundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, false))
        assertTrue(hitBundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, false))

        // 非白名单包查询：仅该包关闭，不污染全局标志
        val missBundle = provider.call(IpcProtocol.METHOD_GET_CONFIG, "com.other.app", null)
        assertFalse(missBundle.getBoolean(IpcProtocol.KEY_IS_MOCK_ENABLED, true))
        assertTrue(missBundle.getBoolean(IpcProtocol.KEY_GLOBAL_MOCK_ENABLED, false))
    }

    @Test
    fun `METHOD_SET_CONFIG rejects unauthorized UID writes and tolerates null extras`() {
        val prefs = InMemorySharedPreferences()
        val repo = MockConfigRepository(prefs = prefs)
        repo.updateImmediate { it.copy(enabled = false, latitude = 39.9087, longitude = 116.3975) }

        val ctx = mock<Context>()
        var callerUid = 99999 // 外部非同 UID 进程
        val provider = LocationConfigProvider(
            repositoryResolver = { repo },
            callingUidProvider = { callerUid },
            myUidProvider = { 10001 },
            bundleFactory = { createStatefulMockBundle() }
        )
        provider.attachInfo(ctx, ProviderInfo().apply { authority = IpcProtocol.PROVIDER_AUTHORITY })

        val maliciousExtras = createStatefulMockBundle()
        ConfigSyncSerializer.toBundle(
            MockConfig(enabled = true, latitude = 1.0, longitude = 2.0),
            maliciousExtras
        )
        val deniedResult = provider.call(IpcProtocol.METHOD_SET_CONFIG, null, maliciousExtras)
        assertFalse(deniedResult.getBoolean(IpcProtocol.RESULT_OK, true))
        assertTrue(deniedResult.getString(IpcProtocol.RESULT_ERROR, "").contains("PERMISSION_DENIED_UID"))
        // 原配置未被篡改
        assertFalse(repo.currentConfig.enabled)
        assertEquals(39.9087, repo.currentConfig.latitude, 1e-9)

        // 切换为同 UID 写入，允许并持久化
        callerUid = 10001
        val nullExtrasResult = provider.call(IpcProtocol.METHOD_SET_CONFIG, null, null)
        assertFalse(nullExtrasResult.getBoolean(IpcProtocol.RESULT_OK, true))

        val okResult = provider.call(IpcProtocol.METHOD_SET_CONFIG, null, maliciousExtras)
        assertTrue(okResult.getBoolean(IpcProtocol.RESULT_OK, false))
        assertTrue(repo.currentConfig.enabled)
        assertEquals(1.0, repo.currentConfig.latitude, 1e-9)
        assertEquals(1.0, ConfigSyncSerializer.fromPrefs(prefs).latitude, 1e-9)
    }
}
