package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import br.com.c6tag.diagnostico.remote.access.OwnerGeneration
import br.com.c6tag.diagnostico.remote.access.PairingCandidateInstallation
import br.com.c6tag.diagnostico.remote.access.PairingSupportInstallation
import br.com.c6tag.diagnostico.remote.access.RemoteSessionPresence
import br.com.c6tag.diagnostico.remote.access.RemoteSupportAttemptId
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionLoad
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionRecord
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionStore
import br.com.c6tag.diagnostico.remote.access.RemoteSupportSessionWriteResult
import br.com.c6tag.diagnostico.remote.access.SecretValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestMonitorRepositoryTest {
    private val origin = "https://dev.autosocorrotem.com.br"
    private val monitorId = "11111111-1111-4111-8111-111111111111"
    private val approvedRecord = RemoteSupportSessionRecord.Approved(
        origin = origin,
        attemptId = RemoteSupportAttemptId.generated("0123456789abcdef0123456789abcdef"),
        requestId = "22222222-2222-4222-8222-222222222222",
        installationId = "33333333-3333-4333-8333-333333333333",
        requestExpiresAtMillis = 1_764_000_000_000L,
        accessToken = SecretValue.of("1|support-secret"),
        monitorId = monitorId,
        membershipId = "44444444-4444-4444-8444-444444444444",
        observedGeneration = OwnerGeneration.parse("1")!!,
    )

    @Test
    fun syncsSnapshotHistoryRegistersFcmAndTriggersCriticalAlert() = runBlocking {
        var nowMs = 1_764_000_000_000L
        val sessionStore = FakeSupportStore(RemoteSupportSessionLoad.Approved(approvedRecord))
        val client = FakeSyncClient(origin).apply {
            snapshotResult = SharedSyncResult.Success(
                SharedMonitorSnapshot(
                    monitorId = monitorId,
                    hostGeneration = "1",
                    balanceCents = 12_000L,
                    limitCents = 30_000L,
                    repeatMinutes = 15,
                    pauseMinutes = 30,
                    needsReference = false,
                    severity = SharedAlertSeverity.CRITICAL,
                    alertCycleId = "cycle-crit",
                    alertOpenedAtMs = nowMs,
                    alertEmissionCount = 1,
                    guestAlertActive = true,
                    guestAlertEligibleAtMs = nowMs,
                    lastObservedAtMs = nowMs,
                    referenceConfirmedAtMs = nowMs,
                    syncedAtMs = nowMs,
                    snapshotVersion = 1L,
                    serverTimeMs = nowMs,
                ),
            )
            historyResult = SharedSyncResult.Success(
                SharedHistoryPage(
                    entries = listOf(
                        SharedHistoryEntry(5L, FinancialEntryKind.TAG_DEBIT, 1_800L, 12_000L, nowMs),
                    ),
                    hasMore = false,
                    nextBeforeEntryId = null,
                ),
            )
        }
        val localStore = InMemoryGuestLocalStateStore()
        val notifier = FakeGuestNotifier()
        val scheduler = FakeGuestScheduler()
        val repo = GuestMonitorRepository(
            supportSessionStore = sessionStore,
            syncClient = client,
            localStateStore = localStore,
            alertNotifier = notifier,
            scheduler = scheduler,
            fcmTokenProvider = { "fcm-guest-token" },
            clock = { nowMs },
        )

        assertTrue(repo.syncNow())
        assertTrue(repo.state.value.isApprovedGuest)
        assertEquals(12_000L, repo.state.value.snapshot?.balanceCents)
        assertEquals(1, repo.state.value.history.size)
        assertEquals(listOf("fcm-guest-token"), client.registeredTokens)
        assertEquals(1, notifier.notifiedCount)
        assertTrue(scheduler.periodicEnsured)
        assertEquals(1, scheduler.scheduledDelays.size)

        // Second sync does not re-upload same FCM token or duplicate alert before repeatMinutes
        nowMs += 60_000L
        assertTrue(repo.syncNow())
        assertEquals(1, client.registeredTokens.size)
        assertEquals(1, notifier.notifiedCount)
    }

    @Test
    fun unauthenticatedResponseClearsSupportSessionAndCancelsGuestAlerts() = runBlocking {
        val sessionStore = FakeSupportStore(RemoteSupportSessionLoad.Approved(approvedRecord))
        val client = FakeSyncClient(origin).apply {
            snapshotResult = SharedSyncResult.Failure(SharedSyncFailureReason.UNAUTHENTICATED)
        }
        val localStore = InMemoryGuestLocalStateStore()
        val notifier = FakeGuestNotifier()
        val scheduler = FakeGuestScheduler()
        val repo = GuestMonitorRepository(
            supportSessionStore = sessionStore,
            syncClient = client,
            localStateStore = localStore,
            alertNotifier = notifier,
            scheduler = scheduler,
            fcmTokenProvider = { null },
            clock = { 1_764_000_000_000L },
        )

        assertFalse(repo.syncNow())
        assertTrue(sessionStore.cleared)
        assertFalse(repo.state.value.isApprovedGuest)
        assertTrue(repo.state.value.credentialLost)
        assertNull(repo.state.value.snapshot)
        assertTrue(notifier.cancelledCount > 0)
        assertTrue(scheduler.cancelled)
    }

    private class FakeSupportStore(
        private var current: RemoteSupportSessionLoad,
    ) : RemoteSupportSessionStore {
        var cleared = false
        override suspend fun load(origin: String) = current
        override suspend fun presence(): RemoteSessionPresence =
            if (current is RemoteSupportSessionLoad.Empty) RemoteSessionPresence.Absent else RemoteSessionPresence.Present
        override suspend fun beginAttempt(origin: String, expectedPreviousAttempt: RemoteSupportAttemptId?) =
            error("unused")
        override suspend fun commitPending(
            origin: String,
            attemptId: RemoteSupportAttemptId,
            candidate: PairingCandidateInstallation,
        ) = error("unused")
        override suspend fun commitApproved(
            expectedRecord: RemoteSupportSessionRecord,
            supportInstallation: PairingSupportInstallation,
        ) = error("unused")
        override suspend fun clearExpected(expectedRecord: RemoteSupportSessionRecord): RemoteSupportSessionWriteResult {
            cleared = true
            current = RemoteSupportSessionLoad.Empty
            return RemoteSupportSessionWriteResult.Cleared
        }
    }

    private class FakeSyncClient(override val canonicalOrigin: String) : SharedMonitorSyncClient {
        var snapshotResult: SharedSyncResult<SharedMonitorSnapshot?> = SharedSyncResult.Success(null)
        var historyResult: SharedSyncResult<SharedHistoryPage> =
            SharedSyncResult.Success(SharedHistoryPage(emptyList(), false, null))
        val registeredTokens = mutableListOf<String>()

        override suspend fun publishHostSnapshot(
            monitorId: String,
            accessToken: SecretValue,
            upload: HostSnapshotUpload,
        ) = error("unused")

        override suspend fun fetchMonitorSnapshot(monitorId: String, accessToken: SecretValue) =
            snapshotResult

        override suspend fun fetchMonitorHistory(
            monitorId: String,
            accessToken: SecretValue,
            beforeEntryId: Long?,
            limit: Int,
            filter: HistoryFilter,
        ) = historyResult

        override suspend fun registerPushToken(accessToken: SecretValue, fcmToken: String): SharedSyncResult<Unit> {
            registeredTokens.add(fcmToken)
            return SharedSyncResult.Success(Unit)
        }

        override suspend fun deletePushToken(accessToken: SecretValue) = SharedSyncResult.Success(Unit)
    }

    private class InMemoryGuestLocalStateStore : GuestLocalStateStore {
        private var alertState = GuestLocalAlertState()
        private var snapshot: SharedMonitorSnapshot? = null
        private var history: List<SharedHistoryEntry> = emptyList()
        private var theme = ThemePreference.SYSTEM
        private var token: String? = null

        override fun readAlertState() = alertState
        override fun writeAlertState(state: GuestLocalAlertState) { alertState = state }
        override fun readCachedSnapshot() = snapshot
        override fun writeCachedSnapshot(snapshot: SharedMonitorSnapshot?) { this.snapshot = snapshot }
        override fun readCachedHistory() = history
        override fun writeCachedHistory(entries: List<SharedHistoryEntry>) { history = entries }
        override fun readTheme() = theme
        override fun writeTheme(theme: ThemePreference) { this.theme = theme }
        override fun readUploadedFcmToken() = token
        override fun writeUploadedFcmToken(token: String?) { this.token = token }
        override fun clearMonitorCache() {
            alertState = GuestLocalAlertState(pauseMinutes = alertState.pauseMinutes)
            snapshot = null
            history = emptyList()
            token = null
        }
    }

    private class FakeGuestNotifier : GuestAlertNotifier {
        var notifiedCount = 0
        var cancelledCount = 0
        override fun ensureChannel() = Unit
        override fun notify(snapshot: SharedMonitorSnapshot): Boolean {
            notifiedCount++
            return true
        }
        override fun cancel() { cancelledCount++ }
    }

    private class FakeGuestScheduler : GuestSyncScheduler {
        var periodicEnsured = false
        var cancelled = false
        val scheduledDelays = mutableListOf<Long>()
        override fun ensurePeriodicSync() { periodicEnsured = true }
        override fun scheduleNextCheck(delayMs: Long) { scheduledDelays.add(delayMs) }
        override fun cancelAll() { cancelled = true }
    }
}
