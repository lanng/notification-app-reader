package br.com.c6tag.diagnostico.diagnostic

enum class DiagnosticEventType {
    POSTED_OR_UPDATED,
    REMOVED,
    ACTIVE_SNAPSHOT,
    LISTENER_CONNECTED,
    LISTENER_DISCONNECTED,
}

enum class CandidateKind {
    TAG_DEBIT,
    PIX_CREDIT,
    GROUP_SUMMARY,
    UNKNOWN,
}

data class NotificationInterpretation(
    val kind: CandidateKind,
    val amountCents: Long? = null,
    val reason: String? = null,
) {
    init {
        when (kind) {
            CandidateKind.TAG_DEBIT, CandidateKind.PIX_CREDIT ->
                require(amountCents != null && amountCents >= 0L && reason == null)

            CandidateKind.GROUP_SUMMARY ->
                require(amountCents == null && reason == null)

            CandidateKind.UNKNOWN ->
                require(amountCents == null && !reason.isNullOrBlank())
        }
    }
}

data class DiagnosticEvent(
    val type: DiagnosticEventType,
    val observedAtMs: Long,
    val postedAtMs: Long? = null,
    val packageName: String? = null,
    val key: String? = null,
    val notificationId: Int? = null,
    val tag: String? = null,
    val groupKey: String? = null,
    val groupSummary: Boolean? = null,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val lines: List<String> = emptyList(),
    val removalReason: Int? = null,
    val interpretation: NotificationInterpretation? = null,
)
