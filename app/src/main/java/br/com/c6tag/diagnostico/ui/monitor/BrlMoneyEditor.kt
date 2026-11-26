package br.com.c6tag.diagnostico.ui.monitor

import java.math.BigDecimal
import java.math.BigInteger

internal data class BrlMoneySelection(val start: Int, val end: Int)

internal data class BrlMoneyEdit(
    val digits: String,
    val inheritedNegative: Boolean,
    val selection: BrlMoneySelection,
)

internal data class BrlMoneyInputChange(val canonicalText: String, val invalid: Boolean = false)

internal enum class BrlMoneyEditError {
    INVALID_INPUT,
    OVERFLOW,
}

internal sealed interface BrlMoneyEditResult {
    data class Accepted(val edit: BrlMoneyEdit) : BrlMoneyEditResult

    data class Rejected(val error: BrlMoneyEditError) : BrlMoneyEditResult
}

internal data class BrlMoneyPresentation(
    val text: String,
    val originalToTransformed: IntArray,
    val transformedToOriginal: IntArray,
)

internal fun moneyEditFromCanonical(text: String): BrlMoneyEdit {
    val parsedCents = parseBrlInput(text) ?: return BrlMoneyEdit("", false, BrlMoneySelection(0, 0))
    val digits = BigInteger.valueOf(parsedCents).abs().toString()
    val inheritedNegative = text.trim().startsWith('-')

    return BrlMoneyEdit(digits, inheritedNegative, BrlMoneySelection(digits.length, digits.length))
}

internal fun applyMoneyEdit(
    previous: BrlMoneyEdit,
    proposedText: String,
    selection: BrlMoneySelection,
): BrlMoneyEditResult {
    if (!isValidSelection(selection, proposedText.length) ||
        !previous.digits.all { it in '0'..'9' } ||
        !isValidSelection(previous.selection, previous.digits.length)
    ) {
        return BrlMoneyEditResult.Rejected(BrlMoneyEditError.INVALID_INPUT)
    }

    val rawDigits: String
    val rawSelection: BrlMoneySelection
    if (proposedText.all { it in '0'..'9' }) {
        rawDigits = proposedText
        rawSelection = selection
    } else {
        val replacement = formattedReplacement(previous, proposedText, selection)
            ?: return BrlMoneyEditResult.Rejected(BrlMoneyEditError.INVALID_INPUT)
        rawDigits = replacement.digits
        rawSelection = replacement.selection
    }

    val normalized = normalizeDigits(rawDigits)
    val normalizedSelection = BrlMoneySelection(
        normalizeOffset(rawDigits, normalized.digits, rawSelection.start),
        normalizeOffset(rawDigits, normalized.digits, rawSelection.end),
    )

    if (normalized.digits.isNotEmpty() &&
        exceedsMagnitude(normalized.digits, if (previous.inheritedNegative) LONG_MIN_MAGNITUDE else LONG_MAX_MAGNITUDE)
    ) {
        return BrlMoneyEditResult.Rejected(BrlMoneyEditError.OVERFLOW)
    }

    val inheritedNegative = previous.inheritedNegative && normalized.digits.isNotEmpty()
    return BrlMoneyEditResult.Accepted(
        BrlMoneyEdit(normalized.digits, inheritedNegative, normalizedSelection),
    )
}

internal fun moneyCanonicalText(edit: BrlMoneyEdit): String {
    val digits = normalizeDigits(edit.digits).digits
    if (digits.isEmpty()) {
        return ""
    }

    val paddedDigits = digits.padStart(3, '0')
    val whole = paddedDigits.dropLast(2)
    val fraction = paddedDigits.takeLast(2)
    val sign = if (edit.inheritedNegative) "-" else ""

    return "$sign$whole,$fraction"
}

internal fun moneyPresentation(edit: BrlMoneyEdit): BrlMoneyPresentation {
    val digits = normalizeDigits(edit.digits).digits
    if (digits.isEmpty()) {
        return BrlMoneyPresentation("", intArrayOf(0), intArrayOf(0))
    }

    val paddedDigits = digits.padStart(3, '0')
    val wholeLength = paddedDigits.length - 2
    val sourceDigitOffset = paddedDigits.length - digits.length
    val text = StringBuilder()
    val sourceDigitPositions = IntArray(digits.length)
    val isNegative = edit.inheritedNegative

    if (isNegative) {
        text.append('-')
    }

    paddedDigits.forEachIndexed { index, digit ->
        if (index == wholeLength) {
            text.append(',')
        } else if (index < wholeLength && index > 0 && (wholeLength - index) % 3 == 0) {
            text.append('.')
        }

        if (index >= sourceDigitOffset) {
            sourceDigitPositions[index - sourceDigitOffset] = text.length
        }
        text.append(digit)
    }

    val originalToTransformed = IntArray(digits.length + 1)
    sourceDigitPositions.forEachIndexed { index, position ->
        originalToTransformed[index] = position
    }
    originalToTransformed[digits.length] = sourceDigitPositions.last() + 1

    val transformedToOriginal = IntArray(text.length + 1)
    var sourceOffset = 0
    transformedToOriginal.indices.forEach { transformedOffset ->
        while (sourceOffset < sourceDigitPositions.size &&
            sourceDigitPositions[sourceOffset] < transformedOffset
        ) {
            sourceOffset += 1
        }
        transformedToOriginal[transformedOffset] = sourceOffset
    }

    return BrlMoneyPresentation(
        text = text.toString(),
        originalToTransformed = originalToTransformed,
        transformedToOriginal = transformedToOriginal,
    )
}

private data class FormattedReplacement(
    val digits: String,
    val selection: BrlMoneySelection,
)

private data class ParsedFormattedAmount(
    val digits: String,
    val sourceOffsets: IntArray,
)

private data class NormalizedDigits(val digits: String)

private fun formattedReplacement(
    previous: BrlMoneyEdit,
    proposedText: String,
    selection: BrlMoneySelection,
): FormattedReplacement? {
    val start = minOf(previous.selection.start, previous.selection.end)
    val end = maxOf(previous.selection.start, previous.selection.end)
    val prefix = previous.digits.substring(0, start)
    val suffix = previous.digits.substring(end)

    if (!proposedText.startsWith(prefix) ||
        !proposedText.endsWith(suffix) ||
        proposedText.length < prefix.length + suffix.length
    ) {
        return null
    }

    val insertedEnd = proposedText.length - suffix.length
    val insertedText = proposedText.substring(prefix.length, insertedEnd)
    val parsedAmount = parseFormattedAmount(insertedText) ?: return null
    val combinedDigits = prefix + parsedAmount.digits + suffix
    val mappedSelection = BrlMoneySelection(
        mapFormattedOffset(selection.start, prefix.length, insertedEnd, parsedAmount, suffix.length),
        mapFormattedOffset(selection.end, prefix.length, insertedEnd, parsedAmount, suffix.length),
    )

    return FormattedReplacement(combinedDigits, mappedSelection)
}

private fun mapFormattedOffset(
    offset: Int,
    prefixLength: Int,
    insertedEnd: Int,
    insertedAmount: ParsedFormattedAmount,
    suffixLength: Int,
): Int =
    when {
        offset <= prefixLength -> offset
        offset < insertedEnd -> prefixLength + insertedAmount.sourceOffsets[offset - prefixLength]
        else -> prefixLength + insertedAmount.digits.length + (offset - insertedEnd).coerceAtMost(suffixLength)
    }

private fun parseFormattedAmount(text: String): ParsedFormattedAmount? {
    val trimmed = text.trim()
    if (!BRL_PASTE.matches(trimmed)) {
        return null
    }

    val trimmedStart = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    val amountStart = if (trimmed.startsWith("R$")) {
        2 + trimmed.substring(2).indexOfFirst { !it.isWhitespace() }
    } else {
        0
    }
    val amount = trimmed.substring(amountStart)
    val decimalSeparator = amount.indexOf(',')
    val integerPart = if (decimalSeparator == -1) amount else amount.substring(0, decimalSeparator)
    val fractionalPart = if (decimalSeparator == -1) "" else amount.substring(decimalSeparator + 1)
    val integerDigits = integerPart.filter { it in '0'..'9' }
    val unnormalizedDigits = integerDigits + fractionalPart.padEnd(2, '0')
    val normalizedDigits = normalizeDigits(unnormalizedDigits).digits
    val leadingZeroCount = leadingZeroCount(unnormalizedDigits)
    val digitPrefixCounts = IntArray(amount.length + 1)
    amount.indices.forEach { index ->
        digitPrefixCounts[index + 1] = digitPrefixCounts[index] + if (amount[index] in '0'..'9') 1 else 0
    }
    val parsedDigits = try {
        BigDecimal(amount.replace(".", "").replace(',', '.'))
            .movePointRight(2)
            .toBigIntegerExact()
            .toString()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    } ?: return null

    if (normalizedDigits != parsedDigits) {
        return null
    }

    val sourceOffsets = IntArray(text.length + 1)
    sourceOffsets.indices.forEach { sourceOffset ->
        val trimmedOffset = (sourceOffset - trimmedStart).coerceIn(0, trimmed.length)
        val amountOffset = trimmedOffset - amountStart
        val unnormalizedOffset = when {
            amountOffset <= 0 -> 0
            amountOffset >= amount.length -> unnormalizedDigits.length
            decimalSeparator != -1 && amountOffset > decimalSeparator ->
                integerDigits.length + digitPrefixCounts[amountOffset] - digitPrefixCounts[decimalSeparator + 1]
            else -> digitPrefixCounts[amountOffset.coerceAtMost(integerPart.length)]
        }
        sourceOffsets[sourceOffset] = normalizeOffset(unnormalizedOffset, leadingZeroCount, normalizedDigits.length)
    }

    return ParsedFormattedAmount(normalizedDigits, sourceOffsets)
}

private fun normalizeDigits(digits: String): NormalizedDigits {
    if (digits.isEmpty()) {
        return NormalizedDigits("")
    }

    if (!digits.all { it in '0'..'9' }) {
        return NormalizedDigits("")
    }

    val withoutLeadingZeroes = digits.dropWhile { it == '0' }
    return NormalizedDigits(withoutLeadingZeroes.ifEmpty { "0" })
}

private fun normalizeOffset(originalDigits: String, normalizedDigits: String, offset: Int): Int {
    return normalizeOffset(offset, leadingZeroCount(originalDigits), normalizedDigits.length)
}

private fun normalizeOffset(offset: Int, leadingZeroCount: Int, normalizedDigitsLength: Int): Int =
    (offset - leadingZeroCount).coerceIn(0, normalizedDigitsLength)

private fun leadingZeroCount(digits: String): Int {
    if (digits.isEmpty()) {
        return 0
    }

    return digits.indexOfFirst { it != '0' }
        .let { if (it == -1) digits.lastIndex else it }
}

private fun exceedsMagnitude(value: String, maximum: String): Boolean =
    value.length > maximum.length || (value.length == maximum.length && value > maximum)

private fun isValidSelection(selection: BrlMoneySelection, textLength: Int): Boolean =
    selection.start in 0..textLength && selection.end in 0..textLength

private const val LONG_MAX_MAGNITUDE = "9223372036854775807"
private const val LONG_MIN_MAGNITUDE = "9223372036854775808"

private val BRL_PASTE = Regex(
    "(?:R\\$\\s*)?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]{1,2})?",
)
