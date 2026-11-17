package br.com.c6tag.diagnostico.monitor

import kotlinx.coroutines.flow.StateFlow
import br.com.c6tag.diagnostico.monitor.alerts.AlertEvaluation

/** Synchronous private storage. Invoke through the coordinator's IO/serial lane. */
internal interface MonitorStore {
    val revisions: StateFlow<MonitorRevision>
    fun overview(): MonitorOverview
    fun history(beforeId: Long? = null, limit: Int, filter: HistoryFilter = HistoryFilter.ALL): List<FinancialEntry>
    fun pending(beforeId: Long? = null, limit: Int): List<PendingMovement>
    fun beginReference(nowMs: Long): MonitorResult
    fun confirmReference(command: ConfirmReference): MonitorResult
    fun cancelReference(sessionId: String, nowMs: Long): MonitorResult
    fun refresh(nowMs: Long): MonitorResult
    fun resolvePending(command: ResolvePending): MonitorResult
    fun changeSettings(command: ChangeSettings): MonitorResult
    fun acknowledge(cycleId: String, nowMs: Long): MonitorResult
    fun resume(cycleId: String, nowMs: Long): MonitorResult
    fun evaluateAlert(nowMs: Long): AlertEvaluation
    fun recordSubmission(command: RecordSubmission): MonitorResult
    fun startProcessSession(sessionId: String, nowMs: Long): MonitorResult
}
