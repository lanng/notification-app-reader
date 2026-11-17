package br.com.c6tag.diagnostico.diagnostic

class NotificationClassifier {
    fun classify(
        title: String?,
        text: String?,
        bigText: String?,
        lines: List<String>,
        groupSummary: Boolean,
    ): NotificationInterpretation {
        if (groupSummary) {
            return NotificationInterpretation(CandidateKind.GROUP_SUMMARY)
        }

        val (kind, phrase) = when {
            title?.trim().equals(DEBIT_TITLE, ignoreCase = true) ->
                CandidateKind.TAG_DEBIT to DEBIT_PHRASE

            title?.trim().equals(PIX_TITLE, ignoreCase = true) ->
                CandidateKind.PIX_CREDIT to PIX_PHRASE

            title.isNullOrBlank() ->
                return unknown("Título ausente ou oculto.")

            else ->
                return unknown("Título ou formato de notificação desconhecido.")
        }

        val fieldResults = buildList {
            text?.let(::add)
            bigText?.let(::add)
            addAll(lines)
        }.map { field -> inspectField(field, phrase) }

        val invalid = fieldResults.filterIsInstance<FieldResult.Invalid>().firstOrNull()
        if (invalid != null) {
            return unknown(invalid.reason)
        }

        val amounts = fieldResults
            .filterIsInstance<FieldResult.Value>()
            .map(FieldResult.Value::amountCents)
            .distinct()

        if (amounts.size > 1) {
            return unknown("Campos completos apresentam valores divergentes.")
        }
        val amountCents = amounts.singleOrNull()
            ?: return unknown("Texto ausente, oculto, truncado ou sem a frase de movimento esperada.")

        return NotificationInterpretation(kind = kind, amountCents = amountCents)
    }

    private fun inspectField(field: String, phrase: String): FieldResult {
        val phrasePositions = mutableListOf<Int>()
        var searchFrom = 0
        while (searchFrom < field.length) {
            val position = field.indexOf(phrase, startIndex = searchFrom, ignoreCase = true)
            if (position < 0) break
            phrasePositions += position
            searchFrom = position + phrase.length
        }

        if (phrasePositions.isEmpty()) return FieldResult.NoCandidate
        if (phrasePositions.size > 1) {
            return FieldResult.Invalid("Mais de um valor candidato no mesmo campo.")
        }

        var amountStart = phrasePositions.single() + phrase.length
        while (amountStart < field.length && field[amountStart].isWhitespace()) amountStart++
        if (!field.startsWith(CURRENCY_PREFIX, startIndex = amountStart, ignoreCase = true)) {
            return FieldResult.Incomplete
        }
        amountStart += CURRENCY_PREFIX.length

        val amountPart = field.substring(amountStart)
        val match = AMOUNT_PATTERN.find(amountPart)
        if (match == null) {
            return if (isTruncatedAmountPrefix(amountPart)) {
                FieldResult.Incomplete
            } else {
                FieldResult.Invalid("Valor monetário ausente ou malformado.")
            }
        }

        val suffix = amountPart.substring(match.range.last + 1)
        if (hasInvalidAmountBoundary(suffix)) {
            return FieldResult.Invalid("Valor monetário malformado ou com casas extras.")
        }

        val wholeUnits = match.groupValues[1].replace(".", "").toLongOrNull()
            ?: return FieldResult.Invalid("Valor monetário excede o limite suportado.")
        val cents = match.groupValues[2].toLong()
        if (wholeUnits > (Long.MAX_VALUE - cents) / 100L) {
            return FieldResult.Invalid("Valor monetário excede o limite suportado.")
        }

        return FieldResult.Value(wholeUnits * 100L + cents)
    }

    private fun hasInvalidAmountBoundary(suffix: String): Boolean {
        if (suffix.isEmpty()) return false
        val next = suffix.first()
        if (next.isLetterOrDigit()) return true
        return (next == ',' || next == '.') && suffix.getOrNull(1)?.isDigit() == true
    }

    private fun isTruncatedAmountPrefix(amountPart: String): Boolean {
        val trimmed = amountPart.trimStart()
        if (trimmed.isEmpty()) return true
        if (trimmed.any(Char::isWhitespace)) return false
        if (!TRUNCATED_AMOUNT_PREFIX.matches(trimmed)) return false
        return true
    }

    private fun unknown(reason: String) =
        NotificationInterpretation(kind = CandidateKind.UNKNOWN, reason = reason)

    private sealed interface FieldResult {
        data object NoCandidate : FieldResult
        data object Incomplete : FieldResult
        data class Value(val amountCents: Long) : FieldResult
        data class Invalid(val reason: String) : FieldResult
    }

    private companion object {
        const val DEBIT_TITLE = "Débito C6 Tag"
        const val PIX_TITLE = "Você recebeu um Pix"
        const val DEBIT_PHRASE = "Valor a debitar"
        const val PIX_PHRASE = "Pix recebido no valor de"
        const val CURRENCY_PREFIX = "R$"

        val AMOUNT_PATTERN = Regex("""^\s*((?:\d{1,3}(?:\.\d{3})+|\d+)),(\d{2})""")
        val TRUNCATED_AMOUNT_PREFIX =
            Regex("""(?:\d+|\d{1,3}(?:\.\d{3})*(?:\.\d{0,2})?)(?:,\d{0,1})?""")
    }
}
