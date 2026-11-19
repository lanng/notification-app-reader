package br.com.c6tag.diagnostico.remote.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import br.com.c6tag.diagnostico.R
import java.util.UUID

/**
 * ACCEPTED means the notification platform call returned normally; it does not prove delivery,
 * visibility, or audibility. Channel preferences and device policy remain user-controlled.
 */
internal enum class ProbeSubmissionOutcome {
    ACCEPTED,
    PERMISSION_DENIED,
    CHANNEL_BLOCKED,
    FAILED,
}

internal enum class ProbeCapabilityStatus {
    GRANTED,
    DENIED,
    UNKNOWN,
}

internal enum class ProbeChannelStatus {
    NOT_CREATED,
    AVAILABLE,
    BLOCKED,
    UNKNOWN,
}

internal enum class ProbeSessionReadStatus {
    CONFIRMED_EMPTY,
    OCCURRENCE_PRESENT,
    UNKNOWN,
}

internal data class ProbeControllerStatus(
    val permission: ProbeCapabilityStatus,
    val appNotifications: ProbeCapabilityStatus,
    val channel: ProbeChannelStatus,
    val sessionReadStatus: ProbeSessionReadStatus,
    val storedOccurrenceId: String?,
)

internal interface ProbeNotificationPlatform {
    fun permissionGranted(): Boolean
    fun notificationsEnabled(): Boolean
    fun channel(): NotificationChannel?
    fun createChannel(channel: NotificationChannel)
    fun publish(notificationId: Int, notification: Notification)
    fun cancel(notificationId: Int)
}

internal class ProbeNotificationController(
    context: Context,
    private val store: ProbeSessionStore = ProbeSessionStore(context.applicationContext),
    platformOverride: ProbeNotificationPlatform? = null,
    notificationFactoryOverride: ((String) -> Notification)? = null,
) {
    private val context = context.applicationContext
    private val platform = platformOverride ?: AndroidProbeNotificationPlatform(this.context)
    private val notificationFactory = notificationFactoryOverride ?: { occurrenceId ->
        buildNotification(occurrenceId)
    }
    private var lastKnownOccurrenceId: String? = null

    /**
     * A previous ID preserves the UI retry hint only; [stop] always revalidates stored ownership.
     */
    fun readStatus(previouslyKnownOccurrenceId: String? = null): ProbeControllerStatus {
        val (occurrenceId, sessionReadStatus) = synchronized(ProbeSessionStore.processLock) {
            try {
                val currentOccurrenceId = store.currentOccurrenceId()
                lastKnownOccurrenceId = currentOccurrenceId
                currentOccurrenceId to if (currentOccurrenceId == null) {
                    ProbeSessionReadStatus.CONFIRMED_EMPTY
                } else {
                    ProbeSessionReadStatus.OCCURRENCE_PRESENT
                }
            } catch (_: Exception) {
                (lastKnownOccurrenceId ?: previouslyKnownOccurrenceId) to
                    ProbeSessionReadStatus.UNKNOWN
            }
        }
        val permission = readCapability { platform.permissionGranted() }
        val appNotifications = readCapability { platform.notificationsEnabled() }
        val channel = try {
            when (val current = platform.channel()) {
                null -> ProbeChannelStatus.NOT_CREATED
                else -> if (current.importance == NotificationManager.IMPORTANCE_NONE) {
                    ProbeChannelStatus.BLOCKED
                } else {
                    ProbeChannelStatus.AVAILABLE
                }
            }
        } catch (_: Exception) {
            ProbeChannelStatus.UNKNOWN
        }
        return ProbeControllerStatus(
            permission,
            appNotifications,
            channel,
            sessionReadStatus,
            occurrenceId,
        )
    }

    fun submitCritical(): ProbeSubmissionOutcome = synchronized(ProbeSessionStore.processLock) {
        try {
            if (!platform.permissionGranted() || !platform.notificationsEnabled()) {
                return@synchronized ProbeSubmissionOutcome.PERMISSION_DENIED
            }

            if (platform.channel() == null) {
                platform.createChannel(createChannel())
            }
            val channel = platform.channel()
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return@synchronized ProbeSubmissionOutcome.CHANNEL_BLOCKED
            }

            if (store.currentOccurrenceId() != null) {
                platform.cancel(NOTIFICATION_ID)
            }

            val occurrenceId = UUID.randomUUID().toString()
            if (!store.persistOccurrenceId(occurrenceId)) {
                store.clearCurrentIfMatches(occurrenceId)
                return@synchronized ProbeSubmissionOutcome.FAILED
            }
            lastKnownOccurrenceId = occurrenceId

            val notification = try {
                notificationFactory(occurrenceId)
            } catch (_: SecurityException) {
                if (store.clearCurrentIfMatches(occurrenceId)) lastKnownOccurrenceId = null
                return@synchronized ProbeSubmissionOutcome.PERMISSION_DENIED
            } catch (_: Exception) {
                if (store.clearCurrentIfMatches(occurrenceId)) lastKnownOccurrenceId = null
                return@synchronized ProbeSubmissionOutcome.FAILED
            }
            try {
                platform.publish(NOTIFICATION_ID, notification)
                ProbeSubmissionOutcome.ACCEPTED
            } catch (_: SecurityException) {
                cleanupFailedPublication(occurrenceId)
                ProbeSubmissionOutcome.PERMISSION_DENIED
            } catch (_: Exception) {
                cleanupFailedPublication(occurrenceId)
                ProbeSubmissionOutcome.FAILED
            }
        } catch (_: SecurityException) {
            ProbeSubmissionOutcome.PERMISSION_DENIED
        } catch (_: Exception) {
            ProbeSubmissionOutcome.FAILED
        }
    }

    fun stop(expectedOccurrenceId: String): Boolean =
        synchronized(ProbeSessionStore.processLock) {
            try {
                val currentOccurrenceId = store.currentOccurrenceId()
                lastKnownOccurrenceId = currentOccurrenceId
                if (currentOccurrenceId != expectedOccurrenceId) return@synchronized false
                platform.cancel(NOTIFICATION_ID)
                val cleared = store.clearCurrentIfMatches(expectedOccurrenceId)
                if (cleared) lastKnownOccurrenceId = null
                cleared
            } catch (_: Exception) {
                false
            }
        }

    private fun cleanupFailedPublication(occurrenceId: String) {
        if (store.currentOccurrenceId() != occurrenceId) return
        try {
            platform.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // Keep the matching identity so the stop action can retry cancellation.
            return
        }
        if (store.clearCurrentIfMatches(occurrenceId)) lastKnownOccurrenceId = null
    }

    private inline fun readCapability(read: () -> Boolean): ProbeCapabilityStatus =
        try {
            if (read()) ProbeCapabilityStatus.GRANTED else ProbeCapabilityStatus.DENIED
        } catch (_: Exception) {
            ProbeCapabilityStatus.UNKNOWN
        }

    private fun createChannel() = NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.probe_critical_channel_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.probe_critical_channel_description)
        setSound(
            Settings.System.DEFAULT_NOTIFICATION_URI,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        setBypassDnd(false)
    }

    private fun buildNotification(occurrenceId: String): Notification {
        val stopIntent = Intent(context, ProbeStopReceiver::class.java).apply {
            action = STOP_ACTION
            data = Uri.Builder()
                .scheme("monitor-probe")
                .authority("sound")
                .appendPath(occurrenceId)
                .build()
            putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tag_notification)
            .setContentTitle(context.getString(R.string.probe_critical_title))
            .setContentText(context.getString(R.string.probe_synthetic_only))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            // Platform timeout/insistent flags are probes, not proof of 30 seconds of audio.
            .setTimeoutAfter(TIMEOUT_AFTER_MILLIS)
            .setAutoCancel(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.probe_stop_sound),
                    stopPendingIntent,
                ).build(),
            )
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    companion object {
        const val CHANNEL_ID = "monitor_critical_probe_v1"
        const val NOTIFICATION_ID = 6_099
        const val STOP_ACTION = "br.com.c6tag.diagnostico.remote.probe.STOP"
        const val EXTRA_OCCURRENCE_ID = "occurrenceId"
        const val TIMEOUT_AFTER_MILLIS = 30_000L
    }
}

private class AndroidProbeNotificationPlatform(
    private val context: Context,
) : ProbeNotificationPlatform {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun permissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun notificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    override fun channel(): NotificationChannel? =
        manager.getNotificationChannel(ProbeNotificationController.CHANNEL_ID)

    override fun createChannel(channel: NotificationChannel) {
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun publish(notificationId: Int, notification: Notification) {
        if (!permissionGranted()) throw SecurityException("Notification permission unavailable")
        manager.notify(notificationId, notification)
    }

    override fun cancel(notificationId: Int) {
        manager.cancel(notificationId)
    }
}
