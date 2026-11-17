package br.com.c6tag.diagnostico.monitor.alerts

import android.content.Context
import br.com.c6tag.diagnostico.monitor.BalanceReference
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.MonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.ConfirmReference
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.UUID

internal fun alertOverview(cycleId: String = "synthetic-cycle"): MonitorOverview {
    val cycle = AlertPolicy().evaluate(
        AlertBalance(20_000L, 30_000L), AlertSettings(), null, 1_000L, cycleId,
    ).cycle
    return MonitorOverview(
        reference = BalanceReference(1L, 20_000L, 900L, 0L),
        balanceCents = 20_000L, continuityPreserved = true, alertCycle = cycle,
    )
}

internal class AlertHarness(
    private val context: Context,
    effects: (SqliteMonitorStore, DiagnosticDatabase) -> MonitorAlertEffects,
) : AutoCloseable {
    private val databaseName = "alert-test-${UUID.randomUUID()}.db"
    var nowMs = 1_000L
    val database = DiagnosticDatabase(context, databaseName, "synthetic.bank", { nowMs })
    val store = SqliteMonitorStore(database)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recorder = DiagnosticRecorder(database, "synthetic.bank", Dispatchers.IO, scope)
    val coordinator = MonitorCoordinator(recorder, store, effects(store, database), scope, { nowMs }, "test-process")

    suspend fun configureLow() {
        val session = coordinator.beginReference().session!!
        nowMs = 1_100L
        check(coordinator.confirmReference(ConfirmReference(session.id, null, 20_000L, 30_000L, 0L)).rejection == null)
    }

    override fun close() {
        coordinator.close()
        runBlocking { recorder.closeAndJoin() }
        scope.cancel()
        database.close()
        context.deleteDatabase(databaseName)
    }
}
