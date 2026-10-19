package com.mockloc.data

import org.json.JSONObject

/**
 * 收藏地点数据模型
 */
data class FavoriteLocation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val address: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 50.0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("address", address)
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude", altitude)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): FavoriteLocation {
            return FavoriteLocation(
                id = json.optString("id", java.util.UUID.randomUUID().toString()),
                name = json.optString("name", "未命名地点"),
                address = json.optString("address", ""),
                latitude = json.optDouble("latitude", 0.0),
                longitude = json.optDouble("longitude", 0.0),
                altitude = json.optDouble("altitude", 50.0),
                createdAt = json.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}
