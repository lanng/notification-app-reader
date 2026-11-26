package br.com.c6tag.diagnostico.remote.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    val roleMode: RemoteAccessRoleMode = RemoteAccessRoleMode.UNASSIGNED,
    val support: SupportPairingViewState = SupportPairingViewState(),
    val hostSupports: HostSupportViewState = HostSupportViewState(),
    val supportActionInProgress: Boolean = false,
    val hostSupportActionInProgress: Boolean = false,
) {
    val canEnterSupportFlow: Boolean
        get() = roleMode == RemoteAccessRoleMode.UNASSIGNED ||
            roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT

    val canManageSupports: Boolean
        get() = roleMode == RemoteAccessRoleMode.HOST_PRESENT &&
            host.phase == RemoteHostPhase.ACTIVE_CONFIRMED &&
            host.verifiedHost != null

    val canActivateHost: Boolean
        get() = roleMode == RemoteAccessRoleMode.UNASSIGNED ||
            roleMode == RemoteAccessRoleMode.HOST_PRESENT
}

/**
 * Owns only the remote surface's safe presentation state and coordinators.
 * Credential Manager requests and clipboard effects remain in the Activity that
 * owns the current UI.
 */
internal class RemoteAccessViewModel(
    private val identity: OwnerIdentity,
    private val ownerAccessClient: OwnerAccessClient,
    private val sessionStore: RemoteHostSessionStore,
    private val pairingAccessClient: PairingAccessClient,
    private val supportSessionStore: RemoteSupportSessionStore,
    private val roleGuard: RemoteRoleGuard,
    private val configuredPairingOrigin: () -> String? = { ownerAccessClient.canonicalOrigin },
    private val baseCoroutineContext: CoroutineContext? = null,
) : ViewModel() {
    private val lifecycleLock = Any()
    private val mutableUiState = MutableStateFlow(RemoteAccessUiState())
    val uiState = mutableUiState.asStateFlow()

    private var leaseCounter = 0L
    private var activeLease: Long? = null
    private var coordinatorGeneration = 0L
    private var surfaceScope: CoroutineScope? = null
    private var surfaceJob: Job? = null
    private var hostCoordinator: RemoteHostCoordinator? = null
    private var supportCoordinator: SupportPairingCoordinator? = null
    private var hostSupportCoordinator: HostSupportCoordinator? = null
    private var hostActionJob: Job? = null
    private var supportActionJob: Job? = null
    private var hostSupportActionJob: Job? = null
    private var attachSupportInitInFlight = false

    fun attachSurface(): Long {
        val lease: Long
        val generation: Long
        val scope: CoroutineScope
        val newHost: RemoteHostCoordinator
        val newSupport: SupportPairingCoordinator
        val newHostSupport: HostSupportCoordinator
        val previousRelease: SurfaceBundle?

        synchronized(lifecycleLock) {
            activeLease = null
            coordinatorGeneration++
            previousRelease = detachCurrentSurfaceLocked()

            lease = ++leaseCounter
            generation = coordinatorGeneration
            activeLease = lease

            val parent = parentContext()
            val job = SupervisorJob(parent[Job])
            scope = CoroutineScope(parent + job)
            newHost = newHostCoordinator(scope)
            newSupport = newSupportCoordinator(scope)
            newHostSupport = newHostSupportCoordinator(newHost, scope)

            surfaceJob = job
            surfaceScope = scope
            hostCoordinator = newHost
            supportCoordinator = newSupport
            hostSupportCoordinator = newHostSupport

            mutableUiState.value = RemoteAccessUiState(
                account = identity.account.value,
                host = newHost.state.value,
                configuredOrigin = ownerAccessClient.canonicalOrigin,
                roleMode = RemoteAccessRoleMode.CHECKING,
                support = newSupport.state.value,
                hostSupports = newHostSupport.state.value,
            )
        }

        previousRelease?.release()

        observeSurface(lease, generation, scope, newHost, newSupport, newHostSupport)
        scope.launch {
            initializeAttachedSurface(lease, generation, newHost, newSupport)
        }
        return lease
    }

    fun detachSurface(lease: Long) {
        val previousRelease = synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return
            }
            activeLease = null
            leaseCounter++
            coordinatorGeneration++
            val bundle = detachCurrentSurfaceLocked()
            mutableUiState.value = RemoteAccessUiState(
                configuredOrigin = ownerAccessClient.canonicalOrigin,
            )
            bundle
        }
        previousRelease?.release()
    }

    fun googleCredentialRequestStarted(lease: Long) {
        updateUiStateIfCurrentLease(lease) {
            it.copy(googleRequestInProgress = true, notice = null)
        }
    }

    fun googleCredentialRequestFailed(lease: Long) {
        updateUiStateIfCurrentLease(lease) {
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
            updateUiStateIfCurrentLease(lease) {
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

        launchHostAction(lease = lease, refreshRoleAfter = false) { generation ->
            val identityResult = if (reauthenticate) {
                reauthenticate(token)
            } else {
                signIn(token)
            }
            updateUiStateIfCurrent(lease, generation) {
                it.copy(
                    notice = identityNotice(identityResult, reauthenticate),
                    googleRequestInProgress = false,
                )
            }
        }
    }

    fun discoverOwnerMonitor(lease: Long) {
        val originMissing = synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return
            }
            if (mutableUiState.value.configuredOrigin == null) {
                mutableUiState.update { it.copy(notice = RemoteAccessNotice.ACTION_FAILED) }
                true
            } else {
                false
            }
        }
        if (originMissing) {
            return
        }
        launchHostAction(lease = lease, refreshRoleAfter = false) {
            discoverOwnerMonitor()
        }
    }

    fun confirmHostActivation(
        lease: Long,
        confirmationId: String,
        hostReplacementWarningConfirmed: Boolean,
    ) {
        val blocked = synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return
            }
            val currentMode = mutableUiState.value.roleMode
            if (
                currentMode != RemoteAccessRoleMode.UNASSIGNED &&
                currentMode != RemoteAccessRoleMode.HOST_PRESENT
            ) {
                mutableUiState.update { it.copy(notice = RemoteAccessNotice.ACTION_FAILED) }
                true
            } else {
                false
            }
        }
        if (blocked) {
            return
        }
        launchHostAction(lease = lease, refreshRoleAfter = true) {
            confirmHostActivation(
                confirmationId = confirmationId,
                hostReplacementWarningConfirmed = hostReplacementWarningConfirmed,
            )
        }
    }

    /**
     * Closing the confirmation replaces all three coordinators without loading
     * or validating saved access; any old owner confirmation, host-support
     * decision confirmation, or emitted invitation copy becomes unusable.
     */
    fun cancelConfirmation(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        replaceCoordinators(lease)
    }

    fun signOut(lease: Long) {
        if (!isCurrent(lease)) {
            return
        }
        launchHostAction(lease = lease, refreshRoleAfter = true) { generation ->
            val result = signOut()
            if (result.outcome != IdentityOutcome.SIGNED_OUT) {
                updateUiStateIfCurrent(lease, generation) {
                    it.copy(notice = RemoteAccessNotice.SIGN_OUT_FAILED)
                }
            }
        }
    }

    fun openSupportPairing(lease: Long) {
        val shouldSkip = synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                true
            } else {
                val current = mutableUiState.value
                !isRoleAllowedForSupport(current.roleMode) ||
                    attachSupportInitInFlight ||
                    current.support.phase == SupportPairingPhase.CREDENTIAL_LOST ||
                    supportCoordinator?.state?.value?.phase == SupportPairingPhase.CREDENTIAL_LOST
            }
        }
        if (shouldSkip) {
            return
        }
        launchSupportAction(lease = lease, refreshRoleAfter = true) {
            initialize()
        }
    }

    fun submitSupportInvitation(
        lease: Long,
        invitationSecret: String,
        installationLabel: String = "",
        replaceUncertainAttempt: Boolean = false,
    ) {
        if (!isRoleAllowedForSupport(lease, setFailureNoticeIfBlocked = true)) {
            return
        }
        launchSupportAction(lease = lease, refreshRoleAfter = true) {
            submitInvitation(
                invitationSecret = invitationSecret,
                installationLabel = installationLabel,
                replaceUncertainAttempt = replaceUncertainAttempt,
            )
        }
    }

    fun refreshSupportStatus(lease: Long) {
        if (!isRoleAllowedForSupport(lease, setFailureNoticeIfBlocked = false)) {
            return
        }
        launchSupportAction(lease = lease, refreshRoleAfter = true) {
            refreshStatus()
        }
    }

    fun openHostSupports(lease: Long) {
        refreshHostSupports(lease)
    }

    fun refreshHostSupports(lease: Long) {
        if (!isRoleAllowedForHostSupports(lease, setFailureNoticeIfBlocked = false)) {
            return
        }
        launchHostSupportAction(lease = lease, refreshRoleAfter = false) {
            refresh()
        }
    }

    fun revalidateHostSupportsSession(lease: Long) {
        if (!isRoleAllowedForHostSupports(lease, setFailureNoticeIfBlocked = false)) {
            return
        }
        launchHostSupportAction(lease = lease, refreshRoleAfter = true) {
            revalidateSession()
        }
    }

    fun issueSupportInvitation(lease: Long) {
        if (!isRoleAllowedForHostSupports(lease, setFailureNoticeIfBlocked = true)) {
            return
        }
        launchHostSupportAction(lease = lease, refreshRoleAfter = false) {
            issueInvitation()
        }
    }

    fun cancelSupportInvitation(
        lease: Long,
        invitationId: String,
    ) {
        if (!isRoleAllowedForHostSupports(lease, setFailureNoticeIfBlocked = true)) {
            return
        }
        launchHostSupportAction(lease = lease, refreshRoleAfter = false) {
            cancelInvitation(invitationId)
        }
    }

    fun requestHostSupportDecision(
        lease: Long,
        action: HostSupportDecisionAction,
        targetId: String,
    ): HostSupportDecisionConfirmation? {
        val currentCoordinator = synchronized(lifecycleLock) {
            if (
                !isCurrentLocked(lease) ||
                !mutableUiState.value.canManageSupports
            ) {
                return null
            }
            hostSupportCoordinator ?: return null
        }
        return currentCoordinator.requestDecision(action, targetId)
    }

    fun cancelHostSupportDecision(lease: Long) {
        val currentCoordinator = synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return
            }
            hostSupportCoordinator ?: return
        }
        currentCoordinator.cancelDecision()
    }

    fun confirmHostSupportDecision(
        lease: Long,
        decisionId: String,
    ) {
        if (!isRoleAllowedForHostSupports(lease, setFailureNoticeIfBlocked = true)) {
            return
        }
        launchHostSupportAction(lease = lease, refreshRoleAfter = false) {
            confirmDecision(decisionId)
        }
    }

    /**
     * Returns an emitted invitation secret only for an explicit copy gesture
     * on the active surface lease; never exposes PATs or copies secrets into UI state.
     */
    suspend fun invitationForExplicitCopy(
        lease: Long,
        invitationId: String,
    ): SecretValue? {
        val generation: Long
        val currentCoordinator: HostSupportCoordinator
        synchronized(lifecycleLock) {
            if (
                !isCurrentLocked(lease) ||
                mutableUiState.value.roleMode != RemoteAccessRoleMode.HOST_PRESENT
            ) {
                return null
            }
            generation = coordinatorGeneration
            currentCoordinator = hostSupportCoordinator ?: return null
        }
        val secret = currentCoordinator.invitationForExplicitCopy(invitationId)
        return if (isCurrent(lease, generation)) secret else null
    }

    override fun onCleared() {
        val previousRelease = synchronized(lifecycleLock) {
            activeLease = null
            leaseCounter++
            coordinatorGeneration++
            detachCurrentSurfaceLocked()
        }
        previousRelease?.release()
        super.onCleared()
    }

    private fun observeSurface(
        lease: Long,
        generation: Long,
        scope: CoroutineScope,
        observedHost: RemoteHostCoordinator,
        observedSupport: SupportPairingCoordinator,
        observedHostSupport: HostSupportCoordinator,
    ) {
        scope.launch {
            identity.account.collect { account ->
                updateUiStateIfCurrent(lease, generation) { it.copy(account = account) }
            }
        }
        scope.launch {
            observedHost.state.collect { host ->
                val hostNoLongerConfirmed =
                    host.phase != RemoteHostPhase.ACTIVE_CONFIRMED || host.verifiedHost == null
                updateUiStateIfCurrent(lease, generation) { current ->
                    current.copy(
                        host = host,
                        hostSupports = if (hostNoLongerConfirmed) {
                            current.hostSupports.copy(pendingConfirmation = null)
                        } else {
                            current.hostSupports
                        },
                    )
                }
                if (hostNoLongerConfirmed && isCurrent(lease, generation)) {
                    observedHostSupport.cancelDecision()
                }
            }
        }
        scope.launch {
            observedSupport.state.collect { support ->
                updateUiStateIfCurrent(lease, generation) { it.copy(support = support) }
            }
        }
        scope.launch {
            observedHostSupport.state.collect { hostSupports ->
                val shouldCancelDecision = synchronized(lifecycleLock) {
                    if (!isCurrentLocked(lease, generation)) {
                        false
                    } else if (
                        !mutableUiState.value.canManageSupports &&
                        hostSupports.pendingConfirmation != null
                    ) {
                        mutableUiState.update { current ->
                            current.copy(
                                hostSupports = hostSupports.copy(pendingConfirmation = null),
                            )
                        }
                        true
                    } else {
                        mutableUiState.update { current ->
                            current.copy(hostSupports = hostSupports)
                        }
                        false
                    }
                }
                if (shouldCancelDecision) {
                    observedHostSupport.cancelDecision()
                }
            }
        }
    }

    private suspend fun initializeAttachedSurface(
        lease: Long,
        generation: Long,
        targetHost: RemoteHostCoordinator,
        targetSupport: SupportPairingCoordinator,
    ) {
        try {
            val mode = resolveRoleMode()
            val willInitializeSupport = mode == RemoteAccessRoleMode.SUPPORT_PRESENT ||
                mode == RemoteAccessRoleMode.UNASSIGNED
            var markedSupportInitInFlight = false
            synchronized(lifecycleLock) {
                if (!isCurrentLocked(lease, generation)) {
                    return
                }
                if (willInitializeSupport) {
                    attachSupportInitInFlight = true
                    markedSupportInitInFlight = true
                }
                mutableUiState.update { it.copy(roleMode = mode) }
            }

            try {
                when (mode) {
                    RemoteAccessRoleMode.STORAGE_UNAVAILABLE,
                    RemoteAccessRoleMode.DUAL_ROLE_CONFLICT,
                    RemoteAccessRoleMode.CHECKING -> {
                        // Fail closed: block mutations on both sides without deleting either record
                        // and without initializing host or support sessions.
                    }

                    RemoteAccessRoleMode.SUPPORT_PRESENT -> {
                        targetSupport.initialize()
                        refreshRoleModeIfCurrent(lease, generation)
                    }

                    RemoteAccessRoleMode.HOST_PRESENT -> {
                        targetHost.initialize()
                        refreshRoleModeIfCurrent(lease, generation)
                    }

                    RemoteAccessRoleMode.UNASSIGNED -> {
                        targetHost.initialize()
                        if (!isCurrent(lease, generation)) {
                            return
                        }
                        targetSupport.initialize()
                        refreshRoleModeIfCurrent(lease, generation)
                    }
                }
            } finally {
                if (markedSupportInitInFlight) {
                    synchronized(lifecycleLock) {
                        if (isCurrentLocked(lease, generation)) {
                            attachSupportInitInFlight = false
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            updateUiStateIfCurrent(lease, generation) { current ->
                current.copy(
                    roleMode = if (current.roleMode == RemoteAccessRoleMode.CHECKING) {
                        RemoteAccessRoleMode.STORAGE_UNAVAILABLE
                    } else {
                        current.roleMode
                    },
                    notice = RemoteAccessNotice.ACTION_FAILED,
                )
            }
            throw exception
        }
    }

    private suspend fun resolveRoleMode(): RemoteAccessRoleMode {
        val hostPresence = sessionStore.presence()
        val supportPresence = supportSessionStore.presence()
        return classifyRoleMode(hostPresence, supportPresence)
    }

    private suspend fun refreshRoleModeIfCurrent(
        lease: Long,
        generation: Long,
    ) {
        if (!isCurrent(lease, generation)) {
            return
        }
        val mode = resolveRoleMode()
        updateUiStateIfCurrent(lease, generation) { it.copy(roleMode = mode) }
    }

    private fun classifyRoleMode(
        hostPresence: RemoteSessionPresence,
        supportPresence: RemoteSessionPresence,
    ): RemoteAccessRoleMode =
        when {
            hostPresence is RemoteSessionPresence.Unavailable ||
                supportPresence is RemoteSessionPresence.Unavailable ->
                RemoteAccessRoleMode.STORAGE_UNAVAILABLE

            hostPresence == RemoteSessionPresence.Present &&
                supportPresence == RemoteSessionPresence.Present ->
                RemoteAccessRoleMode.DUAL_ROLE_CONFLICT

            supportPresence == RemoteSessionPresence.Present ->
                RemoteAccessRoleMode.SUPPORT_PRESENT

            hostPresence == RemoteSessionPresence.Present ->
                RemoteAccessRoleMode.HOST_PRESENT

            else -> RemoteAccessRoleMode.UNASSIGNED
        }

    private fun isRoleAllowedForSupport(mode: RemoteAccessRoleMode): Boolean =
        mode == RemoteAccessRoleMode.UNASSIGNED ||
            mode == RemoteAccessRoleMode.SUPPORT_PRESENT

    private fun isRoleAllowedForSupport(
        lease: Long,
        setFailureNoticeIfBlocked: Boolean,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return false
            }
            val allowed = isRoleAllowedForSupport(mutableUiState.value.roleMode)
            if (!allowed && setFailureNoticeIfBlocked) {
                mutableUiState.update { it.copy(notice = RemoteAccessNotice.ACTION_FAILED) }
            }
            allowed
        }

    private fun isRoleAllowedForHostSupports(
        lease: Long,
        setFailureNoticeIfBlocked: Boolean,
    ): Boolean =
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return false
            }
            val allowed = mutableUiState.value.roleMode == RemoteAccessRoleMode.HOST_PRESENT
            if (!allowed && setFailureNoticeIfBlocked) {
                mutableUiState.update { it.copy(notice = RemoteAccessNotice.ACTION_FAILED) }
            }
            allowed
        }

    private fun launchHostAction(
        lease: Long,
        refreshRoleAfter: Boolean,
        action: suspend RemoteHostCoordinator.(generation: Long) -> Unit,
    ) {
        val launched: Job
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease) || hostActionJob?.isActive == true) {
                return
            }
            val scope = surfaceScope ?: return
            val currentCoordinator = hostCoordinator ?: return
            val generation = coordinatorGeneration
            mutableUiState.update {
                it.copy(actionInProgress = true, notice = null)
            }
            launched = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (isCurrent(lease, generation)) {
                        action(currentCoordinator, generation)
                    }
                    if (refreshRoleAfter) {
                        refreshRoleModeIfCurrent(lease, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    if (refreshRoleAfter) {
                        refreshRoleModeOnFailure(lease, generation)
                    }
                    updateUiStateIfCurrent(lease, generation) {
                        it.copy(
                            notice = RemoteAccessNotice.ACTION_FAILED,
                            googleRequestInProgress = false,
                        )
                    }
                    throw exception
                } finally {
                    val currentJob = coroutineContext[Job]
                    synchronized(lifecycleLock) {
                        if (hostActionJob === currentJob) {
                            hostActionJob = null
                        }
                        if (isCurrentLocked(lease, generation)) {
                            mutableUiState.update { it.copy(actionInProgress = false) }
                        }
                    }
                }
            }
            hostActionJob = launched
        }
        launched.start()
    }

    private fun launchSupportAction(
        lease: Long,
        refreshRoleAfter: Boolean,
        action: suspend SupportPairingCoordinator.() -> Unit,
    ) {
        val launched: Job
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease) || supportActionJob?.isActive == true) {
                return
            }
            val scope = surfaceScope ?: return
            val currentCoordinator = supportCoordinator ?: return
            val generation = coordinatorGeneration
            mutableUiState.update {
                it.copy(supportActionInProgress = true, notice = null)
            }
            launched = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (isCurrent(lease, generation)) {
                        action(currentCoordinator)
                    }
                    if (refreshRoleAfter) {
                        refreshRoleModeIfCurrent(lease, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    if (refreshRoleAfter) {
                        refreshRoleModeOnFailure(lease, generation)
                    }
                    updateUiStateIfCurrent(lease, generation) {
                        it.copy(notice = RemoteAccessNotice.ACTION_FAILED)
                    }
                    throw exception
                } finally {
                    val currentJob = coroutineContext[Job]
                    synchronized(lifecycleLock) {
                        if (supportActionJob === currentJob) {
                            supportActionJob = null
                        }
                        if (isCurrentLocked(lease, generation)) {
                            mutableUiState.update { it.copy(supportActionInProgress = false) }
                        }
                    }
                }
            }
            supportActionJob = launched
        }
        launched.start()
    }

    private fun launchHostSupportAction(
        lease: Long,
        refreshRoleAfter: Boolean,
        action: suspend HostSupportCoordinator.() -> Unit,
    ) {
        val launched: Job
        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease) || hostSupportActionJob?.isActive == true) {
                return
            }
            val scope = surfaceScope ?: return
            val currentCoordinator = hostSupportCoordinator ?: return
            val generation = coordinatorGeneration
            mutableUiState.update {
                it.copy(hostSupportActionInProgress = true, notice = null)
            }
            launched = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (isCurrent(lease, generation)) {
                        action(currentCoordinator)
                    }
                    if (refreshRoleAfter) {
                        refreshRoleModeIfCurrent(lease, generation)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    if (refreshRoleAfter) {
                        refreshRoleModeOnFailure(lease, generation)
                    }
                    updateUiStateIfCurrent(lease, generation) {
                        it.copy(notice = RemoteAccessNotice.ACTION_FAILED)
                    }
                    throw exception
                } finally {
                    val currentJob = coroutineContext[Job]
                    synchronized(lifecycleLock) {
                        if (hostSupportActionJob === currentJob) {
                            hostSupportActionJob = null
                        }
                        if (isCurrentLocked(lease, generation)) {
                            mutableUiState.update { it.copy(hostSupportActionInProgress = false) }
                        }
                    }
                }
            }
            hostSupportActionJob = launched
        }
        launched.start()
    }

    private suspend fun refreshRoleModeOnFailure(
        lease: Long,
        generation: Long,
    ) {
        try {
            refreshRoleModeIfCurrent(lease, generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            updateUiStateIfCurrent(lease, generation) {
                it.copy(roleMode = RemoteAccessRoleMode.STORAGE_UNAVAILABLE)
            }
        }
    }

    private fun replaceCoordinators(lease: Long) {
        val generation: Long
        val scope: CoroutineScope
        val newHost: RemoteHostCoordinator
        val newSupport: SupportPairingCoordinator
        val newHostSupport: HostSupportCoordinator
        val previousRelease: SurfaceBundle?

        synchronized(lifecycleLock) {
            if (!isCurrentLocked(lease)) {
                return
            }
            coordinatorGeneration++
            generation = coordinatorGeneration
            previousRelease = detachCurrentSurfaceLocked()

            val parent = parentContext()
            val job = SupervisorJob(parent[Job])
            scope = CoroutineScope(parent + job)
            newHost = newHostCoordinator(scope)
            newSupport = newSupportCoordinator(scope)
            newHostSupport = newHostSupportCoordinator(newHost, scope)

            surfaceJob = job
            surfaceScope = scope
            hostCoordinator = newHost
            supportCoordinator = newSupport
            hostSupportCoordinator = newHostSupport

            mutableUiState.value = RemoteAccessUiState(
                account = identity.account.value,
                host = newHost.state.value,
                configuredOrigin = ownerAccessClient.canonicalOrigin,
                roleMode = RemoteAccessRoleMode.CHECKING,
                support = newSupport.state.value,
                hostSupports = newHostSupport.state.value,
            )
        }

        previousRelease?.release()

        observeSurface(lease, generation, scope, newHost, newSupport, newHostSupport)
        scope.launch {
            try {
                refreshRoleModeIfCurrent(lease, generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                updateUiStateIfCurrent(lease, generation) {
                    it.copy(
                        roleMode = RemoteAccessRoleMode.STORAGE_UNAVAILABLE,
                        notice = RemoteAccessNotice.ACTION_FAILED,
                    )
                }
                throw exception
            }
        }
    }

    private fun detachCurrentSurfaceLocked(): SurfaceBundle? {
        val bundle = if (
            surfaceJob != null ||
            surfaceScope != null ||
            hostCoordinator != null ||
            supportCoordinator != null ||
            hostSupportCoordinator != null
        ) {
            SurfaceBundle(
                surfaceJob = surfaceJob,
                surfaceScope = surfaceScope,
                hostCoordinator = hostCoordinator,
                supportCoordinator = supportCoordinator,
                hostSupportCoordinator = hostSupportCoordinator,
            )
        } else {
            null
        }
        hostActionJob = null
        supportActionJob = null
        hostSupportActionJob = null
        attachSupportInitInFlight = false
        surfaceJob = null
        surfaceScope = null
        hostCoordinator = null
        supportCoordinator = null
        hostSupportCoordinator = null
        return bundle
    }

    private fun parentContext(): CoroutineContext =
        baseCoroutineContext ?: viewModelScope.coroutineContext

    private fun newHostCoordinator(parentScope: CoroutineScope) = RemoteHostCoordinator(
        identity = identity,
        ownerAccessClient = ownerAccessClient,
        sessionStore = sessionStore,
        roleGuard = roleGuard,
        parentScope = parentScope,
    )

    private fun newSupportCoordinator(parentScope: CoroutineScope) = SupportPairingCoordinator(
        pairingAccessClient = pairingAccessClient,
        sessionStore = supportSessionStore,
        roleGuard = roleGuard,
        configuredOrigin = configuredPairingOrigin,
        parentScope = parentScope,
    )

    private fun newHostSupportCoordinator(
        administrationSessionProvider: HostAdministrationSessionProvider,
        parentScope: CoroutineScope,
    ) = HostSupportCoordinator(
        pairingAccessClient = pairingAccessClient,
        administrationSessionProvider = administrationSessionProvider,
        parentScope = parentScope,
    )

    private fun isCurrentLocked(lease: Long): Boolean = activeLease == lease

    private fun isCurrentLocked(lease: Long, generation: Long): Boolean =
        activeLease == lease && coordinatorGeneration == generation

    private fun isCurrent(lease: Long): Boolean =
        synchronized(lifecycleLock) { isCurrentLocked(lease) }

    private fun isCurrent(lease: Long, generation: Long): Boolean =
        synchronized(lifecycleLock) { isCurrentLocked(lease, generation) }

    private fun updateUiStateIfCurrentLease(
        lease: Long,
        transform: (RemoteAccessUiState) -> RemoteAccessUiState,
    ) {
        synchronized(lifecycleLock) {
            if (isCurrentLocked(lease)) {
                mutableUiState.update(transform)
            }
        }
    }

    private fun updateUiStateIfCurrent(
        lease: Long,
        generation: Long,
        transform: (RemoteAccessUiState) -> RemoteAccessUiState,
    ) {
        synchronized(lifecycleLock) {
            if (isCurrentLocked(lease, generation)) {
                mutableUiState.update(transform)
            }
        }
    }

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

    private class SurfaceBundle(
        val surfaceJob: Job?,
        val surfaceScope: CoroutineScope?,
        val hostCoordinator: RemoteHostCoordinator?,
        val supportCoordinator: SupportPairingCoordinator?,
        val hostSupportCoordinator: HostSupportCoordinator?,
    ) {
        fun release() {
            surfaceJob?.cancel()
            surfaceScope?.cancel()
            hostSupportCoordinator?.close()
            supportCoordinator?.close()
            hostCoordinator?.close()
        }
    }
}
