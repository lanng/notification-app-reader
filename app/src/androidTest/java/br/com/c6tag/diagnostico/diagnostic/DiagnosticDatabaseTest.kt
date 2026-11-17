package br.com.c6tag.diagnostico.diagnostic

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticDatabaseTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun duplicateKeysGetIndependentIdsAndEventsSurviveReopen() {
        withDatabase("duplicate-reopen") { name ->
            val event = interpretedNotification(key = "same-key")
            val first = DiagnosticDatabase(context, name)
            val firstId = first.append(event)
            val secondId = first.append(event)
            first.close()

            val reopened = DiagnosticDatabase(context, name)
            try {
                val stored = reopened.page(limit = 10)
                assertNotEquals(firstId, secondId)
                assertEquals(listOf(secondId, firstId), stored.map(StoredDiagnosticEvent::id))
                assertEquals(listOf(event, event), stored.map(StoredDiagnosticEvent::event))
                assertEquals(listOf("same-key", "same-key"), stored.map { it.event.key })
                assertEquals(
                    listOf(CandidateKind.TAG_DEBIT, CandidateKind.TAG_DEBIT),
                    stored.map { it.event.interpretation?.kind },
                )
                assertEquals(listOf(1691L, 1691L), stored.map { it.event.interpretation?.amountCents })
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun sameKeyAndInterpretationAtDifferentObservationTimesSurviveReopen() {
        withDatabase("same-key-distinct-observations") { name ->
            val earlier = interpretedNotification(
                key = "same-key",
                observedAtMs = 1_800_000_000_100L,
            )
            val later = interpretedNotification(
                key = "same-key",
                observedAtMs = 1_800_000_000_200L,
            )
            val first = DiagnosticDatabase(context, name)
            val earlierId = first.append(earlier)
            val laterId = first.append(later)
            first.close()

            val reopened = DiagnosticDatabase(context, name)
            try {
                val stored = reopened.page(limit = 10)

                assertEquals(2, stored.size)
                assertNotEquals(earlierId, laterId)
                assertEquals(listOf(laterId, earlierId), stored.map(StoredDiagnosticEvent::id))
                assertEquals(listOf(later, earlier), stored.map(StoredDiagnosticEvent::event))
                assertEquals(listOf(1_800_000_000_200L, 1_800_000_000_100L), stored.map { it.event.observedAtMs })
                assertEquals(listOf("same-key", "same-key"), stored.map { it.event.key })
                assertEquals(listOf("Débito C6 Tag", "Débito C6 Tag"), stored.map { it.event.title })
                assertEquals(
                    listOf("Valor a debitar R$ 16,91.", "Valor a debitar R$ 16,91."),
                    stored.map { it.event.text },
                )
                assertEquals(
                    listOf(CandidateKind.TAG_DEBIT, CandidateKind.TAG_DEBIT),
                    stored.map { it.event.interpretation?.kind },
                )
                assertEquals(listOf(1691L, 1691L), stored.map { it.event.interpretation?.amountCents })
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun nullableUnicodeAndAllPayloadFieldsRoundTripWithoutMixingMetadata() {
        withDatabase("payload-roundtrip") { name ->
            val database = DiagnosticDatabase(context, name)
            try {
                val richEvent = DiagnosticEvent(
                    type = DiagnosticEventType.POSTED_OR_UPDATED,
                    observedAtMs = 1_800_000_000_123L,
                    postedAtMs = 1_799_999_000_456L,
                    packageName = "synthetic.package",
                    key = "chave \"citada\"\nlinha dois",
                    notificationId = 42,
                    tag = "tag – ação",
                    groupKey = "grupo\nseparado",
                    groupSummary = false,
                    title = "Título “fictício”\nlinha",
                    text = null,
                    bigText = ("texto longo com ç, aspas \" e newline\n").repeat(4_000),
                    lines = listOf("linha 1", "ação €", "linha \"3\"\ncontinuação"),
                    removalReason = null,
                    interpretation = NotificationInterpretation(
                        kind = CandidateKind.PIX_CREDIT,
                        amountCents = 123456L,
                    ),
                )
                val nullableEvent = DiagnosticEvent(
                    type = DiagnosticEventType.REMOVED,
                    observedAtMs = 1_800_000_000_999L,
                    key = null,
                    notificationId = null,
                    tag = null,
                    groupKey = null,
                    groupSummary = null,
                    title = null,
                    text = null,
                    bigText = null,
                    lines = emptyList(),
                    removalReason = 7,
                    interpretation = null,
                )
                val snapshotEvent = richEvent.copy(
                    type = DiagnosticEventType.ACTIVE_SNAPSHOT,
                    key = "snapshot-key",
                )

                val richId = database.append(richEvent)
                val nullableId = database.append(nullableEvent)
                val snapshotId = database.append(snapshotEvent)

                val stored = database.page(limit = 10).associateBy(StoredDiagnosticEvent::id)
                assertEquals(richEvent, stored.getValue(richId).event)
                assertEquals(nullableEvent, stored.getValue(nullableId).event)
                assertEquals(snapshotEvent, stored.getValue(snapshotId).event)
                assertEquals(DiagnosticEventType.POSTED_OR_UPDATED, stored.getValue(richId).event.type)
                assertEquals(DiagnosticEventType.ACTIVE_SNAPSHOT, stored.getValue(snapshotId).event.type)
                assertEquals(CandidateKind.PIX_CREDIT, stored.getValue(richId).event.interpretation?.kind)
                assertEquals("chave \"citada\"\nlinha dois", stored.getValue(richId).event.key)
                assertEquals(7, stored.getValue(nullableId).event.removalReason)
                assertNull(stored.getValue(nullableId).event.interpretation)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun paginationUsesDescendingExclusiveCursorValidatesLimitsAndClearKeepsIdsMonotonic() {
        withDatabase("pagination-clear") { name ->
            val database = DiagnosticDatabase(context, name)
            try {
                val ids = (0..2).map { index ->
                    database.append(notification(key = "page-$index"))
                }

                val firstPage = database.page(limit = 2)
                val secondPage = database.page(beforeId = firstPage.last().id, limit = 2)
                assertEquals(listOf(ids[2], ids[1]), firstPage.map(StoredDiagnosticEvent::id))
                assertEquals(listOf(ids[0]), secondPage.map(StoredDiagnosticEvent::id))
                assertThrows(IllegalArgumentException::class.java) {
                    database.page(limit = 0)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    database.page(limit = 101)
                }

                database.clear()
                assertTrue(database.page(limit = 10).isEmpty())
                assertTrue(database.append(notification(key = "after-clear")) > ids.last())
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun exportSnapshotReportsEmptyAndOrderedMetadataAndClosesResourcesAfterCallbackFailure() {
        withDatabase("snapshot") { name ->
            val database = DiagnosticDatabase(context, name)
            try {
                var emptyMetadata: DiagnosticSnapshotMetadata? = null
                var emptyEvents: List<StoredDiagnosticEvent> = emptyList()
                database.exportSnapshot { metadata, events ->
                    emptyMetadata = metadata
                    emptyEvents = events.toList()
                }
                assertEquals(DiagnosticSnapshotMetadata(total = 0L, minId = null, maxId = null), emptyMetadata)
                assertTrue(emptyEvents.isEmpty())

                val ids = (0..1).map { index ->
                    database.append(notification(key = "snapshot-$index"))
                }
                var metadata: DiagnosticSnapshotMetadata? = null
                var events: List<StoredDiagnosticEvent> = emptyList()
                database.exportSnapshot { snapshotMetadata, snapshotEvents ->
                    metadata = snapshotMetadata
                    events = snapshotEvents.toList()
                }
                assertEquals(
                    DiagnosticSnapshotMetadata(total = 2L, minId = ids.first(), maxId = ids.last()),
                    metadata,
                )
                assertEquals(listOf(ids.last(), ids.first()), events.map(StoredDiagnosticEvent::id))

                assertThrows(IllegalStateException::class.java) {
                    database.exportSnapshot { _, snapshotEvents ->
                        snapshotEvents.first()
                        throw IllegalStateException("synthetic callback failure")
                    }
                }
                assertEquals(2, database.page(limit = 10).size)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun unsupportedSchemaAndPayloadVersionsFailInsteadOfBeingTreatedAsEmpty() {
        withDatabase("unsupported-schema") { name ->
            val rawDatabase = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            rawDatabase.execSQL(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    observed_at_ms INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                )
                """.trimIndent(),
            )
            rawDatabase.version = 3
            rawDatabase.close()

            val database = DiagnosticDatabase(context, name)
            try {
                assertThrows(IllegalStateException::class.java) {
                    database.page(limit = 10)
                }
            } finally {
                database.close()
            }
        }

        withDatabase("unsupported-payload") { name ->
            val rawDatabase = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            rawDatabase.execSQL(
                """
                CREATE TABLE events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    observed_at_ms INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                )
                """.trimIndent(),
            )
            rawDatabase.execSQL(
                "INSERT INTO events (observed_at_ms, kind, payload_json) " +
                    "VALUES (1, 'POSTED_OR_UPDATED', '{\"codecVersion\":99}')",
            )
            rawDatabase.version = 1
            rawDatabase.close()

            val database = DiagnosticDatabase(context, name)
            try {
                assertThrows(IllegalStateException::class.java) {
                    database.page(limit = 10)
                }
            } finally {
                database.close()
            }
        }
    }

    private fun notification(
        key: String,
    ) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = 1_800_000_000_000L,
        packageName = "synthetic.package",
        key = key,
        title = "Notificação de teste",
        text = "Conteúdo sintético sem dados pessoais",
    )

    private fun interpretedNotification(
        key: String,
        observedAtMs: Long = 1_800_000_000_000L,
    ) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = observedAtMs,
        packageName = "synthetic.package",
        key = key,
        title = "Débito C6 Tag",
        text = "Valor a debitar R$ 16,91.",
        interpretation = NotificationInterpretation(
            kind = CandidateKind.TAG_DEBIT,
            amountCents = 1691L,
        ),
    )

    private inline fun withDatabase(name: String, block: (String) -> Unit) {
        val databaseName = "diagnostic-test-$name.db"
        context.deleteDatabase(databaseName)
        try {
            block(databaseName)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
