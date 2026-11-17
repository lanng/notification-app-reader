package br.com.c6tag.diagnostico.monitor.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.monitor.MovementPolicy
import br.com.c6tag.diagnostico.monitor.MovementKind
import br.com.c6tag.diagnostico.monitor.SourceValue
import br.com.c6tag.diagnostico.monitor.*

/** Financial writes share the caller's transaction; no platform effects belong here. */
internal object MonitorSql {
    private val movementPolicy = MovementPolicy()

    fun bumpRevision(database: SQLiteDatabase, cause: RevisionCause) {
        val next = Math.incrementExact(MonitorQueries.revision(database).value)
        database.execSQL(
            "UPDATE monitor_state SET revision=?,revision_cause=? WHERE id=1",
            arrayOf<Any>(next, cause.name),
        )
    }

    fun projectObservation(
        database: SQLiteDatabase,
        observationId: Long,
        event: DiagnosticEvent,
        expectedPackageName: String,
        nowMs: Long,
    ) {
        if (event.type == DiagnosticEventType.LISTENER_DISCONNECTED) {
            val overview = MonitorQueries.overview(database)
            if (overview.reference != null && overview.continuityPreserved) {
                database.execSQL("UPDATE monitor_state SET continuity=0 WHERE id=1")
                bumpRevision(database, RevisionCause.OBSERVATION)
            }
        }
        val evidence = movementPolicy.evidence(event)
        val sources = history(database, event.packageName, event.key, evidence)
        val sourceId = rememberSource(database, observationId, event) ?: return
        if (expectedPackageName.isBlank() || event.packageName != expectedPackageName) return
        val overview = MonitorQueries.overview(database)
        val decision = movementPolicy.decide(
            FinancialObservation(observationId, event),
            MovementContext(
                expectedPackageName, overview.reference, overview.balanceCents ?: 0L,
                sources, overview.referenceSession != null,
            ),
        )
        when (decision) {
            is MovementDecision.Apply -> apply(database, sourceId, observationId, event.observedAtMs, overview.reference!!.id, decision)
            is MovementDecision.Pending -> addPending(
                database, sourceId, observationId, event.observedAtMs, overview, evidence!!, decision.reason,
            )
            MovementDecision.Ignore -> Unit
        }
        MonitorAlertSql.evaluate(database, nowMs, bumpRevision = false)
        bumpRevision(database, RevisionCause.OBSERVATION)
    }

    fun history(
        database: SQLiteDatabase, packageName: String?, key: String?, evidence: SourceEvidence?,
        excludingSourceId: Long = -1L,
    ): SourceHistory {
        if (evidence == null) return SourceHistory()
        val publication = evidence.publication
        val matched = publication?.let {
            database.rawQuery(
                "SELECT movement_kind,amount_cents FROM monitor_sources " +
                    "WHERE package_name=? AND notification_key=? AND posted_at_ms=? AND id!=?",
                arrayOf(it.packageName, it.key, it.postedAtMs.toString(), excludingSourceId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) SourceValue(
                    cursor.nullString("movement_kind")?.let(MovementKind::valueOf), cursor.nullLong("amount_cents"),
                ) else null
            }
        }
        val sameKey = MonitorQueries.scalar(
            database, "SELECT COUNT(*) FROM monitor_sources WHERE package_name=? AND notification_key=? AND id!=?",
            arrayOf(packageName.orEmpty(), key.orEmpty(), excludingSourceId.toString()),
        ) > 0L
        val sameContent = MonitorQueries.scalar(
            database, "SELECT COUNT(*) FROM monitor_source_evidence e JOIN monitor_sources s ON s.id=e.source_id " +
                "WHERE s.package_name=? AND e.content_hash=? AND s.id!=?",
            arrayOf(packageName.orEmpty(), evidence.contentHash, excludingSourceId.toString()),
        ) > 0L
        return SourceHistory(matched, sameKey, sameContent)
    }

    fun reevaluateProvisional(database: SQLiteDatabase, sessionId: String, nowMs: Long, expectedPackageName: String) {
        // Freeze only the session's IDs before changing the selection predicate.
        // No raw text or full financial history is materialized.
        val sourceIds = database.rawQuery(
            "SELECT id FROM monitor_sources WHERE status='PROVISIONAL' AND reference_session_id=? " +
                "ORDER BY first_observation_id ASC",
            arrayOf(sessionId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        sourceIds.forEach { sourceId ->
            database.rawQuery(
                "SELECT s.*,e.content_hash FROM monitor_sources s JOIN monitor_source_evidence e " +
                    "ON e.source_id=s.id AND e.first_observation_id=s.first_observation_id WHERE s.id=?",
                arrayOf(sourceId.toString()),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "Provisional source evidence missing" }
                val key = cursor.nullString("notification_key")
                val packageName = cursor.nullString("package_name")
                val postedAt = cursor.nullLong("posted_at_ms")
                val publication = if (!packageName.isNullOrBlank() && !key.isNullOrBlank() && postedAt != null && postedAt > 0L) {
                    PublicationIdentity(packageName, key, postedAt)
                } else null
                val evidence = SourceEvidence(
                    publication,
                    SourceValue(cursor.nullString("movement_kind")?.let(MovementKind::valueOf), cursor.nullLong("amount_cents")),
                    cursor.string("content_hash"),
                )
                val candidate = FinancialCandidate(
                    cursor.long("first_observation_id"), DiagnosticEventType.valueOf(cursor.string("first_event_type")),
                    cursor.long("first_observed_at_ms"), packageName, evidence,
                )
                val overview = MonitorQueries.overview(database)
                val decision = if (cursor.int("conflicting") == 1) {
                    MovementDecision.Pending(PendingReason.CONFLICTING_PUBLICATION)
                } else movementPolicy.decideCandidate(candidate, MovementContext(
                    expectedPackageName, overview.reference, overview.balanceCents ?: 0L,
                    history(database, packageName, key, evidence, excludingSourceId = sourceId),
                ))
                database.execSQL(
                    "UPDATE monitor_pending SET status=?,decided_at_ms=? " +
                        "WHERE source_id=? AND reason='REFERENCE_IN_PROGRESS' AND status='OPEN'",
                    arrayOf<Any>(if (decision is MovementDecision.Apply) "APPLIED" else "NOT_COUNTED", nowMs, sourceId),
                )
                database.execSQL("UPDATE monitor_sources SET status='IGNORED',reference_session_id=NULL WHERE id=?", arrayOf(sourceId))
                when (decision) {
                    is MovementDecision.Apply -> apply(database, sourceId, candidate.observationId, nowMs, overview.reference!!.id, decision)
                    is MovementDecision.Pending -> addPending(
                        database, sourceId, candidate.observationId, candidate.observedAtMs, overview, evidence, decision.reason,
                    )
                    MovementDecision.Ignore -> Unit
                }
            }
        }
    }

    fun resolve(database: SQLiteDatabase, command: ResolvePending): MonitorResult {
        val overview = MonitorQueries.overview(database)
        if (command.expectedReferenceId != overview.reference?.id) {
            return MonitorResult(overview, RejectionCode.STALE_REFERENCE)
        }
        if (command.nowMs < 0L || command.nowMs < (overview.reference?.confirmedAtMs ?: 0L)) {
            return MonitorResult(overview, RejectionCode.INVALID_TIME)
        }
        if (overview.referenceSession != null) return MonitorResult(overview, RejectionCode.REFERENCE_IN_PROGRESS)
        val pending = MonitorQueries.pendingById(database, command.pendingId)
            ?: return MonitorResult(overview, RejectionCode.INVALID_INPUT)
        val decision = movementPolicy.resolve(pending, command, overview.reference?.id, overview.balanceCents ?: 0L)
        when (decision) {
            is PendingResolution.Rejected -> return MonitorResult(overview, decision.code)
            PendingResolution.AlreadyResolved -> return MonitorResult(overview)
            is PendingResolution.Applied -> apply(
                database, pending.sourceId, pending.observationId, command.nowMs, overview.reference!!.id, decision.movement,
            )
            PendingResolution.NotCounted -> Unit
        }
        database.execSQL(
            "UPDATE monitor_pending SET status=?,decided_at_ms=? WHERE id=?",
            arrayOf<Any>(if (decision is PendingResolution.Applied) "APPLIED" else "NOT_COUNTED", command.nowMs, pending.id),
        )
        MonitorAlertSql.evaluate(database, command.nowMs, bumpRevision = false)
        bumpRevision(database, RevisionCause.PENDING_RESOLUTION)
        return MonitorResult(MonitorQueries.overview(database))
    }

    fun startProcessSession(database: SQLiteDatabase, sessionId: String, nowMs: Long): MonitorResult {
        val overview = MonitorQueries.overview(database)
        if (sessionId.isBlank()) return MonitorResult(overview, RejectionCode.INVALID_INPUT)
        if (nowMs < 0L) return MonitorResult(overview, RejectionCode.INVALID_TIME)
        val previous = database.rawQuery("SELECT process_session_id FROM monitor_state WHERE id=1", null).use {
            check(it.moveToFirst())
            if (it.isNull(0)) null else it.getString(0)
        }
        if (previous == sessionId) return MonitorResult(overview)
        database.execSQL(
            "UPDATE monitor_state SET process_session_id=?,continuity=0 WHERE id=1", arrayOf(sessionId),
        )
        bumpRevision(database, RevisionCause.PROCESS_SESSION)
        return MonitorResult(MonitorQueries.overview(database))
    }

    fun apply(
        database: SQLiteDatabase, sourceId: Long, observationId: Long, recordedAtMs: Long,
        referenceId: Long, decision: MovementDecision.Apply,
    ) {
        database.insertOrThrow("monitor_entries", null, ContentValues().apply {
            put("entry_kind", decision.kind.name)
            put("reference_id", referenceId)
            put("source_id", sourceId)
            put("amount_cents", decision.amountCents)
            put("balance_after_cents", decision.balanceCents)
            put("recorded_at_ms", recordedAtMs)
            put("observation_id", observationId)
        })
        database.execSQL("UPDATE monitor_state SET balance_cents=? WHERE id=1", arrayOf(decision.balanceCents))
        database.execSQL(
            "UPDATE monitor_sources SET status='APPLIED',reference_id=?,reference_session_id=NULL WHERE id=?",
            arrayOf(referenceId, sourceId),
        )
    }

    fun addPending(
        database: SQLiteDatabase, sourceId: Long, observationId: Long, observedAtMs: Long,
        overview: MonitorOverview, evidence: SourceEvidence, reason: PendingReason,
    ) {
        val deduplicationKey = "$sourceId:${reason.name}:${evidence.contentHash}"
        if (MonitorQueries.scalar(
            database, "SELECT COUNT(*) FROM monitor_pending WHERE deduplication_key=?", arrayOf(deduplicationKey),
        ) == 0L) {
            database.insertOrThrow("monitor_pending", null, ContentValues().apply {
                put("source_id", sourceId)
                put("observation_id", observationId)
                put("deduplication_key", deduplicationKey)
                put("reason", reason.name)
                put("reference_id", overview.reference?.id)
                put("reference_session_id", overview.referenceSession?.id)
                put("movement_kind", evidence.value.kind?.name)
                put("amount_cents", evidence.value.amountCents)
                put("created_at_ms", observedAtMs)
            })
        }
        database.execSQL(
            "UPDATE monitor_sources SET status=?,reference_session_id=? WHERE id=? AND status NOT IN('APPLIED','COVERED')",
            arrayOf<Any?>(if (overview.referenceSession != null) "PROVISIONAL" else "PENDING",
                overview.referenceSession?.id, sourceId),
        )
    }

    // Also used by migration: record identity only, never reinterpret history as money.
    fun rememberSource(database: SQLiteDatabase, observationId: Long, event: DiagnosticEvent): Long? {
        check(database.inTransaction()) { "Monitor identity requires a transaction" }
        val evidence = movementPolicy.evidence(event) ?: return null
        val publication = evidence.publication
        var existingValue: SourceValue? = null
        val existingId = publication?.let {
            database.rawQuery(
                "SELECT id,movement_kind,amount_cents FROM monitor_sources " +
                    "WHERE package_name=? AND notification_key=? AND posted_at_ms=?",
                arrayOf(it.packageName, it.key, it.postedAtMs.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                existingValue = SourceValue(
                    if (cursor.isNull(1)) null else MovementKind.valueOf(cursor.getString(1)),
                    if (cursor.isNull(2)) null else cursor.getLong(2),
                )
                cursor.getLong(0)
            }
        }
        val sourceId = if (existingId == null) {
            database.insertOrThrow("monitor_sources", null, ContentValues().apply {
                put("package_name", event.packageName)
                put("notification_key", event.key)
                put("posted_at_ms", event.postedAtMs)
                put("movement_kind", evidence.value.kind?.name)
                put("amount_cents", evidence.value.amountCents)
                put("first_observation_id", observationId)
                put("last_observation_id", observationId)
                put("first_observed_at_ms", event.observedAtMs)
                put("last_observed_at_ms", event.observedAtMs)
                put("first_event_type", event.type.name)
            })
        } else {
            database.execSQL(
                "UPDATE monitor_sources SET last_observation_id=?,last_observed_at_ms=?," +
                    "conflicting=MAX(conflicting,?) WHERE id=?",
                arrayOf(observationId, event.observedAtMs, if (existingValue != evidence.value) 1 else 0, existingId),
            )
            existingId
        }
        val values = ContentValues().apply {
            put("source_id", sourceId)
            put("content_hash", evidence.contentHash)
            put("movement_kind", evidence.value.kind?.name)
            put("amount_cents", evidence.value.amountCents)
            put("first_observation_id", observationId)
            put("last_observation_id", observationId)
        }
        val variantExists = database.rawQuery(
            "SELECT 1 FROM monitor_source_evidence WHERE source_id=? AND content_hash=?",
            arrayOf(sourceId.toString(), evidence.contentHash),
        ).use { it.moveToFirst() }
        if (variantExists) {
            database.execSQL(
                "UPDATE monitor_source_evidence SET last_observation_id=? WHERE source_id=? AND content_hash=?",
                arrayOf<Any>(observationId, sourceId, evidence.contentHash),
            )
        } else {
            // Do not use INSERT OR IGNORE: an integrity/storage failure is not deduplication.
            database.insertOrThrow("monitor_source_evidence", null, values)
        }
        return sourceId
    }
}
