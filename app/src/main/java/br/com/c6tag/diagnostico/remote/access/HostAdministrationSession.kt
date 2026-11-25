package br.com.c6tag.diagnostico.remote.access

internal class HostAdministrationLease internal constructor(
    private val opaqueId: String,
) {
    init {
        require(opaqueId.isNotBlank())
    }

    override fun toString(): String = "HostAdministrationLease([REDACTED])"

    internal companion object {
        fun generated(): HostAdministrationLease =
            HostAdministrationLease(java.util.UUID.randomUUID().toString())
    }
}

internal sealed interface HostAdministrationSessionResult<out T> {
    data class Ready<T>(val value: T) : HostAdministrationSessionResult<T>

    data class Rejected(
        val failure: HostAdministrationSessionFailure,
    ) : HostAdministrationSessionResult<Nothing>
}

internal enum class HostAdministrationSessionFailure {
    HOST_NOT_ACTIVE,
    SESSION_UNAVAILABLE,
    AUTHORIZATION_REJECTED,
    LEASE_INVALIDATED,
}

internal enum class HostAdministrationSessionInvalidation {
    ADMIN_AUTHORIZATION_REJECTED,
}

internal interface HostAdministrationSessionProvider {
    suspend fun acquireLease(): HostAdministrationSessionResult<HostAdministrationLease>

    suspend fun validateLease(
        lease: HostAdministrationLease,
    ): HostAdministrationSessionResult<Unit>

    suspend fun <T> withValidatedLease(
        lease: HostAdministrationLease,
        operation: suspend (PairingHostLease) -> T,
    ): HostAdministrationSessionResult<T>

    fun invalidateLease(
        lease: HostAdministrationLease,
        reason: HostAdministrationSessionInvalidation,
    )

    suspend fun revalidateAdministrationSession(): HostAdministrationSessionResult<Unit>
}
