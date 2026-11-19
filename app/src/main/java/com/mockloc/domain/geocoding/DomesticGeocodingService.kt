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

        val onlineResults = fetchOnlineSearchCascade(trimmed)
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

        val onlineResult = fetchOnlineReverseCascade(lat, lon)
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

    private suspend fun fetchOnlineSearchCascade(query: String): List<GeocodingResult> {
        val providers: List<Pair<String, (String) -> List<GeocodingResult>>> = listOf(
            buildSearchUrl(query) to { body -> AmapResponseParser.parseSearchResults(body) },
            buildBaiduSearchUrl(query) to { body -> MultiProviderGeocodingParser.parseBaiduWebSearch(body) },
            buildArcGisSearchUrl(query) to { body -> MultiProviderGeocodingParser.parseArcGisSearch(body) },
            buildPhotonSearchUrl(query) to { body -> MultiProviderGeocodingParser.parsePhotonSearch(body) },
            buildNominatimSearchUrl(query) to { body -> MultiProviderGeocodingParser.parseNominatimSearch(body) },
            buildOpenMeteoSearchUrl(query) to { body -> MultiProviderGeocodingParser.parseOpenMeteoSearch(body) }
        )

        for ((url, parser) in providers) {
            currentCoroutineContext().ensureActive()
            val results = try {
                withContext(ioDispatcher) {
                    val response = runInterruptible { httpClient.get(url) }
                    if (response.statusCode == 200) {
                        parser(response.body)
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
            if (results.isNotEmpty()) {
                return results
            }
        }
        return emptyList()
    }

    private suspend fun fetchOnlineReverseCascade(wgsLat: Double, wgsLon: Double): GeocodingResult? {
        val gcj = CoordinateConverter.wgs84ToGcj02(wgsLat, wgsLon)
        val providers: List<Pair<String, (String) -> GeocodingResult?>> = listOf(
            buildReverseGeocodeUrl(gcj.latitude, gcj.longitude) to { body ->
                AmapResponseParser.parseReverseGeocode(body, wgsLat, wgsLon)
            },
            buildPhotonReverseUrl(wgsLat, wgsLon) to { body ->
                MultiProviderGeocodingParser.parsePhotonReverse(body, wgsLat, wgsLon)
            },
            buildNominatimReverseUrl(wgsLat, wgsLon) to { body ->
                MultiProviderGeocodingParser.parseNominatimReverse(body, wgsLat, wgsLon)
            }
        )

        for ((url, parser) in providers) {
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext(ioDispatcher) {
                    val response = runInterruptible { httpClient.get(url) }
                    if (response.statusCode == 200) {
                        parser(response.body)
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
            if (result != null) {
                return result
            }
        }
        return null
    }

    companion object {
        private const val AMAP_WEB_SEARCH_BASE = "https://ditu.amap.com/service/poiTipslite"
        private const val AMAP_WEB_REGEO_BASE = "https://ditu.amap.com/service/regeo"
        private const val BAIDU_WEB_SEARCH_BASE = "https://map.baidu.com/"
        private const val ARCGIS_SEARCH_BASE = "https://geocode.arcgis.com/arcgis/rest/services/World/GeocodeServer/findAddressCandidates"
        private const val PHOTON_SEARCH_BASE = "https://photon.komoot.io/api/"
        private const val PHOTON_REGEO_BASE = "https://photon.komoot.io/reverse"
        private const val NOMINATIM_SEARCH_BASE = "https://nominatim.openstreetmap.org/search"
        private const val NOMINATIM_REGEO_BASE = "https://nominatim.openstreetmap.org/reverse"
        private const val OPEN_METEO_SEARCH_BASE = "https://geocoding-api.open-meteo.com/v1/search"

        internal fun buildSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$AMAP_WEB_SEARCH_BASE?words=$encoded&city=&geo=true"
        }

        internal fun buildBaiduSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "${BAIDU_WEB_SEARCH_BASE}?newmap=1&reqflag=pcmap&biz=1&from=webmap&qt=s&wd=$encoded&c=1&pn=0&rn=10&ie=utf-8"
        }

        internal fun buildArcGisSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$ARCGIS_SEARCH_BASE?SingleLine=$encoded&f=json&maxLocations=10&outFields=Match_addr,Place_addr,City,Region"
        }

        internal fun buildPhotonSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$PHOTON_SEARCH_BASE?q=$encoded&limit=10"
        }

        internal fun buildNominatimSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$NOMINATIM_SEARCH_BASE?q=$encoded&format=json&addressdetails=1&limit=10&accept-language=zh-CN"
        }

        internal fun buildOpenMeteoSearchUrl(query: String): String {
            val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
            return "$OPEN_METEO_SEARCH_BASE?name=$encoded&count=10&language=zh&format=json"
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

        internal fun buildPhotonReverseUrl(wgsLat: Double, wgsLon: Double): String {
            return String.format(
                Locale.US,
                "%s?lon=%.6f&lat=%.6f",
                PHOTON_REGEO_BASE,
                wgsLon,
                wgsLat
            )
        }

        internal fun buildNominatimReverseUrl(wgsLat: Double, wgsLon: Double): String {
            return String.format(
                Locale.US,
                "%s?lat=%.6f&lon=%.6f&format=json&accept-language=zh-CN",
                NOMINATIM_REGEO_BASE,
                wgsLat,
                wgsLon
            )
        }
    }
}
