package br.com.c6tag.diagnostico.remote.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

internal data class CriticalAlertPermissionState(
    val canUseFullScreenIntent: Boolean = true,
    val canDrawOverlays: Boolean = false,
)

internal object CriticalAlertPermissions {
    fun read(context: Context): CriticalAlertPermissionState {
        val appContext = context.applicationContext
        val fullScreen = if (Build.VERSION.SDK_INT >= 34) {
            try {
                appContext.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } catch (_: RuntimeException) {
                false
            }
        } else {
            true
        }
        val overlay = try {
            Settings.canDrawOverlays(appContext)
        } catch (_: RuntimeException) {
            false
        }
        return CriticalAlertPermissionState(
            canUseFullScreenIntent = fullScreen,
            canDrawOverlays = overlay,
        )
    }

    fun openFullScreenIntentSettings(context: Context): Boolean = try {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        context.startActivity(intent)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun openOverlaySettings(context: Context): Boolean = try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        context.startActivity(intent)
        true
    } catch (_: RuntimeException) {
        false
    }
}
