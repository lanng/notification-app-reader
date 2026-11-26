package br.com.c6tag.diagnostico.remote.access

internal enum class RemoteAccessRoleMode {
    CHECKING,
    UNASSIGNED,
    HOST_PRESENT,
    SUPPORT_PRESENT,
    DUAL_ROLE_CONFLICT,
    STORAGE_UNAVAILABLE,
}

internal enum class SupportPairingPhase {
    EMPTY,
    REDEEMING,
    PENDING,
    VALIDATING,
    CONFIRMED,
    OFFLINE_STALE,
    CREDENTIAL_LOST,
    REDEMPTION_UNCERTAIN,
    ROLE_CONFLICT,
    RECOVERABLE_ERROR,
}

internal data class PendingSupportPairingInfo(
    val requestId: String,
    val installationId: String,
    val requestExpiresAtMillis: Long,
)

internal data class ConfirmedSupportAccess(
    val requestId: String,
    val installationId: String,
    val monitorId: String,
    val membershipId: String,
    val observedGeneration: OwnerGeneration,
)

/**
 * Public-safe UI state for candidate support pairing and restoration.
 * Never carries PATs, invitation secrets, raw server messages, or Google UIDs.
 */
internal data class SupportPairingViewState(
    val phase: SupportPairingPhase = SupportPairingPhase.EMPTY,
    val pending: PendingSupportPairingInfo? = null,
    val confirmed: ConfirmedSupportAccess? = null,
    val safeFailure: PairingAccessFailureReason? = null,
    val retryAfterSeconds: Long? = null,
    val storageProblem: RemoteSupportSessionProblem? = null,
    val roleConflict: RemoteRoleGuardBlock? = null,
    val canReplaceUncertainAttempt: Boolean = false,
    val previousAttemptMayRemainActiveOnHost: Boolean = false,
)

internal const val MAX_ACTIVE_SUPPORT_MEMBERSHIPS = 3

internal enum class HostSupportPhase {
    IDLE,
    LOADING,
    READY,
    MUTATING,
    STALE,
    AUTHORIZATION_REQUIRED,
    HOST_UNAVAILABLE,
    RECOVERABLE_ERROR,
}

internal enum class HostSupportDecisionAction {
    APPROVE_REQUEST,
    REJECT_REQUEST,
    REVOKE_SUPPORT,
}

internal data class HostSupportDecisionConfirmation(
    val decisionId: String,
    val action: HostSupportDecisionAction,
    val targetId: String,
    val installationId: String,
    val targetLabel: String?,
    val targetTimestampMillis: Long,
) {
    override fun toString(): String =
        "HostSupportDecisionConfirmation(action=$action, targetId=[REDACTED], installationId=[REDACTED])"
}

internal data class HostEmittedInvitationNotice(
    val id: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val copyAvailable: Boolean,
)

internal enum class HostSupportReconciliationKind {
    ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
    APPROVAL_CONFIRMED_BY_SUPPORT_LIST,
    APPROVAL_UNCERTAIN,
    REJECTION_INDETERMINATE,
    CANCEL_CONFIRMED_ABSENT_CAUSE_UNKNOWN,
    REVOKE_CONFIRMED_ABSENT_CAUSE_UNKNOWN,
    STILL_PENDING,
    STILL_ACTIVE,
    STILL_AVAILABLE,
    RECONCILIATION_UNAVAILABLE,
}

internal data class HostSupportReconciliationNotice(
    val kind: HostSupportReconciliationKind,
    val targetId: String? = null,
    val installationId: String? = null,
)

/**
 * Public-safe UI state for host support administration.
 * Never carries PATs, invitation secrets, raw server messages, or Google UIDs.
 */
internal data class HostSupportViewState(
    val phase: HostSupportPhase = HostSupportPhase.IDLE,
    val invitations: List<PairingInvitationSummary> = emptyList(),
    val pendingRequests: List<PairingRequestSummary> = emptyList(),
    val supports: List<PairingSupportMembership> = emptyList(),
    val maxActiveSupports: Int = MAX_ACTIVE_SUPPORT_MEMBERSHIPS,
    val listSnapshotFresh: Boolean = false,
    val emittedInvitation: HostEmittedInvitationNotice? = null,
    val pendingConfirmation: HostSupportDecisionConfirmation? = null,
    val reconciliationNotice: HostSupportReconciliationNotice? = null,
    val safeFailure: PairingAccessFailureReason? = null,
    val retryAfterSeconds: Long? = null,
    val sessionFailure: HostAdministrationSessionFailure? = null,
) {
    val activeSupportCount: Int
        get() = supports.size

    val isSupportLimitReached: Boolean
        get() = supports.size >= maxActiveSupports

    val canRequestDecisions: Boolean
        get() = listSnapshotFresh && phase == HostSupportPhase.READY

    override fun toString(): String =
        "HostSupportViewState(phase=$phase, invitations=${invitations.size}, " +
            "pendingRequests=${pendingRequests.size}, supports=${supports.size}/$maxActiveSupports, " +
            "listSnapshotFresh=$listSnapshotFresh, copyAvailable=${emittedInvitation?.copyAvailable == true}, " +
            "hasPendingConfirmation=${pendingConfirmation != null}, " +
            "reconciliationKind=${reconciliationNotice?.kind}, " +
            "safeFailure=$safeFailure, retryAfterSeconds=$retryAfterSeconds, " +
            "sessionFailure=$sessionFailure)"
}
