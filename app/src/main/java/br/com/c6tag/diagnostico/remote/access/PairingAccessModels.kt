package br.com.c6tag.diagnostico.remote.access

import java.nio.charset.StandardCharsets

internal const val MAX_PAIRING_ACCESS_RESPONSE_BYTES = 64 * 1024
internal const val MAX_PAIRING_ACCESS_REQUEST_BYTES = 64 * 1024

internal enum class PairingAccessMethod {
    GET,
    POST,
    DELETE,
}

internal sealed interface PairingAccessOperation {
    val method: PairingAccessMethod
    val path: String

    data class IssueInvitation(
        val monitorId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.POST
        override val path: String = "/api/v1/monitors/$monitorId/invitations"
    }

    data class ListInvitations(
        val monitorId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.GET
        override val path: String = "/api/v1/monitors/$monitorId/invitations"
    }

    data class CancelInvitation(
        val monitorId: String,
        val invitationId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.DELETE
        override val path: String = "/api/v1/monitors/$monitorId/invitations/$invitationId"
    }

    data object RedeemInvitation : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.POST
        override val path: String = "/api/v1/pairing-requests"
    }

    data object CurrentRequest : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.GET
        override val path: String = "/api/v1/pairing-requests/current"
    }

    data class ListRequests(
        val monitorId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.GET
        override val path: String = "/api/v1/monitors/$monitorId/pairing-requests"
    }

    data class ApproveRequest(
        val monitorId: String,
        val requestId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.POST
        override val path: String =
            "/api/v1/monitors/$monitorId/pairing-requests/$requestId/approval"
    }

    data class RejectRequest(
        val monitorId: String,
        val requestId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.POST
        override val path: String =
            "/api/v1/monitors/$monitorId/pairing-requests/$requestId/rejection"
    }

    data class ListSupports(
        val monitorId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.GET
        override val path: String = "/api/v1/monitors/$monitorId/supports"
    }

    data class RevokeSupport(
        val monitorId: String,
        val membershipId: String,
    ) : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.DELETE
        override val path: String = "/api/v1/monitors/$monitorId/supports/$membershipId"
    }

    data object ValidateSupportInstallation : PairingAccessOperation {
        override val method: PairingAccessMethod = PairingAccessMethod.GET
        override val path: String = "/api/v1/installation"
    }
}

internal sealed interface PairingAccessCredential {
    val origin: String
    val accessToken: SecretValue
}

internal class PairingHostLease(
    override val origin: String,
    val monitorId: String,
    override val accessToken: SecretValue,
) : PairingAccessCredential {
    override fun toString(): String = "PairingHostLease([REDACTED])"
}

internal class PairingCandidateInstallation(
    override val origin: String,
    val requestId: String,
    val installationId: String,
    val requestExpiresAtMillis: Long,
    override val accessToken: SecretValue,
    val status: String = "Pending",
) : PairingAccessCredential {
    override fun toString(): String = "PairingCandidateInstallation([REDACTED])"
}

/**
 * JSON request bytes may include an invitation secret and must never appear in logs.
 */
internal class PairingAccessRequestBody internal constructor(
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()

    val size: Int
        get() = content.size

    internal fun bytes(): ByteArray = content.copyOf()

    internal fun utf8(): String = String(content, StandardCharsets.UTF_8)

    override fun toString(): String = "PairingAccessRequestBody([REDACTED])"
}

internal sealed interface PairingAccessTransportResult {
    class Response(
        val statusCode: Int,
        body: ByteArray,
        val retryAfterSeconds: Long? = null,
    ) : PairingAccessTransportResult {
        private val responseBody = body.copyOf()

        internal fun bodyBytes(): ByteArray = responseBody.copyOf()

        override fun toString(): String =
            "Response(statusCode=$statusCode, body=[REDACTED])"
    }

    data object DispatchRejected : PairingAccessTransportResult
    data object NetworkFailure : PairingAccessTransportResult
    data object RequestRejected : PairingAccessTransportResult
    data class ResponseTooLarge(val statusCode: Int?) : PairingAccessTransportResult
}

internal interface PairingAccessTransport {
    val origin: String?
    val configurationFailure: PairingAccessFailureReason?
        get() = null

    suspend fun execute(
        operation: PairingAccessOperation,
        credential: PairingAccessCredential?,
        body: PairingAccessRequestBody?,
    ): PairingAccessTransportResult
}

internal sealed interface PairingAccessResult<out T> {
    data class Success<T>(val value: T) : PairingAccessResult<T>

    data class Failure(
        val reason: PairingAccessFailureReason,
        val writeDisposition: PairingWriteDisposition? = null,
        val retryAfterSeconds: Long? = null,
    ) : PairingAccessResult<Nothing>
}

internal enum class PairingAccessFailureReason(
    val code: String,
    val safeMessage: String,
) {
    NOT_CONFIGURED("not_configured", "O serviço de pareamento não está configurado."),
    INVALID_ORIGIN("invalid_origin", "O endereço do serviço de pareamento é inválido."),
    INVALID_CREDENTIAL("invalid_credential", "A credencial de acesso não é válida."),
    INVALID_PAYLOAD("invalid_payload", "A solicitação de pareamento não é válida."),
    NOT_AUTHENTICATED("not_authenticated", "Não foi possível autenticar no serviço."),
    ACCESS_FORBIDDEN("access_forbidden", "Acesso não permitido."),
    RESOURCE_NOT_FOUND("resource_not_found", "O recurso de pareamento não foi encontrado."),
    CONFLICT("conflict", "O estado do pareamento mudou. Atualize e tente novamente."),
    INVITATION_UNAVAILABLE("invitation_unavailable", "O convite não está disponível."),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", "O formato da solicitação não é aceito."),
    RATE_LIMITED("rate_limited", "Muitas tentativas. Tente novamente mais tarde."),
    IDENTITY_VERIFICATION_UNAVAILABLE(
        "identity_verification_unavailable",
        "A verificação de identidade está indisponível.",
    ),
    SERVICE_UNAVAILABLE("service_unavailable", "O serviço de pareamento está indisponível."),
    NETWORK_FAILURE("network_failure", "Não foi possível conectar ao serviço de pareamento."),
    INVALID_RESPONSE("invalid_response", "O serviço retornou uma resposta inesperada."),
    CONTEXT_MISMATCH("context_mismatch", "A credencial não corresponde ao acesso de pareamento."),
    INVALID_REQUEST("invalid_request", "A solicitação não pôde ser enviada."),
}

internal enum class PairingWriteDisposition {
    NOT_SENT,
    KNOWN_NOT_WRITTEN,
    AMBIGUOUS,
}

internal class PairingInvitation(
    val id: String,
    val invitationSecret: SecretValue,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "PairingInvitation([REDACTED])"
}

internal data class PairingInvitationSummary(
    val id: String,
    val status: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String = "PairingInvitationSummary([REDACTED])"
}

internal data class PairingRequestSummary(
    val id: String,
    val status: String,
    val installationId: String,
    val installationLabel: String?,
    val createdAtMillis: Long,
    val requestExpiresAtMillis: Long,
) {
    override fun toString(): String = "PairingRequestSummary([REDACTED])"
}

internal data class PairingApprovedMonitor(
    val id: String,
    val membershipId: String,
    val role: String,
) {
    override fun toString(): String = "PairingApprovedMonitor([REDACTED])"
}

internal data class PairingCurrentRequest(
    val requestId: String,
    val status: String,
    val requestExpiresAtMillis: Long,
    val decidedAtMillis: Long?,
    val monitor: PairingApprovedMonitor?,
) {
    override fun toString(): String = "PairingCurrentRequest([REDACTED])"
}

internal data class PairingRequestApproval(
    val requestId: String,
    val status: String,
    val membershipId: String,
    val role: String,
    val decidedAtMillis: Long,
) {
    override fun toString(): String = "PairingRequestApproval([REDACTED])"
}

internal data class PairingRequestRejection(
    val requestId: String,
    val status: String,
    val decidedAtMillis: Long,
) {
    override fun toString(): String = "PairingRequestRejection([REDACTED])"
}

internal data class PairingSupportMembership(
    val membershipId: String,
    val installationId: String,
    val label: String?,
    val role: String,
    val status: String,
    val approvedAtMillis: Long,
) {
    override fun toString(): String = "PairingSupportMembership([REDACTED])"
}

internal data class PairingSupportInstallation(
    val installationId: String,
    val monitorId: String,
    val membershipId: String,
    val role: String,
    val status: String,
    val monitorGeneration: OwnerGeneration,
) {
    override fun toString(): String = "PairingSupportInstallation([REDACTED])"
}
