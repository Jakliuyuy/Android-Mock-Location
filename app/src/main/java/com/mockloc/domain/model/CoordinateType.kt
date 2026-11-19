package com.mockloc.domain.model

enum class CoordinateType {
    WGS84,
    GCJ02,
    BD09;

    companion object {
        fun fromString(value: String?): CoordinateType {
            if (value.isNullOrBlank()) return WGS84
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: WGS84
        }
    }
}
