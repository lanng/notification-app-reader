package br.com.c6tag.diagnostico.remote.access

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinates explicit remote-owner actions. It never acquires UI credentials,
 * owns an Activity, or participates in local monitor composition.
 */
internal class RemoteHostCoordinator(
    private val identity: OwnerIdentity,
    private val ownerAccessClient: OwnerAccessClient,
    private val sessionStore: RemoteHostSessionStore,
    parentScope: CoroutineScope,
    private val confirmationId: () -> String = { java.util.UUID.randomUUID().toString() },
) : AutoCloseable {
    private val coordinatorJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(parentScope.coroutineContext + coordinatorJob)
    private val operationEpoch = AtomicLong()
    private val identityMutationInProgress = AtomicBoolean()
    private val lastObservedAccount = AtomicReference(identity.account.value)
    private val activeConfirmation = AtomicReference<ConfirmationBinding?>()
    private val reauthenticatedOwner = AtomicReference<OwnerKey?>()
    private val closed = AtomicBoolean()
    private val activationMutex = Mutex()
    private val mutableState = MutableStateFlow(
        RemoteHostViewState(
            phase = if (identity.account.value == null) {
                RemoteHostPhase.LOCAL_ONLY
            } else {
                RemoteHostPhase.SIGNED_IN_API_NOT_READY
            },
        ),
    )

    val state: StateFlow<RemoteHostViewState> = mutableState.asStateFlow()

    init {
        coordinatorScope.launch {
            identity.account.collect(::onAccountChanged)
        }
    }

    /**
     * Sign-in only establishes a Firebase candidate. It never performs discovery
     * or host activation.
     */
    suspend fun signIn(googleIdToken: SecretValue): IdentityResult {
        checkOpen()
        val epoch = nextOperation()
        identityMutationInProgress.set(true)
        mutableState.value = RemoteHostViewState(RemoteHostPhase.SIGNING_IN)

        return try {
            val result = identity.signIn(googleIdToken)
            val account = identity.account.value
            lastObservedAccount.set(account)
            if (isEpochCurrent(epoch)) {
                mutableState.value = if (account == null) {
                    RemoteHostViewState(RemoteHostPhase.LOCAL_ONLY)
                } else {
                    RemoteHostViewState(
                        phase = RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                        safeFailure = if (ownerAccessClient.canonicalOrigin == null) {
                            OwnerAccessFailureReason.NOT_CONFIGURED
                        } else {
                            null
                        },
                    )
                }
            }
            result
        } catch (cancelled: CancellationException) {
            if (isEpochCurrent(epoch)) {
                publishAccountNotReady(identity.account.value)
            }
            throw cancelled
        } catch (exception: Exception) {
            if (isEpochCurrent(epoch)) {
                mutableState.value = RemoteHostViewState(
                    phase = if (identity.account.value == null) {
                        RemoteHostPhase.LOCAL_ONLY
                    } else {
                        RemoteHostPhase.RECOVERABLE_ERROR
                    },
                )
            }
            throw exception
        } finally {
            identityMutationInProgress.set(false)
            lastObservedAccount.set(identity.account.value)
        }
    }

    /**
     * Loads local remote-session state and validates a saved PAT only when this
     * explicit remote surface is opened. Empty or pending storage never causes
     * Google UI, discovery, or activation.
     */
    suspend fun initialize() {
        checkOpen()
        val epoch = nextOperation()
        val context = currentContext()
        if (context == null) {
            publishAccountNotReady(identity.account.value)
            return
        }
        if (ownerAccessClient.canonicalOrigin == null) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(
                    RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                    safeFailure = OwnerAccessFailureReason.NOT_CONFIGURED,
                ),
            )
            return
        }

        mutableState.value = RemoteHostViewState(RemoteHostPhase.DISCOVERING)
        val loaded = loadSession(context, epoch)
        if (!isCurrent(epoch, context)) {
            return
        }
        when (loaded) {
            RemoteHostSessionLoad.Empty -> {
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(RemoteHostPhase.SIGNED_IN_API_NOT_READY),
                )
            }

            is RemoteHostSessionLoad.PendingAttempt -> {
                reauthenticatedOwner.set(null)
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(RemoteHostPhase.ACTIVATION_UNCERTAIN),
                )
            }

            is RemoteHostSessionLoad.ActiveSession -> {
                validateSavedActivation(context, loaded.activation, epoch)
            }

            is RemoteHostSessionLoad.Unavailable -> {
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                )
            }
        }
    }

    /**
     * A fresh Google credential is supplied by the caller. Successful
     * reauthentication only permits a later discovery; it never activates.
     */
    suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult {
        checkOpen()
        val epoch = nextOperation()
        val accountBefore = identity.account.value
        if (accountBefore == null) {
            publishAccountNotReady(null)
            return IdentityResult(IdentityOutcome.NOT_SIGNED_IN)
        }

        identityMutationInProgress.set(true)
        mutableState.value = RemoteHostViewState(RemoteHostPhase.SIGNING_IN)
        return try {
            val result = identity.reauthenticate(googleIdToken)
            val accountAfter = identity.account.value
            lastObservedAccount.set(accountAfter)
            if (!sameOwner(accountBefore, accountAfter)) {
                operationEpoch.incrementAndGet()
                reauthenticatedOwner.set(null)
                publishAccountNotReady(accountAfter)
                return result.copy(outcome = IdentityOutcome.ACCOUNT_MISMATCH)
            }

            if (!isEpochCurrent(epoch)) {
                return result
            }

            if (result.outcome == IdentityOutcome.REAUTHENTICATED) {
                reauthenticatedOwner.set(accountBefore.toOwnerKey())
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                )
            } else {
                reauthenticatedOwner.set(null)
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.REAUTHENTICATION_REQUIRED,
                )
            }
            result
        } catch (cancelled: CancellationException) {
            reauthenticatedOwner.set(null)
            if (isEpochCurrent(epoch)) {
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.REAUTHENTICATION_REQUIRED,
                )
            }
            throw cancelled
        } catch (exception: Exception) {
            reauthenticatedOwner.set(null)
            if (isEpochCurrent(epoch)) {
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.REAUTHENTICATION_REQUIRED,
                )
            }
            throw exception
        } finally {
            identityMutationInProgress.set(false)
            lastObservedAccount.set(identity.account.value)
        }
    }

    /**
     * Discovery is read-only. Its non-secret confirmation is bound to account,
     * canonical origin, server generation, and a fresh local single-use id.
     */
    suspend fun discoverOwnerMonitor() {
        checkOpen()
        val epoch = nextOperation()
        val context = currentContext()
        if (context == null) {
            publishAccountNotReady(identity.account.value)
            return
        }
        if (ownerAccessClient.canonicalOrigin != context.origin) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(
                    phase = RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                    safeFailure = OwnerAccessFailureReason.NOT_CONFIGURED,
                ),
            )
            return
        }

        mutableState.value = RemoteHostViewState(RemoteHostPhase.DISCOVERING)
        val savedState = loadSession(context, epoch)
        if (!isCurrent(epoch, context)) {
            return
        }
        val previousActivation: HostActivation?
        val recoveryAttempt: RemoteHostAttemptId?
        when (savedState) {
            RemoteHostSessionLoad.Empty -> {
                previousActivation = null
                recoveryAttempt = null
            }

            is RemoteHostSessionLoad.ActiveSession -> {
                previousActivation = savedState.activation
                recoveryAttempt = null
            }

            is RemoteHostSessionLoad.PendingAttempt -> {
                previousActivation = null
                if (reauthenticatedOwner.get() != context.owner.toOwnerKey()) {
                    publishIfCurrent(
                        epoch,
                        context,
                        RemoteHostViewState(RemoteHostPhase.ACTIVATION_UNCERTAIN),
                    )
                    return
                }
                recoveryAttempt = savedState.attemptId
            }

            is RemoteHostSessionLoad.Unavailable -> {
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                )
                return
            }
        }

        val firebaseIdToken = try {
            identity.firebaseIdToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: IdentityOperationException) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(
                    phase = RemoteHostPhase.RECOVERABLE_ERROR,
                    safeFailure = OwnerAccessFailureReason.NOT_AUTHENTICATED,
                ),
            )
            return
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
            )
            throw exception
        }
        if (!isCurrent(epoch, context)) {
            return
        }

        val result = try {
            ownerAccessClient.discoverOwnerMonitor(context.owner, firebaseIdToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(
                    phase = RemoteHostPhase.RECOVERABLE_ERROR,
                ),
            )
            throw exception
        }
        if (!isCurrent(epoch, context)) {
            return
        }

        when (result) {
            is OwnerAccessResult.Failure -> {
                publishIfCurrent(epoch, context, stateForFailure(result))
            }

            is OwnerAccessResult.Success -> {
                val id = try {
                    confirmationId().takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("Invalid local confirmation id")
                } catch (exception: Exception) {
                    publishIfCurrent(
                        epoch,
                        context,
                        RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                    )
                    throw exception
                }
                val snapshot = HostActivationConfirmation(
                    confirmationId = id,
                    firebaseProjectId = context.owner.firebaseProjectId,
                    uid = context.owner.uid,
                    origin = context.origin,
                    generation = result.value.generation,
                    monitorId = result.value.monitorId,
                    existingHostInstallationId = result.value.hostInstallationId,
                )
                activeConfirmation.set(
                    ConfirmationBinding(
                        snapshot = snapshot,
                        context = context,
                        discoveryEpoch = epoch,
                        recoveryAttemptId = recoveryAttempt,
                        previousActivation = previousActivation,
                    ),
                )
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(
                        phase = RemoteHostPhase.CONFIRMATION_READY,
                        confirmation = snapshot,
                    ),
                )
            }
        }
    }

    /**
     * Posts only after the caller returns the current confirmation id. Repeated
     * taps are dropped while the single activation operation is in flight.
     */
    suspend fun confirmHostActivation(
        confirmationId: String,
        hostReplacementWarningConfirmed: Boolean = false,
    ) {
        checkOpen()
        val binding = activeConfirmation.get() ?: return
        if (
            binding.snapshot.confirmationId != confirmationId ||
            binding.discoveryEpoch != operationEpoch.get() ||
            (binding.snapshot.requiresHostReplacementWarning &&
                !hostReplacementWarningConfirmed)
        ) {
            return
        }
        if (!activationMutex.tryLock()) {
            return
        }

        try {
            if (
                activeConfirmation.get() !== binding ||
                !isCurrent(binding.discoveryEpoch, binding.context)
            ) {
                invalidateStaleConfirmation(binding)
                return
            }
            if (!activeConfirmation.compareAndSet(binding, null)) {
                return
            }

            val epoch = operationEpoch.incrementAndGet()
            reauthenticatedOwner.set(null)
            mutableState.value = RemoteHostViewState(RemoteHostPhase.ACTIVATING)

            val beginResult = try {
                if (binding.recoveryAttemptId == null) {
                    sessionStore.beginAttempt(
                        binding.context,
                        binding.snapshot.generation,
                    )
                } else {
                    sessionStore.beginRecoveryAttempt(
                        expectedContext = binding.context,
                        previousAttemptId = binding.recoveryAttemptId,
                        expectedGeneration = binding.snapshot.generation,
                    )
                }
            } catch (cancelled: CancellationException) {
                settleCancelledPendingJournal(epoch, binding.context, cancelled)
                throw cancelled
            } catch (exception: Exception) {
                publishIfCurrent(
                    epoch,
                    binding.context,
                    RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                )
                throw exception
            }

            val attemptId = (beginResult as? RemoteHostSessionWriteResult.PendingAttemptStarted)
                ?.attemptId
            if (attemptId == null) {
                publishIfCurrent(
                    epoch,
                    binding.context,
                    RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                )
                return
            }
            if (!isCurrent(epoch, binding.context)) {
                withContext(NonCancellable) {
                    clearOrRestoreKnownNotSent(binding, attemptId, epoch)
                }
                return
            }

            var requestInvocationStarted = false
            var activeCommitted = false
            try {
                val firebaseIdToken = try {
                    identity.firebaseIdToken()
                } catch (expected: IdentityOperationException) {
                    withContext(NonCancellable) {
                        clearOrRestoreKnownNotSent(binding, attemptId, epoch)
                    }
                    publishIfCurrent(
                        epoch,
                        binding.context,
                        RemoteHostViewState(
                            phase = RemoteHostPhase.RECOVERABLE_ERROR,
                            safeFailure = OwnerAccessFailureReason.NOT_AUTHENTICATED,
                        ),
                    )
                    return
                }
                if (!isCurrent(epoch, binding.context)) {
                    withContext(NonCancellable) {
                        clearOrRestoreKnownNotSent(binding, attemptId, epoch)
                    }
                    return
                }

                requestInvocationStarted = true
                val result = ownerAccessClient.activateHost(
                    owner = binding.context.owner,
                    firebaseIdToken = firebaseIdToken,
                    expectedGeneration = binding.snapshot.generation,
                )
                if (!isCurrent(epoch, binding.context)) {
                    val knownNoWrite = (result as? OwnerAccessResult.Failure)
                        ?.activationDisposition
                        ?.let { disposition ->
                            disposition == ActivationDisposition.NOT_SENT ||
                                disposition == ActivationDisposition.KNOWN_NOT_WRITTEN
                        } == true
                    if (knownNoWrite) {
                        withContext(NonCancellable) {
                            sessionStore.clearPendingAttempt(binding.context, attemptId)
                        }
                    }
                    return
                }

                when (result) {
                    is OwnerAccessResult.Failure -> {
                        if (
                            result.activationDisposition == ActivationDisposition.NOT_SENT ||
                            result.activationDisposition == ActivationDisposition.KNOWN_NOT_WRITTEN
                        ) {
                            handleKnownNoWrite(
                                binding = binding,
                                attemptId = attemptId,
                                epoch = epoch,
                                failure = result,
                            )
                        } else {
                            publishIfCurrent(
                                epoch,
                                binding.context,
                                RemoteHostViewState(
                                    phase = RemoteHostPhase.ACTIVATION_UNCERTAIN,
                                    safeFailure = result.reason,
                                    retryAfterSeconds = result.retryAfterSeconds,
                                ),
                            )
                        }
                    }

                    is OwnerAccessResult.Success -> {
                        val activation = result.value
                        if (
                            binding.snapshot.monitorId != null &&
                            activation.monitorId != binding.snapshot.monitorId
                        ) {
                            publishIfCurrent(
                                epoch,
                                binding.context,
                                RemoteHostViewState(
                                    phase = RemoteHostPhase.ACTIVATION_UNCERTAIN,
                                    safeFailure = OwnerAccessFailureReason.INVALID_RESPONSE,
                                ),
                            )
                            return
                        }

                        val commitResult = sessionStore.commitAttempt(
                            expectedContext = binding.context,
                            attemptId = attemptId,
                            activation = activation,
                        )
                        if (commitResult != RemoteHostSessionWriteResult.ActiveSessionCommitted) {
                            publishIfCurrent(
                                epoch,
                                binding.context,
                                RemoteHostViewState(
                                    phase = RemoteHostPhase.ACTIVATION_UNCERTAIN,
                                    safeFailure = OwnerAccessFailureReason.INVALID_RESPONSE,
                                ),
                            )
                            return
                        }

                        activeCommitted = true
                        if (!isCurrent(epoch, binding.context)) {
                            return
                        }
                        validateSavedActivation(binding.context, activation, epoch)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (requestInvocationStarted) {
                    settleCancelledActivation(
                        epoch,
                        binding.context,
                        activeCommitted,
                        cancelled,
                    )
                } else {
                    try {
                        withContext(NonCancellable) {
                            clearOrRestoreKnownNotSent(binding, attemptId, epoch)
                        }
                    } catch (cleanupFailure: Exception) {
                        cancelled.addSuppressed(cleanupFailure)
                        publishIfCurrent(
                            epoch,
                            binding.context,
                            RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                        )
                    }
                }
                throw cancelled
            } catch (exception: Exception) {
                if (requestInvocationStarted && !activeCommitted) {
                    if (
                        mutableState.value.phase !=
                        RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE
                    ) {
                        publishIfCurrent(
                            epoch,
                            binding.context,
                            RemoteHostViewState(RemoteHostPhase.ACTIVATION_UNCERTAIN),
                        )
                    }
                } else if (activeCommitted) {
                    if (
                        mutableState.value.phase !=
                        RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE
                    ) {
                        publishIfCurrent(
                            epoch,
                            binding.context,
                            RemoteHostViewState(
                                phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                                safeFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                                hostValidationFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                            ),
                        )
                    }
                } else {
                    try {
                        withContext(NonCancellable) {
                            clearOrRestoreKnownNotSent(binding, attemptId, epoch)
                        }
                    } catch (cleanupFailure: Exception) {
                        exception.addSuppressed(cleanupFailure)
                    }
                    publishIfCurrent(
                        epoch,
                        binding.context,
                        RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                    )
                }
                throw exception
            }
        } finally {
            activationMutex.unlock()
        }
    }

    suspend fun signOut(): IdentityResult {
        checkOpen()
        val epoch = nextOperation()
        reauthenticatedOwner.set(null)
        identityMutationInProgress.set(true)
        mutableState.value = RemoteHostViewState(RemoteHostPhase.LOCAL_ONLY)

        return try {
            val result = identity.signOut()
            val account = identity.account.value
            lastObservedAccount.set(account)
            if (isEpochCurrent(epoch)) {
                publishAccountNotReady(account)
            }
            result
        } catch (cancelled: CancellationException) {
            if (isEpochCurrent(epoch)) {
                publishAccountNotReady(identity.account.value)
            }
            throw cancelled
        } catch (exception: Exception) {
            if (isEpochCurrent(epoch)) {
                publishAccountNotReady(identity.account.value)
            }
            throw exception
        } finally {
            identityMutationInProgress.set(false)
            lastObservedAccount.set(identity.account.value)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        operationEpoch.incrementAndGet()
        invalidateConfirmation()
        reauthenticatedOwner.set(null)
        mutableState.value = RemoteHostViewState(
            if (identity.account.value == null) {
                RemoteHostPhase.LOCAL_ONLY
            } else {
                RemoteHostPhase.SIGNED_IN_API_NOT_READY
            },
        )
        coordinatorJob.cancel()
    }

    private suspend fun validateSavedActivation(
        context: RemoteHostOwnerContext,
        activation: HostActivation,
        epoch: Long,
    ) {
        if (!isCurrent(epoch, context)) {
            return
        }
        val result = try {
            ownerAccessClient.discoverInstallationAccess(
                accessToken = activation.accessToken,
                expectedContext = activation.expectedHostContext(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(
                    phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                    safeFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                    hostValidationFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                ),
            )
            throw exception
        }
        if (!isCurrent(epoch, context)) {
            return
        }

        when (result) {
            is OwnerAccessResult.Success -> {
                publishIfCurrent(
                    epoch,
                    context,
                    RemoteHostViewState(
                        phase = RemoteHostPhase.ACTIVE_CONFIRMED,
                        verifiedHost = result.value,
                    ),
                )
            }

            is OwnerAccessResult.Failure -> {
                when (result.reason) {
                    OwnerAccessFailureReason.NOT_AUTHENTICATED -> {
                        val clearResult = try {
                            sessionStore.clearActiveSession(activation.expectedHostContext())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (exception: Exception) {
                            publishIfCurrent(
                                epoch,
                                context,
                                RemoteHostViewState(
                                    phase = RemoteHostPhase.RECOVERABLE_ERROR,
                                    safeFailure = result.reason,
                                ),
                            )
                            throw exception
                        }
                        if (!isCurrent(epoch, context)) {
                            return
                        }
                        publishIfCurrent(
                            epoch,
                            context,
                            RemoteHostViewState(
                                phase = if (
                                    clearResult == RemoteHostSessionWriteResult.ActiveSessionCleared
                                ) {
                                    RemoteHostPhase.CREDENTIAL_LOST
                                } else {
                                    RemoteHostPhase.RECOVERABLE_ERROR
                                },
                                safeFailure = result.reason,
                            ),
                        )
                    }

                    OwnerAccessFailureReason.NETWORK_FAILURE,
                    OwnerAccessFailureReason.IDENTITY_VERIFICATION_UNAVAILABLE -> {
                        publishIfCurrent(
                            epoch,
                            context,
                            RemoteHostViewState(
                                phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                                safeFailure = result.reason,
                                hostValidationFailure = result.reason,
                            ),
                        )
                    }

                    else -> {
                        publishIfCurrent(
                            epoch,
                            context,
                            RemoteHostViewState(
                                phase = RemoteHostPhase.RECOVERABLE_ERROR,
                                safeFailure = result.reason,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleKnownNoWrite(
        binding: ConfirmationBinding,
        attemptId: RemoteHostAttemptId,
        epoch: Long,
        failure: OwnerAccessResult.Failure,
    ) {
        val restoreResult = restoreCurrentOrClearStaleKnownNoWrite(
            binding,
            attemptId,
            epoch,
        )
        if (!isCurrent(epoch, binding.context)) {
            return
        }
        if (restoreResult != RemoteHostSessionWriteResult.ActiveSessionCommitted &&
            restoreResult != RemoteHostSessionWriteResult.PendingAttemptCleared
        ) {
            publishIfCurrent(
                epoch,
                binding.context,
                RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
            )
            return
        }

        var verifiedHost: VerifiedHostAccess? = null
        var hostValidationFailure: OwnerAccessFailureReason? = null
        if (restoreResult == RemoteHostSessionWriteResult.ActiveSessionCommitted) {
            val previousActivation = binding.previousActivation ?: return
            validateSavedActivation(binding.context, previousActivation, epoch)
            if (!isCurrent(epoch, binding.context)) {
                return
            }
            verifiedHost = mutableState.value.verifiedHost
            if (mutableState.value.phase == RemoteHostPhase.CREDENTIAL_LOST) {
                return
            }
            if (
                mutableState.value.phase == RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE
            ) {
                hostValidationFailure = mutableState.value.hostValidationFailure
                    ?: mutableState.value.safeFailure
            }
        }

        val phase = when {
            hostValidationFailure != null -> RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE
            failure.reason == OwnerAccessFailureReason.RECENT_AUTHENTICATION_REQUIRED ->
                RemoteHostPhase.REAUTHENTICATION_REQUIRED

            else -> RemoteHostPhase.RECOVERABLE_ERROR
        }
        if (failure.reason == OwnerAccessFailureReason.RECENT_AUTHENTICATION_REQUIRED) {
            reauthenticatedOwner.set(null)
        }
        publishIfCurrent(
            epoch,
            binding.context,
            RemoteHostViewState(
                phase = phase,
                verifiedHost = verifiedHost,
                safeFailure = failure.reason,
                retryAfterSeconds = failure.retryAfterSeconds,
                hostValidationFailure = hostValidationFailure,
            ),
        )
    }

    private suspend fun clearOrRestoreKnownNotSent(
        binding: ConfirmationBinding,
        attemptId: RemoteHostAttemptId,
        epoch: Long,
    ) {
        val result = restoreCurrentOrClearStaleKnownNoWrite(
            binding,
            attemptId,
            epoch,
        )
        if (!isCurrent(epoch, binding.context)) {
            return
        }
        if (
            result == RemoteHostSessionWriteResult.PendingAttemptCleared ||
            result == RemoteHostSessionWriteResult.ActiveSessionCommitted
        ) {
            val previousActivation = binding.previousActivation
            if (result == RemoteHostSessionWriteResult.ActiveSessionCommitted &&
                previousActivation != null
            ) {
                publishIfCurrent(
                    epoch,
                    binding.context,
                    RemoteHostViewState(
                        phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                        safeFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                    ),
                )
            } else {
                publishIfCurrent(
                    epoch,
                    binding.context,
                    RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
                )
            }
        }
    }

    private suspend fun restoreCurrentOrClearStaleKnownNoWrite(
        binding: ConfirmationBinding,
        attemptId: RemoteHostAttemptId,
        epoch: Long,
    ): RemoteHostSessionWriteResult? {
        val callerContext = currentCoroutineContext()
        var cleanupFailure: Exception? = null
        val result = withContext(NonCancellable) {
            if (!isCurrent(epoch, binding.context)) {
                cleanupFailure = captureStaleCleanupFailure {
                    clearStalePendingAttempt(binding.context, attemptId)
                }
                return@withContext null
            }

            val restoreResult = restoreOrClearPending(binding, attemptId)
            if (!isCurrent(epoch, binding.context)) {
                cleanupFailure = captureStaleCleanupFailure {
                    reconcileStaleKnownNoWrite(binding, attemptId, restoreResult)
                }
                return@withContext null
            }

            restoreResult
        }

        try {
            callerContext.ensureActive()
        } catch (cancelled: CancellationException) {
            cleanupFailure?.let { failure ->
                if (failure !== cancelled) {
                    cancelled.addSuppressed(failure)
                }
            }
            throw cancelled
        }

        cleanupFailure?.let { failure -> throw failure }
        return result
    }

    private suspend fun reconcileStaleKnownNoWrite(
        binding: ConfirmationBinding,
        attemptId: RemoteHostAttemptId,
        restoreResult: RemoteHostSessionWriteResult,
    ) {
        when (restoreResult) {
            RemoteHostSessionWriteResult.ActiveSessionCommitted -> {
                val previousActivation = binding.previousActivation
                    ?: throw IllegalStateException(
                        "Stale host restore committed without a previous activation",
                    )
                when (
                    val clearResult = sessionStore.clearActiveSession(
                        previousActivation.expectedHostContext(),
                    )
                ) {
                    RemoteHostSessionWriteResult.ActiveSessionCleared -> Unit
                    is RemoteHostSessionWriteResult.Rejected -> {
                        if (
                            clearResult.problem !=
                            RemoteHostSessionProblem.NO_MATCHING_ACTIVE_SESSION &&
                            clearResult.problem != RemoteHostSessionProblem.SESSION_CONTEXT_MISMATCH
                        ) {
                            throw IllegalStateException(
                                "Unable to clear stale restored host session: ${clearResult.problem}",
                            )
                        }
                    }

                    else -> throw IllegalStateException(
                        "Unable to clear stale restored host session",
                    )
                }
            }

            RemoteHostSessionWriteResult.PendingAttemptCleared -> Unit
            else -> clearStalePendingAttempt(binding.context, attemptId)
        }
    }

    private suspend fun clearStalePendingAttempt(
        context: RemoteHostOwnerContext,
        attemptId: RemoteHostAttemptId,
    ) {
        when (val result = sessionStore.clearPendingAttempt(context, attemptId)) {
            RemoteHostSessionWriteResult.PendingAttemptCleared -> Unit
            is RemoteHostSessionWriteResult.Rejected -> {
                if (result.problem != RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT) {
                    throw IllegalStateException(
                        "Unable to clear stale pending host attempt: ${result.problem}",
                    )
                }
            }

            else -> throw IllegalStateException("Unable to clear stale pending host attempt")
        }
    }

    private suspend fun captureStaleCleanupFailure(
        cleanup: suspend () -> Unit,
    ): Exception? =
        try {
            cleanup()
            null
        } catch (exception: Exception) {
            exception
        }

    private suspend fun restoreOrClearPending(
        binding: ConfirmationBinding,
        attemptId: RemoteHostAttemptId,
    ): RemoteHostSessionWriteResult {
        val previousActivation = binding.previousActivation
        return if (previousActivation != null) {
            sessionStore.commitAttempt(
                expectedContext = binding.context,
                attemptId = attemptId,
                activation = previousActivation,
            )
        } else {
            sessionStore.clearPendingAttempt(binding.context, attemptId)
        }
    }

    private suspend fun loadSession(
        context: RemoteHostOwnerContext,
        epoch: Long,
    ): RemoteHostSessionLoad =
        try {
            sessionStore.load(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            publishIfCurrent(
                epoch,
                context,
                RemoteHostViewState(RemoteHostPhase.RECOVERABLE_ERROR),
            )
            throw exception
        }

    private suspend fun settleCancelledPendingJournal(
        epoch: Long,
        context: RemoteHostOwnerContext,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            if (!isCurrent(epoch, context)) {
                return@withContext
            }
            val loaded = try {
                sessionStore.load(context)
            } catch (settlementFailure: Exception) {
                cancellation.addSuppressed(settlementFailure)
                null
            }
            if (!isCurrent(epoch, context)) {
                return@withContext
            }
            val phase = when (loaded) {
                is RemoteHostSessionLoad.PendingAttempt -> RemoteHostPhase.ACTIVATION_UNCERTAIN
                is RemoteHostSessionLoad.ActiveSession ->
                    RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE

                RemoteHostSessionLoad.Empty,
                is RemoteHostSessionLoad.Unavailable,
                null -> RemoteHostPhase.RECOVERABLE_ERROR
            }
            mutableState.value = RemoteHostViewState(phase)
        }
    }

    private suspend fun settleCancelledActivation(
        epoch: Long,
        context: RemoteHostOwnerContext,
        activeCommitted: Boolean,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            if (!isCurrent(epoch, context)) {
                return@withContext
            }
            if (activeCommitted) {
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                    safeFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                    hostValidationFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                )
                return@withContext
            }

            mutableState.value = RemoteHostViewState(RemoteHostPhase.ACTIVATION_UNCERTAIN)
            val loaded = try {
                sessionStore.load(context)
            } catch (settlementFailure: Exception) {
                cancellation.addSuppressed(settlementFailure)
                null
            }
            if (!isCurrent(epoch, context)) {
                return@withContext
            }
            if (loaded is RemoteHostSessionLoad.ActiveSession) {
                mutableState.value = RemoteHostViewState(
                    phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                    safeFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                    hostValidationFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
                )
            }
        }
    }

    private fun stateForFailure(
        failure: OwnerAccessResult.Failure,
    ): RemoteHostViewState =
        RemoteHostViewState(
            phase = if (
                failure.reason == OwnerAccessFailureReason.RECENT_AUTHENTICATION_REQUIRED
            ) {
                RemoteHostPhase.REAUTHENTICATION_REQUIRED
            } else {
                RemoteHostPhase.RECOVERABLE_ERROR
            },
            safeFailure = failure.reason,
            retryAfterSeconds = failure.retryAfterSeconds,
        )

    private fun currentContext(): RemoteHostOwnerContext? {
        val owner = identity.account.value ?: return null
        val origin = ownerAccessClient.canonicalOrigin ?: return null
        return RemoteHostOwnerContext(owner, origin)
    }

    private fun nextOperation(): Long {
        invalidateConfirmation()
        return operationEpoch.incrementAndGet()
    }

    private fun invalidateConfirmation() {
        activeConfirmation.set(null)
    }

    private fun invalidateStaleConfirmation(binding: ConfirmationBinding) {
        activeConfirmation.compareAndSet(binding, null)
        if (operationEpoch.get() != binding.discoveryEpoch) {
            return
        }

        val currentAccount = identity.account.value
        if (!sameOwner(currentAccount, binding.context.owner)) {
            operationEpoch.incrementAndGet()
            reauthenticatedOwner.set(null)
            publishAccountNotReady(currentAccount)
        } else if (ownerAccessClient.canonicalOrigin != binding.context.origin) {
            mutableState.value = RemoteHostViewState(
                phase = RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                safeFailure = OwnerAccessFailureReason.INVALID_ORIGIN,
            )
        }
    }

    private fun isCurrent(
        epoch: Long,
        context: RemoteHostOwnerContext,
    ): Boolean =
        isEpochCurrent(epoch) &&
            ownerAccessClient.canonicalOrigin == context.origin &&
            sameOwner(identity.account.value, context.owner)

    private fun isEpochCurrent(epoch: Long): Boolean =
        !closed.get() && operationEpoch.get() == epoch

    private fun publishIfCurrent(
        epoch: Long,
        context: RemoteHostOwnerContext,
        nextState: RemoteHostViewState,
    ) {
        if (isCurrent(epoch, context)) {
            mutableState.value = nextState
        }
    }

    private fun publishAccountNotReady(account: OwnerAccount?) {
        mutableState.value = if (account == null) {
            RemoteHostViewState(RemoteHostPhase.LOCAL_ONLY)
        } else {
            RemoteHostViewState(
                phase = RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                safeFailure = if (ownerAccessClient.canonicalOrigin == null) {
                    OwnerAccessFailureReason.NOT_CONFIGURED
                } else {
                    null
                },
            )
        }
    }

    private fun onAccountChanged(account: OwnerAccount?) {
        val previous = lastObservedAccount.getAndSet(account)
        if (sameOwner(previous, account)) {
            return
        }
        invalidateConfirmation()
        reauthenticatedOwner.set(null)
        if (!identityMutationInProgress.get()) {
            operationEpoch.incrementAndGet()
            publishAccountNotReady(account)
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "Remote host coordinator is closed" }
    }

    private fun OwnerAccount.toOwnerKey(): OwnerKey =
        OwnerKey(firebaseProjectId, uid)

    private fun sameOwner(
        first: OwnerAccount?,
        second: OwnerAccount?,
    ): Boolean =
        first?.firebaseProjectId == second?.firebaseProjectId &&
            first?.uid == second?.uid

    private data class OwnerKey(
        val firebaseProjectId: String,
        val uid: String,
    )

    private data class ConfirmationBinding(
        val snapshot: HostActivationConfirmation,
        val context: RemoteHostOwnerContext,
        val discoveryEpoch: Long,
        val recoveryAttemptId: RemoteHostAttemptId?,
        val previousActivation: HostActivation?,
    )
}
