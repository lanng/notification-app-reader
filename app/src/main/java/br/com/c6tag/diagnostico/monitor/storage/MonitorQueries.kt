package br.com.c6tag.diagnostico.monitor.storage

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.monitor.alerts.AlertSettings
import br.com.c6tag.diagnostico.monitor.alerts.AlertCycle
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome

internal object MonitorQueries {
    fun revision(database: SQLiteDatabase): MonitorRevision =
        database.rawQuery("SELECT revision,revision_cause FROM monitor_state WHERE id=1", null).use {
            check(it.moveToFirst()) { "Monitor state missing" }
            MonitorRevision(it.getLong(0), RevisionCause.valueOf(it.getString(1)))
        }

    fun overview(database: SQLiteDatabase): MonitorOverview {
        val settings = database.rawQuery("SELECT * FROM monitor_settings WHERE id=1", null).use {
            check(it.moveToFirst()) { "Monitor settings missing" }
            MonitorSettings(
                it.long("limit_cents"),
                AlertSettings(it.int("repeat_minutes"), it.int("pause_minutes")),
                ThemePreference.valueOf(it.string("theme")),
            )
        }
        return database.rawQuery("SELECT * FROM monitor_state WHERE id=1", null).use {
            check(it.moveToFirst()) { "Monitor state missing" }
            MonitorOverview(
                reference = it.nullLong("reference_id")?.let { id -> reference(database, id) },
                balanceCents = it.nullLong("balance_cents"),
                settings = settings,
                continuityPreserved = it.int("continuity") == 1,
                pendingCount = scalar(database, "SELECT COUNT(*) FROM monitor_pending WHERE status='OPEN'"),
                referenceSession = it.nullString("reference_session_id")?.let { id -> session(database, id) },
                alertCycle = alertCycle(database),
                lastObservedAtMs = it.nullLong("last_observed_at_ms"),
                revision = MonitorRevision(it.long("revision"), RevisionCause.valueOf(it.string("revision_cause"))),
            )
        }
    }

    fun alertCycle(database: SQLiteDatabase): AlertCycle? =
        database.rawQuery("SELECT * FROM monitor_alert_cycles ORDER BY rowid DESC LIMIT 1", null).use {
            if (!it.moveToFirst()) null else AlertCycle(
                it.string("id"), AlertStatus.valueOf(it.string("status")), it.long("opened_at_ms"), it.long("version"),
                it.nullLong("last_submitted_at_ms"), it.nullLong("next_reminder_at_ms"), it.nullLong("pause_until_ms"),
                it.nullLong("intent_version")?.let { version ->
                    EmissionIntent(
                        it.string("id"),
                        version,
                        it.long("intent_requested_at_ms"),
                        it.int("intent_critical") == 1,
                    )
                },
                it.nullString("last_submission_outcome")?.let(SubmissionOutcome::valueOf),
                it.int("emission_count"),
                it.int("critical_alerted") == 1,
            )
        }

    fun reference(database: SQLiteDatabase, id: Long): BalanceReference =
        database.rawQuery("SELECT * FROM monitor_references WHERE id=?", arrayOf(id.toString())).use {
            check(it.moveToFirst()) { "Monitor reference missing" }
            val included = database.rawQuery(
                "SELECT observation_id FROM monitor_reference_inclusions WHERE reference_id=?",
                arrayOf(id.toString()),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
            BalanceReference(
                id, it.long("balance_cents"), it.long("confirmed_at_ms"), it.long("observation_cursor"),
                it.long("session_opened_at_ms"), it.long("cursor_at_session_open"), included, it.string("session_id"),
            )
        }

    fun session(database: SQLiteDatabase, id: String): ReferenceSession? =
        database.rawQuery("SELECT * FROM monitor_reference_sessions WHERE id=?", arrayOf(id)).use {
            if (!it.moveToFirst()) null else ReferenceSession(
                it.string("id"), it.long("opened_at_ms"), it.long("observation_cursor"),
                it.nullLong("base_reference_id"), ReferenceSessionStatus.valueOf(it.string("status")),
            )
        }

    fun history(database: SQLiteDatabase, beforeId: Long?, limit: Int, filter: HistoryFilter): List<FinancialEntry> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val kind = when (filter) {
            HistoryFilter.ALL -> null
            HistoryFilter.ENTRIES -> FinancialEntryKind.PIX_CREDIT
            HistoryFilter.EXITS -> FinancialEntryKind.TAG_DEBIT
            HistoryFilter.ADJUSTMENTS -> FinancialEntryKind.REFERENCE
        }
        val conditions = buildList {
            if (beforeId != null) add("id < ?")
            if (kind != null) add("entry_kind = ?")
        }
        val arguments = buildList {
            if (beforeId != null) add(beforeId.toString())
            if (kind != null) add(kind.name)
        }.toTypedArray()
        return database.query(
            "monitor_entries", null, conditions.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            arguments, null, null, "id DESC", limit.toString(),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(FinancialEntry(
                cursor.long("id"), FinancialEntryKind.valueOf(cursor.string("entry_kind")),
                cursor.long("reference_id"), cursor.nullLong("source_id"), cursor.long("amount_cents"),
                cursor.long("balance_after_cents"), cursor.long("recorded_at_ms"), cursor.nullLong("observation_id"),
            ))
        } }
    }

    fun pending(database: SQLiteDatabase, beforeId: Long?, limit: Int): List<PendingMovement> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return database.rawQuery(
            "$PENDING_SELECT WHERE p.status='OPEN' " +
                (if (beforeId != null) "AND p.id < ? " else "") + "ORDER BY p.id DESC LIMIT ?",
            buildList { if (beforeId != null) add(beforeId.toString()); add(limit.toString()) }.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.readPending()) } }
    }

    fun pendingById(database: SQLiteDatabase, id: Long): PendingMovement? =
        database.rawQuery("$PENDING_SELECT WHERE p.id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.readPending() else null
        }

    fun candidates(database: SQLiteDatabase): List<ReferenceCandidate> =
        database.rawQuery(
            "SELECT DISTINCT observation_id,created_at_ms FROM monitor_pending WHERE status='OPEN'", null,
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(ReferenceCandidate(cursor.getLong(0), cursor.getLong(1)))
        } }

    fun scalar(database: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        database.rawQuery(sql, args).use {
            check(it.moveToFirst()) { "Monitor scalar missing" }
            it.getLong(0)
        }

    private fun Cursor.readPending() = PendingMovement(
        long("id"), nullLong("reference_id"), long("source_id"),
        SourceValue(nullString("movement_kind")?.let(MovementKind::valueOf), nullLong("amount_cents")),
        PendingStatus.valueOf(string("status")), int("source_applied") == 1,
        PendingReason.valueOf(string("reason")), long("observation_id"), long("created_at_ms"),
        int("raw_available") == 1,
    )

    private const val PENDING_SELECT = """SELECT p.*,
        EXISTS(SELECT 1 FROM monitor_entries e WHERE e.source_id=p.source_id) AS source_applied,
        EXISTS(SELECT 1 FROM events e WHERE e.id=p.observation_id) AS raw_available FROM monitor_pending p"""
}

internal fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
internal fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
internal fun Cursor.nullLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
internal fun Cursor.nullString(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
