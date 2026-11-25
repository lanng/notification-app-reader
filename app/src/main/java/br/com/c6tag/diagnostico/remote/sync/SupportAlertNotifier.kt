package br.com.c6tag.diagnostico.remote.sync

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
import br.com.c6tag.diagnostico.MainActivity
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal interface GuestAlertNotifier {
    fun ensureChannel()
    fun notify(snapshot: SharedMonitorSnapshot): Boolean
    fun cancel()
}

internal class SupportAlertNotifier(
    private val context: Context,
) : GuestAlertNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    override fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.guest_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appContext.getString(R.string.guest_alert_channel_description)
            setSound(
                Settings.System.DEFAULT_NOTIFICATION_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun notify(snapshot: SharedMonitorSnapshot): Boolean {
        if (!canPostNotifications()) return false
        ensureChannel()
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return false
        }

        val balanceText = snapshot.balanceCents?.let(::formatBrl)
            ?: appContext.getString(R.string.guest_balance_unavailable)
        val limitText = formatBrl(snapshot.limitCents)

        val titleRes = if (snapshot.severity == SharedAlertSeverity.CRITICAL) {
            R.string.guest_alert_critical_title
        } else {
            R.string.guest_alert_attention_title
        }
        val content = appContext.getString(
            R.string.guest_alert_content,
            balanceText,
            limitText,
        )

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("c6tag-monitor").authority("guest").build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val ackIntent = Intent(appContext, SupportAlertActionReceiver::class.java).apply {
            action = SupportAlertActionReceiver.ACTION_ACKNOWLEDGE
            data = Uri.Builder().scheme("c6tag-monitor").authority("guest-alert").appendPath("ack").build()
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tag_notification)
            .setContentTitle(appContext.getString(titleRes))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(PendingIntent.getActivity(appContext, 10, openIntent, immutable))
            .addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(R.string.monitor_alert_acknowledge),
                    PendingIntent.getBroadcast(appContext, 11, ackIntent, immutable),
                ).build(),
            )
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    private fun formatBrl(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"))
            .format(BigDecimal.valueOf(cents, 2))

    companion object {
        const val CHANNEL_ID = "support_shared_alert_v1"
        const val NOTIFICATION_ID = 6_002
    }
}
