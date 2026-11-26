package br.com.c6tag.diagnostico.monitor.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlertPolicyTest {
    private val policy = AlertPolicy()
    private val settings = AlertSettings()
    private val low = AlertBalance(20_000L, 30_000L)

    @Test
    fun belowLimitOpensCycleWithFirstEmissionButEqualityDoesNot() {
        val result = policy.evaluate(low, settings, null, 1_000L, "cycle-a")
        assertNotNull(result.cycle)
        assertEquals("cycle-a", result.cycle!!.id)
        assertEquals(AlertStatus.ACTIVE, result.cycle.status)
        assertEquals(EmissionIntent("cycle-a", 1L, 1_000L), result.emissionRequested)
        assertEquals(result.emissionRequested, result.cycle.emissionIntent)
        assertNull(result.cycle.lastSubmittedAtMs)
        assertNull(policy.evaluate(low.copy(balanceCents = 30_000L), settings, null, 1_000L, "x").cycle)
    }

    @Test
    fun newDebitInExistingCycleDoesNotRestartCycleOrReminderDeadline() {
        val cycle = submitted()
        val result = evaluate(cycle, 2_000L, low.copy(balanceCents = 19_000L))
        assertEquals(cycle, result.cycle)
        assertNull(result.emissionRequested)
        assertEquals(901_100L, result.cycle!!.nextReminderAtMs)
    }

    @Test
    fun onlySuccessfulSubmissionAdvancesLastSendAndNextDeadline() {
        val cycle = opened()
        val result = policy.recordSubmission(
            cycle, cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_100L, settings,
        )
        assertEquals(1_100L, result.cycle!!.lastSubmittedAtMs)
        assertEquals(901_100L, result.cycle.nextReminderAtMs)
        assertNull(result.cycle.emissionIntent)
        assertNull(result.emissionRequested)
        assertEquals(SubmissionOutcome.ACCEPTED, result.cycle.lastSubmissionOutcome)
    }

    @Test
    fun reminderAtDeadlineCreatesOneIntentNotAnAccumulatedBurst() {
        val cycle = submitted()
        assertNull(evaluate(cycle, 901_099L).emissionRequested)
        val due = evaluate(cycle, 901_100L)
        assertNotNull(due.emissionRequested)
        assertEquals(cycle.id, due.emissionRequested!!.cycleId)
        val late = evaluate(cycle, 9_001_100L)
        assertNotNull(late.emissionRequested)
        assertEquals(9_001_100L, late.emissionRequested!!.requestedAtMs)
        assertEquals(1_100L, late.cycle!!.lastSubmittedAtMs)
        assertEquals(late, evaluate(late.cycle, 9_001_101L))
    }

    @Test
    fun acknowledgmentPausesForThirtyMinutesAndIsIdempotentDuringPause() {
        val cycle = submitted()
        val paused = policy.acknowledge(cycle, cycle.id, 2_000L, settings)
        assertEquals(AlertStatus.PAUSED, paused.cycle!!.status)
        assertEquals(1_802_000L, paused.cycle.pauseUntilMs)
        assertNull(paused.cycle.emissionIntent)
        assertNull(paused.emissionRequested)
        assertEquals(paused, policy.acknowledge(paused.cycle, cycle.id, 3_000L, settings))
        assertNull(evaluate(paused.cycle, 1_801_999L).emissionRequested)
        val expired = evaluate(paused.cycle, 1_802_000L)
        assertEquals(AlertStatus.ACTIVE, expired.cycle!!.status)
        assertNotNull(expired.emissionRequested)
    }

    @Test
    fun insufficientCreditPreservesCycleAndExistingPause() {
        val paused = policy.acknowledge(submitted(), "cycle-a", 2_000L, settings).cycle!!
        assertEquals(paused, evaluate(paused, 3_000L, low.copy(balanceCents = 29_999L)).cycle)
    }

    @Test
    fun trustedRecoveryAtOrAboveLimitClosesAndCancelsOutstandingIntentAndPause() {
        listOf(30_000L, 40_000L).forEach { balance ->
            val paused = policy.acknowledge(opened(), "cycle-a", 2_000L, settings).cycle!!
            val recovered = evaluate(paused, 3_000L, low.copy(balanceCents = balance))
            assertEquals(AlertStatus.CLOSED, recovered.cycle!!.status)
            assertNull(recovered.cycle.pauseUntilMs)
            assertNull(recovered.cycle.emissionIntent)
            assertNull(recovered.cycle.nextReminderAtMs)
            assertNull(recovered.emissionRequested)
        }
    }

    @Test
    fun uncertainRecoveryDoesNotSilentlyCloseExistingCycle() {
        val result = evaluate(submitted(), 3_000L, low.copy(balanceCents = 40_000L, needsReference = true))
        assertEquals(AlertStatus.ACTIVE, result.cycle!!.status)
        assertNull(result.emissionRequested)
    }

    @Test
    fun staleAcknowledgmentAndResumeDoNotAffectCurrentOrClosedCycle() {
        val cycle = submitted()
        assertEquals(AlertEvaluation(cycle), policy.acknowledge(cycle, "old-cycle", 2_000L, settings))
        assertEquals(AlertEvaluation(cycle), policy.resume(cycle, "old-cycle", low, settings, 2_000L))
        val closed = evaluate(cycle, 2_000L, low.copy(balanceCents = 30_000L)).cycle!!
        assertEquals(AlertEvaluation(closed), policy.acknowledge(closed, closed.id, 3_000L, settings))
        assertEquals(AlertEvaluation(closed), policy.resume(closed, closed.id, low, settings, 3_000L))
    }

    @Test
    fun openingAppOrDiscardingNotificationWithoutAcknowledgmentDoesNotPause() {
        val cycle = submitted()
        val result = evaluate(cycle, 901_100L)
        assertEquals(AlertStatus.ACTIVE, result.cycle!!.status)
        assertNull(result.cycle.pauseUntilMs)
        assertNotNull(result.emissionRequested)
    }

    @Test
    fun manualResumeKeepsCycleAndOnlyEmitsWhenRepeatDeadlineAllows() {
        val paused = policy.acknowledge(submitted(), "cycle-a", 2_000L, settings).cycle!!
        val early = policy.resume(paused, paused.id, low, settings, 3_000L)
        assertEquals(paused.id, early.cycle!!.id)
        assertEquals(AlertStatus.ACTIVE, early.cycle.status)
        assertNull(early.cycle.pauseUntilMs)
        assertNull(early.emissionRequested)
        val due = policy.resume(paused, paused.id, low, settings, 901_100L)
        assertNotNull(due.emissionRequested)
        assertEquals(paused.id, due.cycle!!.id)
    }

    @Test
    fun preferenceChangesDoNotExtendExistingPause() {
        val paused = policy.acknowledge(submitted(), "cycle-a", 2_000L, settings).cycle!!
        val changed = policy.evaluate(low, AlertSettings(30, 120), paused, 3_000L, "unused")
        assertEquals(paused.pauseUntilMs, changed.cycle!!.pauseUntilMs)
        assertEquals(1_801_100L, changed.cycle.nextReminderAtMs)
        assertNull(changed.emissionRequested)
    }

    @Test
    fun intervalChangeReusesCycleAndCanOnlyRequestOneOverdueReminder() {
        val cycle = policy.recordSubmission(
            opened(), opened().emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_100L, AlertSettings(60, 30),
        ).cycle!!
        val changed = policy.evaluate(low, settings, cycle, 1_001_100L, "unused")
        assertEquals(cycle.id, changed.cycle!!.id)
        assertNotNull(changed.emissionRequested)
        assertEquals(changed, policy.evaluate(low, settings, changed.cycle, 1_001_101L, "unused"))
    }

    @Test
    fun thresholdChangeCanOpenOrCloseCycleWithoutMovement() {
        assertNotNull(policy.evaluate(low.copy(limitCents = 20_001L), settings, null, 1_000L, "limit").cycle)
        val cycle = opened()
        assertEquals(AlertStatus.CLOSED, evaluate(cycle, 2_000L, low.copy(limitCents = 20_000L)).cycle!!.status)
    }

    @Test
    fun failedOrBlockedSubmissionRetainsIntentWithoutClaimingSendOrCreatingFeedback() {
        SubmissionOutcome.entries.filter { it != SubmissionOutcome.ACCEPTED }.forEach { outcome ->
            val cycle = opened()
            val result = policy.recordSubmission(cycle, cycle.emissionIntent!!, outcome, 1_100L, settings)
            assertEquals(cycle.emissionIntent, result.cycle!!.emissionIntent)
            assertNull(result.cycle.lastSubmittedAtMs)
            assertNull(result.cycle.nextReminderAtMs)
            assertEquals(outcome, result.cycle.lastSubmissionOutcome)
            assertNull(result.emissionRequested)
            assertEquals(cycle.emissionIntent, evaluate(result.cycle, 2_000L).emissionRequested)
        }
    }

    @Test
    fun persistedIntentCanBeRecoveredWithoutCreatingAnotherCycleOrVersion() {
        val cycle = opened()
        val result = evaluate(cycle.copy(), 10_000L)
        assertEquals(cycle, result.cycle)
        assertEquals(cycle.emissionIntent, result.emissionRequested)
    }

    @Test
    fun lateSubmissionCannotResurrectAcknowledgedOrRecoveredCycle() {
        val cycle = opened()
        val paused = policy.acknowledge(cycle, cycle.id, 2_000L, settings).cycle!!
        assertEquals(
            AlertEvaluation(paused),
            policy.recordSubmission(paused, cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 3_000L, settings),
        )
        val closed = evaluate(cycle, 2_000L, low.copy(balanceCents = 30_000L)).cycle!!
        assertEquals(
            AlertEvaluation(closed),
            policy.recordSubmission(closed, cycle.emissionIntent, SubmissionOutcome.ACCEPTED, 3_000L, settings),
        )
    }

    @Test
    fun repeatedOrWrongVersionSubmissionDoesNotAdvanceLastSendTwice() {
        val cycle = opened()
        val sent = policy.recordSubmission(cycle, cycle.emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_100L, settings)
        assertEquals(
            AlertEvaluation(sent.cycle),
            policy.recordSubmission(sent.cycle, cycle.emissionIntent, SubmissionOutcome.ACCEPTED, 2_000L, settings),
        )
        assertEquals(
            AlertEvaluation(cycle),
            policy.recordSubmission(cycle, cycle.emissionIntent.copy(version = 99L),
                SubmissionOutcome.ACCEPTED, 2_000L, settings),
        )
    }

    @Test
    fun nextLowEpisodeUsesNewIdentityAndUnconfiguredBalanceDoesNotOpenCycle() {
        assertNull(policy.evaluate(low.copy(balanceCents = null), settings, null, 1_000L, "x").cycle)
        val closed = evaluate(opened(), 2_000L, low.copy(balanceCents = 30_000L)).cycle!!
        val next = policy.evaluate(low, settings, closed, 3_000L, "cycle-b")
        assertEquals("cycle-b", next.cycle!!.id)
        assertNotEquals(closed.id, next.cycle.id)
        assertNotNull(next.emissionRequested)
    }

    @Test
    fun unsupportedSettingsAndClockRollbackAreRejected() {
        listOf(AlertSettings(1, 30), AlertSettings(15, 1)).forEach {
            assertEquals(AlertRejection.INVALID_SETTINGS, policy.evaluate(low, it, null, 1_000L, "x").rejection)
        }
        assertEquals(AlertRejection.INVALID_TIME, evaluate(opened(), 999L).rejection)
        assertEquals(
            AlertRejection.INVALID_TIME,
            policy.recordSubmission(opened(), opened().emissionIntent!!,
                SubmissionOutcome.ACCEPTED, 999L, settings).rejection,
        )
    }

    @Test
    fun deadlinesDoNotWrapOnOverflow() {
        val cycle = opened()
        assertEquals(
            AlertRejection.OVERFLOW,
            policy.acknowledge(cycle, cycle.id, Long.MAX_VALUE, settings).rejection,
        )
        assertEquals(
            AlertRejection.OVERFLOW,
            policy.recordSubmission(cycle, cycle.emissionIntent!!,
                SubmissionOutcome.ACCEPTED, Long.MAX_VALUE, settings).rejection,
        )
    }

    @Test
    fun escalationToCriticalBypassesRemainingRepeatIntervalAndAttentionPause() {
        val attentionSent = submitted() // balance = 20_000, limit = 30_000, lastSubmittedAt = 1_100, nextReminder = 901_100
        val pausedAttention = policy.acknowledge(attentionSent, attentionSent.id, 2_000L, settings).cycle!!

        // 5 minutes later (still well inside 15-min repeat & 30-min pause), balance drops below half (14_999 < 15_000)
        val critical = evaluate(pausedAttention, 302_000L, AlertBalance(14_999L, 30_000L))
        assertEquals(AlertStatus.ACTIVE, critical.cycle!!.status)
        assertNull(critical.cycle.pauseUntilMs)
        assertNotNull(critical.emissionRequested)
        assertEquals(true, critical.emissionRequested!!.isCritical)

        // Once critical emission is accepted, subsequent critical repeats respect the 15-min interval
        val criticalSent = policy.recordSubmission(
            critical.cycle,
            critical.emissionRequested!!,
            SubmissionOutcome.ACCEPTED,
            302_100L,
            settings,
        ).cycle!!
        assertEquals(true, criticalSent.criticalAlerted)
        assertNull(evaluate(criticalSent, 400_000L, AlertBalance(12_000L, 30_000L)).emissionRequested)
        assertNotNull(evaluate(criticalSent, 302_100L + 900_000L, AlertBalance(12_000L, 30_000L)).emissionRequested)
    }

    private fun opened(): AlertCycle {
        val result = policy.evaluate(low, settings, null, 1_000L, "cycle-a")
        assertNotNull(result.cycle)
        return result.cycle!!
    }

    private fun submitted(): AlertCycle {
        val result = policy.recordSubmission(
            opened(), opened().emissionIntent!!, SubmissionOutcome.ACCEPTED, 1_100L, settings,
        )
        assertNotNull(result.cycle)
        return result.cycle!!
    }

    private fun evaluate(cycle: AlertCycle?, nowMs: Long, balance: AlertBalance = low) =
        policy.evaluate(balance, settings, cycle, nowMs, "unused")
}
