package br.com.c6tag.diagnostico.monitor.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import br.com.c6tag.diagnostico.MainActivity
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal data class NotificationCapability(
    val permissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val channelExists: Boolean,
    val channelBlocked: Boolean,
) {
    val canSubmit: Boolean
        get() = permissionGranted && appNotificationsEnabled && channelExists && !channelBlocked
}

internal interface MonitorNotificationPlatform {
    fun permissionGranted(): Boolean
    fun notificationsEnabled(): Boolean
    fun channel(): NotificationChannel?
    fun createChannel(channel: NotificationChannel)
    fun publish(notification: Notification)
    fun cancel()
}

internal class MonitorNotifier(
    private val context: Context,
    platformOverride: MonitorNotificationPlatform? = null,
) {
    private val platform = platformOverride ?: AndroidNotificationPlatform(context.applicationContext)

    fun capabilities(): NotificationCapability {
        val channel = platform.channel()
        return NotificationCapability(
            platform.permissionGranted(), platform.notificationsEnabled(), channel != null,
            channel?.importance == NotificationManager.IMPORTANCE_NONE,
        )
    }

    fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome = try {
        val notification = build(overview, intent)
        if (notification == null) {
            SubmissionOutcome.FAILED
        } else {
            ensureChannel()
            val capability = capabilities()
            when {
                !capability.permissionGranted || !capability.appNotificationsEnabled -> SubmissionOutcome.PERMISSION_DENIED
                !capability.channelExists || capability.channelBlocked -> SubmissionOutcome.CHANNEL_BLOCKED
                else -> {
                    platform.publish(notification)
                    SubmissionOutcome.ACCEPTED
                }
            }
        }
    } catch (_: SecurityException) {
        SubmissionOutcome.PERMISSION_DENIED
    } catch (_: Exception) {
        SubmissionOutcome.FAILED
    }

    fun cancel() = platform.cancel()

    fun ensureChannel() {
        if (platform.channel() != null) return
        platform.createChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.monitor_alert_channel_name), NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.monitor_alert_channel_description)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    fun build(overview: MonitorOverview, intent: EmissionIntent): Notification? {
        val cycle = overview.alertCycle ?: return null
        val cents = overview.balanceCents ?: return null
        if (cycle.status != AlertStatus.ACTIVE || cycle.id != intent.cycleId ||
            cycle.version != intent.version || cycle.emissionIntent != intent) return null
        val open = Intent(context, MainActivity::class.java).apply {
            data = Uri.Builder().scheme("c6tag-monitor").authority("overview").appendPath(cycle.id).build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acknowledgment = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_ACKNOWLEDGE
            data = Uri.Builder().scheme("c6tag-monitor").authority("alert")
                .appendPath(cycle.id).appendPath("acknowledge").build()
            putExtra(AlertActionReceiver.EXTRA_CYCLE_ID, cycle.id)
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val content = context.getString(
            R.string.monitor_alert_content, money(cents), money(overview.settings.limitCents),
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tag_notification)
            .setContentTitle(context.getString(
                if (overview.needsReference) R.string.monitor_alert_review_title else R.string.monitor_alert_low_title,
            ))
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, immutable))
            .addAction(Notification.Action.Builder(
                null, context.getString(R.string.monitor_alert_acknowledge),
                PendingIntent.getBroadcast(context, 0, acknowledgment, immutable),
            ).build())
            .build()
    }

    private fun money(cents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(cents, 2))

    companion object {
        const val CHANNEL_ID = "monitor_low_balance_v1"
        const val NOTIFICATION_ID = 6_001
    }
}

private class AndroidNotificationPlatform(private val context: Context) : MonitorNotificationPlatform {
    private val manager = context.getSystemService(NotificationManager::class.java)
    override fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    override fun notificationsEnabled() = manager.areNotificationsEnabled()
    override fun channel(): NotificationChannel? = manager.getNotificationChannel(MonitorNotifier.CHANNEL_ID)
    override fun createChannel(channel: NotificationChannel) = manager.createNotificationChannel(channel)
    @SuppressLint("MissingPermission") // Checked by the notifier and again at the platform boundary.
    override fun publish(notification: Notification) {
        if (!permissionGranted()) throw SecurityException("Notification permission unavailable")
        manager.notify(MonitorNotifier.NOTIFICATION_ID, notification)
    }
    override fun cancel() = manager.cancel(MonitorNotifier.NOTIFICATION_ID)
}
