package com.mockloc.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mockloc.data.config.MockConfig
import com.mockloc.data.remote.RemoteControlConfig
import com.mockloc.data.remote.RemoteControlRepository
import com.mockloc.domain.engine.CruiseConfig
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.domain.model.PlannedRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MockEngineController {
    private val appContext: Context?
    private val permissionCheck: () -> PermissionCheckResult
    private val serviceStarter: ((Context) -> Unit)?
    private val serviceStopper: ((Context) -> Unit)?
    private val leaseCheck: () -> Boolean
    private val controllerScope: CoroutineScope

    private val stateLock = Any()
    private val _runtimeState = MutableStateFlow(MockRuntimeState())
    val runtimeState: StateFlow<MockRuntimeState> = _runtimeState.asStateFlow()

    @Volatile
    private var boundCoordinator: MockEngineCoordinator? = null
    @Volatile
    private var isBound = false
    private var bindRequested = false
    private var isReleased = false
    private var isDrainingPending = false
    private var stateCollectJob: Job? = null
    private val pendingActions = mutableListOf<(MockEngineCoordinator) -> Unit>()
    private var foregroundStartDispatched = false
    private var pendingStopAfterConnect = false

    constructor(
        context: Context,
        permissionCheck: () -> PermissionCheckResult = { PermissionChecker.check(context) },
        serviceStarter: (Context) -> Unit = { ctx ->
            val intent = Intent(ctx, MockLocationForegroundService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        },
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        serviceStopper: (Context) -> Unit = { ctx ->
            val intent = Intent(ctx, MockLocationForegroundService::class.java)
            ctx.stopService(intent)
        },
        leaseCheck: () -> Boolean = { RemoteControlRepository.getInstance(context).hasActiveLease() }
    ) {
        this.appContext = context.applicationContext ?: context
        this.permissionCheck = permissionCheck
        this.serviceStarter = serviceStarter
        this.serviceStopper = serviceStopper
        this.leaseCheck = leaseCheck
        this.controllerScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    constructor(
        coordinator: MockEngineCoordinator,
        permissionCheck: () -> PermissionCheckResult,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        leaseCheck: () -> Boolean = { true }
    ) {
        this.appContext = null
        this.permissionCheck = permissionCheck
        this.serviceStarter = null
        this.serviceStopper = null
        this.leaseCheck = leaseCheck
        this.controllerScope = CoroutineScope(SupervisorJob() + dispatcher)
        synchronized(stateLock) {
            boundCoordinator = coordinator
            _runtimeState.value = coordinator.runtimeState.value
            stateCollectJob = controllerScope.launch {
                coordinator.runtimeState.collect { state ->
                    _runtimeState.value = state
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? MockLocationForegroundService.LocalBinder ?: return
            val foregroundService = localBinder.getService()
            val coordinator = foregroundService.getCoordinator()

            var releasedOnConnect = false
            var initialBatch: List<(MockEngineCoordinator) -> Unit> = emptyList()
            var initialShouldStop = false

            synchronized(stateLock) {
                bindRequested = false
                if (isReleased) {
                    isBound = false
                    boundCoordinator = null
                    pendingActions.clear()
                    pendingStopAfterConnect = false
                    foregroundStartDispatched = false
                    isDrainingPending = false
                    releasedOnConnect = true
                } else {
                    isBound = true
                    boundCoordinator = coordinator
                    _runtimeState.value = coordinator.runtimeState.value
                    stateCollectJob?.cancel()
                    stateCollectJob = controllerScope.launch {
                        coordinator.runtimeState.collect { state ->
                            _runtimeState.value = state
                        }
                    }

                    val stop = pendingStopAfterConnect && pendingActions.isEmpty()
                    if (stop) {
                        pendingStopAfterConnect = false
                        foregroundStartDispatched = false
                        initialShouldStop = true
                    } else {
                        if (pendingActions.isNotEmpty()) {
                            pendingStopAfterConnect = false
                        }
                        initialBatch = pendingActions.toList()
                        pendingActions.clear()
                        isDrainingPending = initialBatch.isNotEmpty()
                    }
                }
            }

            if (releasedOnConnect) {
                appContext?.let { ctx -> runCatching { ctx.unbindService(this) } }
                return
            }

            if (initialShouldStop) {
                coordinator.stopMock()
                runCatching { foregroundService.stopForegroundAndSelf() }
                appContext?.let { ctx -> runCatching { serviceStopper?.invoke(ctx) } }
                return
            }

            if (initialBatch.isEmpty()) {
                return
            }

            var batch = initialBatch
            while (true) {
                batch.forEach { action -> action(coordinator) }
                val nextStep = synchronized(stateLock) {
                    if (isReleased) {
                        pendingActions.clear()
                        pendingStopAfterConnect = false
                        isDrainingPending = false
                        DrainNext.Released
                    } else if (pendingStopAfterConnect && pendingActions.isEmpty()) {
                        pendingStopAfterConnect = false
                        foregroundStartDispatched = false
                        isDrainingPending = false
                        DrainNext.StopNow
                    } else if (pendingActions.isNotEmpty()) {
                        pendingStopAfterConnect = false
                        val copy = pendingActions.toList()
                        pendingActions.clear()
                        DrainNext.Batch(copy)
                    } else {
                        isDrainingPending = false
                        DrainNext.Done
                    }
                }
                when (nextStep) {
                    is DrainNext.Batch -> batch = nextStep.actions
                    is DrainNext.StopNow -> {
                        coordinator.stopMock()
                        runCatching { foregroundService.stopForegroundAndSelf() }
                        appContext?.let { ctx -> runCatching { serviceStopper?.invoke(ctx) } }
                        break
                    }
                    is DrainNext.Released, is DrainNext.Done -> break
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(stateLock) {
                isBound = false
                bindRequested = false
                boundCoordinator = null
                isDrainingPending = false
                stateCollectJob?.cancel()
                stateCollectJob = null
            }
        }
    }

    fun startSingleMock(point: GeoPoint, config: MockConfig) {
        if (!leaseCheck()) {
            val reason = appContext?.let { RemoteControlRepository.getInstance(it).getDeniedReason() }
                ?: RemoteControlConfig.DEFAULT_NETWORK_REQUIRED_REASON
            emitError(reason)
            return
        }
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            emitError("非法目标坐标: (${point.latitude}, ${point.longitude})")
            return
        }
        val perm = permissionCheck()
        if (!perm.canStartForegroundLocation) {
            emitError(perm.errorMessage ?: "缺少定位权限，无法开启单点模拟")
            return
        }
        dispatchToCoordinator(startForegroundIfNeeded = true) { coordinator ->
            coordinator.startSingleMock(point, config)
        }
    }

    fun updateSingleTarget(point: GeoPoint) {
        if (!GeoPoint.isValid(point.latitude, point.longitude)) {
            emitError("非法更新坐标: (${point.latitude}, ${point.longitude})")
            return
        }
        dispatchToCoordinator(startForegroundIfNeeded = false) { coordinator ->
            coordinator.updateSingleTarget(point)
        }
    }

    fun startCruise(route: PlannedRoute, cruiseConfig: CruiseConfig, baseConfig: MockConfig) {
        if (!leaseCheck()) {
            val reason = appContext?.let { RemoteControlRepository.getInstance(it).getDeniedReason() }
                ?: RemoteControlConfig.DEFAULT_NETWORK_REQUIRED_REASON
            emitError(reason)
            return
        }
        if (route.waypoints.isEmpty()) {
            emitError("巡航路线为空，至少需要 1 个航点")
            return
        }
        val perm = permissionCheck()
        if (!perm.canStartForegroundLocation) {
            emitError(perm.errorMessage ?: "缺少定位权限，无法开启轨迹巡航")
            return
        }
        dispatchToCoordinator(startForegroundIfNeeded = true) { coordinator ->
            coordinator.startCruise(route, cruiseConfig, baseConfig)
        }
    }

    fun pauseCruise() {
        dispatchToCoordinator(startForegroundIfNeeded = false) { it.pauseCruise() }
    }

    fun resumeCruise() {
        dispatchToCoordinator(startForegroundIfNeeded = false) { it.resumeCruise() }
    }

    fun stopMock() {
        val coordinatorToStop = synchronized(stateLock) {
            pendingActions.clear()
            val direct = boundCoordinator
            if (direct != null) {
                foregroundStartDispatched = false
                if (isDrainingPending) {
                    // 正处于连接后批量回放窗口：交给 drain 循环末尾统一停机，防止与当前批动作乱序
                    pendingStopAfterConnect = true
                    null
                } else {
                    pendingStopAfterConnect = false
                    direct
                }
            } else {
                if (foregroundStartDispatched) {
                    pendingStopAfterConnect = true
                }
                null
            }
        }
        if (coordinatorToStop != null) {
            coordinatorToStop.stopMock()
        } else {
            _runtimeState.value = _runtimeState.value.copy(
                status = MockRunStatus.Idle,
                speedMps = 0.0,
                progressFraction = 0f,
                errorMessage = null
            )
        }
    }

    fun updateSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps < 0.0) return
        dispatchToCoordinator(startForegroundIfNeeded = false) { it.updateSpeed(speedMps) }
    }

    fun updateJitter(enabled: Boolean) {
        dispatchToCoordinator(startForegroundIfNeeded = false) { it.updateJitter(enabled) }
    }

    fun release() {
        val ctx = appContext
        val shouldUnbind = synchronized(stateLock) {
            isReleased = true
            val bound = isBound
            isBound = false
            bindRequested = false
            boundCoordinator = null
            isDrainingPending = false
            pendingActions.clear()
            pendingStopAfterConnect = false
            foregroundStartDispatched = false
            stateCollectJob?.cancel()
            stateCollectJob = null
            bound
        }
        if (ctx != null && shouldUnbind) {
            runCatching { ctx.unbindService(connection) }
        }
        controllerScope.cancel()
    }

    private fun dispatchToCoordinator(
        startForegroundIfNeeded: Boolean,
        action: (MockEngineCoordinator) -> Unit
    ) {
        var needServiceStarterForBound = false
        var queuedWhileDraining = false
        var needBind = false
        val direct = synchronized(stateLock) {
            if (isReleased) return
            val current = boundCoordinator
            if (current != null) {
                if (startForegroundIfNeeded) {
                    pendingStopAfterConnect = false
                    val currentStatus = current.runtimeState.value.status
                    if (!foregroundStartDispatched ||
                        currentStatus is MockRunStatus.Idle ||
                        currentStatus is MockRunStatus.Error
                    ) {
                        foregroundStartDispatched = true
                        needServiceStarterForBound = true
                    }
                }
                if (isDrainingPending) {
                    pendingActions.add(action)
                    queuedWhileDraining = true
                    null
                } else {
                    current
                }
            } else {
                if (appContext == null) {
                    null
                } else if (!startForegroundIfNeeded && pendingStopAfterConnect) {
                    // 处于等待连接后立即停机的窗口期，非启动类辅助指令不覆写停机意图
                    return
                } else {
                    if (startForegroundIfNeeded) {
                        pendingStopAfterConnect = false
                        foregroundStartDispatched = true
                    }
                    pendingActions.add(action)
                    if (!bindRequested) {
                        bindRequested = true
                        needBind = true
                    }
                    null
                }
            }
        }

        if (needServiceStarterForBound) {
            val ctx = appContext
            if (ctx != null) {
                try {
                    serviceStarter?.invoke(ctx)
                } catch (t: Throwable) {
                    synchronized(stateLock) {
                        foregroundStartDispatched = false
                    }
                    emitError(t.message ?: "无法重启前台定位服务")
                    return
                }
            }
        }

        if (queuedWhileDraining) {
            return
        }

        if (direct != null) {
            action(direct)
            return
        }

        val ctx = appContext
        if (ctx == null) {
            emitError("引擎控制器尚未初始化")
            return
        }

        try {
            if (startForegroundIfNeeded) {
                serviceStarter?.invoke(ctx)
            }
            if (needBind) {
                val bindIntent = Intent(ctx, MockLocationForegroundService::class.java)
                val boundOk = ctx.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE)
                if (!boundOk) {
                    synchronized(stateLock) {
                        bindRequested = false
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(stateLock) {
                pendingActions.clear()
                bindRequested = false
                if (startForegroundIfNeeded) {
                    foregroundStartDispatched = false
                }
            }
            emitError(t.message ?: "无法启动或绑定前台定位服务")
        }
    }

    private fun emitError(reason: String) {
        val coordinator = synchronized(stateLock) { boundCoordinator }
        if (coordinator != null) {
            coordinator.reportError(reason)
        }
        _runtimeState.value = _runtimeState.value.copy(
            status = MockRunStatus.Error(reason),
            speedMps = 0.0,
            errorMessage = reason,
            lastSyncTimeMs = System.currentTimeMillis()
        )
    }

    private sealed interface DrainNext {
        data class Batch(val actions: List<(MockEngineCoordinator) -> Unit>) : DrainNext
        data object StopNow : DrainNext
        data object Released : DrainNext
        data object Done : DrainNext
    }
}
