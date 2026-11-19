package com.mockloc.domain.geocoding

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AmapResponseParser {

    fun parseSearchResults(jsonBody: String): List<GeocodingResult> {
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return emptyList()

        val root = try {
            JSONObject(trimmed)
        } catch (_: Throwable) {
            return emptyList()
        }

        if (root.has("status") && !root.isNull("status")) {
            val statusStr = root.opt("status")?.toString()?.trim().orEmpty()
            val statusOk = statusStr == "1" || statusStr.equals("true", ignoreCase = true)
            if (!statusOk) {
                return emptyList()
            }
        }

        val dataObj = root.optJSONObject("data")
        val candidateArrays = listOfNotNull(
            dataObj?.optJSONArray("tip_list"),
            dataObj?.optJSONArray("poi_list"),
            root.optJSONArray("tip_list"),
            root.optJSONArray("tips"),
            root.optJSONArray("pois")
        )

        if (candidateArrays.isEmpty()) return emptyList()

        val results = ArrayList<GeocodingResult>()
        val seenKeys = HashSet<String>()

        for (array in candidateArrays) {
            for (i in 0 until array.length()) {
                val rawItem = array.optJSONObject(i) ?: continue
                val item = rawItem.optJSONObject("tip") ?: rawItem
                val parsed = parsePoiObject(item) ?: continue
                val dedupKey = "${parsed.name}|${String.format(Locale.US, "%.5f,%.5f", parsed.latitude, parsed.longitude)}"
                if (seenKeys.add(dedupKey)) {
                    results.add(parsed)
                }
            }
            if (results.isNotEmpty()) break
        }

        return results
    }

    fun parseReverseGeocode(
        jsonBody: String,
        wgsLat: Double,
        wgsLon: Double
    ): GeocodingResult? {
        if (!GeoPoint.isValid(wgsLat, wgsLon)) return null
        val trimmed = jsonBody.trim()
        if (!trimmed.startsWith("{")) return null

        val root = try {
            JSONObject(trimmed)
        } catch (_: Throwable) {
            return null
        }

        if (root.has("status") && !root.isNull("status")) {
            val statusStr = root.opt("status")?.toString()?.trim().orEmpty()
            val statusOk = statusStr == "1" || statusStr.equals("true", ignoreCase = true)
            if (!statusOk) {
                return null
            }
        }

        val dataObj = root.optJSONObject("data")
        val regeocodeObj = root.optJSONObject("regeocode")
        val addressComponent = regeocodeObj?.optJSONObject("addressComponent")

        val city = firstNonBlankString(
            dataObj?.let { optStringField(it, "city", "cityname", "province") },
            dataObj?.optJSONObject("pos")?.let { optStringField(it, "city", "cityname", "province") },
            addressComponent?.let { optStringField(it, "city", "province", "district") }
        )

        val formattedDesc = firstNonBlankString(
            dataObj?.let { optStringField(it, "desc", "pos_desc") },
            dataObj?.optJSONObject("pos")?.let { optStringField(it, "desc") },
            regeocodeObj?.let { optStringField(it, "formatted_address") }
        )

        val firstPoi = dataObj?.optJSONArray("poi_list")?.optJSONObject(0)
            ?: regeocodeObj?.optJSONArray("pois")?.optJSONObject(0)
        val firstRoad = dataObj?.optJSONArray("road_list")?.optJSONObject(0)
            ?: regeocodeObj?.optJSONArray("roads")?.optJSONObject(0)

        val poiName = firstPoi?.let { optStringField(it, "name") }.orEmpty()
        val roadName = firstRoad?.let { optStringField(it, "name") }.orEmpty()

        val displayName = when {
            poiName.isNotEmpty() -> poiName
            formattedDesc.isNotEmpty() -> formattedDesc
            roadName.isNotEmpty() -> roadName
            else -> return null
        }

        val fullAddress = when {
            formattedDesc.isNotEmpty() -> formattedDesc
            roadName.isNotEmpty() && poiName.isNotEmpty() -> "$roadName $poiName"
            else -> displayName
        }

        return GeocodingResult(
            name = displayName,
            address = fullAddress,
            latitude = wgsLat,
            longitude = wgsLon,
            coordinateType = CoordinateType.WGS84,
            city = city,
            source = "amap_regeo"
        )
    }

    private fun parsePoiObject(item: JSONObject): GeocodingResult? {
        val name = optStringField(item, "name", "disp_name")
        if (name.isEmpty()) return null

        val gcjCoords = extractCoordinates(item) ?: return null
        val gcjLat = gcjCoords.first
        val gcjLon = gcjCoords.second
        if (!GeoPoint.isValid(gcjLat, gcjLon)) return null

        val wgsPoint = CoordinateConverter.gcj02ToWgs84(gcjLat, gcjLon)

        val district = optStringField(item, "district", "adname")
        val city = firstNonBlankString(
            optStringField(item, "cityname", "city"),
            district
        )
        val rawAddress = optStringField(item, "address", "addr")
        val fullAddress = when {
            district.isNotEmpty() && rawAddress.isNotEmpty() && !rawAddress.contains(district) ->
                "$district $rawAddress"
            rawAddress.isNotEmpty() -> rawAddress
            district.isNotEmpty() -> district
            else -> city
        }

        return GeocodingResult(
            name = name,
            address = fullAddress,
            latitude = wgsPoint.latitude,
            longitude = wgsPoint.longitude,
            coordinateType = CoordinateType.WGS84,
            city = city,
            source = "amap_web"
        )
    }

    private fun extractCoordinates(item: JSONObject): Pair<Double, Double>? {
        val locStr = firstNonBlankString(
            optStringField(item, "location"),
            optStringField(item, "geo")
        )
        if (locStr.isNotEmpty()) {
            val parts = locStr.split(',')
            if (parts.size == 2) {
                val lon = parts[0].trim().toDoubleOrNull()
                val lat = parts[1].trim().toDoubleOrNull()
                if (lon != null && lat != null && lat.isFinite() && lon.isFinite()) {
                    return lat to lon
                }
            }
        }

        val lon = optDoubleField(item, "x", "lng", "lon", "longitude", "disp_x")
        val lat = optDoubleField(item, "y", "lat", "latitude", "disp_y")
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
            return lat to lon
        }
        return null
    }

    private fun optStringField(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            // 严防高德接口返回 "address": [] 空数组被 String.valueOf 转成 "[]"
            if (value is JSONArray || value is JSONObject) continue
            val str = value?.toString()?.trim().orEmpty()
            if (str.isNotEmpty() && !str.equals("null", ignoreCase = true)) {
                return str
            }
        }
        return ""
    }

    private fun optDoubleField(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key)
            val d = when (value) {
                is Number -> value.toDouble()
                is String -> value.trim().toDoubleOrNull()
                else -> null
            }
            if (d != null && d.isFinite()) {
                return d
            }
        }
        return null
    }

    private fun firstNonBlankString(vararg candidates: String?): String {
        for (candidate in candidates) {
            val trimmed = candidate?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }
}
