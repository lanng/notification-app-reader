package br.com.c6tag.diagnostico.remote.access

/** A Firebase sign-in candidate is not authorization for a remote host. */
internal sealed interface AccountState {
    data object SignedOut : AccountState

    data class SignedInCandidate(
        val account: OwnerAccount,
    ) : AccountState
}

internal fun OwnerAccount?.toAccountState(): AccountState =
    this?.let(AccountState::SignedInCandidate) ?: AccountState.SignedOut

internal const val MAX_OWNER_ACCESS_RESPONSE_BYTES = 64 * 1024

internal enum class OwnerAccessMethod {
    GET,
    POST,
}

internal enum class OwnerAccessPath(val value: String) {
    OWNER_MONITOR("/api/v1/owner/monitor"),
    HOST_ACTIVATIONS("/api/v1/owner/host-activations"),
    INSTALLATION("/api/v1/installation"),
}

internal enum class OwnerAccessCredentialKind {
    FIREBASE_ID_TOKEN,
    INSTALLATION_ACCESS_TOKEN,
}

internal class OwnerGeneration private constructor(
    val decimal: String,
) {
    override fun toString(): String = decimal

    override fun equals(other: Any?): Boolean =
        other is OwnerGeneration && decimal == other.decimal

    override fun hashCode(): Int = decimal.hashCode()

    internal companion object {
        private val canonicalDecimal = Regex("0|[1-9][0-9]*")

        fun parse(value: String): OwnerGeneration? =
            if (canonicalDecimal.matches(value) && value.toLongOrNull() != null) {
                OwnerGeneration(value)
            } else {
                null
            }
    }
}

internal data class ExpectedHostAccessContext(
    val owner: OwnerAccount,
    val origin: String,
    val monitorId: String,
    val installationId: String,
    val membershipId: String,
    val generation: OwnerGeneration,
)

internal class OwnerAccessCredential internal constructor(
    internal val origin: String,
    internal val kind: OwnerAccessCredentialKind,
    internal val owner: OwnerAccount,
    internal val secret: SecretValue,
    internal val hostContext: ExpectedHostAccessContext? = null,
) {
    internal val isHeaderSafe: Boolean
        get() = secret.reveal().all { character -> character.code in 0x21..0x7E }

    override fun toString(): String = "OwnerAccessCredential([REDACTED])"
}

internal sealed interface OwnerAccessTransportResult {
    class Response(
        val statusCode: Int,
        val body: ByteArray,
    ) : OwnerAccessTransportResult {
        override fun toString(): String =
            "Response(statusCode=$statusCode, body=[REDACTED])"
    }

    data object DispatchRejected : OwnerAccessTransportResult
    data object NetworkFailure : OwnerAccessTransportResult
    data object RequestRejected : OwnerAccessTransportResult
    data class ResponseTooLarge(val statusCode: Int?) : OwnerAccessTransportResult
}

internal interface OwnerAccessTransport {
    val origin: String?
    val configurationFailure: OwnerAccessFailureReason?
        get() = null

    suspend fun execute(
        method: OwnerAccessMethod,
        path: OwnerAccessPath,
        credential: OwnerAccessCredential,
        body: String?,
    ): OwnerAccessTransportResult
}

internal sealed interface OwnerAccessResult<out T> {
    data class Success<T>(val value: T) : OwnerAccessResult<T>
    data class Failure(
        val reason: OwnerAccessFailureReason,
        val activationDisposition: ActivationDisposition? = null,
    ) : OwnerAccessResult<Nothing>
}

internal enum class OwnerAccessFailureReason(val safeMessage: String) {
    NOT_CONFIGURED("O serviço de acesso remoto não está configurado."),
    INVALID_ORIGIN("O endereço do serviço de acesso remoto é inválido."),
    NOT_AUTHENTICATED("Não foi possível autenticar com o serviço."),
    OWNER_NOT_AUTHORIZED("Esta identidade não está autorizada como proprietária."),
    RECENT_AUTHENTICATION_REQUIRED("É necessária uma autenticação recente."),
    ACCESS_FORBIDDEN("Acesso não permitido."),
    GENERATION_CONFLICT("O estado de acesso mudou. Atualize antes de continuar."),
    INVALID_PAYLOAD("A solicitação de acesso não é válida."),
    RATE_LIMITED("Muitas tentativas. Tente novamente mais tarde."),
    IDENTITY_VERIFICATION_UNAVAILABLE("A verificação de identidade está indisponível."),
    SERVER_FAILURE("O serviço de acesso remoto está indisponível."),
    NETWORK_FAILURE("Não foi possível conectar ao serviço de acesso remoto."),
    INVALID_RESPONSE("O serviço retornou uma resposta inesperada."),
    HOST_CONTEXT_MISMATCH("A instalação não corresponde ao acesso salvo.");
}

internal enum class ActivationDisposition {
    NOT_SENT,
    KNOWN_NOT_WRITTEN,
    AMBIGUOUS,
}

internal data class OwnerMonitorSnapshot(
    val monitorId: String?,
    val generation: OwnerGeneration,
    val hostInstallationId: String?,
    val hostLabel: String?,
    val requiresReference: Boolean,
)

internal class HostActivation internal constructor(
    val monitorId: String,
    val installationId: String,
    val membershipId: String,
    val generation: OwnerGeneration,
    val accessToken: SecretValue,
    val createdAtMillis: Long,
    private val owner: OwnerAccount,
    private val origin: String,
) {
    fun expectedHostContext(): ExpectedHostAccessContext = ExpectedHostAccessContext(
        owner = owner,
        origin = origin,
        monitorId = monitorId,
        installationId = installationId,
        membershipId = membershipId,
        generation = generation,
    )

    override fun toString(): String =
        "HostActivation(monitorId=$monitorId, installationId=$installationId, " +
            "membershipId=$membershipId, generation=$generation, " +
            "accessToken=[REDACTED], createdAtMillis=$createdAtMillis)"
}

internal data class VerifiedHostAccess(
    val installationId: String,
    val monitorId: String,
    val membershipId: String,
    val generation: OwnerGeneration,
)
