package br.com.c6tag.diagnostico.monitor.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import br.com.c6tag.diagnostico.monitor.*
import java.util.UUID

internal object MonitorReferenceSql {
    private val policy = ReferencePolicy()

    fun begin(database: SQLiteDatabase, nowMs: Long): MonitorResult {
        val overview = MonitorQueries.overview(database)
        if (nowMs < 0L) return MonitorResult(overview, RejectionCode.INVALID_TIME)
        overview.referenceSession?.let { return MonitorResult(overview, session = it) }
        val session = ReferenceSession(
            UUID.randomUUID().toString(), nowMs,
            MonitorQueries.scalar(database, "SELECT last_observation_id FROM monitor_state WHERE id=1"),
            overview.reference?.id,
        )
        database.insertOrThrow("monitor_reference_sessions", null, ContentValues().apply {
            put("id", session.id)
            put("opened_at_ms", session.openedAtMs)
            put("observation_cursor", session.observationCursor)
            put("base_reference_id", session.baseReferenceId)
            put("status", session.status.name)
        })
        database.execSQL("UPDATE monitor_state SET reference_session_id=? WHERE id=1", arrayOf(session.id))
        MonitorSql.bumpRevision(database, RevisionCause.REFERENCE_SESSION)
        return MonitorResult(MonitorQueries.overview(database), session = session)
    }

    fun confirm(database: SQLiteDatabase, command: ConfirmReference): MonitorResult {
        val overview = MonitorQueries.overview(database)
        val session = MonitorQueries.session(database, command.sessionId)
            ?: return MonitorResult(overview, RejectionCode.SESSION_MISMATCH)
        if (session.status == ReferenceSessionStatus.EXPIRED) return MonitorResult(overview, RejectionCode.SESSION_EXPIRED)
        val nextId = Math.incrementExact(MonitorQueries.scalar(database, "SELECT COALESCE(MAX(id),0) FROM monitor_references"))
        val cursor = MonitorQueries.scalar(database, "SELECT last_observation_id FROM monitor_state WHERE id=1")
        val decision = policy.confirm(
            session, command, overview.reference?.id, nextId, cursor, MonitorQueries.candidates(database),
        )
        if (decision is ReferenceDecision.Rejected) return MonitorResult(overview, decision.code)
        check(decision is ReferenceDecision.Confirmed) { "Unexpected reference decision" }
        val reference = decision.reference
        database.insertOrThrow("monitor_references", null, ContentValues().apply {
            put("id", reference.id)
            put("balance_cents", reference.balanceCents)
            put("confirmed_at_ms", reference.confirmedAtMs)
            put("observation_cursor", reference.observationCursor)
            put("session_opened_at_ms", reference.sessionOpenedAtMs)
            put("cursor_at_session_open", reference.cursorAtSessionOpen)
            put("session_id", reference.sessionId)
        })
        reference.explicitlyIncludedObservationIds.forEach { id ->
            database.execSQL("INSERT INTO monitor_reference_inclusions VALUES(?,?)", arrayOf(reference.id, id))
        }
        database.execSQL("UPDATE monitor_reference_sessions SET status='CONFIRMED' WHERE id=?", arrayOf(session.id))
        database.execSQL(
            "UPDATE monitor_state SET reference_id=?,balance_cents=?,continuity=1,reference_session_id=NULL WHERE id=1",
            arrayOf(reference.id, reference.balanceCents),
        )
        database.execSQL("UPDATE monitor_settings SET limit_cents=? WHERE id=1", arrayOf(decision.limitCents))
        database.execSQL(
            "UPDATE monitor_sources SET status='COVERED',reference_id=?,reference_session_id=NULL " +
                "WHERE status!='APPLIED' AND first_observation_id<=? AND first_observed_at_ms<?",
            arrayOf(reference.id, session.observationCursor, session.openedAtMs),
        )
        decision.coveredObservationIds.forEach { id ->
            database.execSQL(
                "UPDATE monitor_pending SET status='COVERED_BY_REFERENCE',decided_at_ms=? WHERE observation_id=? AND status='OPEN'",
                arrayOf(command.nowMs, id),
            )
        }
        database.execSQL(
            "UPDATE monitor_pending SET reference_id=?,reference_session_id=NULL," +
                "reason=CASE WHEN reason='REFERENCE_IN_PROGRESS' THEN 'CONCURRENT_REFERENCE' ELSE reason END " +
                "WHERE status='OPEN'",
            arrayOf(reference.id),
        )
        database.execSQL(
            "UPDATE monitor_sources SET status=CASE WHEN EXISTS(" +
                "SELECT 1 FROM monitor_pending p WHERE p.source_id=monitor_sources.id AND p.status='OPEN'" +
                ") THEN 'PENDING' ELSE 'COVERED' END,reference_id=?,reference_session_id=NULL " +
                "WHERE status='PROVISIONAL' AND reference_session_id=?",
            arrayOf<Any>(reference.id, session.id),
        )
        database.insertOrThrow("monitor_entries", null, ContentValues().apply {
            put("entry_kind", FinancialEntryKind.REFERENCE.name)
            put("reference_id", reference.id)
            put("amount_cents", reference.balanceCents)
            put("balance_after_cents", reference.balanceCents)
            put("recorded_at_ms", command.nowMs)
        })
        MonitorAlertSql.evaluate(database, command.nowMs, bumpRevision = false)
        MonitorSql.bumpRevision(database, RevisionCause.REFERENCE)
        return MonitorResult(MonitorQueries.overview(database))
    }

    fun expire(database: SQLiteDatabase, nowMs: Long): MonitorResult {
        val overview = MonitorQueries.overview(database)
        if (nowMs < 0L) return MonitorResult(overview, RejectionCode.INVALID_TIME)
        val session = overview.referenceSession ?: return MonitorResult(overview)
        return when (val result = policy.expire(session, nowMs, emptyList())) {
            is ReferenceDecision.Expired -> {
                database.execSQL("UPDATE monitor_reference_sessions SET status='EXPIRED' WHERE id=?", arrayOf(session.id))
                database.execSQL(
                    "UPDATE monitor_state SET reference_session_id=NULL,continuity=0 WHERE id=1",
                )
                database.execSQL(
                    "UPDATE monitor_sources SET status='PENDING',reference_session_id=NULL " +
                        "WHERE status='PROVISIONAL' AND reference_session_id=?", arrayOf(session.id),
                )
                database.execSQL(
                    "UPDATE monitor_pending SET reference_session_id=NULL WHERE reference_session_id=?", arrayOf(session.id),
                )
                MonitorSql.bumpRevision(database, RevisionCause.REFERENCE_SESSION)
                MonitorResult(MonitorQueries.overview(database))
            }
            is ReferenceDecision.Rejected -> MonitorResult(overview, result.code)
            else -> MonitorResult(overview)
        }
    }

    fun cancel(database: SQLiteDatabase, sessionId: String, nowMs: Long, expectedPackageName: String): MonitorResult {
        val overview = MonitorQueries.overview(database)
        val session = MonitorQueries.session(database, sessionId)
            ?: return MonitorResult(overview, RejectionCode.SESSION_MISMATCH)
        if (session.status == ReferenceSessionStatus.EXPIRED) return MonitorResult(overview, RejectionCode.SESSION_EXPIRED)
        val result = policy.cancel(
            session, sessionId, nowMs, overview.reference?.id, overview.continuityPreserved, emptyList(),
        )
        if (result is ReferenceDecision.Rejected) return MonitorResult(overview, result.code)
        check(result is ReferenceDecision.Cancelled) { "Unexpected cancellation decision" }
        database.execSQL("UPDATE monitor_reference_sessions SET status='CANCELLED' WHERE id=?", arrayOf(session.id))
        database.execSQL("UPDATE monitor_state SET reference_session_id=NULL WHERE id=1")
        if (overview.reference != null && overview.continuityPreserved) {
            MonitorSql.reevaluateProvisional(database, session.id, nowMs, expectedPackageName)
        } else {
            database.execSQL(
                "UPDATE monitor_sources SET status='PENDING',reference_session_id=NULL " +
                    "WHERE status='PROVISIONAL' AND reference_session_id=?", arrayOf(session.id),
            )
        }
        database.execSQL(
            "UPDATE monitor_pending SET reference_session_id=NULL WHERE reference_session_id=?", arrayOf(session.id),
        )
        MonitorAlertSql.evaluate(database, nowMs, bumpRevision = false)
        MonitorSql.bumpRevision(database, RevisionCause.REFERENCE_SESSION)
        return MonitorResult(MonitorQueries.overview(database))
    }
}
