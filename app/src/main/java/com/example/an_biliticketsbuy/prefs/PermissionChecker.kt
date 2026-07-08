package com.example.an_biliticketsbuy.prefs

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.an_biliticketsbuy.service.TicketAccessibilityService

object PermissionChecker {

    data class PermissionState(
        val overlayGranted: Boolean,
        val accessibilityEnabled: Boolean,
        val notificationGranted: Boolean
    ) {
        val allGranted: Boolean
            get() = overlayGranted && accessibilityEnabled && notificationGranted
    }

    fun check(context: Context): PermissionState = PermissionState(
        overlayGranted = Settings.canDrawOverlays(context),
        accessibilityEnabled = TicketAccessibilityService.isServiceEnabled(),
        notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    )
}
