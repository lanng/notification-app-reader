package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.*
import br.com.c6tag.diagnostico.monitor.alerts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class MonitorCoordinatorTest {
    @Test
    fun bootstrapRecoversPersistedIntentOnSerialLaneAndAcceptedSubmissionAdvancesSend() = runBlocking {
        fixture(initial = lowOverview()).use { fixture ->
            val overview = fixture.coordinator.overview()
            assertEquals(1, fixture.effects.submissions.size)
            assertEquals("persisted-cycle", fixture.effects.submissions.single().cycleId)
            assertEquals(2_000L, overview.alertCycle!!.lastSubmittedAtMs)
            assertNull(overview.alertCycle.emissionIntent)
            assertTrue(overview.needsReference)
            assertEquals("process-start", fixture.store.operations.first())
            assertTrue(fixture.effects.threads.all { it != fixture.callerThread })
        }
    }

    @Test
    fun unconfiguredBootstrapDoesNotInventBalanceCycleOrCaptureContinuity() = runBlocking {
        fixture().use {
            val overview = it.coordinator.overview()
            assertNull(overview.reference)
            assertNull(overview.balanceCents)
            assertNull(overview.alertCycle)
            assertFalse(overview.continuityPreserved)
            assertTrue(it.effects.submissions.isEmpty())
            assertEquals("process-start", it.store.operations.firstOrNull())
        }
    }

    @Test
    fun lowReferencePublishesDirectlyAfterCommandWithoutWaitingForPeriodicWork() = runBlocking {
        fixture().use {
            val session = it.coordinator.beginReference().session!!
            it.nowMs = 2_100L
            val result = it.coordinator.confirmReference(ConfirmReference(session.id, null, 20_000L, 30_000L, 0L))
            assertNull(result.rejection)
            assertEquals(1, it.effects.submissions.size)
            assertEquals(20_000L, it.effects.lastPublishedOverview!!.balanceCents)
            assertEquals(2_100L, it.coordinator.overview().alertCycle!!.lastSubmittedAtMs)
        }
    }

    @Test
    fun blockedSubmissionAppearanceAndReadQueriesDoNotCreateFeedbackAttempts() = runBlocking {
        fixture(lowOverview(), outcome = SubmissionOutcome.PERMISSION_DENIED).use {
            it.coordinator.overview()
            awaitObserved(it.store.revisions, it.store.revisions.value.value)
            repeat(10) { _ ->
                it.coordinator.overview()
                it.coordinator.history(limit = 10)
                it.coordinator.pending(limit = 10)
            }
            val current = it.coordinator.overview()
            it.coordinator.changeSettings(ChangeSettings(1L, current.settings.copy(theme = ThemePreference.DARK), 0L))
            awaitObserved(it.store.revisions, it.store.revisions.value.value)
            assertEquals(1, it.effects.submissions.size)
            assertEquals(1, it.effects.synchronizations.size)
            assertNull(it.coordinator.overview().alertCycle!!.lastSubmittedAtMs)
        }
    }

    @Test
    fun newExternalObservationMayRetryBlockedIntentWithoutResettingSchedule() = runBlocking {
        fixture(lowOverview(), outcome = SubmissionOutcome.PERMISSION_DENIED).use {
            it.coordinator.overview()
            awaitObserved(it.store.revisions, it.store.revisions.value.value)
            it.nowMs = 3_000L
            it.recorder.recordNotification("synthetic.bank") {
                DiagnosticEvent(DiagnosticEventType.POSTED_OR_UPDATED, 3_000L,
                    packageName = "synthetic.bank", key = "debit")
            }
            it.recorder.page(limit = 10)
            awaitObserved(it.store.revisions, it.store.revisions.value.value)
            assertEquals(2, it.effects.submissions.size)
            assertEquals(1, it.effects.synchronizations.size)
            assertEquals(it.effects.submissions.first(), it.effects.submissions.last())
        }
    }

    @Test
    fun acknowledgmentRecoveryAndLateActionsDoNotResurrectClosedCycle() = runBlocking {
        fixture(lowOverview()).use {
            val cycle = it.coordinator.overview().alertCycle!!
            it.nowMs = 3_000L
            it.coordinator.acknowledge(cycle.id)
            assertEquals(AlertStatus.PAUSED, it.coordinator.overview().alertCycle!!.status)
            it.coordinator.refresh()
            assertEquals(1, it.effects.submissions.size)
            val session = it.coordinator.beginReference().session!!
            it.nowMs = 3_100L
            it.coordinator.confirmReference(ConfirmReference(session.id, 1L, 40_000L, 30_000L, 0L))
            it.nowMs = 5_000_000L
            it.coordinator.resume(cycle.id)
            it.coordinator.refresh()
            assertEquals(AlertStatus.CLOSED, it.coordinator.overview().alertCycle!!.status)
            assertEquals(1, it.effects.submissions.size)
        }
    }

    @Test
    fun thrownSubmissionPreservesIntentAndNextExternalRefreshCanRecover() = runBlocking {
        fixture(lowOverview(), throwSubmission = true).use {
            val failed = it.coordinator.overview()
            assertEquals(SubmissionOutcome.FAILED, failed.alertCycle!!.lastSubmissionOutcome)
            assertEquals(MonitorCoordinatorError.ALERT_SUBMISSION_FAILED, it.coordinator.state.value.lastError)
            assertNull(failed.alertCycle.lastSubmittedAtMs)
            it.nowMs = 2_100L
            it.coordinator.refresh()
            assertEquals(2, it.effects.submissions.size)
            assertEquals(2_100L, it.coordinator.overview().alertCycle!!.lastSubmittedAtMs)
        }
    }

    @Test
    fun schedulingFailureDoesNotBlockFirstDirectSubmission() = runBlocking {
        fixture(lowOverview(), throwSynchronization = true).use {
            val overview = it.coordinator.overview()
            assertEquals(1, it.effects.submissions.size)
            assertEquals(2_000L, overview.alertCycle!!.lastSubmittedAtMs)
            assertEquals(MonitorCoordinatorError.ALERT_SYNC_FAILED, it.coordinator.state.value.lastError)
        }
    }

    @Test
    fun failedBootstrapCanBeRetriedByFollowingCommandAndClosedQueueDoesNotCallStore() = runBlocking {
        fixture(failBootstrap = true).use {
            assertNull(it.coordinator.overview().balanceCents)
            assertEquals(2, it.store.operations.count { operation -> operation == "process-start" })
            assertEquals(1L, it.recorder.state.value.errorCount)
            it.recorder.closeAndJoin()
            val operations = it.store.operations.size
            assertTrue(runCatching { it.coordinator.beginReference() }.isFailure)
            assertEquals(operations, it.store.operations.size)
        }
    }

    @Test
    fun stateChangedDuringSynchronizationInvalidatesOldEmissionBeforeSubmission() = runBlocking {
        fixture(lowOverview(), recoverDuringSynchronization = true).use {
            assertEquals(AlertStatus.CLOSED, it.coordinator.overview().alertCycle!!.status)
            assertTrue(it.effects.submissions.isEmpty())
        }
    }

    @Test
    fun closingCoordinatorRejectsCommandsWithoutClosingRecorderItDoesNotOwn() = runBlocking {
        fixture().use {
            it.coordinator.overview()
            it.coordinator.close()
            val operations = it.store.operations.size
            assertTrue(runCatching { it.coordinator.overview() }.isFailure)
            assertEquals(operations, it.store.operations.size)
            assertEquals(42L, it.recorder.runSerialized { 42L })
        }
    }

    private suspend fun awaitObserved(flow: ObservedRevisions, revision: Long) {
        val observed = withTimeoutOrNull(5_000L) {
            while (true) {
                if (flow.observed.receive().value >= revision) return@withTimeoutOrNull true
            }
        }
        assertEquals("Coordinator must observe the revision through its real collector", true, observed)
    }

    private fun lowOverview(): MonitorOverview {
        val balance = AlertBalance(20_000L, 30_000L)
        val cycle = AlertPolicy().evaluate(balance, AlertSettings(), null, 1_100L, "persisted-cycle").cycle
        return MonitorOverview(
            reference = BalanceReference(1L, 20_000L, 1_100L, 0L),
            balanceCents = 20_000L, continuityPreserved = true, alertCycle = cycle,
        )
    }

    private fun fixture(
        initial: MonitorOverview = MonitorOverview(),
        outcome: SubmissionOutcome = SubmissionOutcome.ACCEPTED,
        throwSubmission: Boolean = false,
        throwSynchronization: Boolean = false,
        failBootstrap: Boolean = false,
        recoverDuringSynchronization: Boolean = false,
    ) = Fixture(initial, outcome, throwSubmission, throwSynchronization, failBootstrap, recoverDuringSynchronization)

    private class Fixture(
        initial: MonitorOverview,
        outcome: SubmissionOutcome,
        throwSubmission: Boolean,
        throwSynchronization: Boolean,
        failBootstrap: Boolean,
        recoverDuringSynchronization: Boolean,
    ) : AutoCloseable {
        val callerThread: Thread = Thread.currentThread()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = FakeMonitorStore(initial).apply { failNextStart = failBootstrap }
        val effects = RecordingEffects().apply {
            this.outcome = outcome
            throwNextSubmission = throwSubmission
            throwNextSynchronization = throwSynchronization
            if (recoverDuringSynchronization) onSynchronization = {
                store.confirmReference(ConfirmReference("review", 1L, 40_000L, 30_000L, 2_000L))
            }
        }
        val recorder = DiagnosticRecorder(EmptyDiagnosticStore(store::observe), "synthetic.bank", Dispatchers.IO, scope)
        var nowMs = 2_000L
        val coordinator = MonitorCoordinator(recorder, store, effects, scope, { nowMs }, "process-a")
        override fun close() {
            coordinator.close()
            runBlocking { recorder.closeAndJoin() }
            scope.cancel()
        }
    }

    private class RecordingEffects : MonitorAlertEffects {
        val submissions = Collections.synchronizedList(mutableListOf<EmissionIntent>())
        val synchronizations = Collections.synchronizedList(mutableListOf<MonitorOverview>())
        val threads = Collections.synchronizedList(mutableListOf<Thread>())
        var outcome = SubmissionOutcome.ACCEPTED
        var throwNextSubmission = false
        var throwNextSynchronization = false
        var lastPublishedOverview: MonitorOverview? = null
        var onSynchronization: (() -> Unit)? = null
        override fun synchronize(overview: MonitorOverview) {
            threads += Thread.currentThread()
            synchronizations += overview
            onSynchronization?.invoke()
            if (throwNextSynchronization) {
                throwNextSynchronization = false
                error("synthetic scheduling failure")
            }
        }
        override fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome {
            threads += Thread.currentThread()
            submissions += intent
            lastPublishedOverview = overview
            if (throwNextSubmission) {
                throwNextSubmission = false
                error("private text must not escape")
            }
            return outcome
        }
    }

    private class FakeMonitorStore(initial: MonitorOverview) : MonitorStore {
        private var current = initial
        override val revisions = ObservedRevisions(initial.revision)
        val operations = Collections.synchronizedList(mutableListOf<String>())
        private val policy = AlertPolicy()
        private var cycleNumber = 0
        var failNextStart = false
        override fun overview() = current
        private fun replace(cause: RevisionCause, next: MonitorOverview) {
            current = next.copy(revision = MonitorRevision(current.revision.value + 1L, cause))
            revisions.publish(current.revision)
        }
        override fun startProcessSession(sessionId: String, nowMs: Long): MonitorResult {
            operations += "process-start"
            if (failNextStart) {
                failNextStart = false
                error("private storage detail")
            }
            replace(RevisionCause.PROCESS_SESSION, current.copy(continuityPreserved = false))
            return MonitorResult(current)
        }
        override fun evaluateAlert(nowMs: Long): AlertEvaluation {
            operations += "evaluate"
            val result = policy.evaluate(
                AlertBalance(current.balanceCents, current.settings.limitCents, current.needsReference),
                current.settings.alerts, current.alertCycle, nowMs, "new-cycle-${++cycleNumber}",
            )
            if (result.cycle != current.alertCycle) replace(RevisionCause.ALERT_EVALUATION, current.copy(alertCycle = result.cycle))
            return result
        }
        override fun refresh(nowMs: Long): MonitorResult {
            evaluateAlert(nowMs)
            return MonitorResult(current)
        }
        override fun recordSubmission(command: RecordSubmission): MonitorResult {
            operations += "record-submission"
            val result = policy.recordSubmission(current.alertCycle, command.intent, command.outcome, command.nowMs, current.settings.alerts)
            if (result.cycle != current.alertCycle) replace(RevisionCause.SUBMISSION, current.copy(alertCycle = result.cycle))
            return MonitorResult(current)
        }
        override fun history(beforeId: Long?, limit: Int, filter: HistoryFilter): List<FinancialEntry> = emptyList()
        override fun pending(beforeId: Long?, limit: Int): List<PendingMovement> = emptyList()
        override fun beginReference(nowMs: Long): MonitorResult {
            val session = ReferenceSession("review", nowMs, 0L, current.reference?.id)
            replace(RevisionCause.REFERENCE_SESSION, current.copy(referenceSession = session))
            return MonitorResult(current)
        }
        override fun confirmReference(command: ConfirmReference): MonitorResult {
            current = current.copy(
                reference = BalanceReference((current.reference?.id ?: 0L) + 1L, command.balanceCents, command.nowMs, 0L),
                balanceCents = command.balanceCents, settings = current.settings.copy(limitCents = command.limitCents),
                continuityPreserved = true, referenceSession = null,
            )
            evaluateAlert(command.nowMs)
            replace(RevisionCause.REFERENCE, current)
            return MonitorResult(current)
        }
        override fun cancelReference(sessionId: String, nowMs: Long) = MonitorResult(current)
        override fun resolvePending(command: ResolvePending) = MonitorResult(current)
        override fun changeSettings(command: ChangeSettings): MonitorResult {
            val appearanceOnly = command.settings.copy(theme = current.settings.theme) == current.settings
            replace(if (appearanceOnly) RevisionCause.APPEARANCE else RevisionCause.SETTINGS,
                current.copy(settings = command.settings))
            return MonitorResult(current)
        }
        override fun acknowledge(cycleId: String, nowMs: Long): MonitorResult {
            val result = policy.acknowledge(current.alertCycle, cycleId, nowMs, current.settings.alerts)
            if (result.cycle != current.alertCycle) replace(RevisionCause.ACKNOWLEDGMENT, current.copy(alertCycle = result.cycle))
            return MonitorResult(current)
        }
        override fun resume(cycleId: String, nowMs: Long): MonitorResult {
            val result = policy.resume(current.alertCycle, cycleId,
                AlertBalance(current.balanceCents, current.settings.limitCents, current.needsReference),
                current.settings.alerts, nowMs)
            if (result.cycle != current.alertCycle) replace(RevisionCause.ACKNOWLEDGMENT, current.copy(alertCycle = result.cycle))
            return MonitorResult(current)
        }
        fun observe(event: DiagnosticEvent) {
            val next = current.copy(balanceCents = current.balanceCents?.minus(100L), lastObservedAtMs = event.observedAtMs)
            replace(RevisionCause.OBSERVATION, next)
        }
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ObservedRevisions(initial: MonitorRevision) : StateFlow<MonitorRevision> {
        private val state = MutableStateFlow(initial)
        val observed = Channel<MonitorRevision>(Channel.UNLIMITED)
        override val value get() = state.value
        override val replayCache get() = state.replayCache
        fun publish(revision: MonitorRevision) { state.value = revision }
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<MonitorRevision>): Nothing {
            state.collect { revision ->
                collector.emit(revision)
                observed.send(revision)
            }
        }
    }

    private class EmptyDiagnosticStore(private val onAppend: (DiagnosticEvent) -> Unit) : DiagnosticStore {
        override fun append(event: DiagnosticEvent): Long { onAppend(event); return 1L }
        override fun page(beforeId: Long?, limit: Int): List<StoredDiagnosticEvent> = emptyList()
        override fun clear() = Unit
        override fun <T> exportSnapshot(block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T): T =
            block(DiagnosticSnapshotMetadata(0L, null, null), emptySequence())
    }
}
