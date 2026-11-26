package br.com.c6tag.diagnostico.remote

internal enum class SharedAlertSeverity { UNKNOWN, NORMAL, ATTENTION, CRITICAL }

internal fun classifySharedAlert(balanceCents: Long?, limitCents: Long): SharedAlertSeverity {
    require(limitCents >= 0L)
    if (balanceCents == null) return SharedAlertSeverity.UNKNOWN
    if (balanceCents >= limitCents) return SharedAlertSeverity.NORMAL

    val firstNonCriticalCent = limitCents / 2L + limitCents % 2L
    return if (balanceCents < firstNonCriticalCent) {
        SharedAlertSeverity.CRITICAL
    } else {
        SharedAlertSeverity.ATTENTION
    }
}
