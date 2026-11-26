package br.com.c6tag.diagnostico.remote.access

import kotlinx.coroutines.flow.StateFlow

internal interface OwnerIdentity {
    val account: StateFlow<OwnerAccount?>

    suspend fun signIn(googleIdToken: SecretValue): IdentityResult

    suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult

    suspend fun firebaseIdToken(): SecretValue

    suspend fun signOut(): IdentityResult
}

/** A Firebase identity candidate; it does not establish remote host authorization. */
internal data class OwnerAccount(
    val firebaseProjectId: String,
    val uid: String,
    val label: String?,
) {
    init {
        require(firebaseProjectId.isNotBlank())
        require(uid.isNotBlank())
    }
}

internal enum class IdentityOutcome {
    SIGNED_IN_CANDIDATE,
    REAUTHENTICATED,
    SIGNED_OUT,
    NOT_SIGNED_IN,
    ACCOUNT_MISMATCH,
    UNAVAILABLE,
    FAILED,
}

internal data class IdentityResult(val outcome: IdentityOutcome)

internal enum class IdentityFailure {
    NOT_SIGNED_IN,
    FIREBASE_TOKEN_UNAVAILABLE,
}

internal class IdentityOperationException(
    val reason: IdentityFailure,
) : IllegalStateException(reason.name)

/**
 * Opaque token holder. JVM strings cannot be reliably zeroed; its textual
 * representation never includes the held value.
 */
internal class SecretValue private constructor(
    private val value: String,
) {
    internal fun reveal(): String = value

    override fun toString(): String = "[REDACTED]"

    internal companion object {
        fun of(value: String): SecretValue {
            require(value.isNotBlank())
            return SecretValue(value)
        }
    }
}
