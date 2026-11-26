package br.com.c6tag.diagnostico.remote.access

import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface RemoteSupportSessionStore {
    suspend fun load(origin: String): RemoteSupportSessionLoad

    suspend fun presence(): RemoteSessionPresence

    suspend fun beginAttempt(
        origin: String,
        expectedPreviousAttempt: RemoteSupportAttemptId? = null,
    ): RemoteSupportSessionWriteResult

    suspend fun commitPending(
        origin: String,
        attemptId: RemoteSupportAttemptId,
        candidate: PairingCandidateInstallation,
    ): RemoteSupportSessionWriteResult

    suspend fun commitApproved(
        expectedRecord: RemoteSupportSessionRecord,
        supportInstallation: PairingSupportInstallation,
    ): RemoteSupportSessionWriteResult

    suspend fun clearExpected(
        expectedRecord: RemoteSupportSessionRecord,
    ): RemoteSupportSessionWriteResult
}

internal class RemoteSupportAttemptId private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteSupportAttemptId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    internal companion object {
        private val canonicalValue = Regex("[0-9a-f]{32}")

        fun parse(value: String): RemoteSupportAttemptId? =
            value.takeIf(canonicalValue::matches)?.let(::RemoteSupportAttemptId)

        fun generated(value: String): RemoteSupportAttemptId =
            requireNotNull(parse(value))
    }
}

internal sealed interface RemoteSupportSessionRecord {
    val origin: String
    val attemptId: RemoteSupportAttemptId

    data class Attempt(
        override val origin: String,
        override val attemptId: RemoteSupportAttemptId,
    ) : RemoteSupportSessionRecord

    data class Pending(
        override val origin: String,
        override val attemptId: RemoteSupportAttemptId,
        val requestId: String,
        val installationId: String,
        val requestExpiresAtMillis: Long,
        val accessToken: SecretValue,
    ) : RemoteSupportSessionRecord {
        override fun toString(): String =
            "Pending(origin=$origin, attemptId=$attemptId, requestId=$requestId, " +
                "installationId=$installationId, requestExpiresAtMillis=$requestExpiresAtMillis, " +
                "accessToken=[REDACTED])"
    }

    data class Approved(
        override val origin: String,
        override val attemptId: RemoteSupportAttemptId,
        val requestId: String,
        val installationId: String,
        val requestExpiresAtMillis: Long,
        val accessToken: SecretValue,
        val monitorId: String,
        val membershipId: String,
        val observedGeneration: OwnerGeneration,
    ) : RemoteSupportSessionRecord {
        override fun toString(): String =
            "Approved(origin=$origin, attemptId=$attemptId, requestId=$requestId, " +
                "installationId=$installationId, monitorId=$monitorId, " +
                "membershipId=$membershipId, observedGeneration=$observedGeneration, " +
                "accessToken=[REDACTED])"
    }
}

internal sealed interface RemoteSupportSessionLoad {
    data object Empty : RemoteSupportSessionLoad

    data class Attempt(
        val record: RemoteSupportSessionRecord.Attempt,
    ) : RemoteSupportSessionLoad

    data class Pending(
        val record: RemoteSupportSessionRecord.Pending,
    ) : RemoteSupportSessionLoad {
        override fun toString(): String = "Pending($record)"
    }

    data class Approved(
        val record: RemoteSupportSessionRecord.Approved,
    ) : RemoteSupportSessionLoad {
        override fun toString(): String = "Approved($record)"
    }

    data class Unavailable(
        val problem: RemoteSupportSessionProblem,
    ) : RemoteSupportSessionLoad
}

internal sealed interface RemoteSessionPresence {
    data object Absent : RemoteSessionPresence

    data object Present : RemoteSessionPresence

    data class Unavailable(
        val problem: RemoteSupportSessionProblem,
    ) : RemoteSessionPresence
}

internal enum class RemoteSupportSessionProblem(
    val code: String,
) {
    INVALID_ORIGIN("invalid_origin"),
    ORIGIN_MISMATCH("origin_mismatch"),
    UNSUPPORTED_VERSION("unsupported_version"),
    CORRUPT_RECORD("corrupt_record"),
    AUTHENTICATION_FAILED("authentication_failed"),
    KEY_UNAVAILABLE("key_unavailable"),
    PERSISTENCE_FAILURE("persistence_failure"),
    ATTEMPT_ALREADY_EXISTS("attempt_already_exists"),
    NO_MATCHING_EXPECTED_RECORD("no_matching_expected_record"),
    INVALID_CANDIDATE("invalid_candidate"),
    INVALID_SUPPORT_CONTEXT("invalid_support_context"),
}

internal sealed interface RemoteSupportSessionWriteResult {
    data class AttemptStarted(
        val record: RemoteSupportSessionRecord.Attempt,
    ) : RemoteSupportSessionWriteResult

    data class Committed(
        val record: RemoteSupportSessionRecord,
    ) : RemoteSupportSessionWriteResult {
        override fun toString(): String = "Committed($record)"
    }

    data object Cleared : RemoteSupportSessionWriteResult

    data class Rejected(
        val problem: RemoteSupportSessionProblem,
    ) : RemoteSupportSessionWriteResult
}

internal class RemoteSupportSessionRecordFormatException : Exception()

internal class UnsupportedRemoteSupportSessionVersionException : Exception()

internal const val MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES = 64 * 1024
internal const val MAX_REMOTE_SUPPORT_ORIGIN_BYTES = 2 * 1024
internal const val MAX_REMOTE_SUPPORT_TOKEN_BYTES = 8 * 1024
internal const val REMOTE_SUPPORT_GCM_NONCE_BYTES = 12
internal const val REMOTE_SUPPORT_SESSION_FORMAT_VERSION = 1

internal class PersistentRemoteSupportSessionStore(
    private val file: RemoteHostAtomicFile,
    private val keys: RemoteHostKeyProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val secureRandom: SecureRandom = SecureRandom(),
) : RemoteSupportSessionStore {
    private val codec = RemoteSupportSessionCodec()

    override suspend fun load(origin: String): RemoteSupportSessionLoad {
        if (!isValidOrigin(origin)) {
            return RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.INVALID_ORIGIN)
        }
        return withSerializedIo { callerContext ->
            try {
                callerContext.ensureActive()
                val stored = readCurrent(origin)
                callerContext.ensureActive()
                when (stored) {
                    StoredSupportRead.Empty -> RemoteSupportSessionLoad.Empty
                    is StoredSupportRead.Invalid ->
                        RemoteSupportSessionLoad.Unavailable(stored.problem)

                    is StoredSupportRead.Present -> when (val record = stored.record) {
                        is RemoteSupportSessionRecord.Attempt ->
                            RemoteSupportSessionLoad.Attempt(record)

                        is RemoteSupportSessionRecord.Pending ->
                            RemoteSupportSessionLoad.Pending(record)

                        is RemoteSupportSessionRecord.Approved ->
                            RemoteSupportSessionLoad.Approved(record)
                    }
                }
            } catch (failure: Throwable) {
                val problem = failure.toProblem() ?: throw failure
                RemoteSupportSessionLoad.Unavailable(problem)
            }
        }
    }

    override suspend fun presence(): RemoteSessionPresence =
        withSerializedIo {
            try {
                if (file.readRecord(1) == null) {
                    RemoteSessionPresence.Absent
                } else {
                    RemoteSessionPresence.Present
                }
            } catch (_: IOException) {
                RemoteSessionPresence.Unavailable(RemoteSupportSessionProblem.PERSISTENCE_FAILURE)
            }
        }

    override suspend fun beginAttempt(
        origin: String,
        expectedPreviousAttempt: RemoteSupportAttemptId?,
    ): RemoteSupportSessionWriteResult {
        if (!isValidOrigin(origin)) {
            return rejected(RemoteSupportSessionProblem.INVALID_ORIGIN)
        }
        return withSerializedIo { callerContext ->
            try {
                callerContext.ensureActive()
                val current = readCurrent(origin)
                callerContext.ensureActive()
                val existing = when (current) {
                    StoredSupportRead.Empty -> null
                    is StoredSupportRead.Invalid -> return@withSerializedIo rejected(current.problem)
                    is StoredSupportRead.Present -> current
                }
                when (val record = existing?.record) {
                    null -> if (expectedPreviousAttempt != null) {
                        return@withSerializedIo rejected(
                            RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                        )
                    }

                    is RemoteSupportSessionRecord.Attempt -> {
                        if (record.attemptId != expectedPreviousAttempt) {
                            return@withSerializedIo rejected(
                                if (expectedPreviousAttempt == null) {
                                    RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS
                                } else {
                                    RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD
                                },
                            )
                        }
                    }

                    is RemoteSupportSessionRecord.Pending,
                    is RemoteSupportSessionRecord.Approved -> {
                        return@withSerializedIo rejected(
                            RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
                        )
                    }
                }

                val attempt = RemoteSupportSessionRecord.Attempt(origin, newAttemptId())
                val key = existing?.key ?: run {
                    callerContext.ensureActive()
                    keys.keyForNewRecord()
                }
                persist(attempt, key, callerContext)
                RemoteSupportSessionWriteResult.AttemptStarted(attempt)
            } catch (failure: Throwable) {
                failure.toProblem()?.let(::rejected) ?: throw failure
            }
        }
    }

    override suspend fun commitPending(
        origin: String,
        attemptId: RemoteSupportAttemptId,
        candidate: PairingCandidateInstallation,
    ): RemoteSupportSessionWriteResult {
        if (!isValidOrigin(origin)) {
            return rejected(RemoteSupportSessionProblem.INVALID_ORIGIN)
        }
        if (!isValidCandidate(candidate) || candidate.origin != origin) {
            return rejected(
                if (candidate.origin != origin) {
                    RemoteSupportSessionProblem.ORIGIN_MISMATCH
                } else {
                    RemoteSupportSessionProblem.INVALID_CANDIDATE
                },
            )
        }

        return withSerializedIo { callerContext ->
            try {
                callerContext.ensureActive()
                val current = readCurrent(origin)
                callerContext.ensureActive()
                if (current is StoredSupportRead.Invalid) {
                    return@withSerializedIo rejected(current.problem)
                }
                if (
                    current !is StoredSupportRead.Present ||
                    current.record !is RemoteSupportSessionRecord.Attempt ||
                    current.record.attemptId != attemptId
                ) {
                    return@withSerializedIo rejected(
                        RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                    )
                }
                val pending = RemoteSupportSessionRecord.Pending(
                    origin = origin,
                    attemptId = attemptId,
                    requestId = candidate.requestId,
                    installationId = candidate.installationId,
                    requestExpiresAtMillis = candidate.requestExpiresAtMillis,
                    accessToken = candidate.accessToken,
                )
                persist(pending, current.key, callerContext)
                RemoteSupportSessionWriteResult.Committed(pending)
            } catch (failure: Throwable) {
                failure.toProblem()?.let(::rejected) ?: throw failure
            }
        }
    }

    override suspend fun commitApproved(
        expectedRecord: RemoteSupportSessionRecord,
        supportInstallation: PairingSupportInstallation,
    ): RemoteSupportSessionWriteResult {
        if (!isValidStoredRecord(expectedRecord)) {
            return rejected(RemoteSupportSessionProblem.INVALID_CANDIDATE)
        }
        if (!isValidSupportInstallation(supportInstallation)) {
            return rejected(RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT)
        }
        val approvedRecord = when (expectedRecord) {
            is RemoteSupportSessionRecord.Attempt ->
                return rejected(RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD)

            is RemoteSupportSessionRecord.Pending -> {
                if (supportInstallation.installationId != expectedRecord.installationId) {
                    return rejected(RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT)
                }
                RemoteSupportSessionRecord.Approved(
                    origin = expectedRecord.origin,
                    attemptId = expectedRecord.attemptId,
                    requestId = expectedRecord.requestId,
                    installationId = expectedRecord.installationId,
                    requestExpiresAtMillis = expectedRecord.requestExpiresAtMillis,
                    accessToken = expectedRecord.accessToken,
                    monitorId = supportInstallation.monitorId,
                    membershipId = supportInstallation.membershipId,
                    observedGeneration = supportInstallation.monitorGeneration,
                )
            }

            is RemoteSupportSessionRecord.Approved -> {
                if (
                    supportInstallation.monitorGeneration.decimal.toLong() <
                    expectedRecord.observedGeneration.decimal.toLong()
                ) {
                    return rejected(RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT)
                }
                if (
                    supportInstallation.installationId != expectedRecord.installationId ||
                    supportInstallation.monitorId != expectedRecord.monitorId ||
                    supportInstallation.membershipId != expectedRecord.membershipId
                ) {
                    return rejected(RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT)
                }
                expectedRecord.copy(observedGeneration = supportInstallation.monitorGeneration)
            }
        }

        return withSerializedIo { callerContext ->
            try {
                callerContext.ensureActive()
                val current = readCurrent(expectedRecord.origin)
                callerContext.ensureActive()
                if (current is StoredSupportRead.Invalid) {
                    return@withSerializedIo rejected(current.problem)
                }
                if (
                    current !is StoredSupportRead.Present ||
                    !sameRecord(current.record, expectedRecord)
                ) {
                    return@withSerializedIo rejected(
                        RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                    )
                }
                persist(approvedRecord, current.key, callerContext)
                RemoteSupportSessionWriteResult.Committed(approvedRecord)
            } catch (failure: Throwable) {
                failure.toProblem()?.let(::rejected) ?: throw failure
            }
        }
    }

    override suspend fun clearExpected(
        expectedRecord: RemoteSupportSessionRecord,
    ): RemoteSupportSessionWriteResult {
        if (!isValidStoredRecord(expectedRecord)) {
            return rejected(RemoteSupportSessionProblem.INVALID_CANDIDATE)
        }
        return withSerializedIo { callerContext ->
            try {
                callerContext.ensureActive()
                val current = readCurrent(expectedRecord.origin)
                callerContext.ensureActive()
                if (current is StoredSupportRead.Invalid) {
                    return@withSerializedIo rejected(current.problem)
                }
                if (
                    current !is StoredSupportRead.Present ||
                    !sameRecord(current.record, expectedRecord)
                ) {
                    return@withSerializedIo rejected(
                        RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                    )
                }
                mutateAtomically(callerContext) {
                    file.deleteRecord()
                    if (file.readRecord(1) != null) {
                        rejected(RemoteSupportSessionProblem.PERSISTENCE_FAILURE)
                    } else {
                        RemoteSupportSessionWriteResult.Cleared
                    }
                }
            } catch (failure: Throwable) {
                failure.toProblem()?.let(::rejected) ?: throw failure
            }
        }
    }

    private fun readCurrent(origin: String): StoredSupportRead {
        val bytes = file.readRecord(MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES + 1)
            ?: return StoredSupportRead.Empty
        if (bytes.size > MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES) {
            return StoredSupportRead.Invalid(RemoteSupportSessionProblem.CORRUPT_RECORD)
        }
        val header = codec.readHeader(bytes)
        if (header.origin != origin) {
            return StoredSupportRead.Invalid(RemoteSupportSessionProblem.ORIGIN_MISMATCH)
        }
        val key = keys.existingKey()
            ?: return StoredSupportRead.Invalid(RemoteSupportSessionProblem.KEY_UNAVAILABLE)
        val record = codec.decode(bytes, key)
        if (record.origin != origin) {
            return StoredSupportRead.Invalid(RemoteSupportSessionProblem.ORIGIN_MISMATCH)
        }
        if (!isValidStoredRecord(record)) {
            return StoredSupportRead.Invalid(RemoteSupportSessionProblem.CORRUPT_RECORD)
        }
        return StoredSupportRead.Present(record, key)
    }

    private suspend fun persist(
        record: RemoteSupportSessionRecord,
        key: SecretKey,
        callerContext: CoroutineContext,
    ) {
        val encoded = codec.encode(record, key)
        try {
            mutateAtomically(callerContext) {
                file.writeAtomically(encoded)
            }
        } finally {
            encoded.fill(0)
        }
    }

    private suspend fun <T> withSerializedIo(
        operation: suspend (CoroutineContext) -> T,
    ): T {
        val callerContext = currentCoroutineContext()
        return PROCESS_STORE_MUTEX.withLock {
            callerContext.ensureActive()
            withContext(ioDispatcher) {
                callerContext.ensureActive()
                operation(callerContext)
            }
        }
    }

    private suspend fun <T> mutateAtomically(
        callerContext: CoroutineContext,
        mutation: () -> T,
    ): T {
        callerContext.ensureActive()
        return withContext(NonCancellable + ioDispatcher) {
            callerContext.ensureActive()
            mutation()
        }
    }

    private fun newAttemptId(): RemoteSupportAttemptId {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return try {
            val hexadecimal = "0123456789abcdef"
            val value = buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(hexadecimal[unsigned ushr 4])
                    append(hexadecimal[unsigned and 0x0f])
                }
            }
            RemoteSupportAttemptId.generated(value)
        } finally {
            bytes.fill(0)
        }
    }

    private fun isValidOrigin(origin: String): Boolean =
        origin.length <= MAX_REMOTE_SUPPORT_ORIGIN_BYTES &&
            origin.toByteArray(StandardCharsets.US_ASCII).size <= MAX_REMOTE_SUPPORT_ORIGIN_BYTES &&
            canonicalOwnerApiOrigin(origin) == origin

    private fun isValidCandidate(candidate: PairingCandidateInstallation): Boolean =
        candidate.status == "Pending" &&
            isValidPendingMetadata(
                origin = candidate.origin,
                requestId = candidate.requestId,
                installationId = candidate.installationId,
                requestExpiresAtMillis = candidate.requestExpiresAtMillis,
                accessToken = candidate.accessToken,
            )

    private fun isValidSupportInstallation(
        supportInstallation: PairingSupportInstallation,
    ): Boolean =
        PairingAccessJson.isCanonicalUuid(supportInstallation.installationId) &&
            PairingAccessJson.isCanonicalUuid(supportInstallation.monitorId) &&
            PairingAccessJson.isCanonicalUuid(supportInstallation.membershipId) &&
            supportInstallation.role == "Support" &&
            supportInstallation.status == "Active"

    private fun isValidStoredRecord(record: RemoteSupportSessionRecord): Boolean {
        if (
            !isValidOrigin(record.origin) ||
            RemoteSupportAttemptId.parse(record.attemptId.value) != record.attemptId
        ) {
            return false
        }
        return when (record) {
            is RemoteSupportSessionRecord.Attempt -> true
            is RemoteSupportSessionRecord.Pending ->
                isValidPendingMetadata(
                    origin = record.origin,
                    requestId = record.requestId,
                    installationId = record.installationId,
                    requestExpiresAtMillis = record.requestExpiresAtMillis,
                    accessToken = record.accessToken,
                )

            is RemoteSupportSessionRecord.Approved ->
                isValidPendingMetadata(
                    origin = record.origin,
                    requestId = record.requestId,
                    installationId = record.installationId,
                    requestExpiresAtMillis = record.requestExpiresAtMillis,
                    accessToken = record.accessToken,
                ) &&
                    PairingAccessJson.isCanonicalUuid(record.monitorId) &&
                    PairingAccessJson.isCanonicalUuid(record.membershipId) &&
                    OwnerGeneration.parse(record.observedGeneration.decimal) ==
                    record.observedGeneration
        }
    }

    private fun isValidPendingMetadata(
        origin: String,
        requestId: String,
        installationId: String,
        requestExpiresAtMillis: Long,
        accessToken: SecretValue,
    ): Boolean {
        val token = accessToken.reveal()
        return origin.toByteArray(StandardCharsets.US_ASCII).size <= MAX_REMOTE_SUPPORT_ORIGIN_BYTES &&
            PairingAccessJson.isCanonicalUuid(requestId) &&
            PairingAccessJson.isCanonicalUuid(installationId) &&
            requestExpiresAtMillis >= 0 &&
            token.length <= MAX_REMOTE_SUPPORT_TOKEN_BYTES &&
            token.toByteArray(StandardCharsets.US_ASCII).size <= MAX_REMOTE_SUPPORT_TOKEN_BYTES &&
            PairingAccessJson.isSafeBearer(token)
    }

    private fun sameRecord(
        first: RemoteSupportSessionRecord,
        second: RemoteSupportSessionRecord,
    ): Boolean {
        if (
            first::class != second::class ||
            first.origin != second.origin ||
            first.attemptId != second.attemptId
        ) {
            return false
        }
        return when {
            first is RemoteSupportSessionRecord.Attempt &&
                second is RemoteSupportSessionRecord.Attempt -> true

            first is RemoteSupportSessionRecord.Pending &&
                second is RemoteSupportSessionRecord.Pending ->
                first.requestId == second.requestId &&
                    first.installationId == second.installationId &&
                    first.requestExpiresAtMillis == second.requestExpiresAtMillis &&
                    first.accessToken.reveal() == second.accessToken.reveal()

            first is RemoteSupportSessionRecord.Approved &&
                second is RemoteSupportSessionRecord.Approved ->
                first.requestId == second.requestId &&
                    first.installationId == second.installationId &&
                    first.requestExpiresAtMillis == second.requestExpiresAtMillis &&
                    first.accessToken.reveal() == second.accessToken.reveal() &&
                    first.monitorId == second.monitorId &&
                    first.membershipId == second.membershipId &&
                    first.observedGeneration == second.observedGeneration

            else -> false
        }
    }

    private fun Throwable.toProblem(): RemoteSupportSessionProblem? =
        when (this) {
            is UnsupportedRemoteSupportSessionVersionException ->
                RemoteSupportSessionProblem.UNSUPPORTED_VERSION

            is AEADBadTagException -> RemoteSupportSessionProblem.AUTHENTICATION_FAILED
            is EOFException,
            is RemoteSupportSessionRecordFormatException -> RemoteSupportSessionProblem.CORRUPT_RECORD

            is IOException -> RemoteSupportSessionProblem.PERSISTENCE_FAILURE
            is GeneralSecurityException -> RemoteSupportSessionProblem.KEY_UNAVAILABLE
            else -> null
        }

    private fun rejected(problem: RemoteSupportSessionProblem) =
        RemoteSupportSessionWriteResult.Rejected(problem)

    private sealed interface StoredSupportRead {
        data object Empty : StoredSupportRead

        data class Present(
            val record: RemoteSupportSessionRecord,
            val key: SecretKey,
        ) : StoredSupportRead

        data class Invalid(
            val problem: RemoteSupportSessionProblem,
        ) : StoredSupportRead
    }

    private companion object {
        val PROCESS_STORE_MUTEX = Mutex()
    }
}
