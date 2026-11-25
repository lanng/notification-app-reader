package br.com.c6tag.diagnostico.monitor.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.monitor.alerts.*
import java.util.UUID

internal object MonitorAlertSql {
    private val policy = AlertPolicy()

    fun evaluate(database: SQLiteDatabase, nowMs: Long, bumpRevision: Boolean = true): AlertEvaluation {
        val overview = MonitorQueries.overview(database)
        val result = policy.evaluate(
            overview.alertBalance(), overview.settings.alerts, overview.alertCycle, nowMs, UUID.randomUUID().toString(),
        )
        persist(database, overview.alertCycle, result, if (bumpRevision) RevisionCause.ALERT_EVALUATION else null)
        return result
    }

    fun refresh(database: SQLiteDatabase, nowMs: Long): MonitorResult {
        val evaluation = evaluate(database, nowMs)
        return MonitorResult(MonitorQueries.overview(database), evaluation.rejection?.toCode())
    }

    fun acknowledge(database: SQLiteDatabase, cycleId: String, nowMs: Long): MonitorResult {
        val overview = MonitorQueries.overview(database)
        val result = policy.acknowledge(overview.alertCycle, cycleId, nowMs, overview.settings.alerts)
        persist(database, overview.alertCycle, result, RevisionCause.ACKNOWLEDGMENT)
        return MonitorResult(MonitorQueries.overview(database), result.rejection?.toCode())
    }

    fun resume(database: SQLiteDatabase, cycleId: String, nowMs: Long): MonitorResult {
        val overview = MonitorQueries.overview(database)
        val result = policy.resume(overview.alertCycle, cycleId, overview.alertBalance(), overview.settings.alerts, nowMs)
        persist(database, overview.alertCycle, result, RevisionCause.ACKNOWLEDGMENT)
        return MonitorResult(MonitorQueries.overview(database), result.rejection?.toCode())
    }

    fun recordSubmission(database: SQLiteDatabase, command: RecordSubmission): MonitorResult {
        val overview = MonitorQueries.overview(database)
        val result = policy.recordSubmission(
            overview.alertCycle, command.intent, command.outcome, command.nowMs, overview.settings.alerts,
        )
        persist(database, overview.alertCycle, result, RevisionCause.SUBMISSION)
        return MonitorResult(MonitorQueries.overview(database), result.rejection?.toCode())
    }

    fun changeSettings(database: SQLiteDatabase, command: ChangeSettings): MonitorResult {
        val overview = MonitorQueries.overview(database)
        if (overview.reference?.id != command.expectedReferenceId) return MonitorResult(overview, RejectionCode.STALE_REFERENCE)
        if (command.nowMs < 0L || command.nowMs < (overview.reference?.confirmedAtMs ?: 0L)) {
            return MonitorResult(overview, RejectionCode.INVALID_TIME)
        }
        val settings = command.settings
        if (
            settings.limitCents < 0L || settings.alerts.repeatMinutes !in setOf(15, 30, 60) ||
            settings.alerts.pauseMinutes !in setOf(15, 30, 60, 120)
        ) return MonitorResult(overview, RejectionCode.INVALID_INPUT)
        if (settings == overview.settings) return MonitorResult(overview)
        val appearanceOnly = settings.copy(theme = overview.settings.theme) == overview.settings
        database.update("monitor_settings", ContentValues().apply {
            put("limit_cents", settings.limitCents)
            put("repeat_minutes", settings.alerts.repeatMinutes)
            put("pause_minutes", settings.alerts.pauseMinutes)
            put("theme", settings.theme.name)
        }, "id=1", null)
        if (!appearanceOnly) evaluate(database, command.nowMs, bumpRevision = false)
        MonitorSql.bumpRevision(database, if (appearanceOnly) RevisionCause.APPEARANCE else RevisionCause.SETTINGS)
        return MonitorResult(MonitorQueries.overview(database))
    }

    private fun MonitorOverview.alertBalance() = AlertBalance(balanceCents, settings.limitCents, needsReference)

    private fun persist(
        database: SQLiteDatabase, previous: AlertCycle?, result: AlertEvaluation, cause: RevisionCause?,
    ) {
        if (result.rejection != null || result.cycle == null || result.cycle == previous) return
        val cycle = result.cycle
        val values = ContentValues().apply {
            put("id", cycle.id)
            put("status", cycle.status.name)
            put("opened_at_ms", cycle.openedAtMs)
            put("version", cycle.version)
            put("last_submitted_at_ms", cycle.lastSubmittedAtMs)
            put("next_reminder_at_ms", cycle.nextReminderAtMs)
            put("pause_until_ms", cycle.pauseUntilMs)
            put("intent_version", cycle.emissionIntent?.version)
            put("intent_requested_at_ms", cycle.emissionIntent?.requestedAtMs)
            put("last_submission_outcome", cycle.lastSubmissionOutcome?.name)
            put("emission_count", cycle.emissionCount)
        }
        if (previous?.id == cycle.id) {
            check(database.update("monitor_alert_cycles", values, "id=?", arrayOf(cycle.id)) == 1)
        } else {
            database.insertOrThrow("monitor_alert_cycles", null, values)
        }
        if (cause != null) MonitorSql.bumpRevision(database, cause)
    }

    private fun AlertRejection.toCode(): RejectionCode = when (this) {
        AlertRejection.INVALID_TIME -> RejectionCode.INVALID_TIME
        AlertRejection.OVERFLOW -> RejectionCode.TIME_OVERFLOW
        else -> RejectionCode.INVALID_INPUT
    }
}
