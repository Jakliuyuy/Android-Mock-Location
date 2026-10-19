package com.mockloc.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏地点本地持久化仓库
 */
class FavoriteRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "mockloc_favorites_prefs"
        private const val KEY_FAVORITES_JSON = "key_favorites_json"

        @Volatile
        private var instance: FavoriteRepository? = null

        fun getInstance(context: Context): FavoriteRepository {
            return instance ?: synchronized(this) {
                instance ?: FavoriteRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getAll(): List<FavoriteLocation> {
        val jsonStr = prefs.getString(KEY_FAVORITES_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            // 首次为空时预置示范地点 (六盘水凉都公园、拉萨布达拉宫、北京天安门广场)
            val defaults = listOf(
                FavoriteLocation(
                    name = "六盘水凉都大剧院/人民广场",
                    address = "贵州省六盘水市钟山区",
                    latitude = 26.586111,
                    longitude = 104.830556
                ),
                FavoriteLocation(
                    name = "西藏拉萨布达拉宫",
                    address = "西藏自治区拉萨市城关区北京中路35号",
                    latitude = 29.655556,
                    longitude = 91.117222
                ),
                FavoriteLocation(
                    name = "北京天安门广场",
                    address = "北京市东城区长安街",
                    latitude = 39.908700,
                    longitude = 116.397500
                )
            )
            saveAll(defaults)
            return defaults
        }

        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<FavoriteLocation>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(FavoriteLocation.fromJson(obj))
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun add(favorite: FavoriteLocation) {
        val current = getAll().toMutableList()
        // 移除同 id 或完全同名的旧项
        current.removeAll { it.id == favorite.id || (it.name == favorite.name && it.latitude == favorite.latitude && it.longitude == favorite.longitude) }
        current.add(0, favorite) // 插入到最前面
        saveAll(current)
    }

    @Synchronized
    fun delete(id: String) {
        val current = getAll().toMutableList()
        current.removeAll { it.id == id }
        saveAll(current)
    }

    private fun saveAll(list: List<FavoriteLocation>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item.toJson())
        }
        prefs.edit().putString(KEY_FAVORITES_JSON, array.toString()).apply()
    }
}
