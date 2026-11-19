package com.mockloc.domain.geocoding

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

class DomesticGeocodingService(
    private val httpClient: GeocodingHttpClient = HttpURLConnectionGeocodingClient(),
    private val fallbackService: GeocodingService = OfflineFallbackGeocodingService(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GeocodingService {

    override suspend fun search(query: String): Result<List<GeocodingResult>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return Result.success(emptyList())
        }

        when (val coordOutcome = CoordinateQueryParser.parseWithStatus(trimmed)) {
            is CoordinateParseOutcome.Valid -> {
                return Result.success(listOf(coordOutcome.result))
            }
            CoordinateParseOutcome.OutOfRangeOrInvalid -> {
                return Result.success(emptyList())
            }
            CoordinateParseOutcome.NotACoordinate -> Unit
        }

        val onlineResults = try {
            withContext(ioDispatcher) {
                val url = buildSearchUrl(trimmed)
                val response = runInterruptible { httpClient.get(url) }
                if (response.statusCode == 200) {
                    AmapResponseParser.parseSearchResults(response.body)
                } else {
                    emptyList()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            emptyList()
        }

        if (onlineResults.isNotEmpty()) {
            return Result.success(onlineResults)
        }

        currentCoroutineContext().ensureActive()

        return try {
            fallbackService.search(trimmed)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            Result.success(emptyList())
        }
    }

    override suspend fun reverseGeocode(lat: Double, lon: Double): Result<GeocodingResult?> {
        if (!GeoPoint.isValid(lat, lon)) {
            return Result.success(null)
        }

        val onlineResult = try {
            withContext(ioDispatcher) {
                val gcj = CoordinateConverter.wgs84ToGcj02(lat, lon)
                val url = buildReverseGeocodeUrl(gcj.latitude, gcj.longitude)
                val response = runInterruptible { httpClient.get(url) }
                if (response.statusCode == 200) {
                    AmapResponseParser.parseReverseGeocode(response.body, lat, lon)
                } else {
                    null
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            null
        }

        if (onlineResult != null) {
            return Result.success(onlineResult)
        }

        currentCoroutineContext().ensureActive()

        return try {
            fallbackService.reverseGeocode(lat, lon)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            Result.success(null)
        }
    }

    companion object {
        private const val AMAP_WEB_SEARCH_BASE = "https://ditu.amap.com/service/poiTipslite"
        private const val AMAP_WEB_REGEO_BASE = "https://ditu.amap.com/service/regeo"

        internal fun buildSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$AMAP_WEB_SEARCH_BASE?words=$encoded&city=&geo=true"
        }

        internal fun buildReverseGeocodeUrl(gcjLat: Double, gcjLon: Double): String {
            return String.format(
                Locale.US,
                "%s?longitude=%.6f&latitude=%.6f",
                AMAP_WEB_REGEO_BASE,
                gcjLon,
                gcjLat
            )
        }
    }
}
