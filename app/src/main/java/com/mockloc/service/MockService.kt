package com.mockloc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mockloc.data.MockConfigManager
import com.mockloc.presentation.MainActivity
import com.mockloc.root.EnvironmentDetector
import com.mockloc.root.RootProviderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 模拟定位后台保活与单点心跳脉冲服务 (Mock Pulse Foreground Service)
 * 解决 Android 14/15/16 后台冻结、单点模拟无持续事件上报导致目标应用失效的根本问题。
 */
class MockService : Service() {

    companion object {
        const val CHANNEL_ID = "mockloc_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_MOCK = "com.mockloc.service.ACTION_START_MOCK"
        const val ACTION_STOP_MOCK = "com.mockloc.service.ACTION_STOP_MOCK"

        fun start(context: Context) {
            val intent = Intent(context, MockService::class.java).apply {
                action = ACTION_START_MOCK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MockService::class.java).apply {
                action = ACTION_STOP_MOCK
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var heartbeatJob: Job? = null
    private val rootProviderManager by lazy { RootProviderManager(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MOCK -> {
                stopHeartbeat()
                stopForeground(true)
                stopSelf()
            }
            else -> {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
                startHeartbeat()
            }
        }
        return START_STICKY
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var tick = 0
            while (isActive && MockConfigManager.isMockEnabled) {
                tick++

                // 拟真微抖动 (0.000001度 ~ 0.1米自然热噪声)
                val jitterLat = Math.sin(tick.toDouble()) * 0.000001
                val jitterLng = Math.cos(tick.toDouble()) * 0.000001
                val curLat = MockConfigManager.latitude + jitterLat
                val curLng = MockConfigManager.longitude + jitterLng

                // 持续向系统底层 TestProvider 投递最新时间戳的坐标
                if (EnvironmentDetector.isRootAvailable() && rootProviderManager.isRunning) {
                    try {
                        rootProviderManager.updateLocation(
                            lat = curLat,
                            lng = curLng,
                            alt = MockConfigManager.altitude,
                            speed = MockConfigManager.speed,
                            bearing = MockConfigManager.bearing,
                            accuracy = 3.0f
                        )
                    } catch (_: Throwable) {}
                }

                // 周期性发送广播保持活跃
                if (tick % 2 == 0) {
                    broadcastSync()
                }

                delay(1000)
            }
        }
    }

    private fun broadcastSync() {
        val bundle = MockConfigManager.toBundle()
        val intent = Intent(MockConfigManager.ACTION_CONFIG_CHANGED).apply {
            putExtras(bundle)
        }
        sendBroadcast(intent)
        for (pkg in MockConfigManager.getTargetPackages()) {
            try {
                val expIntent = Intent(MockConfigManager.ACTION_CONFIG_CHANGED).apply {
                    setPackage(pkg)
                    putExtras(bundle)
                }
                sendBroadcast(expIntent)
            } catch (_: Throwable) {}
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (EnvironmentDetector.isRootAvailable() && rootProviderManager.isRunning) {
            try {
                rootProviderManager.stopMock()
            } catch (_: Throwable) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模拟定位保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保障模拟定位在后台持续稳定生效"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockService::class.java).apply {
            action = ACTION_STOP_MOCK
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MockLocation 模拟定位生效中")
            .setContentText("目标坐标: %.5f, %.5f".format(MockConfigManager.latitude, MockConfigManager.longitude))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止模拟", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
