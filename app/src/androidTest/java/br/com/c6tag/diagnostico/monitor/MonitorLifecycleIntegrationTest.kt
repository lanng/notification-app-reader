package br.com.c6tag.diagnostico.monitor

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventJsonCodec
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real migration, recorder, storage and coordinator; effects count attempts without publishing. */
@RunWith(AndroidJUnit4::class)
class MonitorLifecycleIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun migratedHistoryBootstrapsThroughRealCoordinatorWithoutRetroactiveMoneyOrSubmission() = runBlocking {
        val name = "monitor-launch-${UUID.randomUUID()}.db"
        val historical = debit("historical", 100L, "16,91", "Historical")
        try {
            createV1(name, historical)
            Harness(name).use { harness ->
                val overview = harness.coordinator.refresh().overview
                assertNull(overview.reference)
                assertNull(overview.balanceCents)
                assertNull(overview.alertCycle)
                assertTrue(harness.coordinator.history(limit = 100).isEmpty())
                assertTrue(harness.coordinator.pending(limit = 100).isEmpty())
                assertEquals(listOf(historical), harness.recorder.page(limit = 100).map { it.event })
                assertEquals(0, harness.effects.submissions.get())
                harness.recorder.runSerialized {
                    assertEquals(2, harness.database.readableDatabase.version)
                    listOf("monitor_references", "monitor_entries", "monitor_pending", "monitor_alert_cycles").forEach {
                        assertEquals(0L, harness.scalar("SELECT COUNT(*) FROM $it"))
                    }
                    assertEquals(1L, harness.scalar("SELECT COUNT(*) FROM monitor_sources"))
                }
                assertEquals(0L, harness.coordinator.state.value.errorCount)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun fullFinancialLifecycleAndCoordinatorRecreationPreserveBalanceHistoryAndSourceIdentity() = runBlocking {
        val name = "monitor-lifecycle-${UUID.randomUUID()}.db"
        val first = debit("charge-a", 2_000L, "100,00", "A")
        val second = debit("charge-b", 2_500L, "150,00", "B")
        try {
            Harness(name).use { harness ->
                val session = harness.coordinator.beginReference().session!!
                harness.nowMs = 1_100L
                assertNull(harness.coordinator.confirmReference(ConfirmReference(
                    session.id, null, 50_000L, 30_000L, 0L,
                )).rejection)
                assertEquals(40_000L, harness.observe(first).balanceCents)
                val low = harness.observe(second)
                assertEquals(25_000L, low.balanceCents)
                assertEquals(AlertStatus.ACTIVE, low.alertCycle!!.status)
                assertEquals(1, harness.effects.submissions.get())
                harness.nowMs = 2_600L
                assertNull(harness.coordinator.acknowledge(low.alertCycle.id).rejection)
                assertEquals(25_000L, harness.observe(first.copy(observedAtMs = 2_700L)).balanceCents)
                val ambiguous = harness.observe(second.copy(
                    key = "reappeared-b", postedAtMs = 2_799L, observedAtMs = 2_800L,
                ))
                assertEquals(25_000L, ambiguous.balanceCents)
                assertEquals(1L, ambiguous.pendingCount)
                val credited = harness.observe(DiagnosticEvent(
                    DiagnosticEventType.POSTED_OR_UPDATED, 3_000L, packageName = "synthetic.bank",
                    key = "credit-a", postedAtMs = 2_999L, title = "Você recebeu um Pix",
                    text = "Pix recebido no valor de R$ 100,00.",
                ))
                assertEquals(35_000L, credited.balanceCents)
                assertTrue(credited.needsReference)
                assertEquals(AlertStatus.PAUSED, credited.alertCycle!!.status)
                harness.assertReconstructible()

                harness.nowMs = 3_100L
                val correction = harness.coordinator.beginReference().session!!
                harness.nowMs = 3_200L
                val corrected = harness.coordinator.confirmReference(ConfirmReference(
                    correction.id, 1L, 60_000L, 30_000L, 0L,
                ))
                assertNull(corrected.rejection)
                assertEquals(60_000L, corrected.overview.balanceCents)
                assertEquals(0L, corrected.overview.pendingCount)
                assertEquals(AlertStatus.CLOSED, corrected.overview.alertCycle!!.status)
                assertEquals(5, harness.coordinator.history(limit = 100).size)
                harness.assertReconstructible()

                val beforeClear = harness.coordinator.overview()
                val historyBeforeClear = harness.coordinator.history(limit = 100)
                harness.recorder.clear()
                assertTrue(harness.recorder.page(limit = 100).isEmpty())
                assertEquals(beforeClear, harness.coordinator.overview())
                assertEquals(historyBeforeClear, harness.coordinator.history(limit = 100))
                harness.assertSourcesAppliedOnce()
            }

            Harness(name, initialNowMs = 4_000L).use { restarted ->
                val overview = restarted.coordinator.refresh().overview
                assertEquals(60_000L, overview.balanceCents)
                assertTrue(overview.needsReference)
                assertEquals(AlertStatus.CLOSED, overview.alertCycle!!.status)
                assertEquals(5, restarted.coordinator.history(limit = 100).size)
                assertEquals(0, restarted.effects.submissions.get())
                val reappeared = restarted.observe(first.copy(observedAtMs = 4_100L))
                assertEquals(60_000L, reappeared.balanceCents)
                assertEquals(5, restarted.coordinator.history(limit = 100).size)
                restarted.assertReconstructible()
                restarted.assertSourcesAppliedOnce()
                assertEquals(0L, restarted.coordinator.state.value.errorCount)
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private inner class Harness(name: String, initialNowMs: Long = 1_000L) : AutoCloseable {
        @Volatile var nowMs = initialNowMs
        val database = DiagnosticDatabase(context, name, "synthetic.bank", { nowMs })
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = DiagnosticRecorder(database, "synthetic.bank", Dispatchers.IO, scope)
        val effects = RecordingEffects()
        val coordinator = MonitorCoordinator(
            recorder, SqliteMonitorStore(database), effects, scope, { nowMs }, UUID.randomUUID().toString(),
        )

        suspend fun observe(event: DiagnosticEvent): MonitorOverview {
            nowMs = event.observedAtMs
            recorder.recordNotification("synthetic.bank") { event }
            // The refresh queues behind the append and completes its financial effects.
            return coordinator.refresh().overview
        }

        suspend fun assertReconstructible() {
            val overview = coordinator.overview()
            val reference = overview.reference!!
            val reconstructed = coordinator.history(limit = 100)
                .filter { it.referenceId == reference.id }
                .fold(reference.balanceCents) { balance, entry ->
                    when (entry.kind) {
                        FinancialEntryKind.REFERENCE -> balance
                        FinancialEntryKind.TAG_DEBIT -> Math.subtractExact(balance, entry.amountCents)
                        FinancialEntryKind.PIX_CREDIT -> Math.addExact(balance, entry.amountCents)
                    }
                }
            assertEquals(overview.balanceCents, reconstructed)
        }

        suspend fun assertSourcesAppliedOnce() = recorder.runSerialized {
            assertEquals(0L, scalar(
                "SELECT COUNT(*) FROM (SELECT source_id FROM monitor_entries " +
                    "WHERE source_id IS NOT NULL GROUP BY source_id HAVING COUNT(*)>1)",
            ))
        }

        fun scalar(sql: String): Long = database.readableDatabase.rawQuery(sql, null).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

        override fun close() {
            coordinator.close()
            runBlocking { recorder.closeAndJoin() }
            scope.cancel()
            database.close()
        }
    }

    private class RecordingEffects : MonitorAlertEffects {
        val submissions = AtomicInteger()
        override fun synchronize(overview: MonitorOverview) = Unit
        override fun submit(overview: MonitorOverview, intent: EmissionIntent): SubmissionOutcome {
            submissions.incrementAndGet()
            return SubmissionOutcome.ACCEPTED
        }
    }

    private fun createV1(name: String, event: DiagnosticEvent) {
        context.openOrCreateDatabase(name, 0, null).use { database ->
            database.execSQL(
                "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, observed_at_ms INTEGER NOT NULL," +
                    "kind TEXT NOT NULL,payload_json TEXT NOT NULL)",
            )
            database.insertOrThrow("events", null, ContentValues().apply {
                put("observed_at_ms", event.observedAtMs)
                put("kind", event.type.name)
                put("payload_json", DiagnosticEventJsonCodec.encode(event))
            })
            database.version = 1
        }
    }

    private fun debit(key: String, observedAtMs: Long, amount: String, plaza: String) = DiagnosticEvent(
        DiagnosticEventType.POSTED_OR_UPDATED, observedAtMs, packageName = "synthetic.bank",
        key = key, postedAtMs = observedAtMs - 1L, title = "Débito C6 Tag",
        text = "Valor a debitar R$ $amount. Praça $plaza.",
    )
}
