package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.monitor.alerts.AlertCycle
import br.com.c6tag.diagnostico.monitor.alerts.AlertSettings
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome

internal enum class MovementKind { TAG_DEBIT, PIX_CREDIT }

internal data class FinancialObservation(val id: Long, val event: DiagnosticEvent)

internal data class FinancialCandidate(
    val observationId: Long,
    val type: DiagnosticEventType,
    val observedAtMs: Long,
    val packageName: String?,
    val evidence: SourceEvidence?,
)

internal data class BalanceReference(
    val id: Long,
    val balanceCents: Long,
    val confirmedAtMs: Long,
    val observationCursor: Long,
    val sessionOpenedAtMs: Long = confirmedAtMs,
    val cursorAtSessionOpen: Long = observationCursor,
    val explicitlyIncludedObservationIds: Set<Long> = emptySet(),
    val sessionId: String? = null,
)

internal data class SourceHistory(
    val matchingPublication: SourceValue? = null,
    val sameKeySeen: Boolean = false,
    val identicalContentSeen: Boolean = false,
)

internal data class SourceValue(val kind: MovementKind?, val amountCents: Long?)

internal data class PublicationIdentity(val packageName: String, val key: String, val postedAtMs: Long)

internal data class SourceEvidence(
    val publication: PublicationIdentity?,
    val value: SourceValue,
    val contentHash: String,
)

internal enum class PendingReason {
    INVALID_AMOUNT,
    INSUFFICIENT_IDENTITY,
    CONFLICTING_PUBLICATION,
    REUSED_KEY,
    IDENTICAL_CONTENT,
    UNCERTAIN_BOUNDARY,
    REFERENCE_IN_PROGRESS,
    CONCURRENT_REFERENCE,
    AMOUNT_OVERFLOW,
}

internal data class MovementContext(
    val expectedPackageName: String,
    val reference: BalanceReference?,
    val balanceCents: Long,
    val sources: SourceHistory = SourceHistory(),
    val referenceSessionActive: Boolean = false,
)

internal sealed interface MovementDecision {
    data class Apply(
        val kind: MovementKind,
        val amountCents: Long,
        val balanceCents: Long,
    ) : MovementDecision

    data object Ignore : MovementDecision
    data class Pending(val reason: PendingReason) : MovementDecision
}

internal enum class ReferenceSessionStatus { ACTIVE, CONFIRMED, CANCELLED, EXPIRED }

internal data class ReferenceSession(
    val id: String,
    val openedAtMs: Long,
    val observationCursor: Long,
    val baseReferenceId: Long?,
    val status: ReferenceSessionStatus = ReferenceSessionStatus.ACTIVE,
)

internal data class ReferenceCandidate(val observationId: Long, val observedAtMs: Long)

internal data class ConfirmReference(
    val sessionId: String,
    val expectedReferenceId: Long?,
    val balanceCents: Long,
    val limitCents: Long,
    val nowMs: Long,
    val explicitlyIncludedObservationIds: Set<Long> = emptySet(),
)

internal enum class RejectionCode {
    INVALID_INPUT, INVALID_TIME, SESSION_NOT_ACTIVE, SESSION_MISMATCH,
    SESSION_EXPIRED, STALE_REFERENCE, UNKNOWN_OBSERVATION, UNREADABLE_AMOUNT,
    ALREADY_APPLIED, AMOUNT_OVERFLOW, REFERENCE_IN_PROGRESS, TIME_OVERFLOW,
}

internal enum class ReferencePosition { COVERED, CONCURRENT, AFTER, UNCERTAIN }

internal sealed interface ReferenceDecision {
    data class Confirmed(
        val reference: BalanceReference,
        val limitCents: Long,
        val session: ReferenceSession,
        val coveredObservationIds: Set<Long>,
        val pendingObservationIds: Set<Long>,
    ) : ReferenceDecision

    data class Cancelled(
        val session: ReferenceSession,
        val reevaluateObservationIds: Set<Long>,
        val retainedObservationIds: Set<Long>,
    ) : ReferenceDecision

    data class Expired(
        val session: ReferenceSession,
        val retainedObservationIds: Set<Long>,
    ) : ReferenceDecision

    data object Unchanged : ReferenceDecision
    data class Rejected(val code: RejectionCode) : ReferenceDecision
}

internal enum class PendingStatus { OPEN, APPLIED, NOT_COUNTED, COVERED_BY_REFERENCE }
internal enum class PendingChoice { CONFIRM_NEW, DO_NOT_COUNT }

internal data class PendingMovement(
    val id: Long,
    val referenceId: Long?,
    val sourceId: Long,
    val value: SourceValue,
    val status: PendingStatus = PendingStatus.OPEN,
    val sourceAlreadyApplied: Boolean = false,
    val reason: PendingReason = PendingReason.UNCERTAIN_BOUNDARY,
    val observationId: Long = 0L,
    val createdAtMs: Long = 0L,
    val rawEvidenceAvailable: Boolean = false,
)

// The amount is deliberately absent: a decision cannot edit a bank observation.
internal data class ResolvePending(
    val pendingId: Long,
    val expectedReferenceId: Long,
    val choice: PendingChoice,
    val nowMs: Long = 0L,
)

internal sealed interface PendingResolution {
    data class Applied(val movement: MovementDecision.Apply) : PendingResolution
    data object NotCounted : PendingResolution
    data object AlreadyResolved : PendingResolution
    data class Rejected(val code: RejectionCode) : PendingResolution
}

internal enum class RevisionCause {
    BOOTSTRAP, OBSERVATION, REFERENCE, REFERENCE_SESSION, PENDING_RESOLUTION,
    SETTINGS, ACKNOWLEDGMENT, APPEARANCE, SUBMISSION, PROCESS_SESSION, ALERT_EVALUATION,
}

internal data class MonitorRevision(val value: Long = 0L, val cause: RevisionCause = RevisionCause.BOOTSTRAP)
internal enum class ThemePreference { SYSTEM, LIGHT, DARK }
internal data class MonitorSettings(
    val limitCents: Long = 30_000L,
    val alerts: AlertSettings = AlertSettings(),
    val theme: ThemePreference = ThemePreference.SYSTEM,
)

internal data class MonitorOverview(
    val reference: BalanceReference? = null,
    val balanceCents: Long? = null,
    val settings: MonitorSettings = MonitorSettings(),
    val continuityPreserved: Boolean = false,
    val pendingCount: Long = 0L,
    val referenceSession: ReferenceSession? = null,
    val alertCycle: AlertCycle? = null,
    val lastObservedAtMs: Long? = null,
    val revision: MonitorRevision = MonitorRevision(),
) {
    val needsReference: Boolean
        get() = reference != null && (!continuityPreserved || pendingCount > 0L || referenceSession != null)
}

internal enum class FinancialEntryKind { REFERENCE, TAG_DEBIT, PIX_CREDIT }
internal enum class HistoryFilter { ALL, ENTRIES, EXITS, ADJUSTMENTS }
internal data class FinancialEntry(
    val id: Long,
    val kind: FinancialEntryKind,
    val referenceId: Long,
    val sourceId: Long?,
    val amountCents: Long,
    val balanceAfterCents: Long,
    val recordedAtMs: Long,
    val observationId: Long?,
)

internal data class MonitorResult(
    val overview: MonitorOverview,
    val rejection: RejectionCode? = null,
    val session: ReferenceSession? = overview.referenceSession,
)

internal data class ChangeSettings(
    val expectedReferenceId: Long?,
    val settings: MonitorSettings,
    val nowMs: Long,
)

internal data class RecordSubmission(
    val intent: EmissionIntent,
    val outcome: SubmissionOutcome,
    val nowMs: Long,
)
