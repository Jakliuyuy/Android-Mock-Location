package com.mockloc.domain.geocoding

import com.mockloc.domain.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap Nominatim 在线地理编码服务客户端。
 * 遵循 Nominatim 使用协议规范，附带定制 User-Agent 与请求超时保护，
 * 遇到网络异常、超时或无效响应时自动降级到 OfflineFallbackGeocodingService。
 *
 * @param fallbackService 降级备用地理编码服务，默认为 OfflineFallbackGeocodingService
 * @param httpRequester 可选的网络请求执行器（主要用于单元测试注入与离线隔离）
 */
class NominatimGeocodingService(
    val fallbackService: GeocodingService = OfflineFallbackGeocodingService(),
    private val httpRequester: ((url: String) -> String)? = null
) : GeocodingService {

    companion object {
        const val BASE_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
        const val BASE_REVERSE_URL = "https://nominatim.openstreetmap.org/reverse"
        const val USER_AGENT = "MockLocApp/1.0"
        const val CONNECT_TIMEOUT_MS = 3000
        const val READ_TIMEOUT_MS = 3000
    }

    override suspend fun search(query: String, limit: Int): List<GeocodingResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext emptyList()
        }

        // 1. 优先检测是否直接输入或粘贴了经纬度数值 (如: "39.9087, 116.3975" 或 "39.9087 116.3975")
        val coordRegex = Regex("""^([+-]?\d+(?:\.\d+)?)[,\s，]+([+-]?\d+(?:\.\d+)?)$""")
        val match = coordRegex.find(trimmed)
        if (match != null) {
            val v1 = match.groupValues[1].toDoubleOrNull()
            val v2 = match.groupValues[2].toDoubleOrNull()
            if (v1 != null && v2 != null) {
                // 判断第一位是纬度还是经度
                val (lat, lng) = if (v1 in -90.0..90.0 && v2 in -180.0..180.0) {
                    Pair(v1, v2)
                } else if (v2 in -90.0..90.0 && v1 in -180.0..180.0) {
                    Pair(v2, v1)
                } else {
                    Pair(null, null)
                }
                if (lat != null && lng != null) {
                    return@withContext listOf(
                        GeocodingResult(
                            name = "直接坐标: ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)}",
                            displayName = "纬度: $lat, 经度: $lng",
                            point = GeoPoint(lat, lng),
                            type = "coordinate"
                        )
                    )
                }
            }
        }

        val effectiveLimit = if (limit <= 0) 10 else limit

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = "$BASE_SEARCH_URL?q=$encodedQuery&format=json&limit=$effectiveLimit&addressdetails=1"

            val jsonString = executeGet(urlString)
            val parsedList = parseSearchResults(jsonString)

            if (parsedList.isNotEmpty()) {
                parsedList
            } else {
                // 如果在线搜索无返回，尝试降级
                fallbackService.search(trimmed, effectiveLimit)
            }
        } catch (_: Throwable) {
            // 网络异常或解析错误，无缝降级到离线库
            fallbackService.search(trimmed, effectiveLimit)
        }
    }

    override suspend fun reverse(point: GeoPoint): GeocodingResult? = withContext(Dispatchers.IO) {
        try {
            val urlString = "$BASE_REVERSE_URL?lat=${point.latitude}&lon=${point.longitude}&format=json&addressdetails=1"
            val jsonString = executeGet(urlString)
            val result = parseReverseResult(jsonString)
            result ?: fallbackService.reverse(point)
        } catch (_: Throwable) {
            fallbackService.reverse(point)
        }
    }

    /**
     * 发起 HTTP GET 请求获取响应字符串
     */
    fun executeGet(urlString: String): String {
        httpRequester?.let { return it(urlString) }

        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } else {
                throw IllegalStateException("HTTP Error $responseCode: ${connection.responseMessage}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析 Nominatim 搜索 JSON 结果列表
     */
    fun parseSearchResults(jsonString: String): List<GeocodingResult> {
        val results = mutableListOf<GeocodingResult>()
        if (jsonString.isBlank()) return results

        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val item = parseSingleItem(obj)
            if (item != null) {
                results.add(item)
            }
        }
        return results
    }

    /**
     * 解析 Nominatim 逆向地理编码 JSON 结果
     */
    fun parseReverseResult(jsonString: String): GeocodingResult? {
        if (jsonString.isBlank()) return null
        val obj = JSONObject(jsonString)
        if (obj.has("error")) {
            return null
        }
        return parseSingleItem(obj)
    }

    private fun parseSingleItem(obj: JSONObject): GeocodingResult? {
        val latStr = obj.optString("lat")
        val lonStr = obj.optString("lon")
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null

        val displayName = obj.optString("display_name", "")
        val rawName = obj.optString("name", "")
        val effectiveName = if (rawName.isNotBlank()) {
            rawName
        } else if (displayName.isNotBlank()) {
            displayName.split(",").firstOrNull()?.trim() ?: displayName
        } else {
            "($lat, $lon)"
        }

        val type = obj.optString("type", obj.optString("class", "poi"))

        return GeocodingResult(
            name = effectiveName,
            displayName = if (displayName.isNotBlank()) displayName else effectiveName,
            point = GeoPoint(lat, lon),
            type = type
        )
    }
}
