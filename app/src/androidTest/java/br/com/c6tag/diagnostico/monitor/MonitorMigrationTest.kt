package br.com.c6tag.diagnostico.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.diagnostic.DiagnosticDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventJsonCodec
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.monitor.storage.SqliteMonitorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorMigrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun v1UpgradePreservesEventsAndBackfillsEveryEvidenceVariantWithoutMoney() = withDatabase { name ->
        val original = debit()
        val enriched = original.copy(observedAtMs = 200L, bigText = "${original.text} Praça 123.")
        val conflict = original.copy(observedAtMs = 300L, text = "Valor a debitar R$ 20,00.")
        val reused = original.copy(observedAtMs = 400L, postedAtMs = 350L)
        val incomplete = original.copy(observedAtMs = 500L, key = null, postedAtMs = null)
        val events = listOf(original, enriched, conflict, reused, incomplete)
        createV1(name, events)
        DiagnosticDatabase(context, name).use { helper ->
            val database = helper.writableDatabase
            assertEquals(2, database.version)
            assertEquals(events.reversed(), helper.page(limit = 100).map { it.event })
            assertNoFinancialState(database)
            assertEquals(3L, database.count("monitor_sources"))
            assertEquals(5L, database.count("monitor_source_evidence"))
            assertEquals(1L, database.scalar("SELECT COUNT(*) FROM monitor_sources WHERE conflicting = 1"))
            assertEquals(1L, database.scalar("SELECT COUNT(*) FROM monitor_sources WHERE notification_key IS NULL"))
            val expected = events.map { MovementPolicy().evidence(it)!!.contentHash }.toSet()
            assertEquals(expected, database.rawQuery(
                "SELECT DISTINCT content_hash FROM monitor_source_evidence", null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } })
        }
    }

    @Test
    fun clearingRawEventsDoesNotDeleteMigratedIdentityOrEarlierVariants() = withDatabase { name ->
        val first = debit()
        val last = first.copy(observedAtMs = 200L, text = "${first.text} Evidência adicional.")
        createV1(name, listOf(first, last))
        DiagnosticDatabase(context, name).use { helper ->
            assertEquals(2, helper.writableDatabase.version)
            helper.clear()
            assertTrue(helper.page(limit = 100).isEmpty())
            assertEquals(1L, helper.readableDatabase.count("monitor_sources"))
            assertEquals(2L, helper.readableDatabase.count("monitor_source_evidence"))
            assertNoFinancialState(helper.readableDatabase)
        }
    }

    @Test
    fun corruptPayloadAbortsMigrationWithoutLosingV1RowsOrLeavingHalfSchema() = withDatabase { name ->
        createV1(name, listOf(debit()))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            it.execSQL(
                "INSERT INTO events(observed_at_ms,kind,payload_json) VALUES(200,'POSTED_OR_UPDATED','{}')",
            )
        }
        val helper = DiagnosticDatabase(context, name)
        try {
            assertThrows(IllegalStateException::class.java) { helper.writableDatabase }
        } finally {
            helper.close()
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
            assertEquals(1, it.version)
            assertEquals(2L, it.count("events"))
            assertEquals(0L, it.scalar(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='monitor_sources'",
            ))
        }
    }

    @Test
    fun freshSchemaHasNoConfiguredBalanceOrAlert() = withDatabase { name ->
        DiagnosticDatabase(context, name).use {
            assertEquals(2, it.writableDatabase.version)
            assertNoFinancialState(it.readableDatabase)
            assertEquals(0L, it.readableDatabase.count("monitor_sources"))
        }
    }

    @Test
    fun newFinancialEvidenceBeforeFirstReferenceSurvivesClearAndReopenWithoutMoney() = withDatabase { name ->
        DiagnosticDatabase(context, name).use {
            val firstId = it.append(debit())
            it.append(debit().copy(observedAtMs = 200L, text = "${debit().text} Praça 123."))
            assertEquals(1L, it.readableDatabase.count("monitor_sources"))
            assertEquals(2L, it.readableDatabase.count("monitor_source_evidence"))
            it.clear()
            assertTrue(it.append(debit().copy(observedAtMs = 300L)) > firstId)
        }
        DiagnosticDatabase(context, name).use {
            assertEquals(1L, it.readableDatabase.count("monitor_sources"))
            assertEquals(2L, it.readableDatabase.count("monitor_source_evidence"))
            assertNoFinancialState(it.readableDatabase)
        }
    }

    @Test
    fun identityWriteFailureRollsBackRawObservationAndPartialSource() = withDatabase { name ->
        DiagnosticDatabase(context, name).use {
            it.writableDatabase.execSQL(
                """CREATE TRIGGER reject_evidence BEFORE INSERT ON monitor_source_evidence
                   BEGIN SELECT RAISE(ABORT,'synthetic identity failure'); END""",
            )
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { it.append(debit()) }
            assertEquals(0L, it.readableDatabase.count("events"))
            assertEquals(0L, it.readableDatabase.count("monitor_sources"))
            assertEquals(0L, it.readableDatabase.count("monitor_source_evidence"))
        }
    }

    @Test
    fun migratedEvidenceSurvivesClearReferenceAndReappearanceOfEveryOldVariant() = withDatabase { name ->
        val first = debit()
        val enriched = first.copy(observedAtMs = 200L, bigText = "${first.text} Praça 123.")
        createV1(name, listOf(first, enriched))
        DiagnosticDatabase(context, name, monitoredPackageName = "synthetic.bank").use { helper ->
            helper.clear()
            val store = SqliteMonitorStore(helper)
            val session = store.beginReference(1_000L).session
            org.junit.Assert.assertNotNull(session)
            val confirmed = store.confirmReference(
                ConfirmReference(session!!.id, null, 10_000L, 5_000L, 1_100L),
            )
            assertNull(confirmed.rejection)
            assertEquals(10_000L, store.overview().balanceCents)
            // Existing publication: ignore. Reused key/new postTime: pending.
            helper.append(first.copy(observedAtMs = 2_000L))
            helper.append(first.copy(observedAtMs = 2_100L, postedAtMs = 2_000L))
            // Both the original AND enriched variants must still be protected in new keys.
            helper.append(first.copy(observedAtMs = 2_200L, postedAtMs = 2_100L, key = "copy-original"))
            helper.append(enriched.copy(observedAtMs = 2_300L, postedAtMs = 2_200L, key = "copy-enriched"))
            assertEquals(10_000L, store.overview().balanceCents)
            assertEquals(listOf(FinancialEntryKind.REFERENCE), store.history(limit = 100).map { it.kind })
            val reasons = store.pending(limit = 100).map { it.reason }
            assertEquals(1, reasons.count { it == PendingReason.REUSED_KEY })
            assertEquals(2, reasons.count { it == PendingReason.IDENTICAL_CONTENT })
        }
    }

    private fun assertNoFinancialState(database: SQLiteDatabase) {
        listOf("monitor_references", "monitor_entries", "monitor_pending", "monitor_alert_cycles").forEach {
            assertEquals(0L, database.count(it))
        }
        database.rawQuery("SELECT reference_id,balance_cents FROM monitor_state", null).use {
            assertTrue(it.moveToFirst())
            assertNull(if (it.isNull(0)) null else it.getLong(0))
            assertNull(if (it.isNull(1)) null else it.getLong(1))
        }
    }

    private fun createV1(name: String, events: List<DiagnosticEvent>) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            database.execSQL(
                """CREATE TABLE events(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    observed_at_ms INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                )""",
            )
            events.forEach { event ->
                database.insertOrThrow("events", null, ContentValues().apply {
                    put("observed_at_ms", event.observedAtMs)
                    put("kind", event.type.name)
                    put("payload_json", DiagnosticEventJsonCodec.encode(event))
                })
            }
            database.version = 1
        }
    }

    // Migration uses historical capture evidence, not a new permission/package grant.
    private fun debit() = DiagnosticEvent(
        DiagnosticEventType.POSTED_OR_UPDATED, 100L, postedAtMs = 50L,
        packageName = "synthetic.bank", key = "charge-a", title = "Débito C6 Tag",
        text = "Valor a debitar R$ 16,91.",
    )

    private fun SQLiteDatabase.count(table: String) = scalar("SELECT COUNT(*) FROM $table")

    private fun SQLiteDatabase.scalar(sql: String): Long = rawQuery(sql, null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private fun withDatabase(block: (String) -> Unit) {
        val name = "monitor-migration-${java.util.UUID.randomUUID()}.db"
        try {
            block(name)
        } finally {
            context.deleteDatabase(name)
        }
    }
}
