package br.com.c6tag.diagnostico.remote

internal data class AlertSoundSession(
    val occurrenceId: String,
    val startedAtElapsedMs: Long,
    val stopped: Boolean = false,
) {
    init {
        require(occurrenceId.isNotBlank())
        require(startedAtElapsedMs >= 0L)
    }

    fun stop(expectedOccurrenceId: String): AlertSoundSession =
        if (expectedOccurrenceId == occurrenceId) copy(stopped = true) else this

    fun isActive(nowElapsedMs: Long): Boolean =
        !stopped &&
            nowElapsedMs >= startedAtElapsedMs &&
            nowElapsedMs - startedAtElapsedMs < 30_000L
}
