package com.mockloc.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FavoriteLocationTest {

    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val memoryStore = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        mockContext = mock()
        mockPrefs = mock()
        mockEditor = mock()

        whenever(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        whenever(mockContext.applicationContext).thenReturn(mockContext)

        whenever(mockPrefs.edit()).thenReturn(mockEditor)
        whenever(mockPrefs.getString(anyString(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val defaultVal = invocation.getArgument<String?>(1)
            memoryStore[key] ?: defaultVal
        }

        whenever(mockEditor.putString(anyString(), anyString())).thenAnswer { invocation ->
            val key = invocation.getArgument<String>(0)
            val value = invocation.getArgument<String>(1)
            memoryStore[key] = value
            mockEditor
        }
    }

    @Test
    fun testFavoriteLocationJsonSerialization() {
        val original = FavoriteLocation(
            id = "test-123",
            name = "六盘水凉都公园",
            address = "贵州省六盘水市",
            latitude = 26.586111,
            longitude = 104.830556,
            altitude = 1200.0,
            createdAt = 1700000000000L
        )

        val json = original.toJson()
        assertEquals("test-123", json.getString("id"))
        assertEquals("六盘水凉都公园", json.getString("name"))
        assertEquals(26.586111, json.getDouble("latitude"), 0.000001)
        assertEquals(104.830556, json.getDouble("longitude"), 0.000001)

        val restored = FavoriteLocation.fromJson(json)
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.latitude, restored.latitude, 0.000001)
        assertEquals(original.longitude, restored.longitude, 0.000001)
        assertEquals(original.altitude, restored.altitude, 0.001)
    }

    @Test
    fun testFavoriteRepositoryDefaultsAndAddDelete() {
        val repo = FavoriteRepository(mockContext)

        // 1. 默认应提供预置地点 (六盘水、拉萨等)
        val initialList = repo.getAll()
        assertTrue(initialList.isNotEmpty())
        assertTrue(initialList.any { it.name.contains("六盘水") })
        assertTrue(initialList.any { it.name.contains("拉萨") })

        // 2. 添加新地点
        val newFav = FavoriteLocation(
            id = "custom-id-99",
            name = "深圳南山科技园",
            address = "广东省深圳市",
            latitude = 22.540000,
            longitude = 113.950000
        )
        repo.add(newFav)

        val afterAdd = repo.getAll()
        assertEquals("custom-id-99", afterAdd.first().id)
        assertEquals("深圳南山科技园", afterAdd.first().name)

        // 3. 删除地点
        repo.delete("custom-id-99")
        val afterDelete = repo.getAll()
        assertTrue(afterDelete.none { it.id == "custom-id-99" })
    }
}
