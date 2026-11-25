package br.com.c6tag.diagnostico.remote.sync

import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestAlertPolicyTest {
    private val policy = GuestAlertPolicy()
    private val t0 = 1_764_000_000_000L
    private val min = 60_000L

    private fun snapshot(
        balanceCents: Long? = 35_000L,
        limitCents: Long = 30_000L,
        repeatMinutes: Int = 15,
        severity: SharedAlertSeverity = SharedAlertSeverity.NORMAL,
        alertCycleId: String? = "cycle-1",
        alertOpenedAtMs: Long? = t0,
        alertEmissionCount: Int = 1,
        guestAlertActive: Boolean = false,
        guestAlertEligibleAtMs: Long? = null,
    ) = SharedMonitorSnapshot(
        monitorId = "11111111-1111-4111-8111-111111111111",
        hostGeneration = "1",
        balanceCents = balanceCents,
        limitCents = limitCents,
        repeatMinutes = repeatMinutes,
        pauseMinutes = 30,
        needsReference = false,
        severity = severity,
        alertCycleId = alertCycleId,
        alertOpenedAtMs = alertOpenedAtMs,
        alertEmissionCount = alertEmissionCount,
        guestAlertActive = guestAlertActive,
        guestAlertEligibleAtMs = guestAlertEligibleAtMs,
        lastObservedAtMs = t0,
        referenceConfirmedAtMs = t0,
        syncedAtMs = t0,
        snapshotVersion = 1L,
        serverTimeMs = t0,
    )

    @Test
    fun normalBalanceClearsAnyActiveGuestNotificationAndState() {
        val state = GuestLocalAlertState(
            cycleKey = "cycle-1",
            pausedSeverity = SharedAlertSeverity.ATTENTION,
            pausedUntilMs = t0 + 30 * min,
            lastNotifiedAtMs = t0,
            lastNotifiedSeverity = SharedAlertSeverity.ATTENTION,
        )
        val decision = policy.evaluate(snapshot(severity = SharedAlertSeverity.NORMAL), state, t0 + 5 * min)

        assertFalse(decision.shouldNotify)
        assertTrue(decision.shouldCancelNotification)
        assertNull(decision.nextCheckAtMs)
        assertNull(decision.updatedState.cycleKey)
        assertNull(decision.updatedState.pausedUntilMs)
    }

    @Test
    fun attentionFirstAlertStaysHostOnlyAndSchedulesEligibilityCheck() {
        val snap = snapshot(
            balanceCents = 29_999L,
            severity = SharedAlertSeverity.ATTENTION,
            alertEmissionCount = 1,
            guestAlertActive = false,
            guestAlertEligibleAtMs = t0 + 15 * min,
        )
        val decision = policy.evaluate(snap, GuestLocalAlertState(), t0 + 1 * min)

        assertFalse(decision.shouldNotify)
        assertTrue(decision.shouldCancelNotification)
        assertEquals(t0 + 15 * min, decision.nextCheckAtMs)
    }

    @Test
    fun attentionBecomesActiveAfterRepeatIntervalAndRepeatsEveryRepeatMinutes() {
        val snap = snapshot(
            balanceCents = 29_999L,
            repeatMinutes = 15,
            severity = SharedAlertSeverity.ATTENTION,
            alertEmissionCount = 1,
            guestAlertActive = false,
            guestAlertEligibleAtMs = t0 + 15 * min,
        )

        val firstGuestAlert = policy.evaluate(snap, GuestLocalAlertState(), t0 + 15 * min)
        assertTrue(firstGuestAlert.shouldNotify)
        assertEquals(t0 + 30 * min, firstGuestAlert.nextCheckAtMs)

        // Before 15 minutes pass, do not duplicate
        val tooSoon = policy.evaluate(snap, firstGuestAlert.updatedState, t0 + 25 * min)
        assertFalse(tooSoon.shouldNotify)
        assertEquals(t0 + 30 * min, tooSoon.nextCheckAtMs)

        // At 15 minutes later, repeat
        val secondGuestAlert = policy.evaluate(snap, tooSoon.updatedState, t0 + 30 * min)
        assertTrue(secondGuestAlert.shouldNotify)
        assertEquals(t0 + 45 * min, secondGuestAlert.nextCheckAtMs)
    }

    @Test
    fun criticalNotifiesGuestImmediatelyEvenOnFirstEmission() {
        val snap = snapshot(
            balanceCents = 14_999L,
            severity = SharedAlertSeverity.CRITICAL,
            alertEmissionCount = 1,
            guestAlertActive = true,
            guestAlertEligibleAtMs = t0,
        )
        val decision = policy.evaluate(snap, GuestLocalAlertState(), t0)

        assertTrue(decision.shouldNotify)
        assertEquals(SharedAlertSeverity.CRITICAL, decision.updatedState.lastNotifiedSeverity)
        assertEquals(t0 + 15 * min, decision.nextCheckAtMs)
    }

    @Test
    fun guestPauseIsLocalAndOverriddenImmediatelyOnEscalationToCritical() {
        val attentionSnap = snapshot(
            balanceCents = 20_000L,
            severity = SharedAlertSeverity.ATTENTION,
            alertEmissionCount = 2,
            guestAlertActive = true,
        )
        val fired = policy.evaluate(attentionSnap, GuestLocalAlertState(pauseMinutes = 30), t0)
        assertTrue(fired.shouldNotify)

        val paused = policy.acknowledge(attentionSnap, fired.updatedState, t0 + 1 * min)
        assertFalse(paused.shouldNotify)
        assertTrue(paused.shouldCancelNotification)
        assertEquals(t0 + 31 * min, paused.updatedState.pausedUntilMs)

        // Still paused during Attention
        val whilePaused = policy.evaluate(attentionSnap, paused.updatedState, t0 + 20 * min)
        assertFalse(whilePaused.shouldNotify)

        // Escalation to Critical wakes Guest IMMEDIATELY despite Attention pause!
        val criticalSnap = attentionSnap.copy(
            balanceCents = 14_999L,
            severity = SharedAlertSeverity.CRITICAL,
        )
        val escalated = policy.evaluate(criticalSnap, whilePaused.updatedState, t0 + 21 * min)
        assertTrue(escalated.shouldNotify)
        assertNull(escalated.updatedState.pausedUntilMs)
        assertEquals(SharedAlertSeverity.CRITICAL, escalated.updatedState.lastNotifiedSeverity)

        // Pausing DURING Critical respects local pause
        val pausedCritical = policy.acknowledge(criticalSnap, escalated.updatedState, t0 + 22 * min)
        assertTrue(pausedCritical.shouldCancelNotification)
        val stillPausedCritical = policy.evaluate(criticalSnap, pausedCritical.updatedState, t0 + 40 * min)
        assertFalse(stillPausedCritical.shouldNotify)

        // Manual resume fires immediately
        val resumed = policy.resume(criticalSnap, stillPausedCritical.updatedState, t0 + 41 * min)
        assertTrue(resumed.shouldNotify)
    }
}
