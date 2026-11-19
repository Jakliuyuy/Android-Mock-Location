package com.mockloc.domain.model

sealed interface MockRunStatus {
    data object Idle : MockRunStatus
    data object SingleRunning : MockRunStatus
    data object CruiseRunning : MockRunStatus
    data object CruisePaused : MockRunStatus
    data class Error(val reason: String) : MockRunStatus
}
