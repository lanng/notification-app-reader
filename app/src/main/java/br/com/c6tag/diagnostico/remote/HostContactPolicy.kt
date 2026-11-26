package br.com.c6tag.diagnostico.remote

internal enum class HostContactState {
    UNCONFIRMED,
    RECENT,
    STALE,
    CLOCK_UNCERTAIN,
}

internal fun hostContactState(lastContactAtMs: Long?, nowMs: Long): HostContactState {
    require(nowMs >= 0L)
    if (lastContactAtMs == null) return HostContactState.UNCONFIRMED

    require(lastContactAtMs >= 0L)
    if (nowMs < lastContactAtMs) return HostContactState.CLOCK_UNCERTAIN

    return if (nowMs - lastContactAtMs >= 3_600_000L) {
        HostContactState.STALE
    } else {
        HostContactState.RECENT
    }
}
