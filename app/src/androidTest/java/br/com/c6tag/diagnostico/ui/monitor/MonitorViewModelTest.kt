package br.com.c6tag.diagnostico.ui.monitor

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
        private val recorder = DiagnosticRecorder(database, "synthetic.bank", Dispatchers.IO, scope)
        @Volatile var failReads = failReadsInitially
        private val store = object : MonitorStore by SqliteMonitorStore(database) {
            override fun history(beforeId: Long?, limit: Int, filter: HistoryFilter): List<FinancialEntry> {
                if (failReads) error("raw private exception")
                return SqliteMonitorStore(database).history(beforeId, limit, filter)
            }
        }
        val coordinator = MonitorCoordinator(recorder, store, DisabledMonitorAlertEffects, scope, { now })
        private val owner = ViewModelStore()
        val vm: MonitorViewModel
        init {
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
            withTimeout(5_000L) { vm.uiState.first(predicate) }
        suspend fun main(action: () -> Unit) = withContext(Dispatchers.Main) { action() }
        suspend fun configure() {
            await { !it.isLoading }
            main { vm.beginReference() }
            await { it.referenceDraft != null && !it.isBusy }
            main { vm.updateBalance("200"); vm.reviewReference() }
            now = 1_100L
            main { vm.confirmReference() }
            await { it.overview.reference != null && !it.isBusy }
        }
        fun record() {
            recorder.recordNotification("synthetic.bank") {
                DiagnosticEvent(DiagnosticEventType.POSTED_OR_UPDATED, now, packageName = "synthetic.bank",
                    postedAtMs = now, key = "synthetic-pix", title = "Você recebeu um Pix",
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
