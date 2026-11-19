package com.mockloc.data.remote

import android.content.SharedPreferences
import com.mockloc.domain.geocoding.GeocodingHttpClient
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

class RemoteControlRepositoryTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val boolStore = mutableMapOf<String, Boolean>()
    private val intStore = mutableMapOf<String, Int>()
    private val strStore = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        boolStore.clear()
        intStore.clear()
        strStore.clear()
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
    }

    @Test
    fun `remote kill switch blocks app and persists offline lock even after network loss`() = runTest {
        var failNetwork = false
        val client = GeocodingHttpClient { url ->
            if (failNetwork) throw java.io.IOException("offline")
            GeocodingHttpResponse(
                200,
                """
                {
                  "appEnabled": false,
                  "minVersionCode": 1,
                  "disableReason": "已停用测试版本",
                  "announcement": {
                    "enabled": true,
                    "id": "n1",
                    "title": "通知",
                    "message": "内容"
                  }
                }
                """.trimIndent()
            )
        }
        val repo = RemoteControlRepository(
            prefs = mockPrefs,
            httpClient = client,
            ioDispatcher = Dispatchers.Unconfined,
            currentVersionCodeProvider = { 12 }
        )

        val onlineRes = repo.fetchAndEvaluate()
        assertTrue(onlineRes.isBlocked)
        assertEquals("已停用测试版本", onlineRes.blockReason)
        assertNull(onlineRes.announcementToAutoShow)

        // 断网重开后应依然读取本地锁死缓存，防止断网绕过
        failNetwork = true
        val offlineRes = repo.fetchAndEvaluate()
        assertTrue(offlineRes.isBlocked)
        assertFalse(offlineRes.fromNetwork)
        assertEquals("已停用测试版本", offlineRes.blockReason)
    }

    @Test
    fun `minVersionCode enforces upgrade block and announcement dismisses by id unless forceShow`() = runTest {
        val repo = RemoteControlRepository(
            prefs = mockPrefs,
            httpClient = GeocodingHttpClient {
                GeocodingHttpResponse(
                    200,
                    """
                    {
                      "appEnabled": true,
                      "minVersionCode": 10,
                      "disableReason": "请升级到最新版本",
                      "announcement": {
                        "enabled": true,
                        "id": "ann_001",
                        "title": "新版本公告",
                        "message": "已上线多源搜索",
                        "forceShow": false,
                        "actionUrl": "https://example.com"
                      }
                    }
                    """.trimIndent()
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
            currentVersionCodeProvider = { 12 }
        )

        val first = repo.fetchAndEvaluate()
        assertFalse(first.isBlocked)
        assertNotNull(first.announcementToAutoShow)
        assertEquals("ann_001", first.announcementToAutoShow!!.id)

        repo.markAnnouncementDismissed("ann_001")
        val second = repo.fetchAndEvaluate()
        assertNull(second.announcementToAutoShow)
        assertNotNull(second.latestAnnouncement)

        // 低版本直接拦截
        val oldVerRepo = RemoteControlRepository(
            prefs = mockPrefs,
            httpClient = GeocodingHttpClient { GeocodingHttpResponse(500, "err") },
            ioDispatcher = Dispatchers.Unconfined,
            currentVersionCodeProvider = { 9 }
        )
        assertTrue(oldVerRepo.getCachedState().isBlocked)
    }
}
