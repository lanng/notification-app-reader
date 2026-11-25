package br.com.c6tag.diagnostico.remote.access

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
