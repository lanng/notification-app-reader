package br.com.c6tag.diagnostico.remote.access

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object PairingAccessJson {
    private val uuidPattern =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val integerPattern = Regex("0|[1-9][0-9]*")
    private val invitationSecretPattern = Regex("[A-Za-z0-9_-]{43}")
    private val maxBearerLength = 8 * 1024

    fun encodeEmptyObject(): PairingAccessRequestBody? =
        encodeJson { writer ->
            writer.beginObject()
            writer.endObject()
        }

    fun encodeRedemption(
        invitationSecret: String,
        installationLabel: String,
    ): PairingAccessRequestBody? {
        if (encodeUtf8(invitationSecret) == null || encodeUtf8(installationLabel) == null) {
            return null
        }

        return encodeJson { writer ->
            writer.beginObject()
            writer.name("invitationSecret").value(invitationSecret)
            if (installationLabel.isNotEmpty()) {
                writer.name("installationLabel").value(installationLabel)
            }
            writer.endObject()
        }
    }

    fun parseInvitation(body: ByteArray): PairingInvitation? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader, ::readInvitation)
        }

    fun parseInvitationList(body: ByteArray): List<PairingInvitationSummary>? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader) { dataReader ->
                readNativeArray(dataReader, 50, ::readInvitationSummary)
            }
        }

    fun parseRedemption(body: ByteArray, origin: String): PairingCandidateInstallation? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader) { dataReader ->
                readRedemption(dataReader, origin)
            }
        }

    fun parseCurrentRequest(body: ByteArray): PairingCurrentRequest? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader, ::readCurrentRequest)
        }

    fun parseRequestList(body: ByteArray): List<PairingRequestSummary>? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader) { dataReader ->
                readNativeArray(dataReader, 50, ::readRequestSummary)
            }
        }

    fun parseApproval(body: ByteArray): PairingRequestApproval? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader, ::readApproval)
        }

    fun parseRejection(body: ByteArray): PairingRequestRejection? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader, ::readRejection)
        }

    fun parseSupportList(body: ByteArray): List<PairingSupportMembership>? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader) { dataReader ->
                readNativeArray(dataReader, 3, ::readSupportMembership)
            }
        }

    fun parseSupportInstallation(body: ByteArray): PairingSupportInstallation? =
        parseResponse(body) { reader ->
            readDataEnvelope(reader, ::readSupportInstallation)
        }

    fun parseErrorCode(body: ByteArray): String? =
        parseResponse(body, ::readErrorEnvelope)

    fun isCanonicalUuid(value: String): Boolean = uuidPattern.matches(value)

    fun isValidInvitationSecret(value: String): Boolean {
        if (!invitationSecretPattern.matches(value)) {
            return false
        }

        return try {
            val decoded = Base64.getUrlDecoder().decode(value)
            decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun isSafeBearer(value: String): Boolean =
        value.length in 1..maxBearerLength &&
            value.all { character -> character.code in 0x21..0x7E }

    fun isValidUtf8(value: String): Boolean = encodeUtf8(value) != null

    private fun encodeJson(write: (JsonWriter) -> Unit): PairingAccessRequestBody? {
        val output = StringWriter()
        try {
            JsonWriter(output).use { writer ->
                writer.setStrictness(Strictness.STRICT)
                writer.setSerializeNulls(false)
                write(writer)
            }
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }

        val bytes = encodeUtf8(output.toString()) ?: return null
        if (bytes.size > MAX_PAIRING_ACCESS_REQUEST_BYTES) {
            return null
        }

        return PairingAccessRequestBody(bytes)
    }

    private fun encodeUtf8(value: String): ByteArray? =
        try {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) {
            null
        }

    private fun readInvitation(reader: JsonReader): PairingInvitation {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        var secret: String? = null
        var createdAt: Long? = null
        var expiresAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readUuid(reader)
                "invitationSecret" -> secret = readRequiredString(reader).takeIf(::isValidInvitationSecret)
                    ?: invalidJson()

                "createdAt" -> createdAt = readNonnegativeLong(reader)
                "expiresAt" -> expiresAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        return PairingInvitation(
            id = id ?: invalidJson(),
            invitationSecret = SecretValue.of(secret ?: invalidJson()),
            createdAtMillis = createdAt ?: invalidJson(),
            expiresAtMillis = expiresAt ?: invalidJson(),
        )
    }

    private fun readInvitationSummary(reader: JsonReader): PairingInvitationSummary {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        var status: String? = null
        var createdAt: Long? = null
        var expiresAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "createdAt" -> createdAt = readNonnegativeLong(reader)
                "expiresAt" -> expiresAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (status != "Available") {
            invalidJson()
        }
        return PairingInvitationSummary(
            id = id ?: invalidJson(),
            status = status,
            createdAtMillis = createdAt ?: invalidJson(),
            expiresAtMillis = expiresAt ?: invalidJson(),
        )
    }

    private fun readRedemption(
        reader: JsonReader,
        origin: String,
    ): PairingCandidateInstallation {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var requestId: String? = null
        var installationId: String? = null
        var status: String? = null
        var requestExpiresAt: Long? = null
        var accessToken: String? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "requestId" -> requestId = readUuid(reader)
                "installationId" -> installationId = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "requestExpiresAt" -> requestExpiresAt = readNonnegativeLong(reader)
                "accessToken" -> accessToken = readRequiredString(reader).takeIf(::isSafeBearer)
                    ?: invalidJson()

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (status != "Pending") {
            invalidJson()
        }
        return PairingCandidateInstallation(
            origin = origin,
            requestId = requestId ?: invalidJson(),
            installationId = installationId ?: invalidJson(),
            requestExpiresAtMillis = requestExpiresAt ?: invalidJson(),
            accessToken = SecretValue.of(accessToken ?: invalidJson()),
            status = status,
        )
    }

    private fun readCurrentRequest(reader: JsonReader): PairingCurrentRequest {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var requestId: String? = null
        var status: String? = null
        var requestExpiresAt: Long? = null
        var decidedAtSeen = false
        var decidedAt: Long? = null
        var monitorSeen = false
        var monitor: PairingApprovedMonitor? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> requestId = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "requestExpiresAt" -> requestExpiresAt = readNonnegativeLong(reader)
                "decidedAt" -> {
                    decidedAtSeen = true
                    decidedAt = readNullableLong(reader)
                }

                "monitor" -> {
                    monitorSeen = true
                    monitor = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        readApprovedMonitor(reader)
                    }
                }

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (!decidedAtSeen || !monitorSeen) {
            invalidJson()
        }
        when (status) {
            "Pending" -> if (decidedAt != null || monitor != null) {
                invalidJson()
            }

            "Approved" -> if (decidedAt == null || monitor == null) {
                invalidJson()
            }

            else -> invalidJson()
        }

        return PairingCurrentRequest(
            requestId = requestId ?: invalidJson(),
            status = status ?: invalidJson(),
            requestExpiresAtMillis = requestExpiresAt ?: invalidJson(),
            decidedAtMillis = decidedAt,
            monitor = monitor,
        )
    }

    private fun readApprovedMonitor(reader: JsonReader): PairingApprovedMonitor {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        var membershipId: String? = null
        var role: String? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readUuid(reader)
                "membershipId" -> membershipId = readUuid(reader)
                "role" -> role = readRequiredString(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (role != "Support") {
            invalidJson()
        }
        return PairingApprovedMonitor(
            id = id ?: invalidJson(),
            membershipId = membershipId ?: invalidJson(),
            role = role,
        )
    }

    private fun readRequestSummary(reader: JsonReader): PairingRequestSummary {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        var status: String? = null
        var installationId: String? = null
        var installationLabelSeen = false
        var installationLabel: String? = null
        var createdAt: Long? = null
        var requestExpiresAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "installation" -> {
                    val installation = readRequestInstallation(reader)
                    installationId = installation.first
                    installationLabelSeen = true
                    installationLabel = installation.second
                }

                "createdAt" -> createdAt = readNonnegativeLong(reader)
                "requestExpiresAt" -> requestExpiresAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (status != "Pending" || !installationLabelSeen) {
            invalidJson()
        }
        return PairingRequestSummary(
            id = id ?: invalidJson(),
            status = status,
            installationId = installationId ?: invalidJson(),
            installationLabel = installationLabel,
            createdAtMillis = createdAt ?: invalidJson(),
            requestExpiresAtMillis = requestExpiresAt ?: invalidJson(),
        )
    }

    private fun readRequestInstallation(reader: JsonReader): Pair<String, String?> {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var id: String? = null
        var labelSeen = false
        var label: String? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "id" -> id = readUuid(reader)
                "label" -> {
                    labelSeen = true
                    label = readNullableString(reader)
                }

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (!labelSeen) {
            invalidJson()
        }
        return (id ?: invalidJson()) to label
    }

    private fun readApproval(reader: JsonReader): PairingRequestApproval {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var requestId: String? = null
        var status: String? = null
        var membershipId: String? = null
        var role: String? = null
        var decidedAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "requestId" -> requestId = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "membershipId" -> membershipId = readUuid(reader)
                "role" -> role = readRequiredString(reader)
                "decidedAt" -> decidedAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (status != "Approved" || role != "Support") {
            invalidJson()
        }
        return PairingRequestApproval(
            requestId = requestId ?: invalidJson(),
            status = status,
            membershipId = membershipId ?: invalidJson(),
            role = role,
            decidedAtMillis = decidedAt ?: invalidJson(),
        )
    }

    private fun readRejection(reader: JsonReader): PairingRequestRejection {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var requestId: String? = null
        var status: String? = null
        var decidedAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "requestId" -> requestId = readUuid(reader)
                "status" -> status = readRequiredString(reader)
                "decidedAt" -> decidedAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (status != "Rejected") {
            invalidJson()
        }
        return PairingRequestRejection(
            requestId = requestId ?: invalidJson(),
            status = status,
            decidedAtMillis = decidedAt ?: invalidJson(),
        )
    }

    private fun readSupportMembership(reader: JsonReader): PairingSupportMembership {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var membershipId: String? = null
        var installationId: String? = null
        var labelSeen = false
        var label: String? = null
        var role: String? = null
        var status: String? = null
        var approvedAt: Long? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "membershipId" -> membershipId = readUuid(reader)
                "installationId" -> installationId = readUuid(reader)
                "label" -> {
                    labelSeen = true
                    label = readNullableString(reader)
                }

                "role" -> role = readRequiredString(reader)
                "status" -> status = readRequiredString(reader)
                "approvedAt" -> approvedAt = readNonnegativeLong(reader)
                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (!labelSeen || role != "Support" || status != "Active") {
            invalidJson()
        }
        return PairingSupportMembership(
            membershipId = membershipId ?: invalidJson(),
            installationId = installationId ?: invalidJson(),
            label = label,
            role = role,
            status = status,
            approvedAtMillis = approvedAt ?: invalidJson(),
        )
    }

    private fun readSupportInstallation(reader: JsonReader): PairingSupportInstallation {
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
                "installationId" -> installationId = readUuid(reader)
                "monitorId" -> monitorId = readUuid(reader)
                "membershipId" -> membershipId = readUuid(reader)
                "role" -> role = readRequiredString(reader)
                "status" -> status = readRequiredString(reader)
                "monitorGeneration" -> {
                    generation = OwnerGeneration.parse(readRequiredString(reader)) ?: invalidJson()
                }

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (role != "Support" || status != "Active") {
            invalidJson()
        }
        return PairingSupportInstallation(
            installationId = installationId ?: invalidJson(),
            monitorId = monitorId ?: invalidJson(),
            membershipId = membershipId ?: invalidJson(),
            role = role,
            status = status,
            monitorGeneration = generation ?: invalidJson(),
        )
    }

    private fun readErrorEnvelope(reader: JsonReader): String {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var code: String? = null
        var message: String? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "code" -> code = readNonBlankString(reader)
                "message" -> message = readNonBlankString(reader)
                "errors" -> readValidationErrors(reader)

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        if (message == null) {
            invalidJson()
        }
        return code ?: invalidJson()
    }

    private fun readValidationErrors(reader: JsonReader) {
        reader.beginObject()
        val names = mutableSetOf<String>()
        while (reader.hasNext()) {
            if (uniqueName(reader, names).isBlank()) {
                invalidJson()
            }
            reader.beginArray()
            while (reader.hasNext()) {
                readRequiredString(reader)
            }
            reader.endArray()
        }
        reader.endObject()
    }

    private fun <T> readDataEnvelope(
        reader: JsonReader,
        readData: (JsonReader) -> T,
    ): T {
        reader.beginObject()
        val names = mutableSetOf<String>()
        var dataSeen = false
        var data: T? = null

        while (reader.hasNext()) {
            when (uniqueName(reader, names)) {
                "data" -> {
                    dataSeen = true
                    data = readData(reader)
                }

                else -> skipUniqueValue(reader)
            }
        }
        reader.endObject()

        return if (dataSeen) data ?: invalidJson() else invalidJson()
    }

    private fun <T> readNativeArray(
        reader: JsonReader,
        maximumSize: Int,
        readItem: (JsonReader) -> T,
    ): List<T> {
        reader.beginArray()
        val items = ArrayList<T>(minOf(maximumSize, 16))
        while (reader.hasNext()) {
            if (items.size == maximumSize) {
                invalidJson()
            }
            items += readItem(reader)
        }
        reader.endArray()
        return items
    }

    private fun readUuid(reader: JsonReader): String =
        readRequiredString(reader).takeIf(::isCanonicalUuid) ?: invalidJson()

    private fun readNonnegativeLong(reader: JsonReader): Long {
        if (reader.peek() != JsonToken.NUMBER) {
            invalidJson()
        }
        val value = reader.nextString()
        if (!integerPattern.matches(value)) {
            invalidJson()
        }
        return value.toLongOrNull() ?: invalidJson()
    }

    private fun readNullableLong(reader: JsonReader): Long? =
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else {
            readNonnegativeLong(reader)
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

    private fun skipUniqueValue(reader: JsonReader) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    skipUniqueValue(reader)
                }
                reader.endArray()
            }

            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    uniqueName(reader, names)
                    skipUniqueValue(reader)
                }
                reader.endObject()
            }

            JsonToken.STRING,
            JsonToken.NUMBER,
            JsonToken.BOOLEAN,
            JsonToken.NULL -> reader.skipValue()
            else -> invalidJson()
        }
    }

    private fun <T> parseResponse(
        body: ByteArray,
        parse: (JsonReader) -> T,
    ): T? {
        if (body.size > MAX_PAIRING_ACCESS_RESPONSE_BYTES) {
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
                val value = parse(reader)
                if (reader.peek() == JsonToken.END_DOCUMENT) value else null
            }
        } catch (_: InvalidPairingAccessJson) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun invalidJson(): Nothing = throw InvalidPairingAccessJson()

    private class InvalidPairingAccessJson : RuntimeException()
}
