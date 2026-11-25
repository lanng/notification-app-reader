package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionLoad
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionRecord
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GuestMonitorUiState(
    val isCheckingRole: Boolean = true,
    val isApprovedGuest: Boolean = false,
    val monitorId: String? = null,
    val snapshot: SharedMonitorSnapshot? = null,
    val history: List<SharedHistoryEntry> = emptyList(),
    val historyFilter: HistoryFilter = HistoryFilter.ALL,
    val historyHasMore: Boolean = false,
    val historyNextBeforeEntryId: Long? = null,
    val historyLoading: Boolean = false,
    val historyError: Boolean = false,
    val localAlertState: GuestLocalAlertState = GuestLocalAlertState(),
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val syncInProgress: Boolean = false,
    val syncError: SharedSyncFailureReason? = null,
    val credentialLost: Boolean = false,
    val lastEvaluatedAtMs: Long = 0L,
)

internal class GuestMonitorRepository(
    private val supportSessionStore: RemoteSupportSessionStore,
    private val syncClient: SharedMonitorSyncClient,
    private val localStateStore: GuestLocalStateStore,
    private val alertNotifier: GuestAlertNotifier,
    private val scheduler: GuestSyncScheduler,
    private val fcmTokenProvider: suspend () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val policy: GuestAlertPolicy = GuestAlertPolicy(),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(
        GuestMonitorUiState(
            isCheckingRole = true,
            snapshot = localStateStore.readCachedSnapshot(),
            history = localStateStore.readCachedHistory(),
            localAlertState = localStateStore.readAlertState(),
            theme = localStateStore.readTheme(),
            lastEvaluatedAtMs = clock(),
        ),
    )
    val state: StateFlow<GuestMonitorUiState> = mutableState.asStateFlow()

    suspend fun refreshRoleOnly(): Boolean = mutex.withLock {
        val approved = loadApprovedSessionLocked()
        if (approved == null) {
            handleNoApprovedSessionLocked(credentialLost = false)
            false
        } else {
            mutableState.update {
                it.copy(
                    isCheckingRole = false,
                    isApprovedGuest = true,
                    monitorId = approved.monitorId,
                    credentialLost = false,
                )
            }
            true
        }
    }

    suspend fun syncNow(explicitFcmToken: String? = null): Boolean = mutex.withLock {
        val approved = loadApprovedSessionLocked()
        if (approved == null) {
            handleNoApprovedSessionLocked(credentialLost = false)
            return@withLock false
        }

        mutableState.update {
            it.copy(
                isCheckingRole = false,
                isApprovedGuest = true,
                monitorId = approved.monitorId,
                syncInProgress = true,
                syncError = null,
                credentialLost = false,
            )
        }

        scheduler.ensurePeriodicSync()

        // Best-effort FCM token registration (never blocks snapshot/history sync).
        registerPushTokenBestEffort(approved, explicitFcmToken)

        val snapshotResult = syncClient.fetchMonitorSnapshot(
            monitorId = approved.monitorId,
            accessToken = approved.accessToken,
        )

        when (snapshotResult) {
            is SharedSyncResult.Failure -> {
                if (snapshotResult.reason == SharedSyncFailureReason.UNAUTHENTICATED) {
                    supportSessionStore.clearExpected(approved)
                    handleNoApprovedSessionLocked(credentialLost = true)
                    return@withLock false
                }
                // Even when offline, re-evaluate cached snapshot so local alerts repeat cleanly.
                val cached = localStateStore.readCachedSnapshot()
                applyPolicyLocked(cached)
                mutableState.update {
                    it.copy(
                        syncInProgress = false,
                        syncError = snapshotResult.reason,
                        lastEvaluatedAtMs = clock(),
                    )
                }
                return@withLock false
            }

            is SharedSyncResult.Success -> {
                val snapshot = snapshotResult.value
                localStateStore.writeCachedSnapshot(snapshot)
                applyPolicyLocked(snapshot)
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        syncInProgress = false,
                        syncError = null,
                        lastEvaluatedAtMs = clock(),
                    )
                }
            }
        }

        loadHistoryLocked(
            approved = approved,
            filter = mutableState.value.historyFilter,
            beforeEntryId = null,
            append = false,
        )
        true
    }

    suspend fun onFcmTokenRefreshed(token: String) {
        if (token.isBlank()) return
        mutex.withLock {
            val approved = loadApprovedSessionLocked() ?: return@withLock
            val result = syncClient.registerPushToken(approved.accessToken, token)
            if (result is SharedSyncResult.Success) {
                localStateStore.writeUploadedFcmToken(token)
            }
        }
    }

    suspend fun selectHistoryFilter(filter: HistoryFilter) = mutex.withLock {
        val approved = loadApprovedSessionLocked() ?: return@withLock
        mutableState.update {
            it.copy(
                historyFilter = filter,
                historyLoading = true,
                historyError = false,
            )
        }
        loadHistoryLocked(
            approved = approved,
            filter = filter,
            beforeEntryId = null,
            append = false,
        )
    }

    suspend fun loadMoreHistory() = mutex.withLock {
        val approved = loadApprovedSessionLocked() ?: return@withLock
        val current = mutableState.value
        val cursor = current.historyNextBeforeEntryId ?: return@withLock
        if (!current.historyHasMore || current.historyLoading) return@withLock
        mutableState.update { it.copy(historyLoading = true, historyError = false) }
        loadHistoryLocked(
            approved = approved,
            filter = current.historyFilter,
            beforeEntryId = cursor,
            append = true,
        )
    }

    suspend fun acknowledgeLocalAlert() = mutex.withLock {
        val snapshot = mutableState.value.snapshot ?: localStateStore.readCachedSnapshot()
        val currentAlert = localStateStore.readAlertState()
        val nowMs = clock()
        val decision = policy.acknowledge(snapshot, currentAlert, nowMs)
        localStateStore.writeAlertState(decision.updatedState)
        if (decision.shouldCancelNotification) {
            alertNotifier.cancel()
        }
        decision.nextCheckAtMs?.let { nextMs ->
            scheduler.scheduleNextCheck((nextMs - nowMs).coerceAtLeast(15_000L))
        }
        mutableState.update {
            it.copy(
                localAlertState = decision.updatedState,
                lastEvaluatedAtMs = nowMs,
            )
        }
    }

    suspend fun resumeLocalAlert() = mutex.withLock {
        val snapshot = mutableState.value.snapshot ?: localStateStore.readCachedSnapshot()
        val currentAlert = localStateStore.readAlertState()
        val nowMs = clock()
        val decision = policy.resume(snapshot, currentAlert, nowMs)
        applyDecisionLocked(snapshot, decision, nowMs)
    }

    fun updateLocalPauseMinutes(minutes: Int) {
        if (minutes !in GuestAlertPolicy.SUPPORTED_PAUSE_MINUTES) return
        val updated = localStateStore.readAlertState().copy(pauseMinutes = minutes)
        localStateStore.writeAlertState(updated)
        mutableState.update { it.copy(localAlertState = updated) }
    }

    fun updateLocalTheme(theme: ThemePreference) {
        localStateStore.writeTheme(theme)
        mutableState.update { it.copy(theme = theme) }
    }

    private suspend fun registerPushTokenBestEffort(
        approved: RemoteSupportSessionRecord.Approved,
        explicitToken: String?,
    ) {
        val token = try {
            explicitToken?.takeIf(String::isNotBlank) ?: fcmTokenProvider()?.takeIf(String::isNotBlank)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return

        if (localStateStore.readUploadedFcmToken() == token) {
            return
        }
        val result = syncClient.registerPushToken(approved.accessToken, token)
        if (result is SharedSyncResult.Success) {
            localStateStore.writeUploadedFcmToken(token)
        }
    }

    private suspend fun loadHistoryLocked(
        approved: RemoteSupportSessionRecord.Approved,
        filter: HistoryFilter,
        beforeEntryId: Long?,
        append: Boolean,
    ) {
        val result = syncClient.fetchMonitorHistory(
            monitorId = approved.monitorId,
            accessToken = approved.accessToken,
            beforeEntryId = beforeEntryId,
            limit = HISTORY_PAGE_SIZE,
            filter = filter,
        )
        when (result) {
            is SharedSyncResult.Failure -> {
                if (result.reason == SharedSyncFailureReason.UNAUTHENTICATED) {
                    supportSessionStore.clearExpected(approved)
                    handleNoApprovedSessionLocked(credentialLost = true)
                } else {
                    mutableState.update {
                        it.copy(historyLoading = false, historyError = true)
                    }
                }
            }

            is SharedSyncResult.Success -> {
                val page = result.value
                val combined = if (append) {
                    (mutableState.value.history + page.entries).distinctBy { it.hostEntryId }
                } else {
                    page.entries
                }
                if (filter == HistoryFilter.ALL && !append) {
                    localStateStore.writeCachedHistory(combined)
                }
                mutableState.update {
                    it.copy(
                        history = combined,
                        historyHasMore = page.hasMore,
                        historyNextBeforeEntryId = page.nextBeforeEntryId,
                        historyLoading = false,
                        historyError = false,
                    )
                }
            }
        }
    }

    private fun applyPolicyLocked(snapshot: SharedMonitorSnapshot?) {
        val nowMs = clock()
        val currentAlert = localStateStore.readAlertState()
        val decision = policy.evaluate(snapshot, currentAlert, nowMs)
        applyDecisionLocked(snapshot, decision, nowMs)
    }

    private fun applyDecisionLocked(
        snapshot: SharedMonitorSnapshot?,
        decision: GuestAlertDecision,
        nowMs: Long,
    ) {
        localStateStore.writeAlertState(decision.updatedState)
        if (decision.shouldCancelNotification) {
            alertNotifier.cancel()
        } else if (decision.shouldNotify && snapshot != null) {
            alertNotifier.notify(snapshot)
        }
        decision.nextCheckAtMs?.let { nextMs ->
            scheduler.scheduleNextCheck((nextMs - nowMs).coerceAtLeast(15_000L))
        }
        mutableState.update {
            it.copy(
                localAlertState = decision.updatedState,
                lastEvaluatedAtMs = nowMs,
            )
        }
    }

    private suspend fun loadApprovedSessionLocked(): RemoteSupportSessionRecord.Approved? {
        val origin = syncClient.canonicalOrigin ?: return null
        return when (val loaded = supportSessionStore.load(origin)) {
            is RemoteSupportSessionLoad.Approved -> loaded.record
            else -> null
        }
    }

    private fun handleNoApprovedSessionLocked(credentialLost: Boolean) {
        alertNotifier.cancel()
        scheduler.cancelAll()
        if (credentialLost) {
            localStateStore.clearMonitorCache()
        }
        mutableState.update {
            it.copy(
                isCheckingRole = false,
                isApprovedGuest = false,
                monitorId = null,
                syncInProgress = false,
                credentialLost = credentialLost,
                snapshot = if (credentialLost) null else it.snapshot,
                history = if (credentialLost) emptyList() else it.history,
            )
        }
    }

    private companion object {
        const val HISTORY_PAGE_SIZE = 30
    }
}
