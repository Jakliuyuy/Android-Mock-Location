package com.mockloc.service

import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.engine.CruiseEngine
import com.mockloc.domain.engine.CruiseOutput
import com.mockloc.domain.engine.MotionSimulator
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockMode
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MockEngineCoordinator(
    private val configRepository: MockConfigRepository,
    private var rootDriver: RootLocationDriver = NoOpRootLocationDriver,
    private val permissionProvider: () -> PermissionCheckResult,
    private val broadcastDispatcher: (MockConfig, Set<String>) -> Unit,
    private val foregroundLauncher: (Boolean) -> Boolean,
    private val scope: CoroutineScope,
    private val tickIntervalMs: Long = DEFAULT_TICK_INTERVAL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val motionSimulator: MotionSimulator = MotionSimulator()
) {
    private val _runtimeState = MutableStateFlow(MockRuntimeState())
    val runtimeState: StateFlow<MockRuntimeState> = _runtimeState.asStateFlow()

    private val cruiseEngine = CruiseEngine(motionSimulator)
    private val stateLock = Any()
    private var activeEpoch: Long = 0L
    private var singleTargetEpoch: Long = 0L
    private var activeJob: Job? = null
    private var currentSingleBasePoint: GeoPoint? = null
    private var currentActiveConfig: MockConfig = configRepository.currentConfig

    fun setRootDriver(driver: RootLocationDriver) {
        synchronized(stateLock) {
            rootDriver = driver
        }
    }

    fun startSingleMock(point: GeoPoint, config: MockConfig) {
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            reportError("非法目标坐标: (${point.latitude}, ${point.longitude})")
            return
        }

        val perm = permissionProvider()
        if (!perm.canStartForegroundLocation) {
            reportError(perm.errorMessage ?: "缺少定位权限，禁止启动前台定位服务")
            return
        }

        if (!foregroundLauncher(true)) {
            reportError("无法启动前台定位服务")
            return
        }

        synchronized(stateLock) {
            activeEpoch++
            val myEpoch = activeEpoch
            singleTargetEpoch++

            cancelActiveJobLocked()
            cruiseEngine.stop()
            motionSimulator.reset()
            currentSingleBasePoint = point

            val updatedConfig = configRepository.updateImmediate {
                config.copy(
                    enabled = true,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitude = point.altitude,
                    accuracy = point.accuracy
                )
            }
            currentActiveConfig = updatedConfig

            _runtimeState.value = MockRuntimeState(
                status = MockRunStatus.SingleRunning,
                activeMode = updatedConfig.mode,
                currentPoint = point,
                speedMps = updatedConfig.speed.toDouble(),
                bearingDeg = updatedConfig.bearing,
                progressFraction = 0f,
                activeWaypointIndex = 0,
                totalWaypoints = 1,
                lastSyncTimeMs = timeProvider(),
                errorMessage = null
            )

            broadcastDispatcher(updatedConfig, updatedConfig.targetPackages)

            activeJob = scope.launch {
                try {
                    if (!isEpochActive(myEpoch)) return@launch
                    // 首次触发 Root 驱动
                    if (!dispatchRootStartOrUpdate(updatedConfig, point, myEpoch)) {
                        return@launch
                    }

                    while (isActive) {
                        delay(tickIntervalMs)
                        if (!isActive) break

                        val tickFrame = synchronized(stateLock) {
                            if (myEpoch != activeEpoch) return@synchronized null
                            val base = currentSingleBasePoint ?: return@synchronized null
                            val cfg = currentActiveConfig
                            val capturedTargetEpoch = singleTargetEpoch
                            val nextPoint = if (cfg.jitterEnabled && cfg.jitterRadiusMeters > 0.0) {
                                motionSimulator.applyMicroDrift(
                                    basePoint = base,
                                    maxDriftRadiusMeters = cfg.jitterRadiusMeters,
                                    enableDrift = true,
                                    deltaSeconds = tickIntervalMs / 1000.0
                                )
                            } else {
                                base
                            }
                            val previewCfg = cfg.copy(
                                enabled = true,
                                latitude = nextPoint.latitude,
                                longitude = nextPoint.longitude,
                                altitude = nextPoint.altitude,
                                accuracy = nextPoint.accuracy
                            )
                            SingleTickFrame(nextPoint, previewCfg, capturedTargetEpoch)
                        } ?: break

                        if (!dispatchRootStartOrUpdate(tickFrame.previewConfig, tickFrame.point, myEpoch)) {
                            return@launch
                        }

                        val commitOutcome = synchronized(stateLock) {
                            if (myEpoch != activeEpoch) {
                                return@synchronized FrameCommitOutcome.SESSION_EXPIRED
                            }
                            if (tickFrame.targetEpoch != singleTargetEpoch) {
                                return@synchronized FrameCommitOutcome.TARGET_STALE
                            }
                            val nextPoint = tickFrame.point
                            val synced = configRepository.updateDebounced { current ->
                                current.copy(
                                    enabled = true,
                                    latitude = nextPoint.latitude,
                                    longitude = nextPoint.longitude,
                                    altitude = nextPoint.altitude,
                                    accuracy = nextPoint.accuracy
                                )
                            }
                            currentActiveConfig = synced
                            broadcastDispatcher(synced, synced.targetPackages)
                            _runtimeState.value = _runtimeState.value.copy(
                                status = MockRunStatus.SingleRunning,
                                currentPoint = nextPoint,
                                lastSyncTimeMs = timeProvider(),
                                errorMessage = null
                            )
                            FrameCommitOutcome.COMMITTED
                        }
                        when (commitOutcome) {
                            FrameCommitOutcome.SESSION_EXPIRED -> break
                            FrameCommitOutcome.TARGET_STALE -> continue
                            FrameCommitOutcome.COMMITTED -> Unit
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    failWithError(t.message ?: "单点模拟运行异常", expectedEpoch = myEpoch)
                }
            }
        }
    }

    fun updateSingleTarget(point: GeoPoint) {
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            reportError("非法更新坐标: (${point.latitude}, ${point.longitude})")
            return
        }
        val dispatchSnapshot = synchronized(stateLock) {
            if (_runtimeState.value.status !is MockRunStatus.SingleRunning) {
                return
            }
            val myEpoch = activeEpoch
            singleTargetEpoch++
            val myTargetEpoch = singleTargetEpoch
            currentSingleBasePoint = point
            val updated = configRepository.updateImmediate { current ->
                current.copy(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitude = point.altitude,
                    accuracy = point.accuracy
                )
            }
            currentActiveConfig = updated
            broadcastDispatcher(updated, updated.targetPackages)
            _runtimeState.value = _runtimeState.value.copy(
                currentPoint = point,
                lastSyncTimeMs = timeProvider()
            )
            Triple(updated, myEpoch, myTargetEpoch)
        }
        val (updated, myEpoch, myTargetEpoch) = dispatchSnapshot
        scope.launch {
            if (isTargetEpochActive(myEpoch, myTargetEpoch)) {
                runCatching {
                    dispatchRootStartOrUpdate(updated, point, myEpoch)
                }
            }
        }
    }

    fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) {
        if (route.waypoints.isEmpty()) {
            reportError("巡航路线为空，至少需要 1 个航点")
            return
        }

        val perm = permissionProvider()
        if (!perm.canStartForegroundLocation) {
            reportError(perm.errorMessage ?: "缺少定位权限，禁止启动前台定位服务")
            return
        }

        if (!foregroundLauncher(true)) {
            reportError("无法启动前台定位服务")
            return
        }

        var initFailureMessage: String? = null
        synchronized(stateLock) {
            activeEpoch++
            val myEpoch = activeEpoch
            singleTargetEpoch++

            cancelActiveJobLocked()
            currentSingleBasePoint = null

            try {
                cruiseEngine.loadRoute(route, cruiseConfig)
                cruiseEngine.start()
            } catch (t: Throwable) {
                cruiseEngine.stop()
                initFailureMessage = t.message ?: "巡航路线初始化失败"
                return@synchronized
            }

            val initial = cruiseEngine.currentSnapshot()
            val updatedConfig = configRepository.updateImmediate {
                baseConfig.copy(
                    enabled = true,
                    latitude = initial.point.latitude,
                    longitude = initial.point.longitude,
                    altitude = initial.point.altitude,
                    speed = cruiseConfig.speedMps.toFloat(),
                    bearing = initial.bearingDeg,
                    jitterEnabled = cruiseConfig.enableMicroDrift,
                    jitterRadiusMeters = cruiseConfig.maxDriftRadiusMeters
                )
            }
            currentActiveConfig = updatedConfig

            _runtimeState.value = MockRuntimeState(
                status = MockRunStatus.CruiseRunning,
                activeMode = updatedConfig.mode,
                currentPoint = initial.point,
                speedMps = initial.speedMps,
                bearingDeg = initial.bearingDeg,
                progressFraction = initial.progressFraction,
                activeWaypointIndex = initial.activeWaypointIndex,
                totalWaypoints = initial.totalWaypoints,
                lastSyncTimeMs = timeProvider(),
                errorMessage = null
            )

            broadcastDispatcher(updatedConfig, updatedConfig.targetPackages)

            activeJob = scope.launch {
                try {
                    if (!isEpochActive(myEpoch)) return@launch
                    if (!dispatchRootStartOrUpdate(updatedConfig, initial.point, myEpoch)) {
                        synchronized(stateLock) {
                            if (myEpoch == activeEpoch) {
                                cruiseEngine.stop()
                            }
                        }
                        return@launch
                    }

                    while (isActive) {
                        delay(tickIntervalMs)
                        if (!isActive) break

                        val stepOutcome = synchronized(stateLock) {
                            if (myEpoch != activeEpoch || cruiseEngine.isFinished()) {
                                return@synchronized CruiseStepOutcome.Exit
                            }
                            if (cruiseEngine.state == CruiseEngine.State.PAUSED) {
                                return@synchronized CruiseStepOutcome.SkipPaused
                            }

                            val output = cruiseEngine.advanceStep(tickIntervalMs / 1000.0)
                            if (output.isCompleted) {
                                activeEpoch++
                                val completedEpoch = activeEpoch
                                val synced = configRepository.updateImmediate { current ->
                                    current.copy(
                                        enabled = false,
                                        latitude = output.point.latitude,
                                        longitude = output.point.longitude,
                                        altitude = output.point.altitude,
                                        speed = 0.0f,
                                        bearing = output.bearingDeg
                                    )
                                }
                                currentActiveConfig = synced
                                broadcastDispatcher(synced, synced.targetPackages)
                                cruiseEngine.stop()
                                _runtimeState.value = MockRuntimeState(
                                    status = MockRunStatus.Idle,
                                    activeMode = synced.mode,
                                    currentPoint = output.point,
                                    speedMps = 0.0,
                                    bearingDeg = output.bearingDeg,
                                    progressFraction = 1.0f,
                                    activeWaypointIndex = output.activeWaypointIndex,
                                    totalWaypoints = output.totalWaypoints,
                                    lastSyncTimeMs = timeProvider(),
                                    errorMessage = null
                                )
                                CruiseStepOutcome.Completed(completedEpoch)
                            } else {
                                val previewConfig = currentActiveConfig.copy(
                                    enabled = true,
                                    latitude = output.point.latitude,
                                    longitude = output.point.longitude,
                                    altitude = output.point.altitude,
                                    speed = output.speedMps.toFloat(),
                                    bearing = output.bearingDeg
                                )
                                CruiseStepOutcome.Continue(output, previewConfig)
                            }
                        }

                        when (stepOutcome) {
                            is CruiseStepOutcome.Exit -> break
                            is CruiseStepOutcome.SkipPaused -> continue
                            is CruiseStepOutcome.Completed -> {
                                val driver = synchronized(stateLock) { rootDriver }
                                if (isEpochActive(stepOutcome.completedEpoch)) {
                                    runCatching { driver.stop() }
                                }
                                if (isEpochActive(stepOutcome.completedEpoch)) {
                                    foregroundLauncher(false)
                                }
                                break
                            }
                            is CruiseStepOutcome.Continue -> {
                                val output = stepOutcome.output
                                if (!dispatchRootStartOrUpdate(stepOutcome.previewConfig, output.point, myEpoch)) {
                                    synchronized(stateLock) {
                                        if (myEpoch == activeEpoch) {
                                            cruiseEngine.stop()
                                        }
                                    }
                                    return@launch
                                }

                                val committed = synchronized(stateLock) {
                                    if (myEpoch != activeEpoch) return@synchronized false
                                    val synced = configRepository.updateDebounced { current ->
                                        current.copy(
                                            enabled = true,
                                            latitude = output.point.latitude,
                                            longitude = output.point.longitude,
                                            altitude = output.point.altitude,
                                            speed = output.speedMps.toFloat(),
                                            bearing = output.bearingDeg
                                        )
                                    }
                                    currentActiveConfig = synced
                                    broadcastDispatcher(synced, synced.targetPackages)
                                    _runtimeState.value = MockRuntimeState(
                                        status = MockRunStatus.CruiseRunning,
                                        activeMode = synced.mode,
                                        currentPoint = output.point,
                                        speedMps = output.speedMps,
                                        bearingDeg = output.bearingDeg,
                                        progressFraction = output.progressFraction,
                                        activeWaypointIndex = output.activeWaypointIndex,
                                        totalWaypoints = output.totalWaypoints,
                                        lastSyncTimeMs = timeProvider(),
                                        errorMessage = null
                                    )
                                    true
                                }
                                if (!committed) break
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    synchronized(stateLock) {
                        if (myEpoch == activeEpoch) {
                            cruiseEngine.stop()
                        }
                    }
                    failWithError(t.message ?: "轨迹巡航异常", expectedEpoch = myEpoch)
                }
            }
        }

        initFailureMessage?.let { reason ->
            foregroundLauncher(false)
            reportError(reason)
        }
    }

    fun pauseCruise() {
        synchronized(stateLock) {
            if (_runtimeState.value.status !is MockRunStatus.CruiseRunning) return
            cruiseEngine.pause()
            _runtimeState.value = _runtimeState.value.copy(
                status = MockRunStatus.CruisePaused,
                speedMps = 0.0,
                lastSyncTimeMs = timeProvider()
            )
        }
    }

    fun resumeCruise() {
        synchronized(stateLock) {
            if (_runtimeState.value.status !is MockRunStatus.CruisePaused) return
            cruiseEngine.resume()
            _runtimeState.value = _runtimeState.value.copy(
                status = MockRunStatus.CruiseRunning,
                speedMps = currentActiveConfig.speed.toDouble(),
                lastSyncTimeMs = timeProvider()
            )
        }
    }

    fun stopMock() {
        val stopEpoch = synchronized(stateLock) {
            activeEpoch++
            singleTargetEpoch++
            cancelActiveJobLocked()
            cruiseEngine.stop()
            currentSingleBasePoint = null
            val stoppedConfig = configRepository.updateImmediate { it.copy(enabled = false, speed = 0.0f) }
            currentActiveConfig = stoppedConfig
            broadcastDispatcher(stoppedConfig, stoppedConfig.targetPackages)
            _runtimeState.value = MockRuntimeState(
                status = MockRunStatus.Idle,
                activeMode = stoppedConfig.mode,
                currentPoint = _runtimeState.value.currentPoint,
                speedMps = 0.0,
                bearingDeg = _runtimeState.value.bearingDeg,
                progressFraction = 0f,
                activeWaypointIndex = 0,
                totalWaypoints = 0,
                lastSyncTimeMs = timeProvider(),
                errorMessage = null
            )
            activeEpoch
        }
        if (isEpochActive(stopEpoch)) {
            foregroundLauncher(false)
        }
        scope.launch {
            if (isEpochActive(stopEpoch)) {
                val driver = synchronized(stateLock) { rootDriver }
                runCatching { driver.stop() }
            }
        }
    }

    fun updateSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps < 0.0) return
        synchronized(stateLock) {
            cruiseEngine.updateSpeed(speedMps)
            val updated = configRepository.updateImmediate { it.copy(speed = speedMps.toFloat()) }
            currentActiveConfig = updated
            broadcastDispatcher(updated, updated.targetPackages)
            val status = _runtimeState.value.status
            val nextRuntimeSpeed = when (status) {
                is MockRunStatus.SingleRunning, is MockRunStatus.CruiseRunning -> speedMps
                is MockRunStatus.CruisePaused, is MockRunStatus.Idle, is MockRunStatus.Error -> 0.0
            }
            _runtimeState.value = _runtimeState.value.copy(
                speedMps = nextRuntimeSpeed,
                lastSyncTimeMs = timeProvider()
            )
        }
    }

    fun updateJitter(enabled: Boolean) {
        synchronized(stateLock) {
            cruiseEngine.updateJitter(enabled)
            val updated = configRepository.updateImmediate { it.copy(jitterEnabled = enabled) }
            currentActiveConfig = updated
            broadcastDispatcher(updated, updated.targetPackages)
        }
    }

    fun reportError(reason: String) {
        synchronized(stateLock) {
            activeEpoch++
            singleTargetEpoch++
            cancelActiveJobLocked()
            cruiseEngine.stop()
            currentSingleBasePoint = null
            _runtimeState.value = _runtimeState.value.copy(
                status = MockRunStatus.Error(reason),
                speedMps = 0.0,
                errorMessage = reason,
                lastSyncTimeMs = timeProvider()
            )
        }
    }

    private suspend fun dispatchRootStartOrUpdate(
        config: MockConfig,
        point: GeoPoint,
        expectedEpoch: Long
    ): Boolean {
        if (config.mode == MockMode.LSPOSED_ONLY) return true
        val driver = synchronized(stateLock) {
            if (expectedEpoch != activeEpoch) return false
            rootDriver
        }
        val result = try {
            driver.startOrUpdate(
                latitude = point.latitude,
                longitude = point.longitude,
                altitude = point.altitude,
                accuracy = config.accuracy,
                bearing = config.bearing,
                speed = config.speed
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
        if (result.isFailure && config.mode == MockMode.ROOT_ONLY) {
            val msg = result.exceptionOrNull()?.message ?: "Root 定位注入失败"
            failWithError("Root 模式失败: $msg", expectedEpoch = expectedEpoch)
            return false
        }
        return true
    }

    private suspend fun failWithError(reason: String, expectedEpoch: Long) {
        val (errorEpoch, driver) = synchronized(stateLock) {
            if (expectedEpoch != activeEpoch) return
            activeEpoch++
            singleTargetEpoch++
            cancelActiveJobLocked()
            cruiseEngine.stop()
            currentSingleBasePoint = null
            val stoppedConfig = configRepository.updateImmediate { it.copy(enabled = false, speed = 0.0f) }
            currentActiveConfig = stoppedConfig
            broadcastDispatcher(stoppedConfig, stoppedConfig.targetPackages)
            _runtimeState.value = _runtimeState.value.copy(
                status = MockRunStatus.Error(reason),
                speedMps = 0.0,
                errorMessage = reason,
                lastSyncTimeMs = timeProvider()
            )
            activeEpoch to rootDriver
        }
        runCatching { driver.stop() }
        if (isEpochActive(errorEpoch)) {
            foregroundLauncher(false)
        }
    }

    private fun isEpochActive(expectedEpoch: Long): Boolean {
        return synchronized(stateLock) { activeEpoch == expectedEpoch }
    }

    private fun isTargetEpochActive(expectedEpoch: Long, expectedTargetEpoch: Long): Boolean {
        return synchronized(stateLock) {
            activeEpoch == expectedEpoch && singleTargetEpoch == expectedTargetEpoch
        }
    }

    private fun cancelActiveJobLocked() {
        activeJob?.cancel()
        activeJob = null
    }

    private data class SingleTickFrame(
        val point: GeoPoint,
        val previewConfig: MockConfig,
        val targetEpoch: Long
    )

    private enum class FrameCommitOutcome {
        COMMITTED,
        TARGET_STALE,
        SESSION_EXPIRED
    }

    private sealed interface CruiseStepOutcome {
        data object Exit : CruiseStepOutcome
        data object SkipPaused : CruiseStepOutcome
        data class Completed(val completedEpoch: Long) : CruiseStepOutcome
        data class Continue(
            val output: CruiseOutput,
            val previewConfig: MockConfig
        ) : CruiseStepOutcome
    }

    companion object {
        const val DEFAULT_TICK_INTERVAL_MS = 1000L
    }
}
