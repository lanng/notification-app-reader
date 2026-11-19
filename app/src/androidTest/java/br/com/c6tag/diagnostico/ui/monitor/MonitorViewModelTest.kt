package br.com.c6tag.diagnostico.ui.monitor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.diagnostic.*
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MonitorViewModelTest {
    @Test fun readFailureEndsLoadingAndRetryClearsSafeErrorWithoutLosingBalance() = runBlocking {
        Fixture(failReadsInitially = true).use { f ->
            val failed = f.await { !it.isLoading && it.error != null }
            assertFalse(failed.error!!.contains("raw private exception"))
            f.failReads = false
            f.main { f.vm.refresh() }
            f.await { !it.isBusy && !it.isLoading && it.error == null }
            f.configure()
            f.failReads = true
            f.main { f.vm.refresh() }
            val retained = f.await { !it.isBusy && it.error != null }
            assertEquals(20_000L, retained.overview.balanceCents)
        }
    }

    @Test fun externalNewReferenceInvalidatesOldFormWithoutReplacingItsTypedValue() = runBlocking {
        Fixture().use { f ->
            f.await { !it.isLoading }
            f.main { f.vm.beginReference() }
            val form = f.await { it.referenceDraft != null && !it.isBusy }.referenceDraft!!
            f.main { f.vm.updateBalance("999") }
            f.now = 1_100L
            f.coordinator.confirmReference(ConfirmReference(form.session.id, null, 10_000L, 30_000L, 0L))
            val stale = f.await { it.referenceDraft?.sessionUnavailable == true && it.overview.reference != null }
            assertEquals("999", stale.referenceDraft!!.balance)
            f.main { f.vm.reviewReference(); f.vm.confirmReference() }
            assertEquals(10_000L, f.coordinator.overview().balanceCents)
        }
    }

    @Test fun themeChoicePersistsWithoutReferenceAndDoesNotChangeFinancialSettings() = runBlocking {
        Fixture().use { f ->
            val before = f.await { !it.isLoading }.overview.settings
            f.main { f.vm.changeTheme(ThemePreference.DARK) }
            val changed = f.await { it.overview.settings.theme == ThemePreference.DARK && !it.isBusy }
            assertEquals(before.copy(theme = ThemePreference.DARK), changed.overview.settings)
            assertEquals(ThemePreference.DARK, f.coordinator.overview().settings.theme)
            assertNull(changed.overview.reference)
            assertTrue(f.coordinator.history(limit = 3).isEmpty())
        }
    }

    @Test fun financialHistoryPagesByLocalIdAndFilterChangesDiscardAnInFlightPage() = runBlocking {
        Fixture().use { f ->
            f.configure()
            repeat(21) { index ->
                f.now += 100L
                val session = f.coordinator.beginReference().session!!
                val expected = f.coordinator.overview().reference!!.id
                assertNull(f.coordinator.confirmReference(ConfirmReference(
                    session.id, expected, 20_000L + index, 30_000L, 0L,
                )).rejection)
            }
            val first = f.await { !it.historyLoading && it.overview.reference?.id == 22L }
            assertEquals(20, first.history.size)
            assertTrue(first.historyHasMore)
            assertTrue(first.history.zipWithNext().all { (newer, older) -> newer.id > older.id })

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.historyEntered = entered
            f.historyRelease = release
            f.main { f.vm.loadMoreHistory() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            f.main { f.vm.selectHistoryFilter(HistoryFilter.ADJUSTMENTS) }
            release.countDown()
            f.historyEntered = null
            f.historyRelease = null
            val filtered = f.await {
                it.historyFilter == HistoryFilter.ADJUSTMENTS && !it.historyLoading && it.history.isNotEmpty()
            }
            assertTrue(filtered.history.all { it.kind == FinancialEntryKind.REFERENCE })
            assertEquals(20, filtered.history.size)
            assertTrue(filtered.historyHasMore)

            f.now += 100L
            val next = f.coordinator.beginReference().session!!
            assertNull(f.coordinator.confirmReference(ConfirmReference(
                next.id, 22L, 23_000L, 30_000L, 0L,
            )).rejection)
            val refreshed = f.await {
                it.overview.reference?.id == 23L && !it.historyLoading && it.history.firstOrNull()?.referenceId == 23L
            }
            assertEquals(20, refreshed.history.size)
            assertTrue(refreshed.history.zipWithNext().all { (newer, older) -> newer.id > older.id })
        }
    }

    @Test fun pendingReviewPagesByDescendingLocalIdAndReturnsToNewestAfterNewObservation() = runBlocking {
        Fixture().use { f ->
            suspend fun awaitStage(
                label: String,
                predicate: (MonitorUiState) -> Boolean,
            ): MonitorUiState = try {
                f.await(predicate)
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Timed out at $label; synthetic UI state=${f.vm.uiState.value}", timeout)
            }

            f.configure()
            f.recreateViewModel()
            val session = f.coordinator.beginReference().session!!
            repeat(21) { index ->
                f.now += 10L
                f.record("pending-$index")
            }
            val persistedPending = f.coordinator.pending(limit = 100)
            assertEquals("All synthetic observations should be retained as candidates.", 21, persistedPending.size)
            f.recreateViewModel()
            awaitStage("candidate count") { it.overview.pendingCount == 21L }
            assertEquals(session.id, f.vm.uiState.value.overview.referenceSession?.id)
            f.main { f.vm.loadPending() }
            val firstPage = awaitStage("first pending page") { !it.pendingLoading && it.pending.size == 20 && it.pendingHasMore }
            assertTrue(firstPage.pending.zipWithNext().all { (newer, older) -> newer.id > older.id })

            f.main { f.vm.loadMorePending() }
            val allPages = awaitStage("second pending page") { !it.pendingLoading && it.pending.size == 21 && !it.pendingHasMore }
            assertEquals(21, allPages.pending.map { it.id }.distinct().size)

            f.now += 10L
            f.record("pending-newest")
            val newestId = f.coordinator.pending(limit = 1).single().id
            val refreshed = awaitStage("new candidate refresh") {
                it.overview.pendingCount == 22L && !it.pendingLoading && it.pending.firstOrNull()?.id == newestId
            }
            assertEquals(20, refreshed.pending.size)
            assertTrue(refreshed.pendingHasMore)
        }
    }

    @Test fun settingsRejectNegativeLimitAndUnsupportedIntervalsAndPersistValidChoices() = runBlocking {
        Fixture().use { f ->
            val initial = f.await { !it.isLoading }.overview.settings
            f.main {
                f.vm.editSettingsLimit("-1,00")
                f.vm.editSettingsRepeat(20)
                f.vm.editSettingsPause(45)
                f.vm.saveSettings()
            }
            val invalid = f.vm.uiState.value
            assertTrue(invalid.settingsDraft!!.invalid)
            assertEquals(initial, f.coordinator.overview().settings)

            f.main {
                f.vm.editSettingsLimit("450,00")
                f.vm.editSettingsRepeat(30)
                f.vm.editSettingsPause(120)
                f.vm.saveSettings()
            }
            val saved = f.await {
                !it.isBusy && it.overview.settings.limitCents == 45_000L &&
                    it.overview.settings.alerts.repeatMinutes == 30 &&
                    it.overview.settings.alerts.pauseMinutes == 120
            }
            assertNull(saved.settingsDraft)
            assertEquals(saved.overview.settings, f.coordinator.overview().settings)
            assertNull(saved.overview.reference)
        }
    }

    @Test fun nativeSettingsLaunchFailuresStaySeparateFromNoticeAndCaptureCapabilities() = runBlocking {
        Fixture().use { f ->
            f.await { !it.isLoading }
            f.main { f.vm.updatePlatform(AuthorizationStatus.NOT_AUTHORIZED, NoticeAccess.DENIED) }
            f.main { f.vm.reportNoticeSettingsFailure() }
            val noticeFailure = f.vm.uiState.value
            assertNotNull(noticeFailure.noticeSettingsError)
            assertNull(noticeFailure.captureSettingsError)
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, noticeFailure.authorization)
            assertEquals(NoticeAccess.DENIED, noticeFailure.notices)

            f.main { f.vm.reportCaptureSettingsFailure() }
            val captureFailure = f.vm.uiState.value
            assertNotNull(captureFailure.captureSettingsError)
            assertNotNull(captureFailure.noticeSettingsError)
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, captureFailure.authorization)
            assertEquals(NoticeAccess.DENIED, captureFailure.notices)

            f.main { f.vm.clearNoticeSettingsError(); f.vm.clearCaptureSettingsError() }
            assertNull(f.vm.uiState.value.noticeSettingsError)
            assertNull(f.vm.uiState.value.captureSettingsError)
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, f.vm.uiState.value.authorization)
            assertEquals(NoticeAccess.DENIED, f.vm.uiState.value.notices)
        }
    }

    @Test fun stalePendingSelectionIsInvalidatedAfterExternalReferenceCorrection() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.main { f.vm.beginReference() }
            val draft = f.await { it.referenceDraft != null && !it.isBusy }.referenceDraft!!
            f.now = 1_200L
            f.record()
            f.await { it.overview.pendingCount == 1L }
            f.main { f.vm.loadPending() }
            val pending = f.await { !it.pendingLoading && it.pending.isNotEmpty() }.pending.first()
            f.main { f.vm.openPending(pending.id) }
            assertEquals(1L, f.vm.uiState.value.pendingSelection!!.expectedReferenceId)

            f.now = 1_300L
            assertNull(f.coordinator.confirmReference(ConfirmReference(
                draft.session.id, 1L, 25_000L, 30_000L, 0L,
            )).rejection)
            val stale = f.await { it.pendingSelection?.stale == true && it.overview.reference?.id == 2L }
            assertNull(stale.pendingSelection!!.confirmation)
            assertEquals(25_000L, stale.overview.balanceCents)
            f.main { f.vm.requestPendingDecision(PendingChoice.CONFIRM_NEW); f.vm.confirmPendingDecision() }
            assertEquals(25_000L, f.coordinator.overview().balanceCents)
            assertEquals(1L, f.coordinator.overview().pendingCount)
        }
    }

    @Test fun pendingWithRemovedRawEvidenceShowsPersistedSummaryAndCanBeExplicitlyResolved() = runBlocking {
        Fixture().use { f ->
            f.configure(awaitLabelPrefix = "pending-removed-raw")
            f.main { f.vm.beginReference() }
            val draft = f.await("active-reference-draft") {
                it.referenceDraft != null && !it.isBusy
            }.referenceDraft!!
            f.now = 1_200L
            f.record()
            f.await("pending-count-one") { it.overview.pendingCount == 1L }
            f.now = 1_300L
            val externalConfirmation = f.coordinator.confirmReference(ConfirmReference(
                draft.session.id, 1L, 20_000L, 30_000L, 0L,
            ))
            assertNull(externalConfirmation.rejection)
            val confirmedOverview = externalConfirmation.overview
            val expectedReferenceId = requireNotNull(confirmedOverview.reference?.id)
            val expectedRevision = confirmedOverview.revision.value
            val observedReference = f.await("new-reference-observed") {
                it.overview.reference?.id == expectedReferenceId &&
                    it.overview.revision.value >= expectedRevision &&
                    it.overview.referenceSession == null &&
                    !it.isBusy
            }
            assertEquals(expectedReferenceId, observedReference.overview.reference?.id)
            f.recorder.clear()
            f.main { f.vm.loadPending() }
            val missingRaw = f.await("pending-page-loaded") {
                !it.pendingLoading && it.pending.isNotEmpty()
            }
            assertFalse(missingRaw.pending.single().rawEvidenceAvailable)
            assertEquals(20_000L, missingRaw.overview.balanceCents)
            assertEquals(FinancialEntryKind.REFERENCE, f.coordinator.history(limit = 1).single().kind)

            f.main { f.vm.openPending(missingRaw.pending.single().id) }
            assertFalse(f.vm.uiState.value.pendingSelection!!.movement.rawEvidenceAvailable)
            f.main {
                f.vm.requestPendingDecision(PendingChoice.CONFIRM_NEW)
                f.vm.confirmPendingDecision()
                f.vm.confirmPendingDecision()
            }
            val applied = f.await("resolution-applied") {
                !it.isBusy && it.overview.balanceCents == 21_000L
            }
            assertNull(applied.pendingSelection)
            assertEquals(0L, applied.overview.pendingCount)
            assertEquals(21_000L, f.coordinator.overview().balanceCents)
        }
    }

    @Test fun initialLoadAndExplicitReferencePersistOnceDespiteDoubleTap() = runBlocking {
        Fixture().use { f ->
            val initial = f.await { !it.isLoading }
            assertNull(initial.overview.balanceCents)
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.main {
                f.vm.updateBalance("847,20")
                f.vm.reviewReference()
            }
            f.await { it.referenceDraft?.confirmationVisible == true }
            f.now = 1_100L
            f.main { f.vm.confirmReference(); f.vm.confirmReference() }
            val saved = f.await { it.overview.reference != null && it.referenceDraft == null && !it.isBusy }
            assertEquals(84_720L, saved.overview.balanceCents)
            assertEquals(1, f.coordinator.history(limit = 100).size)
            assertEquals(FinancialEntryKind.REFERENCE, saved.recent.single().kind)
        }
    }

    @Test fun invalidInputAndUnreviewedSubmissionCannotCreateReference() = runBlocking {
        Fixture().use { f ->
            f.await { !it.isLoading }
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.main { f.vm.updateBalance("10,001"); f.vm.reviewReference() }
            assertTrue(f.vm.uiState.value.referenceDraft!!.invalidInput)
            f.main { f.vm.updateBalance("12"); f.vm.updateLimit("-1"); f.vm.reviewReference() }
            assertTrue(f.vm.uiState.value.referenceDraft!!.invalidInput)
            f.main { f.vm.updateLimit("300"); f.vm.confirmReference() }
            assertNull(f.coordinator.overview().reference)
            f.main { f.vm.cancelReference() }
            f.await { it.referenceDraft == null && !it.isBusy }
            assertNull(f.coordinator.overview().referenceSession)
        }
    }

    @Test fun concurrentObservationIsVisibleAndRetainedWithoutAutomaticFinancialImpact() = runBlocking {
        Fixture().use { f ->
            f.await { !it.isLoading }
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.now = 1_050L
            f.record()
            f.await { it.overview.pendingCount == 1L }
            f.main { f.vm.updateBalance("-20,00"); f.vm.reviewReference() }
            f.now = 1_100L
            f.main { f.vm.confirmReference() }
            val saved = f.await { it.referenceDraft == null && it.overview.reference != null && !it.isBusy }
            assertEquals(-2_000L, saved.overview.balanceCents)
            assertTrue(saved.overview.needsReference)
            assertEquals(1L, saved.overview.pendingCount)
            assertTrue(saved.overview.reference!!.explicitlyIncludedObservationIds.isEmpty())
            assertEquals(1, f.coordinator.pending(limit = 100).size)
        }
    }

    @Test fun correctionIsNotCreditAndCancelReevaluatesProvisionalObservation() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.now = 1_200L
            f.record()
            f.await { it.overview.pendingCount == 1L }
            f.main { f.vm.cancelReference() }
            val cancelled = f.await { it.referenceDraft == null && !it.isBusy && it.overview.balanceCents == 21_000L }
            assertEquals(0L, cancelled.overview.pendingCount)
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.main { f.vm.updateBalance("100,00"); f.vm.reviewReference() }
            f.now = 1_300L
            f.main { f.vm.confirmReference() }
            val corrected = f.await { it.overview.reference?.id == 2L && !it.isBusy }
            assertEquals(10_000L, corrected.overview.balanceCents)
            assertEquals(listOf(FinancialEntryKind.REFERENCE, FinancialEntryKind.PIX_CREDIT, FinancialEntryKind.REFERENCE),
                corrected.recent.map { it.kind })
        }
    }

    @Test fun expiredSessionRetainsDraftButCannotSaveAndCanBeCancelled() = runBlocking {
        Fixture().use { f ->
            f.await { !it.isLoading }
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.main { f.vm.updateBalance("42,00") }
            f.now = 901_000L
            f.main { f.vm.refresh() }
            val expired = f.await { it.referenceDraft?.sessionUnavailable == true && !it.isBusy }
            assertEquals("42,00", expired.referenceDraft!!.balance)
            f.main { f.vm.reviewReference(); f.vm.confirmReference() }
            assertNull(f.coordinator.overview().reference)
            f.main { f.vm.cancelReference() }
            f.await { it.referenceDraft == null && !it.isBusy }
            assertNull(f.coordinator.overview().referenceSession)
        }
    }

    @Test fun cancelExpiresAnUnrefreshedSessionAndClosesOnTheFirstTapWithoutError() = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.main { f.vm.beginReference() }
            f.await { it.referenceDraft != null && !it.isBusy }
            f.now = 1_200L
            f.record()
            f.await { it.overview.pendingCount == 1L }
            // Finish observation effects while the clock is still inside the valid session.
            // Otherwise a delayed evaluation could discover expiry before cancel is invoked.
            f.main { f.vm.refresh() }
            f.await { !it.isBusy }
            // No refresh/resume before cancellation: the store discovers expiry in this command.
            f.now = 901_101L
            f.main { f.vm.cancelReference() }
            val cancelled = f.await { !it.isBusy }
            assertNull(cancelled.referenceDraft)
            assertNull(cancelled.error)
            assertNull(f.coordinator.overview().referenceSession)
            assertEquals(20_000L, cancelled.overview.balanceCents)
            assertEquals(1L, cancelled.overview.pendingCount)
        }
    }

    @Test fun platformStatesDoNotBlockBalanceAndCycleActionsUsePersistedIdentity(): Unit = runBlocking {
        Fixture().use { f ->
            f.configure()
            f.main { f.vm.updatePlatform(AuthorizationStatus.NOT_AUTHORIZED, NoticeAccess.DENIED) }
            assertEquals(20_000L, f.vm.uiState.value.overview.balanceCents)
            assertEquals(NoticeAccess.DENIED, f.vm.uiState.value.notices)
            val cycle = f.coordinator.overview().alertCycle!!
            f.now = 2_000L
            f.main { f.vm.acknowledge(cycle.id) }
            f.await { it.overview.alertCycle?.status == AlertStatus.PAUSED && !it.isBusy }
            f.main { f.vm.resume("obsolete") }
            f.await { !it.isBusy }
            assertEquals(AlertStatus.PAUSED, f.coordinator.overview().alertCycle!!.status)
            f.main { f.vm.resume(cycle.id) }
            f.await { it.overview.alertCycle?.status == AlertStatus.ACTIVE && !it.isBusy }
        }
    }

    private class Fixture(failReadsInitially: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "monitor-ui-${UUID.randomUUID()}.db"
        @Volatile var now = 1_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val database = DiagnosticDatabase(context, name, "synthetic.bank", { now })
        val recorder = DiagnosticRecorder(database, "synthetic.bank", Dispatchers.IO, scope)
        @Volatile var failReads = failReadsInitially
        @Volatile var historyEntered: CountDownLatch? = null
        @Volatile var historyRelease: CountDownLatch? = null
        private val store = object : MonitorStore by SqliteMonitorStore(database) {
            override fun history(beforeId: Long?, limit: Int, filter: HistoryFilter): List<FinancialEntry> {
                if (failReads) error("raw private exception")
                historyEntered?.countDown()
                historyRelease?.await(5, TimeUnit.SECONDS)
                return SqliteMonitorStore(database).history(beforeId, limit, filter)
            }
        }
        val coordinator = MonitorCoordinator(recorder, store, DisabledMonitorAlertEffects, scope, { now })
        private val owner = ViewModelStore()
        lateinit var vm: MonitorViewModel
            private set
        init {
            attachViewModel()
        }
        fun recreateViewModel() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            attachViewModel()
        }
        private fun attachViewModel() {
            var created: MonitorViewModel? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                created = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        MonitorViewModel(coordinator, CaptureStatus("synthetic.bank")) as T
                })[MonitorViewModel::class.java]
            }
            vm = created!!
        }
        suspend fun await(predicate: (MonitorUiState) -> Boolean): MonitorUiState =
            await(null, predicate)
        suspend fun await(
            stage: String?,
            predicate: (MonitorUiState) -> Boolean,
        ): MonitorUiState = try {
            withTimeout(5_000L) { vm.uiState.first(predicate) }
        } catch (timeout: TimeoutCancellationException) {
            if (stage != null) {
                val state = vm.uiState.value
                val overview = state.overview
                val selection = state.pendingSelection
                Log.e(
                    "MonitorViewModelTest",
                    "[T7-AWAIT-TIMEOUT] stage=$stage " +
                        "busy=${state.isBusy} referenceId=${overview.reference?.id} " +
                        "referenceSessionId=${overview.referenceSession?.id} " +
                        "pendingCount=${overview.pendingCount} " +
                        "visiblePendingRows=${state.pending.size} " +
                        "selectedExpectedReferenceId=${selection?.expectedReferenceId} " +
                        "selectedStale=${selection?.stale} " +
                        "selectedConfirmation=${selection?.confirmation} " +
                        "balanceCents=${overview.balanceCents} " +
                        "revision=${overview.revision.value}/${overview.revision.cause}",
                    timeout,
                )
            }
            throw timeout
        }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun configure(awaitLabelPrefix: String? = null) {
            await(awaitLabelPrefix?.let { "$it.initial" }) { !it.isLoading }
            main { vm.beginReference() }
            await(awaitLabelPrefix?.let { "$it.reference-draft" }) {
                it.referenceDraft != null && !it.isBusy
            }
            main { vm.updateBalance("200"); vm.reviewReference() }
            now = 1_100L
            main { vm.confirmReference() }
            await(awaitLabelPrefix?.let { "$it.reference-confirmed" }) {
                it.overview.reference != null && !it.isBusy
            }
        }
        fun record(key: String = "synthetic-pix") {
            recorder.recordNotification("synthetic.bank") {
                DiagnosticEvent(DiagnosticEventType.POSTED_OR_UPDATED, now, packageName = "synthetic.bank",
                    postedAtMs = now, key = key, title = "Você recebeu um Pix",
                    text = "Pix recebido no valor de R$ 10,00.")
            }
        }
        override fun close() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            coordinator.close()
            runBlocking { recorder.closeAndJoin() }
            scope.cancel()
            database.close()
            context.deleteDatabase(name)
        }
    }
}
