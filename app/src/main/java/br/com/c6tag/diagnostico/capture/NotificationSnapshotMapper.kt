package br.com.c6tag.diagnostico.capture

import android.app.Notification
import android.service.notification.StatusBarNotification
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.NotificationClassifier

internal object NotificationSnapshotMapper {
    private val classifier = NotificationClassifier()

    fun map(
        sbn: StatusBarNotification,
        type: DiagnosticEventType,
        observedAtMs: Long,
        removalReason: Int? = null,
    ): DiagnosticEvent {
        require(
            type == DiagnosticEventType.POSTED_OR_UPDATED ||
                type == DiagnosticEventType.REMOVED ||
                type == DiagnosticEventType.ACTIVE_SNAPSHOT,
        ) { "Only notification observations can be mapped" }

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            .orEmpty()
        val groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

        return DiagnosticEvent(
            type = type,
            observedAtMs = observedAtMs,
            postedAtMs = sbn.postTime,
            packageName = sbn.packageName,
            key = sbn.key,
            notificationId = sbn.id,
            tag = sbn.tag,
            groupKey = sbn.groupKey,
            groupSummary = groupSummary,
            title = title,
            text = text,
            bigText = bigText,
            lines = lines,
            removalReason = if (type == DiagnosticEventType.REMOVED) removalReason else null,
            interpretation = classifier.classify(
                title = title,
                text = text,
                bigText = bigText,
                lines = lines,
                groupSummary = groupSummary,
            ),
        )
    }
}
