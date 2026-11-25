package br.com.c6tag.diagnostico.remote.access

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface RemoteHostSessionStore {
    suspend fun load(expectedContext: RemoteHostOwnerContext): RemoteHostSessionLoad

    suspend fun presence(): RemoteSessionPresence

    suspend fun beginAttempt(
        expectedContext: RemoteHostOwnerContext,
        expectedGeneration: OwnerGeneration,
    ): RemoteHostSessionWriteResult

    /**
     * Replace a pending journal after the caller completes fresh reauthentication, owner
     * rediscovery, and renewed confirmation. [expectedGeneration] must come from that
     * rediscovery. This store does not validate those steps or establish whether the
     * previous remote operation wrote.
     */
    suspend fun beginRecoveryAttempt(
        expectedContext: RemoteHostOwnerContext,
        previousAttemptId: RemoteHostAttemptId,
        expectedGeneration: OwnerGeneration,
    ): RemoteHostSessionWriteResult

    suspend fun commitAttempt(
        expectedContext: RemoteHostOwnerContext,
        attemptId: RemoteHostAttemptId,
        activation: HostActivation,
    ): RemoteHostSessionWriteResult

    /** Clear a pending attempt only after its remote operation is known not to have written. */
    suspend fun clearPendingAttempt(
        expectedContext: RemoteHostOwnerContext,
        attemptId: RemoteHostAttemptId,
    ): RemoteHostSessionWriteResult

    /** Clear an active credential only when its complete saved host context still matches. */
    suspend fun clearActiveSession(
        expectedContext: ExpectedHostAccessContext,
    ): RemoteHostSessionWriteResult
}

internal interface RemoteHostAtomicFile {
    @Throws(IOException::class)
    fun readRecord(maximumBytes: Int): ByteArray?

    @Throws(IOException::class)
    fun writeAtomically(record: ByteArray)

    @Throws(IOException::class)
    fun deleteRecord()
}

internal interface RemoteHostKeyProvider {
    @Throws(GeneralSecurityException::class)
    fun existingKey(): SecretKey?

    @Throws(GeneralSecurityException::class)
    fun keyForNewRecord(): SecretKey
}

internal class RemoteHostKeyUnavailableException(cause: Throwable? = null) :
    GeneralSecurityException(cause)

internal sealed interface RemoteHostStoredRecord {
    val firebaseProjectId: String
    val uid: String
    val origin: String

    data class Pending(
        override val firebaseProjectId: String,
        override val uid: String,
        override val origin: String,
        val expectedGeneration: OwnerGeneration,
        val attemptId: RemoteHostAttemptId,
    ) : RemoteHostStoredRecord

    class Active(
        override val firebaseProjectId: String,
        override val uid: String,
        override val origin: String,
        val monitorId: String,
        val installationId: String,
        val membershipId: String,
        val generation: OwnerGeneration,
        val accessToken: SecretValue,
        val createdAtMillis: Long,
    ) : RemoteHostStoredRecord {
        override fun toString(): String =
            "Active(firebaseProjectId=$firebaseProjectId, uid=$uid, origin=$origin, " +
                "monitorId=$monitorId, installationId=$installationId, " +
                "membershipId=$membershipId, generation=$generation, " +
                "accessToken=[REDACTED], createdAtMillis=$createdAtMillis)"
    }
}

internal class RemoteHostSessionRecordFormatException : Exception()
internal class UnsupportedRemoteHostSessionVersionException : Exception()

/**
 * Authenticated binary envelope. The public origin is repeated inside the
 * encrypted payload and authenticated as associated data.
 */
internal class RemoteHostSessionRecordCodec {
    fun encode(record: RemoteHostStoredRecord, key: SecretKey): ByteArray {
        require(canonicalOwnerApiOrigin(record.origin) == record.origin)
        val origin = record.origin.toByteArray(StandardCharsets.US_ASCII)
        if (origin.isEmpty() || origin.size > MAX_REMOTE_HOST_ORIGIN_BYTES) {
            throw RemoteHostSessionRecordFormatException()
        }
        val plaintext = encodePayload(record)
        try {
            if (plaintext.size > MAX_REMOTE_HOST_RECORD_BYTES) {
                throw RemoteHostSessionRecordFormatException()
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            if (nonce == null || nonce.size != REMOTE_HOST_GCM_NONCE_BYTES) {
                throw GeneralSecurityException("Unsupported AES-GCM nonce")
            }
            cipher.updateAAD(associatedData(origin))
            val encrypted = cipher.doFinal(plaintext)
            if (
                encrypted.size < REMOTE_HOST_GCM_TAG_BYTES ||
                encrypted.size > MAX_REMOTE_HOST_RECORD_BYTES
            ) {
                throw RemoteHostSessionRecordFormatException()
            }

            val envelope = ByteArrayOutputStream()
            DataOutputStream(envelope).use { output ->
                output.write(MAGIC)
                output.writeInt(REMOTE_HOST_SESSION_FORMAT_VERSION)
                output.writeShort(origin.size)
                output.write(origin)
                output.writeByte(nonce.size)
                output.writeInt(encrypted.size)
                output.write(nonce)
                output.write(encrypted)
            }
            return envelope.toByteArray().also { bytes ->
                if (bytes.size > MAX_REMOTE_HOST_RECORD_BYTES) {
                    throw RemoteHostSessionRecordFormatException()
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun readHeader(bytes: ByteArray): RemoteHostSessionRecordHeader {
        if (bytes.size > MAX_REMOTE_HOST_RECORD_BYTES) {
            throw RemoteHostSessionRecordFormatException()
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.available() < MINIMUM_ENVELOPE_BYTES) {
            throw RemoteHostSessionRecordFormatException()
        }
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw RemoteHostSessionRecordFormatException()
        }
        val version = input.readInt()
        if (version != REMOTE_HOST_SESSION_FORMAT_VERSION) {
            throw UnsupportedRemoteHostSessionVersionException()
        }
        val originLength = input.readUnsignedShort()
        if (
            originLength == 0 ||
            originLength > MAX_REMOTE_HOST_ORIGIN_BYTES ||
            originLength > input.available() - ENVELOPE_FIXED_FIELDS_BYTES
        ) {
            throw RemoteHostSessionRecordFormatException()
        }
        val originBytes = ByteArray(originLength)
        input.readFully(originBytes)
        val origin = decodeAscii(originBytes)
        if (canonicalOwnerApiOrigin(origin) != origin) {
            throw RemoteHostSessionRecordFormatException()
        }
        return RemoteHostSessionRecordHeader(version, origin, originBytes)
    }

    fun decode(bytes: ByteArray, key: SecretKey): RemoteHostStoredRecord {
        val header = readHeader(bytes)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipFully(MAGIC.size + Int.SIZE_BYTES + Short.SIZE_BYTES + header.originBytes.size)
        val nonceLength = input.readUnsignedByte()
        if (nonceLength != REMOTE_HOST_GCM_NONCE_BYTES) {
            throw RemoteHostSessionRecordFormatException()
        }
        val encryptedLength = input.readInt()
        val remaining = input.available()
        if (
            encryptedLength < REMOTE_HOST_GCM_TAG_BYTES ||
            encryptedLength > MAX_REMOTE_HOST_RECORD_BYTES ||
            encryptedLength.toLong() + nonceLength.toLong() != remaining.toLong()
        ) {
            throw RemoteHostSessionRecordFormatException()
        }
        val nonce = ByteArray(nonceLength)
        input.readFully(nonce)
        val encrypted = ByteArray(encryptedLength)
        input.readFully(encrypted)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(REMOTE_HOST_GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData(header.originBytes))
        val plaintext = try {
            cipher.doFinal(encrypted)
        } catch (exception: AEADBadTagException) {
            throw exception
        }
        try {
            return decodePayload(plaintext, header)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encodePayload(record: RemoteHostStoredRecord): ByteArray {
        val output = ZeroingByteArrayOutputStream()
        try {
            DataOutputStream(output).use { data ->
                when (record) {
                    is RemoteHostStoredRecord.Pending -> {
                        data.writeByte(PENDING_RECORD_KIND)
                        data.writeBoundedString(record.firebaseProjectId, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.uid, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.origin, MAX_REMOTE_HOST_ORIGIN_BYTES)
                        data.writeBoundedString(record.expectedGeneration.decimal, MAX_GENERATION_BYTES)
                        data.writeBoundedString(record.attemptId.value, MAX_ATTEMPT_ID_BYTES)
                    }

                    is RemoteHostStoredRecord.Active -> {
                        data.writeByte(ACTIVE_RECORD_KIND)
                        data.writeBoundedString(record.firebaseProjectId, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.uid, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.origin, MAX_REMOTE_HOST_ORIGIN_BYTES)
                        data.writeBoundedString(record.monitorId, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.installationId, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.membershipId, MAX_REMOTE_HOST_METADATA_BYTES)
                        data.writeBoundedString(record.generation.decimal, MAX_GENERATION_BYTES)
                        data.writeLong(record.createdAtMillis)
                        data.writeBoundedString(
                            record.accessToken.reveal(),
                            MAX_REMOTE_HOST_ACCESS_TOKEN_BYTES,
                        )
                    }
                }
            }
            val plaintext = output.toByteArray()
            if (plaintext.size > MAX_REMOTE_HOST_RECORD_BYTES) {
                plaintext.fill(0)
                throw RemoteHostSessionRecordFormatException()
            }
            return plaintext
        } finally {
            output.clear()
        }
    }

    private fun decodePayload(
        plaintext: ByteArray,
        header: RemoteHostSessionRecordHeader,
    ): RemoteHostStoredRecord {
        val input = DataInputStream(ByteArrayInputStream(plaintext))
        val record = when (input.readUnsignedByte()) {
            PENDING_RECORD_KIND -> {
                val (projectId, uid, origin) = input.readCommonContext()
                val generation = OwnerGeneration.parse(
                    input.readBoundedString(MAX_GENERATION_BYTES),
                ) ?: throw RemoteHostSessionRecordFormatException()
                val attemptId = RemoteHostAttemptId.parse(
                    input.readBoundedString(MAX_ATTEMPT_ID_BYTES),
                ) ?: throw RemoteHostSessionRecordFormatException()
                RemoteHostStoredRecord.Pending(
                    firebaseProjectId = projectId,
                    uid = uid,
                    origin = origin,
                    expectedGeneration = generation,
                    attemptId = attemptId,
                )
            }

            ACTIVE_RECORD_KIND -> {
                val (projectId, uid, origin) = input.readCommonContext()
                val monitorId = input.readNonBlankBoundedString(MAX_REMOTE_HOST_METADATA_BYTES)
                val installationId = input.readNonBlankBoundedString(MAX_REMOTE_HOST_METADATA_BYTES)
                val membershipId = input.readNonBlankBoundedString(MAX_REMOTE_HOST_METADATA_BYTES)
                val generation = OwnerGeneration.parse(
                    input.readBoundedString(MAX_GENERATION_BYTES),
                ) ?: throw RemoteHostSessionRecordFormatException()
                val createdAtMillis = input.readLong()
                val accessToken = SecretValue.of(
                    input.readNonBlankBoundedString(MAX_REMOTE_HOST_ACCESS_TOKEN_BYTES),
                )
                RemoteHostStoredRecord.Active(
                    firebaseProjectId = projectId,
                    uid = uid,
                    origin = origin,
                    monitorId = monitorId,
                    installationId = installationId,
                    membershipId = membershipId,
                    generation = generation,
                    accessToken = accessToken,
                    createdAtMillis = createdAtMillis,
                )
            }

            else -> throw RemoteHostSessionRecordFormatException()
        }
        if (input.available() != 0 || record.origin != header.origin) {
            throw RemoteHostSessionRecordFormatException()
        }
        return record
    }

    private fun DataInputStream.readCommonContext(): Triple<String, String, String> {
        val projectId = readNonBlankBoundedString(MAX_REMOTE_HOST_METADATA_BYTES)
        val uid = readNonBlankBoundedString(MAX_REMOTE_HOST_METADATA_BYTES)
        val origin = readNonBlankBoundedString(MAX_REMOTE_HOST_ORIGIN_BYTES)
        if (canonicalOwnerApiOrigin(origin) != origin) {
            throw RemoteHostSessionRecordFormatException()
        }
        return Triple(projectId, uid, origin)
    }

    private fun DataOutputStream.writeBoundedString(value: String, maximumBytes: Int) {
        if (value.isEmpty() || value.length > maximumBytes) {
            throw RemoteHostSessionRecordFormatException()
        }
        val encoded = encodeUtf8(value)
        if (encoded.isEmpty() || encoded.size > maximumBytes) {
            encoded.fill(0)
            throw RemoteHostSessionRecordFormatException()
        }
        try {
            writeInt(encoded.size)
            write(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun DataInputStream.readNonBlankBoundedString(maximumBytes: Int): String =
        readBoundedString(maximumBytes).takeIf(String::isNotBlank)
            ?: throw RemoteHostSessionRecordFormatException()

    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        if (available() < Int.SIZE_BYTES) {
            throw RemoteHostSessionRecordFormatException()
        }
        val byteCount = readInt()
        if (byteCount <= 0 || byteCount > maximumBytes || byteCount > available()) {
            throw RemoteHostSessionRecordFormatException()
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
            throw RemoteHostSessionRecordFormatException()
        } finally {
            encoded.fill(0)
        }
    }

    private fun associatedData(origin: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(REMOTE_HOST_SESSION_FORMAT_VERSION)
                output.writeShort(origin.size)
                output.write(origin)
            }
        }.toByteArray()

    private fun decodeAscii(bytes: ByteArray): String {
        if (bytes.any { byte -> byte.toInt() !in 0..0x7F }) {
            throw RemoteHostSessionRecordFormatException()
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
            throw RemoteHostSessionRecordFormatException()
        }

    private fun DataInputStream.skipFully(byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped <= 0) {
                throw RemoteHostSessionRecordFormatException()
            }
            remaining -= skipped
        }
    }

    private class ZeroingByteArrayOutputStream : ByteArrayOutputStream() {
        fun clear() {
            buf.fill(0)
            reset()
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val REMOTE_HOST_GCM_TAG_BITS = 128
        const val REMOTE_HOST_GCM_TAG_BYTES = REMOTE_HOST_GCM_TAG_BITS / 8
        const val PENDING_RECORD_KIND = 1
        const val ACTIVE_RECORD_KIND = 2
        const val ENVELOPE_FIXED_FIELDS_BYTES = 1 + Int.SIZE_BYTES
        const val MINIMUM_ENVELOPE_BYTES =
            8 + Int.SIZE_BYTES + Short.SIZE_BYTES + 1 + Int.SIZE_BYTES +
                REMOTE_HOST_GCM_NONCE_BYTES + REMOTE_HOST_GCM_TAG_BYTES
        val MAGIC = byteArrayOf(0x54, 0x41, 0x47, 0x52, 0x48, 0x53, 0x54, 0x31)
    }
}

internal data class RemoteHostSessionRecordHeader(
    val version: Int,
    val origin: String,
    internal val originBytes: ByteArray,
)

internal const val MAX_REMOTE_HOST_RECORD_BYTES = 64 * 1024
internal const val MAX_REMOTE_HOST_ORIGIN_BYTES = 2 * 1024
internal const val MAX_REMOTE_HOST_METADATA_BYTES = 2 * 1024
internal const val MAX_REMOTE_HOST_ACCESS_TOKEN_BYTES = 8 * 1024
private const val MAX_GENERATION_BYTES = 19
private const val MAX_ATTEMPT_ID_BYTES = 32
internal const val REMOTE_HOST_GCM_NONCE_BYTES = 12
internal const val REMOTE_HOST_SESSION_FORMAT_VERSION = 1

internal class PersistentRemoteHostSessionStore(
    private val file: RemoteHostAtomicFile,
    private val keys: RemoteHostKeyProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val secureRandom: SecureRandom = SecureRandom(),
) : RemoteHostSessionStore {
    private val codec = RemoteHostSessionRecordCodec()

    override suspend fun load(expectedContext: RemoteHostOwnerContext): RemoteHostSessionLoad {
        if (!isValidContext(expectedContext)) {
            return RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        return withSerializedIo {
            try {
                when (val current = readCurrent(expectedContext)) {
                    StoredRead.Empty -> RemoteHostSessionLoad.Empty
                    is StoredRead.Invalid -> RemoteHostSessionLoad.Unavailable(current.problem)
                    is StoredRead.Present -> when (val record = current.record) {
                        is RemoteHostStoredRecord.Pending -> RemoteHostSessionLoad.PendingAttempt(
                            attemptId = record.attemptId,
                            expectedGeneration = record.expectedGeneration,
                        )

                        is RemoteHostStoredRecord.Active -> RemoteHostSessionLoad.ActiveSession(
                            record.toHostActivation(expectedContext.owner),
                        )
                    }
                }
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: AEADBadTagException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.AUTHENTICATION_FAILED)
            } catch (_: EOFException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun presence(): RemoteSessionPresence =
        withSerializedIo {
            val record = try {
                file.readRecord(1)
            } catch (_: IOException) {
                return@withSerializedIo RemoteSessionPresence.Unavailable(
                    RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                )
            }
            if (record == null) {
                RemoteSessionPresence.Absent
            } else {
                RemoteSessionPresence.Present
            }
        }

    override suspend fun beginAttempt(
        expectedContext: RemoteHostOwnerContext,
        expectedGeneration: OwnerGeneration,
    ): RemoteHostSessionWriteResult {
        if (!isValidContext(expectedContext)) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        return withSerializedIo {
            try {
                val current = readCurrent(expectedContext)
                when (current) {
                    is StoredRead.Invalid ->
                        return@withSerializedIo RemoteHostSessionWriteResult.Rejected(current.problem)

                    is StoredRead.Present -> {
                        if (current.record is RemoteHostStoredRecord.Pending) {
                            return@withSerializedIo RemoteHostSessionWriteResult.Rejected(
                                RemoteHostSessionProblem.ATTEMPT_ALREADY_PENDING,
                            )
                        }
                    }

                    StoredRead.Empty -> Unit
                }

                val attemptId = newAttemptId()
                val pending = RemoteHostStoredRecord.Pending(
                    firebaseProjectId = expectedContext.owner.firebaseProjectId,
                    uid = expectedContext.owner.uid,
                    origin = expectedContext.origin,
                    expectedGeneration = expectedGeneration,
                    attemptId = attemptId,
                )
                val key = when (current) {
                    is StoredRead.Present -> current.key
                    StoredRead.Empty -> keys.keyForNewRecord()
                    is StoredRead.Invalid -> error("Invalid records returned above")
                }
                persist(pending, key)
                RemoteHostSessionWriteResult.PendingAttemptStarted(attemptId)
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: EOFException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun beginRecoveryAttempt(
        expectedContext: RemoteHostOwnerContext,
        previousAttemptId: RemoteHostAttemptId,
        expectedGeneration: OwnerGeneration,
    ): RemoteHostSessionWriteResult {
        if (!isValidContext(expectedContext)) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        return withSerializedIo {
            try {
                val current = readCurrent(expectedContext)
                if (current is StoredRead.Invalid) {
                    return@withSerializedIo RemoteHostSessionWriteResult.Rejected(current.problem)
                }
                if (
                    current !is StoredRead.Present ||
                    current.record !is RemoteHostStoredRecord.Pending ||
                    current.record.attemptId != previousAttemptId
                ) {
                    return@withSerializedIo RemoteHostSessionWriteResult.Rejected(
                        RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
                    )
                }

                val replacementAttemptId = newAttemptId()
                val replacement = RemoteHostStoredRecord.Pending(
                    firebaseProjectId = expectedContext.owner.firebaseProjectId,
                    uid = expectedContext.owner.uid,
                    origin = expectedContext.origin,
                    expectedGeneration = expectedGeneration,
                    attemptId = replacementAttemptId,
                )
                persist(replacement, current.key)
                RemoteHostSessionWriteResult.PendingAttemptStarted(replacementAttemptId)
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: AEADBadTagException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.AUTHENTICATION_FAILED)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: EOFException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun commitAttempt(
        expectedContext: RemoteHostOwnerContext,
        attemptId: RemoteHostAttemptId,
        activation: HostActivation,
    ): RemoteHostSessionWriteResult {
        if (!isValidContext(expectedContext)) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        val activationContext = activation.expectedHostContext()
        if (
            !sameOwner(activationContext.owner, expectedContext.owner) ||
            activationContext.origin != expectedContext.origin
        ) {
            return RemoteHostSessionWriteResult.Rejected(
                if (activationContext.origin != expectedContext.origin) {
                    RemoteHostSessionProblem.ORIGIN_MISMATCH
                } else {
                    RemoteHostSessionProblem.OWNER_MISMATCH
                },
            )
        }
        if (
            !isBoundedMetadata(activation.monitorId) ||
            !isBoundedMetadata(activation.installationId) ||
            !isBoundedMetadata(activation.membershipId) ||
            !isBoundedSecret(activation.accessToken.reveal())
        ) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }

        return withSerializedIo {
            try {
                val current = readCurrent(expectedContext)
                if (current is StoredRead.Invalid) {
                    return@withSerializedIo RemoteHostSessionWriteResult.Rejected(current.problem)
                }
                if (
                    current !is StoredRead.Present ||
                    current.record !is RemoteHostStoredRecord.Pending ||
                    current.record.attemptId != attemptId
                ) {
                    return@withSerializedIo RemoteHostSessionWriteResult.Rejected(
                        RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
                    )
                }

                val active = RemoteHostStoredRecord.Active(
                    firebaseProjectId = expectedContext.owner.firebaseProjectId,
                    uid = expectedContext.owner.uid,
                    origin = expectedContext.origin,
                    monitorId = activation.monitorId,
                    installationId = activation.installationId,
                    membershipId = activation.membershipId,
                    generation = activation.generation,
                    accessToken = activation.accessToken,
                    createdAtMillis = activation.createdAtMillis,
                )
                persist(active, current.key)
                RemoteHostSessionWriteResult.ActiveSessionCommitted
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: EOFException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun clearPendingAttempt(
        expectedContext: RemoteHostOwnerContext,
        attemptId: RemoteHostAttemptId,
    ): RemoteHostSessionWriteResult {
        if (!isValidContext(expectedContext)) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        return withSerializedIo {
            try {
                when (val current = readCurrent(expectedContext)) {
                    StoredRead.Empty -> RemoteHostSessionWriteResult.Rejected(
                        RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
                    )

                    is StoredRead.Invalid -> RemoteHostSessionWriteResult.Rejected(current.problem)
                    is StoredRead.Present -> {
                        if (
                            current.record !is RemoteHostStoredRecord.Pending ||
                            current.record.attemptId != attemptId
                        ) {
                            return@withSerializedIo RemoteHostSessionWriteResult.Rejected(
                                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
                            )
                        }
                        file.deleteRecord()
                        if (file.readRecord(1) != null) {
                            RemoteHostSessionWriteResult.Rejected(
                                RemoteHostSessionProblem.PERSISTENCE_FAILURE,
                            )
                        } else {
                            RemoteHostSessionWriteResult.PendingAttemptCleared
                        }
                    }
                }
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: AEADBadTagException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.AUTHENTICATION_FAILED)
            } catch (_: EOFException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun clearActiveSession(
        expectedContext: ExpectedHostAccessContext,
    ): RemoteHostSessionWriteResult {
        val ownerContext = RemoteHostOwnerContext(expectedContext.owner, expectedContext.origin)
        if (
            !isValidContext(ownerContext) ||
            !isBoundedMetadata(expectedContext.monitorId) ||
            !isBoundedMetadata(expectedContext.installationId) ||
            !isBoundedMetadata(expectedContext.membershipId)
        ) {
            return RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.INVALID_CONTEXT)
        }
        return withSerializedIo {
            try {
                when (val current = readCurrent(ownerContext)) {
                    StoredRead.Empty -> RemoteHostSessionWriteResult.Rejected(
                        RemoteHostSessionProblem.NO_MATCHING_ACTIVE_SESSION,
                    )

                    is StoredRead.Invalid -> RemoteHostSessionWriteResult.Rejected(current.problem)
                    is StoredRead.Present -> {
                        val record = current.record as? RemoteHostStoredRecord.Active
                            ?: return@withSerializedIo RemoteHostSessionWriteResult.Rejected(
                                RemoteHostSessionProblem.NO_MATCHING_ACTIVE_SESSION,
                            )
                        if (
                            record.monitorId != expectedContext.monitorId ||
                            record.installationId != expectedContext.installationId ||
                            record.membershipId != expectedContext.membershipId ||
                            record.generation != expectedContext.generation
                        ) {
                            RemoteHostSessionWriteResult.Rejected(
                                RemoteHostSessionProblem.SESSION_CONTEXT_MISMATCH,
                            )
                        } else {
                            file.deleteRecord()
                            if (file.readRecord(1) != null) {
                                RemoteHostSessionWriteResult.Rejected(
                                    RemoteHostSessionProblem.PERSISTENCE_FAILURE,
                                )
                            } else {
                                RemoteHostSessionWriteResult.ActiveSessionCleared
                            }
                        }
                    }
                }
            } catch (_: RemoteHostKeyUnavailableException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            } catch (_: AEADBadTagException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.AUTHENTICATION_FAILED)
            } catch (_: EOFException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: UnsupportedRemoteHostSessionVersionException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION)
            } catch (_: RemoteHostSessionRecordFormatException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.CORRUPT_RECORD)
            } catch (_: IOException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE)
            } catch (_: GeneralSecurityException) {
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE)
            }
        }
    }

    private fun readCurrent(expectedContext: RemoteHostOwnerContext): StoredRead {
        val bytes = file.readRecord(MAX_REMOTE_HOST_RECORD_BYTES + 1) ?: return StoredRead.Empty
        if (bytes.size > MAX_REMOTE_HOST_RECORD_BYTES) {
            return StoredRead.Invalid(RemoteHostSessionProblem.CORRUPT_RECORD)
        }
        val header = codec.readHeader(bytes)
        if (header.origin != expectedContext.origin) {
            return StoredRead.Invalid(RemoteHostSessionProblem.ORIGIN_MISMATCH)
        }
        val key = keys.existingKey()
            ?: return StoredRead.Invalid(RemoteHostSessionProblem.KEY_UNAVAILABLE)
        val record = codec.decode(bytes, key)
        if (
            record.origin != expectedContext.origin ||
            record.firebaseProjectId != expectedContext.owner.firebaseProjectId ||
            record.uid != expectedContext.owner.uid
        ) {
            return StoredRead.Invalid(
                if (record.origin != expectedContext.origin) {
                    RemoteHostSessionProblem.ORIGIN_MISMATCH
                } else {
                    RemoteHostSessionProblem.OWNER_MISMATCH
                },
            )
        }
        return StoredRead.Present(record, key)
    }

    private fun persist(record: RemoteHostStoredRecord, key: SecretKey) {
        val encoded = codec.encode(record, key)
        try {
            file.writeAtomically(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun <T> withSerializedIo(operation: () -> T): T {
        val callerContext = currentCoroutineContext()
        return PROCESS_STORE_MUTEX.withLock {
            callerContext.ensureActive()
            val result = withContext(NonCancellable + ioDispatcher) {
                operation()
            }
            callerContext.ensureActive()
            result
        }
    }

    private fun newAttemptId(): RemoteHostAttemptId {
        val randomBytes = ByteArray(16)
        secureRandom.nextBytes(randomBytes)
        return try {
            RemoteHostAttemptId.generated(randomBytes.joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            })
        } finally {
            randomBytes.fill(0)
        }
    }

    private fun isValidContext(context: RemoteHostOwnerContext): Boolean =
        context.owner.firebaseProjectId.isNotBlank() &&
            context.owner.uid.isNotBlank() &&
            isBoundedMetadata(context.owner.firebaseProjectId) &&
            isBoundedMetadata(context.owner.uid) &&
            context.origin.length <= MAX_REMOTE_HOST_ORIGIN_BYTES &&
            canonicalOwnerApiOrigin(context.origin) == context.origin

    private fun isBoundedMetadata(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_REMOTE_HOST_METADATA_BYTES &&
            value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_HOST_METADATA_BYTES

    private fun isBoundedSecret(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_REMOTE_HOST_ACCESS_TOKEN_BYTES &&
            value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REMOTE_HOST_ACCESS_TOKEN_BYTES

    private fun sameOwner(first: OwnerAccount, second: OwnerAccount): Boolean =
        first.firebaseProjectId == second.firebaseProjectId && first.uid == second.uid

    private fun RemoteHostStoredRecord.Active.toHostActivation(owner: OwnerAccount): HostActivation =
        HostActivation(
            monitorId = monitorId,
            installationId = installationId,
            membershipId = membershipId,
            generation = generation,
            accessToken = accessToken,
            createdAtMillis = createdAtMillis,
            owner = owner,
            origin = origin,
        )

    private sealed interface StoredRead {
        data object Empty : StoredRead

        data class Present(
            val record: RemoteHostStoredRecord,
            val key: SecretKey,
        ) : StoredRead

        data class Invalid(val problem: RemoteHostSessionProblem) : StoredRead
    }

    private companion object {
        val PROCESS_STORE_MUTEX = Mutex()
    }
}
