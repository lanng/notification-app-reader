package br.com.c6tag.diagnostico.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertSoundSessionTest {
    @Test
    fun matchingOccurrenceStopDisablesActiveAudio() {
        val session = AlertSoundSession(occurrenceId = "occurrence-1", startedAtElapsedMs = 1_000L)

        assertTrue(session.isActive(nowElapsedMs = 1_000L))
        assertFalse(session.stop(expectedOccurrenceId = "occurrence-1").isActive(nowElapsedMs = 1_000L))
    }

    @Test
    fun oldOccurrenceStopCannotStopNewSound() {
        val session = AlertSoundSession(occurrenceId = "new-occurrence", startedAtElapsedMs = 1_000L)

        assertSame(session, session.stop(expectedOccurrenceId = "old-occurrence"))
        assertTrue(session.isActive(nowElapsedMs = 1_000L))
    }

    @Test
    fun stoppedSessionStaysInactiveAndMatchingStopIsIdempotent() {
        val stopped = AlertSoundSession("occurrence-1", 1_000L).stop("occurrence-1")

        assertTrue(stopped.stopped)
        assertFalse(stopped.isActive(nowElapsedMs = 1_001L))
        assertEquals(stopped, stopped.stop(expectedOccurrenceId = "occurrence-1"))
    }

    @Test
    fun activeWindowIncludesStartAndTheMillisecondBeforeExpiry() {
        val session = AlertSoundSession("occurrence-1", 1_000L)

        assertTrue(session.isActive(nowElapsedMs = 1_000L))
        assertTrue(session.isActive(nowElapsedMs = 30_999L))
    }

    @Test
    fun activeWindowEndsAtThirtySecondsAndRemainsInactiveLater() {
        val session = AlertSoundSession("occurrence-1", 1_000L)

        assertFalse(session.isActive(nowElapsedMs = 31_000L))
        assertFalse(session.isActive(nowElapsedMs = 31_001L))
    }

    @Test
    fun timeBeforeStartAndNegativeTimeAreInactive() {
        val session = AlertSoundSession("occurrence-1", 1_000L)

        assertFalse(session.isActive(nowElapsedMs = 999L))
        assertFalse(AlertSoundSession("occurrence-2", 0L).isActive(nowElapsedMs = -1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankOccurrenceIdIsRejected() {
        AlertSoundSession(occurrenceId = "", startedAtElapsedMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun whitespaceOccurrenceIdIsRejected() {
        AlertSoundSession(occurrenceId = " \t\n ", startedAtElapsedMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeStartTimeIsRejected() {
        AlertSoundSession(occurrenceId = "occurrence-1", startedAtElapsedMs = -1L)
    }

    @Test
    fun newIdentityCanBeActiveAfterOldIdentityWasStopped() {
        val oldSession = AlertSoundSession("old-occurrence", 1_000L).stop("old-occurrence")
        val newSession = AlertSoundSession("new-occurrence", 1_001L)

        assertFalse(oldSession.isActive(nowElapsedMs = 1_001L))
        assertTrue(newSession.isActive(nowElapsedMs = 1_001L))
    }

    @Test
    fun maximumElapsedTimesDoNotOverflow() {
        assertTrue(AlertSoundSession("at-max", Long.MAX_VALUE).isActive(nowElapsedMs = Long.MAX_VALUE))
        assertTrue(
            AlertSoundSession("near-max", Long.MAX_VALUE - 29_999L)
                .isActive(nowElapsedMs = Long.MAX_VALUE),
        )
        assertFalse(
            AlertSoundSession("expired-near-max", Long.MAX_VALUE - 30_000L)
                .isActive(nowElapsedMs = Long.MAX_VALUE),
        )
    }
}
