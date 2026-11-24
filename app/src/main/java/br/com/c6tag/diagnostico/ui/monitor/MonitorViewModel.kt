package br.com.c6tag.diagnostico.ui.monitor

import androidx.lifecycle.ViewModel
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.ConfirmReference
import br.com.c6tag.diagnostico.monitor.MonitorResult
import br.com.c6tag.diagnostico.monitor.RejectionCode
import br.com.c6tag.diagnostico.monitor.ChangeSettings
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.MonitorSettings
import br.com.c6tag.diagnostico.monitor.PendingChoice
import br.com.c6tag.diagnostico.monitor.PendingMovement
import br.com.c6tag.diagnostico.monitor.PendingStatus
import br.com.c6tag.diagnostico.monitor.ResolvePending
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.monitor.alerts.AlertSettings
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
    private var historyGeneration = 0L
    private var pendingGeneration = 0L

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

    fun reportCaptureSettingsFailure() {
        mutableUiState.update { it.copy(captureSettingsError = "Não foi possível abrir as configurações de captura.") }
    }

    fun reportNoticeSettingsFailure() {
        mutableUiState.update { it.copy(noticeSettingsError = "Não foi possível abrir as configurações de avisos.") }
    }

    fun clearNoticeSettingsError() {
        mutableUiState.update { it.copy(noticeSettingsError = null) }
    }

    fun clearCaptureSettingsError() {
        mutableUiState.update { it.copy(captureSettingsError = null) }
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

    fun updateBalance(change: BrlMoneyInputChange) = edit {
        if (change.invalid) {
            it.copy(balanceInputError = true)
        } else {
            it.copy(balance = change.canonicalText, balanceInputError = false)
        }
    }

    fun updateLimit(change: BrlMoneyInputChange) = edit {
        if (change.invalid) {
            it.copy(limitInputError = true)
        } else {
            it.copy(limit = change.canonicalText, limitInputError = false)
        }
    }

    fun reviewReference() {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { state ->
            val draft = state.referenceDraft ?: return@update state
            if (draft.sessionUnavailable) return@update state
            val balance = parseBrlInput(draft.balance)
            val limit = parseBrlInput(draft.limit)
            val invalid = draft.balanceInputError || draft.limitInputError ||
                balance == null || (limit?.let { it < 0L } != false)
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
        if (draft.balanceInputError || draft.limitInputError) {
            mutableUiState.update { state ->
                val currentDraft = state.referenceDraft ?: return@update state
                if (currentDraft.session.id != draft.session.id) return@update state
                state.copy(referenceDraft = currentDraft.copy(
                    confirmationVisible = false,
                    invalidInput = true,
                ))
            }
            return
        }
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

    fun selectHistoryFilter(filter: HistoryFilter) {
        if (mutableUiState.value.historyFilter == filter) return
        mutableUiState.update {
            it.copy(
                historyFilter = filter,
                history = emptyList(),
                historyHasMore = false,
                historyError = null,
                financialSelection = null,
            )
        }
        reloadHistory()
    }

    fun openFinancialEntry(id: Long) {
        if (mutableUiState.value.isBusy) return
        val entry = mutableUiState.value.history.firstOrNull { it.id == id } ?: return
        mutableUiState.update { it.copy(financialSelection = entry) }
    }

    fun closeFinancialEntry() {
        mutableUiState.update { it.copy(financialSelection = null) }
    }

    fun retryHistory() = reloadHistory()

    fun loadMoreHistory() {
        val current = mutableUiState.value
        if (current.historyLoading || !current.historyHasMore || current.history.isEmpty()) return
        val generation = historyGeneration
        val filter = current.historyFilter
        val beforeId = current.history.last().id
        mutableUiState.update { it.copy(historyLoading = true, historyError = null) }
        scope.launch {
            operations.withLock { readHistoryPage(generation, filter, beforeId, append = true) }
        }
    }

    fun loadPending() = reloadPending()

    fun retryPending() = reloadPending()

    fun loadMorePending() {
        val current = mutableUiState.value
        if (current.pendingLoading || !current.pendingHasMore || current.pending.isEmpty()) return
        val generation = pendingGeneration
        val beforeId = current.pending.last().id
        mutableUiState.update { it.copy(pendingLoading = true, pendingError = null) }
        scope.launch {
            operations.withLock { readPendingPage(generation, beforeId, append = true) }
        }
    }

    fun openPending(id: Long) {
        if (mutableUiState.value.isBusy) return
        val state = mutableUiState.value
        val movement = state.pending.firstOrNull { it.id == id && it.status == PendingStatus.OPEN } ?: return
        val expectedReferenceId = state.overview.reference?.id
        mutableUiState.update {
            it.copy(pendingSelection = PendingReviewSelection(
                movement = movement,
                expectedReferenceId = expectedReferenceId,
                stale = movement.referenceId != expectedReferenceId,
            ))
        }
    }

    fun closePending() {
        if (!mutableUiState.value.isBusy) mutableUiState.update { it.copy(pendingSelection = null) }
    }

    fun requestPendingDecision(choice: PendingChoice) {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { state ->
            val selection = state.pendingSelection ?: return@update state
            if (selection.stale || state.overview.referenceSession != null ||
                selection.movement.status != PendingStatus.OPEN ||
                (choice == PendingChoice.CONFIRM_NEW && !canConfirmNew(selection.movement)) ||
                (choice == PendingChoice.CONFIRM_NEW && selection.expectedReferenceId == null)
            ) return@update state
            state.copy(pendingSelection = selection.copy(confirmation = choice))
        }
    }

    fun dismissPendingDecision() {
        if (!mutableUiState.value.isBusy) mutableUiState.update {
            it.copy(pendingSelection = it.pendingSelection?.copy(confirmation = null))
        }
    }

    fun confirmPendingDecision() {
        val selection = mutableUiState.value.pendingSelection ?: return
        val choice = selection.confirmation ?: return
        if (mutableUiState.value.isBusy || selection.stale) return
        val referenceId = selection.expectedReferenceId ?: return
        if (choice == PendingChoice.CONFIRM_NEW && !canConfirmNew(selection.movement)) return
        command {
            val result = coordinator.resolvePending(ResolvePending(selection.movement.id, referenceId, choice))
            if (result.rejection == null) {
                mutableUiState.update { it.copy(pendingSelection = null) }
            } else if (result.rejection == RejectionCode.STALE_REFERENCE) {
                mutableUiState.update { state ->
                    state.copy(pendingSelection = state.pendingSelection?.copy(confirmation = null, stale = true))
                }
            } else {
                mutableUiState.update { state ->
                    state.copy(pendingSelection = state.pendingSelection?.copy(confirmation = null))
                }
            }
            result
        }
    }

    fun editSettingsLimit(change: BrlMoneyInputChange) = editSettings {
        if (change.invalid) {
            it.copy(moneyInputError = true)
        } else {
            it.copy(
                limit = change.canonicalText,
                invalid = false,
                moneyInputError = false,
            )
        }
    }

    fun editSettingsRepeat(minutes: Int) {
        if (minutes !in SUPPORTED_REPEAT_MINUTES || mutableUiState.value.isBusy) return
        editSettings { it.copy(repeatMinutes = minutes, invalid = false) }
    }

    fun editSettingsPause(minutes: Int) {
        if (minutes !in SUPPORTED_PAUSE_MINUTES || mutableUiState.value.isBusy) return
        editSettings { it.copy(pauseMinutes = minutes, invalid = false) }
    }

    fun saveSettings() {
        if (mutableUiState.value.isBusy) return
        val draft = mutableUiState.value.settingsDraft ?: settingsDraft(mutableUiState.value.overview.settings)
        if (draft.moneyInputError) return
        val limit = parseBrlInput(draft.limit)
        if (limit == null || limit < 0L || draft.repeatMinutes !in SUPPORTED_REPEAT_MINUTES ||
            draft.pauseMinutes !in SUPPORTED_PAUSE_MINUTES
        ) {
            mutableUiState.update { it.copy(settingsDraft = draft.copy(invalid = true)) }
            return
        }
        command {
            val current = coordinator.overview()
            val result = coordinator.changeSettings(ChangeSettings(
                current.reference?.id,
                current.settings.copy(
                    limitCents = limit,
                    alerts = AlertSettings(draft.repeatMinutes, draft.pauseMinutes),
                ),
                0L,
            ))
            if (result.rejection == null) mutableUiState.update { it.copy(settingsDraft = null) }
            result
        }
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

    private fun editSettings(change: (MonitorSettingsDraft) -> MonitorSettingsDraft) {
        if (mutableUiState.value.isBusy) return
        mutableUiState.update { state ->
            val draft = state.settingsDraft ?: settingsDraft(state.overview.settings)
            state.copy(settingsDraft = change(draft))
        }
    }

    private fun reloadHistory() {
        val generation = ++historyGeneration
        val filter = mutableUiState.value.historyFilter
        mutableUiState.update {
            it.copy(history = emptyList(), historyHasMore = false, historyLoading = true, historyError = null)
        }
        scope.launch {
            operations.withLock { readHistoryPage(generation, filter, beforeId = null, append = false) }
        }
    }

    private fun reloadPending() {
        val generation = ++pendingGeneration
        mutableUiState.update {
            it.copy(pending = emptyList(), pendingHasMore = false, pendingLoading = true, pendingError = null)
        }
        scope.launch {
            operations.withLock { readPendingPage(generation, beforeId = null, append = false) }
        }
    }

    private suspend fun readHistoryPage(
        generation: Long,
        filter: HistoryFilter,
        beforeId: Long?,
        append: Boolean,
    ) {
        try {
            val rows = coordinator.history(beforeId, PAGE_SIZE + 1, filter)
            if (generation != historyGeneration || filter != mutableUiState.value.historyFilter) return
            val hasMore = rows.size > PAGE_SIZE
            val page = rows.take(PAGE_SIZE)
            mutableUiState.update { state ->
                if (generation != historyGeneration || state.historyFilter != filter) state else {
                    val entries = if (append) (state.history + page).distinctBy { it.id }
                        .sortedByDescending { it.id } else page
                    state.copy(history = entries, historyHasMore = hasMore, historyLoading = false, historyError = null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableUiState.update { state ->
                if (generation == historyGeneration && state.historyFilter == filter) {
                    state.copy(historyLoading = false, historyError = "Não foi possível carregar o histórico financeiro.")
                } else state
            }
        }
    }

    private suspend fun readPendingPage(generation: Long, beforeId: Long?, append: Boolean) {
        try {
            val rows = coordinator.pending(beforeId, PAGE_SIZE + 1)
            if (generation != pendingGeneration) return
            val hasMore = rows.size > PAGE_SIZE
            val page = rows.take(PAGE_SIZE)
            mutableUiState.update { state ->
                if (generation != pendingGeneration) state else {
                    val movements = if (append) (state.pending + page).distinctBy { it.id }
                        .sortedByDescending { it.id } else page
                    state.copy(pending = movements, pendingHasMore = hasMore, pendingLoading = false, pendingError = null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableUiState.update { state ->
                if (generation == pendingGeneration) {
                    state.copy(pendingLoading = false, pendingError = "Não foi possível carregar as pendências.")
                } else state
            }
        }
    }

    private suspend fun load() {
        val before = coordinator.overview()
        val recent = try {
            coordinator.history(limit = 3)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val overview = coordinator.overview()
        mutableUiState.update { state ->
            state.copy(
                overview = overview,
                recent = if (recent != null && before.reference?.id == overview.reference?.id) recent
                    else if (before.reference?.id != overview.reference?.id) emptyList() else state.recent,
                isLoading = false,
                error = if (recent == null) "Não foi possível carregar o acompanhamento." else state.error,
                historyLoading = if (recent == null) false else state.historyLoading,
                historyError = if (recent == null) "Não foi possível carregar o histórico financeiro." else state.historyError,
                referenceDraft = state.referenceDraft?.let { draft ->
                    if (overview.referenceSession?.id == draft.session.id) draft else
                        draft.copy(sessionUnavailable = true, confirmationVisible = false)
                },
                pendingSelection = state.pendingSelection?.let { selection ->
                    if (selection.expectedReferenceId == overview.reference?.id) selection else
                        selection.copy(confirmation = null, stale = true)
                },
            )
        }
        reloadHistoryLocked()
        reloadPendingLocked()
    }

    private suspend fun reloadHistoryLocked() {
        val generation = ++historyGeneration
        val filter = mutableUiState.value.historyFilter
        mutableUiState.update {
            it.copy(history = emptyList(), historyHasMore = false, historyLoading = true, historyError = null)
        }
        readHistoryPage(generation, filter, beforeId = null, append = false)
    }

    private suspend fun reloadPendingLocked() {
        val generation = ++pendingGeneration
        mutableUiState.update {
            it.copy(pending = emptyList(), pendingHasMore = false, pendingLoading = true, pendingError = null)
        }
        readPendingPage(generation, beforeId = null, append = false)
    }

    private fun canConfirmNew(movement: PendingMovement): Boolean =
        movement.status == PendingStatus.OPEN && !movement.sourceAlreadyApplied &&
            movement.value.kind != null && (movement.value.amountCents?.let { it >= 0L } == true)

    private fun settingsDraft(settings: MonitorSettings): MonitorSettingsDraft = MonitorSettingsDraft(
        limit = brlEditable(settings.limitCents),
        repeatMinutes = settings.alerts.repeatMinutes,
        pauseMinutes = settings.alerts.pauseMinutes,
    )

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        const val PAGE_SIZE = 20
        val SUPPORTED_REPEAT_MINUTES = setOf(15, 30, 60)
        val SUPPORTED_PAUSE_MINUTES = setOf(15, 30, 60, 120)
    }
}
