package com.mockloc.domain.geocoding

import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import java.util.Locale

sealed interface CoordinateParseOutcome {
    data class Valid(val result: GeocodingResult) : CoordinateParseOutcome
    data object OutOfRangeOrInvalid : CoordinateParseOutcome
    data object NotACoordinate : CoordinateParseOutcome
}

object CoordinateQueryParser {

    private val COORD_TYPE_PREFIX_REGEX = Regex(
        """^(wgs84|wgs|gps|gcj02|gcj|mars|amap|gaode|bd09|bd|baidu)\s*[:：]\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private val DECIMAL_REGEX = Regex("""^([+-]?\d+(?:\.\d+)?)°?$""")
    private val DMS_REGEX = Regex(
        """^([+-]?\d+(?:\.\d+)?)\s*°\s*(\d+(?:\.\d+)?)\s*'(?:\s*(\d+(?:\.\d+)?)\s*"?)?$"""
    )

    private val SPECIAL_FLOAT_REGEX = Regex(
        """^[+-]?(?:nan|inf|infinity)$""",
        RegexOption.IGNORE_CASE
    )

    private val DMS_UNIT_SPACE_REGEX = Regex("""([°'"])\s+""")
    private val VALUE_HEMISPHERE_SPACE_REGEX = Regex("""([\d"'°])\s+([NSEWnsew])(?=$|[\s,;])""")
    private val HEMISPHERE_VALUE_SPACE_REGEX = Regex("""(^|[\s,;])([NSEWnsew])\s+([+-]?\d)""")

    private enum class Hemisphere {
        NORTH, SOUTH, EAST, WEST;

        val isLatitude: Boolean get() = this == NORTH || this == SOUTH
        val isLongitude: Boolean get() = this == EAST || this == WEST
        val sign: Int get() = if (this == SOUTH || this == WEST) -1 else 1
    }

    private sealed interface ComponentParse {
        data class Parsed(val value: Double, val hemisphere: Hemisphere?) : ComponentParse
        data object InvalidNumericOrRange : ComponentParse
        data object NotNumeric : ComponentParse
    }

    fun parse(rawQuery: String): GeocodingResult? {
        return when (val outcome = parseWithStatus(rawQuery)) {
            is CoordinateParseOutcome.Valid -> outcome.result
            CoordinateParseOutcome.OutOfRangeOrInvalid,
            CoordinateParseOutcome.NotACoordinate -> null
        }
    }

    fun parseWithStatus(rawQuery: String): CoordinateParseOutcome {
        val trimmed = normalizeFullwidthAscii(rawQuery).trim()
        if (trimmed.isEmpty()) return CoordinateParseOutcome.NotACoordinate

        var sourceType = CoordinateType.WGS84
        var hasExplicitType = false
        var body = trimmed

        val prefixMatch = COORD_TYPE_PREFIX_REGEX.matchEntire(trimmed)
        if (prefixMatch != null) {
            sourceType = mapPrefixToType(prefixMatch.groupValues[1])
            hasExplicitType = true
            body = prefixMatch.groupValues[2].trim()
            if (body.isEmpty()) return CoordinateParseOutcome.OutOfRangeOrInvalid
        }

        val normalized = normalizePunctuation(body)
        val tokens = splitTwoTokens(normalized) ?: return CoordinateParseOutcome.NotACoordinate
        if (tokens.size != 2) return CoordinateParseOutcome.NotACoordinate

        val first = parseComponent(tokens[0])
        val second = parseComponent(tokens[1])

        if (first is ComponentParse.NotNumeric || second is ComponentParse.NotNumeric) {
            return if (hasExplicitType && (first is ComponentParse.InvalidNumericOrRange || second is ComponentParse.InvalidNumericOrRange)) {
                CoordinateParseOutcome.OutOfRangeOrInvalid
            } else {
                CoordinateParseOutcome.NotACoordinate
            }
        }

        if (first !is ComponentParse.Parsed || second !is ComponentParse.Parsed) {
            return CoordinateParseOutcome.OutOfRangeOrInvalid
        }

        val latLon = resolveLatLon(first, second) ?: return CoordinateParseOutcome.OutOfRangeOrInvalid
        val rawLat = latLon.first
        val rawLon = latLon.second

        if (!GeoPoint.isValid(rawLat, rawLon)) {
            return CoordinateParseOutcome.OutOfRangeOrInvalid
        }

        val wgsPoint = when (sourceType) {
            CoordinateType.WGS84 -> GeoPoint(latitude = rawLat, longitude = rawLon)
            CoordinateType.GCJ02 -> CoordinateConverter.gcj02ToWgs84(rawLat, rawLon)
            CoordinateType.BD09 -> CoordinateConverter.bd09ToWgs84(rawLat, rawLon)
        }

        val (sourceTag, typeDesc) = when (sourceType) {
            CoordinateType.WGS84 -> "direct_wgs84" to "WGS-84 权威坐标"
            CoordinateType.GCJ02 -> "direct_gcj02" to "由 GCJ-02 转换至 WGS-84 权威坐标"
            CoordinateType.BD09 -> "direct_bd09" to "由 BD-09 转换至 WGS-84 权威坐标"
        }

        return CoordinateParseOutcome.Valid(
            GeocodingResult(
                name = String.format(Locale.US, "坐标 (%.6f, %.6f)", wgsPoint.latitude, wgsPoint.longitude),
                address = typeDesc,
                latitude = wgsPoint.latitude,
                longitude = wgsPoint.longitude,
                coordinateType = CoordinateType.WGS84,
                city = "",
                source = sourceTag
            )
        )
    }

    private fun mapPrefixToType(rawPrefix: String): CoordinateType {
        return when (rawPrefix.trim().lowercase(Locale.US)) {
            "gcj02", "gcj", "mars", "amap", "gaode" -> CoordinateType.GCJ02
            "bd09", "bd", "baidu" -> CoordinateType.BD09
            else -> CoordinateType.WGS84
        }
    }

    private fun normalizeFullwidthAscii(input: String): String {
        return buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                    '\u3000', '\u00A0' -> append(' ')
                    else -> append(ch)
                }
            }
        }
    }

    private fun normalizePunctuation(input: String): String {
        val halfWidth = normalizeFullwidthAscii(input)
        val replaced = buildString(halfWidth.length) {
            for (ch in halfWidth) {
                when (ch) {
                    '，' -> append(',')
                    '；' -> append(';')
                    '′', '＇', '’', '‘' -> append('\'')
                    '″', '＂', '”', '“' -> append('"')
                    '\u3000', '\u00A0' -> append(' ')
                    else -> append(ch)
                }
            }
        }.replace("''", "\"")
        var compact = DMS_UNIT_SPACE_REGEX.replace(replaced.trim(), "$1")
        compact = VALUE_HEMISPHERE_SPACE_REGEX.replace(compact, "$1$2")
        compact = HEMISPHERE_VALUE_SPACE_REGEX.replace(compact, "$1$2$3")
        return compact.trim()
    }

    private fun splitTwoTokens(normalized: String): List<String>? {
        val byDelimiter = normalized.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (byDelimiter.size > 1) {
            return if (byDelimiter.size == 2) byDelimiter else null
        }
        val byWhitespace = normalized.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (byWhitespace.size == 2) byWhitespace else null
    }

    private fun parseComponent(rawToken: String): ComponentParse {
        var token = rawToken.trim()
        if (token.isEmpty()) return ComponentParse.NotNumeric

        var prefixHemi: Hemisphere? = null
        var suffixHemi: Hemisphere? = null

        val firstCharHemi = charToHemisphere(token.first())
        if (firstCharHemi != null) {
            prefixHemi = firstCharHemi
            token = token.substring(1).trim()
        }

        if (token.isNotEmpty()) {
            val lastCharHemi = charToHemisphere(token.last())
            if (lastCharHemi != null) {
                suffixHemi = lastCharHemi
                token = token.substring(0, token.length - 1).trim()
            }
        }

        if (prefixHemi != null && suffixHemi != null) {
            return ComponentParse.InvalidNumericOrRange
        }
        val hemisphere = prefixHemi ?: suffixHemi

        if (token.isEmpty()) {
            return ComponentParse.NotNumeric
        }

        if (SPECIAL_FLOAT_REGEX.matches(token)) {
            return ComponentParse.InvalidNumericOrRange
        }

        if (hemisphere != null && (token.startsWith('+') || token.startsWith('-'))) {
            // 禁止出现矛盾或冗余符号如 N-39.9
            return ComponentParse.InvalidNumericOrRange
        }

        val decimalMatch = DECIMAL_REGEX.matchEntire(token)
        if (decimalMatch != null) {
            val num = decimalMatch.groupValues[1].toDoubleOrNull()
                ?: return ComponentParse.InvalidNumericOrRange
            if (!num.isFinite()) return ComponentParse.InvalidNumericOrRange
            val signed = if (hemisphere != null) num * hemisphere.sign else num
            return ComponentParse.Parsed(signed, hemisphere)
        }

        val dmsMatch = DMS_REGEX.matchEntire(token)
        if (dmsMatch != null) {
            val degRaw = dmsMatch.groupValues[1].toDoubleOrNull()
                ?: return ComponentParse.InvalidNumericOrRange
            val minRaw = dmsMatch.groupValues[2].toDoubleOrNull()
                ?: return ComponentParse.InvalidNumericOrRange
            val secRaw = if (dmsMatch.groupValues[3].isNotEmpty()) {
                dmsMatch.groupValues[3].toDoubleOrNull() ?: return ComponentParse.InvalidNumericOrRange
            } else {
                0.0
            }
            if (!degRaw.isFinite() || !minRaw.isFinite() || !secRaw.isFinite()) {
                return ComponentParse.InvalidNumericOrRange
            }
            if (minRaw < 0.0 || minRaw >= 60.0 || secRaw < 0.0 || secRaw >= 60.0) {
                return ComponentParse.InvalidNumericOrRange
            }
            // 度为小数时禁止再携带分秒
            if (dmsMatch.groupValues[1].contains('.') && (minRaw != 0.0 || secRaw != 0.0)) {
                return ComponentParse.InvalidNumericOrRange
            }
            // 分为小数时禁止再携带秒
            if (dmsMatch.groupValues[2].contains('.') && secRaw != 0.0) {
                return ComponentParse.InvalidNumericOrRange
            }

            val absDeg = kotlin.math.abs(degRaw)
            val magnitude = absDeg + minRaw / 60.0 + secRaw / 3600.0
            val sign = when {
                hemisphere != null -> hemisphere.sign
                degRaw < 0.0 || dmsMatch.groupValues[1].startsWith('-') -> -1
                else -> 1
            }
            val value = sign * magnitude
            if (!value.isFinite()) return ComponentParse.InvalidNumericOrRange
            return ComponentParse.Parsed(value, hemisphere)
        }

        return ComponentParse.NotNumeric
    }

    private fun resolveLatLon(
        first: ComponentParse.Parsed,
        second: ComponentParse.Parsed
    ): Pair<Double, Double>? {
        val h1 = first.hemisphere
        val h2 = second.hemisphere

        return when {
            h1 != null && h2 != null -> {
                when {
                    h1.isLatitude && h2.isLongitude -> first.value to second.value
                    h1.isLongitude && h2.isLatitude -> second.value to first.value
                    else -> null // 同维度双半球 (如 N 与 S, 或 E 与 W) 非法
                }
            }
            h1 != null && h2 == null -> {
                if (h1.isLatitude) first.value to second.value else second.value to first.value
            }
            h1 == null && h2 != null -> {
                if (h2.isLongitude) first.value to second.value else second.value to first.value
            }
            else -> first.value to second.value
        }
    }

    private fun charToHemisphere(c: Char): Hemisphere? {
        return when (c.uppercaseChar()) {
            'N' -> Hemisphere.NORTH
            'S' -> Hemisphere.SOUTH
            'E' -> Hemisphere.EAST
            'W' -> Hemisphere.WEST
            else -> null
        }
    }
}
