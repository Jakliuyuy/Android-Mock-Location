package com.mockloc.domain.model

enum class MockMode {
    AUTO,
    LSPOSED_ONLY,
    ROOT_ONLY;

    companion object {
        fun fromString(value: String?): MockMode {
            if (value.isNullOrBlank()) return AUTO
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}
