package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.remote.SharedAlertSeverity

internal data class GuestLocalAlertState(
    val cycleKey: String? = null,
    val pausedSeverity: SharedAlertSeverity? = null,
    val pausedUntilMs: Long? = null,
    val lastNotifiedAtMs: Long? = null,
    val lastNotifiedSeverity: SharedAlertSeverity? = null,
    val pauseMinutes: Int = 30,
) {
    fun isPausedAt(nowMs: Long): Boolean =
        pausedUntilMs != null && nowMs < pausedUntilMs
}

internal data class GuestAlertDecision(
    val shouldNotify: Boolean,
    val shouldCancelNotification: Boolean,
    val nextCheckAtMs: Long?,
    val updatedState: GuestLocalAlertState,
)

/**
 * Pure, deterministic alert policy for Guest/Support devices.
 *
 * Rules:
 * - Normal / Unknown: clears any active Guest alert and local pause.
 * - Attention: Guest is silent on the Host's 1st alert; becomes active on the 2nd alert
 *   or once `guestAlertEligibleAtMs` elapses, then repeats every `repeatMinutes`.
 * - Critical: Guest is alerted immediately (even on 1st alert), wakes up if previously
 *   paused on Attention, and repeats every `repeatMinutes`.
 * - Pause ("Entendi"): strictly local to this Guest device; Host pause never silences Guest.
 */
internal class GuestAlertPolicy {
    fun isGuestAlertActiveAt(snapshot: SharedMonitorSnapshot, nowMs: Long): Boolean =
        when (snapshot.severity) {
            SharedAlertSeverity.CRITICAL -> true
            SharedAlertSeverity.ATTENTION -> snapshot.guestAlertActive ||
                (snapshot.guestAlertEligibleAtMs != null && nowMs >= snapshot.guestAlertEligibleAtMs)

            SharedAlertSeverity.NORMAL,
            SharedAlertSeverity.UNKNOWN -> false
        }

    fun evaluate(
        snapshot: SharedMonitorSnapshot?,
        localState: GuestLocalAlertState,
        nowMs: Long,
    ): GuestAlertDecision {
        if (snapshot == null) {
            return clearedDecision(localState, nextCheckAtMs = null)
        }

        if (!isGuestAlertActiveAt(snapshot, nowMs)) {
            val nextEligible = if (snapshot.severity == SharedAlertSeverity.ATTENTION) {
                snapshot.guestAlertEligibleAtMs?.takeIf { it > nowMs }
            } else {
                null
            }
            return clearedDecision(localState, nextCheckAtMs = nextEligible)
        }

        val cycleKey = cycleKeyFor(snapshot)
        var current = if (localState.cycleKey != cycleKey) {
            localState.copy(
                cycleKey = cycleKey,
                pausedSeverity = null,
                pausedUntilMs = null,
                lastNotifiedAtMs = null,
                lastNotifiedSeverity = null,
            )
        } else {
            localState
        }

        // Critical severity immediately overrides a local pause set during Attention.
        if (
            snapshot.severity == SharedAlertSeverity.CRITICAL &&
            current.pausedSeverity == SharedAlertSeverity.ATTENTION
        ) {
            current = current.copy(
                pausedSeverity = null,
                pausedUntilMs = null,
            )
        }

        val pausedUntil = current.pausedUntilMs
        if (pausedUntil != null && nowMs < pausedUntil) {
            return GuestAlertDecision(
                shouldNotify = false,
                shouldCancelNotification = false,
                nextCheckAtMs = pausedUntil,
                updatedState = current,
            )
        }

        if (pausedUntil != null) {
            current = current.copy(pausedSeverity = null, pausedUntilMs = null)
        }

        val repeatMs = snapshot.repeatMinutes.coerceIn(15, 60).toLong() * 60_000L
        val escalatedToCritical =
            snapshot.severity == SharedAlertSeverity.CRITICAL &&
                current.lastNotifiedSeverity != SharedAlertSeverity.CRITICAL
        val lastNotified = current.lastNotifiedAtMs
        val intervalDue = lastNotified == null || nowMs >= lastNotified + repeatMs

        return if (escalatedToCritical || intervalDue) {
            val updated = current.copy(
                lastNotifiedAtMs = nowMs,
                lastNotifiedSeverity = snapshot.severity,
            )
            GuestAlertDecision(
                shouldNotify = true,
                shouldCancelNotification = false,
                nextCheckAtMs = nowMs + repeatMs,
                updatedState = updated,
            )
        } else {
            GuestAlertDecision(
                shouldNotify = false,
                shouldCancelNotification = false,
                nextCheckAtMs = lastNotified + repeatMs,
                updatedState = current,
            )
        }
    }

    fun acknowledge(
        snapshot: SharedMonitorSnapshot?,
        localState: GuestLocalAlertState,
        nowMs: Long,
    ): GuestAlertDecision {
        if (snapshot == null || !isGuestAlertActiveAt(snapshot, nowMs)) {
            return clearedDecision(localState, nextCheckAtMs = null)
        }
        val pauseMinutes = localState.pauseMinutes.takeIf { it in SUPPORTED_PAUSE_MINUTES } ?: 30
        val pausedUntilMs = nowMs + pauseMinutes.toLong() * 60_000L
        val updated = localState.copy(
            cycleKey = cycleKeyFor(snapshot),
            pausedSeverity = snapshot.severity,
            pausedUntilMs = pausedUntilMs,
        )
        return GuestAlertDecision(
            shouldNotify = false,
            shouldCancelNotification = true,
            nextCheckAtMs = pausedUntilMs,
            updatedState = updated,
        )
    }

    fun resume(
        snapshot: SharedMonitorSnapshot?,
        localState: GuestLocalAlertState,
        nowMs: Long,
    ): GuestAlertDecision {
        val clearedPause = localState.copy(
            pausedSeverity = null,
            pausedUntilMs = null,
            lastNotifiedAtMs = null,
        )
        return evaluate(snapshot, clearedPause, nowMs)
    }

    private fun clearedDecision(
        localState: GuestLocalAlertState,
        nextCheckAtMs: Long?,
    ): GuestAlertDecision = GuestAlertDecision(
        shouldNotify = false,
        shouldCancelNotification = true,
        nextCheckAtMs = nextCheckAtMs,
        updatedState = localState.copy(
            cycleKey = null,
            pausedSeverity = null,
            pausedUntilMs = null,
            lastNotifiedAtMs = null,
            lastNotifiedSeverity = null,
        ),
    )

    private fun cycleKeyFor(snapshot: SharedMonitorSnapshot): String =
        snapshot.alertCycleId?.takeIf(String::isNotBlank)
            ?: "open-${snapshot.alertOpenedAtMs ?: snapshot.syncedAtMs}"

    companion object {
        val SUPPORTED_PAUSE_MINUTES = setOf(15, 30, 60, 120)
    }
}
