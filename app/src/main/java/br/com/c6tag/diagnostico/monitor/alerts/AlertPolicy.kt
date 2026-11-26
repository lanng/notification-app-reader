package br.com.c6tag.diagnostico.monitor.alerts

import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import br.com.c6tag.diagnostico.remote.classifySharedAlert

internal class AlertPolicy {
    fun acknowledge(
        cycle: AlertCycle?,
        cycleId: String,
        nowMs: Long,
        settings: AlertSettings,
    ): AlertEvaluation {
        if (cycle == null || cycle.id != cycleId || cycle.status == AlertStatus.CLOSED) {
            return AlertEvaluation(cycle)
        }
        validate(cycle, settings, nowMs)?.let { return AlertEvaluation(cycle, rejection = it) }
        if (cycle.status == AlertStatus.PAUSED && cycle.pauseUntilMs != null && nowMs < cycle.pauseUntilMs) {
            return AlertEvaluation(cycle)
        }
        return checked(cycle) {
            AlertEvaluation(cycle.copy(
                status = AlertStatus.PAUSED,
                version = Math.incrementExact(cycle.version),
                pauseUntilMs = deadline(nowMs, settings.pauseMinutes),
                emissionIntent = null,
            ))
        }
    }

    fun resume(
        cycle: AlertCycle?,
        cycleId: String,
        balance: AlertBalance,
        settings: AlertSettings,
        nowMs: Long,
    ): AlertEvaluation {
        if (cycle == null || cycle.id != cycleId || cycle.status == AlertStatus.CLOSED) {
            return AlertEvaluation(cycle)
        }
        validate(cycle, settings, nowMs)?.let { return AlertEvaluation(cycle, rejection = it) }
        return checked(cycle) {
            val resumed = if (cycle.status == AlertStatus.PAUSED) {
                cycle.copy(
                    status = AlertStatus.ACTIVE, pauseUntilMs = null,
                    version = Math.incrementExact(cycle.version),
                )
            } else {
                cycle
            }
            evaluate(balance, settings, resumed, nowMs, cycle.id)
        }
    }

    fun recordSubmission(
        cycle: AlertCycle?,
        intent: EmissionIntent,
        outcome: SubmissionOutcome,
        nowMs: Long,
        settings: AlertSettings,
    ): AlertEvaluation {
        if (
            cycle == null || cycle.status != AlertStatus.ACTIVE || cycle.id != intent.cycleId ||
            cycle.emissionIntent != intent || cycle.version != intent.version
        ) return AlertEvaluation(cycle)
        validate(cycle, settings, nowMs)?.let { return AlertEvaluation(cycle, rejection = it) }
        // Returning no new emission here prevents a submission result from being a retry trigger.
        if (outcome != SubmissionOutcome.ACCEPTED) {
            return AlertEvaluation(cycle.copy(lastSubmissionOutcome = outcome))
        }
        return checked(cycle) {
            AlertEvaluation(cycle.copy(
                version = Math.incrementExact(cycle.version),
                lastSubmittedAtMs = nowMs,
                nextReminderAtMs = deadline(nowMs, settings.repeatMinutes),
                emissionIntent = null,
                lastSubmissionOutcome = outcome,
                emissionCount = cycle.emissionCount + 1,
                criticalAlerted = cycle.criticalAlerted || intent.isCritical,
            ))
        }
    }

    fun evaluate(
        balance: AlertBalance,
        settings: AlertSettings,
        cycle: AlertCycle?,
        nowMs: Long,
        newCycleId: String,
    ): AlertEvaluation {
        validate(cycle, settings, nowMs)?.let { return AlertEvaluation(cycle, rejection = it) }
        if (balance.limitCents < 0L) return AlertEvaluation(cycle, rejection = AlertRejection.INVALID_SETTINGS)
        val cents = balance.balanceCents ?: return AlertEvaluation(cycle)
        val low = cents < balance.limitCents
        val isCritical = classifySharedAlert(cents, balance.limitCents) == SharedAlertSeverity.CRITICAL
        if (cycle == null || cycle.status == AlertStatus.CLOSED) {
            if (!low) return AlertEvaluation(cycle)
            if (newCycleId.isBlank() || newCycleId == cycle?.id) {
                return AlertEvaluation(cycle, rejection = AlertRejection.INVALID_CYCLE)
            }
            val intent = EmissionIntent(newCycleId, 1L, nowMs, isCritical = isCritical)
            return AlertEvaluation(
                AlertCycle(newCycleId, AlertStatus.ACTIVE, nowMs, emissionIntent = intent), intent,
            )
        }
        return checked(cycle) {
            if (!low && !balance.needsReference) {
                return@checked AlertEvaluation(cycle.copy(
                    status = AlertStatus.CLOSED,
                    version = Math.incrementExact(cycle.version),
                    pauseUntilMs = null, emissionIntent = null, nextReminderAtMs = null,
                ))
            }
            val escalatedToCritical = isCritical && !cycle.criticalAlerted
            var current = cycle.copy(
                nextReminderAtMs = cycle.lastSubmittedAtMs?.let { deadline(it, settings.repeatMinutes) },
            )
            if (current.status == AlertStatus.PAUSED) {
                if (!escalatedToCritical && current.pauseUntilMs != null && nowMs < current.pauseUntilMs) {
                    return@checked AlertEvaluation(current)
                }
                current = current.copy(
                    status = AlertStatus.ACTIVE, pauseUntilMs = null,
                    version = Math.incrementExact(current.version),
                )
            }
            current.emissionIntent?.let { existingIntent ->
                if (!escalatedToCritical || existingIntent.isCritical) {
                    return@checked AlertEvaluation(current, existingIntent)
                }
            }
            if (!escalatedToCritical && current.nextReminderAtMs != null && nowMs < current.nextReminderAtMs) {
                return@checked AlertEvaluation(current)
            }
            val version = Math.incrementExact(current.version)
            val intent = EmissionIntent(current.id, version, nowMs, isCritical = isCritical)
            AlertEvaluation(current.copy(version = version, emissionIntent = intent), intent)
        }
    }

    private fun validate(cycle: AlertCycle?, settings: AlertSettings, nowMs: Long): AlertRejection? {
        if (settings.repeatMinutes !in REPEAT_MINUTES || settings.pauseMinutes !in PAUSE_MINUTES) {
            return AlertRejection.INVALID_SETTINGS
        }
        if (
            nowMs < 0L || (cycle != null && (
                cycle.openedAtMs < 0L || nowMs < cycle.openedAtMs ||
                    nowMs < (cycle.lastSubmittedAtMs ?: cycle.openedAtMs) ||
                    nowMs < (cycle.emissionIntent?.requestedAtMs ?: cycle.openedAtMs)
                ))
        ) return AlertRejection.INVALID_TIME
        if (cycle != null && (cycle.id.isBlank() || cycle.version < 1L)) {
            return AlertRejection.INVALID_CYCLE
        }
        return null
    }

    private inline fun checked(cycle: AlertCycle?, block: () -> AlertEvaluation): AlertEvaluation = try {
        block()
    } catch (_: ArithmeticException) {
        AlertEvaluation(cycle, rejection = AlertRejection.OVERFLOW)
    }

    private fun deadline(nowMs: Long, minutes: Int): Long =
        Math.addExact(nowMs, minutes.toLong() * 60_000L)

    private companion object {
        val REPEAT_MINUTES = setOf(15, 30, 60)
        val PAUSE_MINUTES = setOf(15, 30, 60, 120)
    }
}
