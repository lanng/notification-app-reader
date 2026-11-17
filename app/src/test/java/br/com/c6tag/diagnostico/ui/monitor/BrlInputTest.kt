package br.com.c6tag.diagnostico.ui.monitor

import org.junit.Assert.*
import org.junit.Test

class BrlInputTest {
    @Test fun wholeDecimalAndGroupedAmountsUseIntegerCents() {
        assertEquals(30_000L, parseBrlInput("300"))
        assertEquals(30_010L, parseBrlInput("300,1"))
        assertEquals(123_456L, parseBrlInput("1.234,56"))
        assertEquals(0L, parseBrlInput(" 0,00 "))
        assertEquals(-123L, parseBrlInput("-1,23"))
    }

    @Test fun invalidAmbiguousAndOverpreciseInputIsNotRounded() {
        listOf("", " ", "1.23", "1,234", "1,2,3", "1e3", "NaN", "R$ 1,00", "+1", "1 000", "--1", "1.", ",50")
            .forEach { assertNull(it, parseBrlInput(it)) }
    }

    @Test fun longBoundaryIsExactAndOverflowIsRejected() {
        assertEquals(Long.MAX_VALUE, parseBrlInput("92.233.720.368.547.758,07"))
        assertEquals(Long.MIN_VALUE, parseBrlInput("-92.233.720.368.547.758,08"))
        assertNull(parseBrlInput("92.233.720.368.547.758,08"))
        assertNull(parseBrlInput("-92.233.720.368.547.758,09"))
    }
}
