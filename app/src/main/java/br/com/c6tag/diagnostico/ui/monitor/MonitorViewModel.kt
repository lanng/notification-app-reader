package br.com.c6tag.diagnostico.ui.monitor

import androidx.lifecycle.ViewModel
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.ConfirmReference
import br.com.c6tag.diagnostico.monitor.MonitorResult
import br.com.c6tag.diagnostico.monitor.RejectionCode
import br.com.c6tag.diagnostico.monitor.ChangeSettings
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MonitorViewModel(
    private val coordinator: MonitorCoordinator,
    captureStatus: CaptureStatus,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(MonitorUiState(capture = captureStatus.state.value))
    val uiState = mutableUiState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()

    init {
        scope.launch { captureStatus.state.collect { capture -> mutableUiState.update { it.copy(capture = capture) } } }
        scope.launch {
            // Reads never mutate the financial revision: no refresh/observer feedback loop.
            coordinator.revisions.collect {
                operations.withLock {
                    try {
                        load()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableUiState.update { it.copy(isLoading = false, error = "Não foi possível carregar o acompanhamento.") }
                    }
                }
            }
        }
    }

    fun refresh() = command { coordinator.refresh() }

    fun updatePlatform(authorization: AuthorizationStatus, notices: NoticeAccess) {
        mutableUiState.update { it.copy(authorization = authorization, notices = notices) }
    }

    fun reportPlatformFailure() {
        mutableUiState.update { it.copy(error = "Não foi possível abrir as configurações do Android.") }
    }

    fun beginReference() = command {
        val result = coordinator.beginReference()
        if (result.rejection == null) {
            result.session?.let { session ->
                mutableUiState.update { state ->
                    state.copy(referenceDraft = BalanceDraft(
                        session = session,
                        balance = result.overview.balanceCents?.let(::brlEditable) ?: "",
                        limit = brlEditable(result.overview.settings.limitCents),
                    ))
                }
            }
        }
        result
    }

    fun updateBalance(value: String) = edit { it.copy(balance = value) }
    fun updateLimit(value: String) = edit { it.copy(limit = value) }

    fun reviewReference() {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { state ->
            val draft = state.referenceDraft ?: return@update state
            if (draft.sessionUnavailable) return@update state
            val invalid = parseBrlInput(draft.balance) == null || (parseBrlInput(draft.limit)?.let { it < 0L } != false)
            state.copy(referenceDraft = draft.copy(invalidInput = invalid, confirmationVisible = !invalid))
        }
    }

    fun dismissReferenceConfirmation() {
        if (!mutableUiState.value.isBusy) mutableUiState.update {
            it.copy(referenceDraft = it.referenceDraft?.copy(confirmationVisible = false))
        }
    }

    fun confirmReference() {
        val draft = mutableUiState.value.referenceDraft ?: return
        if (!draft.confirmationVisible || draft.sessionUnavailable) return
        val balance = parseBrlInput(draft.balance) ?: return
        val limit = parseBrlInput(draft.limit)?.takeIf { it >= 0L } ?: return
        command {
            val result = coordinator.confirmReference(ConfirmReference(
                draft.session.id, draft.session.baseReferenceId, balance, limit, 0L,
                // Concurrent observations stay individually pending, never silently covered.
                explicitlyIncludedObservationIds = emptySet(),
            ))
            mutableUiState.update { state ->
                state.copy(referenceDraft = if (result.rejection == null) null else
                    state.referenceDraft?.copy(confirmationVisible = false, sessionUnavailable = true))
            }
            result
        }
    }

    fun cancelReference() {
        val draft = mutableUiState.value.referenceDraft ?: return
        command {
            val current = coordinator.overview()
            val cancellation = if (current.referenceSession?.id == draft.session.id) {
                coordinator.cancelReference(draft.session.id)
            } else MonitorResult(current)
            // Expiry is persisted by the same command before cancellation is evaluated.
            // Its session is already closed; dismissing that form is a successful outcome.
            val result = if (cancellation.rejection == RejectionCode.SESSION_EXPIRED &&
                cancellation.overview.referenceSession?.id != draft.session.id) {
                cancellation.copy(rejection = null)
            } else cancellation
            if (result.rejection == null) mutableUiState.update { it.copy(referenceDraft = null) }
            result
        }
    }

    fun acknowledge(cycleId: String) = command { coordinator.acknowledge(cycleId) }
    fun resume(cycleId: String) = command { coordinator.resume(cycleId) }

    fun changeTheme(theme: ThemePreference) = command {
        val current = coordinator.overview()
        coordinator.changeSettings(ChangeSettings(current.reference?.id, current.settings.copy(theme = theme), 0L))
    }

    private fun edit(change: (BalanceDraft) -> BalanceDraft) {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { state ->
            state.copy(referenceDraft = state.referenceDraft?.let(change)?.copy(
                confirmationVisible = false, invalidInput = false,
            ))
        }
    }

    private fun command(operation: suspend () -> MonitorResult) {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { it.copy(isBusy = true, error = null) }
        scope.launch {
            operations.withLock {
                try {
                    val result = operation()
                    load()
                    if (result.rejection != null) mutableUiState.update {
                        it.copy(error = "A ação não foi aplicada. Confira o estado atual e tente novamente.")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Safe static message only: no exception or notification content.
                    mutableUiState.update { it.copy(isLoading = false, error = "Não foi possível concluir a ação. Tente novamente.") }
                } finally {
                    mutableUiState.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    private suspend fun load() {
        val before = coordinator.overview()
        val recent = coordinator.history(limit = 3)
        val overview = coordinator.overview()
        mutableUiState.update { state ->
            state.copy(
                overview = overview,
                recent = if (before.reference?.id == overview.reference?.id) recent else emptyList(),
                isLoading = false,
                referenceDraft = state.referenceDraft?.let { draft ->
                    if (overview.referenceSession?.id == draft.session.id) draft else
                        draft.copy(sessionUnavailable = true, confirmationVisible = false)
                },
            )
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
