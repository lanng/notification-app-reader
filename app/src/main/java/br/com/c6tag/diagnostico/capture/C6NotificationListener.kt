package br.com.c6tag.diagnostico.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import br.com.c6tag.diagnostico.CollectorApplication
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType

class C6NotificationListener : NotificationListenerService() {
    private val collector: CollectorApplication
        get() = application as CollectorApplication

    override fun onListenerConnected() {
        super.onListenerConnected()
        val connectedAtMs = System.currentTimeMillis()
        collector.captureStatus.markConnected(connectedAtMs)
        collector.captureCoordinator.recordLifecycle(
            DiagnosticEventType.LISTENER_CONNECTED,
            connectedAtMs,
        )

        try {
            val active = activeNotifications
            if (active == null) {
                collector.captureCoordinator.reportSnapshotQueryFailure(System.currentTimeMillis())
                return
            }
            for (sbn in active) {
                collector.captureCoordinator.enqueueNotification(
                    sbn = sbn,
                    type = DiagnosticEventType.ACTIVE_SNAPSHOT,
                    observedAtMs = System.currentTimeMillis(),
                )
            }
        } catch (_: Exception) {
            collector.captureCoordinator.reportSnapshotQueryFailure(System.currentTimeMillis())
        }
    }

    override fun onNotificationPosted(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
    ) {
        val observedAtMs = System.currentTimeMillis()
        collector.captureCoordinator.enqueueNotification(
            sbn = sbn,
            type = DiagnosticEventType.POSTED_OR_UPDATED,
            observedAtMs = observedAtMs,
        )
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        val observedAtMs = System.currentTimeMillis()
        collector.captureCoordinator.enqueueNotification(
            sbn = sbn,
            type = DiagnosticEventType.REMOVED,
            observedAtMs = observedAtMs,
            removalReason = reason,
        )
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val disconnectedAtMs = System.currentTimeMillis()
        collector.captureStatus.markDisconnected(disconnectedAtMs)
        collector.captureCoordinator.recordLifecycle(
            DiagnosticEventType.LISTENER_DISCONNECTED,
            disconnectedAtMs,
        )
    }

    override fun onDestroy() {
        collector.captureStatus.markUnknown(System.currentTimeMillis())
        super.onDestroy()
    }
}
