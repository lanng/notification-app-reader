package br.com.c6tag.diagnostico.monitor

import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovementPolicyTest {
    private val policy = MovementPolicy()

    @Test
    fun newDebitAfterReferenceChangesBalanceInIntegerCents() {
        assertEquals(
            MovementDecision.Apply(MovementKind.TAG_DEBIT, 1_691L, 8_309L),
            policy.decide(observation(), context()),
        )
    }

    @Test
    fun pixAddsCentsAndDebitCanMakeBalanceNegative() {
        assertEquals(
            MovementDecision.Apply(MovementKind.PIX_CREDIT, 100L, 10_100L),
            policy.decide(observation(event = debit().copy(
                title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 1,00.",
            )), context()),
        )
        assertEquals(
            MovementDecision.Apply(MovementKind.TAG_DEBIT, 1_691L, -1_591L),
            policy.decide(observation(), context(balanceCents = 100L)),
        )
    }

    @Test
    fun snapshotsRemovalsAndLifecycleNeverChangeBalance() {
        DiagnosticEventType.entries.filter { it != DiagnosticEventType.POSTED_OR_UPDATED }.forEach {
            assertEquals(
                MovementDecision.Ignore,
                policy.decide(observation(event = debit().copy(type = it)), context()),
            )
        }
    }

    @Test
    fun summaryAdvertisementWrongOriginAndUnconfiguredAccountAreIgnored() {
        listOf(
            debit().copy(groupSummary = true),
            debit().copy(title = "Oferta", text = "Ganhe R$ 10,00."),
            debit().copy(packageName = "another.bank"),
            debit().copy(packageName = null),
        ).forEach {
            assertEquals(MovementDecision.Ignore, policy.decide(observation(event = it), context()))
        }
        assertEquals(MovementDecision.Ignore, policy.decide(observation(), context(reference = null)))
        assertEquals(MovementDecision.Ignore, policy.decide(observation(), context().copy(expectedPackageName = "")))
    }

    @Test
    fun coveredObservationIsNotReapplied() {
        assertEquals(
            MovementDecision.Ignore,
            policy.decide(observation(id = 9L, event = debit(observedAtMs = 900L)), context()),
        )
    }

    @Test
    fun samePublicationAndValueDoesNotApplyAgainEvenWithEnrichedText() {
        assertEquals(
            MovementDecision.Ignore,
            policy.decide(
                observation(event = debit().copy(bigText = "${debit().text} Detalhe adicional.")),
                context(sources = SourceHistory(SourceValue(MovementKind.TAG_DEBIT, 1_691L))),
            ),
        )
    }

    @Test
    fun conflictingAmountOrKindRequiresReviewWithoutChangingPreviousEntry() {
        listOf(
            SourceValue(MovementKind.TAG_DEBIT, 2_000L),
            SourceValue(MovementKind.PIX_CREDIT, 1_691L),
            SourceValue(MovementKind.TAG_DEBIT, null),
        ).forEach { previous ->
            assertEquals(
                MovementDecision.Pending(PendingReason.CONFLICTING_PUBLICATION),
                policy.decide(observation(), context(sources = SourceHistory(previous))),
            )
        }
    }

    @Test
    fun reusedKeyAndIdenticalContentInNewKeyArePendingNotAssumedDuplicates() {
        assertEquals(
            MovementDecision.Pending(PendingReason.REUSED_KEY),
            policy.decide(observation(), context(sources = SourceHistory(sameKeySeen = true))),
        )
        assertEquals(
            MovementDecision.Pending(PendingReason.IDENTICAL_CONTENT),
            policy.decide(observation(), context(sources = SourceHistory(identicalContentSeen = true))),
        )
    }

    @Test
    fun equalAmountInDifferentPublicationWithDifferentEvidenceIsNew() {
        val first = requireNotNull(policy.evidence(debit()))
        val secondEvent = debit(key = "charge-b", text = debit().text!!.replace("Praça A", "Praça B"))
        val second = requireNotNull(policy.evidence(secondEvent))
        assertEquals(first.value, second.value)
        assertNotEquals(first.publication, second.publication)
        assertNotEquals(first.contentHash, second.contentHash)
        assertEquals(
            MovementDecision.Apply(MovementKind.TAG_DEBIT, 1_691L, 8_309L),
            policy.decide(observation(event = secondEvent), context()),
        )
    }

    @Test
    fun missingOrInvalidIdentityNeedsReview() {
        listOf(
            debit(key = null), debit(key = ""), debit(postedAtMs = null), debit(postedAtMs = 0L),
        ).forEach {
            assertEquals(
                MovementDecision.Pending(PendingReason.INSUFFICIENT_IDENTITY),
                policy.decide(observation(event = it), context()),
            )
        }
    }

    @Test
    fun financialTitleWithoutReadableAmountIsPendingButUnknownTitleIsNot() {
        listOf("Valor a debitar R$ 16,", "Valor a debitar R$ 92233720368547758,08.", "").forEach {
            assertEquals(
                MovementDecision.Pending(PendingReason.INVALID_AMOUNT),
                policy.decide(observation(event = debit(text = it)), context()),
            )
        }
        assertEquals(
            MovementDecision.Ignore,
            policy.decide(observation(event = debit().copy(title = "Novo formato")), context()),
        )
    }

    @Test
    fun uncertainTemporalOrSerialBoundaryNeedsReview() {
        listOf(
            observation(id = 9L),
            observation(event = debit(observedAtMs = 1_000L)),
            observation(event = debit(observedAtMs = 900L)),
            observation(event = debit(postedAtMs = 1_000L)),
            observation(event = debit(postedAtMs = 900L)),
            observation(event = debit(postedAtMs = 2_100L)),
        ).forEach {
            assertEquals(
                MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY),
                policy.decide(it, context()),
            )
        }
    }

    @Test
    fun candidateWhileReferenceIsOpenHasNoAutomaticImpact() {
        assertEquals(
            MovementDecision.Pending(PendingReason.REFERENCE_IN_PROGRESS),
            policy.decide(observation(), context(referenceSessionActive = true)),
        )
    }

    @Test
    fun overflowInEitherDirectionIsPendingInsteadOfWrapping() {
        assertEquals(
            MovementDecision.Pending(PendingReason.AMOUNT_OVERFLOW),
            policy.decide(observation(), context(balanceCents = Long.MIN_VALUE)),
        )
        assertEquals(
            MovementDecision.Pending(PendingReason.AMOUNT_OVERFLOW),
            policy.decide(observation(event = debit().copy(
                title = "Você recebeu um Pix", text = "Pix recebido no valor de R$ 0,01.",
            )), context(balanceCents = Long.MAX_VALUE)),
        )
    }

    @Test
    fun evidenceUsesNfcAndEquivalentSpacesWithoutDiscardingDatesDigitsOrLocations() {
        val base = requireNotNull(policy.evidence(debit()))
        val equivalent = debit().copy(
            title = "De\u0301bito\u00a0C6  Tag",
            text = debit().text!!.replace(" ", "\u00a0  "),
        )
        assertEquals(base.contentHash, requireNotNull(policy.evidence(equivalent)).contentHash)
        listOf(
            debit().text!!.replace("10/11", "11/11"),
            debit().text!!.replace("Praça A", "Praça B"),
            debit().text!!.replace("16,91", "16,92"),
        ).forEach {
            assertNotEquals(base.contentHash, requireNotNull(policy.evidence(debit(text = it))).contentHash)
        }
        assertEquals(64, base.contentHash.length)
        assertEquals(
            PublicationIdentity("example.bank", "charge-a", 1_500L),
            base.publication,
        )
    }

    @Test
    fun evidenceIncludesExpandedTextAndLinesButDoesNotDependOnRepeatedFields() {
        val base = requireNotNull(policy.evidence(debit()))
        assertEquals(base.contentHash, requireNotNull(policy.evidence(
            debit().copy(bigText = debit().text, lines = listOf(debit().text!!)),
        )).contentHash)
        assertNotEquals(base.contentHash, requireNotNull(policy.evidence(
            debit().copy(bigText = "${debit().text} Local 123."),
        )).contentHash)
        assertNotEquals(base.contentHash, requireNotNull(policy.evidence(
            debit().copy(lines = listOf("Local 456.")),
        )).contentHash)
        assertNull(policy.evidence(debit().copy(title = "Oferta")))
        assertNull(policy.evidence(debit().copy(groupSummary = true)))
    }

    @Test
    fun firstReferenceAlsoRetainsCandidatesWhileFormIsOpen() {
        assertEquals(
            MovementDecision.Pending(PendingReason.REFERENCE_IN_PROGRESS),
            policy.decide(observation(), context(reference = null, referenceSessionActive = true)),
        )
    }

    @Test
    fun invalidObservationIdOrTimestampCannotBecomeCoveredHistory() {
        assertEquals(
            MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY),
            policy.decide(observation(id = 0L, event = debit(observedAtMs = 900L)), context()),
        )
        assertEquals(
            MovementDecision.Pending(PendingReason.UNCERTAIN_BOUNDARY),
            policy.decide(observation(id = 9L, event = debit(observedAtMs = -1L)), context()),
        )
    }

    @Test
    fun pendingCanBeExplicitlyConfirmedUsingOnlyItsStoredValue() {
        assertEquals(
            PendingResolution.Applied(MovementDecision.Apply(MovementKind.TAG_DEBIT, 1_691L, 8_309L)),
            resolve(pending()),
        )
        assertEquals(
            PendingResolution.Applied(MovementDecision.Apply(MovementKind.PIX_CREDIT, 100L, 10_100L)),
            resolve(pending().copy(value = SourceValue(MovementKind.PIX_CREDIT, 100L))),
        )
    }

    @Test
    fun doNotCountAndRepeatedDecisionsDoNotChangeBalanceAgain() {
        assertEquals(PendingResolution.NotCounted, resolve(pending(), PendingChoice.DO_NOT_COUNT))
        PendingStatus.entries.filter { it != PendingStatus.OPEN }.forEach {
            assertEquals(PendingResolution.AlreadyResolved, resolve(pending().copy(status = it)))
        }
    }

    @Test
    fun pendingFromPreviousReferenceCannotChangeNewBalance() {
        assertEquals(
            PendingResolution.Rejected(RejectionCode.STALE_REFERENCE),
            policy.resolve(pending(), ResolvePending(7L, 1L, PendingChoice.CONFIRM_NEW), 2L, 10_000L),
        )
        assertEquals(
            PendingResolution.Rejected(RejectionCode.STALE_REFERENCE),
            policy.resolve(pending(), ResolvePending(7L, 2L, PendingChoice.CONFIRM_NEW), 2L, 10_000L),
        )
        assertEquals(
            PendingResolution.Rejected(RejectionCode.INVALID_INPUT),
            policy.resolve(pending(), ResolvePending(8L, 1L, PendingChoice.CONFIRM_NEW), 1L, 10_000L),
        )
    }

    @Test
    fun unreadableOrInvalidValueCannotBeInventedByPendingResolution() {
        listOf(
            SourceValue(MovementKind.TAG_DEBIT, null),
            SourceValue(null, 100L),
            SourceValue(MovementKind.TAG_DEBIT, -100L),
        ).forEach {
            assertEquals(
                PendingResolution.Rejected(RejectionCode.UNREADABLE_AMOUNT),
                resolve(pending().copy(value = it)),
            )
        }
    }

    @Test
    fun alreadyAppliedSourceCannotApplyAgainThroughAnotherPending() {
        assertEquals(
            PendingResolution.Rejected(RejectionCode.ALREADY_APPLIED),
            resolve(pending().copy(sourceAlreadyApplied = true)),
        )
    }

    @Test
    fun manualPendingResolutionAlsoRejectsOverflow() {
        assertEquals(
            PendingResolution.Rejected(RejectionCode.AMOUNT_OVERFLOW),
            policy.resolve(pending(), ResolvePending(7L, 1L, PendingChoice.CONFIRM_NEW), 1L, Long.MIN_VALUE),
        )
    }

    private fun pending() = PendingMovement(7L, 1L, 3L, SourceValue(MovementKind.TAG_DEBIT, 1_691L))

    private fun resolve(
        pending: PendingMovement,
        choice: PendingChoice = PendingChoice.CONFIRM_NEW,
    ) = policy.resolve(pending, ResolvePending(pending.id, 1L, choice), 1L, 10_000L)

    private fun observation(
        id: Long = 11L,
        event: DiagnosticEvent = debit(),
    ) = FinancialObservation(id, event)

    private fun context(
        reference: BalanceReference? = BalanceReference(1L, 10_000L, 1_000L, 10L),
        balanceCents: Long = 10_000L,
        sources: SourceHistory = SourceHistory(),
        referenceSessionActive: Boolean = false,
    ) = MovementContext("example.bank", reference, balanceCents, sources, referenceSessionActive)

    private fun debit(
        key: String? = "charge-a",
        postedAtMs: Long? = 1_500L,
        observedAtMs: Long = 2_000L,
        text: String = "Uso em 10/11/2026. Valor a debitar R$ 16,91. Praça A.",
    ) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = observedAtMs,
        postedAtMs = postedAtMs,
        packageName = "example.bank",
        key = key,
        title = "Débito C6 Tag",
        text = text,
    )
}
