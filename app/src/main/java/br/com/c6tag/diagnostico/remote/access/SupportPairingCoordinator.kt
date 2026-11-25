package br.com.c6tag.diagnostico.remote.access

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Coordinates explicit candidate support invitation redemption and safe
 * restoration without leaking secrets to UI state.
 */
internal class SupportPairingCoordinator(
    private val pairingAccessClient: PairingAccessClient,
    private val sessionStore: RemoteSupportSessionStore,
    private val roleGuard: RemoteRoleGuard,
    private val configuredOrigin: () -> String?,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val stateLock = Any()
    private var operationEpoch = 0L
    private var redemptionInFlight = false
    private var closed = false
    private var uncertainAttemptId: RemoteSupportAttemptId? = null
    private val redemptionMutex = Mutex()
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(SupportPairingViewState())

    val state: StateFlow<SupportPairingViewState> = mutableState.asStateFlow()

    init {
        coordinatorJob.invokeOnCompletion {
            close()
        }
    }

    /**
     * Loads local support session state when the explicit support surface is
     * opened or reopened. Empty and uncertain-attempt states perform zero
     * network calls; Pending and Approved validate read-only against the API.
     */
    suspend fun initialize() {
        val epoch = beginReadOperation() ?: return
        loadAndValidate(epoch)
    }

    /**
     * Explicit manual status refresh. Dropped while a redemption or another
     * refresh is already in flight; never triggers POST/DELETE mutations.
     */
    suspend fun refreshStatus() {
        checkOpen()
        if (!refreshMutex.tryLock()) {
            return
        }
        try {
            val epoch = beginReadOperation() ?: return
            loadAndValidate(epoch)
        } finally {
            refreshMutex.unlock()
        }
    }

    /**
     * Explicitly redeems a single-use invitation secret. Enforces single-flight,
     * reserves role + durable Attempt journal before POST, and commits a
     * valid 201 Pending PAT in bounded NonCancellable before propagating
     * cancellation.
     */
    suspend fun submitInvitation(
        invitationSecret: String,
        installationLabel: String = "",
        replaceUncertainAttempt: Boolean = false,
    ) {
        checkOpen()
        if (!redemptionMutex.tryLock()) {
            return
        }

        var enteredRedemption = false
        try {
            val gate = enterRedemptionGate(
                invitationSecret = invitationSecret,
                installationLabel = installationLabel,
                replaceUncertainAttempt = replaceUncertainAttempt,
            )
            if (gate !is RedemptionGate.Proceed) {
                return
            }
            enteredRedemption = true
            val epoch = gate.epoch
            val origin = gate.origin
            val normalizedSecret = gate.normalizedSecret
            val expectedPreviousAttempt = gate.expectedPreviousAttempt
            val hadPriorUncertainAttempt = gate.hadPriorUncertainAttempt

            val guarded = try {
                roleGuard.beginSupportAttempt {
                    sessionStore.beginAttempt(origin, expectedPreviousAttempt)
                }
            } catch (cancelled: CancellationException) {
                settleCancelledBeginAttempt(epoch, origin, cancelled)
                throw cancelled
            } catch (exception: Exception) {
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { current -> current },
                ) { currentUncertain ->
                    if (hadPriorUncertainAttempt) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    } else {
                        SupportPairingViewState(phase = SupportPairingPhase.RECOVERABLE_ERROR)
                    }
                }
                throw exception
            }

            val writeResult = when (guarded) {
                is RemoteRoleGuardResult.Blocked -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { current -> current },
                    ) { currentUncertain ->
                        SupportPairingViewState(
                            phase = SupportPairingPhase.ROLE_CONFLICT,
                            roleConflict = guarded.reason,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
                        )
                    }
                    return
                }

                is RemoteRoleGuardResult.Begun -> guarded.value
            }

            if (writeResult !is RemoteSupportSessionWriteResult.AttemptStarted) {
                val problem = (writeResult as? RemoteSupportSessionWriteResult.Rejected)?.problem
                    ?: RemoteSupportSessionProblem.PERSISTENCE_FAILURE
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { current ->
                        if (
                            problem == RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD &&
                            current == expectedPreviousAttempt
                        ) {
                            null
                        } else {
                            current
                        }
                    },
                ) { currentUncertain ->
                    if (
                        problem == RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS &&
                        expectedPreviousAttempt == null &&
                        currentUncertain != null
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            storageProblem = problem,
                            canReplaceUncertainAttempt = true,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    } else {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.RECOVERABLE_ERROR,
                            storageProblem = problem,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
                        )
                    }
                }
                return
            }

            val attemptRecord = writeResult.record
            val stillCurrent = synchronized(stateLock) {
                if (isCurrentLocked(epoch, origin)) {
                    uncertainAttemptId = attemptRecord.attemptId
                    true
                } else {
                    false
                }
            }
            if (!stillCurrent) {
                clearFreshUnsentAttemptIfNeeded(attemptRecord, expectedPreviousAttempt)
                return
            }

            var postInvocationStarted = false
            var postOutcomeSettled = false
            try {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(epoch, origin)) {
                    clearFreshUnsentAttemptIfNeeded(attemptRecord, expectedPreviousAttempt)
                    return
                }

                postInvocationStarted = true
                val result = pairingAccessClient.redeemInvitation(
                    invitationSecret = normalizedSecret,
                    installationLabel = installationLabel,
                )

                when (result) {
                    is PairingAccessResult.Success -> {
                        val commitResult = withContext(NonCancellable) {
                            sessionStore.commitPending(
                                origin = origin,
                                attemptId = attemptRecord.attemptId,
                                candidate = result.value,
                            )
                        }
                        when (commitResult) {
                            is RemoteSupportSessionWriteResult.Committed -> {
                                val pendingRecord =
                                    commitResult.record as? RemoteSupportSessionRecord.Pending
                                if (pendingRecord != null) {
                                    publishIfCurrentWithUncertain(
                                        epoch = epoch,
                                        origin = origin,
                                        updateUncertain = { current ->
                                            if (current == attemptRecord.attemptId) null else current
                                        },
                                    ) {
                                        SupportPairingViewState(
                                            phase = SupportPairingPhase.PENDING,
                                            pending = pendingRecord.toPendingInfo(),
                                            previousAttemptMayRemainActiveOnHost =
                                                hadPriorUncertainAttempt,
                                        )
                                    }
                                    postOutcomeSettled = true
                                    currentCoroutineContext().ensureActive()
                                } else {
                                    publishIfCurrentWithUncertain(
                                        epoch = epoch,
                                        origin = origin,
                                        updateUncertain = { current -> current },
                                    ) { currentUncertain ->
                                        SupportPairingViewState(
                                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                                            storageProblem =
                                                RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                                            canReplaceUncertainAttempt = currentUncertain != null,
                                            previousAttemptMayRemainActiveOnHost = true,
                                        )
                                    }
                                    postOutcomeSettled = true
                                    currentCoroutineContext().ensureActive()
                                }
                            }

                            is RemoteSupportSessionWriteResult.Rejected -> {
                                val clearUncertain =
                                    commitResult.problem ==
                                        RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD
                                publishIfCurrentWithUncertain(
                                    epoch = epoch,
                                    origin = origin,
                                    updateUncertain = { current ->
                                        if (clearUncertain && current == attemptRecord.attemptId) {
                                            null
                                        } else {
                                            current
                                        }
                                    },
                                ) { currentUncertain ->
                                    SupportPairingViewState(
                                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                                        storageProblem = commitResult.problem,
                                        canReplaceUncertainAttempt = currentUncertain != null,
                                        previousAttemptMayRemainActiveOnHost = true,
                                    )
                                }
                                postOutcomeSettled = true
                                currentCoroutineContext().ensureActive()
                            }

                            else -> {
                                publishIfCurrentWithUncertain(
                                    epoch = epoch,
                                    origin = origin,
                                    updateUncertain = { current -> current },
                                ) { currentUncertain ->
                                    SupportPairingViewState(
                                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                                        storageProblem =
                                            RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                                        canReplaceUncertainAttempt = currentUncertain != null,
                                        previousAttemptMayRemainActiveOnHost = true,
                                    )
                                }
                                postOutcomeSettled = true
                                currentCoroutineContext().ensureActive()
                            }
                        }
                    }

                    is PairingAccessResult.Failure -> {
                        handlePostFailure(
                            epoch = epoch,
                            origin = origin,
                            attemptRecord = attemptRecord,
                            expectedPreviousAttempt = expectedPreviousAttempt,
                            hadPriorUncertainAttempt = hadPriorUncertainAttempt,
                            failure = result,
                            onOutcomeSettled = { postOutcomeSettled = true },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                settleCancelledAfterAttemptStarted(
                    epoch = epoch,
                    origin = origin,
                    attemptRecord = attemptRecord,
                    expectedPreviousAttempt = expectedPreviousAttempt,
                    hadPriorUncertainAttempt = hadPriorUncertainAttempt,
                    postInvocationStarted = postInvocationStarted,
                    postOutcomeSettled = postOutcomeSettled,
                    cancellation = cancelled,
                )
                throw cancelled
            } catch (exception: Exception) {
                settleUnexpectedFailureAfterAttemptStarted(
                    epoch = epoch,
                    origin = origin,
                    attemptRecord = attemptRecord,
                    expectedPreviousAttempt = expectedPreviousAttempt,
                    hadPriorUncertainAttempt = hadPriorUncertainAttempt,
                    postInvocationStarted = postInvocationStarted,
                    postOutcomeSettled = postOutcomeSettled,
                    failure = exception,
                )
                throw exception
            }
        } finally {
            if (enteredRedemption) {
                synchronized(stateLock) {
                    redemptionInFlight = false
                }
            }
            redemptionMutex.unlock()
        }
    }

    override fun close() {
        val shouldCancel = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                operationEpoch += 1
                uncertainAttemptId = null
                mutableState.value = SupportPairingViewState()
                true
            }
        }
        if (shouldCancel) {
            coordinatorJob.cancel()
        }
    }

    private fun enterRedemptionGate(
        invitationSecret: String,
        installationLabel: String,
        replaceUncertainAttempt: Boolean,
    ): RedemptionGate =
        synchronized(stateLock) {
            check(!closed) { "Support pairing coordinator is closed" }
            if (coordinatorJob.isCancelled) {
                return@synchronized RedemptionGate.Drop
            }
            val currentState = mutableState.value
            val knownUncertainId = uncertainAttemptId
            if (currentState.pending != null || currentState.confirmed != null) {
                return@synchronized RedemptionGate.Drop
            }

            val (origin, originFailure) = resolveOrigin()
            if (origin == null) {
                operationEpoch += 1
                mutableState.value = SupportPairingViewState(
                    phase = SupportPairingPhase.RECOVERABLE_ERROR,
                    safeFailure = originFailure,
                    canReplaceUncertainAttempt = knownUncertainId != null,
                    previousAttemptMayRemainActiveOnHost =
                        knownUncertainId != null || currentState.previousAttemptMayRemainActiveOnHost,
                )
                return@synchronized RedemptionGate.Drop
            }

            val normalizedSecret = invitationSecret.trim()
            if (
                !PairingAccessJson.isValidInvitationSecret(normalizedSecret) ||
                installationLabel.codePointCount(0, installationLabel.length) >
                MAX_INSTALLATION_LABEL_CODE_POINTS ||
                !PairingAccessJson.isValidUtf8(installationLabel)
            ) {
                operationEpoch += 1
                mutableState.value = if (knownUncertainId != null) {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                        safeFailure = PairingAccessFailureReason.INVALID_PAYLOAD,
                        canReplaceUncertainAttempt = true,
                        previousAttemptMayRemainActiveOnHost = true,
                    )
                } else {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        safeFailure = PairingAccessFailureReason.INVALID_PAYLOAD,
                        canReplaceUncertainAttempt = false,
                        previousAttemptMayRemainActiveOnHost =
                            currentState.previousAttemptMayRemainActiveOnHost,
                    )
                }
                return@synchronized RedemptionGate.Drop
            }

            val expectedPreviousAttempt = if (replaceUncertainAttempt) {
                if (knownUncertainId == null) {
                    operationEpoch += 1
                    mutableState.value = SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        storageProblem = RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                    )
                    return@synchronized RedemptionGate.Drop
                }
                knownUncertainId
            } else {
                if (knownUncertainId != null) {
                    operationEpoch += 1
                    mutableState.value = SupportPairingViewState(
                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                        storageProblem = RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
                        canReplaceUncertainAttempt = true,
                        previousAttemptMayRemainActiveOnHost = true,
                    )
                    return@synchronized RedemptionGate.Drop
                }
                null
            }

            val hadPriorUncertainAttempt =
                replaceUncertainAttempt || currentState.previousAttemptMayRemainActiveOnHost
            redemptionInFlight = true
            operationEpoch += 1
            val epoch = operationEpoch
            mutableState.value = SupportPairingViewState(
                phase = SupportPairingPhase.REDEEMING,
                previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
            )
            RedemptionGate.Proceed(
                epoch = epoch,
                origin = origin,
                normalizedSecret = normalizedSecret,
                expectedPreviousAttempt = expectedPreviousAttempt,
                hadPriorUncertainAttempt = hadPriorUncertainAttempt,
            )
        }

    private suspend fun loadAndValidate(epoch: Long) {
        val (origin, originFailure) = resolveOrigin()
        if (origin == null) {
            publishIfEpochCurrent(
                epoch = epoch,
                nextState = SupportPairingViewState(
                    phase = SupportPairingPhase.RECOVERABLE_ERROR,
                    safeFailure = originFailure,
                ),
                clearUncertainAttemptId = true,
            )
            return
        }

        val loaded = try {
            sessionStore.load(origin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                origin,
                SupportPairingViewState(phase = SupportPairingPhase.RECOVERABLE_ERROR),
            )
            throw exception
        }
        if (!isCurrent(epoch, origin)) {
            return
        }

        when (loaded) {
            RemoteSupportSessionLoad.Empty -> {
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { null },
                ) {
                    SupportPairingViewState(phase = SupportPairingPhase.EMPTY)
                }
            }

            is RemoteSupportSessionLoad.Attempt -> {
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { loaded.record.attemptId },
                ) {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                        canReplaceUncertainAttempt = true,
                        previousAttemptMayRemainActiveOnHost = true,
                    )
                }
            }

            is RemoteSupportSessionLoad.Pending -> {
                val record = loaded.record
                val published = publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { null },
                ) {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.VALIDATING,
                        pending = record.toPendingInfo(),
                    )
                }
                if (!published) {
                    return
                }
                val currentResult = try {
                    pairingAccessClient.currentRequest(record.toCandidateInstallation())
                } catch (cancelled: CancellationException) {
                    publishIfCurrent(
                        epoch,
                        origin,
                        SupportPairingViewState(
                            phase = SupportPairingPhase.OFFLINE_STALE,
                            pending = record.toPendingInfo(),
                        ),
                    )
                    throw cancelled
                } catch (exception: Exception) {
                    publishIfCurrent(
                        epoch,
                        origin,
                        SupportPairingViewState(
                            phase = SupportPairingPhase.OFFLINE_STALE,
                            pending = record.toPendingInfo(),
                        ),
                    )
                    throw exception
                }
                if (!isCurrent(epoch, origin)) {
                    return
                }

                when (currentResult) {
                    is PairingAccessResult.Failure -> {
                        handleReadFailure(epoch, origin, currentResult, record)
                    }

                    is PairingAccessResult.Success -> {
                        val current = currentResult.value
                        when (current.status) {
                            "Pending" -> {
                                publishIfCurrent(
                                    epoch,
                                    origin,
                                    SupportPairingViewState(
                                        phase = SupportPairingPhase.PENDING,
                                        pending = PendingSupportPairingInfo(
                                            requestId = record.requestId,
                                            installationId = record.installationId,
                                            requestExpiresAtMillis = current.requestExpiresAtMillis,
                                        ),
                                    ),
                                )
                            }

                            "Approved" -> {
                                validateAndCommitApproved(
                                    epoch = epoch,
                                    origin = origin,
                                    consultedRecord = record,
                                    expectedCurrent = current,
                                )
                            }

                            else -> {
                                publishIfCurrent(
                                    epoch,
                                    origin,
                                    SupportPairingViewState(
                                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                        pending = record.toPendingInfo(),
                                        safeFailure = PairingAccessFailureReason.INVALID_RESPONSE,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            is RemoteSupportSessionLoad.Approved -> {
                val record = loaded.record
                val published = publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { null },
                ) {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.VALIDATING,
                        confirmed = record.toConfirmedInfo(),
                    )
                }
                if (!published) {
                    return
                }
                validateAndCommitApproved(
                    epoch = epoch,
                    origin = origin,
                    consultedRecord = record,
                    expectedCurrent = record.toExpectedCurrentRequest(),
                )
            }

            is RemoteSupportSessionLoad.Unavailable -> {
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { null },
                ) {
                    SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        storageProblem = loaded.problem,
                    )
                }
            }
        }
    }

    private suspend fun validateAndCommitApproved(
        epoch: Long,
        origin: String,
        consultedRecord: RemoteSupportSessionRecord,
        expectedCurrent: PairingCurrentRequest,
    ) {
        val candidate = when (consultedRecord) {
            is RemoteSupportSessionRecord.Pending -> consultedRecord.toCandidateInstallation()
            is RemoteSupportSessionRecord.Approved -> consultedRecord.toCandidateInstallation()
            is RemoteSupportSessionRecord.Attempt -> return
        }
        val pendingInfo = (consultedRecord as? RemoteSupportSessionRecord.Pending)?.toPendingInfo()
        val confirmedInfo =
            (consultedRecord as? RemoteSupportSessionRecord.Approved)?.toConfirmedInfo()

        val result = try {
            pairingAccessClient.validateSupportInstallation(candidate, expectedCurrent)
        } catch (cancelled: CancellationException) {
            publishIfCurrent(
                epoch,
                origin,
                SupportPairingViewState(
                    phase = SupportPairingPhase.OFFLINE_STALE,
                    pending = pendingInfo,
                    confirmed = confirmedInfo,
                ),
            )
            throw cancelled
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                origin,
                SupportPairingViewState(
                    phase = SupportPairingPhase.OFFLINE_STALE,
                    pending = pendingInfo,
                    confirmed = confirmedInfo,
                ),
            )
            throw exception
        }
        if (!isCurrent(epoch, origin)) {
            return
        }

        when (result) {
            is PairingAccessResult.Failure -> {
                handleReadFailure(epoch, origin, result, consultedRecord)
            }

            is PairingAccessResult.Success -> {
                val commitResult = try {
                    withContext(NonCancellable) {
                        sessionStore.commitApproved(consultedRecord, result.value)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    publishIfCurrent(
                        epoch,
                        origin,
                        SupportPairingViewState(
                            phase = SupportPairingPhase.OFFLINE_STALE,
                            pending = pendingInfo,
                            confirmed = confirmedInfo,
                        ),
                    )
                    throw exception
                }

                when (commitResult) {
                    is RemoteSupportSessionWriteResult.Committed -> {
                        val approved = commitResult.record as? RemoteSupportSessionRecord.Approved
                        if (approved != null) {
                            publishIfCurrent(
                                epoch,
                                origin,
                                SupportPairingViewState(
                                    phase = SupportPairingPhase.CONFIRMED,
                                    confirmed = approved.toConfirmedInfo(),
                                ),
                            )
                        } else {
                            publishIfCurrent(
                                epoch,
                                origin,
                                SupportPairingViewState(
                                    phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                    pending = pendingInfo,
                                    confirmed = confirmedInfo,
                                    storageProblem = RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                                ),
                            )
                        }
                    }

                    is RemoteSupportSessionWriteResult.Rejected -> {
                        publishIfCurrent(
                            epoch,
                            origin,
                            SupportPairingViewState(
                                phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                pending = pendingInfo,
                                confirmed = confirmedInfo,
                                storageProblem = commitResult.problem,
                            ),
                        )
                    }

                    else -> {
                        publishIfCurrent(
                            epoch,
                            origin,
                            SupportPairingViewState(
                                phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                pending = pendingInfo,
                                confirmed = confirmedInfo,
                                storageProblem = RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                            ),
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
            }
        }
    }

    private suspend fun handleReadFailure(
        epoch: Long,
        origin: String,
        failure: PairingAccessResult.Failure,
        consultedRecord: RemoteSupportSessionRecord,
    ) {
        if (!isCurrent(epoch, origin)) {
            return
        }
        val pendingInfo = (consultedRecord as? RemoteSupportSessionRecord.Pending)?.toPendingInfo()
        val confirmedInfo =
            (consultedRecord as? RemoteSupportSessionRecord.Approved)?.toConfirmedInfo()

        when (failure.reason) {
            PairingAccessFailureReason.NOT_AUTHENTICATED -> {
                val clearResult = try {
                    sessionStore.clearExpected(consultedRecord)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    publishIfCurrent(
                        epoch,
                        origin,
                        SupportPairingViewState(
                            phase = SupportPairingPhase.RECOVERABLE_ERROR,
                            pending = pendingInfo,
                            confirmed = confirmedInfo,
                            safeFailure = failure.reason,
                        ),
                    )
                    throw exception
                }
                if (!isCurrent(epoch, origin)) {
                    return
                }
                when (clearResult) {
                    RemoteSupportSessionWriteResult.Cleared -> {
                        publishIfCurrent(
                            epoch,
                            origin,
                            SupportPairingViewState(
                                phase = SupportPairingPhase.CREDENTIAL_LOST,
                                safeFailure = PairingAccessFailureReason.NOT_AUTHENTICATED,
                            ),
                        )
                    }

                    is RemoteSupportSessionWriteResult.Rejected -> {
                        publishIfCurrent(
                            epoch,
                            origin,
                            SupportPairingViewState(
                                phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                pending = pendingInfo,
                                confirmed = confirmedInfo,
                                safeFailure = PairingAccessFailureReason.NOT_AUTHENTICATED,
                                storageProblem = clearResult.problem,
                            ),
                        )
                    }

                    else -> {
                        publishIfCurrent(
                            epoch,
                            origin,
                            SupportPairingViewState(
                                phase = SupportPairingPhase.RECOVERABLE_ERROR,
                                pending = pendingInfo,
                                confirmed = confirmedInfo,
                                safeFailure = PairingAccessFailureReason.NOT_AUTHENTICATED,
                                storageProblem = RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                            ),
                        )
                    }
                }
            }

            PairingAccessFailureReason.RATE_LIMITED,
            PairingAccessFailureReason.NETWORK_FAILURE,
            PairingAccessFailureReason.SERVICE_UNAVAILABLE,
            PairingAccessFailureReason.IDENTITY_VERIFICATION_UNAVAILABLE -> {
                publishIfCurrent(
                    epoch,
                    origin,
                    SupportPairingViewState(
                        phase = SupportPairingPhase.OFFLINE_STALE,
                        pending = pendingInfo,
                        confirmed = confirmedInfo,
                        safeFailure = failure.reason,
                        retryAfterSeconds = failure.retryAfterSeconds,
                    ),
                )
            }

            else -> {
                publishIfCurrent(
                    epoch,
                    origin,
                    SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        pending = pendingInfo,
                        confirmed = confirmedInfo,
                        safeFailure = failure.reason,
                        retryAfterSeconds = failure.retryAfterSeconds,
                    ),
                )
            }
        }
    }

    private suspend fun handlePostFailure(
        epoch: Long,
        origin: String,
        attemptRecord: RemoteSupportSessionRecord.Attempt,
        expectedPreviousAttempt: RemoteSupportAttemptId?,
        hadPriorUncertainAttempt: Boolean,
        failure: PairingAccessResult.Failure,
        onOutcomeSettled: () -> Unit,
    ) {
        val knownNoWrite = failure.writeDisposition == PairingWriteDisposition.NOT_SENT ||
            failure.writeDisposition == PairingWriteDisposition.KNOWN_NOT_WRITTEN
        if (knownNoWrite) {
            if (expectedPreviousAttempt == null) {
                val clearResult = withContext(NonCancellable) {
                    sessionStore.clearExpected(attemptRecord)
                }
                if (clearResult == RemoteSupportSessionWriteResult.Cleared) {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { current ->
                            if (current == attemptRecord.attemptId) null else current
                        },
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.RECOVERABLE_ERROR,
                            safeFailure = failure.reason,
                            retryAfterSeconds = failure.retryAfterSeconds,
                            canReplaceUncertainAttempt = false,
                            previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
                        )
                    }
                    onOutcomeSettled()
                    currentCoroutineContext().ensureActive()
                } else {
                    val problem =
                        (clearResult as? RemoteSupportSessionWriteResult.Rejected)?.problem
                            ?: RemoteSupportSessionProblem.PERSISTENCE_FAILURE
                    val clearUncertain =
                        problem == RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { current ->
                            if (clearUncertain && current == attemptRecord.attemptId) {
                                null
                            } else {
                                current
                            }
                        },
                    ) { currentUncertain ->
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            safeFailure = failure.reason,
                            retryAfterSeconds = failure.retryAfterSeconds,
                            storageProblem = problem,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    }
                    onOutcomeSettled()
                    currentCoroutineContext().ensureActive()
                }
            } else {
                // Keep the replacement attempt marker on disk so reopen still
                // remembers that an earlier attempt may remain active on the host.
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { current -> current },
                ) { currentUncertain ->
                    SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        safeFailure = failure.reason,
                        retryAfterSeconds = failure.retryAfterSeconds,
                        canReplaceUncertainAttempt = currentUncertain != null,
                        previousAttemptMayRemainActiveOnHost = true,
                    )
                }
                onOutcomeSettled()
            }
        } else {
            publishIfCurrentWithUncertain(
                epoch = epoch,
                origin = origin,
                updateUncertain = { current -> current },
            ) { currentUncertain ->
                SupportPairingViewState(
                    phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                    safeFailure = failure.reason,
                    retryAfterSeconds = failure.retryAfterSeconds,
                    canReplaceUncertainAttempt = currentUncertain != null,
                    previousAttemptMayRemainActiveOnHost = true,
                )
            }
            onOutcomeSettled()
        }
    }

    private suspend fun settleCancelledBeginAttempt(
        epoch: Long,
        origin: String,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            if (!isCurrent(epoch, origin)) {
                return@withContext
            }
            val loaded = try {
                sessionStore.load(origin)
            } catch (settlementFailure: Exception) {
                cancellation.addSuppressed(settlementFailure)
                null
            }
            when (loaded) {
                is RemoteSupportSessionLoad.Attempt -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { loaded.record.attemptId },
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            canReplaceUncertainAttempt = true,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    }
                }

                is RemoteSupportSessionLoad.Pending -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { null },
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.OFFLINE_STALE,
                            pending = loaded.record.toPendingInfo(),
                        )
                    }
                }

                is RemoteSupportSessionLoad.Approved -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { null },
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.OFFLINE_STALE,
                            confirmed = loaded.record.toConfirmedInfo(),
                        )
                    }
                }

                RemoteSupportSessionLoad.Empty -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { null },
                    ) {
                        SupportPairingViewState(phase = SupportPairingPhase.EMPTY)
                    }
                }

                is RemoteSupportSessionLoad.Unavailable -> {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { null },
                    ) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.RECOVERABLE_ERROR,
                            storageProblem = loaded.problem,
                        )
                    }
                }

                null -> {
                    publishIfCurrent(
                        epoch,
                        origin,
                        SupportPairingViewState(phase = SupportPairingPhase.RECOVERABLE_ERROR),
                    )
                }
            }
        }
    }

    private suspend fun settleCancelledAfterAttemptStarted(
        epoch: Long,
        origin: String,
        attemptRecord: RemoteSupportSessionRecord.Attempt,
        expectedPreviousAttempt: RemoteSupportAttemptId?,
        hadPriorUncertainAttempt: Boolean,
        postInvocationStarted: Boolean,
        postOutcomeSettled: Boolean,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            if (postOutcomeSettled) {
                return@withContext
            }

            if (!postInvocationStarted) {
                if (expectedPreviousAttempt == null) {
                    var cleared = false
                    var problem: RemoteSupportSessionProblem? = null
                    try {
                        when (val clearResult = sessionStore.clearExpected(attemptRecord)) {
                            RemoteSupportSessionWriteResult.Cleared -> cleared = true
                            is RemoteSupportSessionWriteResult.Rejected -> problem = clearResult.problem
                            else -> problem = RemoteSupportSessionProblem.PERSISTENCE_FAILURE
                        }
                    } catch (cleanupFailure: Exception) {
                        cancellation.addSuppressed(cleanupFailure)
                    }
                    val clearUncertain = cleared ||
                        problem == RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { current ->
                            if (clearUncertain && current == attemptRecord.attemptId) {
                                null
                            } else {
                                current
                            }
                        },
                    ) { currentUncertain ->
                        if (cleared) {
                            SupportPairingViewState(
                                phase = SupportPairingPhase.EMPTY,
                                previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
                            )
                        } else {
                            SupportPairingViewState(
                                phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                                storageProblem = problem,
                                canReplaceUncertainAttempt = currentUncertain != null,
                                previousAttemptMayRemainActiveOnHost = true,
                            )
                        }
                    }
                } else {
                    publishIfCurrentWithUncertain(
                        epoch = epoch,
                        origin = origin,
                        updateUncertain = { current -> current },
                    ) { currentUncertain ->
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    }
                }
                return@withContext
            }

            publishIfCurrentWithUncertain(
                epoch = epoch,
                origin = origin,
                updateUncertain = { current -> current },
            ) { currentUncertain ->
                SupportPairingViewState(
                    phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                    canReplaceUncertainAttempt = currentUncertain != null,
                    previousAttemptMayRemainActiveOnHost = true,
                )
            }
        }
    }

    private suspend fun settleUnexpectedFailureAfterAttemptStarted(
        epoch: Long,
        origin: String,
        attemptRecord: RemoteSupportSessionRecord.Attempt,
        expectedPreviousAttempt: RemoteSupportAttemptId?,
        hadPriorUncertainAttempt: Boolean,
        postInvocationStarted: Boolean,
        postOutcomeSettled: Boolean,
        failure: Exception,
    ) {
        withContext(NonCancellable) {
            if (postOutcomeSettled) {
                return@withContext
            }

            if (!postInvocationStarted && expectedPreviousAttempt == null) {
                var cleared = false
                var problem: RemoteSupportSessionProblem? = null
                try {
                    when (val clearResult = sessionStore.clearExpected(attemptRecord)) {
                        RemoteSupportSessionWriteResult.Cleared -> cleared = true
                        is RemoteSupportSessionWriteResult.Rejected -> problem = clearResult.problem
                        else -> problem = RemoteSupportSessionProblem.PERSISTENCE_FAILURE
                    }
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                val clearUncertain = cleared ||
                    problem == RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD
                publishIfCurrentWithUncertain(
                    epoch = epoch,
                    origin = origin,
                    updateUncertain = { current ->
                        if (clearUncertain && current == attemptRecord.attemptId) {
                            null
                        } else {
                            current
                        }
                    },
                ) { currentUncertain ->
                    if (cleared) {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.RECOVERABLE_ERROR,
                            previousAttemptMayRemainActiveOnHost = hadPriorUncertainAttempt,
                        )
                    } else {
                        SupportPairingViewState(
                            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                            storageProblem = problem,
                            canReplaceUncertainAttempt = currentUncertain != null,
                            previousAttemptMayRemainActiveOnHost = true,
                        )
                    }
                }
                return@withContext
            }

            publishIfCurrentWithUncertain(
                epoch = epoch,
                origin = origin,
                updateUncertain = { current -> current },
            ) { currentUncertain ->
                SupportPairingViewState(
                    phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                    canReplaceUncertainAttempt = currentUncertain != null,
                    previousAttemptMayRemainActiveOnHost = true,
                )
            }
        }
    }

    private suspend fun clearFreshUnsentAttemptIfNeeded(
        attemptRecord: RemoteSupportSessionRecord.Attempt,
        expectedPreviousAttempt: RemoteSupportAttemptId?,
    ) {
        if (expectedPreviousAttempt != null) {
            return
        }
        withContext(NonCancellable) {
            if (sessionStore.clearExpected(attemptRecord) == RemoteSupportSessionWriteResult.Cleared) {
                synchronized(stateLock) {
                    if (uncertainAttemptId == attemptRecord.attemptId) {
                        uncertainAttemptId = null
                    }
                }
            }
        }
    }

    private fun resolveOrigin(): Pair<String?, PairingAccessFailureReason?> {
        val raw = configuredOrigin()
        if (raw.isNullOrEmpty()) {
            return null to PairingAccessFailureReason.NOT_CONFIGURED
        }
        val canonical = canonicalOwnerApiOrigin(raw)
        if (canonical == null || canonical != raw) {
            return null to PairingAccessFailureReason.INVALID_ORIGIN
        }
        return canonical to null
    }

    private fun beginReadOperation(): Long? =
        synchronized(stateLock) {
            check(!closed) { "Support pairing coordinator is closed" }
            if (coordinatorJob.isCancelled || redemptionInFlight) {
                null
            } else {
                operationEpoch += 1
                operationEpoch
            }
        }

    private fun isEpochCurrentLocked(epoch: Long): Boolean =
        !closed && !coordinatorJob.isCancelled && operationEpoch == epoch

    private fun isCurrentLocked(epoch: Long, expectedOrigin: String): Boolean =
        isEpochCurrentLocked(epoch) && resolveOrigin().first == expectedOrigin

    private fun isCurrent(epoch: Long, expectedOrigin: String): Boolean =
        synchronized(stateLock) {
            isCurrentLocked(epoch, expectedOrigin)
        }

    private fun publishIfEpochCurrent(
        epoch: Long,
        nextState: SupportPairingViewState,
        clearUncertainAttemptId: Boolean = false,
    ) {
        synchronized(stateLock) {
            if (isEpochCurrentLocked(epoch)) {
                if (clearUncertainAttemptId) {
                    uncertainAttemptId = null
                }
                mutableState.value = nextState
            }
        }
    }

    private fun publishIfCurrent(
        epoch: Long,
        origin: String,
        nextState: SupportPairingViewState,
    ) {
        synchronized(stateLock) {
            if (isCurrentLocked(epoch, origin)) {
                mutableState.value = nextState
            }
        }
    }

    private fun publishIfCurrentWithUncertain(
        epoch: Long,
        origin: String,
        updateUncertain: (RemoteSupportAttemptId?) -> RemoteSupportAttemptId?,
        buildState: (RemoteSupportAttemptId?) -> SupportPairingViewState,
    ): Boolean =
        synchronized(stateLock) {
            if (!isCurrentLocked(epoch, origin)) {
                return@synchronized false
            }
            val updated = updateUncertain(uncertainAttemptId)
            uncertainAttemptId = updated
            mutableState.value = buildState(updated)
            true
        }

    private fun checkOpen() {
        synchronized(stateLock) {
            check(!closed) { "Support pairing coordinator is closed" }
        }
    }

    private fun RemoteSupportSessionRecord.Pending.toPendingInfo(): PendingSupportPairingInfo =
        PendingSupportPairingInfo(
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = requestExpiresAtMillis,
        )

    private fun RemoteSupportSessionRecord.Approved.toConfirmedInfo(): ConfirmedSupportAccess =
        ConfirmedSupportAccess(
            requestId = requestId,
            installationId = installationId,
            monitorId = monitorId,
            membershipId = membershipId,
            observedGeneration = observedGeneration,
        )

    private fun RemoteSupportSessionRecord.Pending.toCandidateInstallation():
        PairingCandidateInstallation =
        PairingCandidateInstallation(
            origin = origin,
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = requestExpiresAtMillis,
            accessToken = accessToken,
            status = "Pending",
        )

    private fun RemoteSupportSessionRecord.Approved.toCandidateInstallation():
        PairingCandidateInstallation =
        PairingCandidateInstallation(
            origin = origin,
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = requestExpiresAtMillis,
            accessToken = accessToken,
            status = "Pending",
        )

    private fun RemoteSupportSessionRecord.Approved.toExpectedCurrentRequest():
        PairingCurrentRequest =
        PairingCurrentRequest(
            requestId = requestId,
            status = "Approved",
            requestExpiresAtMillis = requestExpiresAtMillis,
            decidedAtMillis = 0L,
            monitor = PairingApprovedMonitor(
                id = monitorId,
                membershipId = membershipId,
                role = "Support",
            ),
        )

    private sealed interface RedemptionGate {
        data object Drop : RedemptionGate

        data class Proceed(
            val epoch: Long,
            val origin: String,
            val normalizedSecret: String,
            val expectedPreviousAttempt: RemoteSupportAttemptId?,
            val hadPriorUncertainAttempt: Boolean,
        ) : RedemptionGate
    }

    private companion object {
        const val MAX_INSTALLATION_LABEL_CODE_POINTS = 128
    }
}
