package br.com.c6tag.diagnostico.remote.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import br.com.c6tag.diagnostico.R
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal object CriticalAlertController {
    const val CHANNEL_ID = "critical_alarm_alert_v1"
    const val NOTIFICATION_ID = 6_003

    fun ensureChannel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.critical_alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.critical_alarm_channel_description)
            // Sound and vibration are played continuously for 30s by CriticalAlarmPlayer
            // so audio never doubles up and stops immediately when the user taps Stop.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun trigger(
        context: Context,
        isGuest: Boolean,
        balanceCents: Long?,
        limitCents: Long,
        cycleId: String?,
    ): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permissionGranted || !manager.areNotificationsEnabled()) {
            return false
        }

        ensureChannel(appContext)
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }

        val activeAlarm = CriticalAlarmPlayer.start(
            context = appContext,
            isGuest = isGuest,
            balanceCents = balanceCents,
            limitCents = limitCents,
            cycleId = cycleId,
        )

        val fullScreenIntent = buildActivityIntent(
            context = appContext,
            occurrenceId = activeAlarm.occurrenceId,
            isGuest = isGuest,
            balanceCents = balanceCents,
            limitCents = limitCents,
            cycleId = cycleId,
        )
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val fullScreenPendingIntent = PendingIntent.getActivity(
            appContext,
            20,
            fullScreenIntent,
            immutable,
        )

        val stopIntent = Intent(appContext, CriticalAlertStopReceiver::class.java).apply {
            action = CriticalAlertStopReceiver.ACTION_STOP
            data = Uri.Builder().scheme("c6tag-monitor").authority("critical")
                .appendPath(activeAlarm.occurrenceId).appendPath("stop").build()
            putExtra(CriticalAlertStopReceiver.EXTRA_OCCURRENCE_ID, activeAlarm.occurrenceId)
        }
        val ackIntent = Intent(appContext, CriticalAlertStopReceiver::class.java).apply {
            action = CriticalAlertStopReceiver.ACTION_ACKNOWLEDGE
            data = Uri.Builder().scheme("c6tag-monitor").authority("critical")
                .appendPath(activeAlarm.occurrenceId).appendPath("ack").build()
            putExtra(CriticalAlertStopReceiver.EXTRA_OCCURRENCE_ID, activeAlarm.occurrenceId)
            putExtra(CriticalAlertStopReceiver.EXTRA_IS_GUEST, isGuest)
            cycleId?.let { putExtra(CriticalAlertStopReceiver.EXTRA_CYCLE_ID, it) }
        }

        val balanceText = balanceCents?.let(::formatBrl)
            ?: appContext.getString(R.string.guest_balance_unavailable)
        val limitText = formatBrl(limitCents)
        val title = appContext.getString(
            if (isGuest) R.string.guest_alert_critical_title else R.string.monitor_alert_critical_title,
        )
        val content = appContext.getString(R.string.critical_alarm_notification_body, balanceText, limitText)

        val permissions = CriticalAlertPermissions.read(appContext)
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tag_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(fullScreenPendingIntent)
            .setDeleteIntent(PendingIntent.getBroadcast(appContext, 23, stopIntent, immutable))
            .addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.critical_alarm_stop_action),
                    PendingIntent.getBroadcast(appContext, 21, stopIntent, immutable),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.monitor_alert_acknowledge),
                    PendingIntent.getBroadcast(appContext, 22, ackIntent, immutable),
                ).build(),
            )

        if (permissions.canUseFullScreenIntent) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        }

        val posted = try {
            manager.notify(NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            // Fallback without full-screen intent if system revokes it mid-call.
            try {
                builder.setFullScreenIntent(null, false)
                manager.notify(NOTIFICATION_ID, builder.build())
                true
            } catch (_: Exception) {
                CriticalAlarmPlayer.stop(activeAlarm.occurrenceId)
                false
            }
        } catch (_: RuntimeException) {
            CriticalAlarmPlayer.stop(activeAlarm.occurrenceId)
            false
        }

        // If user granted optional "Display over other apps" (SYSTEM_ALERT_WINDOW),
        // launch the fullscreen alarm immediately even while unlocked in another app.
        if (posted && canLaunchOverOtherApps(appContext)) {
            try {
                appContext.startActivity(fullScreenIntent)
            } catch (_: RuntimeException) {
                // Notification + fullScreenIntent fallback remains active.
            }
        }
        return posted
    }

    fun stopAndCancel(context: Context, expectedOccurrenceId: String? = null) {
        CriticalAlarmPlayer.stop(expectedOccurrenceId)
        try {
            context.applicationContext
                .getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        } catch (_: RuntimeException) {
            // Safe cancel.
        }
    }

    fun stopForRole(context: Context, isGuest: Boolean) {
        val current = CriticalAlarmPlayer.active.value
        if (current != null && current.isGuest == isGuest) {
            stopAndCancel(context, current.occurrenceId)
        }
    }

    private fun canLaunchOverOtherApps(context: Context): Boolean = try {
        Settings.canDrawOverlays(context)
    } catch (_: RuntimeException) {
        false
    }

    private fun buildActivityIntent(
        context: Context,
        occurrenceId: String,
        isGuest: Boolean,
        balanceCents: Long?,
        limitCents: Long,
        cycleId: String?,
    ): Intent = Intent(context, CriticalAlertActivity::class.java).apply {
        action = CriticalAlertActivity.ACTION_SHOW_CRITICAL_ALARM
        data = Uri.Builder().scheme("c6tag-monitor").authority("critical-alarm")
            .appendPath(occurrenceId).build()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(CriticalAlertActivity.EXTRA_OCCURRENCE_ID, occurrenceId)
        putExtra(CriticalAlertActivity.EXTRA_IS_GUEST, isGuest)
        balanceCents?.let { putExtra(CriticalAlertActivity.EXTRA_BALANCE_CENTS, it) }
        putExtra(CriticalAlertActivity.EXTRA_LIMIT_CENTS, limitCents)
        cycleId?.let { putExtra(CriticalAlertActivity.EXTRA_CYCLE_ID, it) }
    }

    private fun formatBrl(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
            .format(BigDecimal.valueOf(cents, 2))
}
