package com.mockloc.domain.geocoding

import com.mockloc.domain.model.GeocodingResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface GeocodingService {
    suspend fun search(query: String): Result<List<GeocodingResult>>

    suspend fun reverseGeocode(lat: Double, lon: Double): Result<GeocodingResult?>
}

data class GeocodingHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface GeocodingHttpClient {
    @Throws(IOException::class)
    fun get(url: String): GeocodingHttpResponse
}

class HttpURLConnectionGeocodingClient(
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val referer: String = DEFAULT_REFERER
) : GeocodingHttpClient {

    @Throws(IOException::class)
    override fun get(url: String): GeocodingHttpResponse {
        val connection = (URL(url).openConnection() as? HttpURLConnection)
            ?: throw IOException("Unsupported URL connection for $url")

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            val isAmap = url.contains("amap.com", ignoreCase = true)
            val isBaidu = url.contains("map.baidu.com", ignoreCase = true)
            connection.setRequestProperty(
                "User-Agent",
                if (isAmap || isBaidu) userAgent else OPEN_API_USER_AGENT
            )
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Charset", "UTF-8")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.5")
            if (isAmap) {
                connection.setRequestProperty("Referer", referer)
            } else if (isBaidu) {
                connection.setRequestProperty("Referer", BAIDU_REFERER)
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use { readBoundedUtf8(it, maxResponseBytes) } ?: ""
            return GeocodingHttpResponse(statusCode = statusCode, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedUtf8(stream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val remaining = maxBytes - total
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_500
        const val DEFAULT_MAX_RESPONSE_BYTES = 512 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        const val OPEN_API_USER_AGENT = "MockLocationApp/2.0 (Android; +https://github.com/Jakliuyuy/Android-Mock-Location)"
        const val DEFAULT_REFERER = "https://ditu.amap.com/"
        const val BAIDU_REFERER = "https://map.baidu.com/"
    }
}
