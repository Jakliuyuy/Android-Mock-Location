package com.mockloc.domain.geocoding

import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 多源在线地理编码响应解析器（Photon / Nominatim / Open-Meteo）。
 * 所有在线源均返回权威 WGS-84 坐标，直接匹配项目核心坐标系。
 */
object MultiProviderGeocodingParser {

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
