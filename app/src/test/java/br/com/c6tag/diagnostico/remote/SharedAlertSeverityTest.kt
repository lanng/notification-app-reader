package br.com.c6tag.diagnostico.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedAlertSeverityTest {
    @Test
    fun exactHalfOfLimitRemainsAttention() {
        assertEquals(
            SharedAlertSeverity.ATTENTION,
            classifySharedAlert(15_000L, 30_000L),
        )
    }

    @Test
    fun oddLimitUsesCeilingForStrictlyBelowHalf() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(150L, 301L))
        assertEquals(SharedAlertSeverity.ATTENTION, classifySharedAlert(151L, 301L))
    }

    @Test
    fun standardLimitClassifiesBelowHalfAtHalfAndAtLimit() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(14_999L, 30_000L))
        assertEquals(SharedAlertSeverity.ATTENTION, classifySharedAlert(15_000L, 30_000L))
        assertEquals(SharedAlertSeverity.NORMAL, classifySharedAlert(30_000L, 30_000L))
    }

    @Test
    fun nullBalanceIsUnknown() {
        assertEquals(SharedAlertSeverity.UNKNOWN, classifySharedAlert(null, 30_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLimitIsRejectedEvenWhenBalanceIsUnknown() {
        classifySharedAlert(null, -1L)
    }

    @Test
    fun zeroLimitClassifiesNonnegativeBalancesAsNormalAndNegativeAsCritical() {
        assertEquals(SharedAlertSeverity.NORMAL, classifySharedAlert(0L, 0L))
        assertEquals(SharedAlertSeverity.NORMAL, classifySharedAlert(1L, 0L))
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(-1L, 0L))
    }

    @Test
    fun negativeBalanceBelowPositiveLimitIsCritical() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(-1L, 1L))
    }

    @Test
    fun longBoundariesDoNotOverflowAtMaximumLimit() {
        assertEquals(SharedAlertSeverity.CRITICAL, classifySharedAlert(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(
            SharedAlertSeverity.CRITICAL,
            classifySharedAlert(4_611_686_018_427_387_903L, Long.MAX_VALUE),
        )
        assertEquals(
            SharedAlertSeverity.ATTENTION,
            classifySharedAlert(4_611_686_018_427_387_904L, Long.MAX_VALUE),
        )
        assertEquals(SharedAlertSeverity.NORMAL, classifySharedAlert(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
