package br.com.c6tag.diagnostico.remote.probe

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import android.provider.Settings
import androidx.core.net.toUri

internal enum class ProbeFullScreenAccessStatus {
    GRANTED,
    DENIED,
    UNKNOWN,
}

internal interface ProbeFullScreenAccess {
    fun capabilityStatus(): ProbeFullScreenAccessStatus
    fun openSettings(activity: Activity): Boolean
}

internal class AndroidProbeFullScreenAccess(
    context: Context,
) : ProbeFullScreenAccess {
    private val applicationContext = context.applicationContext

    override fun capabilityStatus(): ProbeFullScreenAccessStatus = try {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                if (manager.canUseFullScreenIntent()) {
                    ProbeFullScreenAccessStatus.GRANTED
                } else {
                    ProbeFullScreenAccessStatus.DENIED
                }
            }

            Build.VERSION.SDK_INT >= 29 -> {
                if (applicationContext.checkSelfPermission(Manifest.permission.USE_FULL_SCREEN_INTENT) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    ProbeFullScreenAccessStatus.GRANTED
                } else {
                    ProbeFullScreenAccessStatus.DENIED
                }
            }

            else -> ProbeFullScreenAccessStatus.GRANTED
        }
    } catch (_: Exception) {
        ProbeFullScreenAccessStatus.UNKNOWN
    }

    override fun openSettings(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = "package:${activity.packageName}".toUri()
                },
            )
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

internal interface ProbeWindowPresentation {
    fun show()
    fun clear()
}

@Suppress("DEPRECATION")
internal class AndroidProbeWindowPresentation(
    private val activity: Activity,
) : ProbeWindowPresentation {
    override fun show() {
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    override fun clear() {
        if (Build.VERSION.SDK_INT >= 27) {
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        } else {
            activity.window.clearFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}
