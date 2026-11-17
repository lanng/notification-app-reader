package br.com.c6tag.diagnostico.diagnostic

internal data class StoredDiagnosticEvent(
    val id: Long,
    val event: DiagnosticEvent,
)

internal data class DiagnosticSnapshotMetadata(
    val total: Long,
    val minId: Long?,
    val maxId: Long?,
)

/**
 * Synchronous storage boundary. Callers that may run on the main thread must
 * dispatch these operations to an IO dispatcher.
 */
internal interface DiagnosticStore {
    fun append(event: DiagnosticEvent): Long

    fun page(
        beforeId: Long? = null,
        limit: Int,
    ): List<StoredDiagnosticEvent>

    fun clear()

    /**
     * The sequence is valid only for the duration of [block]. Implementations
     * must keep the snapshot transaction and any backing resources open until
     * the callback returns.
     */
    fun <T> exportSnapshot(
        block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
    ): T
}
