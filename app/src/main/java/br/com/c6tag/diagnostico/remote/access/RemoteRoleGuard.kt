package br.com.c6tag.diagnostico.remote.access

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RemoteRoleGuardBlock {
    OPPOSITE_SESSION_PRESENT,
    PRESENCE_UNAVAILABLE,
}

internal sealed interface RemoteRoleGuardResult<out T> {
    data class Begun<T>(val value: T) : RemoteRoleGuardResult<T>

    data class Blocked(val reason: RemoteRoleGuardBlock) : RemoteRoleGuardResult<Nothing>
}

internal class RemoteRoleGuard(
    private val hostPresence: suspend () -> RemoteSessionPresence,
    private val supportPresence: suspend () -> RemoteSessionPresence,
) {
    private val reservationMutex = Mutex()

    suspend fun <T> beginHostAttempt(
        beginJournal: suspend () -> T,
    ): RemoteRoleGuardResult<T> =
        reserve(supportPresence, beginJournal)

    suspend fun <T> beginSupportAttempt(
        beginJournal: suspend () -> T,
    ): RemoteRoleGuardResult<T> =
        reserve(hostPresence, beginJournal)

    private suspend fun <T> reserve(
        oppositePresence: suspend () -> RemoteSessionPresence,
        beginJournal: suspend () -> T,
    ): RemoteRoleGuardResult<T> =
        reservationMutex.withLock {
            when (oppositePresence()) {
                RemoteSessionPresence.Present ->
                    RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT)

                RemoteSessionPresence.Absent ->
                    RemoteRoleGuardResult.Begun(beginJournal())

                is RemoteSessionPresence.Unavailable ->
                    RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.PRESENCE_UNAVAILABLE)
            }
        }
}
