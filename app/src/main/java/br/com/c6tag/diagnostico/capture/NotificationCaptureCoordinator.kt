package br.com.c6tag.diagnostico.capture

import android.service.notification.StatusBarNotification
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class NotificationCaptureCoordinator(
    private val targetPackage: String,
    private val recorder: DiagnosticRecorder,
    private val captureStatus: CaptureStatus,
    scope: CoroutineScope,
    private val mapEvent: (
        StatusBarNotification,
        DiagnosticEventType,
        Long,
        Int?,
    ) -> DiagnosticEvent = NotificationSnapshotMapper::map,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val queue = Channel<CaptureWork>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            for (work in queue) {
                try {
                    when (work) {
                        is CaptureWork.Notification -> {
                            val event = mapEvent(
                                work.sbn,
                                work.type,
                                work.observedAtMs,
                                work.removalReason,
                            )
                            recorder.recordNotification(work.sbn.packageName) { event }
                        }

                        is CaptureWork.Lifecycle ->
                            recorder.recordLifecycle(work.type, work.observedAtMs)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (work is CaptureWork.Notification) {
                        captureStatus.recordFailure(
                            CaptureFailureCode.NOTIFICATION_MAPPING_FAILED,
                            work.observedAtMs,
                        )
                    }
                }
            }
        }
    }

    /**
     * Performs only the exact package check on the caller (listener callback)
     * thread. Notification fields are read later by the serialized worker.
     *
     * @return true when accepted into the queue.
     */
    fun enqueueNotification(
        sbn: StatusBarNotification,
        type: DiagnosticEventType,
        observedAtMs: Long,
        removalReason: Int? = null,
    ): Boolean {
        require(
            type == DiagnosticEventType.POSTED_OR_UPDATED ||
                type == DiagnosticEventType.REMOVED ||
                type == DiagnosticEventType.ACTIVE_SNAPSHOT,
        ) { "Only notification observations can be queued" }

        if (targetPackage.isBlank() || sbn.packageName != targetPackage) return false

        if (
            queue.trySend(
                CaptureWork.Notification(
                    sbn = sbn,
                    type = type,
                    observedAtMs = observedAtMs,
                    removalReason = removalReason,
                ),
            ).isFailure
        ) {
            captureStatus.recordFailure(CaptureFailureCode.CAPTURE_QUEUE_CLOSED, observedAtMs)
            return false
        }
        return true
    }

    fun recordLifecycle(type: DiagnosticEventType, observedAtMs: Long) {
        require(
            type == DiagnosticEventType.LISTENER_CONNECTED ||
                type == DiagnosticEventType.LISTENER_DISCONNECTED,
        ) { "Only listener lifecycle events can be queued" }

        if (queue.trySend(CaptureWork.Lifecycle(type, observedAtMs)).isFailure) {
            captureStatus.recordFailure(CaptureFailureCode.CAPTURE_QUEUE_CLOSED, observedAtMs)
        }
    }

    fun reportSnapshotQueryFailure(observedAtMs: Long) {
        captureStatus.recordFailure(
            CaptureFailureCode.ACTIVE_SNAPSHOT_QUERY_FAILED,
            observedAtMs,
        )
    }

    override fun close() {
        queue.close()
    }

    private sealed interface CaptureWork {
        data class Notification(
            val sbn: StatusBarNotification,
            val type: DiagnosticEventType,
            val observedAtMs: Long,
            val removalReason: Int?,
        ) : CaptureWork

        data class Lifecycle(
            val type: DiagnosticEventType,
            val observedAtMs: Long,
        ) : CaptureWork
    }
}
