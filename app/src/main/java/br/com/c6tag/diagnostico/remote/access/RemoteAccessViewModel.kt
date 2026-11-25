package br.com.c6tag.diagnostico.remote.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class RemoteAccessNotice {
    GOOGLE_CANCELLED,
    GOOGLE_UNAVAILABLE,
    SIGN_IN_FAILED,
    REAUTHENTICATION_FAILED,
    ACCOUNT_MISMATCH,
    SIGN_OUT_FAILED,
    ACTION_FAILED,
}

internal data class RemoteAccessUiState(
    val account: OwnerAccount? = null,
    val host: RemoteHostViewState = RemoteHostViewState(RemoteHostPhase.LOCAL_ONLY),
    val configuredOrigin: String? = null,
    val notice: RemoteAccessNotice? = null,
    val googleRequestInProgress: Boolean = false,
    val actionInProgress: Boolean = false,
)

/**
 * Owns only the remote surface's safe presentation state and coordinator.
 * Credential Manager requests remain in the Activity that owns the current UI.
 */
internal class RemoteAccessViewModel(
    private val identity: OwnerIdentity,
    private val ownerAccessClient: OwnerAccessClient,
    private val sessionStore: RemoteHostSessionStore,
    private val roleGuard: RemoteRoleGuard,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RemoteAccessUiState())
    val uiState = mutableUiState.asStateFlow()

    private var leaseCounter = 0L
    private var activeLease: Long? = null
    private var coordinatorGeneration = 0L
    private var surfaceScope: CoroutineScope? = null
    private var surfaceJob: Job? = null
    private var coordinator: RemoteHostCoordinator? = null
    private var actionJob: Job? = null

    fun attachSurface(): Long {
        activeLease = null
        coordinatorGeneration++
        releaseCurrentSurface()

        val lease = ++leaseCounter
        val generation = coordinatorGeneration
        activeLease = lease
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        val newCoordinator = newCoordinator(scope)
        surfaceJob = job
        surfaceScope = scope
        coordinator = newCoordinator
        mutableUiState.value = RemoteAccessUiState(
            account = identity.account.value,
            host = newCoordinator.state.value,
            configuredOrigin = ownerAccessClient.canonicalOrigin,
        )
        observeSurface(lease, generation, scope, newCoordinator)
        scope.launch { runCoordinatorAction(lease, generation) { newCoordinator.initialize() } }
        return lease
    }

    fun detachSurface(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        activeLease = null
        leaseCounter++
        releaseCurrentSurface()
        mutableUiState.value = RemoteAccessUiState(
            configuredOrigin = ownerAccessClient.canonicalOrigin,
        )
    }

    fun googleCredentialRequestStarted(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        mutableUiState.update {
            it.copy(googleRequestInProgress = true, notice = null)
        }
    }

    fun googleCredentialRequestFailed(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        mutableUiState.update {
            it.copy(
                googleRequestInProgress = false,
                notice = RemoteAccessNotice.GOOGLE_UNAVAILABLE,
            )
        }
    }

    fun acceptGoogleCredential(
        lease: Long,
        result: GoogleCredentialResult,
        reauthenticate: Boolean,
    ) {
        if (!isCurrent(lease)) {
            return
        }
        val token = result.token
        if (result.outcome != GoogleCredentialOutcome.ACQUIRED || token == null) {
            mutableUiState.update {
                it.copy(
                    googleRequestInProgress = false,
                    notice = when (result.outcome) {
                        GoogleCredentialOutcome.CANCELLED -> RemoteAccessNotice.GOOGLE_CANCELLED
                        GoogleCredentialOutcome.NO_CREDENTIAL,
                        GoogleCredentialOutcome.FAILED,
                        GoogleCredentialOutcome.ACQUIRED -> RemoteAccessNotice.GOOGLE_UNAVAILABLE
                    },
                )
            }
            return
        }

        val generation = coordinatorGeneration
        launchAction(lease) {
            val identityResult = if (reauthenticate) {
                reauthenticate(token)
            } else {
                signIn(token)
            }
            if (isCurrent(lease, generation)) {
                mutableUiState.update {
                    it.copy(
                        notice = identityNotice(identityResult, reauthenticate),
                        googleRequestInProgress = false,
                    )
                }
            }
        }
    }

    fun discoverOwnerMonitor(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        if (mutableUiState.value.configuredOrigin == null) {
            mutableUiState.update { it.copy(notice = RemoteAccessNotice.ACTION_FAILED) }
            return
        }
        launchAction(lease) {
            discoverOwnerMonitor()
        }
    }

    fun confirmHostActivation(
        lease: Long,
        confirmationId: String,
        hostReplacementWarningConfirmed: Boolean,
    ) {
        if (!isCurrent(lease)) {
            return
        }
        launchAction(lease) {
            confirmHostActivation(
                confirmationId = confirmationId,
                hostReplacementWarningConfirmed = hostReplacementWarningConfirmed,
            )
        }
    }

    /**
     * Closing the confirmation replaces the coordinator without loading or
     * validating saved access; any old confirmation becomes unusable.
     */
    fun cancelConfirmation(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        replaceCoordinator(lease)
    }

    fun signOut(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        launchAction(lease) {
            val result = signOut()
            if (isCurrent(lease) && result.outcome != IdentityOutcome.SIGNED_OUT) {
                mutableUiState.update { it.copy(notice = RemoteAccessNotice.SIGN_OUT_FAILED) }
            }
        }
    }

    override fun onCleared() {
        activeLease = null
        leaseCounter++
        releaseCurrentSurface()
        super.onCleared()
    }

    private fun observeSurface(
        lease: Long,
        generation: Long,
        scope: CoroutineScope,
        observedCoordinator: RemoteHostCoordinator,
    ) {
        scope.launch {
            identity.account.collect { account ->
                if (isCurrent(lease, generation)) {
                    mutableUiState.update { it.copy(account = account) }
                }
            }
        }
        scope.launch {
            observedCoordinator.state.collect { host ->
                if (isCurrent(lease, generation)) {
                    mutableUiState.update { it.copy(host = host) }
                }
            }
        }
    }

    private fun launchAction(
        lease: Long,
        action: suspend RemoteHostCoordinator.() -> Unit,
    ) {
        val scope = surfaceScope ?: return
        if (!isCurrent(lease) || actionJob?.isActive == true) {
            return
        }
        val generation = coordinatorGeneration
        val currentCoordinator = coordinator ?: return
        mutableUiState.update {
            it.copy(actionInProgress = true, notice = null)
        }
        actionJob = scope.launch {
            try {
                if (isCurrent(lease, generation)) {
                    action(currentCoordinator)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (isCurrent(lease, generation)) {
                    mutableUiState.update {
                        it.copy(
                            notice = RemoteAccessNotice.ACTION_FAILED,
                            googleRequestInProgress = false,
                        )
                    }
                }
                throw exception
            } finally {
                if (isCurrent(lease, generation)) {
                    mutableUiState.update { it.copy(actionInProgress = false) }
                }
            }
        }
    }

    private suspend fun runCoordinatorAction(
        lease: Long,
        generation: Long,
        action: suspend () -> Unit,
    ) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (isCurrent(lease, generation)) {
                mutableUiState.update {
                    it.copy(notice = RemoteAccessNotice.ACTION_FAILED)
                }
            }
            throw exception
        }
    }

    private fun replaceCoordinator(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        coordinatorGeneration++
        releaseCurrentSurface()
        val generation = coordinatorGeneration
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        val scope = CoroutineScope(viewModelScope.coroutineContext + job)
        val newCoordinator = newCoordinator(scope)
        surfaceJob = job
        surfaceScope = scope
        coordinator = newCoordinator
        mutableUiState.value = RemoteAccessUiState(
            account = identity.account.value,
            host = newCoordinator.state.value,
            configuredOrigin = ownerAccessClient.canonicalOrigin,
        )
        observeSurface(lease, generation, scope, newCoordinator)
    }

    private fun releaseCurrentSurface() {
        actionJob = null
        surfaceJob?.cancel()
        surfaceScope?.cancel()
        coordinator?.close()
        surfaceJob = null
        surfaceScope = null
        coordinator = null
    }

    private fun newCoordinator(parentScope: CoroutineScope) = RemoteHostCoordinator(
        identity = identity,
        ownerAccessClient = ownerAccessClient,
        sessionStore = sessionStore,
        roleGuard = roleGuard,
        parentScope = parentScope,
    )

    private fun isCurrent(lease: Long): Boolean = activeLease == lease

    private fun isCurrent(lease: Long, generation: Long): Boolean =
        activeLease == lease && coordinatorGeneration == generation

    private fun identityNotice(
        result: IdentityResult,
        reauthenticate: Boolean,
    ): RemoteAccessNotice? =
        when (result.outcome) {
            IdentityOutcome.SIGNED_IN_CANDIDATE,
            IdentityOutcome.REAUTHENTICATED -> null

            IdentityOutcome.ACCOUNT_MISMATCH -> RemoteAccessNotice.ACCOUNT_MISMATCH
            IdentityOutcome.UNAVAILABLE -> RemoteAccessNotice.GOOGLE_UNAVAILABLE
            IdentityOutcome.NOT_SIGNED_IN,
            IdentityOutcome.SIGNED_OUT,
            IdentityOutcome.FAILED -> if (reauthenticate) {
                RemoteAccessNotice.REAUTHENTICATION_FAILED
            } else {
                RemoteAccessNotice.SIGN_IN_FAILED
            }
        }
}
