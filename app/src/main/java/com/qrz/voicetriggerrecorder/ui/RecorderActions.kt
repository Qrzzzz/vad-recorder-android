package com.qrz.voicetriggerrecorder.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.qrz.voicetriggerrecorder.record.RecordForegroundService

internal fun startService(context: android.content.Context) {
    val intent = Intent(context, RecordForegroundService::class.java).apply {
        action = RecordForegroundService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

internal fun stopService(context: android.content.Context) {
    val intent = Intent(context, RecordForegroundService::class.java).apply {
        action = RecordForegroundService.ACTION_STOP
    }
    context.startService(intent)
}

internal fun requestNotificationIfNeeded(
    context: android.content.Context,
    launcher: ActivityResultLauncher<String>? = null
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        launcher?.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

internal fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    context.startActivity(intent)
}

internal fun openNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }
    context.startActivity(intent)
}

internal fun openBatteryOptimizationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    context.startActivity(intent)
}
