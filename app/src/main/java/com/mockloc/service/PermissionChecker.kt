package com.mockloc.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PermissionCheckResult(
    val hasLocationPermission: Boolean,
    val hasNotificationPermission: Boolean,
    val canStartForegroundLocation: Boolean,
    val errorMessage: String? = null
)

object PermissionChecker {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasNotificationPermission(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun check(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): PermissionCheckResult {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotif = hasNotificationPermission(context, sdkInt)
        return evaluateStartForegroundEligibility(
            hasFineLocation = fine,
            hasCoarseLocation = coarse,
            hasNotification = hasNotif
        )
    }

    fun evaluateStartForegroundEligibility(
        hasFineLocation: Boolean,
        hasCoarseLocation: Boolean,
        hasNotification: Boolean
    ): PermissionCheckResult {
        val hasLoc = hasFineLocation || hasCoarseLocation
        return if (!hasLoc) {
            PermissionCheckResult(
                hasLocationPermission = false,
                hasNotificationPermission = hasNotification,
                canStartForegroundLocation = false,
                errorMessage = "缺少定位权限（ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION），无法启动前台定位服务"
            )
        } else {
            PermissionCheckResult(
                hasLocationPermission = true,
                hasNotificationPermission = hasNotification,
                canStartForegroundLocation = true,
                errorMessage = null
            )
        }
    }
}
