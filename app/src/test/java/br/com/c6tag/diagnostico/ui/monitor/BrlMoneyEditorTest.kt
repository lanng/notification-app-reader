package br.com.c6tag.diagnostico.ui.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrlMoneyEditorTest {
    @Test
    fun formattedPasteCursorBeforeSignificantDigitStaysBeforeIt() {
        val accepted = accepted(
            moneyEditFromCanonical(""),
            "0,01",
            BrlMoneySelection(1, 1),
        )

        assertEquals("1", accepted.digits)
        assertEquals(BrlMoneySelection(0, 0), accepted.selection)
    }

    @Test
    fun formattedPasteOffsetsMapAcrossLeadingZeroesCurrencyAndFractionPadding() {
        assertPasteOffsets("0,01", "1", listOf(0, 0, 0, 0, 1))
        assertPasteOffsets("R$0,01", "1", listOf(0, 0, 0, 0, 0, 0, 1))
        assertPasteOffsets("0000,01", "1", listOf(0, 0, 0, 0, 0, 0, 0, 1))
        assertPasteOffsets("1,2", "120", listOf(0, 1, 1, 3))
    }

    @Test
    fun formattedPasteMapsMiddleSelectionAndKeepsInvertedSelectionDirection() {
        val previous = moneyEditFromCanonical("1234,56").copy(selection = BrlMoneySelection(2, 4))
        val proposal = "12" + "0,01" + "56"

        val middleCursor = accepted(previous, proposal, BrlMoneySelection(3, 3))
        assertEquals("12156", middleCursor.digits)
        assertEquals(BrlMoneySelection(2, 2), middleCursor.selection)

        val invertedCursorRange = accepted(previous, proposal, BrlMoneySelection(6, 3))
        assertEquals(BrlMoneySelection(3, 2), invertedCursorRange.selection)

        val suffixCursor = accepted(previous, proposal, BrlMoneySelection(7, 7))
        assertEquals(BrlMoneySelection(4, 4), suffixCursor.selection)

        val afterSuffixCursor = accepted(previous, proposal, BrlMoneySelection(proposal.length, proposal.length))
        assertEquals(BrlMoneySelection(5, 5), afterSuffixCursor.selection)
    }

    @Test(timeout = 15_000)
    fun longLeadingZeroPasteMapsOffsetsWithoutQuadraticWork() {
        val leadingZeroes = "0".repeat(100_000)
        val amount = "$leadingZeroes,01"
        val empty = moneyEditFromCanonical("")

        val atBeginning = accepted(empty, amount, BrlMoneySelection(0, 0))
        assertEquals("1", atBeginning.digits)
        assertEquals(BrlMoneySelection(0, 0), atBeginning.selection)

        val atMiddle = accepted(
            empty,
            amount,
            BrlMoneySelection(leadingZeroes.length / 2, leadingZeroes.length / 2),
        )
        assertEquals("1", atMiddle.digits)
        assertEquals(BrlMoneySelection(0, 0), atMiddle.selection)

        val atEnd = accepted(empty, amount, BrlMoneySelection(amount.length, amount.length))
        assertEquals("1", atEnd.digits)
        assertEquals(BrlMoneySelection(1, 1), atEnd.selection)

        val previousWithSuffix = BrlMoneyEdit("12", false, BrlMoneySelection(1, 1))
        val proposalWithSuffix = "1$amount" + "2"
        val beforeSuffix = accepted(
            previousWithSuffix,
            proposalWithSuffix,
            BrlMoneySelection(1 + leadingZeroes.length / 2, 1 + leadingZeroes.length / 2),
        )
        assertEquals("112", beforeSuffix.digits)
        assertEquals(BrlMoneySelection(1, 1), beforeSuffix.selection)

        val afterSuffix = accepted(
            previousWithSuffix,
            proposalWithSuffix,
            BrlMoneySelection(proposalWithSuffix.length, proposalWithSuffix.length),
        )
        assertEquals("112", afterSuffix.digits)
        assertEquals(BrlMoneySelection(3, 3), afterSuffix.selection)
    }

    @Test
    fun digitsAreCentsWithoutChangingTheLegacyParser() {
        val empty = moneyEditFromCanonical("")
        val accepted = applyMoneyEdit(empty, "124500", BrlMoneySelection(6, 6)) as BrlMoneyEditResult.Accepted

        assertEquals("1245,00", moneyCanonicalText(accepted.edit))
        assertEquals("1.245,00", moneyPresentation(accepted.edit).text)
        assertEquals(124_500L, parseBrlInput(moneyCanonicalText(accepted.edit)))
        assertEquals(30_000L, parseBrlInput("300"))
    }

    @Test
    fun canonicalAndPresentationFollowTheCentDigitTable() {
        val cases = listOf(
            "" to ("" to ""),
            "0" to ("0,00" to "0,00"),
            "1" to ("0,01" to "0,01"),
            "123" to ("1,23" to "1,23"),
            "60000" to ("600,00" to "600,00"),
            "124500" to ("1245,00" to "1.245,00"),
        )

        cases.forEach { (digits, expected) ->
            val edit = BrlMoneyEdit(digits, false, BrlMoneySelection(digits.length, digits.length))
            assertEquals(expected.first, moneyCanonicalText(edit))
            assertEquals(expected.second, moneyPresentation(edit).text)
        }
    }

    @Test
    fun emptyZeroAndLeadingZeroesStayDistinctAndValid() {
        val empty = moneyEditFromCanonical("")
        assertEquals("", moneyCanonicalText(empty))
        assertEquals("", moneyPresentation(empty).text)

        val zero = moneyEditFromCanonical("0")
        assertEquals("0", zero.digits)
        assertEquals("0,00", moneyCanonicalText(zero))
        assertEquals("0,00", moneyPresentation(zero).text)

        val leadingZeroes = applyMoneyEdit(empty, "00001", BrlMoneySelection(5, 5))
            as BrlMoneyEditResult.Accepted
        assertEquals("0,01", moneyCanonicalText(leadingZeroes.edit))
        assertTrue(leadingZeroes.edit.selection.start in 0..leadingZeroes.edit.digits.length)
        assertTrue(leadingZeroes.edit.selection.end in 0..leadingZeroes.edit.digits.length)
    }

    @Test
    fun canonicalLoadsNormalizeExactlyAndPreserveSignedLongBoundaries() {
        val oneFractionalDigit = moneyEditFromCanonical("300,1")
        assertEquals("30010", oneFractionalDigit.digits)
        assertEquals("300,10", moneyCanonicalText(oneFractionalDigit))

        val maximum = moneyEditFromCanonical("92.233.720.368.547.758,07")
        assertEquals("92233720368547758,07", moneyCanonicalText(maximum))
        assertFalse(maximum.inheritedNegative)

        val minimum = moneyEditFromCanonical("-92.233.720.368.547.758,08")
        assertTrue(minimum.inheritedNegative)
        assertEquals("-92233720368547758,08", moneyCanonicalText(minimum))
        assertEquals("-92.233.720.368.547.758,08", moneyPresentation(minimum).text)
    }

    @Test
    fun rawDigitsCanBeInsertedAndDeletedAtEveryPosition() {
        val original = moneyEditFromCanonical("1.234,56")

        assertAccepted("91234,56", original, "9123456", BrlMoneySelection(1, 1))
        assertAccepted("12394,56", original.copy(selection = BrlMoneySelection(3, 3)), "1239456", BrlMoneySelection(4, 4))
        assertAccepted("12345,69", original.copy(selection = BrlMoneySelection(6, 6)), "1234569", BrlMoneySelection(7, 7))

        assertAccepted("234,56", original.copy(selection = BrlMoneySelection(0, 1)), "23456", BrlMoneySelection(0, 0))
        assertAccepted("12,56", original.copy(selection = BrlMoneySelection(2, 4)), "1256", BrlMoneySelection(2, 2))
        assertAccepted("123,45", original.copy(selection = BrlMoneySelection(5, 6)), "12345", BrlMoneySelection(5, 5))
    }

    @Test
    fun invertedSelectionSelectAllAndReplacementArePreservedAsRawDigitEdits() {
        val original = moneyEditFromCanonical("1.234,56")

        assertAccepted("129,56", original.copy(selection = BrlMoneySelection(4, 2)), "12956", BrlMoneySelection(3, 3))
        assertAccepted("0,07", original.copy(selection = BrlMoneySelection(6, 0)), "7", BrlMoneySelection(1, 1))
        assertAccepted("1289,56", original.copy(selection = BrlMoneySelection(2, 4)), "128956", BrlMoneySelection(4, 4))

        val selectionOnly = accepted(original, original.digits, BrlMoneySelection(4, 1))
        assertEquals(moneyCanonicalText(original), moneyCanonicalText(selectionOnly))
        assertEquals(BrlMoneySelection(4, 1), selectionOnly.selection)
    }

    @Test
    fun presentationOffsetMapsAreMonotonicAndRoundTripEveryDigitBoundary() {
        val edits = listOf(
            moneyEditFromCanonical(""),
            moneyEditFromCanonical("0"),
            moneyEditFromCanonical("0,01"),
            moneyEditFromCanonical("1,23"),
            moneyEditFromCanonical("1.234,56"),
            moneyEditFromCanonical("1245,00"),
            moneyEditFromCanonical("-1.234,56"),
            BrlMoneyEdit("12345678901234567890", false, BrlMoneySelection(20, 20)),
        )

        edits.forEach { edit ->
            val presentation = moneyPresentation(edit)
            assertEquals(edit.digits.length + 1, presentation.originalToTransformed.size)
            assertEquals(presentation.text.length + 1, presentation.transformedToOriginal.size)
            assertMonotonic(presentation.originalToTransformed)
            assertMonotonic(presentation.transformedToOriginal)

            presentation.originalToTransformed.forEachIndexed { originalOffset, transformedOffset ->
                assertTrue(transformedOffset in 0..presentation.text.length)
                assertEquals(originalOffset, presentation.transformedToOriginal[transformedOffset])
            }
            presentation.transformedToOriginal.forEach { originalOffset ->
                assertTrue(originalOffset in 0..edit.digits.length)
            }
        }
    }

    @Test
    fun formattedBrazilianPasteReplacesOnlyTheSelectedDigitRange() {
        val previous = moneyEditFromCanonical("1234,56").copy(selection = BrlMoneySelection(2, 4))
        val acceptedPaste = accepted(previous, "12R$ 7,8956", BrlMoneySelection(9, 9))

        assertEquals("1278956", acceptedPaste.digits)
        assertEquals("12789,56", moneyCanonicalText(acceptedPaste))
        assertEquals(BrlMoneySelection(5, 5), acceptedPaste.selection)

        val wholeFieldPaste = accepted(
            moneyEditFromCanonical(""),
            "R$ 1.245,00",
            BrlMoneySelection("R$ 1.245,00".length, "R$ 1.245,00".length),
        )
        assertEquals("124500", wholeFieldPaste.digits)
        assertEquals(BrlMoneySelection(6, 6), wholeFieldPaste.selection)

        val oneFractionalDigitPaste = accepted(
            moneyEditFromCanonical(""),
            "1,2",
            BrlMoneySelection(3, 3),
        )
        assertEquals("120", oneFractionalDigitPaste.digits)
        assertEquals(BrlMoneySelection(3, 3), oneFractionalDigitPaste.selection)
    }

    @Test
    fun malformedPastesNewSignsAndInvalidSelectionsAreRejected() {
        val empty = moneyEditFromCanonical("")
        listOf("1e3", "abc", "1.23,00", "1,234", "+1", "-1", "R$ -1,00", "1 000", "1,,00")
            .forEach { proposal ->
                assertEquals(
                    BrlMoneyEditError.INVALID_INPUT,
                    (applyMoneyEdit(empty, proposal, BrlMoneySelection(proposal.length, proposal.length))
                        as BrlMoneyEditResult.Rejected).error,
                )
            }

        assertEquals(
            BrlMoneyEditError.INVALID_INPUT,
            (applyMoneyEdit(empty, "1", BrlMoneySelection(2, 2)) as BrlMoneyEditResult.Rejected).error,
        )
    }

    @Test
    fun overflowIsRejectedWhileLongBoundariesAndInheritedSignsRemainEditable() {
        val empty = moneyEditFromCanonical("")
        val maximumDigits = "9223372036854775807"
        val maximum = accepted(empty, maximumDigits, BrlMoneySelection(maximumDigits.length, maximumDigits.length))
        assertEquals(Long.MAX_VALUE, parseBrlInput(moneyCanonicalText(maximum)))
        assertEquals(
            BrlMoneyEditError.OVERFLOW,
            (applyMoneyEdit(empty, "${maximumDigits}0", BrlMoneySelection(maximumDigits.length + 1, maximumDigits.length + 1))
                as BrlMoneyEditResult.Rejected).error,
        )

        val minimum = moneyEditFromCanonical("-92.233.720.368.547.758,08")
        val unchangedMinimum = accepted(
            minimum,
            minimum.digits,
            BrlMoneySelection(minimum.digits.length, minimum.digits.length),
        )
        assertTrue(unchangedMinimum.inheritedNegative)
        assertEquals(Long.MIN_VALUE, parseBrlInput(moneyCanonicalText(unchangedMinimum)))
        assertEquals(
            BrlMoneyEditError.OVERFLOW,
            (applyMoneyEdit(minimum, "9223372036854775809", BrlMoneySelection(19, 19))
                as BrlMoneyEditResult.Rejected).error,
        )

        val negativeEdited = accepted(
            moneyEditFromCanonical("-1,23"),
            "1234",
            BrlMoneySelection(4, 4),
        )
        assertTrue(negativeEdited.inheritedNegative)
        assertEquals("-12,34", moneyCanonicalText(negativeEdited))
    }

    @Test
    fun deletingAllDigitsClearsTheInheritedSignBeforeFurtherTyping() {
        val negative = moneyEditFromCanonical("-1,23").copy(selection = BrlMoneySelection(0, 3))
        val cleared = accepted(negative, "", BrlMoneySelection(0, 0))
        assertEquals("", moneyCanonicalText(cleared))
        assertFalse(cleared.inheritedNegative)

        val typedAgain = accepted(cleared, "1", BrlMoneySelection(1, 1))
        assertEquals("0,01", moneyCanonicalText(typedAgain))
        assertFalse(typedAgain.inheritedNegative)
    }

    private fun assertAccepted(
        expectedCanonical: String,
        previous: BrlMoneyEdit,
        proposedText: String,
        selection: BrlMoneySelection,
    ) {
        assertEquals(expectedCanonical, moneyCanonicalText(accepted(previous, proposedText, selection)))
    }

    private fun accepted(
        previous: BrlMoneyEdit,
        proposedText: String,
        selection: BrlMoneySelection,
    ): BrlMoneyEdit =
        (applyMoneyEdit(previous, proposedText, selection) as BrlMoneyEditResult.Accepted).edit

    private fun assertMonotonic(offsets: IntArray) {
        (0 until offsets.lastIndex).forEach { index ->
            assertTrue(
                "offset map must be monotonic: ${offsets.contentToString()}",
                offsets[index] <= offsets[index + 1],
            )
        }
    }

    private fun assertPasteOffsets(text: String, expectedDigits: String, expectedOffsets: List<Int>) {
        expectedOffsets.forEachIndexed { sourceOffset, expectedDigitOffset ->
            val edit = accepted(
                moneyEditFromCanonical(""),
                text,
                BrlMoneySelection(sourceOffset, sourceOffset),
            )
            assertEquals(expectedDigits, edit.digits)
            assertEquals("source offset $sourceOffset in '$text'", expectedDigitOffset, edit.selection.start)
        }
    }
}
