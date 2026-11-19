package com.mockloc.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.mockloc.domain.geocoding.GeocodingHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.io.IOException

class RemoteControlRepositoryTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val boolStore = mutableMapOf<String, Boolean>()
    private val intStore = mutableMapOf<String, Int>()
    private val strStore = mutableMapOf<String, String>()

    private class FakeSecurityBridge(
        var endpoint: String = "http://129.151.9.55/mlapi/v1/heartbeat",
        var signatureValid: Boolean = true
    ) : SecurityBridgeGateway {
        var activeToken: Long = 0L
        var lastPayload: String = ""
        var revokeCalledCount: Int = 0

        override fun getEndpointUrl(context: Context): String = endpoint

        override fun verifyAndGrantLease(
            context: Context,
            payload: String,
            sigHex: String,
            leaseSeconds: Long,
            effectiveEnabled: Boolean,
            minVersionCode: Int,
            currentVersionCode: Int
        ): Long {
            lastPayload = payload
            if (!signatureValid || !effectiveEnabled || currentVersionCode < minVersionCode || leaseSeconds <= 0L) {
                activeToken = 0L
                return 0L
            }
            activeToken = 0x123456789ABCDEF0L
            return activeToken
        }

        override fun checkLeaseToken(context: Context): Long = activeToken

        override fun revokeLease() {
            activeToken = 0L
            revokeCalledCount++
        }
    }

    @Before
    fun setUp() {
        boolStore.clear()
        intStore.clear()
        strStore.clear()

        mockContext = Mockito.mock(Context::class.java)
        whenever(mockContext.applicationContext).thenReturn(mockContext)
        whenever(mockContext.packageName).thenReturn("com.mockloc")

        mockPrefs = Mockito.mock(SharedPreferences::class.java)
        mockEditor = Mockito.mock(SharedPreferences.Editor::class.java)

        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            boolStore[inv.getArgument<String>(0)] ?: inv.getArgument<Boolean>(1)
        }
        whenever(mockPrefs.getInt(anyString(), anyInt())).thenAnswer { inv ->
            intStore[inv.getArgument<String>(0)] ?: inv.getArgument<Int>(1)
        }
        whenever(mockPrefs.getString(anyString(), anyOrNull())).thenAnswer { inv ->
            strStore[inv.getArgument<String>(0)] ?: inv.getArgument<String?>(1)
        }

        whenever(mockEditor.putBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            boolStore[inv.getArgument(0)] = inv.getArgument(1)
            mockEditor
        }
        whenever(mockEditor.putInt(anyString(), anyInt())).thenAnswer { inv ->
            intStore[inv.getArgument(0)] = inv.getArgument(1)
            mockEditor
        }
        whenever(mockEditor.putString(anyString(), anyOrNull())).thenAnswer { inv ->
            val key = inv.getArgument<String>(0)
            val valStr = inv.getArgument<String?>(1)
            if (valStr == null) strStore.remove(key) else strStore[key] = valStr
            mockEditor
        }
        whenever(mockEditor.remove(anyString())).thenAnswer { inv ->
            val key = inv.getArgument<String>(0)
            boolStore.remove(key)
            intStore.remove(key)
            strStore.remove(key)
            mockEditor
        }
    }

    private fun buildRepo(
        client: RemoteJsonPostClient,
        bridge: SecurityBridgeGateway,
        versionCode: Int = 13,
        nonce: String = "fixed-nonce-001"
    ): RemoteControlRepository {
        return RemoteControlRepository(
            context = mockContext,
            currentVersionCode = versionCode,
            currentVersionName = "2.1.0",
            postClient = client,
            securityBridge = bridge,
            ioDispatcher = Dispatchers.Unconfined,
            deviceFingerprintProvider = { "dev-fp-123" },
            appSignatureProvider = { "sig-sha256-abc" },
            nonceGenerator = { nonce },
            prefs = mockPrefs
        )
    }

    @Test
    fun `first launch offline without lease fails closed`() = runTest {
        val bridge = FakeSecurityBridge()
        val client = RemoteJsonPostClient { _, _ -> throw IOException("No network") }
        val repo = buildRepo(client, bridge)

        val result = repo.checkAndRefresh()
        assertTrue(result.isBlocked)
        assertTrue(result.isNetworkRequired)
        assertFalse(repo.hasActiveLease())
    }

    @Test
    fun `valid heartbeat grants lease, allows offline during lease, and expires when lease ends`() = runTest {
        val bridge = FakeSecurityBridge()
        var failNetwork = false
        val client = RemoteJsonPostClient { _, _ ->
            if (failNetwork) throw IOException("offline")
            GeocodingHttpResponse(
                200,
                """
                {
                  "nonce": "fixed-nonce-001",
                  "appEnabled": true,
                  "deviceBlocked": false,
                  "effectiveEnabled": true,
                  "minVersionCode": 13,
                  "serverTime": 1700000000,
                  "leaseSeconds": 1800,
                  "disableReason": "",
                  "announcement": {
                    "enabled": true,
                    "id": "ann-210",
                    "forceShow": false,
                    "title": "新版本上线",
                    "message": "欢迎使用 v2.1.0",
                    "actionUrl": "https://example.com"
                  },
                  "signature": "deadbeef"
                }
                """.trimIndent()
            )
        }
        val repo = buildRepo(client, bridge)

        val onlineRes = repo.checkAndRefresh()
        assertFalse(onlineRes.isBlocked)
        assertTrue(repo.hasActiveLease())
        assertNotNull(onlineRes.announcementToAutoShow)
        assertEquals("ann-210", onlineRes.announcementToAutoShow?.id)

        // 标记已读后不再自动弹出
        repo.markAnnouncementDismissed("ann-210")
        failNetwork = true
        val offlineInLease = repo.checkAndRefresh()
        assertFalse(offlineInLease.isBlocked)
        assertNull(offlineInLease.announcementToAutoShow)
        assertTrue(repo.hasActiveLease())

        // 模拟单调时钟租期过期
        bridge.activeToken = 0L
        val offlineExpired = repo.checkAndRefresh()
        assertTrue(offlineExpired.isBlocked)
        assertTrue(offlineExpired.isNetworkRequired)
        assertFalse(repo.hasActiveLease())
    }

    @Test
    fun `invalid signature or nonce mismatch is strictly blocked`() = runTest {
        val bridge = FakeSecurityBridge(signatureValid = false)
        val client = RemoteJsonPostClient { _, _ ->
            GeocodingHttpResponse(
                200,
                """
                {
                  "nonce": "fixed-nonce-001",
                  "appEnabled": true,
                  "effectiveEnabled": true,
                  "minVersionCode": 1,
                  "serverTime": 1700000000,
                  "leaseSeconds": 1800,
                  "signature": "tampered"
                }
                """.trimIndent()
            )
        }
        val repo = buildRepo(client, bridge)

        val res = repo.checkAndRefresh()
        assertTrue(res.isBlocked)
        assertFalse(res.isNetworkRequired)
        assertFalse(repo.hasActiveLease())

        // Nonce 不匹配视为重放/伪造，同样不可授予租期
        val validBridge = FakeSecurityBridge(signatureValid = true)
        val mismatchClient = RemoteJsonPostClient { _, _ ->
            GeocodingHttpResponse(
                200,
                """
                {
                  "nonce": "wrong-nonce",
                  "appEnabled": true,
                  "effectiveEnabled": true,
                  "minVersionCode": 1,
                  "serverTime": 1700000000,
                  "leaseSeconds": 1800,
                  "signature": "deadbeef"
                }
                """.trimIndent()
            )
        }
        val repo2 = buildRepo(mismatchClient, validBridge)
        val res2 = repo2.checkAndRefresh()
        assertTrue(res2.isBlocked)
        assertFalse(repo2.hasActiveLease())
    }

    @Test
    fun `remote kill switch revokes lease and stays blocked even when offline`() = runTest {
        val bridge = FakeSecurityBridge()
        var failNetwork = false
        val client = RemoteJsonPostClient { _, _ ->
            if (failNetwork) throw IOException("offline")
            GeocodingHttpResponse(
                200,
                """
                {
                  "nonce": "fixed-nonce-001",
                  "appEnabled": false,
                  "deviceBlocked": false,
                  "effectiveEnabled": false,
                  "minVersionCode": 1,
                  "serverTime": 1700000000,
                  "leaseSeconds": 1800,
                  "disableReason": "已紧急停用本版本",
                  "announcement": {
                    "enabled": true,
                    "id": "n1",
                    "title": "通知",
                    "message": "停用说明",
                    "actionUrl": "https://example.com/update"
                  },
                  "signature": "deadbeef"
                }
                """.trimIndent()
            )
        }
        val repo = buildRepo(client, bridge)

        val onlineRes = repo.checkAndRefresh()
        assertTrue(onlineRes.isBlocked)
        assertFalse(onlineRes.isNetworkRequired)
        assertEquals("已紧急停用本版本", onlineRes.blockReason)
        assertFalse(repo.hasActiveLease())

        // 断网重开后应依然读取本地锁死缓存，防止断网绕过
        failNetwork = true
        val offlineRes = repo.checkAndRefresh()
        assertTrue(offlineRes.isBlocked)
        assertFalse(offlineRes.isNetworkRequired)
        assertEquals("已紧急停用本版本", offlineRes.blockReason)
        assertFalse(repo.hasActiveLease())
    }
}
