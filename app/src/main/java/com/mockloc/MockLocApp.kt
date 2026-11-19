package com.mockloc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mockloc.data.config.MockConfigRepository
import com.mockloc.root.RootProviderManager
import com.mockloc.service.MockLocationForegroundService

class MockLocApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            MockConfigRepository.getInstance(this)
        }
        runCatching {
            MockLocationForegroundService.registerRootDriver(RootProviderManager.getInstance(this))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "模拟定位保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持单点定位与轨迹巡航在后台持续稳定运行"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "mockloc_service_channel"
    }
}
