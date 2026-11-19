package com.mockloc.presentation.model

import com.mockloc.data.config.MockConfig
import com.mockloc.data.favorite.FavoriteUsageStats
import com.mockloc.data.scope.RecommendedAppItem
import com.mockloc.data.scope.ScopeSummary
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.model.AppScopeInfo
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.root.EnvironmentStatus

enum class ControlPanelTab {
    SINGLE,
    CRUISE
}

enum class MapLayerType {
    VECTOR,
    SATELLITE
}

enum class PendingPermissionAction {
    START_SINGLE,
    START_CRUISE
}

data class MainUiState(
    val environmentStatus: EnvironmentStatus? = null,
    val isDetectingEnvironment: Boolean = false,
    val config: MockConfig = MockConfig.DEFAULT,
    val runtimeState: MockRuntimeState = MockRuntimeState(),
    val activeTab: ControlPanelTab = ControlPanelTab.SINGLE,
    val mapLayer: MapLayerType = MapLayerType.VECTOR,
    val followCruise: Boolean = true,
    val tapMapUpdatesRunningSingle: Boolean = true,
    val selectedPoint: GeoPoint = MockConfig.DEFAULT.toGeoPoint(),
    val selectedAddressLabel: String = "点击地图或搜索地点以选点",
    val inputCoordinateType: CoordinateType = CoordinateType.WGS84,
    val latInput: String = formatCoord(MockConfig.DEFAULT.latitude),
    val lonInput: String = formatCoord(MockConfig.DEFAULT.longitude),
    val altInput: String = formatPlainDouble(MockConfig.DEFAULT.altitude, 1),
    val accuracyInput: String = formatPlainDouble(MockConfig.DEFAULT.accuracy.toDouble(), 1),
    val jitterRadiusInput: String = formatPlainDouble(MockConfig.DEFAULT.jitterRadiusMeters, 1),
    val latError: String? = null,
    val lonError: String? = null,
    val altError: String? = null,
    val accuracyError: String? = null,
    val jitterRadiusError: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<GeocodingResult> = emptyList(),
    val isSearchOfflineFallback: Boolean = false,
    val searchInfoMessage: String? = null,
    val draftRouteId: String? = null,
    val draftRouteName: String = "自定义巡航路线",
    val draftWaypoints: List<Waypoint> = emptyList(),
    val cruiseSpeedPreset: SpeedPreset = SpeedPreset.WALK,
    val customSpeedMps: Double = SpeedPreset.CUSTOM.speedMps,
    val cruiseLoopMode: LoopMode = LoopMode.ONE_WAY,
    val enableSpeedNoise: Boolean = true,
    val enableMicroDrift: Boolean = true,
    val favorites: List<FavoriteLocation> = emptyList(),
    val favoriteUsageStats: Map<String, FavoriteUsageStats> = emptyMap(),
    val favoriteSearchQuery: String = "",
    val filteredFavorites: List<FavoriteLocation> = emptyList(),
    val savedRoutes: List<PlannedRoute> = emptyList(),
    val recommendedScopes: List<RecommendedAppItem> = emptyList(),
    val scopeSummary: ScopeSummary = ScopeSummary(0, 14, 0, 16),
    val whitelistCount: Int = 0,
    val antiDetectExpanded: Boolean = false,
    val hasDismissedWhitelistHint: Boolean = false
) {
    val isOneWayCompleted: Boolean
        get() = runtimeState.status is MockRunStatus.Idle &&
            runtimeState.progressFraction >= 1.0f &&
            runtimeState.totalWaypoints > 0

    val isRunningAny: Boolean
        get() = runtimeState.status is MockRunStatus.SingleRunning ||
            runtimeState.status is MockRunStatus.CruiseRunning ||
            runtimeState.status is MockRunStatus.CruisePaused

    val shouldShowEmptyWhitelistHint: Boolean
        get() = whitelistCount == 0 &&
            !hasDismissedWhitelistHint &&
            config.mode != MockMode.ROOT_ONLY

    val effectiveCruiseSpeedMps: Double
        get() = if (cruiseSpeedPreset == SpeedPreset.CUSTOM) {
            customSpeedMps
        } else {
            cruiseSpeedPreset.speedMps
        }

    companion object {
        fun formatCoord(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)
        fun formatPlainDouble(value: Double, decimals: Int): String =
            String.format(java.util.Locale.US, "%.${decimals}f", value)
    }
}

sealed interface MainUiEvent {
    data class ShowSnackbar(val message: String) : MainUiEvent
    data class RequestPermissions(
        val needLocation: Boolean,
        val needNotification: Boolean
    ) : MainUiEvent
    data class AnimateMapToPoint(val point: GeoPoint, val zoom: Double = 16.0) : MainUiEvent
    data class FitRouteOnMap(val waypoints: List<Waypoint>) : MainUiEvent
    data class CopyToClipboard(val label: String, val text: String) : MainUiEvent
    data class FavoriteFeedback(val success: Boolean, val message: String) : MainUiEvent
    data class RouteFeedback(val success: Boolean, val message: String) : MainUiEvent
    data class ScopeGuideFeedback(val success: Boolean, val message: String) : MainUiEvent
}

data class WhitelistUiState(
    val isLoading: Boolean = true,
    val allApps: List<AppScopeInfo> = emptyList(),
    val filteredApps: List<AppScopeInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val searchQuery: String = "",
    val includeSystemApps: Boolean = false,
    val recommendedSummary: ScopeSummary? = null,
    val errorMessage: String? = null
)

sealed interface WhitelistUiEvent {
    data class ShowMessage(val message: String) : WhitelistUiEvent
}
