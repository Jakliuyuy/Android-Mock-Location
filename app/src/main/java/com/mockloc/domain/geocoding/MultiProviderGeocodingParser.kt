package com.mockloc.domain.geocoding

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * 多源在线地理编码响应解析器（Baidu Web / ArcGIS / Photon / Nominatim / Open-Meteo）。
 * 统一转换为项目内部权威 WGS-84 坐标。
 */
object MultiProviderGeocodingParser {

    private val BAIDU_MC_BAND = doubleArrayOf(12890594.86, 8362377.87, 5591021.0, 3481989.83, 1678043.12, 0.0)
    private val BAIDU_MC_TO_LL = arrayOf(
        doubleArrayOf(1.410526172116255e-08, 8.98305509648872e-06, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2),
        doubleArrayOf(-7.435856389565537e-09, 8.983055097726239e-06, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86),
        doubleArrayOf(-3.030883460898826e-08, 8.98305509983578e-06, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37),
        doubleArrayOf(-1.981981304930552e-08, 8.983055099779535e-06, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06),
        doubleArrayOf(3.09191371068437e-09, 8.983055096812155e-06, 6.995724062e-05, 23.10934304144901, -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4),
        doubleArrayOf(2.890871144776878e-09, 8.983055095805407e-06, -3.068298e-08, 7.47137025468032, -3.53937994e-06, -0.02145144861037, -1.234426596e-05, 0.00010322952773, -3.23890364e-06, 826088.5)
    )

    fun parseBaiduWebSearch(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val contentVal = root.opt("content") ?: return emptyList()

        val currentCityObj = root.optJSONObject("current_city")
        val fallbackCity = currentCityObj?.let { optNonBlank(it, "name") }.orEmpty()

        val results = ArrayList<GeocodingResult>()
        val seen = HashSet<String>()

        when (contentVal) {
            is JSONArray -> {
                for (i in 0 until contentVal.length()) {
                    val item = contentVal.optJSONObject(i) ?: continue
                    val parsed = parseBaiduPoiItem(item, fallbackCity) ?: continue
                    val key = "${parsed.name}|${String.format(Locale.US, "%.4f,%.4f", parsed.latitude, parsed.longitude)}"
                    if (seen.add(key)) {
                        results.add(parsed)
                    }
                }
            }
            is JSONObject -> {
                val parsed = parseBaiduCityItem(contentVal)
                if (parsed != null) {
                    results.add(parsed)
                }
            }
        }
        return results
    }

    private fun parseBaiduPoiItem(item: JSONObject, fallbackCity: String): GeocodingResult? {
        val name = optNonBlank(item, "name", "cname")
        if (name.isEmpty()) return null

        val mcX = optFiniteDouble(item, "diPointX", "x")
        val mcY = optFiniteDouble(item, "diPointY", "y")
        val wgsPoint = if (mcX != null && mcY != null) {
            baiduMercatorToWgs84(mcX, mcY)
        } else {
            return null
        } ?: return null

        val city = optNonBlank(item, "city_name", "cname").ifEmpty {
            item.optJSONObject("admin_info")?.let { optNonBlank(it, "city_name") }.orEmpty()
        }.ifEmpty { fallbackCity }
        val area = optNonBlank(item, "area_name").ifEmpty {
            item.optJSONObject("admin_info")?.let { optNonBlank(it, "area_name") }.orEmpty()
        }
        val rawAddr = optNonBlank(item, "addr", "address")
        val address = when {
            rawAddr.isNotEmpty() -> rawAddr
            city.isNotEmpty() && area.isNotEmpty() -> "$city$area"
            city.isNotEmpty() -> city
            else -> name
        }

        return GeocodingResult(
            name = name,
            address = address,
            latitude = wgsPoint.latitude,
            longitude = wgsPoint.longitude,
            coordinateType = CoordinateType.WGS84,
            city = city,
            source = "baidu_web"
        )
    }

    private fun parseBaiduCityItem(obj: JSONObject): GeocodingResult? {
        val name = optNonBlank(obj, "cname", "name")
        if (name.isEmpty()) return null
        val mcX = optFiniteDouble(obj, "x") ?: return null
        val mcY = optFiniteDouble(obj, "y") ?: return null
        val wgsPoint = baiduMercatorToWgs84(mcX, mcY) ?: return null

        val apiAdmin = obj.optJSONObject("api_admin_info")
        val admin = obj.optJSONObject("admin_info")
        val prov = apiAdmin?.let { optNonBlank(it, "prov_name") }.orEmpty()
        val city = apiAdmin?.let { optNonBlank(it, "city_name") }.orEmpty()
            .ifEmpty { admin?.let { optNonBlank(it, "city_name") }.orEmpty() }
            .ifEmpty { name }
        val area = admin?.let { optNonBlank(it, "area_name") }.orEmpty()

        val address = listOf(prov, city, area).filter { it.isNotEmpty() }.distinct().joinToString("")
            .ifEmpty { name }

        return GeocodingResult(
            name = name,
            address = address,
            latitude = wgsPoint.latitude,
            longitude = wgsPoint.longitude,
            coordinateType = CoordinateType.WGS84,
            city = city,
            source = "baidu_web"
        )
    }

    internal fun baiduMercatorToWgs84(rawX: Double, rawY: Double): GeoPoint? {
        if (!rawX.isFinite() || !rawY.isFinite()) return null
        val xMeters = abs(rawX) / 100.0
        val yMeters = abs(rawY) / 100.0
        var coeff = BAIDU_MC_TO_LL.last()
        for (i in BAIDU_MC_BAND.indices) {
            if (yMeters >= BAIDU_MC_BAND[i]) {
                coeff = BAIDU_MC_TO_LL[i]
                break
            }
        }
        var bdLon = coeff[0] + coeff[1] * xMeters
        val temp = yMeters / coeff[9]
        var bdLat = coeff[2] +
            coeff[3] * temp +
            coeff[4] * temp * temp +
            coeff[5] * temp * temp * temp +
            coeff[6] * temp * temp * temp * temp +
            coeff[7] * temp * temp * temp * temp * temp +
            coeff[8] * temp * temp * temp * temp * temp * temp

        if (rawX < 0) bdLon = -bdLon
        if (rawY < 0) bdLat = -bdLat
        if (!GeoPoint.isValid(bdLat, bdLon)) return null
        return CoordinateConverter.bd09ToWgs84(bdLat, bdLon)
    }

    fun parseArcGisSearch(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val candidates = root.optJSONArray("candidates") ?: return emptyList()

        val results = ArrayList<GeocodingResult>()
        val seen = HashSet<String>()
        for (i in 0 until candidates.length()) {
            val item = candidates.optJSONObject(i) ?: continue
            val loc = item.optJSONObject("location") ?: continue
            val lon = loc.optDouble("x", Double.NaN)
            val lat = loc.optDouble("y", Double.NaN)
            if (!GeoPoint.isValid(lat, lon)) continue

            val attrs = item.optJSONObject("attributes")
            val matchAddr = attrs?.let { optNonBlank(it, "Match_addr") }.orEmpty()
            val placeAddr = attrs?.let { optNonBlank(it, "Place_addr") }.orEmpty()
            val city = attrs?.let { optNonBlank(it, "City", "Region") }.orEmpty()
            val name = optNonBlank(item, "address").ifEmpty { matchAddr }
            if (name.isEmpty()) continue

            val address = placeAddr.ifEmpty { matchAddr.ifEmpty { name } }
            val key = "$name|${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
            if (seen.add(key)) {
                results.add(
                    GeocodingResult(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        coordinateType = CoordinateType.WGS84,
                        city = city,
                        source = "arcgis_online"
                    )
                )
            }
        }
        return results
    }

    private fun optFiniteDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val num = obj.optDouble(key, Double.NaN)
            if (num.isFinite()) return num
            val str = obj.optString(key).trim().toDoubleOrNull()
            if (str != null && str.isFinite()) return str
        }
        return null
    }

    fun parsePhotonSearch(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val features = root.optJSONArray("features") ?: return emptyList()

        val results = ArrayList<GeocodingResult>()
        val seen = HashSet<String>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue

            val lon = coords.optDouble(0, Double.NaN)
            val lat = coords.optDouble(1, Double.NaN)
            if (!GeoPoint.isValid(lat, lon)) continue

            val props = feature.optJSONObject("properties") ?: continue
            val name = optNonBlank(props, "name", "street", "district", "city", "state")
            if (name.isEmpty()) continue

            val state = optNonBlank(props, "state")
            val city = optNonBlank(props, "city", "county", "state")
            val district = optNonBlank(props, "district")
            val street = optNonBlank(props, "street")
            val country = optNonBlank(props, "country")

            val addressParts = linkedSetOf<String>().apply {
                if (country.isNotEmpty()) add(country)
                if (state.isNotEmpty()) add(state)
                if (city.isNotEmpty()) add(city)
                if (district.isNotEmpty()) add(district)
                if (street.isNotEmpty()) add(street)
            }
            val address = if (addressParts.isNotEmpty()) addressParts.joinToString(" ") else name

            val key = "$name|${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
            if (seen.add(key)) {
                results.add(
                    GeocodingResult(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        coordinateType = CoordinateType.WGS84,
                        city = city,
                        source = "photon_online"
                    )
                )
            }
        }
        return results
    }

    fun parseNominatimSearch(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()

        val results = ArrayList<GeocodingResult>()
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optString("lat").trim().toDoubleOrNull() ?: continue
            val lon = item.optString("lon").trim().toDoubleOrNull() ?: continue
            if (!GeoPoint.isValid(lat, lon)) continue

            val displayName = optNonBlank(item, "display_name")
            val explicitName = optNonBlank(item, "name")
            val name = explicitName.ifEmpty {
                displayName.substringBefore(',').trim()
            }
            if (name.isEmpty()) continue

            val addressObj = item.optJSONObject("address")
            val city = addressObj?.let { optNonBlank(it, "city", "town", "county", "state") }
                .orEmpty()
                .ifEmpty { name }

            val key = "$name|${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
            if (seen.add(key)) {
                results.add(
                    GeocodingResult(
                        name = name,
                        address = displayName.ifEmpty { name },
                        latitude = lat,
                        longitude = lon,
                        coordinateType = CoordinateType.WGS84,
                        city = city,
                        source = "nominatim_online"
                    )
                )
            }
        }
        return results
    }

    fun parseOpenMeteoSearch(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("results") ?: return emptyList()

        val results = ArrayList<GeocodingResult>()
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val lat = item.optDouble("latitude", Double.NaN)
            val lon = item.optDouble("longitude", Double.NaN)
            if (!GeoPoint.isValid(lat, lon)) continue

            val name = optNonBlank(item, "name")
            if (name.isEmpty()) continue

            val country = optNonBlank(item, "country")
            val admin1 = optNonBlank(item, "admin1")
            val admin2 = optNonBlank(item, "admin2")
            val admin3 = optNonBlank(item, "admin3")
            val city = admin2.ifEmpty { admin1.ifEmpty { name } }

            val parts = linkedSetOf<String>().apply {
                if (country.isNotEmpty()) add(country)
                if (admin1.isNotEmpty()) add(admin1)
                if (admin2.isNotEmpty()) add(admin2)
                if (admin3.isNotEmpty()) add(admin3)
            }
            val address = if (parts.isNotEmpty()) parts.joinToString(" ") else name

            val key = "$name|${String.format(Locale.US, "%.4f,%.4f", lat, lon)}"
            if (seen.add(key)) {
                results.add(
                    GeocodingResult(
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lon,
                        coordinateType = CoordinateType.WGS84,
                        city = city,
                        source = "open_meteo_online"
                    )
                )
            }
        }
        return results
    }

    fun parsePhotonReverse(jsonBody: String, wgsLat: Double, wgsLon: Double): GeocodingResult? {
        if (!GeoPoint.isValid(wgsLat, wgsLon)) return null
        val list = parsePhotonSearch(jsonBody)
        val first = list.firstOrNull() ?: return null
        return first.copy(
            latitude = wgsLat,
            longitude = wgsLon,
            coordinateType = CoordinateType.WGS84,
            source = "photon_regeo"
        )
    }

    fun parseNominatimReverse(jsonBody: String, wgsLat: Double, wgsLon: Double): GeocodingResult? {
        if (!GeoPoint.isValid(wgsLat, wgsLon)) return null
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (root.has("error")) return null

        val displayName = optNonBlank(root, "display_name")
        val explicitName = optNonBlank(root, "name")
        val addressObj = root.optJSONObject("address")
        val suburbOrRoad = addressObj?.let {
            optNonBlank(it, "amenity", "building", "road", "suburb", "neighbourhood", "county", "city")
        }.orEmpty()

        val name = explicitName.ifEmpty {
            suburbOrRoad.ifEmpty { displayName.substringBefore(',').trim() }
        }
        if (name.isEmpty()) return null

        val city = addressObj?.let { optNonBlank(it, "city", "town", "county", "state") }.orEmpty()
        return GeocodingResult(
            name = name,
            address = displayName.ifEmpty { name },
            latitude = wgsLat,
            longitude = wgsLon,
            coordinateType = CoordinateType.WGS84,
            city = city,
            source = "nominatim_regeo"
        )
    }

    private fun optNonBlank(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            if (value is JSONArray || value is JSONObject) continue
            val str = value?.toString()?.trim().orEmpty()
            if (str.isNotEmpty() && !str.equals("null", ignoreCase = true)) {
                return str
            }
        }
        return ""
    }
}
