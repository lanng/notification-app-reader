package br.com.c6tag.diagnostico.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationClassifierTest {
    private val classifier = NotificationClassifier()

    @Test
    fun debitUsesChargeValueNotDateOrRoadNumber() {
        val text =
            "Você usou seu tag no dia 16/11/2026 às 13:37. " +
                "Valor a debitar R$ 16,91. Pedágio SP425 4001 LOCAL."

        val result = classifier.classify(
            title = "Débito C6 Tag",
            text = text,
            bigText = null,
            lines = emptyList(),
            groupSummary = false,
        )

        assertEquals(CandidateKind.TAG_DEBIT, result.kind)
        assertEquals(1691L, result.amountCents)
    }

    @Test
    fun pixCreditUsesBrazilianAmount() {
        val result = classify(
            title = "Você recebeu um Pix",
            text = "Pix recebido no valor de R$ 1.234,56, em 16/11/2026.",
        )

        assertEquals(CandidateKind.PIX_CREDIT, result.kind)
        assertEquals(123456L, result.amountCents)
    }

    @Test
    fun groupSummaryWinsOverKnownTitleAndAmount() {
        val result = classifier.classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            bigText = "Valor a debitar R$ 25,00.",
            lines = listOf("Valor a debitar R$ 10,00."),
            groupSummary = true,
        )

        assertEquals(CandidateKind.GROUP_SUMMARY, result.kind)
        assertNull(result.amountCents)
        assertNull(result.reason)
    }

    @Test
    fun nullAndHiddenContentAreUnknownWithReasons() {
        val nullResult = classifier.classify(null, null, null, emptyList(), false)
        val hiddenResult = classifier.classify(
            title = "Débito C6 Tag",
            text = null,
            bigText = null,
            lines = emptyList(),
            groupSummary = false,
        )

        assertUnknown(nullResult)
        assertUnknown(hiddenResult)
    }

    @Test
    fun unknownTitleDoesNotRecognizeArbitraryCurrency() {
        val result = classify(
            title = "Sua conta",
            text = "Saldo atualizado: R$ 500,00.",
        )

        assertUnknown(result)
    }

    @Test
    fun knownTitleWithWrongMovementPhraseIsUnknown() {
        val result = classify(
            title = "Débito C6 Tag",
            text = "Saldo disponível R$ 16,91.",
        )

        assertUnknown(result)
    }

    @Test
    fun completeBigTextCanReplaceTruncatedText() {
        val result = classifier.classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,",
            bigText = "Você usou seu tag. Valor a debitar R$ 16,91.",
            lines = emptyList(),
            groupSummary = false,
        )

        assertEquals(CandidateKind.TAG_DEBIT, result.kind)
        assertEquals(1691L, result.amountCents)
    }

    @Test
    fun divergentCompleteFieldsAreUnknown() {
        val result = classifier.classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            bigText = "Valor a debitar R$ 17,91.",
            lines = emptyList(),
            groupSummary = false,
        )

        assertUnknown(result)
    }

    @Test
    fun multipleMovementCandidatesInOneFieldAreUnknown() {
        val result = classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91. Valor a debitar R$ 17,91.",
        )

        assertUnknown(result)
    }

    @Test
    fun identicalValuesRepeatedAcrossFieldsRemainOneCandidate() {
        val result = classifier.classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            bigText = "Valor a debitar R$ 16,91.",
            lines = listOf("Valor a debitar R$ 16,91."),
            groupSummary = false,
        )

        assertEquals(CandidateKind.TAG_DEBIT, result.kind)
        assertEquals(1691L, result.amountCents)
    }

    @Test
    fun textLinesCanProvideTheCandidate() {
        val result = classifier.classify(
            title = "Você recebeu um Pix",
            text = null,
            bigText = null,
            lines = listOf("Pix recebido no valor de R$ 2,00, em 16/11/2026."),
            groupSummary = false,
        )

        assertEquals(CandidateKind.PIX_CREDIT, result.kind)
        assertEquals(200L, result.amountCents)
    }

    @Test
    fun differentLineCandidatesAreUnknown() {
        val result = classifier.classify(
            title = "Você recebeu um Pix",
            text = null,
            bigText = null,
            lines = listOf(
                "Pix recebido no valor de R$ 2,00.",
                "Pix recebido no valor de R$ 3,00.",
            ),
            groupSummary = false,
        )

        assertUnknown(result)
    }

    @Test
    fun zeroCentsThousandsAndUnformattedThousandsAreExact() {
        assertAmount("R$ 0,00", 0L)
        assertAmount("R$ 0,01", 1L)
        assertAmount("R$ 1.234,56", 123456L)
        assertAmount("R$ 1234,56", 123456L)
    }

    @Test
    fun maximumLongCentsIsAcceptedWithoutFloatingPoint() {
        assertAmount("R$ 92233720368547758,07", Long.MAX_VALUE)
    }

    @Test
    fun overflowIsUnknown() {
        assertUnknown(classifyDebit("R$ 92233720368547758,08"))
    }

    @Test
    fun malformedBrazilianAmountsAreUnknown() {
        listOf(
            "R$ 12.34,56",
            "R$ 1,234",
            "R$ -2,00",
            "R$ 16,910",
        ).forEach { amount ->
            assertUnknown(classifyDebit(amount))
        }
    }

    @Test
    fun differentEventsWithSameAmountRemainDifferentOrigins() {
        val posted = DiagnosticEvent(
            type = DiagnosticEventType.POSTED_OR_UPDATED,
            observedAtMs = 1_800_000_000_001L,
            postedAtMs = 1_799_999_000_000L,
            packageName = "example.bank",
            key = "posted-key",
            notificationId = 7,
            tag = "posted-tag",
            groupKey = "posted-group",
            groupSummary = false,
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            interpretation = classifyDebit("R$ 16,91"),
        )
        val snapshot = DiagnosticEvent(
            type = DiagnosticEventType.ACTIVE_SNAPSHOT,
            observedAtMs = 1_800_000_000_002L,
            postedAtMs = 1_799_999_000_000L,
            packageName = "example.bank",
            key = "snapshot-key",
            notificationId = 7,
            groupSummary = false,
            title = "Débito C6 Tag",
            text = "Valor a debitar R$ 16,91.",
            interpretation = classifyDebit("R$ 16,91"),
        )

        assertEquals(posted.interpretation, snapshot.interpretation)
        assertEquals(1_800_000_000_001L, posted.observedAtMs)
        assertEquals(1_799_999_000_000L, posted.postedAtMs)
        assertEquals(DiagnosticEventType.POSTED_OR_UPDATED, posted.type)
        assertEquals(DiagnosticEventType.ACTIVE_SNAPSHOT, snapshot.type)
        assertEquals("posted-key", posted.key)
        assertEquals("snapshot-key", snapshot.key)
        assertFalse(posted.key == snapshot.key)
    }

    @Test
    fun removalPreservesItsReasonWithoutBecomingAReversal() {
        val removed = DiagnosticEvent(
            type = DiagnosticEventType.REMOVED,
            observedAtMs = 1_800_000_000_003L,
            key = "removed-key",
            removalReason = 4,
        )

        assertEquals(DiagnosticEventType.REMOVED, removed.type)
        assertEquals(4, removed.removalReason)
        assertNull(removed.interpretation)
    }

    @Test
    fun interpretationEnforcesCandidateAndUnknownInvariants() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationInterpretation(CandidateKind.TAG_DEBIT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationInterpretation(CandidateKind.UNKNOWN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotificationInterpretation(
                kind = CandidateKind.GROUP_SUMMARY,
                amountCents = 1L,
            )
        }
        assertTrue(NotificationInterpretation(CandidateKind.UNKNOWN, reason = "oculta").reason!!.isNotBlank())
    }

    private fun assertAmount(amount: String, expectedCents: Long) {
        val result = classifyDebit(amount)

        assertEquals(CandidateKind.TAG_DEBIT, result.kind)
        assertEquals(expectedCents, result.amountCents)
    }

    private fun classifyDebit(amount: String): NotificationInterpretation =
        classify(
            title = "Débito C6 Tag",
            text = "Valor a debitar $amount.",
        )

    private fun classify(
        title: String?,
        text: String?,
    ): NotificationInterpretation = classifier.classify(
        title = title,
        text = text,
        bigText = null,
        lines = emptyList(),
        groupSummary = false,
    )

    private fun assertUnknown(result: NotificationInterpretation) {
        assertEquals(CandidateKind.UNKNOWN, result.kind)
        assertNull(result.amountCents)
        assertFalse(result.reason.isNullOrBlank())
    }
}
