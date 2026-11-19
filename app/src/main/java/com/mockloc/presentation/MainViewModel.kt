package com.mockloc.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.data.favorite.FavoriteRepository
import com.mockloc.data.route.RouteRepository
import com.mockloc.data.scope.PackageInstallChecker
import com.mockloc.data.scope.RecommendedScopeRepository
import com.mockloc.data.whitelist.WhitelistRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.LoopMode
import com.mockloc.domain.engine.SpeedPreset
import com.mockloc.domain.geocoding.GeocodingService
import com.mockloc.domain.geocoding.OfflineFallbackGeocodingService
import com.mockloc.domain.math.CoordinateConverter
import com.mockloc.domain.model.CoordinateType
import com.mockloc.domain.model.FavoriteLocation
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.GeocodingResult
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import com.mockloc.domain.model.Waypoint
import com.mockloc.presentation.gateway.EnvironmentDetectorGateway
import com.mockloc.presentation.gateway.MockEngineGateway
import com.mockloc.presentation.gateway.PermissionStatusProvider
import com.mockloc.presentation.model.ControlPanelTab
import com.mockloc.presentation.model.MainUiEvent
import com.mockloc.presentation.model.MainUiState
import com.mockloc.presentation.model.MapLayerType
import com.mockloc.presentation.model.PendingPermissionAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 首页核心 ViewModel：严格遵循单向数据流，不持有 Android Context，支持 100% 纯 JVM 单元测试。
 */
class MainViewModel(
    private val configRepository: MockConfigRepository,
    private val engineGateway: MockEngineGateway,
    private val geocodingService: GeocodingService,
    private val favoriteRepository: FavoriteRepository,
    private val routeRepository: RouteRepository,
    private val whitelistRepositoryProvider: suspend () -> WhitelistRepository,
    private val environmentDetector: EnvironmentDetectorGateway,
    private val permissionProvider: PermissionStatusProvider,
    private val packageInstallChecker: PackageInstallChecker,
    private val onConfigChangedBroadcast: (MockConfig) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = 350L,
    private val stopRootProvider: suspend () -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        createInitialState(configRepository.currentConfig)
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    private val modeSwitchMutex = Mutex()
    private val lifecycleEpoch = AtomicLong(0L)
    private val lifecycleLock = Any()
    private var searchJob: Job? = null
    private var reverseGeocodeJob: Job? = null
    private var pendingPermissionAction: PendingPermissionAction? = null
    private var hasAskedNotificationPermission = false
    private var lastReportedRunStatus: MockRunStatus = engineGateway.runtimeState.value.status

    // 以下并发控制与审计字段严格受 lifecycleLock 保护；锁偏序：modeSwitchMutex -> lifecycleLock -> engineGateway/configRepo
    private var desiredEngineState: DesiredEngineState = initialDesiredState(
        status = engineGateway.runtimeState.value.status,
        point = _uiState.value.selectedPoint
    )
    private val pendingControlQueue = ArrayDeque<PendingControlAction>()
    private var isDrainingControlQueue = false
    private var lastScheduledMode: MockMode = configRepository.currentConfig.mode

    // 严格先读 currentConfig 再读 engineDisableSeq（满足 MockConfigRepository 锁内 happens-before 写入序）
    private var lastAuditedConfig: MockConfig = configRepository.currentConfig
    private var lastAuditedEngineDisableSeq: Long = configRepository.engineDisableSeq
    private var internalSwitchOpSeq = 0L
    private var activeInternalStopOpId = 0L
    private var internalStopConfigQuota = 0
    private var internalStopIdleQuota = 0
    private var pendingVmStopConfigQuota = 0
    private var pendingVmStopIdleQuota = 0
    private var isInternalModeSwitchStopping = false
    private var lastInternalSwitchIdleState: MockRuntimeState? = null
    private var lastObservedExternalIdleSyncMs = Long.MIN_VALUE
    private var lastInternalRestartConfigVersion = -1L

    init {
        observeConfig()
        observeRuntimeState()
        observeFavorites()
        observeRoutes()
        initWhitelistInBackground()
        refreshEnvironment()
        refreshRecommendedScopes()
    }

    private fun initialDesiredState(status: MockRunStatus, point: GeoPoint): DesiredEngineState {
        return when (status) {
            is MockRunStatus.SingleRunning -> DesiredEngineState.Single(point)
            is MockRunStatus.CruiseRunning -> DesiredEngineState.CruiseRunning
            is MockRunStatus.CruisePaused -> DesiredEngineState.CruisePaused
            is MockRunStatus.Idle, is MockRunStatus.Error -> DesiredEngineState.Stopped
        }
    }

    private fun createInitialState(initialConfig: MockConfig): MainUiState {
        val wgsPoint = initialConfig.toGeoPoint()
        val inputCoords = convertWgsToType(wgsPoint, initialConfig.coordinateType)
        return MainUiState(
            config = initialConfig,
            selectedPoint = wgsPoint,
            inputCoordinateType = initialConfig.coordinateType,
            latInput = MainUiState.formatCoord(inputCoords.latitude),
            lonInput = MainUiState.formatCoord(inputCoords.longitude),
            altInput = MainUiState.formatPlainDouble(wgsPoint.altitude, 1),
            accuracyInput = MainUiState.formatPlainDouble(initialConfig.accuracy.toDouble(), 1),
            jitterRadiusInput = MainUiState.formatPlainDouble(initialConfig.jitterRadiusMeters, 1),
            enableMicroDrift = initialConfig.jitterEnabled
        )
    }

    private fun observeConfig() {
        viewModelScope.launch {
            configRepository.configFlow.collect { cfg ->
                synchronized(lifecycleLock) {
                    auditConfigLocked()
                }
                _uiState.update { state ->
                    if (cfg.version >= state.config.version) {
                        state.copy(
                            config = cfg,
                            whitelistCount = cfg.targetPackages.size
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun observeRuntimeState() {
        viewModelScope.launch {
            engineGateway.runtimeState.collect { runtime ->
                val prev = synchronized(lifecycleLock) {
                    val previous = lastReportedRunStatus
                    lastReportedRunStatus = runtime.status
                    handleObservedRuntimeStatusLocked(previous, runtime)
                    previous
                }
                // 保持 selectedPoint 为用户选定基准锚点，微抖动实时偏移点仅保存在 runtimeState.currentPoint 中
                _uiState.update { state ->
                    state.copy(runtimeState = runtime)
                }

                if (runtime.status is MockRunStatus.Error && prev != runtime.status) {
                    emitEvent(MainUiEvent.ShowSnackbar(runtime.status.reason))
                } else if (
                    prev is MockRunStatus.CruiseRunning &&
                    runtime.status is MockRunStatus.Idle &&
                    runtime.progressFraction >= 1.0f &&
                    runtime.totalWaypoints > 0
                ) {
                    emitEvent(MainUiEvent.ShowSnackbar("单程轨迹巡航已完成，可点击“重新开始巡航”再次出发"))
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepository.favoritesFlow.collect { favs ->
                _uiState.update { state ->
                    state.copy(
                        favorites = favs,
                        filteredFavorites = filterFavoritesLocal(favs, state.favoriteSearchQuery)
                    )
                }
            }
        }
        viewModelScope.launch {
            favoriteRepository.usageStatsFlow.collect { stats ->
                _uiState.update { it.copy(favoriteUsageStats = stats) }
            }
        }
    }

    private fun observeRoutes() {
        viewModelScope.launch {
            routeRepository.routesFlow.collect { routes ->
                _uiState.update { it.copy(savedRoutes = routes) }
            }
        }
    }

    private fun initWhitelistInBackground() {
        viewModelScope.launch(ioDispatcher) {
            runCatching {
                val repo = whitelistRepositoryProvider()
                repo.selectedPackagesFlow.collect { selected ->
                    _uiState.update { it.copy(whitelistCount = selected.size) }
                }
            }
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDetectingEnvironment = true) }
            val env = runCatching {
                withContext(ioDispatcher) {
                    environmentDetector.detect()
                }
            }.getOrNull()
            _uiState.update {
                it.copy(
                    environmentStatus = env ?: it.environmentStatus,
                    isDetectingEnvironment = false
                )
            }
        }
    }

    fun refreshRecommendedScopes() {
        viewModelScope.launch(ioDispatcher) {
            val scopes = RecommendedScopeRepository.getRecommendedScopes(packageInstallChecker)
            val summary = RecommendedScopeRepository.summarizeScopeStats(scopes)
            _uiState.update {
                it.copy(
                    recommendedScopes = scopes,
                    scopeSummary = summary
                )
            }
        }
    }

    fun importRecommendedInstalledToWhitelist() {
        viewModelScope.launch(ioDispatcher) {
            val result = runCatching {
                val repo = whitelistRepositoryProvider()
                RecommendedScopeRepository.addAllInstalledToWhitelist(packageInstallChecker, repo)
            }
            result.onSuccess { res ->
                val msg = if (res.importedPackages.isEmpty()) {
                    "未检测到可新加入的已安装推荐应用（模块自身与系统框架已自动跳过）"
                } else {
                    "已一键加入 ${res.importedPackages.size} 个推荐应用到白名单（当前共 ${res.totalSelectedPackages.size} 项）"
                }
                emitEvent(MainUiEvent.ScopeGuideFeedback(true, msg))
            }.onFailure { err ->
                emitEvent(MainUiEvent.ScopeGuideFeedback(false, "导入推荐白名单失败：${err.message ?: "未知错误"}"))
            }
        }
    }

    fun setActiveTab(tab: ControlPanelTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun toggleMapLayer() {
        _uiState.update { state ->
            val next = if (state.mapLayer == MapLayerType.VECTOR) {
                MapLayerType.SATELLITE
            } else {
                MapLayerType.VECTOR
            }
            state.copy(mapLayer = next)
        }
    }

    fun setFollowCruise(follow: Boolean) {
        _uiState.update { it.copy(followCruise = follow) }
    }

    fun setTapMapUpdatesRunningSingle(enabled: Boolean) {
        _uiState.update { it.copy(tapMapUpdatesRunningSingle = enabled) }
    }

    fun setAntiDetectExpanded(expanded: Boolean) {
        _uiState.update { it.copy(antiDetectExpanded = expanded) }
    }

    fun dismissWhitelistHint() {
        _uiState.update { it.copy(hasDismissedWhitelistHint = true) }
    }

    fun setInputCoordinateType(type: CoordinateType) {
        val current = _uiState.value
        if (current.inputCoordinateType == type) return
        val converted = convertWgsToType(current.selectedPoint, type)
        _uiState.update {
            it.copy(
                inputCoordinateType = type,
                latInput = MainUiState.formatCoord(converted.latitude),
                lonInput = MainUiState.formatCoord(converted.longitude),
                latError = null,
                lonError = null
            )
        }
        updateConfigAndBroadcast { it.copy(coordinateType = type) }
    }

    fun setMockMode(newMode: MockMode) {
        val shouldLaunch = synchronized(lifecycleLock) {
            if (lastScheduledMode == newMode) {
                return
            }
            lastScheduledMode = newMode
            pendingControlQueue.addLast(PendingControlAction.SwitchMode(newMode))
            if (!isDrainingControlQueue) {
                isDrainingControlQueue = true
                true
            } else {
                false
            }
        }
        if (shouldLaunch) {
            viewModelScope.launch {
                drainControlQueue()
            }
        }
    }

    private suspend fun drainControlQueue() {
        var normalExit = false
        modeSwitchMutex.withLock {
            try {
                while (true) {
                    val action = synchronized(lifecycleLock) {
                        if (pendingControlQueue.isEmpty()) {
                            isDrainingControlQueue = false
                            normalExit = true
                            return@withLock
                        }
                        pendingControlQueue.removeFirst()
                    }
                    try {
                        when (action) {
                            is PendingControlAction.SwitchMode -> executeSwitchMode(action.targetMode)
                            is PendingControlAction.StartSingle -> executeQueuedStartSingle(action)
                            is PendingControlAction.StartCruise -> executeQueuedStartCruise(action)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // 单条控制动作异常不阻塞后续队列排空
                    }
                }
            } finally {
                if (!normalExit) {
                    synchronized(lifecycleLock) {
                        pendingControlQueue.clear()
                        isDrainingControlQueue = false
                    }
                }
            }
        }
    }

    private suspend fun executeSwitchMode(newMode: MockMode) {
        val prep = synchronized(lifecycleLock) {
            auditConfigLocked()
            val initialCfg = configRepository.currentConfig
            if (initialCfg.mode == newMode) {
                return@synchronized null
            }
            val rawStatus = engineGateway.runtimeState.value.status
            if (rawStatus is MockRunStatus.Error && desiredEngineState !is DesiredEngineState.Stopped) {
                desiredEngineState = DesiredEngineState.Stopped
            }
            val switchEpoch = lifecycleEpoch.get()
            val effectiveStatus = resolveEffectiveStatusLocked(rawStatus, initialCfg)
            updateConfigAndBroadcastLocked { it.copy(mode = newMode) }
            auditConfigLocked()
            val latestCfgAfterMode = configRepository.currentConfig
            val afterModeStatus = engineGateway.runtimeState.value.status

            val wasActiveBeforeSwitch =
                effectiveStatus is MockRunStatus.SingleRunning ||
                effectiveStatus is MockRunStatus.CruiseRunning ||
                effectiveStatus is MockRunStatus.CruisePaused

            val switchOpId = if (wasActiveBeforeSwitch) {
                val stillActiveAfterModeWrite =
                    lifecycleEpoch.get() == switchEpoch &&
                    desiredEngineState !is DesiredEngineState.Stopped &&
                    latestCfgAfterMode.enabled &&
                    afterModeStatus !is MockRunStatus.Error &&
                    !(rawStatus !is MockRunStatus.Idle && afterModeStatus is MockRunStatus.Idle)

                if (!stillActiveAfterModeWrite) {
                    if (desiredEngineState !is DesiredEngineState.Stopped) {
                        markExternalStopLocked()
                    }
                    ABORTED_BEFORE_INTERNAL_STOP_OP_ID
                } else {
                    val opId = ++internalSwitchOpSeq
                    activeInternalStopOpId = opId
                    isInternalModeSwitchStopping = true
                    lastInternalSwitchIdleState = null

                    val seqBeforeInternalStop = lastAuditedEngineDisableSeq
                    engineGateway.stopMock()
                    val postStopCfg = configRepository.currentConfig
                    val seqAfterInternalStop = configRepository.engineDisableSeq
                    val stopCallDelta = (seqAfterInternalStop - seqBeforeInternalStop).coerceAtLeast(0L)

                    lastAuditedConfig = postStopCfg
                    lastAuditedEngineDisableSeq = seqAfterInternalStop

                    when {
                        stopCallDelta >= 2L -> {
                            internalStopConfigQuota = 0
                            internalStopIdleQuota = 1
                            markExternalStopLocked()
                        }
                        stopCallDelta == 1L -> {
                            internalStopConfigQuota = 0
                            internalStopIdleQuota = 1
                        }
                        else -> {
                            if (engineGateway.mayEmitAsyncStopConfig) {
                                internalStopConfigQuota = 1
                                internalStopIdleQuota = 2
                            } else {
                                internalStopConfigQuota = 0
                                internalStopIdleQuota = 1
                            }
                        }
                    }
                    opId
                }
            } else {
                0L
            }
            ModeSwitchPrep(switchEpoch, effectiveStatus, switchOpId)
        }

        syncConfigToUiState()
        if (prep == null) {
            return
        }

        val (switchEpoch, prevStatus, switchOpId) = prep
        if (switchOpId == ABORTED_BEFORE_INTERNAL_STOP_OP_ID) {
            if (newMode == MockMode.LSPOSED_ONLY) {
                withContext(ioDispatcher) { runCatching { stopRootProvider() } }
            }
            emitAbortedSwitchSnackbar(newMode)
            return
        }
        if (switchOpId == 0L) {
            if (newMode == MockMode.LSPOSED_ONLY) {
                withContext(ioDispatcher) { runCatching { stopRootProvider() } }
            }
            emitEvent(MainUiEvent.ShowSnackbar("运行模式已切换为 ${modeLabel(newMode)}"))
            return
        }

        try {
            val settled = withTimeoutOrNull(2000L) {
                engineGateway.runtimeState.first {
                    it.status is MockRunStatus.Idle || it.status is MockRunStatus.Error
                }
            }
            synchronized(lifecycleLock) {
                isInternalModeSwitchStopping = false
                if (settled != null && settled.status is MockRunStatus.Idle) {
                    lastInternalSwitchIdleState = settled
                }
                auditConfigLocked()
            }
            delay(40L)
            if (newMode == MockMode.LSPOSED_ONLY) {
                withContext(ioDispatcher) { runCatching { stopRootProvider() } }
            }

            when (prevStatus) {
                is MockRunStatus.SingleRunning -> {
                    val restarted = synchronized(lifecycleLock) {
                        val preStartCfg = configRepository.currentConfig
                        val preStartSeq = configRepository.engineDisableSeq
                        auditConfigLocked(preStartCfg, preStartSeq)
                        if (engineGateway.runtimeState.value.status is MockRunStatus.Error &&
                            desiredEngineState !is DesiredEngineState.Stopped
                        ) {
                            markExternalStopLocked()
                        }
                        if (lifecycleEpoch.get() != switchEpoch ||
                            desiredEngineState is DesiredEngineState.Stopped ||
                            desiredEngineState is DesiredEngineState.CruisePaused
                        ) {
                            false
                        } else {
                            val latestPoint = _uiState.value.selectedPoint
                            desiredEngineState = DesiredEngineState.Single(latestPoint)
                            pendingVmStopConfigQuota = 0
                            pendingVmStopIdleQuota = 0
                            clearInternalStopWindowLocked(switchOpId)
                            val latestConfig = preStartCfg.copy(
                                mode = newMode,
                                enabled = true,
                                latitude = latestPoint.latitude,
                                longitude = latestPoint.longitude,
                                altitude = latestPoint.altitude,
                                accuracy = latestPoint.accuracy
                            )
                            engineGateway.startSingleMock(latestPoint, latestConfig)
                            val postStartCfg = configRepository.currentConfig
                            val postStartSeq = configRepository.engineDisableSeq
                            if (!postStartCfg.enabled || postStartSeq > preStartSeq) {
                                markExternalStopLocked()
                                val seqBeforeRollbackStop = postStartSeq
                                engineGateway.stopMock()
                                val rollbackCfg = configRepository.currentConfig
                                val seqAfterRollbackStop = configRepository.engineDisableSeq
                                lastAuditedConfig = rollbackCfg
                                lastAuditedEngineDisableSeq = seqAfterRollbackStop
                                if (seqAfterRollbackStop == seqBeforeRollbackStop &&
                                    engineGateway.mayEmitAsyncStopConfig
                                ) {
                                    pendingVmStopConfigQuota++
                                }
                                pendingVmStopIdleQuota++
                                lastInternalRestartConfigVersion = -1L
                                false
                            } else {
                                lastAuditedConfig = postStartCfg
                                lastAuditedEngineDisableSeq = postStartSeq
                                lastInternalRestartConfigVersion =
                                    if (postStartCfg.enabled) postStartCfg.version else -1L
                                true
                            }
                        }
                    }
                    syncConfigToUiState()
                    if (restarted) {
                        emitEvent(MainUiEvent.ShowSnackbar("已切换至 ${modeLabel(newMode)}，并重新启动单点模拟"))
                    } else {
                        emitAbortedSwitchSnackbar(newMode)
                    }
                }
                is MockRunStatus.CruiseRunning -> {
                    val outcome = synchronized(lifecycleLock) {
                        val preAuditCfg = configRepository.currentConfig
                        val preAuditSeq = configRepository.engineDisableSeq
                        auditConfigLocked(preAuditCfg, preAuditSeq)
                        if (engineGateway.runtimeState.value.status is MockRunStatus.Error &&
                            desiredEngineState !is DesiredEngineState.Stopped
                        ) {
                            markExternalStopLocked()
                        }
                        if (lifecycleEpoch.get() != switchEpoch ||
                            desiredEngineState is DesiredEngineState.Stopped ||
                            desiredEngineState is DesiredEngineState.CruisePaused
                        ) {
                            CruiseSwitchRestartOutcome.ABORTED_BY_LIFECYCLE
                        } else {
                            val latestState = _uiState.value
                            val latestWaypoints = latestState.draftWaypoints
                            if (latestWaypoints.isEmpty()) {
                                desiredEngineState = DesiredEngineState.Stopped
                                CruiseSwitchRestartOutcome.EMPTY_WAYPOINTS
                            } else {
                                val latestCruiseConfig = buildCruiseConfigSafe(latestState)
                                val latestRoute = PlannedRoute(
                                    id = latestState.draftRouteId ?: "draft_route",
                                    name = latestState.draftRouteName.ifBlank { "自定义巡航路线" },
                                    waypoints = latestWaypoints
                                )
                                val preStartCfg = configRepository.currentConfig
                                val preStartSeq = configRepository.engineDisableSeq
                                auditConfigLocked(preStartCfg, preStartSeq)
                                if (lifecycleEpoch.get() != switchEpoch ||
                                    desiredEngineState is DesiredEngineState.Stopped ||
                                    desiredEngineState is DesiredEngineState.CruisePaused
                                ) {
                                    CruiseSwitchRestartOutcome.ABORTED_BY_LIFECYCLE
                                } else {
                                    desiredEngineState = DesiredEngineState.CruiseRunning
                                    pendingVmStopConfigQuota = 0
                                    pendingVmStopIdleQuota = 0
                                    clearInternalStopWindowLocked(switchOpId)
                                    val latestConfig = preStartCfg.copy(
                                        mode = newMode,
                                        enabled = true,
                                        speed = latestCruiseConfig.speedMps.toFloat()
                                    )
                                    engineGateway.startCruise(latestRoute, latestCruiseConfig, latestConfig)
                                    val postStartCfg = configRepository.currentConfig
                                    val postStartSeq = configRepository.engineDisableSeq
                                    if (!postStartCfg.enabled || postStartSeq > preStartSeq) {
                                        markExternalStopLocked()
                                        val seqBeforeRollbackStop = postStartSeq
                                        engineGateway.stopMock()
                                        val rollbackCfg = configRepository.currentConfig
                                        val seqAfterRollbackStop = configRepository.engineDisableSeq
                                        lastAuditedConfig = rollbackCfg
                                        lastAuditedEngineDisableSeq = seqAfterRollbackStop
                                        if (seqAfterRollbackStop == seqBeforeRollbackStop &&
                                            engineGateway.mayEmitAsyncStopConfig
                                        ) {
                                            pendingVmStopConfigQuota++
                                        }
                                        pendingVmStopIdleQuota++
                                        lastInternalRestartConfigVersion = -1L
                                        CruiseSwitchRestartOutcome.ABORTED_BY_LIFECYCLE
                                    } else {
                                        lastAuditedConfig = postStartCfg
                                        lastAuditedEngineDisableSeq = postStartSeq
                                        lastInternalRestartConfigVersion =
                                            if (postStartCfg.enabled) postStartCfg.version else -1L
                                        CruiseSwitchRestartOutcome.RESTARTED
                                    }
                                }
                            }
                        }
                    }
                    syncConfigToUiState()
                    when (outcome) {
                        CruiseSwitchRestartOutcome.RESTARTED ->
                            emitEvent(MainUiEvent.ShowSnackbar("已切换至 ${modeLabel(newMode)}，并重新启动巡航"))
                        CruiseSwitchRestartOutcome.EMPTY_WAYPOINTS ->
                            emitEvent(MainUiEvent.ShowSnackbar("已切换至 ${modeLabel(newMode)} 并清理旧模拟驱动"))
                        CruiseSwitchRestartOutcome.ABORTED_BY_LIFECYCLE ->
                            emitAbortedSwitchSnackbar(newMode)
                    }
                }
                is MockRunStatus.CruisePaused -> {
                    val aborted = synchronized(lifecycleLock) {
                        auditConfigLocked()
                        desiredEngineState = DesiredEngineState.Stopped
                        lifecycleEpoch.get() != switchEpoch
                    }
                    if (aborted) {
                        emitAbortedSwitchSnackbar(newMode)
                    } else {
                        emitEvent(MainUiEvent.ShowSnackbar("已切换至 ${modeLabel(newMode)} 并清理旧驱动，请重新启动巡航"))
                    }
                }
                is MockRunStatus.Idle, is MockRunStatus.Error -> {
                    emitEvent(MainUiEvent.ShowSnackbar("运行模式已切换为 ${modeLabel(newMode)}"))
                }
            }
        } finally {
            synchronized(lifecycleLock) {
                clearInternalStopWindowLocked(switchOpId)
            }
        }
    }

    private fun executeQueuedStartSingle(action: PendingControlAction.StartSingle) {
        val startedPoint = synchronized(lifecycleLock) {
            auditConfigLocked()
            if (lifecycleEpoch.get() != action.epoch) {
                return@synchronized null
            }
            val latestPoint = _uiState.value.selectedPoint
            executeStartSingleLocked(latestPoint)
            latestPoint
        }
        if (startedPoint != null) {
            syncConfigToUiState()
            emitEvent(MainUiEvent.AnimateMapToPoint(startedPoint))
        }
    }

    private fun executeQueuedStartCruise(action: PendingControlAction.StartCruise) {
        val startedRoute = synchronized(lifecycleLock) {
            auditConfigLocked()
            if (lifecycleEpoch.get() != action.epoch) {
                return@synchronized null
            }
            val state = _uiState.value
            if (state.draftWaypoints.isEmpty()) {
                return@synchronized null
            }
            executeStartCruiseLocked(state)
        }
        if (startedRoute != null) {
            syncConfigToUiState()
            emitEvent(MainUiEvent.FitRouteOnMap(startedRoute.waypoints))
        }
    }

    private fun emitAbortedSwitchSnackbar(newMode: MockMode) {
        val msg = if (engineGateway.runtimeState.value.status is MockRunStatus.Idle) {
            "运行模式已切换为 ${modeLabel(newMode)}，当前保持停止"
        } else {
            "运行模式已切换为 ${modeLabel(newMode)}"
        }
        emitEvent(MainUiEvent.ShowSnackbar(msg))
    }

    fun onCoordinateTextChanged(
        latText: String = _uiState.value.latInput,
        lonText: String = _uiState.value.lonInput,
        altText: String = _uiState.value.altInput,
        accuracyText: String = _uiState.value.accuracyInput
    ) {
        _uiState.update {
            it.copy(
                latInput = latText,
                lonInput = lonText,
                altInput = altText,
                accuracyInput = accuracyText,
                latError = null,
                lonError = null,
                altError = null,
                accuracyError = null
            )
        }
    }

    /**
     * 仅解析并校验坐标输入（不提前写盘或发广播），供权限检查前置验证使用。
     */
    private fun parseValidatedInputPoint(
        latText: String = _uiState.value.latInput,
        lonText: String = _uiState.value.lonInput,
        altText: String = _uiState.value.altInput,
        accuracyText: String = _uiState.value.accuracyInput
    ): GeoPoint? {
        val parsed = validateCoordinateInputs(latText, lonText, altText, accuracyText) ?: return null
        val (rawLat, rawLon, alt, acc) = parsed
        val inputType = _uiState.value.inputCoordinateType
        val rawPoint = GeoPoint(
            latitude = rawLat,
            longitude = rawLon,
            altitude = alt,
            accuracy = acc
        )
        val wgsPoint = convertTypeToWgs(rawPoint, inputType)
        if (!GeoPoint.isValid(wgsPoint.latitude, wgsPoint.longitude)) {
            _uiState.update { it.copy(latError = "坐标转换后超出有效范围") }
            return null
        }
        return wgsPoint
    }

    /**
     * 校验并应用用户输入的坐标（按 inputCoordinateType 统一转回权威 WGS-84），绝不因非法输入构造 GeoPoint 崩溃。
     */
    fun applyCoordinateInputs(
        latText: String = _uiState.value.latInput,
        lonText: String = _uiState.value.lonInput,
        altText: String = _uiState.value.altInput,
        accuracyText: String = _uiState.value.accuracyInput,
        animateMap: Boolean = true
    ): GeoPoint? {
        val wgsPoint = parseValidatedInputPoint(latText, lonText, altText, accuracyText) ?: return null
        val inputType = _uiState.value.inputCoordinateType

        _uiState.update {
            it.copy(
                selectedPoint = wgsPoint,
                latInput = latText.trim(),
                lonInput = lonText.trim(),
                altInput = altText.trim(),
                accuracyInput = accuracyText.trim(),
                latError = null,
                lonError = null,
                altError = null,
                accuracyError = null
            )
        }

        updateConfigAndBroadcast {
            it.copy(
                latitude = wgsPoint.latitude,
                longitude = wgsPoint.longitude,
                altitude = wgsPoint.altitude,
                accuracy = wgsPoint.accuracy,
                coordinateType = inputType
            )
        }
        synchronized(lifecycleLock) {
            if (desiredEngineState is DesiredEngineState.Single) {
                desiredEngineState = DesiredEngineState.Single(wgsPoint)
            }
        }
        requestReverseGeocode(wgsPoint)
        if (animateMap) {
            emitEvent(MainUiEvent.AnimateMapToPoint(wgsPoint))
        }
        val canUpdateSingle = _uiState.value.tapMapUpdatesRunningSingle &&
            synchronized(lifecycleLock) {
                desiredEngineState is DesiredEngineState.Single &&
                    engineGateway.runtimeState.value.status is MockRunStatus.SingleRunning
            }
        if (canUpdateSingle) {
            engineGateway.updateSingleTarget(wgsPoint)
        }
        return wgsPoint
    }

    fun onMapTapped(wgsPoint: GeoPoint) {
        selectPointOnMap(wgsPoint, animate = false, sourceLabel = "地图选点")
    }

    fun onPinDragged(wgsPoint: GeoPoint) {
        selectPointOnMap(wgsPoint, animate = false, sourceLabel = "拖拽选点")
    }

    private fun selectPointOnMap(wgsPoint: GeoPoint, animate: Boolean, sourceLabel: String) {
        if (!GeoPoint.isValid(wgsPoint.latitude, wgsPoint.longitude)) return
        val currentAcc = configRepository.currentConfig.accuracy.coerceAtLeast(1.0f)
        val currentAlt = _uiState.value.selectedPoint.altitude
        val enriched = GeoPoint(
            latitude = wgsPoint.latitude,
            longitude = wgsPoint.longitude,
            altitude = if (wgsPoint.altitude != 0.0) wgsPoint.altitude else currentAlt,
            accuracy = currentAcc
        )
        val display = convertWgsToType(enriched, _uiState.value.inputCoordinateType)
        _uiState.update {
            it.copy(
                selectedPoint = enriched,
                selectedAddressLabel = sourceLabel,
                latInput = MainUiState.formatCoord(display.latitude),
                lonInput = MainUiState.formatCoord(display.longitude),
                altInput = MainUiState.formatPlainDouble(enriched.altitude, 1),
                latError = null,
                lonError = null
            )
        }
        updateConfigAndBroadcast {
            it.copy(
                latitude = enriched.latitude,
                longitude = enriched.longitude,
                altitude = enriched.altitude
            )
        }
        synchronized(lifecycleLock) {
            if (desiredEngineState is DesiredEngineState.Single) {
                desiredEngineState = DesiredEngineState.Single(enriched)
            }
        }
        requestReverseGeocode(enriched)
        if (animate) {
            emitEvent(MainUiEvent.AnimateMapToPoint(enriched))
        }
        val canUpdateSingle = _uiState.value.tapMapUpdatesRunningSingle &&
            synchronized(lifecycleLock) {
                desiredEngineState is DesiredEngineState.Single &&
                    engineGateway.runtimeState.value.status is MockRunStatus.SingleRunning
            }
        if (canUpdateSingle) {
            engineGateway.updateSingleTarget(enriched)
        }
    }

    private fun requestReverseGeocode(wgsPoint: GeoPoint) {
        reverseGeocodeJob?.cancel()
        reverseGeocodeJob = viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    geocodingService.reverseGeocode(wgsPoint.latitude, wgsPoint.longitude).getOrNull()
                }
            }.getOrNull()
            if (result != null) {
                val label = buildString {
                    append(result.name)
                    if (result.address.isNotBlank() && result.address != result.name) {
                        append(" · ").append(result.address)
                    }
                    if (result.source in OFFLINE_SOURCES) {
                        append("（离线参考）")
                    }
                }
                _uiState.update { it.copy(selectedAddressLabel = label) }
            }
        }
    }

    fun onStartSingleClicked() {
        // 先仅校验坐标合法性，权限未通过前不提前写盘或发送广播
        parseValidatedInputPoint() ?: return
        val perm = permissionProvider.check()
        if (!perm.hasLocationPermission) {
            val needNotif = !perm.hasNotificationPermission
            if (needNotif) {
                hasAskedNotificationPermission = true
            }
            pendingPermissionAction = PendingPermissionAction.START_SINGLE
            emitEvent(
                MainUiEvent.RequestPermissions(
                    needLocation = true,
                    needNotification = needNotif
                )
            )
            return
        }
        if (!perm.hasNotificationPermission && !hasAskedNotificationPermission) {
            hasAskedNotificationPermission = true
            pendingPermissionAction = PendingPermissionAction.START_SINGLE
            emitEvent(
                MainUiEvent.RequestPermissions(
                    needLocation = false,
                    needNotification = true
                )
            )
            return
        }
        val point = applyCoordinateInputs(animateMap = false) ?: return
        startSingleInternal(point)
    }

    fun onStartCruiseClicked() {
        val state = _uiState.value
        if (state.draftWaypoints.isEmpty()) {
            emitEvent(MainUiEvent.ShowSnackbar("请先至少添加 1 个航点，再启动路线巡航"))
            return
        }
        val speed = state.effectiveCruiseSpeedMps
        if (!speed.isFinite() || speed <= 0.0) {
            emitEvent(MainUiEvent.ShowSnackbar("巡航速度必须为大于 0 的有效数值"))
            return
        }
        val perm = permissionProvider.check()
        if (!perm.hasLocationPermission) {
            val needNotif = !perm.hasNotificationPermission
            if (needNotif) {
                hasAskedNotificationPermission = true
            }
            pendingPermissionAction = PendingPermissionAction.START_CRUISE
            emitEvent(
                MainUiEvent.RequestPermissions(
                    needLocation = true,
                    needNotification = needNotif
                )
            )
            return
        }
        if (!perm.hasNotificationPermission && !hasAskedNotificationPermission) {
            hasAskedNotificationPermission = true
            pendingPermissionAction = PendingPermissionAction.START_CRUISE
            emitEvent(
                MainUiEvent.RequestPermissions(
                    needLocation = false,
                    needNotification = true
                )
            )
            return
        }
        startCruiseInternal()
    }

    fun onPermissionsResult(locationGranted: Boolean, notificationGranted: Boolean) {
        refreshEnvironment()
        val action = pendingPermissionAction
        pendingPermissionAction = null

        val perm = permissionProvider.check()
        val effectiveLocation = locationGranted || perm.hasLocationPermission
        if (!effectiveLocation) {
            emitEvent(MainUiEvent.ShowSnackbar("未授予定位权限，无法启动前台位置模拟服务"))
            return
        }
        if (!notificationGranted && !perm.hasNotificationPermission) {
            emitEvent(MainUiEvent.ShowSnackbar("未授予通知权限，前台服务通知栏提示可能受限"))
        }

        when (action) {
            PendingPermissionAction.START_SINGLE -> {
                val point = applyCoordinateInputs(animateMap = false) ?: _uiState.value.selectedPoint
                startSingleInternal(point)
            }
            PendingPermissionAction.START_CRUISE -> {
                if (_uiState.value.draftWaypoints.isNotEmpty()) {
                    startCruiseInternal()
                }
            }
            null -> Unit
        }
    }

    private fun startSingleInternal(point: GeoPoint) {
        val executedImmediately = synchronized(lifecycleLock) {
            settleTerminalStateBeforeEnqueueLocked()
            val nextEpoch = lifecycleEpoch.incrementAndGet()
            if (isDrainingControlQueue || pendingControlQueue.isNotEmpty()) {
                removePendingStartActionsLocked()
                pendingControlQueue.addLast(PendingControlAction.StartSingle(point, nextEpoch))
                false
            } else {
                executeStartSingleLocked(point)
                true
            }
        }
        if (executedImmediately) {
            syncConfigToUiState()
            emitEvent(MainUiEvent.AnimateMapToPoint(point))
        }
    }

    private fun executeStartSingleLocked(point: GeoPoint) {
        auditConfigLocked()
        pendingVmStopConfigQuota = 0
        pendingVmStopIdleQuota = 0
        desiredEngineState = DesiredEngineState.Single(point)
        val latest = updateConfigAndBroadcastLocked(ignorePreUpdateExternalDisable = true) {
            it.copy(
                enabled = true,
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitude,
                accuracy = point.accuracy
            )
        }
        val seqAfterConfigWrite = lastAuditedEngineDisableSeq
        engineGateway.startSingleMock(point, latest)
        val postStartCfg = configRepository.currentConfig
        val postStartSeq = configRepository.engineDisableSeq
        if (!postStartCfg.enabled || postStartSeq > seqAfterConfigWrite) {
            markExternalStopLocked()
            if (postStartCfg.enabled) {
                val seqBeforeRollbackStop = postStartSeq
                engineGateway.stopMock()
                val rollbackCfg = configRepository.currentConfig
                val seqAfterRollbackStop = configRepository.engineDisableSeq
                lastAuditedConfig = rollbackCfg
                lastAuditedEngineDisableSeq = seqAfterRollbackStop
                if (seqAfterRollbackStop == seqBeforeRollbackStop &&
                    engineGateway.mayEmitAsyncStopConfig
                ) {
                    pendingVmStopConfigQuota++
                }
                pendingVmStopIdleQuota++
            } else {
                lastAuditedConfig = postStartCfg
                lastAuditedEngineDisableSeq = postStartSeq
            }
            lastInternalRestartConfigVersion = -1L
        } else {
            lastAuditedConfig = postStartCfg
            lastAuditedEngineDisableSeq = postStartSeq
            lastInternalRestartConfigVersion = if (postStartCfg.enabled) postStartCfg.version else -1L
        }
    }

    private fun startCruiseInternal() {
        val state = _uiState.value
        if (state.draftWaypoints.isEmpty()) return
        val startedRoute = synchronized(lifecycleLock) {
            settleTerminalStateBeforeEnqueueLocked()
            val nextEpoch = lifecycleEpoch.incrementAndGet()
            if (isDrainingControlQueue || pendingControlQueue.isNotEmpty()) {
                removePendingStartActionsLocked()
                pendingControlQueue.addLast(PendingControlAction.StartCruise(nextEpoch))
                null
            } else {
                executeStartCruiseLocked(state)
            }
        }
        if (startedRoute != null) {
            syncConfigToUiState()
            emitEvent(MainUiEvent.FitRouteOnMap(startedRoute.waypoints))
        }
    }

    private fun executeStartCruiseLocked(state: MainUiState): PlannedRoute {
        val route = PlannedRoute(
            id = state.draftRouteId ?: "active_route",
            name = state.draftRouteName.ifBlank { "自定义巡航路线" },
            waypoints = state.draftWaypoints
        )
        val cruiseConfig = buildCruiseConfigSafe(state)
        auditConfigLocked()
        pendingVmStopConfigQuota = 0
        pendingVmStopIdleQuota = 0
        desiredEngineState = DesiredEngineState.CruiseRunning
        val baseConfig = updateConfigAndBroadcastLocked(ignorePreUpdateExternalDisable = true) {
            it.copy(
                enabled = true,
                speed = cruiseConfig.speedMps.toFloat()
            )
        }
        val seqAfterConfigWrite = lastAuditedEngineDisableSeq
        engineGateway.startCruise(route, cruiseConfig, baseConfig)
        val postStartCfg = configRepository.currentConfig
        val postStartSeq = configRepository.engineDisableSeq
        if (!postStartCfg.enabled || postStartSeq > seqAfterConfigWrite) {
            markExternalStopLocked()
            if (postStartCfg.enabled) {
                val seqBeforeRollbackStop = postStartSeq
                engineGateway.stopMock()
                val rollbackCfg = configRepository.currentConfig
                val seqAfterRollbackStop = configRepository.engineDisableSeq
                lastAuditedConfig = rollbackCfg
                lastAuditedEngineDisableSeq = seqAfterRollbackStop
                if (seqAfterRollbackStop == seqBeforeRollbackStop &&
                    engineGateway.mayEmitAsyncStopConfig
                ) {
                    pendingVmStopConfigQuota++
                }
                pendingVmStopIdleQuota++
            } else {
                lastAuditedConfig = postStartCfg
                lastAuditedEngineDisableSeq = postStartSeq
            }
            lastInternalRestartConfigVersion = -1L
        } else {
            lastAuditedConfig = postStartCfg
            lastAuditedEngineDisableSeq = postStartSeq
            lastInternalRestartConfigVersion = if (postStartCfg.enabled) postStartCfg.version else -1L
        }
        return route
    }

    fun restartCruiseFromCompleted() {
        onStartCruiseClicked()
    }

    fun pauseCruise() {
        synchronized(lifecycleLock) {
            lifecycleEpoch.incrementAndGet()
            removePendingStartActionsLocked()
            if (desiredEngineState is DesiredEngineState.CruiseRunning) {
                desiredEngineState = DesiredEngineState.CruisePaused
            }
            engineGateway.pauseCruise()
        }
    }

    fun resumeCruise() {
        synchronized(lifecycleLock) {
            auditConfigLocked()
            lifecycleEpoch.incrementAndGet()
            val rawStatus = engineGateway.runtimeState.value.status
            if (desiredEngineState is DesiredEngineState.CruisePaused ||
                rawStatus is MockRunStatus.CruisePaused
            ) {
                desiredEngineState = DesiredEngineState.CruiseRunning
            }
            engineGateway.resumeCruise()
        }
    }

    fun stopMock() {
        synchronized(lifecycleLock) {
            auditConfigLocked()
            lifecycleEpoch.incrementAndGet()
            removePendingStartActionsLocked()
            desiredEngineState = DesiredEngineState.Stopped
            val seqBeforeStop = lastAuditedEngineDisableSeq
            engineGateway.stopMock()
            val afterCfg = configRepository.currentConfig
            val seqAfterStop = configRepository.engineDisableSeq
            lastAuditedConfig = afterCfg
            lastAuditedEngineDisableSeq = seqAfterStop
            if (seqAfterStop == seqBeforeStop && engineGateway.mayEmitAsyncStopConfig) {
                pendingVmStopConfigQuota++
            }
            pendingVmStopIdleQuota++
        }
        syncConfigToUiState()
    }

    fun centerMapOnCurrentTarget() {
        val state = _uiState.value
        val target = state.runtimeState.currentPoint ?: state.selectedPoint
        emitEvent(MainUiEvent.AnimateMapToPoint(target))
    }

    // region 搜索流程
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    isSearchOfflineFallback = false,
                    searchInfoMessage = null
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(searchDebounceMs)
            executeSearch(trimmed)
        }
    }

    fun submitSearchNow() {
        val trimmed = _uiState.value.searchQuery.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    isSearchOfflineFallback = false,
                    searchInfoMessage = null
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            executeSearch(trimmed)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = "",
                isSearching = false,
                searchResults = emptyList(),
                isSearchOfflineFallback = false,
                searchInfoMessage = null
            )
        }
    }

    private suspend fun executeSearch(query: String) {
        _uiState.update { it.copy(isSearching = true, searchInfoMessage = null) }
        try {
            val result = withContext(ioDispatcher) {
                geocodingService.search(query)
            }
            val list = result.getOrElse { emptyList() }
            val hasOfflineFallback = list.any { it.source in OFFLINE_SOURCES }
            val infoMsg = when {
                list.isEmpty() -> "未找到匹配地点，请尝试更换关键词或直接输入经纬度"
                hasOfflineFallback -> "网络受限或无在线匹配，已切换内置离线地标库兜底"
                else -> null
            }
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = list,
                    isSearchOfflineFallback = hasOfflineFallback,
                    searchInfoMessage = infoMsg
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchResults = emptyList(),
                    isSearchOfflineFallback = false,
                    searchInfoMessage = "搜索异常：${e.message ?: "请稍后重试"}"
                )
            }
        }
    }

    fun selectSearchResult(item: GeocodingResult) {
        val wgsPoint = item.toGeoPoint()
        val label = buildString {
            append(item.name)
            if (item.address.isNotBlank() && item.address != item.name) {
                append(" · ").append(item.address)
            }
            if (item.source in OFFLINE_SOURCES) {
                append("（离线兜底）")
            }
        }
        val display = convertWgsToType(wgsPoint, _uiState.value.inputCoordinateType)
        _uiState.update {
            it.copy(
                selectedPoint = wgsPoint,
                selectedAddressLabel = label,
                latInput = MainUiState.formatCoord(display.latitude),
                lonInput = MainUiState.formatCoord(display.longitude),
                latError = null,
                lonError = null,
                searchResults = emptyList(),
                isSearchOfflineFallback = false,
                searchInfoMessage = null
            )
        }
        updateConfigAndBroadcast {
            it.copy(
                latitude = wgsPoint.latitude,
                longitude = wgsPoint.longitude
            )
        }
        synchronized(lifecycleLock) {
            if (desiredEngineState is DesiredEngineState.Single) {
                desiredEngineState = DesiredEngineState.Single(wgsPoint)
            }
        }
        emitEvent(MainUiEvent.AnimateMapToPoint(wgsPoint))
        val canUpdateSingle = _uiState.value.tapMapUpdatesRunningSingle &&
            synchronized(lifecycleLock) {
                desiredEngineState is DesiredEngineState.Single &&
                    engineGateway.runtimeState.value.status is MockRunStatus.SingleRunning
            }
        if (canUpdateSingle) {
            engineGateway.updateSingleTarget(wgsPoint)
        }
    }
    // endregion

    // region 多航点路线编辑与巡航参数
    fun updateDraftRouteName(name: String) {
        _uiState.update { it.copy(draftRouteName = name) }
    }

    fun appendSelectedPointToRoute() {
        val state = _uiState.value
        val name = state.selectedAddressLabel
            .takeIf { it.isNotBlank() && it != "点击地图或搜索地点以选点" }
            ?: "航点 ${state.draftWaypoints.size + 1}"
        appendWaypoint(state.selectedPoint, name)
    }

    fun appendWaypoint(point: GeoPoint, name: String = "") {
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            emitEvent(MainUiEvent.ShowSnackbar("无效坐标，无法添加为航点"))
            return
        }
        _uiState.update { state ->
            val label = name.ifBlank { "航点 ${state.draftWaypoints.size + 1}" }
            val nextList = state.draftWaypoints + Waypoint(point = point, name = label)
            state.copy(draftWaypoints = nextList)
        }
    }

    fun moveWaypointUp(index: Int) {
        _uiState.update { state ->
            if (index <= 0 || index >= state.draftWaypoints.size) return@update state
            val mutable = state.draftWaypoints.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index - 1, item)
            state.copy(draftWaypoints = mutable)
        }
    }

    fun moveWaypointDown(index: Int) {
        _uiState.update { state ->
            if (index < 0 || index >= state.draftWaypoints.lastIndex) return@update state
            val mutable = state.draftWaypoints.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index + 1, item)
            state.copy(draftWaypoints = mutable)
        }
    }

    fun removeWaypointAt(index: Int) {
        _uiState.update { state ->
            if (index !in state.draftWaypoints.indices) return@update state
            val mutable = state.draftWaypoints.toMutableList()
            mutable.removeAt(index)
            state.copy(draftWaypoints = mutable)
        }
    }

    fun clearDraftWaypoints() {
        _uiState.update { it.copy(draftWaypoints = emptyList(), draftRouteId = null) }
    }

    fun fitDraftRouteOnMap() {
        val waypoints = _uiState.value.draftWaypoints
        if (waypoints.isEmpty()) {
            emitEvent(MainUiEvent.ShowSnackbar("当前没有可预览的航点"))
            return
        }
        emitEvent(MainUiEvent.FitRouteOnMap(waypoints))
    }

    fun saveDraftRoute(customName: String? = null) {
        val state = _uiState.value
        val name = (customName ?: state.draftRouteName).trim()
        if (name.isEmpty()) {
            emitEvent(MainUiEvent.ShowSnackbar("请输入有效的路线名称"))
            return
        }
        if (state.draftWaypoints.isEmpty()) {
            emitEvent(MainUiEvent.ShowSnackbar("航点为空，请至少添加 1 个航点后再保存"))
            return
        }
        viewModelScope.launch {
            val toSave = PlannedRoute(
                id = state.draftRouteId ?: "",
                name = name,
                waypoints = state.draftWaypoints
            )
            val result = withContext(ioDispatcher) {
                routeRepository.saveRoute(toSave)
            }
            result.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        draftRouteId = saved.id,
                        draftRouteName = saved.name
                    )
                }
                emitEvent(MainUiEvent.ShowSnackbar("路线「${saved.name}」已保存（${saved.waypoints.size} 个航点）"))
            }.onFailure { err ->
                emitEvent(MainUiEvent.ShowSnackbar("保存路线失败：${err.message ?: "未知错误"}"))
            }
        }
    }

    fun loadRouteToDraft(route: PlannedRoute) {
        _uiState.update {
            it.copy(
                draftRouteId = route.id,
                draftRouteName = route.name,
                draftWaypoints = route.waypoints,
                activeTab = ControlPanelTab.CRUISE
            )
        }
        if (route.waypoints.isNotEmpty()) {
            emitEvent(MainUiEvent.FitRouteOnMap(route.waypoints))
        }
        emitEvent(MainUiEvent.ShowSnackbar("已加载路线「${route.name}」（${route.waypoints.size} 个航点）"))
    }

    fun renameSavedRoute(id: String, newName: String) {
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                routeRepository.renameRoute(id, newName)
            }
            result.onSuccess { updated ->
                _uiState.update { state ->
                    if (state.draftRouteId == updated.id) {
                        state.copy(draftRouteName = updated.name)
                    } else {
                        state
                    }
                }
                emitEvent(MainUiEvent.RouteFeedback(true, "路线已重命名为「${updated.name}」"))
            }.onFailure { err ->
                emitEvent(MainUiEvent.RouteFeedback(false, "重命名失败：${err.message ?: "名称无效"}"))
            }
        }
    }

    fun deleteSavedRoute(id: String) {
        viewModelScope.launch {
            val removed = withContext(ioDispatcher) {
                routeRepository.deleteRoute(id)
            }
            if (removed) {
                _uiState.update { state ->
                    if (state.draftRouteId == id) state.copy(draftRouteId = null) else state
                }
                emitEvent(MainUiEvent.RouteFeedback(true, "路线已删除"))
            }
        }
    }

    fun resetSampleRoutes() {
        viewModelScope.launch {
            val res = withContext(ioDispatcher) {
                routeRepository.resetToDefaultSampleRoutes()
            }
            res.onSuccess { list ->
                emitEvent(MainUiEvent.RouteFeedback(true, "已恢复 ${list.size} 条默认示例路线"))
            }.onFailure { err ->
                emitEvent(MainUiEvent.RouteFeedback(false, "恢复示例路线失败：${err.message ?: "未知错误"}"))
            }
        }
    }

    fun selectSpeedPreset(preset: SpeedPreset) {
        _uiState.update { it.copy(cruiseSpeedPreset = preset) }
        val speed = if (preset == SpeedPreset.CUSTOM) {
            _uiState.value.customSpeedMps
        } else {
            preset.speedMps
        }
        val status = engineGateway.runtimeState.value.status
        if (status is MockRunStatus.CruiseRunning || status is MockRunStatus.CruisePaused) {
            engineGateway.updateSpeed(speed)
        }
    }

    fun updateCustomSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps <= 0.0 || speedMps > 200.0) {
            emitEvent(MainUiEvent.ShowSnackbar("自定义速度须在 0.1 ~ 200 m/s 之间"))
            return
        }
        _uiState.update {
            it.copy(
                cruiseSpeedPreset = SpeedPreset.CUSTOM,
                customSpeedMps = speedMps
            )
        }
        val status = engineGateway.runtimeState.value.status
        if (status is MockRunStatus.CruiseRunning || status is MockRunStatus.CruisePaused) {
            engineGateway.updateSpeed(speedMps)
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        _uiState.update { it.copy(cruiseLoopMode = loopMode) }
    }

    fun setSpeedNoiseEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enableSpeedNoise = enabled) }
    }

    fun setMicroDriftEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enableMicroDrift = enabled) }
        updateConfigAndBroadcast { it.copy(jitterEnabled = enabled) }
        val status = engineGateway.runtimeState.value.status
        if (status is MockRunStatus.SingleRunning ||
            status is MockRunStatus.CruiseRunning ||
            status is MockRunStatus.CruisePaused
        ) {
            engineGateway.updateJitter(enabled)
        }
    }
    // endregion

    // region 微抖动与反检测配置（立即写盘 + L2 显式广播）
    fun setSingleJitterEnabled(enabled: Boolean) {
        _uiState.update { it.copy(enableMicroDrift = enabled) }
        updateConfigAndBroadcast { it.copy(jitterEnabled = enabled) }
        val status = engineGateway.runtimeState.value.status
        if (status is MockRunStatus.SingleRunning ||
            status is MockRunStatus.CruiseRunning ||
            status is MockRunStatus.CruisePaused
        ) {
            engineGateway.updateJitter(enabled)
        }
    }

    fun updateJitterRadiusInput(radiusText: String): Boolean {
        _uiState.update { it.copy(jitterRadiusInput = radiusText, jitterRadiusError = null) }
        val parsed = radiusText.trim().toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed < 0.0 || parsed > 500.0) {
            _uiState.update { it.copy(jitterRadiusError = "请输入 0 ~ 500 米的有效半径") }
            return false
        }
        updateConfigAndBroadcast { it.copy(jitterRadiusMeters = parsed) }
        val status = engineGateway.runtimeState.value.status
        if (status is MockRunStatus.CruiseRunning || status is MockRunStatus.CruisePaused) {
            emitEvent(MainUiEvent.ShowSnackbar("微抖动半径已更新，将在下次启动/重新开始巡航时生效"))
        }
        return true
    }

    fun setAntiDetectCellEnabled(enabled: Boolean) {
        updateConfigAndBroadcast { it.copy(antiDetectCellEnabled = enabled) }
    }

    fun setAntiDetectWifiEnabled(enabled: Boolean) {
        updateConfigAndBroadcast { it.copy(antiDetectWifiEnabled = enabled) }
    }

    fun setAntiDetectGnssEnabled(enabled: Boolean) {
        updateConfigAndBroadcast { it.copy(antiDetectGnssEnabled = enabled) }
    }
    // endregion

    // region 收藏夹管理
    fun updateFavoriteSearch(query: String) {
        _uiState.update { state ->
            state.copy(
                favoriteSearchQuery = query,
                filteredFavorites = filterFavoritesLocal(state.favorites, query)
            )
        }
        viewModelScope.launch {
            val matched = withContext(ioDispatcher) {
                favoriteRepository.search(query)
            }
            if (_uiState.value.favoriteSearchQuery == query) {
                _uiState.update { it.copy(filteredFavorites = matched) }
            }
        }
    }

    fun addFavorite(
        name: String,
        address: String,
        point: GeoPoint = _uiState.value.selectedPoint,
        fromMainScreen: Boolean = false
    ) {
        fun emitFavResult(success: Boolean, message: String) {
            if (fromMainScreen) {
                emitEvent(MainUiEvent.ShowSnackbar(message))
            } else {
                emitEvent(MainUiEvent.FavoriteFeedback(success, message))
            }
        }
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            emitFavResult(false, "当前坐标无效，无法收藏")
            return
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                favoriteRepository.add(
                    name = name,
                    address = address,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitude = point.altitude,
                    coordinateType = CoordinateType.WGS84
                )
            }
            result.onSuccess { fav ->
                emitFavResult(true, "已收藏「${fav.name}」")
            }.onFailure { err ->
                emitFavResult(false, err.message ?: "收藏失败")
            }
        }
    }

    fun updateFavorite(id: String, name: String, address: String?, point: GeoPoint? = null) {
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                favoriteRepository.update(id = id, name = name, address = address, point = point)
            }
            result.onSuccess { fav ->
                emitEvent(MainUiEvent.FavoriteFeedback(true, "已更新「${fav.name}」"))
            }.onFailure { err ->
                emitEvent(MainUiEvent.FavoriteFeedback(false, err.message ?: "更新收藏失败"))
            }
        }
    }

    fun removeFavorite(id: String) {
        viewModelScope.launch {
            val ok = withContext(ioDispatcher) {
                favoriteRepository.remove(id)
            }
            if (ok) {
                emitEvent(MainUiEvent.FavoriteFeedback(true, "已删除收藏地点"))
            }
        }
    }

    fun resetDefaultFavorites() {
        viewModelScope.launch {
            val res = withContext(ioDispatcher) {
                favoriteRepository.resetToDefaults()
            }
            res.onSuccess { list ->
                emitEvent(MainUiEvent.FavoriteFeedback(true, "已恢复 ${list.size} 个默认常用地标"))
            }
        }
    }

    fun useFavoriteAsSingle(favorite: FavoriteLocation) {
        val point = favorite.toGeoPoint()
        val label = if (favorite.address.isNotBlank()) {
            "${favorite.name} · ${favorite.address}"
        } else {
            favorite.name
        }
        selectPointOnMap(point, animate = true, sourceLabel = label)
        _uiState.update { it.copy(activeTab = ControlPanelTab.SINGLE) }
        viewModelScope.launch(ioDispatcher) {
            favoriteRepository.markUsed(favorite.id)
        }
        emitEvent(MainUiEvent.ShowSnackbar("已选用收藏点「${favorite.name}」"))
    }

    fun appendFavoriteToRoute(favorite: FavoriteLocation) {
        val point = favorite.toGeoPoint()
        appendWaypoint(point, favorite.name)
        _uiState.update { it.copy(activeTab = ControlPanelTab.CRUISE) }
        viewModelScope.launch(ioDispatcher) {
            favoriteRepository.markUsed(favorite.id)
        }
        emitEvent(MainUiEvent.ShowSnackbar("已将「${favorite.name}」加入当前路线"))
    }
    // endregion

    fun copyPackageName(packageName: String) {
        emitEvent(MainUiEvent.CopyToClipboard("包名", packageName))
        emitEvent(MainUiEvent.ScopeGuideFeedback(true, "已复制包名：$packageName"))
    }

    private fun updateConfigAndBroadcast(transform: (MockConfig) -> MockConfig): MockConfig {
        val updated = synchronized(lifecycleLock) {
            updateConfigAndBroadcastLocked(transform = transform)
        }
        syncConfigToUiState()
        return updated
    }

    private fun updateConfigAndBroadcastLocked(
        ignorePreUpdateExternalDisable: Boolean = false,
        transform: (MockConfig) -> MockConfig
    ): MockConfig {
        auditConfigLocked()
        lateinit var beforeConfig: MockConfig
        var seqBeforeWrite = 0L
        val updated = configRepository.updateImmediate { current ->
            beforeConfig = current
            seqBeforeWrite = configRepository.engineDisableSeq
            transform(current)
        }

        if (!ignorePreUpdateExternalDisable) {
            auditConfigLocked(beforeConfig, seqBeforeWrite)
        }
        val selfDisableDelta =
            if (!updated.enabled && updated.targetPackages == beforeConfig.targetPackages) 1L else 0L
        lastAuditedConfig = updated
        lastAuditedEngineDisableSeq = seqBeforeWrite + selfDisableDelta
        if (ignorePreUpdateExternalDisable && !updated.enabled) {
            markExternalStopLocked()
        }
        onConfigChangedBroadcast(updated)
        return updated
    }

    private fun syncConfigToUiState() {
        val latest = configRepository.currentConfig
        _uiState.update { state ->
            if (latest.version >= state.config.version) {
                state.copy(
                    config = latest,
                    whitelistCount = latest.targetPackages.size
                )
            } else {
                state
            }
        }
    }

    private fun auditConfigLocked() {
        val cfg = configRepository.currentConfig
        val disableSeq = configRepository.engineDisableSeq
        auditConfigLocked(cfg, disableSeq)
    }

    private fun auditConfigLocked(cfg: MockConfig, disableSeq: Long) {
        val prev = lastAuditedConfig
        val isNewerConfig = isVersionStrictlyAfter(cfg.version, prev.version)
        val disableDelta = (disableSeq - lastAuditedEngineDisableSeq).coerceAtLeast(0L)

        if (!isNewerConfig) {
            if (disableDelta > 0L) {
                val refreshed = configRepository.currentConfig
                lastAuditedConfig = if (isVersionStrictlyAfter(refreshed.version, prev.version)) {
                    refreshed
                } else {
                    prev.copy(enabled = false)
                }
                lastAuditedEngineDisableSeq = disableSeq
                handleDisableEventsLocked(disableDelta)
            }
            return
        }

        lastAuditedConfig = cfg
        if (disableDelta > 0L) {
            lastAuditedEngineDisableSeq = disableSeq
            handleDisableEventsLocked(disableDelta)
            return
        }

        // 兜底：跨进程 reloadFromPrefs 在同一快照同时切换 targetPackages 且将 enabled 由 true 改为 false（若未递增 seq 时）
        if (prev.enabled && !cfg.enabled) {
            handleDisableEventsLocked(1L)
        }
    }

    private fun handleDisableEventsLocked(delta: Long) {
        var remaining = delta
        if (remaining <= 0L) return
        if (pendingVmStopConfigQuota > 0) {
            val used = minOf(remaining, pendingVmStopConfigQuota.toLong()).toInt()
            pendingVmStopConfigQuota -= used
            remaining -= used
        }
        if (remaining > 0L && activeInternalStopOpId != 0L && internalStopConfigQuota > 0) {
            val used = minOf(remaining, internalStopConfigQuota.toLong()).toInt()
            internalStopConfigQuota -= used
            remaining -= used
        }
        if (remaining > 0L) {
            markExternalStopLocked()
        }
    }

    private fun settleTerminalStateBeforeEnqueueLocked() {
        auditConfigLocked()
        val rawStatus = engineGateway.runtimeState.value.status
        if (rawStatus is MockRunStatus.Error && desiredEngineState !is DesiredEngineState.Stopped) {
            desiredEngineState = DesiredEngineState.Stopped
        }
    }

    private fun markExternalStopLocked() {
        lifecycleEpoch.incrementAndGet()
        desiredEngineState = DesiredEngineState.Stopped
        removePendingStartActionsLocked()
    }

    private fun handleObservedRuntimeStatusLocked(
        prev: MockRunStatus,
        runtime: MockRuntimeState
    ) {
        auditConfigLocked()
        val latestStatus = engineGateway.runtimeState.value.status
        val latestCfg = configRepository.currentConfig
        when (runtime.status) {
            is MockRunStatus.Error -> {
                if (latestStatus is MockRunStatus.Error &&
                    desiredEngineState !is DesiredEngineState.Stopped
                ) {
                    markExternalStopLocked()
                }
            }
            is MockRunStatus.Idle -> {
                when {
                    activeInternalStopOpId != 0L && internalStopIdleQuota > 0 -> {
                        internalStopIdleQuota--
                    }
                    pendingVmStopIdleQuota > 0 -> {
                        pendingVmStopIdleQuota--
                    }
                    runtime == lastInternalSwitchIdleState -> {
                        // 已知内部停机生成的同一帧 Idle，忽略
                    }
                    latestStatus is MockRunStatus.Idle -> {
                        val isStaleRestartIdle = latestCfg.enabled &&
                            latestCfg.version == lastInternalRestartConfigVersion &&
                            runtime.progressFraction < 1.0f
                        if (!isStaleRestartIdle && desiredEngineState !is DesiredEngineState.Stopped) {
                            lastObservedExternalIdleSyncMs = runtime.lastSyncTimeMs
                            markExternalStopLocked()
                        }
                    }
                }
            }
            is MockRunStatus.CruisePaused -> {
                if (!isInternalModeSwitchStopping &&
                    latestStatus is MockRunStatus.CruisePaused &&
                    desiredEngineState is DesiredEngineState.CruiseRunning
                ) {
                    lifecycleEpoch.incrementAndGet()
                    desiredEngineState = DesiredEngineState.CruisePaused
                }
            }
            is MockRunStatus.CruiseRunning -> {
                if (!isInternalModeSwitchStopping &&
                    latestStatus is MockRunStatus.CruiseRunning &&
                    desiredEngineState is DesiredEngineState.CruisePaused
                ) {
                    lifecycleEpoch.incrementAndGet()
                    desiredEngineState = DesiredEngineState.CruiseRunning
                }
            }
            is MockRunStatus.SingleRunning -> Unit
        }
    }

    private fun resolveEffectiveStatusLocked(
        actualStatus: MockRunStatus,
        currentCfg: MockConfig
    ): MockRunStatus {
        return when (desiredEngineState) {
            is DesiredEngineState.Stopped -> MockRunStatus.Idle
            is DesiredEngineState.CruisePaused -> {
                if (actualStatus is MockRunStatus.Idle || actualStatus is MockRunStatus.Error) {
                    actualStatus
                } else {
                    MockRunStatus.CruisePaused
                }
            }
            is DesiredEngineState.Single -> {
                if (actualStatus is MockRunStatus.Error ||
                    (actualStatus is MockRunStatus.Idle && !currentCfg.enabled)
                ) {
                    actualStatus
                } else {
                    MockRunStatus.SingleRunning
                }
            }
            is DesiredEngineState.CruiseRunning -> {
                if (actualStatus is MockRunStatus.Error ||
                    (actualStatus is MockRunStatus.Idle && !currentCfg.enabled)
                ) {
                    actualStatus
                } else {
                    MockRunStatus.CruiseRunning
                }
            }
        }
    }

    private fun removePendingStartActionsLocked() {
        pendingControlQueue.removeAll {
            it is PendingControlAction.StartSingle || it is PendingControlAction.StartCruise
        }
    }

    private fun clearInternalStopWindowLocked(opId: Long) {
        if (activeInternalStopOpId == opId) {
            activeInternalStopOpId = 0L
            internalStopConfigQuota = 0
            internalStopIdleQuota = 0
            isInternalModeSwitchStopping = false
        }
    }

    private fun validateCoordinateInputs(
        latText: String,
        lonText: String,
        altText: String,
        accuracyText: String
    ): Quadruple<Double, Double, Double, Float>? {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        val alt = altText.trim().toDoubleOrNull()
        val acc = accuracyText.trim().toFloatOrNull()

        var latErr: String? = null
        var lonErr: String? = null
        var altErr: String? = null
        var accErr: String? = null

        if (lat == null || !lat.isFinite() || lat !in -90.0..90.0) {
            latErr = "纬度须为 -90 ~ 90 之间的数字"
        }
        if (lon == null || !lon.isFinite() || lon !in -180.0..180.0) {
            lonErr = "经度须为 -180 ~ 180 之间的数字"
        }
        if (alt == null || !alt.isFinite() || alt !in -10000.0..100000.0) {
            altErr = "海拔须为有效数字 (-10000 ~ 100000)"
        }
        if (acc == null || !acc.isFinite() || acc <= 0f || acc > 5000f) {
            accErr = "精度须为大于 0 且不超过 5000 的数字"
        }

        if (latErr != null || lonErr != null || altErr != null || accErr != null) {
            _uiState.update {
                it.copy(
                    latError = latErr,
                    lonError = lonErr,
                    altError = altErr,
                    accuracyError = accErr
                )
            }
            return null
        }
        return Quadruple(lat!!, lon!!, alt!!, acc!!)
    }

    private fun buildCruiseConfigSafe(state: MainUiState): CruiseConfig {
        val safeSpeed = state.effectiveCruiseSpeedMps
            .takeIf { it.isFinite() && it > 0.0 }
            ?: SpeedPreset.WALK.speedMps
        val safeDriftRadius = configRepository.currentConfig.jitterRadiusMeters
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 1.5
        return CruiseConfig(
            speedMps = safeSpeed,
            loopMode = state.cruiseLoopMode,
            enableSpeedNoise = state.enableSpeedNoise,
            speedNoiseRatio = 0.08,
            enableMicroDrift = state.enableMicroDrift,
            maxDriftRadiusMeters = safeDriftRadius
        )
    }

    private fun convertWgsToType(wgs: GeoPoint, targetType: CoordinateType): GeoPoint {
        return when (targetType) {
            CoordinateType.WGS84 -> wgs
            CoordinateType.GCJ02 -> CoordinateConverter.convert(wgs, CoordinateType.WGS84, CoordinateType.GCJ02)
            CoordinateType.BD09 -> CoordinateConverter.convert(wgs, CoordinateType.WGS84, CoordinateType.BD09)
        }
    }

    private fun convertTypeToWgs(point: GeoPoint, sourceType: CoordinateType): GeoPoint {
        return when (sourceType) {
            CoordinateType.WGS84 -> point
            CoordinateType.GCJ02 -> CoordinateConverter.convert(point, CoordinateType.GCJ02, CoordinateType.WGS84)
            CoordinateType.BD09 -> CoordinateConverter.convert(point, CoordinateType.BD09, CoordinateType.WGS84)
        }
    }

    private fun filterFavoritesLocal(
        favorites: List<FavoriteLocation>,
        query: String
    ): List<FavoriteLocation> {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return favorites
        return favorites.filter { fav ->
            tokens.all { token ->
                fav.name.contains(token, ignoreCase = true) ||
                    fav.address.contains(token, ignoreCase = true)
            }
        }
    }

    private fun modeLabel(mode: MockMode): String = when (mode) {
        MockMode.AUTO -> "自动双模"
        MockMode.LSPOSED_ONLY -> "仅 LSPosed"
        MockMode.ROOT_ONLY -> "仅 Root"
    }

    private fun emitEvent(event: MainUiEvent) {
        _events.tryEmit(event)
    }

    override fun onCleared() {
        searchJob?.cancel()
        reverseGeocodeJob?.cancel()
        engineGateway.release()
        super.onCleared()
    }

    private sealed interface DesiredEngineState {
        data object Stopped : DesiredEngineState
        data class Single(val point: GeoPoint) : DesiredEngineState
        data object CruiseRunning : DesiredEngineState
        data object CruisePaused : DesiredEngineState
    }

    private sealed interface PendingControlAction {
        data class SwitchMode(val targetMode: MockMode) : PendingControlAction
        data class StartSingle(val point: GeoPoint, val epoch: Long) : PendingControlAction
        data class StartCruise(val epoch: Long) : PendingControlAction
    }

    private data class ModeSwitchPrep(
        val switchEpoch: Long,
        val prevStatus: MockRunStatus,
        val switchOpId: Long
    )

    private enum class CruiseSwitchRestartOutcome {
        RESTARTED,
        EMPTY_WAYPOINTS,
        ABORTED_BY_LIFECYCLE
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    companion object {
        private const val ABORTED_BEFORE_INTERNAL_STOP_OP_ID = -1L

        val OFFLINE_SOURCES = setOf(
            OfflineFallbackGeocodingService.SOURCE_FALLBACK,
            OfflineFallbackGeocodingService.SOURCE_NEAREST,
            OfflineFallbackGeocodingService.SOURCE_COORD
        )

        /**
         * 严格判定 [candidateVersion] 是否严格晚于 [baselineVersion]（兼容 [MockConfigRepository] 的 Long.MAX_VALUE - 1 -> 1 回绕）。
         * 普通旧版本（candidateVersion < baselineVersion，例如 5 < 10）严格返回 false。
         */
        internal fun isVersionStrictlyAfter(candidateVersion: Long, baselineVersion: Long): Boolean {
            if (candidateVersion <= 0L || baselineVersion < 0L || candidateVersion == baselineVersion) {
                return false
            }
            val halfRange = Long.MAX_VALUE / 2L
            return if (candidateVersion > baselineVersion) {
                (candidateVersion - baselineVersion) <= halfRange
            } else {
                (baselineVersion - candidateVersion) > halfRange
            }
        }
    }
}
