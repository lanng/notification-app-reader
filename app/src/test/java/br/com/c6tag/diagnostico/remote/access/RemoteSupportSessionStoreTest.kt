package br.com.c6tag.diagnostico.remote.access

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSupportSessionStoreTest {
    @Test
    fun attemptPendingAndApprovedRecordsRoundTripWithoutPersistingInvitationSecret() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteSupportSessionStore(file, keys)

        assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val attemptBytes = file.snapshot()
        assertFalse(attemptBytes.containsBytes(SYNTHETIC_PAT))

        val candidate = candidate()
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate),
        ).record as RemoteSupportSessionRecord.Pending
        val pendingBytes = file.snapshot()
        assertFalse(pendingBytes.containsBytes(SYNTHETIC_PAT))
        assertFalse(pendingBytes.containsBytes(SYNTHETIC_INVITATION_SECRET))
        assertEquals(candidate.accessToken.reveal(), pending.accessToken.reveal())
        assertNotEquals(
            nonce(attemptBytes).toList(),
            nonce(pendingBytes).toList(),
        )

        val approvedResult = committed(
            store.commitApproved(pending, supportInstallation()),
        )
        val approved = approvedResult.record as RemoteSupportSessionRecord.Approved
        assertEquals("7", approved.observedGeneration.decimal)
        assertFalse(file.snapshot().containsBytes(SYNTHETIC_PAT))
        assertFalse(approvedResult.toString().contains(SYNTHETIC_PAT))

        val reopened = PersistentRemoteSupportSessionStore(file, keys)
        val reloaded = (reopened.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record
        assertEquals(approved.origin, reloaded.origin)
        assertEquals(approved.attemptId, reloaded.attemptId)
        assertEquals(approved.requestId, reloaded.requestId)
        assertEquals(approved.installationId, reloaded.installationId)
        assertEquals(approved.monitorId, reloaded.monitorId)
        assertEquals(approved.membershipId, reloaded.membershipId)
        assertEquals(approved.observedGeneration, reloaded.observedGeneration)
        assertEquals(SYNTHETIC_PAT, reloaded.accessToken.reveal())
        assertFalse(reloaded.toString().contains(SYNTHETIC_PAT))
        assertEquals(1, keys.keyCreations)
    }

    @Test
    fun attemptReplacementRequiresMatchingAttemptAndNeverReplacesPendingOrApproved() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val firstAttempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val firstBytes = file.snapshot()

        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
            ),
            store.beginAttempt(ORIGIN),
        )
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
            ),
            store.beginAttempt(ORIGIN, attemptId("00000000000000000000000000000001")),
        )
        assertEquals(firstBytes.toList(), file.snapshot().toList())

        val replacementAttempt = attemptStarted(
            store.beginAttempt(ORIGIN, firstAttempt.attemptId),
        ).record
        assertNotEquals(firstAttempt.attemptId, replacementAttempt.attemptId)
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
            ),
            store.commitPending(ORIGIN, firstAttempt.attemptId, candidate()),
        )

        val pending = committed(
            store.commitPending(ORIGIN, replacementAttempt.attemptId, candidate()),
        ).record
        val pendingBytes = file.snapshot()
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
            ),
            store.beginAttempt(ORIGIN, replacementAttempt.attemptId),
        )
        assertEquals(pendingBytes.toList(), file.snapshot().toList())

        val approved = committed(
            store.commitApproved(pending, supportInstallation()),
        ).record
        val approvedBytes = file.snapshot()
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
            ),
            store.beginAttempt(ORIGIN, replacementAttempt.attemptId),
        )
        assertEquals(approvedBytes.toList(), file.snapshot().toList())
        assertTrue(approved is RemoteSupportSessionRecord.Approved)
    }

    @Test
    fun approvalRequiresMatchingCandidateAndActiveSupportAndPreservesStableIdentity() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val pendingBytes = file.snapshot()

        listOf(
            supportInstallation(installationId = OTHER_INSTALLATION_ID),
            supportInstallation(role = "Host"),
            supportInstallation(status = "Revoked"),
            supportInstallation(monitorId = INVALID_UUID),
            supportInstallation(membershipId = INVALID_UUID),
        ).forEach { invalidSupport ->
            assertEquals(
                RemoteSupportSessionWriteResult.Rejected(
                    RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT,
                ),
                store.commitApproved(pending, invalidSupport),
            )
            assertEquals(pendingBytes.toList(), file.snapshot().toList())
        }

        val approved = committed(
            store.commitApproved(pending, supportInstallation()),
        ).record as RemoteSupportSessionRecord.Approved
        val approvedBytes = file.snapshot()
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
            ),
            store.commitApproved(pending, supportInstallation(generation = "9")),
        )
        assertEquals(approvedBytes.toList(), file.snapshot().toList())

        val updated = committed(
            store.commitApproved(
                approved,
                supportInstallation(generation = "9"),
            ),
        ).record as RemoteSupportSessionRecord.Approved
        assertEquals(approved.attemptId, updated.attemptId)
        assertEquals(approved.requestId, updated.requestId)
        assertEquals(approved.installationId, updated.installationId)
        assertEquals(approved.monitorId, updated.monitorId)
        assertEquals(approved.membershipId, updated.membershipId)
        assertEquals("9", updated.observedGeneration.decimal)
        assertEquals(SYNTHETIC_PAT, updated.accessToken.reveal())
    }

    @Test
    fun approvedSessionRejectsLateOlderGenerationWithoutChangingDurableRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val approved = committed(
            store.commitApproved(pending, supportInstallation(generation = "2")),
        ).record as RemoteSupportSessionRecord.Approved
        val approvedBytes = file.snapshot()

        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT,
            ),
            store.commitApproved(
                approved,
                supportInstallation(generation = "1"),
            ),
        )
        assertEquals(approvedBytes.toList(), file.snapshot().toList())

        val persisted = (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record
        assertEquals("2", persisted.observedGeneration.decimal)
        assertEquals(approved.attemptId, persisted.attemptId)
        assertEquals(SYNTHETIC_PAT, persisted.accessToken.reveal())
    }

    @Test
    fun approvedSessionAllowsEqualAndNumericallyHigherGenerations() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val approved = committed(
            store.commitApproved(pending, supportInstallation(generation = "2")),
        ).record as RemoteSupportSessionRecord.Approved

        val equalGeneration = committed(
            store.commitApproved(
                approved,
                supportInstallation(generation = "2"),
            ),
        ).record as RemoteSupportSessionRecord.Approved
        assertEquals("2", equalGeneration.observedGeneration.decimal)

        val advancedGeneration = committed(
            store.commitApproved(
                equalGeneration,
                supportInstallation(generation = "10"),
            ),
        ).record as RemoteSupportSessionRecord.Approved
        assertEquals("10", advancedGeneration.observedGeneration.decimal)
        assertEquals(approved.attemptId, advancedGeneration.attemptId)
        assertEquals(approved.requestId, advancedGeneration.requestId)
        assertEquals(approved.installationId, advancedGeneration.installationId)
        assertEquals(approved.monitorId, advancedGeneration.monitorId)
        assertEquals(approved.membershipId, advancedGeneration.membershipId)
        assertEquals(SYNTHETIC_PAT, advancedGeneration.accessToken.reveal())
    }

    @Test
    fun clearExpectedUsesCompleteRecordAndOriginCompareAndSet() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val pendingBytes = file.snapshot()
        val differentCredential = pending.copy(
            accessToken = SecretValue.of("synthetic-different-token"),
        )

        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
            ),
            store.clearExpected(differentCredential),
        )
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.ORIGIN_MISMATCH,
            ),
            store.clearExpected(pending.copy(origin = OTHER_ORIGIN)),
        )
        assertEquals(pendingBytes.toList(), file.snapshot().toList())

        assertEquals(RemoteSupportSessionWriteResult.Cleared, store.clearExpected(pending))
        assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
        assertEquals(1, file.deleteCount)
    }

    @Test
    fun preMutationWriteAndDeleteFailuresRetainTheLastDurableRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val originalAttempt = file.snapshot()
        file.nextWriteFailure = IOException("synthetic pre-mutation atomic write failure")

        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
            ),
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        )
        assertEquals(originalAttempt.toList(), file.snapshot().toList())
        assertEquals(attempt, (store.load(ORIGIN) as RemoteSupportSessionLoad.Attempt).record)

        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record
        val durablePending = file.snapshot()
        file.nextDeleteFailure = IOException("synthetic atomic delete failure")
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
            ),
            store.clearExpected(pending),
        )
        assertEquals(durablePending.toList(), file.snapshot().toList())
        assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
    }

    @Test
    fun wrongOriginCorruptOversizedFutureAndMissingKeyFailClosedWithoutReplacement() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteSupportSessionStore(file, keys)
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val original = file.snapshot()

        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.ORIGIN_MISMATCH),
            store.load(OTHER_ORIGIN),
        )
        assertEquals(original.toList(), file.snapshot().toList())

        file.record = byteArrayOf()
        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.CORRUPT_RECORD),
            store.load(ORIGIN),
        )
        file.record = ByteArray(MAX_REMOTE_SUPPORT_SESSION_RECORD_BYTES + 1)
        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.CORRUPT_RECORD),
            store.load(ORIGIN),
        )
        file.record = original.copyOf().apply {
            this[SUPPORT_VERSION_OFFSET + 3] = 2
        }
        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.UNSUPPORTED_VERSION),
            store.load(ORIGIN),
        )

        file.record = original
        keys.keyAvailable = false
        val creationsBeforeLoad = keys.keyCreations
        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.KEY_UNAVAILABLE),
            store.load(ORIGIN),
        )
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(RemoteSupportSessionProblem.KEY_UNAVAILABLE),
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        )
        assertEquals(creationsBeforeLoad, keys.keyCreations)
        assertEquals(original.toList(), file.snapshot().toList())
    }

    @Test
    fun codecAuthenticatesKindVersionAndOriginAndRejectsMalformedEnvelopeAndPayload() {
        val codec = RemoteSupportSessionCodec()
        val key = JceTestKeyProvider().key
        val attemptId = attemptId("0123456789abcdef0123456789abcdef")
        val record = RemoteSupportSessionRecord.Attempt(ORIGIN, attemptId)
        val encoded = codec.encode(record, key)
        assertNotEquals(
            "TAGRHST1".toByteArray(Charsets.US_ASCII).toList(),
            encoded.copyOfRange(0, 8).toList(),
        )
        assertEquals(record, codec.decode(encoded, key))

        assertUnavailableCodec(codec, encoded.copyOf(encoded.size - 1), key)
        assertUnavailableCodec(codec, encoded + byteArrayOf(0x01), key)
        assertUnavailableCodec(codec, encoded.copyOf().apply { this[0] = 0 }, key)

        val kindTampered = encoded.copyOf().apply {
            this[SUPPORT_KIND_OFFSET] = 2
        }
        assertAuthenticationFailure(codec, kindTampered, key)

        val originOffset = SUPPORT_ORIGIN_OFFSET
        assertEquals(ORIGIN.length, OTHER_ORIGIN.length)
        val originTampered = encoded.copyOf().apply {
            OTHER_ORIGIN.toByteArray(Charsets.US_ASCII).copyInto(this, originOffset)
        }
        assertAuthenticationFailure(codec, originTampered, key)

        val futureVersion = encoded.copyOf().apply {
            this[SUPPORT_VERSION_OFFSET + 3] = 2
        }
        assertTrue(runCatching { codec.decode(futureVersion, key) }.exceptionOrNull()
            is UnsupportedRemoteSupportSessionVersionException)

        val invalidRecord = RemoteSupportSessionRecord.Pending(
            origin = ORIGIN,
            attemptId = attemptId,
            requestId = INVALID_UUID,
            installationId = INSTALLATION_ID,
            requestExpiresAtMillis = -1,
            accessToken = SecretValue.of(SYNTHETIC_PAT),
        )
        assertTrue(
            runCatching { codec.encode(invalidRecord, key) }.exceptionOrNull()
                is RemoteSupportSessionRecordFormatException,
        )

        val trailingPlaintext = authenticatedEnvelope(
            kind = SUPPORT_ATTEMPT_KIND,
            origin = ORIGIN,
            plaintext = attemptPayload(ORIGIN, attemptId.value) + byteArrayOf(0x01),
            key = key,
        )
        assertUnavailableCodec(codec, trailingPlaintext, key)

        val mismatchedPayloadKind = authenticatedEnvelope(
            kind = SUPPORT_ATTEMPT_KIND,
            origin = ORIGIN,
            plaintext = attemptPayload(ORIGIN, attemptId.value).apply { this[0] = 2 },
            key = key,
        )
        assertUnavailableCodec(codec, mismatchedPayloadKind, key)

        val mismatchedPayloadOrigin = authenticatedEnvelope(
            kind = SUPPORT_ATTEMPT_KIND,
            origin = ORIGIN,
            plaintext = attemptPayload(OTHER_ORIGIN, attemptId.value),
            key = key,
        )
        assertUnavailableCodec(codec, mismatchedPayloadOrigin, key)

        val invalidUtf8Payload = attemptPayload(ORIGIN, byteArrayOf(0xc3.toByte(), 0x28))
        val invalidUtf8 = authenticatedEnvelope(
            kind = SUPPORT_ATTEMPT_KIND,
            origin = ORIGIN,
            plaintext = invalidUtf8Payload,
            key = key,
        )
        assertUnavailableCodec(codec, invalidUtf8, key)
    }

    @Test
    fun presenceDistinguishesAbsentPresentIncludingEmptyAndUnavailable() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteSupportSessionStore(file, keys)

        assertEquals(RemoteSessionPresence.Absent, store.presence())
        assertEquals(0, keys.existingKeyReads)
        assertEquals(1, file.readLimits.last())
        file.record = byteArrayOf()
        assertEquals(RemoteSessionPresence.Present, store.presence())
        assertEquals(1, file.readLimits.last())
        file.record = byteArrayOf(0)
        assertEquals(RemoteSessionPresence.Present, store.presence())
        assertEquals(1, file.readLimits.last())
        file.nextReadFailure = IOException("synthetic presence read failure")
        assertEquals(
            RemoteSessionPresence.Unavailable(RemoteSupportSessionProblem.PERSISTENCE_FAILURE),
            store.presence(),
        )
        assertEquals(0, keys.existingKeyReads)
        assertEquals(0, keys.keyCreations)
    }

    @Test
    fun presenceDoesNotTreatMissingReadWithBackupOrNewFileAsAbsent() = runBlocking {
        val directory = Files.createTempDirectory("g5-support-presence").toFile()
        val recordFile = File(directory, "synthetic-support-session.bin")
        val keys = JceTestKeyProvider()
        try {
            listOf(".bak", ".new").forEach { suffix ->
                val recoveryFile = File("${recordFile.path}$suffix")
                Files.write(recoveryFile.toPath(), byteArrayOf(0x01))
                val readOperations = object : RemoteHostAtomicReadOperations {
                    override fun openRead(): InputStream =
                        throw FileNotFoundException("synthetic ambiguous support record")
                }
                val store = PersistentRemoteSupportSessionStore(
                    AndroidAtomicRemoteHostFile(recordFile, readOperations),
                    keys,
                )

                assertEquals(
                    RemoteSessionPresence.Unavailable(
                        RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                    ),
                    store.presence(),
                )
                assertTrue(recoveryFile.isFile)
                Files.delete(recoveryFile.toPath())
            }
            assertEquals(0, keys.existingKeyReads)
            assertEquals(0, keys.keyCreations)
        } finally {
            listOf(
                recordFile,
                File("${recordFile.path}.bak"),
                File("${recordFile.path}.new"),
            ).forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(directory.toPath())
        }
    }

    @Test(timeout = 30_000)
    fun concurrentAttemptsAndCancellationSettleBeforeTheCallerObservesCancellation() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteSupportSessionStore(file, keys)
        val stores = (0 until 8).map { index ->
            if (index % 2 == 0) {
                store
            } else {
                PersistentRemoteSupportSessionStore(file, keys)
            }
        }
        val results = withTimeout(JOB_TIMEOUT_MILLIS) {
            stores.map { currentStore ->
                async(Dispatchers.Default) { currentStore.beginAttempt(ORIGIN) }
            }.awaitAll()
        }
        assertEquals(1, results.count { it is RemoteSupportSessionWriteResult.AttemptStarted })
        assertEquals(
            7,
            results.count {
                it == RemoteSupportSessionWriteResult.Rejected(
                    RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
                )
            },
        )

        val otherFile = MemoryAtomicRemoteHostFile()
        val otherStore = PersistentRemoteSupportSessionStore(otherFile, JceTestKeyProvider())
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        otherFile.writeStarted = writeStarted
        otherFile.releaseWrite = releaseWrite
        val cancelled = async(Dispatchers.Default) { otherStore.beginAttempt(ORIGIN) }
        try {
            assertTrue(
                withContext(Dispatchers.IO) {
                    writeStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                },
            )
            cancelled.cancel()
        } finally {
            cancelled.cancel()
            releaseWrite.countDown()
            withTimeout(JOB_TIMEOUT_MILLIS) {
                cancelled.join()
            }
        }
        var cancellationObserved = false
        try {
            withTimeout(JOB_TIMEOUT_MILLIS) {
                cancelled.await()
            }
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
        assertTrue(otherStore.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
    }

    @Test(timeout = 30_000)
    fun cancellationDuringReadPreventsBeginAttemptMutationAndNewKeyCreation() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteSupportSessionStore(file, keys)

        cancelDuringBlockedRead(file) {
            store.beginAttempt(ORIGIN)
        }

        assertNull(file.record)
        assertEquals(0, file.writeCount)
        assertEquals(0, keys.keyCreations)
    }

    @Test(timeout = 30_000)
    fun cancellationDuringReadPreventsCommitPendingMutationAndPreservesAttempt() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val original = file.snapshot()
        val writesBeforeCancellation = file.writeCount

        cancelDuringBlockedRead(file) {
            store.commitPending(ORIGIN, attempt.attemptId, candidate())
        }

        assertEquals(writesBeforeCancellation, file.writeCount)
        assertEquals(original.toList(), file.snapshot().toList())
        assertFalse(file.snapshot().containsBytes(SYNTHETIC_PAT))
        assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
    }

    @Test(timeout = 30_000)
    fun cancellationDuringReadPreventsClearExpectedDeletion() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val original = file.snapshot()
        val deletesBeforeCancellation = file.deleteCount

        cancelDuringBlockedRead(file) {
            store.clearExpected(pending)
        }

        assertEquals(deletesBeforeCancellation, file.deleteCount)
        assertEquals(original.toList(), file.snapshot().toList())
        assertFalse(file.snapshot().containsBytes(SYNTHETIC_PAT))
        assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
    }

    @Test(timeout = 30_000)
    fun cancellationDuringCommitPendingAtomicWriteSettlesDurablePendingBeforePropagating() =
        runBlocking {
            val file = MemoryAtomicRemoteHostFile()
            val keys = JceTestKeyProvider()
            val store = PersistentRemoteSupportSessionStore(file, keys)
            val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
            val writeStarted = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            file.writeStarted = writeStarted
            file.releaseWrite = releaseWrite
            val commit = async(Dispatchers.Default) {
                store.commitPending(ORIGIN, attempt.attemptId, candidate())
            }

            try {
                assertTrue(
                    withContext(Dispatchers.IO) {
                        writeStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    },
                )
                commit.cancel()
            } finally {
                commit.cancel()
                releaseWrite.countDown()
            }

            var cancellationObserved = false
            try {
                withTimeout(JOB_TIMEOUT_MILLIS) {
                    commit.await()
                }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
            assertTrue(cancellationObserved)

            val reopened = PersistentRemoteSupportSessionStore(file, keys)
            val pending = (reopened.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record
            assertEquals(attempt.attemptId, pending.attemptId)
            assertEquals(REQUEST_ID, pending.requestId)
            assertEquals(INSTALLATION_ID, pending.installationId)
            assertEquals(SYNTHETIC_PAT, pending.accessToken.reveal())
            assertFalse(file.snapshot().containsBytes(SYNTHETIC_PAT))
        }

    @Test(timeout = 30_000)
    fun cancellationDuringClearExpectedDeletionSettlesBeforePropagating() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        file.deleteStarted = deleteStarted
        file.releaseDelete = releaseDelete
        val deletesBeforeCancellation = file.deleteCount
        val clear = async(Dispatchers.Default) {
            store.clearExpected(pending)
        }

        try {
            assertTrue(
                withContext(Dispatchers.IO) {
                    deleteStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                },
            )
            clear.cancel()
        } finally {
            clear.cancel()
            releaseDelete.countDown()
        }

        var cancellationObserved = false
        try {
            withTimeout(JOB_TIMEOUT_MILLIS) {
                clear.await()
            }
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
        assertEquals(deletesBeforeCancellation + 1, file.deleteCount)
        assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
    }

    @Test
    fun invalidCandidatesAndOriginsAreRejectedWithoutMutation() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider())
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val original = file.snapshot()

        listOf(
            candidate(requestId = INVALID_UUID),
            candidate(installationId = INVALID_UUID),
            candidate(expiresAtMillis = -1),
            candidate(status = "Approved"),
            candidate(token = "contains whitespace"),
        ).forEach { invalidCandidate ->
            assertEquals(
                RemoteSupportSessionWriteResult.Rejected(
                    RemoteSupportSessionProblem.INVALID_CANDIDATE,
                ),
                store.commitPending(ORIGIN, attempt.attemptId, invalidCandidate),
            )
            assertEquals(original.toList(), file.snapshot().toList())
        }
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(RemoteSupportSessionProblem.ORIGIN_MISMATCH),
            store.commitPending(
                ORIGIN,
                attempt.attemptId,
                candidate(origin = OTHER_ORIGIN),
            ),
        )
        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(RemoteSupportSessionProblem.INVALID_ORIGIN),
            store.beginAttempt("http://api.example.test"),
        )
        assertEquals(original.toList(), file.snapshot().toList())
    }

    private fun attemptStarted(
        result: RemoteSupportSessionWriteResult,
    ): RemoteSupportSessionWriteResult.AttemptStarted =
        result as? RemoteSupportSessionWriteResult.AttemptStarted
            ?: throw AssertionError("Expected a durable attempt, got $result")

    private suspend fun cancelDuringBlockedRead(
        file: MemoryAtomicRemoteHostFile,
        operation: suspend () -> Unit,
    ) = coroutineScope {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        file.readStarted = readStarted
        file.releaseRead = releaseRead
        val job = async(Dispatchers.Default) {
            operation()
        }

        try {
            assertTrue(
                withContext(Dispatchers.IO) {
                    readStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                },
            )
            job.cancel()
        } finally {
            job.cancel()
            releaseRead.countDown()
            withTimeout(JOB_TIMEOUT_MILLIS) {
                job.join()
            }
        }

        var cancellationObserved = false
        try {
            withTimeout(JOB_TIMEOUT_MILLIS) {
                job.await()
            }
        } catch (_: CancellationException) {
            cancellationObserved = true
        }
        assertTrue(cancellationObserved)
    }

    private fun committed(
        result: RemoteSupportSessionWriteResult,
    ): RemoteSupportSessionWriteResult.Committed =
        result as? RemoteSupportSessionWriteResult.Committed
            ?: throw AssertionError("Expected a committed record, got $result")

    private fun candidate(
        origin: String = ORIGIN,
        requestId: String = REQUEST_ID,
        installationId: String = INSTALLATION_ID,
        expiresAtMillis: Long = 1_790_000_000_000L,
        status: String = "Pending",
        token: String = SYNTHETIC_PAT,
    ): PairingCandidateInstallation =
        PairingCandidateInstallation(
            origin = origin,
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = expiresAtMillis,
            accessToken = SecretValue.of(token),
            status = status,
        )

    private fun supportInstallation(
        installationId: String = INSTALLATION_ID,
        monitorId: String = MONITOR_ID,
        membershipId: String = MEMBERSHIP_ID,
        role: String = "Support",
        status: String = "Active",
        generation: String = "7",
    ): PairingSupportInstallation =
        PairingSupportInstallation(
            installationId = installationId,
            monitorId = monitorId,
            membershipId = membershipId,
            role = role,
            status = status,
            monitorGeneration = generation(generation),
        )

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private fun attemptId(value: String): RemoteSupportAttemptId =
        requireNotNull(RemoteSupportAttemptId.parse(value))

    private fun assertUnavailableCodec(
        codec: RemoteSupportSessionCodec,
        bytes: ByteArray,
        key: SecretKey,
    ) {
        assertTrue(
            "Expected malformed envelope to be rejected",
            runCatching { codec.decode(bytes, key) }.isFailure,
        )
    }

    private fun assertAuthenticationFailure(
        codec: RemoteSupportSessionCodec,
        bytes: ByteArray,
        key: SecretKey,
    ) {
        assertTrue(
            "Expected authenticated header modification to fail authentication",
            runCatching { codec.decode(bytes, key) }.exceptionOrNull() is AEADBadTagException,
        )
    }

    private fun nonce(envelope: ByteArray): ByteArray {
        val originLengthOffset = SUPPORT_ORIGIN_LENGTH_OFFSET
        val originLength =
            ((envelope[originLengthOffset].toInt() and 0xff) shl 8) or
                (envelope[originLengthOffset + 1].toInt() and 0xff)
        val nonceLengthOffset = SUPPORT_ORIGIN_OFFSET + originLength
        val nonceLength = envelope[nonceLengthOffset].toInt() and 0xff
        return envelope.copyOfRange(nonceLengthOffset + 1 + Int.SIZE_BYTES, nonceLengthOffset + 1 + Int.SIZE_BYTES + nonceLength)
    }

    private fun authenticatedEnvelope(
        kind: Int,
        origin: String,
        plaintext: ByteArray,
        key: SecretKey,
    ): ByteArray {
        val originBytes = origin.toByteArray(Charsets.US_ASCII)
        val nonce = ByteArray(REMOTE_SUPPORT_GCM_NONCE_BYTES) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(supportAssociatedData(kind, originBytes))
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SUPPORT_MAGIC)
                output.writeInt(REMOTE_SUPPORT_SESSION_FORMAT_VERSION)
                output.writeByte(kind)
                output.writeShort(originBytes.size)
                output.write(originBytes)
                output.writeByte(nonce.size)
                output.writeInt(encrypted.size)
                output.write(nonce)
                output.write(encrypted)
            }
        }.toByteArray()
    }

    private fun supportAssociatedData(kind: Int, origin: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(SUPPORT_MAGIC)
                output.writeInt(REMOTE_SUPPORT_SESSION_FORMAT_VERSION)
                output.writeByte(kind)
                output.writeShort(origin.size)
                output.write(origin)
            }
        }.toByteArray()

    private fun attemptPayload(origin: String, attemptId: String): ByteArray =
        attemptPayload(origin, attemptId.toByteArray(Charsets.UTF_8))

    private fun attemptPayload(origin: String, attemptId: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(SUPPORT_ATTEMPT_KIND)
                output.writeBoundedString(origin.toByteArray(Charsets.UTF_8))
                output.writeBoundedString(attemptId)
            }
        }.toByteArray()

    private fun DataOutputStream.writeBoundedString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun ByteArray.containsBytes(value: String): Boolean =
        toString(Charsets.ISO_8859_1).contains(value)

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var record: ByteArray? = null
        @Volatile
        var nextReadFailure: IOException? = null
        @Volatile
        var nextWriteFailure: IOException? = null
        @Volatile
        var nextDeleteFailure: IOException? = null
        @Volatile
        var writeStarted: CountDownLatch? = null
        @Volatile
        var releaseWrite: CountDownLatch? = null
        @Volatile
        var readStarted: CountDownLatch? = null
        @Volatile
        var releaseRead: CountDownLatch? = null
        @Volatile
        var deleteStarted: CountDownLatch? = null
        @Volatile
        var releaseDelete: CountDownLatch? = null
        val readLimits = mutableListOf<Int>()
        var writeCount = 0
            private set
        var deleteCount = 0
            private set

        override fun readRecord(maximumBytes: Int): ByteArray? {
            readStarted?.let { started ->
                started.countDown()
                if (releaseRead?.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) {
                    throw IOException("synthetic atomic read timed out")
                }
            }
            readLimits += maximumBytes
            nextReadFailure?.let { failure ->
                nextReadFailure = null
                throw failure
            }
            val current = record ?: return null
            return current.copyOf(minOf(current.size, maximumBytes))
        }

        override fun writeAtomically(record: ByteArray) {
            writeStarted?.countDown()
            if (releaseWrite?.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) {
                throw IOException("synthetic atomic write timed out")
            }
            nextWriteFailure?.let { failure ->
                nextWriteFailure = null
                throw failure
            }
            this.record = record.copyOf()
            writeCount += 1
        }

        override fun deleteRecord() {
            deleteCount += 1
            deleteStarted?.let { started ->
                started.countDown()
                if (releaseDelete?.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) == false) {
                    throw IOException("synthetic atomic delete timed out")
                }
            }
            nextDeleteFailure?.let { failure ->
                nextDeleteFailure = null
                throw failure
            }
            record = null
        }

        fun snapshot(): ByteArray = requireNotNull(record).copyOf()
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        val key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        var keyAvailable = true
        var existingKeyReads = 0
        var keyCreations = 0
        var existingKeyFailure: GeneralSecurityException? = null

        override fun existingKey(): SecretKey? {
            existingKeyReads += 1
            existingKeyFailure?.let { failure -> throw failure }
            return key.takeIf { keyAvailable }
        }

        override fun keyForNewRecord(): SecretKey {
            keyCreations += 1
            return key
        }
    }

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val OTHER_ORIGIN = "https://dev.example.test"
        const val SYNTHETIC_PAT = "synthetic-support-installation-token"
        const val SYNTHETIC_INVITATION_SECRET = "synthetic-invitation-secret-never-stored"
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val OTHER_INSTALLATION_ID = "33333333-3333-4333-8333-333333333333"
        const val MONITOR_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
        const val INVALID_UUID = "not-a-canonical-uuid"
        const val SUPPORT_ATTEMPT_KIND = 1
        const val SUPPORT_KIND_OFFSET = 8 + Int.SIZE_BYTES
        const val SUPPORT_VERSION_OFFSET = 8
        const val SUPPORT_ORIGIN_LENGTH_OFFSET = SUPPORT_KIND_OFFSET + 1
        const val SUPPORT_ORIGIN_OFFSET = SUPPORT_ORIGIN_LENGTH_OFFSET + Short.SIZE_BYTES
        const val BARRIER_TIMEOUT_SECONDS = 20L
        const val JOB_TIMEOUT_MILLIS = 25_000L
    }
}
