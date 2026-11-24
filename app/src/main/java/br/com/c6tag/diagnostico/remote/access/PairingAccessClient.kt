package br.com.c6tag.diagnostico.remote.access

import kotlinx.coroutines.CancellationException

internal class PairingAccessClient(
    private val transport: PairingAccessTransport,
) {
    suspend fun issueInvitation(
        lease: PairingHostLease,
    ): PairingAccessResult<PairingInvitation> {
        val body = PairingAccessJson.encodeEmptyObject()
            ?: return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        return execute(
            operation = PairingAccessOperation.IssueInvitation(lease.monitorId),
            credential = lease,
            body = body,
            successStatus = 201,
            write = true,
            parse = PairingAccessJson::parseInvitation,
        )
    }

    suspend fun listInvitations(
        lease: PairingHostLease,
    ): PairingAccessResult<List<PairingInvitationSummary>> =
        execute(
            operation = PairingAccessOperation.ListInvitations(lease.monitorId),
            credential = lease,
            body = null,
            successStatus = 200,
            parse = PairingAccessJson::parseInvitationList,
        )

    suspend fun cancelInvitation(
        lease: PairingHostLease,
        invitationId: String,
    ): PairingAccessResult<Unit> {
        if (!PairingAccessJson.isCanonicalUuid(invitationId)) {
            return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        }
        return execute(
            operation = PairingAccessOperation.CancelInvitation(lease.monitorId, invitationId),
            credential = lease,
            body = null,
            successStatus = 204,
            write = true,
            parse = ::parseEmptyBody,
        )
    }

    suspend fun redeemInvitation(
        invitationSecret: String,
        installationLabel: String = "",
    ): PairingAccessResult<PairingCandidateInstallation> {
        val normalizedSecret = invitationSecret.trim()
        if (
            !PairingAccessJson.isValidInvitationSecret(normalizedSecret) ||
            installationLabel.codePointCount(0, installationLabel.length) > MAX_INSTALLATION_LABEL_CODE_POINTS ||
            !PairingAccessJson.isValidUtf8(installationLabel)
        ) {
            return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        }
        val body = PairingAccessJson.encodeRedemption(normalizedSecret, installationLabel)
            ?: return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        val origin = canonicalTransportOrigin()
            ?: return localFailure(originFailure(), write = true)

        return execute(
            operation = PairingAccessOperation.RedeemInvitation,
            credential = null,
            body = body,
            successStatus = 201,
            write = true,
            parse = { response -> PairingAccessJson.parseRedemption(response, origin) },
        )
    }

    suspend fun currentRequest(
        candidate: PairingCandidateInstallation,
    ): PairingAccessResult<PairingCurrentRequest> {
        val result = execute(
            operation = PairingAccessOperation.CurrentRequest,
            credential = candidate,
            body = null,
            successStatus = 200,
            parse = PairingAccessJson::parseCurrentRequest,
        )
        if (result !is PairingAccessResult.Success) {
            return result
        }
        if (result.value.requestId != candidate.requestId) {
            return PairingAccessResult.Failure(PairingAccessFailureReason.CONTEXT_MISMATCH)
        }
        return result
    }

    suspend fun listRequests(
        lease: PairingHostLease,
    ): PairingAccessResult<List<PairingRequestSummary>> =
        execute(
            operation = PairingAccessOperation.ListRequests(lease.monitorId),
            credential = lease,
            body = null,
            successStatus = 200,
            parse = PairingAccessJson::parseRequestList,
        )

    suspend fun approveRequest(
        lease: PairingHostLease,
        requestId: String,
    ): PairingAccessResult<PairingRequestApproval> {
        if (!PairingAccessJson.isCanonicalUuid(requestId)) {
            return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        }
        val body = PairingAccessJson.encodeEmptyObject()
            ?: return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        val result = execute(
            operation = PairingAccessOperation.ApproveRequest(lease.monitorId, requestId),
            credential = lease,
            body = body,
            successStatus = 200,
            write = true,
            parse = PairingAccessJson::parseApproval,
        )
        if (result !is PairingAccessResult.Success) {
            return result
        }
        return if (result.value.requestId == requestId) {
            result
        } else {
            invalidResponse(write = true)
        }
    }

    suspend fun rejectRequest(
        lease: PairingHostLease,
        requestId: String,
    ): PairingAccessResult<PairingRequestRejection> {
        if (!PairingAccessJson.isCanonicalUuid(requestId)) {
            return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        }
        val body = PairingAccessJson.encodeEmptyObject()
            ?: return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        val result = execute(
            operation = PairingAccessOperation.RejectRequest(lease.monitorId, requestId),
            credential = lease,
            body = body,
            successStatus = 200,
            write = true,
            parse = PairingAccessJson::parseRejection,
        )
        if (result !is PairingAccessResult.Success) {
            return result
        }
        return if (result.value.requestId == requestId) {
            result
        } else {
            invalidResponse(write = true)
        }
    }

    suspend fun listSupports(
        lease: PairingHostLease,
    ): PairingAccessResult<List<PairingSupportMembership>> =
        execute(
            operation = PairingAccessOperation.ListSupports(lease.monitorId),
            credential = lease,
            body = null,
            successStatus = 200,
            parse = PairingAccessJson::parseSupportList,
        )

    suspend fun revokeSupport(
        lease: PairingHostLease,
        membershipId: String,
    ): PairingAccessResult<Unit> {
        if (!PairingAccessJson.isCanonicalUuid(membershipId)) {
            return localFailure(PairingAccessFailureReason.INVALID_PAYLOAD, write = true)
        }
        return execute(
            operation = PairingAccessOperation.RevokeSupport(lease.monitorId, membershipId),
            credential = lease,
            body = null,
            successStatus = 204,
            write = true,
            parse = ::parseEmptyBody,
        )
    }

    suspend fun validateSupportInstallation(
        candidate: PairingCandidateInstallation,
        expectedCurrentRequest: PairingCurrentRequest,
    ): PairingAccessResult<PairingSupportInstallation> {
        val approvedMonitor = expectedCurrentRequest.monitor
        if (
            expectedCurrentRequest.requestId != candidate.requestId ||
            expectedCurrentRequest.status != "Approved" ||
            approvedMonitor == null ||
            approvedMonitor.role != "Support" ||
            !PairingAccessJson.isCanonicalUuid(approvedMonitor.id) ||
            !PairingAccessJson.isCanonicalUuid(approvedMonitor.membershipId)
        ) {
            return PairingAccessResult.Failure(PairingAccessFailureReason.CONTEXT_MISMATCH)
        }

        val result = execute(
            operation = PairingAccessOperation.ValidateSupportInstallation,
            credential = candidate,
            body = null,
            successStatus = 200,
            parse = PairingAccessJson::parseSupportInstallation,
        )
        if (result !is PairingAccessResult.Success) {
            return result
        }

        val installation = result.value
        return if (
            installation.installationId == candidate.installationId &&
            installation.monitorId == approvedMonitor.id &&
            installation.membershipId == approvedMonitor.membershipId &&
            installation.role == "Support" &&
            installation.status == "Active"
        ) {
            result
        } else {
            PairingAccessResult.Failure(PairingAccessFailureReason.CONTEXT_MISMATCH)
        }
    }

    private suspend fun <T> execute(
        operation: PairingAccessOperation,
        credential: PairingAccessCredential?,
        body: PairingAccessRequestBody?,
        successStatus: Int,
        write: Boolean = false,
        parse: (ByteArray) -> T?,
    ): PairingAccessResult<T> {
        val origin = canonicalTransportOrigin()
            ?: return localFailure(originFailure(), write)
        val invalidRequestReason = validateRequest(operation, credential, body, origin)
        if (invalidRequestReason != null) {
            return localFailure(invalidRequestReason, write)
        }

        val result = try {
            transport.execute(operation, credential, body)
        } catch (exception: CancellationException) {
            throw exception
        }

        return when (result) {
            PairingAccessTransportResult.RequestRejected ->
                localFailure(PairingAccessFailureReason.INVALID_REQUEST, write)

            PairingAccessTransportResult.DispatchRejected ->
                PairingAccessResult.Failure(
                    reason = PairingAccessFailureReason.NETWORK_FAILURE,
                    writeDisposition = PairingWriteDisposition.NOT_SENT.takeIf { write },
                )

            PairingAccessTransportResult.NetworkFailure ->
                PairingAccessResult.Failure(
                    reason = PairingAccessFailureReason.NETWORK_FAILURE,
                    writeDisposition = PairingWriteDisposition.AMBIGUOUS.takeIf { write },
                )

            is PairingAccessTransportResult.ResponseTooLarge ->
                invalidResponse(write = write)

            is PairingAccessTransportResult.Response -> {
                val responseBody = result.bodyBytes()
                if (responseBody.size > MAX_PAIRING_ACCESS_RESPONSE_BYTES) {
                    invalidResponse(write = write)
                } else if (result.statusCode == successStatus) {
                    val value = parse(responseBody)
                    if (value == null) invalidResponse(write) else PairingAccessResult.Success(value)
                } else {
                    serverFailure(
                        operation = operation,
                        statusCode = result.statusCode,
                        body = responseBody,
                        retryAfterSeconds = result.retryAfterSeconds,
                        write = write,
                    )
                }
            }
        }
    }

    private fun validateRequest(
        operation: PairingAccessOperation,
        credential: PairingAccessCredential?,
        body: PairingAccessRequestBody?,
        origin: String,
    ): PairingAccessFailureReason? {
        when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport -> {
                val hostLease = credential as? PairingHostLease
                    ?: return PairingAccessFailureReason.INVALID_CREDENTIAL
                if (
                    hostLease.origin != origin ||
                    !PairingAccessJson.isCanonicalUuid(hostLease.monitorId)
                ) {
                    return PairingAccessFailureReason.CONTEXT_MISMATCH
                }
                if (!PairingAccessJson.isSafeBearer(hostLease.accessToken.reveal())) {
                    return PairingAccessFailureReason.INVALID_CREDENTIAL
                }
                if (!validHostOperationBody(operation, body)) {
                    return PairingAccessFailureReason.INVALID_PAYLOAD
                }
            }

            PairingAccessOperation.RedeemInvitation -> {
                if (credential != null) {
                    return PairingAccessFailureReason.INVALID_CREDENTIAL
                }
                if (body == null || body.size > MAX_PAIRING_ACCESS_REQUEST_BYTES) {
                    return PairingAccessFailureReason.INVALID_PAYLOAD
                }
            }

            PairingAccessOperation.CurrentRequest,
            PairingAccessOperation.ValidateSupportInstallation -> {
                val candidate = credential as? PairingCandidateInstallation
                    ?: return PairingAccessFailureReason.INVALID_CREDENTIAL
                if (
                    candidate.origin != origin ||
                    candidate.status != "Pending" ||
                    candidate.requestExpiresAtMillis < 0 ||
                    !PairingAccessJson.isCanonicalUuid(candidate.requestId) ||
                    !PairingAccessJson.isCanonicalUuid(candidate.installationId)
                ) {
                    return PairingAccessFailureReason.CONTEXT_MISMATCH
                }
                if (!PairingAccessJson.isSafeBearer(candidate.accessToken.reveal())) {
                    return PairingAccessFailureReason.INVALID_CREDENTIAL
                }
                if (body != null) {
                    return PairingAccessFailureReason.INVALID_PAYLOAD
                }
            }
        }
        return null
    }

    private fun validHostOperationBody(
        operation: PairingAccessOperation,
        body: PairingAccessRequestBody?,
    ): Boolean {
        val requiredBody = when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest -> "{}"

            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport -> null

            else -> return false
        }
        return if (requiredBody == null) {
            body == null
        } else {
            body?.utf8() == requiredBody
        }
    }

    private fun serverFailure(
        operation: PairingAccessOperation,
        statusCode: Int,
        body: ByteArray,
        retryAfterSeconds: Long?,
        write: Boolean,
    ): PairingAccessResult.Failure {
        val code = PairingAccessJson.parseErrorCode(body)
            ?: return invalidResponse(write)
        val knownFailure = knownFailure(operation, statusCode, code)
        val reason = knownFailure ?: when {
            statusCode == 500 && code == "internal_error" ->
                PairingAccessFailureReason.SERVICE_UNAVAILABLE

            else -> return invalidResponse(write)
        }
        val disposition = when {
            !write -> null
            statusCode in 400..499 && knownFailure != null ->
                PairingWriteDisposition.KNOWN_NOT_WRITTEN

            else -> PairingWriteDisposition.AMBIGUOUS
        }
        return PairingAccessResult.Failure(
            reason = reason,
            writeDisposition = disposition,
            retryAfterSeconds = retryAfterSeconds
                ?.takeIf { statusCode == 429 && reason == PairingAccessFailureReason.RATE_LIMITED && it >= 0 },
        )
    }

    private fun knownFailure(
        operation: PairingAccessOperation,
        statusCode: Int,
        code: String,
    ): PairingAccessFailureReason? {
        val isHostOperation = when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport -> true

            PairingAccessOperation.RedeemInvitation,
            PairingAccessOperation.CurrentRequest,
            PairingAccessOperation.ValidateSupportInstallation -> false
        }
        val hasJsonBody = when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest,
            PairingAccessOperation.RedeemInvitation -> true

            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport,
            PairingAccessOperation.CurrentRequest,
            PairingAccessOperation.ValidateSupportInstallation -> false
        }

        if (
            statusCode == 401 &&
            operation != PairingAccessOperation.RedeemInvitation
        ) {
            return when (code) {
                "access_unauthenticated",
                "unauthenticated" -> PairingAccessFailureReason.NOT_AUTHENTICATED

                else -> null
            }
        }
        if (
            statusCode == 403 &&
            (isHostOperation || operation == PairingAccessOperation.ValidateSupportInstallation)
        ) {
            return when (code) {
                "access_forbidden",
                "access_denied" -> PairingAccessFailureReason.ACCESS_FORBIDDEN

                else -> null
            }
        }
        if (
            statusCode == 404 &&
            (isHostOperation || operation == PairingAccessOperation.CurrentRequest)
        ) {
            return when (code) {
                "resource_not_found",
                "not_found" -> PairingAccessFailureReason.RESOURCE_NOT_FOUND

                else -> null
            }
        }
        if (
            operation.method != PairingAccessMethod.GET &&
            statusCode == 400 &&
            code == "bad_request"
        ) {
            return PairingAccessFailureReason.INVALID_PAYLOAD
        }
        if (hasJsonBody) {
            val requestFailure = when (statusCode to code) {
                400 to "malformed_json" -> PairingAccessFailureReason.INVALID_PAYLOAD
                415 to "unsupported_media_type" ->
                    PairingAccessFailureReason.UNSUPPORTED_MEDIA_TYPE

                422 to "invalid_payload" -> PairingAccessFailureReason.INVALID_PAYLOAD
                else -> null
            }
            if (requestFailure != null) {
                return requestFailure
            }
        }

        return when (operation) {
            is PairingAccessOperation.IssueInvitation ->
                PairingAccessFailureReason.RATE_LIMITED.takeIf {
                    statusCode == 429 && code == "rate_limited"
                }

            is PairingAccessOperation.CancelInvitation ->
                PairingAccessFailureReason.CONFLICT.takeIf {
                    statusCode == 409 && code == "invitation_already_consumed"
                }

            PairingAccessOperation.RedeemInvitation ->
                when (statusCode to code) {
                    410 to "invitation_unavailable" ->
                        PairingAccessFailureReason.INVITATION_UNAVAILABLE

                    429 to "rate_limited" -> PairingAccessFailureReason.RATE_LIMITED
                    else -> null
                }

            PairingAccessOperation.CurrentRequest ->
                when (statusCode to code) {
                    410 to "pairing_request_unavailable" ->
                        PairingAccessFailureReason.INVITATION_UNAVAILABLE

                    429 to "rate_limited" -> PairingAccessFailureReason.RATE_LIMITED
                    else -> null
                }

            is PairingAccessOperation.ApproveRequest ->
                when (statusCode to code) {
                    409 to "support_limit_reached",
                    409 to "pairing_request_terminal" -> PairingAccessFailureReason.CONFLICT

                    410 to "pairing_request_unavailable" ->
                        PairingAccessFailureReason.INVITATION_UNAVAILABLE

                    else -> null
                }

            is PairingAccessOperation.RejectRequest ->
                when (statusCode to code) {
                    409 to "pairing_request_terminal" -> PairingAccessFailureReason.CONFLICT
                    410 to "pairing_request_unavailable" ->
                        PairingAccessFailureReason.INVITATION_UNAVAILABLE

                    else -> null
                }

            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport,
            PairingAccessOperation.ValidateSupportInstallation -> null
        }
    }

    private fun canonicalTransportOrigin(): String? {
        val configuredOrigin = transport.origin ?: return null
        return canonicalOwnerApiOrigin(configuredOrigin)
            ?.takeIf { canonicalOrigin -> canonicalOrigin == configuredOrigin }
    }

    private fun originFailure(): PairingAccessFailureReason =
        transport.configurationFailure
            ?: if (transport.origin.isNullOrEmpty()) {
                PairingAccessFailureReason.NOT_CONFIGURED
            } else {
                PairingAccessFailureReason.INVALID_ORIGIN
            }

    private fun localFailure(
        reason: PairingAccessFailureReason,
        write: Boolean,
    ): PairingAccessResult.Failure =
        PairingAccessResult.Failure(
            reason = reason,
            writeDisposition = PairingWriteDisposition.NOT_SENT.takeIf { write },
        )

    private fun invalidResponse(write: Boolean): PairingAccessResult.Failure =
        PairingAccessResult.Failure(
            reason = PairingAccessFailureReason.INVALID_RESPONSE,
            writeDisposition = PairingWriteDisposition.AMBIGUOUS.takeIf { write },
        )

    private fun parseEmptyBody(body: ByteArray): Unit? = Unit.takeIf { body.isEmpty() }

    private companion object {
        const val MAX_INSTALLATION_LABEL_CODE_POINTS = 128
    }
}
