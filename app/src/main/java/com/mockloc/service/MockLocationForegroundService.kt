package com.mockloc.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mockloc.MockLocApp
import com.mockloc.data.config.MockConfig
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.domain.model.GeoPoint
import com.mockloc.domain.model.MockRunStatus
import com.mockloc.domain.model.MockRuntimeState
import com.mockloc.ipc.ConfigSyncSerializer
import com.mockloc.ipc.IpcProtocol
import com.mockloc.presentation.MainActivity
import com.mockloc.root.RootProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MockLocationForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MockLocationForegroundService = this@MockLocationForegroundService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var coordinator: MockEngineCoordinator
    @Volatile
    private var isForegroundStarted = false
    @Volatile
    private var isDestroying = false
    private var receiverRegistered = false

    private val notificationSync = ServiceNotificationSync(
        isForegroundActive = { isForegroundStarted },
        onUpdateNotification = { state, _ -> postNotificationUpdate(state) },
        onIdleCleanup = {
            stopForegroundCompat()
            if (!isDestroying) {
                runCatching { stopSelf() }
            }
        }
    )

    val runtimeState: StateFlow<MockRuntimeState>
        get() = coordinator.runtimeState

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONTROL_PAUSE -> coordinator.pauseCruise()
                ACTION_CONTROL_RESUME -> coordinator.resumeCruise()
                ACTION_CONTROL_STOP -> {
                    coordinator.stopMock()
                    stopForegroundAndSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val repo = MockConfigRepository.getInstance(this)
        if (globalRootDriver === NoOpRootLocationDriver) {
            runCatching {
                globalRootDriver = RootProviderManager.getInstance(applicationContext ?: this)
            }
        }
        coordinator = MockEngineCoordinator(
            configRepository = repo,
            rootDriver = globalRootDriver,
            permissionProvider = { PermissionChecker.check(this) },
            broadcastDispatcher = { config, packages ->
                dispatchExplicitConfigBroadcasts(this, config, packages)
            },
            foregroundLauncher = { start ->
                if (start) ensureForegroundStarted() else {
                    stopForegroundCompat()
                    true
                }
            },
            scope = serviceScope
        )

        serviceScope.launch {
            coordinator.runtimeState.collect { state ->
                notificationSync.onStateChanged(state)
            }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_CONTROL_PAUSE)
            addAction(ACTION_CONTROL_RESUME)
            addAction(ACTION_CONTROL_STOP)
        }
        ContextCompat.registerReceiver(
            this,
            controlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14–17 铁律：startForeground 前必检定位权限，未授权绝不强行 startForeground
        val perm = PermissionChecker.check(this)
        if (!perm.canStartForegroundLocation) {
            coordinator.reportError(
                perm.errorMessage ?: "缺少定位权限，无法启动前台定位服务"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_CONTROL_PAUSE -> coordinator.pauseCruise()
            ACTION_CONTROL_RESUME -> coordinator.resumeCruise()
            ACTION_CONTROL_STOP -> {
                coordinator.stopMock()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!ensureForegroundStarted()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isDestroying = true
        if (receiverRegistered) {
            runCatching { unregisterReceiver(controlReceiver) }
            receiverRegistered = false
        }
        coordinator.stopMock()
        stopForegroundCompat()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun getCoordinator(): MockEngineCoordinator = coordinator

    fun stopForegroundAndSelf() {
        stopForegroundCompat()
        if (!isDestroying) {
            runCatching { stopSelf() }
        }
    }

    private fun ensureForegroundStarted(): Boolean {
        val perm = PermissionChecker.check(this)
        if (!perm.canStartForegroundLocation) {
            coordinator.reportError(
                perm.errorMessage ?: "缺少定位权限，无法启动前台定位服务"
            )
            return false
        }
        return try {
            val state = coordinator.runtimeState.value
            val notification = buildNotification(state)
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            isForegroundStarted = true
            notificationSync.resetOnForegroundStarted(state)
            true
        } catch (t: Throwable) {
            isForegroundStarted = false
            coordinator.reportError(t.message ?: "启动前台定位服务失败")
            false
        }
    }

    private fun postNotificationUpdate(state: MockRuntimeState) {
        if (!isForegroundStarted || isDestroying) return
        if (!PermissionChecker.hasNotificationPermission(this)) return
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: SecurityException) {
            // Android 13+ 运行时通知权限被撤销时安全忽略
        } catch (_: Throwable) {
            // 防御系统通知调度异常
        }
    }

    private fun stopForegroundCompat() {
        if (isForegroundStarted) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            isForegroundStarted = false
        }
    }

    private fun buildNotification(state: MockRuntimeState): Notification {
        val presentation = buildNotificationPresentation(state)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            openAppIntent,
            PENDING_INTENT_FLAGS
        )

        val builder = NotificationCompat.Builder(this, MockLocApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(presentation.title)
            .setContentText(presentation.contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)

        if (presentation.showStopAction) {
            val stopIntent = Intent(ACTION_CONTROL_STOP).setPackage(packageName)
            val stopPendingIntent = PendingIntent.getBroadcast(
                this,
                REQ_STOP,
                stopIntent,
                PENDING_INTENT_FLAGS
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
        }

        if (presentation.showPauseAction) {
            val pauseIntent = Intent(ACTION_CONTROL_PAUSE).setPackage(packageName)
            val pausePendingIntent = PendingIntent.getBroadcast(
                this,
                REQ_PAUSE,
                pauseIntent,
                PENDING_INTENT_FLAGS
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pausePendingIntent)
        } else if (presentation.showResumeAction) {
            val resumeIntent = Intent(ACTION_CONTROL_RESUME).setPackage(packageName)
            val resumePendingIntent = PendingIntent.getBroadcast(
                this,
                REQ_RESUME,
                resumeIntent,
                PENDING_INTENT_FLAGS
            )
            builder.addAction(android.R.drawable.ic_media_play, "继续", resumePendingIntent)
        }

        return builder.build()
    }

    internal data class NotificationKey(
        val status: MockRunStatus,
        val activeWaypointIndex: Int,
        val totalWaypoints: Int,
        val pointSummary: String?
    )

    internal data class NotificationPresentation(
        val title: String,
        val contentText: String,
        val showPauseAction: Boolean,
        val showResumeAction: Boolean,
        val showStopAction: Boolean,
        val key: NotificationKey
    )

    internal class ServiceNotificationSync(
        private val isForegroundActive: () -> Boolean,
        private val onUpdateNotification: (MockRuntimeState, NotificationPresentation) -> Unit,
        private val onIdleCleanup: () -> Unit
    ) {
        private val lock = Any()
        private var lastPresentedKey: NotificationKey? = null
        private var hasBeenNonIdle = false

        fun onStateChanged(state: MockRuntimeState) {
            var shouldCleanup = false
            var presentationToNotify: NotificationPresentation? = null

            synchronized(lock) {
                val presentation = buildNotificationPresentation(state)
                if (state.status is MockRunStatus.Idle) {
                    if (hasBeenNonIdle) {
                        hasBeenNonIdle = false
                        lastPresentedKey = presentation.key
                        shouldCleanup = true
                    }
                } else {
                    hasBeenNonIdle = true
                    if (isForegroundActive() && presentation.key != lastPresentedKey) {
                        lastPresentedKey = presentation.key
                        presentationToNotify = presentation
                    }
                }
            }

            if (shouldCleanup) {
                onIdleCleanup()
            } else if (presentationToNotify != null) {
                onUpdateNotification(state, presentationToNotify!!)
            }
        }

        fun resetOnForegroundStarted(initialState: MockRuntimeState) {
            synchronized(lock) {
                val presentation = buildNotificationPresentation(initialState)
                lastPresentedKey = presentation.key
                if (initialState.status !is MockRunStatus.Idle) {
                    hasBeenNonIdle = true
                }
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONTROL_PAUSE = "com.mockloc.action.CONTROL_PAUSE"
        const val ACTION_CONTROL_RESUME = "com.mockloc.action.CONTROL_RESUME"
        const val ACTION_CONTROL_STOP = "com.mockloc.action.CONTROL_STOP"

        const val PENDING_INTENT_FLAGS =
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private const val REQ_OPEN_APP = 101
        private const val REQ_STOP = 102
        private const val REQ_PAUSE = 103
        private const val REQ_RESUME = 104

        @Volatile
        private var globalRootDriver: RootLocationDriver = NoOpRootLocationDriver

        internal fun formatPointSummary(point: GeoPoint?): String? {
            if (point == null || !GeoPoint.isValid(point.latitude, point.longitude)) return null
            return String.format(Locale.US, "%.4f,%.4f", point.latitude, point.longitude)
        }

        internal fun buildNotificationPresentation(state: MockRuntimeState): NotificationPresentation {
            val pointSummary = if (state.status is MockRunStatus.SingleRunning) {
                formatPointSummary(state.currentPoint)
            } else {
                null
            }
            val contentText = when (val status = state.status) {
                is MockRunStatus.SingleRunning -> {
                    if (pointSummary != null) "单点模拟运行中 ($pointSummary)" else "单点模拟运行中"
                }
                is MockRunStatus.CruiseRunning -> {
                    if (state.totalWaypoints > 1) {
                        val step = (state.activeWaypointIndex + 1).coerceIn(1, state.totalWaypoints)
                        "轨迹巡航运行中 ($step/${state.totalWaypoints})"
                    } else {
                        "轨迹巡航运行中"
                    }
                }
                is MockRunStatus.CruisePaused -> {
                    if (state.totalWaypoints > 1) {
                        val step = (state.activeWaypointIndex + 1).coerceIn(1, state.totalWaypoints)
                        "轨迹巡航已暂停 ($step/${state.totalWaypoints})"
                    } else {
                        "轨迹巡航已暂停"
                    }
                }
                is MockRunStatus.Error -> "状态异常: ${status.reason}"
                is MockRunStatus.Idle -> "模拟定位待命"
            }

            val key = NotificationKey(
                status = state.status,
                activeWaypointIndex = if (state.status is MockRunStatus.CruiseRunning || state.status is MockRunStatus.CruisePaused) {
                    state.activeWaypointIndex
                } else {
                    0
                },
                totalWaypoints = if (state.status is MockRunStatus.CruiseRunning || state.status is MockRunStatus.CruisePaused) {
                    state.totalWaypoints
                } else {
                    0
                },
                pointSummary = pointSummary
            )

            return NotificationPresentation(
                title = "MockLocation 定位服务",
                contentText = contentText,
                showPauseAction = state.status is MockRunStatus.CruiseRunning,
                showResumeAction = state.status is MockRunStatus.CruisePaused,
                showStopAction = true,
                key = key
            )
        }

        data class BroadcastSpec(
            val action: String,
            val targetPackage: String,
            val config: MockConfig
        )

        fun registerRootDriver(driver: RootLocationDriver) {
            globalRootDriver = driver
        }

        fun buildConfigBroadcastSpec(config: MockConfig, targetPackage: String): BroadcastSpec {
            return BroadcastSpec(
                action = IpcProtocol.ACTION_CONFIG_CHANGED,
                targetPackage = targetPackage,
                config = config
            )
        }

        fun buildConfigBroadcastIntent(
            config: MockConfig,
            targetPackage: String,
            intentFactory: (String) -> Intent = { Intent(it) },
            bundleFactory: () -> Bundle = { Bundle() }
        ): Intent {
            val spec = buildConfigBroadcastSpec(config, targetPackage)
            return intentFactory(spec.action).apply {
                setPackage(spec.targetPackage)
                putExtras(ConfigSyncSerializer.toBundle(spec.config, bundleFactory()))
            }
        }

        fun dispatchExplicitConfigBroadcasts(
            context: Context,
            config: MockConfig,
            targetPackages: Set<String>
        ): Int {
            var count = 0
            for (pkg in targetPackages) {
                val trimmed = pkg.trim()
                if (trimmed.isEmpty()) continue
                val intent = buildConfigBroadcastIntent(config, trimmed)
                runCatching {
                    context.sendBroadcast(intent)
                    count++
                }
            }
            return count
        }
    }
}
