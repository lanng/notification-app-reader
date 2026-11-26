package br.com.c6tag.diagnostico.remote.access

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class RemoteSupportSessionCodec {
    fun encode(
        record: RemoteSupportSessionRecord,
        key: SecretKey,
    ): ByteArray {
        if (!isValidRecord(record)) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val origin = record.origin.toByteArray(StandardCharsets.US_ASCII)
        val plaintext = encodePayload(record)
        try {
            if (plaintext.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES) {
                throw RemoteSupportSessionRecordFormatException()
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            if (nonce == null || nonce.size != REMOTE_SUPPORT_GCM_NONCE_BYTES) {
                throw GeneralSecurityException("Unsupported AES-GCM nonce")
            }
            cipher.updateAAD(associatedData(recordKind(record), origin))
            val encrypted = cipher.doFinal(plaintext)
            try {
                if (
                    encrypted.size < GCM_TAG_BYTES ||
                    encrypted.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES
                ) {
                    throw RemoteSupportSessionRecordFormatException()
                }
                val envelope = ByteArrayOutputStream()
                DataOutputStream(envelope).use { output ->
                    output.write(MAGIC)
                    output.writeInt(REMOTE_SUPPORT_SESSION_FORMAT_VERSION)
                    output.writeByte(recordKind(record))
                    output.writeShort(origin.size)
                    output.write(origin)
                    output.writeByte(nonce.size)
                    output.writeInt(encrypted.size)
                    output.write(nonce)
                    output.write(encrypted)
                }
                return envelope.toByteArray().also { bytes ->
                    if (bytes.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES) {
                        throw RemoteSupportSessionRecordFormatException()
                    }
                }
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun readHeader(bytes: ByteArray): RemoteSupportSessionRecordHeader {
        if (bytes.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.available() < MINIMUM_ENVELOPE_BYTES) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val version = input.readInt()
        if (version != REMOTE_SUPPORT_SESSION_FORMAT_VERSION) {
            throw UnsupportedRemoteSupportSessionVersionException()
        }
        val kind = input.readUnsignedByte()
        if (kind !in SUPPORT_ATTEMPT_KIND..SUPPORT_APPROVED_KIND) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val originLength = input.readUnsignedShort()
        val minimumTailLength = 1L + Int.SIZE_BYTES + REMOTE_SUPPORT_GCM_NONCE_BYTES + GCM_TAG_BYTES
        if (
            originLength == 0 ||
            originLength > MAX_REMOTE_SUPPORT_ORIGIN_BYTES ||
            originLength.toLong() + minimumTailLength > input.available().toLong()
        ) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val originBytes = ByteArray(originLength)
        input.readFully(originBytes)
        val origin = decodeAscii(originBytes)
        if (!isCanonicalOrigin(origin)) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val nonceLength = input.readUnsignedByte()
        if (nonceLength != REMOTE_SUPPORT_GCM_NONCE_BYTES) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val encryptedLength = input.readInt()
        val remainingBytes = input.available()
        if (
            encryptedLength < GCM_TAG_BYTES ||
            encryptedLength > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES ||
            encryptedLength.toLong() + nonceLength.toLong() != remainingBytes.toLong()
        ) {
            throw RemoteSupportSessionRecordFormatException()
        }
        return RemoteSupportSessionRecordHeader(version, kind, origin, originBytes)
    }

    fun decode(
        bytes: ByteArray,
        key: SecretKey,
    ): RemoteSupportSessionRecord {
        val header = readHeader(bytes)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipFully(
            MAGIC.size + Int.SIZE_BYTES + 1 + Short.SIZE_BYTES + header.originBytes.size,
        )
        val nonceLength = input.readUnsignedByte()
        val encryptedLength = input.readInt()
        val nonce = ByteArray(nonceLength)
        input.readFully(nonce)
        val encrypted = ByteArray(encryptedLength)
        input.readFully(encrypted)

        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(associatedData(header.kind, header.originBytes))
            cipher.doFinal(encrypted)
        } finally {
            encrypted.fill(0)
            nonce.fill(0)
        }
        try {
            return decodePayload(plaintext, header)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encodePayload(record: RemoteSupportSessionRecord): ByteArray {
        val output = ZeroingByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                data.writeByte(recordKind(record))
                data.writeBoundedString(record.origin, MAX_REMOTE_SUPPORT_ORIGIN_BYTES)
                data.writeBoundedString(record.attemptId.value, MAX_ATTEMPT_ID_BYTES)
                when (record) {
                    is RemoteSupportSessionRecord.Attempt -> Unit

                    is RemoteSupportSessionRecord.Pending -> {
                        data.writePendingFields(record)
                    }

                    is RemoteSupportSessionRecord.Approved -> {
                        data.writePendingFields(record)
                        data.writeBoundedString(record.monitorId, UUID_BYTES)
                        data.writeBoundedString(record.membershipId, UUID_BYTES)
                        data.writeBoundedString(
                            record.observedGeneration.decimal,
                            MAX_REMOTE_SUPPORT_GENERATION_BYTES,
                        )
                    }
                }
            }
            val plaintext = output.toByteArray()
            if (plaintext.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES) {
                plaintext.fill(0)
                throw RemoteSupportSessionRecordFormatException()
            }
            return plaintext
        } finally {
            output.clear()
        }
    }

    private fun DataOutputStream.writePendingFields(
        record: RemoteSupportSessionRecord.Pending,
    ) {
        writeBoundedString(record.requestId, UUID_BYTES)
        writeBoundedString(record.installationId, UUID_BYTES)
        writeLong(record.requestExpiresAtMillis)
        writeBoundedString(record.accessToken.reveal(), MAX_REMOTE_SUPPORT_TOKEN_BYTES)
    }

    private fun DataOutputStream.writePendingFields(
        record: RemoteSupportSessionRecord.Approved,
    ) {
        writeBoundedString(record.requestId, UUID_BYTES)
        writeBoundedString(record.installationId, UUID_BYTES)
        writeLong(record.requestExpiresAtMillis)
        writeBoundedString(record.accessToken.reveal(), MAX_REMOTE_SUPPORT_TOKEN_BYTES)
    }

    private fun decodePayload(
        plaintext: ByteArray,
        header: RemoteSupportSessionRecordHeader,
    ): RemoteSupportSessionRecord {
        val input = DataInputStream(ByteArrayInputStream(plaintext))
        val payloadKind = input.readUnsignedByte()
        if (payloadKind != header.kind) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val origin = input.readBoundedString(MAX_REMOTE_SUPPORT_ORIGIN_BYTES)
        if (!isCanonicalOrigin(origin) || origin != header.origin) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val attemptId = RemoteSupportAttemptId.parse(
            input.readBoundedString(MAX_ATTEMPT_ID_BYTES),
        ) ?: throw RemoteSupportSessionRecordFormatException()
        val record = when (payloadKind) {
            SUPPORT_ATTEMPT_KIND ->
                RemoteSupportSessionRecord.Attempt(origin, attemptId)

            SUPPORT_PENDING_KIND -> {
                val pending = input.readPendingFields()
                RemoteSupportSessionRecord.Pending(
                    origin = origin,
                    attemptId = attemptId,
                    requestId = pending.requestId,
                    installationId = pending.installationId,
                    requestExpiresAtMillis = pending.requestExpiresAtMillis,
                    accessToken = pending.accessToken,
                )
            }

            SUPPORT_APPROVED_KIND -> {
                val pending = input.readPendingFields()
                val monitorId = input.readBoundedString(UUID_BYTES)
                val membershipId = input.readBoundedString(UUID_BYTES)
                val generation = OwnerGeneration.parse(
                    input.readBoundedString(MAX_REMOTE_SUPPORT_GENERATION_BYTES),
                ) ?: throw RemoteSupportSessionRecordFormatException()
                RemoteSupportSessionRecord.Approved(
                    origin = origin,
                    attemptId = attemptId,
                    requestId = pending.requestId,
                    installationId = pending.installationId,
                    requestExpiresAtMillis = pending.requestExpiresAtMillis,
                    accessToken = pending.accessToken,
                    monitorId = monitorId,
                    membershipId = membershipId,
                    observedGeneration = generation,
                )
            }

            else -> throw RemoteSupportSessionRecordFormatException()
        }
        if (input.available() != 0 || !isValidRecord(record)) {
            throw RemoteSupportSessionRecordFormatException()
        }
        return record
    }

    private fun DataInputStream.readPendingFields(): DecodedSupportPendingFields {
        val requestId = readBoundedString(UUID_BYTES)
        val installationId = readBoundedString(UUID_BYTES)
        if (available() < Long.SIZE_BYTES) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val requestExpiresAtMillis = readLong()
        val accessToken = SecretValue.of(
            readBoundedString(MAX_REMOTE_SUPPORT_TOKEN_BYTES)
                .takeIf(PairingAccessJson::isSafeBearer)
                ?: throw RemoteSupportSessionRecordFormatException(),
        )
        return DecodedSupportPendingFields(
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = requestExpiresAtMillis,
            accessToken = accessToken,
        )
    }

    private fun DataOutputStream.writeBoundedString(
        value: String,
        maximumBytes: Int,
    ) {
        if (value.isEmpty() || value.length > maximumBytes) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val encoded = encodeUtf8(value)
        if (encoded.isEmpty() || encoded.size > maximumBytes) {
            encoded.fill(0)
            throw RemoteSupportSessionRecordFormatException()
        }
        try {
            writeInt(encoded.size)
            write(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        if (available() < Int.SIZE_BYTES) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val byteCount = readInt()
        if (byteCount <= 0 || byteCount > maximumBytes || byteCount > available()) {
            throw RemoteSupportSessionRecordFormatException()
        }
        val encoded = ByteArray(byteCount)
        readFully(encoded)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        } catch (_: CharacterCodingException) {
            throw RemoteSupportSessionRecordFormatException()
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                throw RemoteSupportSessionRecordFormatException()
            }
            remaining -= skipped
        }
    }

    private fun associatedData(
        kind: Int,
        origin: ByteArray,
    ): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(REMOTE_SUPPORT_SESSION_FORMAT_VERSION)
                output.writeByte(kind)
                output.writeShort(origin.size)
                output.write(origin)
            }
        }.toByteArray()

    private fun decodeAscii(bytes: ByteArray): String {
        if (bytes.any { byte -> byte.toInt() !in 0..0x7f }) {
            throw RemoteSupportSessionRecordFormatException()
        }
        return String(bytes, StandardCharsets.US_ASCII)
    }

    private fun encodeUtf8(value: String): ByteArray =
        try {
            val encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: CharacterCodingException) {
            throw RemoteSupportSessionRecordFormatException()
        }

    private fun isValidRecord(record: RemoteSupportSessionRecord): Boolean {
        if (
            !isCanonicalOrigin(record.origin) ||
            RemoteSupportAttemptId.parse(record.attemptId.value) != record.attemptId
        ) {
            return false
        }
        return when (record) {
            is RemoteSupportSessionRecord.Attempt -> true
            is RemoteSupportSessionRecord.Pending ->
                isValidPendingFields(
                    record.requestId,
                    record.installationId,
                    record.requestExpiresAtMillis,
                    record.accessToken,
                )

            is RemoteSupportSessionRecord.Approved ->
                isValidPendingFields(
                    record.requestId,
                    record.installationId,
                    record.requestExpiresAtMillis,
                    record.accessToken,
                ) &&
                    PairingAccessJson.isCanonicalUuid(record.monitorId) &&
                    PairingAccessJson.isCanonicalUuid(record.membershipId) &&
                    OwnerGeneration.parse(record.observedGeneration.decimal) ==
                    record.observedGeneration
        }
    }

    private fun isValidPendingFields(
        requestId: String,
        installationId: String,
        requestExpiresAtMillis: Long,
        accessToken: SecretValue,
    ): Boolean {
        val token = accessToken.reveal()
        return PairingAccessJson.isCanonicalUuid(requestId) &&
            PairingAccessJson.isCanonicalUuid(installationId) &&
            requestExpiresAtMillis >= 0 &&
            token.length <= MAX_REMOTE_SUPPORT_TOKEN_BYTES &&
            token.toByteArray(StandardCharsets.US_ASCII).size <= MAX_REMOTE_SUPPORT_TOKEN_BYTES &&
            PairingAccessJson.isSafeBearer(token)
    }

    private fun isCanonicalOrigin(origin: String): Boolean =
        origin.length <= MAX_REMOTE_SUPPORT_ORIGIN_BYTES &&
            origin.toByteArray(StandardCharsets.US_ASCII).size <= MAX_REMOTE_SUPPORT_ORIGIN_BYTES &&
            canonicalOwnerApiOrigin(origin) == origin

    private fun recordKind(record: RemoteSupportSessionRecord): Int =
        when (record) {
            is RemoteSupportSessionRecord.Attempt -> SUPPORT_ATTEMPT_KIND
            is RemoteSupportSessionRecord.Pending -> SUPPORT_PENDING_KIND
            is RemoteSupportSessionRecord.Approved -> SUPPORT_APPROVED_KIND
        }

    private class ZeroingByteArrayOutputStream : ByteArrayOutputStream() {
        fun clear() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val SUPPORT_ATTEMPT_KIND = 1
        const val SUPPORT_PENDING_KIND = 2
        const val SUPPORT_APPROVED_KIND = 3
        const val UUID_BYTES = 36
        const val MAX_ATTEMPT_ID_BYTES = 32
        const val MAX_REMOTE_SUPPORT_GENERATION_BYTES = 19
        const val MINIMUM_ENVELOPE_BYTES =
            8 + Int.SIZE_BYTES + 1 + Short.SIZE_BYTES + 1 + Int.SIZE_BYTES +
                REMOTE_SUPPORT_GCM_NONCE_BYTES + GCM_TAG_BYTES
        val MAGIC = "TAGRSPS1".toByteArray(StandardCharsets.US_ASCII)
    }
}

internal class RemoteSupportSessionRecordHeader internal constructor(
    val version: Int,
    val kind: Int,
    val origin: String,
    internal val originBytes: ByteArray,
)

internal val SUPPORT_MAGIC: ByteArray
    get() = "TAGRSPS1".toByteArray(StandardCharsets.US_ASCII)

private data class DecodedSupportPendingFields(
    val requestId: String,
    val installationId: String,
    val requestExpiresAtMillis: Long,
    val accessToken: SecretValue,
)
