package br.com.c6tag.diagnostico.remote.access

import br.com.c6tag.diagnostico.BuildConfig
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.MalformedURLException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class HttpsPairingAccessTransport(
    ownerApiOrigin: String = BuildConfig.OWNER_API_ORIGIN,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { url ->
        val connection = url.openConnection()
        if (connection !is HttpsURLConnection) {
            throw IOException("HTTPS connection unavailable")
        }
        connection
    },
) : PairingAccessTransport {
    override val origin: String? = canonicalOwnerApiOrigin(ownerApiOrigin)
    override val configurationFailure: PairingAccessFailureReason? =
        when {
            ownerApiOrigin.isEmpty() -> PairingAccessFailureReason.NOT_CONFIGURED
            origin == null -> PairingAccessFailureReason.INVALID_ORIGIN
            else -> null
        }

    override suspend fun execute(
        operation: PairingAccessOperation,
        credential: PairingAccessCredential?,
        body: PairingAccessRequestBody?,
    ): PairingAccessTransportResult {
        val configuredOrigin = origin ?: return PairingAccessTransportResult.RequestRejected
        val request = prepareRequest(operation, credential, body, configuredOrigin)
            ?: return PairingAccessTransportResult.RequestRejected
        val url = try {
            URL(configuredOrigin + request.path)
        } catch (_: MalformedURLException) {
            return PairingAccessTransportResult.RequestRejected
        }

        return executeOnIoDispatcher(url, request)
    }

    private fun prepareRequest(
        operation: PairingAccessOperation,
        credential: PairingAccessCredential?,
        body: PairingAccessRequestBody?,
        configuredOrigin: String,
    ): PreparedRequest? {
        if (body != null && body.size > MAX_PAIRING_ACCESS_REQUEST_BYTES) {
            return null
        }
        val requestBytes = body?.bytes()

        val route = routeFor(operation) ?: return null
        if (operation.method != route.method || operation.path != route.path) {
            return null
        }

        val bearerToken = when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport -> {
                val hostLease = credential as? PairingHostLease ?: return null
                val monitorId = when (operation) {
                    is PairingAccessOperation.IssueInvitation -> operation.monitorId
                    is PairingAccessOperation.ListInvitations -> operation.monitorId
                    is PairingAccessOperation.CancelInvitation -> operation.monitorId
                    is PairingAccessOperation.ListRequests -> operation.monitorId
                    is PairingAccessOperation.ApproveRequest -> operation.monitorId
                    is PairingAccessOperation.RejectRequest -> operation.monitorId
                    is PairingAccessOperation.ListSupports -> operation.monitorId
                    is PairingAccessOperation.RevokeSupport -> operation.monitorId
                    else -> return null
                }
                if (
                    hostLease.origin != configuredOrigin ||
                    hostLease.monitorId != monitorId ||
                    !PairingAccessJson.isCanonicalUuid(hostLease.monitorId) ||
                    !PairingAccessJson.isSafeBearer(hostLease.accessToken.reveal()) ||
                    !hasExpectedHostBody(operation, requestBytes)
                ) {
                    return null
                }
                hostLease.accessToken.reveal()
            }

            PairingAccessOperation.RedeemInvitation -> {
                if (credential != null || !isValidRedemptionBody(requestBytes)) {
                    return null
                }
                null
            }

            PairingAccessOperation.CurrentRequest,
            PairingAccessOperation.ValidateSupportInstallation -> {
                val candidate = credential as? PairingCandidateInstallation ?: return null
                if (
                    candidate.origin != configuredOrigin ||
                    candidate.status != "Pending" ||
                    candidate.requestExpiresAtMillis < 0 ||
                    !PairingAccessJson.isCanonicalUuid(candidate.requestId) ||
                    !PairingAccessJson.isCanonicalUuid(candidate.installationId) ||
                    !PairingAccessJson.isSafeBearer(candidate.accessToken.reveal()) ||
                    requestBytes != null
                ) {
                    return null
                }
                candidate.accessToken.reveal()
            }
        }

        return PreparedRequest(
            method = route.method,
            path = route.path,
            bearerToken = bearerToken,
            body = requestBytes,
        )
    }

    private fun routeFor(operation: PairingAccessOperation): Route? =
        when (operation) {
            is PairingAccessOperation.IssueInvitation -> {
                if (!PairingAccessJson.isCanonicalUuid(operation.monitorId)) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.POST,
                        "/api/v1/monitors/${operation.monitorId}/invitations",
                    )
                }
            }

            is PairingAccessOperation.ListInvitations -> {
                if (!PairingAccessJson.isCanonicalUuid(operation.monitorId)) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.GET,
                        "/api/v1/monitors/${operation.monitorId}/invitations",
                    )
                }
            }

            is PairingAccessOperation.CancelInvitation -> {
                if (
                    !PairingAccessJson.isCanonicalUuid(operation.monitorId) ||
                    !PairingAccessJson.isCanonicalUuid(operation.invitationId)
                ) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.DELETE,
                        "/api/v1/monitors/${operation.monitorId}/invitations/${operation.invitationId}",
                    )
                }
            }

            PairingAccessOperation.RedeemInvitation ->
                Route(PairingAccessMethod.POST, "/api/v1/pairing-requests")

            PairingAccessOperation.CurrentRequest ->
                Route(PairingAccessMethod.GET, "/api/v1/pairing-requests/current")

            is PairingAccessOperation.ListRequests -> {
                if (!PairingAccessJson.isCanonicalUuid(operation.monitorId)) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.GET,
                        "/api/v1/monitors/${operation.monitorId}/pairing-requests",
                    )
                }
            }

            is PairingAccessOperation.ApproveRequest -> {
                if (
                    !PairingAccessJson.isCanonicalUuid(operation.monitorId) ||
                    !PairingAccessJson.isCanonicalUuid(operation.requestId)
                ) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.POST,
                        "/api/v1/monitors/${operation.monitorId}/pairing-requests/${operation.requestId}/approval",
                    )
                }
            }

            is PairingAccessOperation.RejectRequest -> {
                if (
                    !PairingAccessJson.isCanonicalUuid(operation.monitorId) ||
                    !PairingAccessJson.isCanonicalUuid(operation.requestId)
                ) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.POST,
                        "/api/v1/monitors/${operation.monitorId}/pairing-requests/${operation.requestId}/rejection",
                    )
                }
            }

            is PairingAccessOperation.ListSupports -> {
                if (!PairingAccessJson.isCanonicalUuid(operation.monitorId)) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.GET,
                        "/api/v1/monitors/${operation.monitorId}/supports",
                    )
                }
            }

            is PairingAccessOperation.RevokeSupport -> {
                if (
                    !PairingAccessJson.isCanonicalUuid(operation.monitorId) ||
                    !PairingAccessJson.isCanonicalUuid(operation.membershipId)
                ) {
                    null
                } else {
                    Route(
                        PairingAccessMethod.DELETE,
                        "/api/v1/monitors/${operation.monitorId}/supports/${operation.membershipId}",
                    )
                }
            }

            PairingAccessOperation.ValidateSupportInstallation ->
                Route(PairingAccessMethod.GET, "/api/v1/installation")
        }

    private fun hasExpectedHostBody(
        operation: PairingAccessOperation,
        requestBytes: ByteArray?,
    ): Boolean {
        val requiresEmptyObject = when (operation) {
            is PairingAccessOperation.IssueInvitation,
            is PairingAccessOperation.ApproveRequest,
            is PairingAccessOperation.RejectRequest -> true

            is PairingAccessOperation.ListInvitations,
            is PairingAccessOperation.CancelInvitation,
            is PairingAccessOperation.ListRequests,
            is PairingAccessOperation.ListSupports,
            is PairingAccessOperation.RevokeSupport -> false

            PairingAccessOperation.RedeemInvitation,
            PairingAccessOperation.CurrentRequest,
            PairingAccessOperation.ValidateSupportInstallation -> return false
        }
        if (!requiresEmptyObject) {
            return requestBytes == null
        }
        return requestBytes?.contentEquals(EMPTY_JSON_OBJECT_BYTES) == true
    }

    private fun isValidRedemptionBody(requestBytes: ByteArray?): Boolean {
        if (requestBytes == null || requestBytes.size > MAX_PAIRING_ACCESS_REQUEST_BYTES) {
            return false
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(requestBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return false
        }

        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                reader.setNestingLimit(32)
                reader.beginObject()
                val names = mutableSetOf<String>()
                var invitationSecret: String? = null
                var installationLabel: String? = null
                var labelSeen = false

                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!names.add(name)) {
                        return false
                    }
                    when (name) {
                        "invitationSecret" -> {
                            if (reader.peek() != JsonToken.STRING) {
                                return false
                            }
                            invitationSecret = reader.nextString()
                        }

                        "installationLabel" -> {
                            if (reader.peek() != JsonToken.STRING) {
                                return false
                            }
                            installationLabel = reader.nextString()
                            labelSeen = true
                        }

                        else -> return false
                    }
                }
                reader.endObject()
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    return false
                }
                if (!PairingAccessJson.isValidInvitationSecret(invitationSecret ?: return false)) {
                    return false
                }
                if (!labelSeen) {
                    true
                } else {
                    val label = installationLabel ?: return false
                    label.isNotEmpty() &&
                        label.codePointCount(0, label.length) <= MAX_INSTALLATION_LABEL_CODE_POINTS &&
                        PairingAccessJson.isValidUtf8(label)
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private suspend fun executeOnIoDispatcher(
        url: URL,
        request: PreparedRequest,
    ): PairingAccessTransportResult = suspendCancellableCoroutine { continuation ->
        val activeConnection = AtomicReference<HttpsURLConnection?>()
        continuation.invokeOnCancellation {
            activeConnection.get()?.disconnect()
        }

        val task = Runnable {
            if (!continuation.isActive) {
                return@Runnable
            }

            var connection: HttpsURLConnection? = null
            var result: PairingAccessTransportResult? = null
            var failure: RuntimeException? = null
            try {
                connection = connectionFactory.open(url)
                activeConnection.set(connection)
                if (!continuation.isActive) {
                    return@Runnable
                }

                configureConnection(connection, request)
                request.body?.let { requestBody ->
                    connection.outputStream.use { output ->
                        output.write(requestBody)
                    }
                }
                connection.connect()
                if (!continuation.isActive) {
                    return@Runnable
                }

                val statusCode = connection.responseCode
                if (!continuation.isActive) {
                    return@Runnable
                }
                val response = when {
                    statusCode in REDIRECT_STATUS_RANGE ->
                        BoundedResponse(ByteArray(0), tooLarge = false)

                    connection.contentLengthLong > MAX_PAIRING_ACCESS_RESPONSE_BYTES ->
                        BoundedResponse(ByteArray(0), tooLarge = true)

                    else -> {
                        val responseStream = if (statusCode >= CLIENT_ERROR_STATUS_CODE) {
                            connection.errorStream
                        } else {
                            connection.inputStream
                        }
                        responseStream?.use(::readBoundedResponse)
                            ?: BoundedResponse(ByteArray(0), tooLarge = false)
                    }
                }
                result = if (response.tooLarge) {
                    PairingAccessTransportResult.ResponseTooLarge(statusCode)
                } else {
                    PairingAccessTransportResult.Response(
                        statusCode = statusCode,
                        body = response.bytes,
                        retryAfterSeconds = readRetryAfterSeconds(connection, statusCode),
                    )
                }
            } catch (_: IOException) {
                result = PairingAccessTransportResult.NetworkFailure
            } catch (runtimeFailure: RuntimeException) {
                failure = runtimeFailure
            } finally {
                try {
                    connection?.disconnect()
                } catch (disconnectFailure: RuntimeException) {
                    val operationFailure = failure
                    if (operationFailure == null) {
                        failure = disconnectFailure
                    } else if (operationFailure !== disconnectFailure) {
                        operationFailure.addSuppressed(disconnectFailure)
                    }
                } finally {
                    if (connection != null) {
                        activeConnection.compareAndSet(connection, null)
                    }
                }
            }

            if (failure != null) {
                continuation.resumeWithExceptionIfActive(requireNotNull(failure))
            } else {
                result?.let { transportResult ->
                    continuation.resumeIfActive(transportResult)
                }
            }
        }

        try {
            ioDispatcher.dispatch(continuation.context, task)
        } catch (_: RejectedExecutionException) {
            continuation.resumeIfActive(PairingAccessTransportResult.DispatchRejected)
        }
    }

    private fun configureConnection(
        connection: HttpsURLConnection,
        request: PreparedRequest,
    ) {
        connection.requestMethod = request.method.name
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty(ACCEPT_HEADER, JSON_CONTENT_TYPE)
        request.bearerToken?.let { accessToken ->
            connection.setRequestProperty(AUTHORIZATION_HEADER, "Bearer $accessToken")
        }
        connection.doInput = true
        connection.doOutput = request.body != null
        if (request.body != null) {
            connection.setRequestProperty(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
        }
    }

    private fun readBoundedResponse(input: java.io.InputStream): BoundedResponse {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val bytesRemainingToCheck = MAX_PAIRING_ACCESS_RESPONSE_BYTES + 1 - output.size()
            if (bytesRemainingToCheck <= 0) {
                return BoundedResponse(ByteArray(0), tooLarge = true)
            }

            val bytesRead = input.read(buffer, 0, minOf(buffer.size, bytesRemainingToCheck))
            if (bytesRead < 0) {
                return BoundedResponse(output.toByteArray(), tooLarge = false)
            }
            if (bytesRead == 0) {
                val singleByte = input.read()
                if (singleByte < 0) {
                    return BoundedResponse(output.toByteArray(), tooLarge = false)
                }
                output.write(singleByte)
            } else {
                output.write(buffer, 0, bytesRead)
            }
            if (output.size() > MAX_PAIRING_ACCESS_RESPONSE_BYTES) {
                return BoundedResponse(ByteArray(0), tooLarge = true)
            }
        }
    }

    private fun readRetryAfterSeconds(
        connection: HttpsURLConnection,
        statusCode: Int,
    ): Long? {
        if (statusCode != RATE_LIMITED_STATUS_CODE) {
            return null
        }
        val value = connection.getHeaderField(RETRY_AFTER_HEADER) ?: return null
        if (value.isEmpty() || value.length > MAX_RETRY_AFTER_DIGITS) {
            return null
        }
        if (value.any { character -> character !in '0'..'9' }) {
            return null
        }
        return value.toLongOrNull()
    }

    private fun CancellableContinuation<PairingAccessTransportResult>.resumeIfActive(
        result: PairingAccessTransportResult,
    ) {
        if (isActive) {
            resume(result)
        }
    }

    private fun CancellableContinuation<PairingAccessTransportResult>.resumeWithExceptionIfActive(
        failure: RuntimeException,
    ) {
        if (isActive) {
            resumeWithException(failure)
        }
    }

    private class PreparedRequest(
        val method: PairingAccessMethod,
        val path: String,
        val bearerToken: String?,
        val body: ByteArray?,
    )

    private class Route(
        val method: PairingAccessMethod,
        val path: String,
    )

    private class BoundedResponse(
        val bytes: ByteArray,
        val tooLarge: Boolean,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 3_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val READ_BUFFER_SIZE = 4_096
        const val JSON_CONTENT_TYPE = "application/json"
        const val ACCEPT_HEADER = "Accept"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val RETRY_AFTER_HEADER = "Retry-After"
        const val RATE_LIMITED_STATUS_CODE = 429
        const val CLIENT_ERROR_STATUS_CODE = 400
        const val MAX_RETRY_AFTER_DIGITS = 19
        const val MAX_INSTALLATION_LABEL_CODE_POINTS = 128
        val REDIRECT_STATUS_RANGE = 300..399
        val EMPTY_JSON_OBJECT_BYTES = "{}".toByteArray(StandardCharsets.UTF_8)
    }
}
