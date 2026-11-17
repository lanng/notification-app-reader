package br.com.c6tag.diagnostico.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CaptureConnectionState {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

/** Safe diagnostic codes only; Android exception text and notification content are never retained. */
internal enum class CaptureFailureCode {
    ACTIVE_SNAPSHOT_QUERY_FAILED,
    NOTIFICATION_MAPPING_FAILED,
    CAPTURE_QUEUE_CLOSED,
}

internal data class CaptureStatusState(
    val connection: CaptureConnectionState = CaptureConnectionState.UNKNOWN,
    val connectionObservedAtMs: Long? = null,
    val targetPackageConfigured: Boolean,
    val errorCount: Long = 0L,
    val lastError: CaptureFailureCode? = null,
    val lastErrorAtMs: Long? = null,
)

/**
 * Process-local listener evidence and target configuration.
 *
 * This state deliberately starts UNKNOWN in every process and does not represent
 * notification-listener authorization or promise prompt detection of process death.
 */
internal class CaptureStatus(targetPackage: String) {
    private val mutableState = MutableStateFlow(
        CaptureStatusState(targetPackageConfigured = targetPackage.isNotBlank()),
    )

    val state: StateFlow<CaptureStatusState> = mutableState.asStateFlow()

    fun markConnected(observedAtMs: Long) {
        mutableState.update {
            it.copy(
                connection = CaptureConnectionState.CONNECTED,
                connectionObservedAtMs = observedAtMs,
            )
        }
    }

    fun markDisconnected(observedAtMs: Long) {
        mutableState.update {
            it.copy(
                connection = CaptureConnectionState.DISCONNECTED,
                connectionObservedAtMs = observedAtMs,
            )
        }
    }

    fun markUnknown(observedAtMs: Long) {
        mutableState.update {
            it.copy(
                connection = CaptureConnectionState.UNKNOWN,
                connectionObservedAtMs = observedAtMs,
            )
        }
    }

    fun recordFailure(code: CaptureFailureCode, observedAtMs: Long) {
        mutableState.update {
            it.copy(
                errorCount = it.errorCount + 1L,
                lastError = code,
                lastErrorAtMs = observedAtMs,
            )
        }
    }
}
