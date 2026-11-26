package br.com.c6tag.diagnostico.diagnostic

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import br.com.c6tag.diagnostico.BuildConfig
import br.com.c6tag.diagnostico.monitor.MonitorRevision
import br.com.c6tag.diagnostico.monitor.storage.MonitorQueries
import br.com.c6tag.diagnostico.monitor.storage.MonitorReferenceSql
import br.com.c6tag.diagnostico.monitor.storage.MonitorSchema
import br.com.c6tag.diagnostico.monitor.storage.MonitorSql
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Private, append-only-until-cleared SQLite storage for diagnostic events. */
internal class DiagnosticDatabase(
    context: Context,
    name: String = DEFAULT_DATABASE_NAME,
    internal val monitoredPackageName: String = BuildConfig.C6_PACKAGE_NAME,
    internal val monitorClock: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(
    context.applicationContext,
    name,
    null,
    SCHEMA_VERSION,
    DatabaseErrorHandler {
        throw IllegalStateException("Diagnostic database is corrupt")
    },
), DiagnosticStore {
    private val mutableMonitorRevisions = MutableStateFlow(MonitorRevision())
    internal val monitorRevisions = mutableMonitorRevisions.asStateFlow()

    override fun onOpen(database: SQLiteDatabase) {
        mutableMonitorRevisions.value = MonitorQueries.revision(database)
    }

    internal fun <T> monitorRead(block: (SQLiteDatabase) -> T): T =
        readableDatabase.transaction(exclusive = false) { block(this) }

    internal fun <T> monitorWrite(block: (SQLiteDatabase) -> T): T {
        val database = writableDatabase
        check(!database.inTransaction()) { "Monitor writes require an outer transaction" }
        val result = database.transaction { block(this) }
        mutableMonitorRevisions.value = MonitorQueries.revision(database)
        return result
    }

    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                observed_at_ms INTEGER NOT NULL,
                kind TEXT NOT NULL,
                payload_json TEXT NOT NULL
            )
            """.trimIndent(),
        )
        MonitorSchema.create(database)
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        check(oldVersion in 1 until newVersion && newVersion == SCHEMA_VERSION) {
            "Unsupported diagnostic database upgrade"
        }
        if (oldVersion == 1) {
            MonitorSchema.create(database)
            // SQLiteOpenHelper wraps the entire upgrade in a transaction. Decode by cursor;
            // a corrupt row must abort that transaction instead of masquerading as empty history.
            database.rawQuery(
                "SELECT id,observed_at_ms,kind,payload_json FROM events ORDER BY id ASC", null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val stored = cursor.readStoredEvent()
                    MonitorSql.rememberSource(database, stored.id, stored.event)
                    database.execSQL(
                        "UPDATE monitor_state SET last_observation_id=?,last_observed_at_ms=? WHERE id=1",
                        arrayOf(stored.id, stored.event.observedAtMs),
                    )
                }
            }
        } else {
            if (oldVersion < 3) {
                database.execSQL(
                    "ALTER TABLE monitor_alert_cycles ADD COLUMN emission_count INTEGER NOT NULL DEFAULT 0 CHECK(emission_count>=0)",
                )
            }
            if (oldVersion < 4) {
                database.execSQL(
                    "ALTER TABLE monitor_alert_cycles ADD COLUMN critical_alerted INTEGER NOT NULL DEFAULT 0 CHECK(critical_alerted IN(0,1))",
                )
                database.execSQL(
                    "ALTER TABLE monitor_alert_cycles ADD COLUMN intent_critical INTEGER NOT NULL DEFAULT 0 CHECK(intent_critical IN(0,1))",
                )
            }
        }
    }

    override fun onDowngrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        throw IllegalStateException("Unsupported diagnostic database downgrade")
    }

    override fun append(event: DiagnosticEvent): Long {
        val values = ContentValues().apply {
            put(COLUMN_OBSERVED_AT, event.observedAtMs)
            put(COLUMN_KIND, event.type.name)
            put(COLUMN_PAYLOAD, DiagnosticEventJsonCodec.encode(event))
        }
        return monitorWrite { database ->
            val nowMs = monitorClock()
            MonitorReferenceSql.expire(database, nowMs)
            val id = database.insertOrThrow(TABLE_EVENTS, null, values)
            MonitorSql.projectObservation(database, id, event, monitoredPackageName, nowMs)
            database.execSQL(
                "UPDATE monitor_state SET last_observation_id=?,last_observed_at_ms=? WHERE id=1",
                arrayOf(id, event.observedAtMs),
            )
            id
        }
    }

    override fun page(
        beforeId: Long?,
        limit: Int,
    ): List<StoredDiagnosticEvent> {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }

        val cursor = readableDatabase.query(
            TABLE_EVENTS,
            EVENT_COLUMNS,
            beforeId?.let { "$COLUMN_ID < ?" },
            beforeId?.let { arrayOf(it.toString()) },
            null,
            null,
            "$COLUMN_ID DESC",
            limit.toString(),
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(it.readStoredEvent())
                }
            }
        }
    }

    override fun clear() {
        writableDatabase.delete(TABLE_EVENTS, null, null)
    }

    override fun <T> exportSnapshot(
        block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
    ): T {
        val database = writableDatabase
        database.beginTransactionNonExclusive()
        try {
            val metadata = database.rawQuery(
                "SELECT COUNT(*), MIN($COLUMN_ID), MAX($COLUMN_ID) FROM $TABLE_EVENTS",
                null,
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Could not read diagnostic snapshot metadata" }
                DiagnosticSnapshotMetadata(
                    total = cursor.getLong(0),
                    minId = if (cursor.isNull(1)) null else cursor.getLong(1),
                    maxId = if (cursor.isNull(2)) null else cursor.getLong(2),
                )
            }

            database.rawQuery(
                "SELECT $COLUMN_ID, $COLUMN_OBSERVED_AT, $COLUMN_KIND, $COLUMN_PAYLOAD " +
                    "FROM $TABLE_EVENTS ORDER BY $COLUMN_ID DESC",
                null,
            ).use { cursor ->
                var sequenceConsumed = false
                val events = sequence {
                    check(!sequenceConsumed) { "Snapshot sequence can be consumed only once" }
                    sequenceConsumed = true
                    while (cursor.moveToNext()) {
                        yield(cursor.readStoredEvent())
                    }
                }
                val result = block(metadata, events)
                database.setTransactionSuccessful()
                return result
            }
        } finally {
            database.endTransaction()
        }
    }

    private fun Cursor.readStoredEvent(): StoredDiagnosticEvent {
        val id = getLong(getColumnIndexOrThrow(COLUMN_ID))
        val observedAtMs = getLong(getColumnIndexOrThrow(COLUMN_OBSERVED_AT))
        val kind = getString(getColumnIndexOrThrow(COLUMN_KIND))
        val payload = getString(getColumnIndexOrThrow(COLUMN_PAYLOAD))
        val event = DiagnosticEventJsonCodec.decode(payload)
        check(event.observedAtMs == observedAtMs && event.type.name == kind) {
            "Diagnostic row metadata does not match its payload"
        }
        return StoredDiagnosticEvent(id = id, event = event)
    }

    private companion object {
        const val DEFAULT_DATABASE_NAME = "diagnostic_history.db"
        const val SCHEMA_VERSION = 4
        const val TABLE_EVENTS = "events"
        const val COLUMN_ID = "id"
        const val COLUMN_OBSERVED_AT = "observed_at_ms"
        const val COLUMN_KIND = "kind"
        const val COLUMN_PAYLOAD = "payload_json"
        const val MAX_PAGE_SIZE = 100

        val EVENT_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_OBSERVED_AT,
            COLUMN_KIND,
            COLUMN_PAYLOAD,
        )
    }
}
