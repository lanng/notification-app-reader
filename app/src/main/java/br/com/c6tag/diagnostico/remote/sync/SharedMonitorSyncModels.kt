package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity

internal data class SharedHistoryEntry(
    val hostEntryId: Long,
    val kind: FinancialEntryKind,
    val amountCents: Long,
    val balanceAfterCents: Long,
    val recordedAtMs: Long,
)

internal data class SharedHistoryPage(
    val entries: List<SharedHistoryEntry>,
    val hasMore: Boolean,
    val nextBeforeEntryId: Long?,
)

internal data class HostSnapshotUpload(
    val balanceCents: Long?,
    val limitCents: Long,
    val repeatMinutes: Int,
    val pauseMinutes: Int,
    val needsReference: Boolean,
    val alertCycleId: String?,
    val alertOpenedAtMs: Long?,
    val alertEmissionCount: Int,
    val lastObservedAtMs: Long?,
    val referenceConfirmedAtMs: Long?,
    val entries: List<SharedHistoryEntry>,
)

internal data class SharedMonitorSnapshot(
    val monitorId: String,
    val hostGeneration: String,
    val balanceCents: Long?,
    val limitCents: Long,
    val repeatMinutes: Int,
    val pauseMinutes: Int,
    val needsReference: Boolean,
    val severity: SharedAlertSeverity,
    val alertCycleId: String?,
    val alertOpenedAtMs: Long?,
    val alertEmissionCount: Int,
    val guestAlertActive: Boolean,
    val guestAlertEligibleAtMs: Long?,
    val lastObservedAtMs: Long?,
    val referenceConfirmedAtMs: Long?,
    val syncedAtMs: Long,
    val snapshotVersion: Long,
    val serverTimeMs: Long,
)

internal enum class SharedSyncFailureReason {
    NOT_CONFIGURED,
    INVALID_ORIGIN,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    NETWORK_UNAVAILABLE,
    SERVER_ERROR,
    MALFORMED_RESPONSE,
}

internal sealed interface SharedSyncResult<out T> {
    data class Success<T>(val value: T) : SharedSyncResult<T>
    data class Failure(val reason: SharedSyncFailureReason) : SharedSyncResult<Nothing>
}

internal fun HistoryFilter.toApiQueryValue(): String = when (this) {
    HistoryFilter.ALL -> "all"
    HistoryFilter.ENTRIES -> "entries"
    HistoryFilter.EXITS -> "exits"
    HistoryFilter.ADJUSTMENTS -> "adjustments"
}
