package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePolicyTest {
    private val policy = ReferencePolicy()
    private val session = ReferenceSession("review-a", 1_000L, 10L, 1L)
    private val command = ConfirmReference("review-a", 1L, 10_000L, 30_000L, 1_100L)

    @Test
    fun concurrentCandidateRemainsPendingEvenBelowConfirmedSerialBoundary() {
        val result = confirm(listOf(candidate(9L, 900L), candidate(11L, 1_050L)))
        assertTrue(result is ReferenceDecision.Confirmed)
        result as ReferenceDecision.Confirmed
        assertEquals(10_000L, result.reference.balanceCents)
        assertEquals(setOf(9L), result.coveredObservationIds)
        assertEquals(setOf(11L), result.pendingObservationIds)
        assertEquals(ReferenceSessionStatus.CONFIRMED, result.session.status)
        assertEquals(12L, result.reference.observationCursor)
    }

    @Test
    fun explicitIdsOnlyCoverWhatUserIncludedNotWhatArrivedAfterRendering() {
        val result = confirm(
            listOf(candidate(11L, 1_020L), candidate(12L, 1_090L)),
            command.copy(explicitlyIncludedObservationIds = setOf(11L)),
        ).expect<ReferenceDecision.Confirmed>()
        assertEquals(setOf(11L), result.coveredObservationIds)
        assertEquals(setOf(12L), result.pendingObservationIds)
    }

    @Test
    fun latePersistenceStillBelongsToConcurrentReviewInsteadOfBeingAbsorbedByCursor() {
        val reference = confirm(emptyList()).expect<ReferenceDecision.Confirmed>().reference
        assertEquals(
            ReferencePosition.CONCURRENT,
            policy.position(reference, candidate(13L, 1_050L)),
        )
        assertEquals(
            ReferencePosition.CONCURRENT,
            policy.position(reference, candidate(9L, 1_050L)),
        )
        val event = DiagnosticEvent(
            DiagnosticEventType.POSTED_OR_UPDATED, 1_050L, postedAtMs = 1_030L,
            packageName = "example.bank", key = "late-debit", title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
        )
        assertEquals(
            MovementDecision.Pending(PendingReason.CONCURRENT_REFERENCE),
            MovementPolicy().decide(
                FinancialObservation(13L, event),
                MovementContext("example.bank", reference, 10_000L),
            ),
        )
    }

    @Test
    fun candidateAtOpeningOrConfirmationInstantRemainsConcurrent() {
        val reference = confirm(emptyList()).expect<ReferenceDecision.Confirmed>().reference
        assertEquals(ReferencePosition.CONCURRENT, policy.position(reference, candidate(11L, 1_000L)))
        assertEquals(ReferencePosition.CONCURRENT, policy.position(reference, candidate(13L, 1_100L)))
    }

    @Test
    fun oldObservationIsCoveredNewObservationIsAfterAndDisagreeingBoundariesAreUncertain() {
        val reference = confirm(emptyList()).expect<ReferenceDecision.Confirmed>().reference
        assertEquals(ReferencePosition.COVERED, policy.position(reference, candidate(9L, 900L)))
        assertEquals(ReferencePosition.AFTER, policy.position(reference, candidate(13L, 1_200L)))
        assertEquals(ReferencePosition.UNCERTAIN, policy.position(reference, candidate(13L, 900L)))
        assertEquals(ReferencePosition.CONCURRENT, policy.position(reference, candidate(11L, 1_200L)))
        assertEquals(ReferencePosition.UNCERTAIN, policy.position(reference, candidate(9L, 1_200L)))
    }

    @Test
    fun explicitIncludedIdRemainsCoveredInBoundaryPolicy() {
        val result = confirm(
            listOf(candidate(11L, 1_020L)),
            command.copy(explicitlyIncludedObservationIds = setOf(11L)),
        ).expect<ReferenceDecision.Confirmed>()
        assertEquals(ReferencePosition.COVERED, policy.position(result.reference, candidate(11L, 1_020L)))
    }

    @Test
    fun oldPendingIsAbsorbedByCorrectionWithoutCreatingCreditOrDebit() {
        val result = confirm(
            listOf(candidate(5L, 700L)), command.copy(balanceCents = -100L),
        ).expect<ReferenceDecision.Confirmed>()
        assertEquals(-100L, result.reference.balanceCents)
        assertEquals(setOf(5L), result.coveredObservationIds)
        assertTrue(result.pendingObservationIds.isEmpty())
    }

    @Test
    fun confirmationIsRejectedWhenRepeatedOrSessionIsNotActive() {
        ReferenceSessionStatus.entries.filter { it != ReferenceSessionStatus.ACTIVE }.forEach {
            assertEquals(
                ReferenceDecision.Rejected(RejectionCode.SESSION_NOT_ACTIVE),
                confirm(emptyList(), review = session.copy(status = it)),
            )
        }
    }

    @Test
    fun wrongSessionOrStaleReferenceCannotOverwriteNewReference() {
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.SESSION_MISMATCH),
            confirm(emptyList(), command.copy(sessionId = "review-b")),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.STALE_REFERENCE),
            confirm(emptyList(), currentReferenceId = 2L),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.STALE_REFERENCE),
            confirm(emptyList(), command.copy(expectedReferenceId = 0L)),
        )
    }

    @Test
    fun invalidLimitCursorOrUnknownIncludedIdIsRejectedWithSafeCode() {
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT),
            confirm(emptyList(), command.copy(limitCents = -1L)),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT),
            confirm(emptyList(), cursor = 9L),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.UNKNOWN_OBSERVATION),
            confirm(emptyList(), command.copy(explicitlyIncludedObservationIds = setOf(11L))),
        )
    }

    @Test
    fun expiredSessionCannotBeConfirmedAndClockRollbackIsNotTrusted() {
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.SESSION_EXPIRED),
            confirm(emptyList(), command.copy(nowMs = 901_000L)),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.INVALID_TIME),
            confirm(emptyList(), command.copy(nowMs = 999L)),
        )
    }

    @Test
    fun cancellationReevaluatesProvisionalCandidatesOnlyWithPreviousReferenceAndContinuity() {
        val result = policy.cancel(
            session, session.id, 1_100L, 1L, true, listOf(candidate(11L, 1_050L)),
        ).expect<ReferenceDecision.Cancelled>()
        assertEquals(ReferenceSessionStatus.CANCELLED, result.session.status)
        assertEquals(setOf(11L), result.reevaluateObservationIds)
        assertTrue(result.retainedObservationIds.isEmpty())
    }

    @Test
    fun cancellationWithoutReferenceOrContinuityDoesNotAutomaticallyApplyAnything() {
        listOf(session.copy(baseReferenceId = null), session).forEach { review ->
            val result = policy.cancel(
                review, review.id, 1_100L, review.baseReferenceId, false,
                listOf(candidate(11L, 1_050L)),
            ).expect<ReferenceDecision.Cancelled>()
            assertTrue(result.reevaluateObservationIds.isEmpty())
            assertEquals(setOf(11L), result.retainedObservationIds)
        }
        val unconfigured = policy.cancel(
            session.copy(baseReferenceId = null), session.id, 1_100L, null, true,
            listOf(candidate(11L, 1_050L)),
        ).expect<ReferenceDecision.Cancelled>()
        assertTrue(unconfigured.reevaluateObservationIds.isEmpty())
    }

    @Test
    fun cancellationAlsoRejectsStaleExpiredOrRepeatedCommands() {
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.STALE_REFERENCE),
            policy.cancel(session, session.id, 1_100L, 2L, true, emptyList()),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.SESSION_EXPIRED),
            policy.cancel(session, session.id, 901_000L, 1L, true, emptyList()),
        )
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.SESSION_NOT_ACTIVE),
            policy.cancel(session.copy(status = ReferenceSessionStatus.CANCELLED),
                session.id, 1_100L, 1L, true, emptyList()),
        )
    }

    @Test
    fun expirationAtFifteenMinutesPreservesCandidatesWithoutReapplying() {
        assertEquals(ReferenceDecision.Unchanged, policy.expire(session, 900_999L, emptyList()))
        val result = policy.expire(
            session, 901_000L, listOf(candidate(11L, 1_050L)),
        ).expect<ReferenceDecision.Expired>()
        assertEquals(ReferenceSessionStatus.EXPIRED, result.session.status)
        assertEquals(setOf(11L), result.retainedObservationIds)
        assertEquals(ReferenceDecision.Unchanged, policy.expire(result.session, 902_000L, emptyList()))
    }

    @Test
    fun firstReferenceDoesNotRequirePreviousBalanceOrTurnItIntoIncome() {
        val result = confirm(
            emptyList(), command.copy(expectedReferenceId = null),
            review = session.copy(baseReferenceId = null), currentReferenceId = null,
        ).expect<ReferenceDecision.Confirmed>()
        assertEquals(10_000L, result.reference.balanceCents)
        assertEquals(30_000L, result.limitCents)
    }

    @Test
    fun malformedCandidateIdsOrTimesAreRejectedRatherThanCovered() {
        listOf(
            listOf(candidate(0L, 900L)),
            listOf(candidate(13L, 1_020L)),
            listOf(candidate(11L, -1L)),
            listOf(candidate(11L, 1_200L)),
            listOf(candidate(11L, 1_020L), candidate(11L, 1_030L)),
        ).forEach {
            assertEquals(
                ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT),
                confirm(it),
            )
        }
    }

    @Test
    fun invalidSessionMetadataIsRejectedWithoutOverflowOrExceptions() {
        listOf(
            session.copy(id = ""),
            session.copy(observationCursor = -1L),
        ).forEach {
            assertEquals(
                ReferenceDecision.Rejected(RejectionCode.INVALID_INPUT),
                confirm(emptyList(), review = it),
            )
        }
        assertEquals(
            ReferenceDecision.Rejected(RejectionCode.INVALID_TIME),
            policy.expire(session.copy(openedAtMs = -1L), Long.MAX_VALUE, emptyList()),
        )
    }

    private fun candidate(id: Long, time: Long) = ReferenceCandidate(id, time)

    private inline fun <reified T : ReferenceDecision> ReferenceDecision.expect(): T {
        assertTrue("Expected ${T::class.simpleName}, got $this", this is T)
        return this as T
    }

    private fun confirm(
        candidates: List<ReferenceCandidate>,
        input: ConfirmReference = command,
        review: ReferenceSession = session,
        currentReferenceId: Long? = 1L,
        cursor: Long = 12L,
    ) = policy.confirm(review, input, currentReferenceId, 3L, cursor, candidates)
}
