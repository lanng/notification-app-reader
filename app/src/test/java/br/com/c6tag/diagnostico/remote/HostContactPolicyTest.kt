package br.com.c6tag.diagnostico.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class HostContactPolicyTest {
    @Test
    fun contactIsRecentImmediatelyBeforeOneHourAndStaleAtOneHour() {
        assertEquals(
            HostContactState.RECENT,
            hostContactState(lastContactAtMs = 1_000L, nowMs = 3_600_999L),
        )
        assertEquals(
            HostContactState.STALE,
            hostContactState(lastContactAtMs = 1_000L, nowMs = 3_601_000L),
        )
    }

    @Test
    fun missingContactIsUnconfirmedForValidCurrentTimes() {
        assertEquals(
            HostContactState.UNCONFIRMED,
            hostContactState(lastContactAtMs = null, nowMs = 0L),
        )
        assertEquals(
            HostContactState.UNCONFIRMED,
            hostContactState(lastContactAtMs = null, nowMs = Long.MAX_VALUE),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeCurrentTimeIsRejectedEvenWithoutContact() {
        hostContactState(lastContactAtMs = null, nowMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeContactTimeIsRejected() {
        hostContactState(lastContactAtMs = -1L, nowMs = 0L)
    }

    @Test
    fun futureContactIsClockUncertainAndEqualTimesAreRecent() {
        assertEquals(
            HostContactState.CLOCK_UNCERTAIN,
            hostContactState(lastContactAtMs = 2_000L, nowMs = 1_999L),
        )
        assertEquals(
            HostContactState.RECENT,
            hostContactState(lastContactAtMs = 2_000L, nowMs = 2_000L),
        )
    }

    @Test
    fun zeroContactWithMaximumCurrentTimeIsSafelyStale() {
        assertEquals(
            HostContactState.STALE,
            hostContactState(lastContactAtMs = 0L, nowMs = Long.MAX_VALUE),
        )
    }

    @Test
    fun recentAndStaleBoundariesNearMaximumTimeDoNotOverflow() {
        assertEquals(
            HostContactState.RECENT,
            hostContactState(lastContactAtMs = Long.MAX_VALUE - 3_599_999L, nowMs = Long.MAX_VALUE),
        )
        assertEquals(
            HostContactState.STALE,
            hostContactState(lastContactAtMs = Long.MAX_VALUE - 3_600_000L, nowMs = Long.MAX_VALUE),
        )
    }

    @Test
    fun repeatedEvaluationOfSameContactIsIdempotent() {
        val first = hostContactState(lastContactAtMs = 1_000L, nowMs = 2_000L)
        val second = hostContactState(lastContactAtMs = 1_000L, nowMs = 2_000L)

        assertEquals(HostContactState.RECENT, first)
        assertEquals(first, second)
    }
}
