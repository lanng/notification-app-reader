package br.com.c6tag.diagnostico.monitor.storage

import android.database.sqlite.SQLiteDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.monitor.*
import kotlinx.coroutines.flow.StateFlow
import br.com.c6tag.diagnostico.monitor.alerts.AlertEvaluation
import br.com.c6tag.diagnostico.monitor.alerts.AlertRejection

internal class SqliteMonitorStore(private val helper: DiagnosticDatabase) : MonitorStore {
    override val revisions: StateFlow<MonitorRevision> get() = helper.monitorRevisions
    override fun overview() = helper.monitorRead { MonitorQueries.overview(it) }
    override fun history(beforeId: Long?, limit: Int, filter: HistoryFilter) =
        helper.monitorRead { MonitorQueries.history(it, beforeId, limit, filter) }
    override fun pending(beforeId: Long?, limit: Int) =
        helper.monitorRead { MonitorQueries.pending(it, beforeId, limit) }
    override fun beginReference(nowMs: Long) =
        helper.monitorWrite {
            val refresh = MonitorReferenceSql.expire(it, nowMs)
            if (refresh.rejection != null) refresh else MonitorReferenceSql.begin(it, nowMs)
        }
    override fun confirmReference(command: ConfirmReference) =
        helper.monitorWrite {
            val refresh = MonitorReferenceSql.expire(it, command.nowMs)
            if (refresh.rejection != null) refresh else MonitorReferenceSql.confirm(it, command)
        }
    override fun cancelReference(sessionId: String, nowMs: Long) =
        helper.monitorWrite {
            val refresh = MonitorReferenceSql.expire(it, nowMs)
            if (refresh.rejection != null) refresh else MonitorReferenceSql.cancel(it, sessionId, nowMs, helper.monitoredPackageName)
        }
    override fun refresh(nowMs: Long) = afterExpiry(nowMs) { MonitorAlertSql.refresh(it, nowMs) }
    override fun resolvePending(command: ResolvePending) = helper.monitorWrite {
        val refresh = MonitorReferenceSql.expire(it, command.nowMs)
        if (refresh.rejection != null) refresh else MonitorSql.resolve(it, command)
    }
    override fun changeSettings(command: ChangeSettings) = afterExpiry(command.nowMs) {
        MonitorAlertSql.changeSettings(it, command)
    }
    override fun acknowledge(cycleId: String, nowMs: Long) = afterExpiry(nowMs) {
        MonitorAlertSql.acknowledge(it, cycleId, nowMs)
    }
    override fun resume(cycleId: String, nowMs: Long) = afterExpiry(nowMs) {
        MonitorAlertSql.resume(it, cycleId, nowMs)
    }
    override fun evaluateAlert(nowMs: Long): AlertEvaluation = helper.monitorWrite {
        val refresh = MonitorReferenceSql.expire(it, nowMs)
        if (refresh.rejection != null) {
            AlertEvaluation(refresh.overview.alertCycle, rejection = AlertRejection.INVALID_TIME)
        } else {
            MonitorAlertSql.evaluate(it, nowMs)
        }
    }
    override fun recordSubmission(command: RecordSubmission) = afterExpiry(command.nowMs) {
        MonitorAlertSql.recordSubmission(it, command)
    }
    override fun startProcessSession(sessionId: String, nowMs: Long) = afterExpiry(nowMs) {
        MonitorSql.startProcessSession(it, sessionId, nowMs)
    }

    private fun afterExpiry(nowMs: Long, operation: (SQLiteDatabase) -> MonitorResult): MonitorResult =
        helper.monitorWrite {
            val refresh = MonitorReferenceSql.expire(it, nowMs)
            if (refresh.rejection != null) refresh else operation(it)
        }
}
