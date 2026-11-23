package br.com.c6tag.diagnostico.remote.access

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class OwnerAccessClient(
    private val transport: OwnerAccessTransport = HttpsOwnerAccessTransport(),
) {
    suspend fun discoverOwnerMonitor(
        owner: OwnerAccount,
        firebaseIdToken: SecretValue,
    ): OwnerAccessResult<OwnerMonitorSnapshot> {
        currentCoroutineContext().ensureActive()
        val credential = firebaseCredential(owner, firebaseIdToken) ?: return configurationFailure()
        val response = exchange(
            method = OwnerAccessMethod.GET,
            path = OwnerAccessPath.OWNER_MONITOR,
            credential = credential,
            body = null,
        )
        if (response is ExchangeResult.Failure) {
            currentCoroutineContext().ensureActive()
            return OwnerAccessResult.Failure(response.reason)
        }

        response as ExchangeResult.HttpResponse
        if (response.statusCode != 200) {
            val failure = serverFailure(response.statusCode, response.body, isActivation = false)
            currentCoroutineContext().ensureActive()
            return failure
        }

        val snapshot = parseResponse(response.body) { reader ->
            readDataEnvelope(reader, ::readOwnerMonitor)
        }
        currentCoroutineContext().ensureActive()
        if (snapshot == null) {
            return OwnerAccessResult.Failure(OwnerAccessFailureReason.INVALID_RESPONSE)
        }

        return OwnerAccessResult.Success(snapshot)
    }

    suspend fun activateHost(
        owner: OwnerAccount,
        firebaseIdToken: SecretValue,
        expectedGeneration: OwnerGeneration,
        confirmation: Boolean = true,
    ): OwnerAccessResult<HostActivation> {
        currentCoroutineContext().ensureActive()
        if (!confirmation) {
            return OwnerAccessResult.Failure(
                reason = OwnerAccessFailureReason.INVALID_PAYLOAD,
                activationDisposition = ActivationDisposition.NOT_SENT,
            )
        }

        val credential = firebaseCredential(owner, firebaseIdToken)
            ?: return configurationFailure(activationNotSent = true)
        val body = """{"confirmation":true,"expectedGeneration":"${expectedGeneration.decimal}"}"""
        val response = exchange(
            method = OwnerAccessMethod.POST,
            path = OwnerAccessPath.HOST_ACTIVATIONS,
            credential = credential,
            body = body,
        )
        if (response is ExchangeResult.Failure) {
            currentCoroutineContext().ensureActive()
            return OwnerAccessResult.Failure(
                reason = response.reason,
                activationDisposition = response.activationDisposition,
            )
        }

        response as ExchangeResult.HttpResponse
        if (response.statusCode != 200 && response.statusCode != 201) {
            val failure = serverFailure(response.statusCode, response.body, isActivation = true)
            currentCoroutineContext().ensureActive()
            return failure
        }

        val activation = parseResponse(response.body) { reader ->
            readDataEnvelope(reader) { dataReader ->
                readActivation(dataReader, owner, credential.origin)
            }
        }
        currentCoroutineContext().ensureActive()
        if (activation == null) {
            return OwnerAccessResult.Failure(
                reason = OwnerAccessFailureReason.INVALID_RESPONSE,
                activationDisposition = ActivationDisposition.AMBIGUOUS,
            )
        }

        return OwnerAccessResult.Success(activation)
    }

    suspend fun discoverInstallationAccess(
        accessToken: SecretValue,
        expectedContext: ExpectedHostAccessContext,
    ): OwnerAccessResult<VerifiedHostAccess> {
        currentCoroutineContext().ensureActive()
        val origin = verifiedOrigin()
            ?: return configurationFailure()
        if (
            expectedContext.origin != origin ||
            expectedContext.monitorId.isBlank() ||
            expectedContext.installationId.isBlank() ||
            expectedContext.membershipId.isBlank()
        ) {
            return OwnerAccessResult.Failure(OwnerAccessFailureReason.HOST_CONTEXT_MISMATCH)
        }

        val credential = OwnerAccessCredential(
            origin = origin,
            kind = OwnerAccessCredentialKind.INSTALLATION_ACCESS_TOKEN,
            owner = expectedContext.owner,
            secret = accessToken,
            hostContext = expectedContext,
        )
        val response = exchange(
            method = OwnerAccessMethod.GET,
            path = OwnerAccessPath.INSTALLATION,
            credential = credential,
            body = null,
        )
        if (response is ExchangeResult.Failure) {
            currentCoroutineContext().ensureActive()
            return OwnerAccessResult.Failure(response.reason)
        }

        response as ExchangeResult.HttpResponse
        if (response.statusCode != 200) {
            val failure = serverFailure(response.statusCode, response.body, isActivation = false)
            currentCoroutineContext().ensureActive()
            return failure
        }

        val access = parseResponse(response.body) { reader ->
            readDataEnvelope(reader, ::readInstallationAccess)
        }
        currentCoroutineContext().ensureActive()
        if (access == null) {
            return OwnerAccessResult.Failure(OwnerAccessFailureReason.INVALID_RESPONSE)
        }

        if (
            access.role != "Host" ||
            access.status != "Active" ||
            access.installationId != expectedContext.installationId ||
            access.monitorId != expectedContext.monitorId ||
            access.membershipId != expectedContext.membershipId ||
            access.generation != expectedContext.generation
        ) {
            return OwnerAccessResult.Failure(OwnerAccessFailureReason.HOST_CONTEXT_MISMATCH)
        }

        return OwnerAccessResult.Success(
            VerifiedHostAccess(
                installationId = access.installationId,
                monitorId = access.monitorId,
                membershipId = access.membershipId,
                generation = access.generation,
            ),
        )
    }

    private fun firebaseCredential(
        owner: OwnerAccount,
        firebaseIdToken: SecretValue,
    ): OwnerAccessCredential? {
        val origin = verifiedOrigin() ?: return null
        return OwnerAccessCredential(
            origin = origin,
            kind = OwnerAccessCredentialKind.FIREBASE_ID_TOKEN,
            owner = owner,
            secret = firebaseIdToken,
        )
    }

    private fun verifiedOrigin(): String? {
        val configuredOrigin = transport.origin ?: return null
        return canonicalOwnerApiOrigin(configuredOrigin)
            ?.takeIf { canonicalOrigin -> canonicalOrigin == configuredOrigin }
    }

    private fun configurationFailure(
        activationNotSent: Boolean = false,
    ): OwnerAccessResult.Failure {
        val reason = transport.configurationFailure
            ?: if (transport.origin.isNullOrEmpty()) {
                OwnerAccessFailureReason.NOT_CONFIGURED
            } else {
                OwnerAccessFailureReason.INVALID_ORIGIN
            }
        return OwnerAccessResult.Failure(
            reason = reason,
            activationDisposition = if (activationNotSent) {
                ActivationDisposition.NOT_SENT
            } else {
                null
            },
        )
    }

    private suspend fun exchange(
        method: OwnerAccessMethod,
        path: OwnerAccessPath,
        credential: OwnerAccessCredential,
        body: String?,
    ): ExchangeResult {
        val origin = verifiedOrigin() ?: return ExchangeResult.Failure(
            reason = transport.configurationFailure ?: if (transport.origin.isNullOrEmpty()) {
                OwnerAccessFailureReason.NOT_CONFIGURED
            } else {
                OwnerAccessFailureReason.INVALID_ORIGIN
            },
            activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                ActivationDisposition.NOT_SENT
            } else {
                null
            },
        )
        if (
            credential.origin != origin ||
            !credential.isHeaderSafe ||
            !isExpectedRequest(method, path, credential, body, origin)
        ) {
            return ExchangeResult.Failure(
                reason = OwnerAccessFailureReason.INVALID_RESPONSE,
                activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    ActivationDisposition.NOT_SENT
                } else {
                    null
                },
            )
        }

        val result = try {
            transport.execute(method, path, credential, body)
        } catch (exception: CancellationException) {
            throw exception
        }

        return when (result) {
            OwnerAccessTransportResult.RequestRejected -> ExchangeResult.Failure(
                reason = OwnerAccessFailureReason.INVALID_RESPONSE,
                activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    ActivationDisposition.NOT_SENT
                } else {
                    null
                },
            )

            OwnerAccessTransportResult.DispatchRejected -> ExchangeResult.Failure(
                reason = OwnerAccessFailureReason.NETWORK_FAILURE,
                activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    ActivationDisposition.NOT_SENT
                } else {
                    null
                },
            )

            OwnerAccessTransportResult.NetworkFailure -> ExchangeResult.Failure(
                reason = OwnerAccessFailureReason.NETWORK_FAILURE,
                activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    ActivationDisposition.AMBIGUOUS
                } else {
                    null
                },
            )

            is OwnerAccessTransportResult.ResponseTooLarge -> ExchangeResult.Failure(
                reason = OwnerAccessFailureReason.INVALID_RESPONSE,
                activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    dispositionForHttpStatus(result.statusCode)
                } else {
                    null
                },
            )

            is OwnerAccessTransportResult.Response -> {
                if (result.body.size > MAX_OWNER_ACCESS_RESPONSE_BYTES) {
                    ExchangeResult.Failure(
                        reason = OwnerAccessFailureReason.INVALID_RESPONSE,
                        activationDisposition = if (path == OwnerAccessPath.HOST_ACTIVATIONS) {
                            dispositionForHttpStatus(result.statusCode)
                        } else {
                            null
                        },
                    )
                } else {
                    ExchangeResult.HttpResponse(result.statusCode, result.body)
                }
            }
        }
    }

    private fun isExpectedRequest(
        method: OwnerAccessMethod,
        path: OwnerAccessPath,
        credential: OwnerAccessCredential,
        body: String?,
        origin: String,
    ): Boolean =
        when (path) {
            OwnerAccessPath.OWNER_MONITOR ->
                method == OwnerAccessMethod.GET &&
                    credential.kind == OwnerAccessCredentialKind.FIREBASE_ID_TOKEN &&
                    credential.hostContext == null &&
                    body == null

            OwnerAccessPath.HOST_ACTIVATIONS ->
                method == OwnerAccessMethod.POST &&
                    credential.kind == OwnerAccessCredentialKind.FIREBASE_ID_TOKEN &&
                    credential.hostContext == null &&
                    body?.matches(ACTIVATION_BODY_PATTERN) == true

            OwnerAccessPath.INSTALLATION -> {
                val context = credential.hostContext
                method == OwnerAccessMethod.GET &&
                    credential.kind == OwnerAccessCredentialKind.INSTALLATION_ACCESS_TOKEN &&
                    body == null &&
                    context != null &&
                    context.origin == origin &&
                    context.owner == credential.owner
            }
        }

    private fun serverFailure(
        statusCode: Int,
        body: ByteArray,
        isActivation: Boolean,
    ): OwnerAccessResult.Failure {
        val code = parseResponse(body, ::readErrorCode)
        val reason = code?.let { knownFailure(statusCode, it) }
            ?: OwnerAccessFailureReason.INVALID_RESPONSE
        val disposition = if (isActivation) dispositionForHttpStatus(statusCode) else null
        return OwnerAccessResult.Failure(reason, disposition)
    }

    private fun dispositionForHttpStatus(statusCode: Int?): ActivationDisposition =
        if (statusCode != null && statusCode in KNOWN_NO_WRITE_STATUSES) {
            ActivationDisposition.KNOWN_NOT_WRITTEN
        } else {
            ActivationDisposition.AMBIGUOUS
        }

    private fun knownFailure(statusCode: Int, code: String): OwnerAccessFailureReason? =
        when (statusCode to code) {
            400 to "bad_request",
            400 to "malformed_json",
            422 to "invalid_payload" -> OwnerAccessFailureReason.INVALID_PAYLOAD

            401 to "access_unauthenticated",
            401 to "unauthenticated" -> OwnerAccessFailureReason.NOT_AUTHENTICATED

            403 to "owner_not_authorized" -> OwnerAccessFailureReason.OWNER_NOT_AUTHORIZED
            403 to "recent_authentication_required" ->
                OwnerAccessFailureReason.RECENT_AUTHENTICATION_REQUIRED

            403 to "access_forbidden",
            403 to "access_denied" -> OwnerAccessFailureReason.ACCESS_FORBIDDEN

            409 to "generation_conflict" -> OwnerAccessFailureReason.GENERATION_CONFLICT
            429 to "rate_limited" -> OwnerAccessFailureReason.RATE_LIMITED
            500 to "internal_error" -> OwnerAccessFailureReason.SERVER_FAILURE
            503 to "identity_verification_unavailable" ->
                OwnerAccessFailureReason.IDENTITY_VERIFICATION_UNAVAILABLE

            else -> null
        }

    private fun readErrorCode(reader: JsonReader): String {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var code: String? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "code" -> code = readNonBlankString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return code ?: invalidJson()
    }

    /**
     * Known response fields are strict; unknown fields are skipped for forward compatibility.
     */
    private fun readOwnerMonitor(reader: JsonReader): OwnerMonitorSnapshot {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var monitorSeen = false
        var monitorId: String? = null
        var generation: OwnerGeneration? = null
        var hostSeen = false
        var hostInstallationId: String? = null
        var hostLabel: String? = null
        var requiresReference: Boolean? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "monitor" -> {
                    monitorSeen = true
                    monitorId = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        readObjectId(reader)
                    }
                }

                "generation" -> generation = readGeneration(reader)

                "host" -> {
                    hostSeen = true
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        val host = readOwnerHost(reader)
                        hostInstallationId = host.installationId
                        hostLabel = host.label
                    }
                }

                "requiresReference" -> requiresReference = readBoolean(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (!monitorSeen || !hostSeen || requiresReference != true) {
            invalidJson()
        }

        return OwnerMonitorSnapshot(
            monitorId = monitorId,
            generation = generation ?: invalidJson(),
            hostInstallationId = hostInstallationId,
            hostLabel = hostLabel,
            requiresReference = true,
        )
    }

    private fun readObjectId(reader: JsonReader): String {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readNonBlankString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return id ?: invalidJson()
    }

    private fun readOwnerHost(reader: JsonReader): OwnerHostFields {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var installationId: String? = null
        var labelSeen = false
        var label: String? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "installationId" -> installationId = readNonBlankString(reader)
                "label" -> {
                    labelSeen = true
                    label = readNullableString(reader)
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return OwnerHostFields(
            installationId = installationId ?: invalidJson(),
            label = if (labelSeen) label else invalidJson(),
        )
    }

    private fun readActivation(
        reader: JsonReader,
        owner: OwnerAccount,
        origin: String,
    ): HostActivation {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var monitorId: String? = null
        var installationId: String? = null
        var membershipId: String? = null
        var role: String? = null
        var generation: OwnerGeneration? = null
        var requiresReference: Boolean? = null
        var accessToken: String? = null
        var createdAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "monitorId" -> monitorId = readNonBlankString(reader)
                "installationId" -> installationId = readNonBlankString(reader)
                "membershipId" -> membershipId = readNonBlankString(reader)
                "role" -> role = readRequiredString(reader)
                "generation" -> generation = readGeneration(reader)
                "requiresReference" -> requiresReference = readBoolean(reader)
                "accessToken" -> accessToken = readNonBlankString(reader)
                "createdAt" -> createdAt = readNativeLong(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (role != "Host" || requiresReference != true) {
            invalidJson()
        }

        return HostActivation(
            monitorId = monitorId ?: invalidJson(),
            installationId = installationId ?: invalidJson(),
            membershipId = membershipId ?: invalidJson(),
            generation = generation ?: invalidJson(),
            accessToken = SecretValue.of(accessToken ?: invalidJson()),
            createdAtMillis = createdAt ?: invalidJson(),
            owner = owner,
            origin = origin,
        )
    }

    private fun readInstallationAccess(reader: JsonReader): InstallationAccessFields {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var installationId: String? = null
        var monitorId: String? = null
        var membershipId: String? = null
        var role: String? = null
        var status: String? = null
        var generation: OwnerGeneration? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "installationId" -> installationId = readNonBlankString(reader)
                "monitorId" -> monitorId = readNonBlankString(reader)
                "membershipId" -> membershipId = readNonBlankString(reader)
                "role" -> role = readRequiredString(reader)
                "status" -> status = readRequiredString(reader)
                "monitorGeneration" -> generation = readGeneration(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return InstallationAccessFields(
            installationId = installationId ?: invalidJson(),
            monitorId = monitorId ?: invalidJson(),
            membershipId = membershipId ?: invalidJson(),
            role = role ?: invalidJson(),
            status = status ?: invalidJson(),
            generation = generation ?: invalidJson(),
        )
    }

    private fun readGeneration(reader: JsonReader): OwnerGeneration =
        OwnerGeneration.parse(readRequiredString(reader)) ?: invalidJson()

    private fun readNativeLong(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) {
            invalidJson()
        }
        val number = reader.nextString()
        if (!INTEGER_NUMBER_PATTERN.matches(number)) {
            invalidJson()
        }
        return number.toLongOrNull() ?: invalidJson()
    }

    private fun readBoolean(reader: JsonReader): Boolean {
        if (reader.peek() != JsonToken.BOOLEAN) {
            invalidJson()
        }
        return reader.nextBoolean()
    }

    private fun readNullableString(reader: JsonReader): String? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            readRequiredString(reader)
        }

    private fun readNonBlankString(reader: JsonReader): String =
        readRequiredString(reader).takeIf(String::isNotBlank) ?: invalidJson()

    private fun readRequiredString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) {
            invalidJson()
        }
        return reader.nextString()
    }

    private fun uniqueName(reader: JsonReader, names: MutableSet<String>): String {
        val name = reader.nextName()
        if (!names.add(name)) {
            invalidJson()
        }
        return name
    }

    private fun <T> readDataEnvelope(
        reader: JsonReader,
        readData: (JsonReader) -> T,
    ): T {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var data: T? = null
        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "data" -> data = readData(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return data ?: invalidJson()
    }

    private fun <T> parseResponse(
        body: ByteArray,
        parse: (JsonReader) -> T,
    ): T? {
        if (body.size > MAX_OWNER_ACCESS_RESPONSE_BYTES) {
            return null
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }

        return try {
            JsonReader(StringReader(text)).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                reader.setNestingLimit(32)
                val result = parse(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    null
                } else {
                    result
                }
            }
        } catch (_: InvalidOwnerAccessJson) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun invalidJson(): Nothing = throw InvalidOwnerAccessJson()

    private sealed interface ExchangeResult {
        data class HttpResponse(
            val statusCode: Int,
            val body: ByteArray,
        ) : ExchangeResult

        data class Failure(
            val reason: OwnerAccessFailureReason,
            val activationDisposition: ActivationDisposition? = null,
        ) : ExchangeResult
    }

    private class OwnerHostFields(
        val installationId: String,
        val label: String?,
    )

    private class InstallationAccessFields(
        val installationId: String,
        val monitorId: String,
        val membershipId: String,
        val role: String,
        val status: String,
        val generation: OwnerGeneration,
    )

    private class InvalidOwnerAccessJson : RuntimeException()

    private companion object {
        val ACTIVATION_BODY_PATTERN =
            Regex("""\{"confirmation":true,"expectedGeneration":"(0|[1-9][0-9]*)"\}""")
        val INTEGER_NUMBER_PATTERN = Regex("-?[0-9]+")
        val KNOWN_NO_WRITE_STATUSES = setOf(401, 403, 409, 422, 429)
    }
}
