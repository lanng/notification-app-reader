package br.com.c6tag.diagnostico.monitor.alerts

internal data class AlertSettings(val repeatMinutes: Int = 15, val pauseMinutes: Int = 30)

internal data class AlertBalance(
    val balanceCents: Long?,
    val limitCents: Long,
    val needsReference: Boolean = false,
)

internal enum class AlertStatus { ACTIVE, PAUSED, CLOSED }

internal data class EmissionIntent(val cycleId: String, val version: Long, val requestedAtMs: Long)

internal data class AlertCycle(
    val id: String,
    val status: AlertStatus,
    val openedAtMs: Long,
    val version: Long = 1L,
    val lastSubmittedAtMs: Long? = null,
    val nextReminderAtMs: Long? = null,
    val pauseUntilMs: Long? = null,
    val emissionIntent: EmissionIntent? = null,
    val lastSubmissionOutcome: SubmissionOutcome? = null,
)

// ACCEPTED means the platform call succeeded, not that sound or reading occurred.
internal enum class SubmissionOutcome { ACCEPTED, PERMISSION_DENIED, CHANNEL_BLOCKED, FAILED }
internal enum class AlertRejection { INVALID_SETTINGS, INVALID_TIME, INVALID_CYCLE, OVERFLOW }

internal data class AlertEvaluation(
    val cycle: AlertCycle?,
    val emissionRequested: EmissionIntent? = null,
    val rejection: AlertRejection? = null,
)
