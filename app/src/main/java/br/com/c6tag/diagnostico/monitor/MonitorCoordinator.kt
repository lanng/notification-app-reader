package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal interface MonitorAlertEffects {
    fun synchronize(overview: MonitorOverview)
    fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome
}

/** Task 5 composition: no Android effects and never a false successful submission. */
internal object DisabledMonitorAlertEffects : MonitorAlertEffects {
    override fun synchronize(overview: MonitorOverview) = Unit
    override fun submit(overview: MonitorOverview, intent: EmissionIntent) = SubmissionOutcome.FAILED
}

internal enum class MonitorCoordinatorError { STORAGE_FAILED, ALERT_SYNC_FAILED, ALERT_SUBMISSION_FAILED }
internal data class MonitorCoordinatorState(
    val errorCount: Long = 0L,
    val lastError: MonitorCoordinatorError? = null,
)

internal class MonitorCoordinator(
    private val recorder: DiagnosticRecorder,
    private val store: MonitorStore,
    private val effects: MonitorAlertEffects,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val processSessionId: String = UUID.randomUUID().toString(),
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val mutableState = MutableStateFlow(MonitorCoordinatorState())
    val state = mutableState.asStateFlow()
    val revisions: StateFlow<MonitorRevision> get() = store.revisions
    private var bootstrapped = false
    private var lastHandledRevision = -1L
    private var lastSchedule: ScheduleKey? = null
    private val bootstrapJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            recorder.runSerialized {
                ensureBootstrap()
                performEffects()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordFailure(MonitorCoordinatorError.STORAGE_FAILED)
        }
    }

    private val revisionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        store.revisions.collect { revision ->
            if (!requiresEffects(revision.cause)) return@collect
            try {
                inLane {
                    if (revision.value > lastHandledRevision) performEffects()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                recordFailure(MonitorCoordinatorError.STORAGE_FAILED)
            }
        }
    }

    suspend fun overview(): MonitorOverview = inLane { store.overview() }
    suspend fun beginReference(): MonitorResult = mutate { store.beginReference(clock()) }
    suspend fun confirmReference(command: ConfirmReference): MonitorResult = mutate {
        store.confirmReference(command.copy(nowMs = clock()))
    }
    suspend fun cancelReference(sessionId: String): MonitorResult = mutate { store.cancelReference(sessionId, clock()) }
    suspend fun resolvePending(command: ResolvePending): MonitorResult = mutate {
        store.resolvePending(command.copy(nowMs = clock()))
    }
    suspend fun changeSettings(command: ChangeSettings): MonitorResult = mutate {
        store.changeSettings(command.copy(nowMs = clock()))
    }
    suspend fun acknowledge(cycleId: String): MonitorResult = mutate { store.acknowledge(cycleId, clock()) }
    suspend fun resume(cycleId: String): MonitorResult = mutate { store.resume(cycleId, clock()) }
    suspend fun refresh(): MonitorResult = mutate(forceEffects = true) { store.refresh(clock()) }
    suspend fun history(beforeId: Long? = null, limit: Int, filter: HistoryFilter = HistoryFilter.ALL) =
        inLane { store.history(beforeId, limit, filter) }
    suspend fun pending(beforeId: Long? = null, limit: Int) = inLane { store.pending(beforeId, limit) }

    private suspend fun <T> inLane(block: () -> T): T {
        check(!closed.get()) { "Monitor coordinator closed" }
        return recorder.runSerialized {
            ensureBootstrap()
            block()
        }
    }

    private suspend fun mutate(forceEffects: Boolean = false, operation: () -> MonitorResult): MonitorResult = inLane {
        val previous = store.overview().revision
        val result = operation()
        if (result.rejection == null && (
                forceEffects || (result.overview.revision != previous && requiresEffects(result.overview.revision.cause))
            )) performEffects()
        result.copy(overview = store.overview())
    }

    private fun ensureBootstrap() {
        check(!closed.get()) { "Monitor coordinator closed" }
        if (bootstrapped) return
        check(store.startProcessSession(processSessionId, clock()).rejection == null) { "Monitor bootstrap rejected" }
        check(store.refresh(clock()).rejection == null) { "Monitor refresh rejected" }
        bootstrapped = true
    }

    private fun performEffects() {
        try {
            val evaluation = store.evaluateAlert(clock())
            if (evaluation.rejection != null) return
            val overview = store.overview()
            val schedule = ScheduleKey.from(overview)
            if (schedule != lastSchedule) {
                try {
                    effects.synchronize(overview)
                    lastSchedule = schedule
                } catch (_: Exception) {
                    recordFailure(MonitorCoordinatorError.ALERT_SYNC_FAILED)
                }
            }
            val intent = evaluation.emissionRequested ?: return
            val fresh = store.overview()
            val cycle = fresh.alertCycle ?: return
            if (cycle.status != AlertStatus.ACTIVE || cycle.emissionIntent != intent || cycle.version != intent.version) return
            val outcome = try {
                effects.submit(fresh, intent)
            } catch (_: Exception) {
                recordFailure(MonitorCoordinatorError.ALERT_SUBMISSION_FAILED)
                SubmissionOutcome.FAILED
            }
            store.recordSubmission(RecordSubmission(intent, outcome, clock()))
        } finally {
            // A revision collected during this same command cannot become a second attempt.
            lastHandledRevision = store.overview().revision.value
        }
    }

    private fun recordFailure(error: MonitorCoordinatorError) {
        mutableState.update { it.copy(errorCount = it.errorCount + 1L, lastError = error) }
    }

    override fun close() {
        closed.set(true)
        bootstrapJob.cancel()
        revisionJob.cancel()
    }

    private data class ScheduleKey(
        val cycleId: String?, val status: AlertStatus?, val pauseUntilMs: Long?, val repeatMinutes: Int,
    ) {
        companion object {
            fun from(overview: MonitorOverview): ScheduleKey {
                val cycle = overview.alertCycle?.takeIf { it.status != AlertStatus.CLOSED }
                return ScheduleKey(cycle?.id, cycle?.status, cycle?.pauseUntilMs,
                    if (cycle == null) 0 else overview.settings.alerts.repeatMinutes)
            }
        }
    }

    private fun requiresEffects(cause: RevisionCause): Boolean = when (cause) {
        RevisionCause.BOOTSTRAP, RevisionCause.APPEARANCE, RevisionCause.SUBMISSION, RevisionCause.ALERT_EVALUATION -> false
        else -> true
    }
}
