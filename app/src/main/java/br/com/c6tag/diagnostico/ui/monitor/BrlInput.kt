package br.com.c6tag.diagnostico.ui.monitor

import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Deliberately strict pt-BR input: no currency symbol, exponent or rounding. */
internal fun parseBrlInput(text: String): Long? {
    val value = text.trim()
    if (!BRL_INPUT.matches(value)) return null
    return try {
        BigDecimal(value.replace(".", "").replace(',', '.')).movePointRight(2).longValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

internal fun brl(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(cents, 2))

internal fun brlEditable(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

internal fun localTime(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(ms))

private val BRL_INPUT = Regex("-?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]{1,2})?")
