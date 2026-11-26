package br.com.c6tag.diagnostico.remote.access

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates host-side support administration (invitations, pending requests,
 * active supports, explicit confirmations, and read-only reconciliation)
 * without exposing PATs or invitation secrets in public UI state.
 */
internal class HostSupportCoordinator(
    private val pairingAccessClient: PairingAccessClient,
    private val administrationSessionProvider: HostAdministrationSessionProvider,
    parentScope: CoroutineScope,
    private val decisionIdGenerator: () -> String = { java.util.UUID.randomUUID().toString() },
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val stateLock = Any()
    private var closed = false
    private var operationEpoch = 0L
    private var snapshotEpoch = 0L
    private var operationInFlight = false
    private var activeLease: HostAdministrationLease? = null
    private var emittedSecret: EmittedInvitationSecret? = null
    private var pendingConfirmationBinding: DecisionConfirmationBinding? = null
    private val mutableState = MutableStateFlow(HostSupportViewState())

    val state: StateFlow<HostSupportViewState> = mutableState.asStateFlow()

    init {
        coordinatorJob.invokeOnCompletion {
            close()
        }
    }

    /**
     * Loads invitations, pending requests, and active supports using a
     * validated host administration lease. Dropped while a mutation or another
     * refresh is already in flight.
     */
    suspend fun refresh() {
        val epoch = beginRefreshOperation() ?: return
        try {
            performRefresh(epoch)
        } catch (cancelled: CancellationException) {
            settleReadCancelled(epoch)
            throw cancelled
        } catch (exception: Exception) {
            settleUnexpectedError(epoch, reconciliationNotice = null)
            throw exception
        } finally {
            synchronized(stateLock) {
                operationInFlight = false
            }
        }
    }

    /**
     * Explicit recovery action after 401/403 authorization rejection or host
     * unavailability. Revalidates the host administration session and, if
     * ready, loads a fresh snapshot of all three administration lists.
     */
    suspend fun revalidateSession() {
        val epoch = enterRevalidationGate() ?: return
        try {
            when (val result = administrationSessionProvider.revalidateAdministrationSession()) {
                is HostAdministrationSessionResult.Rejected -> {
                    publishLeaseRejected(
                        epoch = epoch,
                        rejectedLease = null,
                        failure = result.failure,
                        reconciliationNotice = null,
                    )
                }

                is HostAdministrationSessionResult.Ready -> {
                    if (!isEpochCurrent(epoch)) {
                        return
                    }
                    performRefresh(epoch)
                }
            }
        } catch (cancelled: CancellationException) {
            settleReadCancelled(epoch)
            throw cancelled
        } catch (exception: Exception) {
            settleUnexpectedError(epoch, reconciliationNotice = null)
            throw exception
        } finally {
            synchronized(stateLock) {
                operationInFlight = false
            }
        }
    }

    /**
     * Explicitly issues a single-use invitation. Holds the invitation secret
     * only in private coordinator memory bound to the active lease; never
     * retries on ambiguous or post-check failures.
     */
    suspend fun issueInvitation() {
        val gate = enterIssueGate() ?: return
        try {
            val epoch = gate.epoch
            val hadFreshLists = gate.hadFreshLists
            var operationInvoked = false
            var mutationCompleted = false
            var mutationAmbiguous = false

            try {
                val lease = gate.existingLease ?: when (
                    val acquired = administrationSessionProvider.acquireLease()
                ) {
                    is HostAdministrationSessionResult.Rejected -> {
                        publishLeaseRejected(
                            epoch = epoch,
                            rejectedLease = null,
                            failure = acquired.failure,
                            reconciliationNotice = null,
                        )
                        return
                    }

                    is HostAdministrationSessionResult.Ready -> {
                        if (!bindAcquiredLease(epoch, acquired.value)) {
                            return
                        }
                        acquired.value
                    }
                }

                val response = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                    operationInvoked = true
                    pairingAccessClient.issueInvitation(pairingLease)
                }
                if (!isEpochCurrent(epoch)) {
                    return
                }

                when (response) {
                    is HostAdministrationSessionResult.Rejected -> {
                        val notice = if (operationInvoked) {
                            HostSupportReconciliationNotice(
                                kind = HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                            )
                        } else {
                            null
                        }
                        publishLeaseRejected(
                            epoch = epoch,
                            rejectedLease = lease,
                            failure = response.failure,
                            reconciliationNotice = notice,
                        )
                    }

                    is HostAdministrationSessionResult.Ready -> {
                        when (val result = response.value) {
                            is PairingAccessResult.Success -> {
                                mutationCompleted = true
                                val invitation = result.value
                                val notice = HostEmittedInvitationNotice(
                                    id = invitation.id,
                                    createdAtMillis = invitation.createdAtMillis,
                                    expiresAtMillis = invitation.expiresAtMillis,
                                    copyAvailable = true,
                                )
                                val stored = synchronized(stateLock) {
                                    if (!isEpochCurrentLocked(epoch)) {
                                        false
                                    } else {
                                        emittedSecret = EmittedInvitationSecret(
                                            notice = notice,
                                            secret = invitation.invitationSecret,
                                            lease = lease,
                                        )
                                        mutableState.value = mutableState.value.copy(
                                            emittedInvitation = notice,
                                            reconciliationNotice = null,
                                            safeFailure = null,
                                            retryAfterSeconds = null,
                                            sessionFailure = null,
                                        )
                                        true
                                    }
                                }
                                if (!stored) {
                                    return
                                }
                                applyRefreshAfterMutation(
                                    epoch = epoch,
                                    lease = lease,
                                    reconciliationNotice = null,
                                    preservedFailure = null,
                                    preservedRetryAfterSeconds = null,
                                )
                            }

                            is PairingAccessResult.Failure -> {
                                if (isAuthFailure(result.reason)) {
                                    mutationCompleted = true
                                    administrationSessionProvider.invalidateLease(
                                        lease,
                                        HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                                    )
                                    publishAuthorizationRejected(
                                        epoch = epoch,
                                        rejectedLease = lease,
                                        reason = result.reason,
                                        reconciliationNotice = null,
                                    )
                                } else if (isKnownNotWritten(result.writeDisposition)) {
                                    mutationCompleted = true
                                    if (requiresListRefreshOnKnownNoWrite(result.reason)) {
                                        applyRefreshAfterMutation(
                                            epoch = epoch,
                                            lease = lease,
                                            reconciliationNotice = null,
                                            preservedFailure = result.reason,
                                            preservedRetryAfterSeconds = result.retryAfterSeconds,
                                        )
                                    } else {
                                        synchronized(stateLock) {
                                            if (isEpochCurrentLocked(epoch)) {
                                                mutableState.value = mutableState.value.copy(
                                                    phase = if (hadFreshLists) {
                                                        HostSupportPhase.READY
                                                    } else {
                                                        HostSupportPhase.RECOVERABLE_ERROR
                                                    },
                                                    listSnapshotFresh = hadFreshLists,
                                                    reconciliationNotice = null,
                                                    safeFailure = result.reason,
                                                    retryAfterSeconds = result.retryAfterSeconds,
                                                    sessionFailure = null,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    mutationAmbiguous = true
                                    clearEmittedSecretIfCurrent(epoch)
                                    val uncertainNotice = HostSupportReconciliationNotice(
                                        kind = HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                                    )
                                    applyRefreshAfterMutation(
                                        epoch = epoch,
                                        lease = lease,
                                        reconciliationNotice = uncertainNotice,
                                        preservedFailure = result.reason,
                                        preservedRetryAfterSeconds = result.retryAfterSeconds,
                                        overrideReadFailureWithPreserved = false,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                if (uncertain) {
                    clearEmittedSecretIfCurrent(epoch)
                }
                settleMutationCancelled(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                        )
                    } else {
                        null
                    },
                )
                throw cancelled
            } catch (exception: Exception) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                if (uncertain) {
                    clearEmittedSecretIfCurrent(epoch)
                }
                settleUnexpectedError(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                        )
                    } else {
                        null
                    },
                )
                throw exception
            }
        } finally {
            synchronized(stateLock) {
                operationInFlight = false
            }
        }
    }

    /**
     * Returns the emitted invitation secret only for an explicit copy gesture
     * matching the currently bound and validated host administration lease.
     */
    suspend fun invitationForExplicitCopy(invitationId: String): SecretValue? {
        val candidate = synchronized(stateLock) {
            if (closed || coordinatorJob.isCancelled) {
                return@synchronized null
            }
            val currentLease = activeLease ?: return@synchronized null
            val secret = emittedSecret ?: return@synchronized null
            if (secret.notice.id != invitationId || secret.lease !== currentLease) {
                return@synchronized null
            }
            secret
        } ?: return null

        val validation = try {
            administrationSessionProvider.validateLease(candidate.lease)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            synchronized(stateLock) {
                if (!closed && emittedSecret === candidate) {
                    emittedSecret = null
                    mutableState.value = mutableState.value.copy(
                        emittedInvitation = mutableState.value.emittedInvitation?.copy(copyAvailable = false),
                    )
                }
            }
            throw exception
        }

        return when (validation) {
            is HostAdministrationSessionResult.Ready -> {
                synchronized(stateLock) {
                    if (
                        !closed &&
                        !coordinatorJob.isCancelled &&
                        activeLease === candidate.lease &&
                        emittedSecret === candidate
                    ) {
                        candidate.secret
                    } else {
                        null
                    }
                }
            }

            is HostAdministrationSessionResult.Rejected -> {
                synchronized(stateLock) {
                    if (emittedSecret === candidate) {
                        emittedSecret = null
                        if (!closed && mutableState.value.emittedInvitation?.id == candidate.notice.id) {
                            mutableState.value = mutableState.value.copy(emittedInvitation = null)
                        }
                    }
                    if (
                        !closed &&
                        !coordinatorJob.isCancelled &&
                        !operationInFlight &&
                        activeLease === candidate.lease
                    ) {
                        operationEpoch += 1
                        activeLease = null
                        emittedSecret = null
                        pendingConfirmationBinding = null
                        mutableState.value = HostSupportViewState(
                            phase = phaseForSessionFailure(validation.failure),
                            listSnapshotFresh = false,
                            sessionFailure = validation.failure,
                        )
                    }
                }
                null
            }
        }
    }

    /**
     * Explicitly cancels an available invitation from the current fresh
     * snapshot. Never retries on uncertain outcomes; reconciles by GET.
     */
    suspend fun cancelInvitation(invitationId: String) {
        val gate = enterCancelInvitationGate(invitationId) ?: return
        try {
            val epoch = gate.epoch
            val lease = gate.lease
            var operationInvoked = false
            var mutationCompleted = false
            var mutationAmbiguous = false

            try {
                val response = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                    operationInvoked = true
                    pairingAccessClient.cancelInvitation(pairingLease, invitationId)
                }
                if (!isEpochCurrent(epoch)) {
                    return
                }

                when (response) {
                    is HostAdministrationSessionResult.Rejected -> {
                        val notice = if (operationInvoked) {
                            HostSupportReconciliationNotice(
                                kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                                targetId = invitationId,
                            )
                        } else {
                            null
                        }
                        publishLeaseRejected(
                            epoch = epoch,
                            rejectedLease = lease,
                            failure = response.failure,
                            reconciliationNotice = notice,
                        )
                    }

                    is HostAdministrationSessionResult.Ready -> {
                        when (val result = response.value) {
                            is PairingAccessResult.Success -> {
                                mutationCompleted = true
                                clearMatchingEmittedSecretIfCurrent(epoch, invitationId)
                                applyRefreshAfterMutation(
                                    epoch = epoch,
                                    lease = lease,
                                    reconciliationNotice = null,
                                    preservedFailure = null,
                                    preservedRetryAfterSeconds = null,
                                )
                            }

                            is PairingAccessResult.Failure -> {
                                if (isAuthFailure(result.reason)) {
                                    mutationCompleted = true
                                    administrationSessionProvider.invalidateLease(
                                        lease,
                                        HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                                    )
                                    publishAuthorizationRejected(
                                        epoch = epoch,
                                        rejectedLease = lease,
                                        reason = result.reason,
                                        reconciliationNotice = null,
                                    )
                                } else if (isKnownNotWritten(result.writeDisposition)) {
                                    mutationCompleted = true
                                    if (
                                        result.reason == PairingAccessFailureReason.CONFLICT ||
                                        result.reason == PairingAccessFailureReason.RESOURCE_NOT_FOUND
                                    ) {
                                        clearMatchingEmittedSecretIfCurrent(epoch, invitationId)
                                    }
                                    applyRefreshAfterMutation(
                                        epoch = epoch,
                                        lease = lease,
                                        reconciliationNotice = null,
                                        preservedFailure = result.reason,
                                        preservedRetryAfterSeconds = result.retryAfterSeconds,
                                    )
                                } else {
                                    mutationAmbiguous = true
                                    reconcileCancelInvitation(
                                        epoch = epoch,
                                        lease = lease,
                                        invitationId = invitationId,
                                        originalFailure = result,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                if (uncertain) {
                    clearMatchingEmittedSecretIfCurrent(epoch, invitationId)
                }
                settleMutationCancelled(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                            targetId = invitationId,
                        )
                    } else {
                        null
                    },
                )
                throw cancelled
            } catch (exception: Exception) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                if (uncertain) {
                    clearMatchingEmittedSecretIfCurrent(epoch, invitationId)
                }
                settleUnexpectedError(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                            targetId = invitationId,
                        )
                    } else {
                        null
                    },
                )
                throw exception
            }
        } finally {
            synchronized(stateLock) {
                operationInFlight = false
            }
        }
    }

    /**
     * Opens a single-use decision confirmation bound to the target ID,
     * installation ID, active lease, and current fresh list snapshot.
     */
    fun requestDecision(
        action: HostSupportDecisionAction,
        targetId: String,
    ): HostSupportDecisionConfirmation? =
        synchronized(stateLock) {
            if (closed || coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            val lease = activeLease ?: return@synchronized null
            val current = mutableState.value
            if (!current.listSnapshotFresh || current.phase != HostSupportPhase.READY) {
                return@synchronized null
            }

            val confirmationTarget = when (action) {
                HostSupportDecisionAction.APPROVE_REQUEST -> {
                    val request = current.pendingRequests.firstOrNull { it.id == targetId }
                        ?: return@synchronized null
                    if (current.isSupportLimitReached) {
                        pendingConfirmationBinding = null
                        mutableState.value = current.copy(
                            pendingConfirmation = null,
                            safeFailure = PairingAccessFailureReason.CONFLICT,
                            retryAfterSeconds = null,
                        )
                        return@synchronized null
                    }
                    DecisionTarget(
                        targetId = request.id,
                        installationId = request.installationId,
                        targetLabel = request.installationLabel,
                        targetTimestampMillis = request.createdAtMillis,
                    )
                }

                HostSupportDecisionAction.REJECT_REQUEST -> {
                    val request = current.pendingRequests.firstOrNull { it.id == targetId }
                        ?: return@synchronized null
                    DecisionTarget(
                        targetId = request.id,
                        installationId = request.installationId,
                        targetLabel = request.installationLabel,
                        targetTimestampMillis = request.createdAtMillis,
                    )
                }

                HostSupportDecisionAction.REVOKE_SUPPORT -> {
                    val support = current.supports.firstOrNull { it.membershipId == targetId }
                        ?: return@synchronized null
                    DecisionTarget(
                        targetId = support.membershipId,
                        installationId = support.installationId,
                        targetLabel = support.label,
                        targetTimestampMillis = support.approvedAtMillis,
                    )
                }
            }

            val decisionId = decisionIdGenerator()
            require(decisionId.isNotBlank()) { "Decision ID must not be blank" }
            val confirmation = HostSupportDecisionConfirmation(
                decisionId = decisionId,
                action = action,
                targetId = confirmationTarget.targetId,
                installationId = confirmationTarget.installationId,
                targetLabel = confirmationTarget.targetLabel,
                targetTimestampMillis = confirmationTarget.targetTimestampMillis,
            )
            pendingConfirmationBinding = DecisionConfirmationBinding(
                confirmation = confirmation,
                lease = lease,
                snapshotEpoch = snapshotEpoch,
            )
            mutableState.value = current.copy(
                pendingConfirmation = confirmation,
                safeFailure = null,
                retryAfterSeconds = null,
            )
            confirmation
        }

    /**
     * Dismisses any currently open decision confirmation with zero network calls.
     */
    fun cancelDecision() {
        synchronized(stateLock) {
            if (closed) {
                return
            }
            pendingConfirmationBinding = null
            if (mutableState.value.pendingConfirmation != null) {
                mutableState.value = mutableState.value.copy(pendingConfirmation = null)
            }
        }
    }

    /**
     * Executes the single decision matching [decisionId] if and only if the
     * confirmation is still bound to the active lease and current fresh list
     * snapshot. Drops stale, unknown, or duplicate confirmations with zero HTTP.
     */
    suspend fun confirmDecision(decisionId: String) {
        val gate = enterConfirmDecisionGate(decisionId) ?: return
        try {
            val epoch = gate.epoch
            val lease = gate.binding.lease
            val confirmation = gate.binding.confirmation
            var operationInvoked = false
            var mutationCompleted = false
            var mutationAmbiguous = false

            try {
                val response = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                    operationInvoked = true
                    when (confirmation.action) {
                        HostSupportDecisionAction.APPROVE_REQUEST ->
                            pairingAccessClient.approveRequest(pairingLease, confirmation.targetId)

                        HostSupportDecisionAction.REJECT_REQUEST ->
                            pairingAccessClient.rejectRequest(pairingLease, confirmation.targetId)

                        HostSupportDecisionAction.REVOKE_SUPPORT ->
                            pairingAccessClient.revokeSupport(pairingLease, confirmation.targetId)
                    }
                }
                if (!isEpochCurrent(epoch)) {
                    return
                }

                when (response) {
                    is HostAdministrationSessionResult.Rejected -> {
                        val notice = if (operationInvoked) {
                            HostSupportReconciliationNotice(
                                kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                                targetId = confirmation.targetId,
                                installationId = confirmation.installationId,
                            )
                        } else {
                            null
                        }
                        publishLeaseRejected(
                            epoch = epoch,
                            rejectedLease = lease,
                            failure = response.failure,
                            reconciliationNotice = notice,
                        )
                    }

                    is HostAdministrationSessionResult.Ready -> {
                        when (val result = response.value) {
                            is PairingAccessResult.Success -> {
                                mutationCompleted = true
                                applyRefreshAfterMutation(
                                    epoch = epoch,
                                    lease = lease,
                                    reconciliationNotice = null,
                                    preservedFailure = null,
                                    preservedRetryAfterSeconds = null,
                                )
                            }

                            is PairingAccessResult.Failure -> {
                                if (isAuthFailure(result.reason)) {
                                    mutationCompleted = true
                                    administrationSessionProvider.invalidateLease(
                                        lease,
                                        HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                                    )
                                    publishAuthorizationRejected(
                                        epoch = epoch,
                                        rejectedLease = lease,
                                        reason = result.reason,
                                        reconciliationNotice = null,
                                    )
                                } else if (isKnownNotWritten(result.writeDisposition)) {
                                    mutationCompleted = true
                                    applyRefreshAfterMutation(
                                        epoch = epoch,
                                        lease = lease,
                                        reconciliationNotice = null,
                                        preservedFailure = result.reason,
                                        preservedRetryAfterSeconds = result.retryAfterSeconds,
                                    )
                                } else {
                                    mutationAmbiguous = true
                                    reconcileDecision(
                                        epoch = epoch,
                                        lease = lease,
                                        confirmation = confirmation,
                                        originalFailure = result,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                settleMutationCancelled(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                            targetId = confirmation.targetId,
                            installationId = confirmation.installationId,
                        )
                    } else {
                        null
                    },
                )
                throw cancelled
            } catch (exception: Exception) {
                val uncertain = mutationAmbiguous || (operationInvoked && !mutationCompleted)
                settleUnexpectedError(
                    epoch = epoch,
                    reconciliationNotice = if (uncertain) {
                        HostSupportReconciliationNotice(
                            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                            targetId = confirmation.targetId,
                            installationId = confirmation.installationId,
                        )
                    } else {
                        null
                    },
                )
                throw exception
            }
        } finally {
            synchronized(stateLock) {
                operationInFlight = false
            }
        }
    }

    override fun close() {
        val shouldCancel = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                operationEpoch += 1
                activeLease = null
                emittedSecret = null
                pendingConfirmationBinding = null
                mutableState.value = HostSupportViewState()
                true
            }
        }
        if (shouldCancel) {
            coordinatorJob.cancel()
        }
    }

    private suspend fun performRefresh(epoch: Long) {
        val lease = resolveLeaseForRefresh(epoch) ?: return
        when (val fetched = fetchAllLists(epoch, lease)) {
            FetchOutcome.Superseded -> return

            is FetchOutcome.Success -> {
                publishFreshSnapshot(
                    epoch = epoch,
                    lease = lease,
                    invitations = fetched.invitations,
                    requests = fetched.requests,
                    supports = fetched.supports,
                    reconciliationNotice = null,
                    safeFailure = null,
                    retryAfterSeconds = null,
                )
            }

            is FetchOutcome.ReadFailed -> {
                publishStale(
                    epoch = epoch,
                    safeFailure = fetched.failure.reason,
                    retryAfterSeconds = fetched.failure.retryAfterSeconds,
                    reconciliationNotice = null,
                )
            }

            is FetchOutcome.AuthorizationRejected -> {
                publishAuthorizationRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    reason = fetched.reason,
                    reconciliationNotice = null,
                )
            }

            is FetchOutcome.LeaseRejected -> {
                publishLeaseRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    failure = fetched.failure,
                    reconciliationNotice = null,
                )
            }
        }
    }

    private suspend fun resolveLeaseForRefresh(epoch: Long): HostAdministrationLease? {
        val existingLease = synchronized(stateLock) {
            if (isEpochCurrentLocked(epoch)) activeLease else null
        }
        if (existingLease != null) {
            when (val validation = administrationSessionProvider.validateLease(existingLease)) {
                is HostAdministrationSessionResult.Ready -> {
                    return if (isEpochCurrent(epoch)) existingLease else null
                }

                is HostAdministrationSessionResult.Rejected -> {
                    if (!isEpochCurrent(epoch)) {
                        return null
                    }
                    if (validation.failure == HostAdministrationSessionFailure.AUTHORIZATION_REJECTED) {
                        publishLeaseRejected(
                            epoch = epoch,
                            rejectedLease = existingLease,
                            failure = validation.failure,
                            reconciliationNotice = null,
                        )
                        return null
                    }
                    // Old lease is no longer valid; drop any secrets/confirmations bound to it
                    // before attempting to acquire a fresh lease.
                    synchronized(stateLock) {
                        if (isEpochCurrentLocked(epoch)) {
                            activeLease = null
                            emittedSecret = null
                            pendingConfirmationBinding = null
                            mutableState.value = mutableState.value.copy(
                                emittedInvitation = null,
                                pendingConfirmation = null,
                            )
                        }
                    }
                }
            }
        }

        return when (val acquired = administrationSessionProvider.acquireLease()) {
            is HostAdministrationSessionResult.Rejected -> {
                publishLeaseRejected(
                    epoch = epoch,
                    rejectedLease = null,
                    failure = acquired.failure,
                    reconciliationNotice = null,
                )
                null
            }

            is HostAdministrationSessionResult.Ready -> {
                if (bindAcquiredLease(epoch, acquired.value)) acquired.value else null
            }
        }
    }

    private suspend fun fetchAllLists(
        epoch: Long,
        lease: HostAdministrationLease,
    ): FetchOutcome {
        if (!isEpochCurrent(epoch)) {
            return FetchOutcome.Superseded
        }
        val invitations = when (
            val result = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                pairingAccessClient.listInvitations(pairingLease)
            }
        ) {
            is HostAdministrationSessionResult.Rejected ->
                return FetchOutcome.LeaseRejected(result.failure)

            is HostAdministrationSessionResult.Ready -> when (val call = result.value) {
                is PairingAccessResult.Success -> call.value
                is PairingAccessResult.Failure -> return handleListCallFailure(lease, call)
            }
        }

        if (!isEpochCurrent(epoch)) {
            return FetchOutcome.Superseded
        }
        val requests = when (
            val result = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                pairingAccessClient.listRequests(pairingLease)
            }
        ) {
            is HostAdministrationSessionResult.Rejected ->
                return FetchOutcome.LeaseRejected(result.failure)

            is HostAdministrationSessionResult.Ready -> when (val call = result.value) {
                is PairingAccessResult.Success -> call.value
                is PairingAccessResult.Failure -> return handleListCallFailure(lease, call)
            }
        }

        if (!isEpochCurrent(epoch)) {
            return FetchOutcome.Superseded
        }
        val supports = when (
            val result = administrationSessionProvider.withValidatedLease(lease) { pairingLease ->
                pairingAccessClient.listSupports(pairingLease)
            }
        ) {
            is HostAdministrationSessionResult.Rejected ->
                return FetchOutcome.LeaseRejected(result.failure)

            is HostAdministrationSessionResult.Ready -> when (val call = result.value) {
                is PairingAccessResult.Success -> call.value
                is PairingAccessResult.Failure -> return handleListCallFailure(lease, call)
            }
        }

        if (!isEpochCurrent(epoch)) {
            return FetchOutcome.Superseded
        }
        return FetchOutcome.Success(
            invitations = invitations,
            requests = requests,
            supports = supports,
        )
    }

    private fun handleListCallFailure(
        lease: HostAdministrationLease,
        failure: PairingAccessResult.Failure,
    ): FetchOutcome =
        if (isAuthFailure(failure.reason)) {
            administrationSessionProvider.invalidateLease(
                lease,
                HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
            )
            FetchOutcome.AuthorizationRejected(failure.reason)
        } else {
            FetchOutcome.ReadFailed(failure)
        }

    private suspend fun applyRefreshAfterMutation(
        epoch: Long,
        lease: HostAdministrationLease,
        reconciliationNotice: HostSupportReconciliationNotice?,
        preservedFailure: PairingAccessFailureReason?,
        preservedRetryAfterSeconds: Long?,
        overrideReadFailureWithPreserved: Boolean = true,
    ) {
        when (val fetched = fetchAllLists(epoch, lease)) {
            FetchOutcome.Superseded -> return

            is FetchOutcome.Success -> {
                publishFreshSnapshot(
                    epoch = epoch,
                    lease = lease,
                    invitations = fetched.invitations,
                    requests = fetched.requests,
                    supports = fetched.supports,
                    reconciliationNotice = reconciliationNotice,
                    safeFailure = preservedFailure,
                    retryAfterSeconds = preservedRetryAfterSeconds,
                )
            }

            is FetchOutcome.ReadFailed -> {
                val safeFailure = if (overrideReadFailureWithPreserved && preservedFailure != null) {
                    preservedFailure
                } else {
                    fetched.failure.reason
                }
                val retryAfter = if (overrideReadFailureWithPreserved && preservedFailure != null) {
                    preservedRetryAfterSeconds
                } else {
                    fetched.failure.retryAfterSeconds
                }
                publishStale(
                    epoch = epoch,
                    safeFailure = safeFailure,
                    retryAfterSeconds = retryAfter,
                    reconciliationNotice = reconciliationNotice,
                )
            }

            is FetchOutcome.AuthorizationRejected -> {
                publishAuthorizationRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    reason = fetched.reason,
                    reconciliationNotice = reconciliationNotice,
                )
            }

            is FetchOutcome.LeaseRejected -> {
                publishLeaseRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    failure = fetched.failure,
                    reconciliationNotice = reconciliationNotice,
                )
            }
        }
    }

    private suspend fun reconcileCancelInvitation(
        epoch: Long,
        lease: HostAdministrationLease,
        invitationId: String,
        originalFailure: PairingAccessResult.Failure,
    ) {
        val unavailableNotice = HostSupportReconciliationNotice(
            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
            targetId = invitationId,
        )
        when (val fetched = fetchAllLists(epoch, lease)) {
            FetchOutcome.Superseded -> return

            is FetchOutcome.Success -> {
                val kind = if (fetched.invitations.any { it.id == invitationId }) {
                    HostSupportReconciliationKind.STILL_AVAILABLE
                } else {
                    HostSupportReconciliationKind.CANCEL_CONFIRMED_ABSENT_CAUSE_UNKNOWN
                }
                publishFreshSnapshot(
                    epoch = epoch,
                    lease = lease,
                    invitations = fetched.invitations,
                    requests = fetched.requests,
                    supports = fetched.supports,
                    reconciliationNotice = HostSupportReconciliationNotice(
                        kind = kind,
                        targetId = invitationId,
                    ),
                    safeFailure = originalFailure.reason,
                    retryAfterSeconds = originalFailure.retryAfterSeconds,
                )
            }

            is FetchOutcome.ReadFailed -> {
                clearMatchingEmittedSecretIfCurrent(epoch, invitationId)
                publishStale(
                    epoch = epoch,
                    safeFailure = fetched.failure.reason,
                    retryAfterSeconds = fetched.failure.retryAfterSeconds,
                    reconciliationNotice = unavailableNotice,
                )
            }

            is FetchOutcome.AuthorizationRejected -> {
                publishAuthorizationRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    reason = fetched.reason,
                    reconciliationNotice = unavailableNotice,
                )
            }

            is FetchOutcome.LeaseRejected -> {
                publishLeaseRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    failure = fetched.failure,
                    reconciliationNotice = unavailableNotice,
                )
            }
        }
    }

    private suspend fun reconcileDecision(
        epoch: Long,
        lease: HostAdministrationLease,
        confirmation: HostSupportDecisionConfirmation,
        originalFailure: PairingAccessResult.Failure,
    ) {
        val unavailableNotice = HostSupportReconciliationNotice(
            kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
            targetId = confirmation.targetId,
            installationId = confirmation.installationId,
        )
        when (val fetched = fetchAllLists(epoch, lease)) {
            FetchOutcome.Superseded -> return

            is FetchOutcome.Success -> {
                val kind = when (confirmation.action) {
                    HostSupportDecisionAction.APPROVE_REQUEST -> when {
                        fetched.requests.any { it.id == confirmation.targetId } ->
                            HostSupportReconciliationKind.STILL_PENDING

                        fetched.supports.any { it.installationId == confirmation.installationId } ->
                            HostSupportReconciliationKind.APPROVAL_CONFIRMED_BY_SUPPORT_LIST

                        else -> HostSupportReconciliationKind.APPROVAL_UNCERTAIN
                    }

                    HostSupportDecisionAction.REJECT_REQUEST -> when {
                        fetched.requests.any { it.id == confirmation.targetId } ->
                            HostSupportReconciliationKind.STILL_PENDING

                        fetched.supports.any { it.installationId == confirmation.installationId } ->
                            HostSupportReconciliationKind.APPROVAL_CONFIRMED_BY_SUPPORT_LIST

                        else -> HostSupportReconciliationKind.REJECTION_INDETERMINATE
                    }

                    HostSupportDecisionAction.REVOKE_SUPPORT -> when {
                        fetched.supports.any { it.membershipId == confirmation.targetId } ->
                            HostSupportReconciliationKind.STILL_ACTIVE

                        else -> HostSupportReconciliationKind.REVOKE_CONFIRMED_ABSENT_CAUSE_UNKNOWN
                    }
                }
                publishFreshSnapshot(
                    epoch = epoch,
                    lease = lease,
                    invitations = fetched.invitations,
                    requests = fetched.requests,
                    supports = fetched.supports,
                    reconciliationNotice = HostSupportReconciliationNotice(
                        kind = kind,
                        targetId = confirmation.targetId,
                        installationId = confirmation.installationId,
                    ),
                    safeFailure = originalFailure.reason,
                    retryAfterSeconds = originalFailure.retryAfterSeconds,
                )
            }

            is FetchOutcome.ReadFailed -> {
                publishStale(
                    epoch = epoch,
                    safeFailure = fetched.failure.reason,
                    retryAfterSeconds = fetched.failure.retryAfterSeconds,
                    reconciliationNotice = unavailableNotice,
                )
            }

            is FetchOutcome.AuthorizationRejected -> {
                publishAuthorizationRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    reason = fetched.reason,
                    reconciliationNotice = unavailableNotice,
                )
            }

            is FetchOutcome.LeaseRejected -> {
                publishLeaseRejected(
                    epoch = epoch,
                    rejectedLease = lease,
                    failure = fetched.failure,
                    reconciliationNotice = unavailableNotice,
                )
            }
        }
    }

    private fun beginRefreshOperation(): Long? =
        synchronized(stateLock) {
            check(!closed) { "Host support coordinator is closed" }
            if (coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            operationInFlight = true
            operationEpoch += 1
            pendingConfirmationBinding = null
            mutableState.value = mutableState.value.copy(
                phase = HostSupportPhase.LOADING,
                listSnapshotFresh = false,
                pendingConfirmation = null,
                reconciliationNotice = null,
                safeFailure = null,
                retryAfterSeconds = null,
                sessionFailure = null,
            )
            operationEpoch
        }

    private fun enterRevalidationGate(): Long? =
        synchronized(stateLock) {
            check(!closed) { "Host support coordinator is closed" }
            if (coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            operationInFlight = true
            operationEpoch += 1
            activeLease = null
            emittedSecret = null
            pendingConfirmationBinding = null
            mutableState.value = HostSupportViewState(
                phase = HostSupportPhase.LOADING,
                listSnapshotFresh = false,
            )
            operationEpoch
        }

    private fun enterIssueGate(): IssueGate? =
        synchronized(stateLock) {
            check(!closed) { "Host support coordinator is closed" }
            if (coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            val current = mutableState.value
            val hadFreshLists = current.listSnapshotFresh
            val existingLease = activeLease
            operationInFlight = true
            operationEpoch += 1
            pendingConfirmationBinding = null
            mutableState.value = current.copy(
                phase = HostSupportPhase.MUTATING,
                pendingConfirmation = null,
                reconciliationNotice = null,
                safeFailure = null,
                retryAfterSeconds = null,
                sessionFailure = null,
            )
            IssueGate(
                epoch = operationEpoch,
                existingLease = existingLease,
                hadFreshLists = hadFreshLists,
            )
        }

    private fun enterCancelInvitationGate(invitationId: String): CancelInvitationGate? =
        synchronized(stateLock) {
            check(!closed) { "Host support coordinator is closed" }
            if (coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            val lease = activeLease ?: return@synchronized null
            val current = mutableState.value
            if (
                !current.listSnapshotFresh ||
                current.phase != HostSupportPhase.READY ||
                !PairingAccessJson.isCanonicalUuid(invitationId) ||
                current.invitations.none { it.id == invitationId }
            ) {
                return@synchronized null
            }
            operationInFlight = true
            operationEpoch += 1
            pendingConfirmationBinding = null
            mutableState.value = current.copy(
                phase = HostSupportPhase.MUTATING,
                pendingConfirmation = null,
                reconciliationNotice = null,
                safeFailure = null,
                retryAfterSeconds = null,
                sessionFailure = null,
            )
            CancelInvitationGate(
                epoch = operationEpoch,
                lease = lease,
            )
        }

    private fun enterConfirmDecisionGate(decisionId: String): ConfirmDecisionGate? =
        synchronized(stateLock) {
            if (closed || coordinatorJob.isCancelled || operationInFlight) {
                return@synchronized null
            }
            val binding = pendingConfirmationBinding ?: return@synchronized null
            val current = mutableState.value
            if (
                binding.confirmation.decisionId != decisionId ||
                binding.lease !== activeLease ||
                binding.snapshotEpoch != snapshotEpoch ||
                !current.listSnapshotFresh ||
                current.phase != HostSupportPhase.READY
            ) {
                return@synchronized null
            }
            pendingConfirmationBinding = null
            operationInFlight = true
            operationEpoch += 1
            mutableState.value = current.copy(
                phase = HostSupportPhase.MUTATING,
                pendingConfirmation = null,
                reconciliationNotice = null,
                safeFailure = null,
                retryAfterSeconds = null,
                sessionFailure = null,
            )
            ConfirmDecisionGate(
                epoch = operationEpoch,
                binding = binding,
            )
        }

    private fun bindAcquiredLease(
        epoch: Long,
        newLease: HostAdministrationLease,
    ): Boolean =
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return@synchronized false
            }
            val changed = activeLease !== newLease
            activeLease = newLease
            if (changed) {
                emittedSecret = null
                pendingConfirmationBinding = null
                mutableState.value = mutableState.value.copy(
                    emittedInvitation = null,
                    pendingConfirmation = null,
                )
            }
            true
        }

    private fun publishFreshSnapshot(
        epoch: Long,
        lease: HostAdministrationLease,
        invitations: List<PairingInvitationSummary>,
        requests: List<PairingRequestSummary>,
        supports: List<PairingSupportMembership>,
        reconciliationNotice: HostSupportReconciliationNotice?,
        safeFailure: PairingAccessFailureReason?,
        retryAfterSeconds: Long?,
    ) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            snapshotEpoch += 1
            activeLease = lease
            pendingConfirmationBinding = null
            val retainedSecret = emittedSecret?.takeIf { held ->
                held.lease === lease && invitations.any { it.id == held.notice.id }
            }
            emittedSecret = retainedSecret
            mutableState.value = HostSupportViewState(
                phase = HostSupportPhase.READY,
                invitations = invitations,
                pendingRequests = requests,
                supports = supports,
                maxActiveSupports = MAX_ACTIVE_SUPPORT_MEMBERSHIPS,
                listSnapshotFresh = true,
                emittedInvitation = retainedSecret?.notice,
                pendingConfirmation = null,
                reconciliationNotice = reconciliationNotice,
                safeFailure = safeFailure,
                retryAfterSeconds = retryAfterSeconds,
                sessionFailure = null,
            )
        }
    }

    private fun publishStale(
        epoch: Long,
        safeFailure: PairingAccessFailureReason?,
        retryAfterSeconds: Long?,
        reconciliationNotice: HostSupportReconciliationNotice?,
    ) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            pendingConfirmationBinding = null
            val current = mutableState.value
            val retainedNotice = emittedSecret
                ?.takeIf { held -> held.lease === activeLease }
                ?.notice
            mutableState.value = current.copy(
                phase = HostSupportPhase.STALE,
                listSnapshotFresh = false,
                emittedInvitation = retainedNotice,
                pendingConfirmation = null,
                reconciliationNotice = reconciliationNotice,
                safeFailure = safeFailure,
                retryAfterSeconds = retryAfterSeconds,
                sessionFailure = null,
            )
        }
    }

    private fun publishAuthorizationRejected(
        epoch: Long,
        rejectedLease: HostAdministrationLease,
        reason: PairingAccessFailureReason,
        reconciliationNotice: HostSupportReconciliationNotice?,
    ) {
        synchronized(stateLock) {
            if (activeLease === rejectedLease) {
                activeLease = null
                emittedSecret = null
                pendingConfirmationBinding = null
            }
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            activeLease = null
            emittedSecret = null
            pendingConfirmationBinding = null
            mutableState.value = HostSupportViewState(
                phase = HostSupportPhase.AUTHORIZATION_REQUIRED,
                listSnapshotFresh = false,
                reconciliationNotice = reconciliationNotice,
                safeFailure = reason,
                sessionFailure = HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
            )
        }
    }

    private fun publishLeaseRejected(
        epoch: Long,
        rejectedLease: HostAdministrationLease?,
        failure: HostAdministrationSessionFailure,
        reconciliationNotice: HostSupportReconciliationNotice?,
    ) {
        synchronized(stateLock) {
            if (rejectedLease != null && activeLease === rejectedLease) {
                activeLease = null
                emittedSecret = null
                pendingConfirmationBinding = null
            }
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            activeLease = null
            emittedSecret = null
            pendingConfirmationBinding = null
            mutableState.value = HostSupportViewState(
                phase = phaseForSessionFailure(failure),
                listSnapshotFresh = false,
                reconciliationNotice = reconciliationNotice,
                sessionFailure = failure,
            )
        }
    }

    private fun settleReadCancelled(epoch: Long) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            pendingConfirmationBinding = null
            mutableState.value = mutableState.value.copy(
                phase = HostSupportPhase.STALE,
                listSnapshotFresh = false,
                pendingConfirmation = null,
            )
        }
    }

    private fun settleMutationCancelled(
        epoch: Long,
        reconciliationNotice: HostSupportReconciliationNotice?,
    ) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            pendingConfirmationBinding = null
            val retainedNotice = emittedSecret
                ?.takeIf { held -> held.lease === activeLease }
                ?.notice
            mutableState.value = mutableState.value.copy(
                phase = HostSupportPhase.STALE,
                listSnapshotFresh = false,
                emittedInvitation = retainedNotice,
                pendingConfirmation = null,
                reconciliationNotice = reconciliationNotice,
            )
        }
    }

    private fun settleUnexpectedError(
        epoch: Long,
        reconciliationNotice: HostSupportReconciliationNotice?,
    ) {
        synchronized(stateLock) {
            if (!isEpochCurrentLocked(epoch)) {
                return
            }
            pendingConfirmationBinding = null
            val retainedNotice = emittedSecret
                ?.takeIf { held -> held.lease === activeLease }
                ?.notice
            mutableState.value = mutableState.value.copy(
                phase = HostSupportPhase.RECOVERABLE_ERROR,
                listSnapshotFresh = false,
                emittedInvitation = retainedNotice,
                pendingConfirmation = null,
                reconciliationNotice = reconciliationNotice,
                safeFailure = null,
                retryAfterSeconds = null,
                sessionFailure = null,
            )
        }
    }

    private fun clearEmittedSecretIfCurrent(epoch: Long) {
        synchronized(stateLock) {
            if (isEpochCurrentLocked(epoch)) {
                emittedSecret = null
                mutableState.value = mutableState.value.copy(emittedInvitation = null)
            }
        }
    }

    private fun clearMatchingEmittedSecretIfCurrent(
        epoch: Long,
        invitationId: String,
    ) {
        synchronized(stateLock) {
            if (isEpochCurrentLocked(epoch) && emittedSecret?.notice?.id == invitationId) {
                emittedSecret = null
                mutableState.value = mutableState.value.copy(emittedInvitation = null)
            }
        }
    }

    private fun isAuthFailure(reason: PairingAccessFailureReason): Boolean =
        reason == PairingAccessFailureReason.NOT_AUTHENTICATED ||
            reason == PairingAccessFailureReason.ACCESS_FORBIDDEN

    private fun isKnownNotWritten(disposition: PairingWriteDisposition?): Boolean =
        disposition == PairingWriteDisposition.NOT_SENT ||
            disposition == PairingWriteDisposition.KNOWN_NOT_WRITTEN

    private fun requiresListRefreshOnKnownNoWrite(reason: PairingAccessFailureReason): Boolean =
        reason == PairingAccessFailureReason.RESOURCE_NOT_FOUND ||
            reason == PairingAccessFailureReason.CONFLICT ||
            reason == PairingAccessFailureReason.INVITATION_UNAVAILABLE

    private fun phaseForSessionFailure(
        failure: HostAdministrationSessionFailure,
    ): HostSupportPhase =
        if (failure == HostAdministrationSessionFailure.AUTHORIZATION_REJECTED) {
            HostSupportPhase.AUTHORIZATION_REQUIRED
        } else {
            HostSupportPhase.HOST_UNAVAILABLE
        }

    private fun isEpochCurrentLocked(epoch: Long): Boolean =
        !closed && !coordinatorJob.isCancelled && operationEpoch == epoch

    private fun isEpochCurrent(epoch: Long): Boolean =
        synchronized(stateLock) {
            isEpochCurrentLocked(epoch)
        }

    private class EmittedInvitationSecret(
        val notice: HostEmittedInvitationNotice,
        val secret: SecretValue,
        val lease: HostAdministrationLease,
    ) {
        override fun toString(): String = "EmittedInvitationSecret([REDACTED])"
    }

    private class DecisionConfirmationBinding(
        val confirmation: HostSupportDecisionConfirmation,
        val lease: HostAdministrationLease,
        val snapshotEpoch: Long,
    )

    private data class DecisionTarget(
        val targetId: String,
        val installationId: String,
        val targetLabel: String?,
        val targetTimestampMillis: Long,
    )

    private data class IssueGate(
        val epoch: Long,
        val existingLease: HostAdministrationLease?,
        val hadFreshLists: Boolean,
    )

    private data class CancelInvitationGate(
        val epoch: Long,
        val lease: HostAdministrationLease,
    )

    private data class ConfirmDecisionGate(
        val epoch: Long,
        val binding: DecisionConfirmationBinding,
    )

    private sealed interface FetchOutcome {
        data object Superseded : FetchOutcome

        data class Success(
            val invitations: List<PairingInvitationSummary>,
            val requests: List<PairingRequestSummary>,
            val supports: List<PairingSupportMembership>,
        ) : FetchOutcome

        data class ReadFailed(
            val failure: PairingAccessResult.Failure,
        ) : FetchOutcome

        data class AuthorizationRejected(
            val reason: PairingAccessFailureReason,
        ) : FetchOutcome

        data class LeaseRejected(
            val failure: HostAdministrationSessionFailure,
        ) : FetchOutcome
    }
}
