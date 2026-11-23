package br.com.c6tag.diagnostico.remote.access

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHostSessionStoreTest {
    @Test
    fun sessionFileNameMustRemainDirectlyInsideNoBackupDirectory() {
        val noBackupDirectory = File("/app/no_backup")

        assertEquals(
            File(noBackupDirectory.absoluteFile, "remote-host-session.bin"),
            remoteHostSessionFile(noBackupDirectory, "remote-host-session.bin"),
        )
        listOf("../outside.bin", "nested/session.bin", "/outside.bin", ".", "..").forEach { fileName ->
            val failure = runCatching {
                remoteHostSessionFile(noBackupDirectory, fileName)
            }.exceptionOrNull()
            assertTrue("Expected unsafe file name to be rejected: $fileName", failure is IllegalArgumentException)
        }
    }

    @Test
    fun atomicReadReturnsEmptyOnlyWhenBaseBackupAndNewFilesAreDefinitelyAbsent() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            val operations = ControlledAtomicReadOperations(
                openRead = { throw FileNotFoundException("synthetic missing record") },
            )
            val adapter = AndroidAtomicRemoteHostFile(recordFile, operations)

            assertNull(adapter.readRecord(1024))
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
            operations.probedFiles.clear()
            val keys = JceTestKeyProvider()
            assertEquals(
                RemoteHostSessionLoad.Empty,
                PersistentRemoteHostSessionStore(adapter, keys).load(CONTEXT),
            )
            assertEquals(0, keys.keyCreations)
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
        }
    }

    @Test
    fun atomicReadPropagatesOpenFailureWhenBaseFileExists() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            Files.createFile(recordFile.toPath())
            val operations = ControlledAtomicReadOperations(
                openRead = { throw FileNotFoundException("synthetic unreadable base") },
            )

            val failure = runCatching {
                AndroidAtomicRemoteHostFile(recordFile, operations).readRecord(1024)
            }.exceptionOrNull()

            assertTrue("Expected open failure to remain an IOException", failure is FileNotFoundException)
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
        }
    }

    @Test
    fun atomicReadPropagatesOpenFailureForBackupNewFileOrIndeterminateAbsence() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            val backupFile = File("${recordFile.path}.bak")
            val newFile = File("${recordFile.path}.new")
            val cases = listOf(
                backupFile to emptyMap(),
                newFile to emptyMap(),
                null to mapOf(recordFile.path to false),
            )

            cases.forEach { (presentFile, indeterminateProbes) ->
                presentFile?.let { Files.createFile(it.toPath()) }
                val operations = ControlledAtomicReadOperations(
                    openRead = { throw FileNotFoundException("synthetic ambiguous record") },
                    absenceOverrides = indeterminateProbes,
                )
                val failure = runCatching {
                    AndroidAtomicRemoteHostFile(recordFile, operations).readRecord(1024)
                }.exceptionOrNull()

                assertTrue("Expected ambiguous open failure to propagate", failure is FileNotFoundException)
                assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
                operations.probedFiles.clear()
                val keys = JceTestKeyProvider()
                assertEquals(
                    RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                    PersistentRemoteHostSessionStore(
                        AndroidAtomicRemoteHostFile(recordFile, operations),
                        keys,
                    ).load(CONTEXT),
                )
                assertEquals(0, keys.keyCreations)
                assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
                presentFile?.let { Files.deleteIfExists(it.toPath()) }
            }
        }
    }

    @Test
    fun atomicReadAdapterPreservesEncryptedPendingAndActiveRecordBytes() = runBlocking {
        val sourceFile = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val sourceStore = PersistentRemoteHostSessionStore(sourceFile, keys)
        val attemptId = pendingAttempt(sourceStore.beginAttempt(CONTEXT, generation("7")))
        val pendingRecord = sourceFile.snapshot()
        val pendingAdapter = AndroidAtomicRemoteHostFile(
            File("g5-pending-record.bin"),
            ControlledAtomicReadOperations(
                openRead = { ByteArrayInputStream(pendingRecord) },
            ),
        )

        assertEquals(pendingRecord.toList(), pendingAdapter.readRecord(MAX_REMOTE_HOST_RECORD_BYTES)!!.toList())
        assertEquals(
            RemoteHostSessionLoad.PendingAttempt(attemptId, generation("7")),
            PersistentRemoteHostSessionStore(pendingAdapter, keys).load(CONTEXT),
        )

        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            sourceStore.commitAttempt(CONTEXT, attemptId, activation("8", OLD_TOKEN)),
        )
        val activeRecord = sourceFile.snapshot()
        val activeAdapter = AndroidAtomicRemoteHostFile(
            File("g5-active-record.bin"),
            ControlledAtomicReadOperations(
                openRead = { ByteArrayInputStream(activeRecord) },
            ),
        )

        assertEquals(activeRecord.toList(), activeAdapter.readRecord(MAX_REMOTE_HOST_RECORD_BYTES)!!.toList())
        val active = PersistentRemoteHostSessionStore(activeAdapter, keys)
            .load(CONTEXT) as RemoteHostSessionLoad.ActiveSession
        assertEquals(OLD_TOKEN, active.activation.accessToken.reveal())
    }

    @Test
    fun beginningAttemptPreservesSeededEncryptedActiveRecordWhenReadFails() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            val (_, seedFile, keys) = activeStoreWithKeys()
            val activeCiphertext = seedFile.snapshot()
            assertTrue(activeCiphertext.isNotEmpty())
            Files.write(recordFile.toPath(), activeCiphertext)
            val keyCreationsBeforeFailure = keys.keyCreations
            val operations = ControlledAtomicReadOperations(
                openRead = { throw FileNotFoundException("synthetic inaccessible active record") },
            )
            val observedFile = ObservedAtomicReadFile(
                AndroidAtomicRemoteHostFile(recordFile, operations),
            )
            val store = PersistentRemoteHostSessionStore(observedFile, keys)

            assertEquals(
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                store.beginAttempt(CONTEXT, generation("6")),
            )
            assertEquals(activeCiphertext.toList(), Files.readAllBytes(recordFile.toPath()).toList())
            assertFalse(
                Files.readAllBytes(recordFile.toPath())
                    .toString(Charsets.ISO_8859_1)
                    .contains(OLD_TOKEN),
            )
            assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
            assertEquals(0, observedFile.writeCount)
            assertEquals(0, observedFile.deleteCount)
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
            assertEquals(
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                store.load(CONTEXT),
            )
            assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
            assertEquals(activeCiphertext.toList(), Files.readAllBytes(recordFile.toPath()).toList())
        }
    }

    @Test
    fun committingAttemptPreservesSeededEncryptedPendingRecordWhenReadFails() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            val seedFile = MemoryAtomicRemoteHostFile()
            val keys = JceTestKeyProvider()
            val seedStore = PersistentRemoteHostSessionStore(seedFile, keys)
            val attemptId = pendingAttempt(seedStore.beginAttempt(CONTEXT, generation("7")))
            val pendingCiphertext = seedFile.snapshot()
            assertTrue(pendingCiphertext.isNotEmpty())
            Files.write(recordFile.toPath(), pendingCiphertext)
            val keyCreationsBeforeFailure = keys.keyCreations
            val operations = ControlledAtomicReadOperations(
                openRead = { throw FileNotFoundException("synthetic inaccessible pending record") },
            )
            val observedFile = ObservedAtomicReadFile(
                AndroidAtomicRemoteHostFile(recordFile, operations),
            )
            val store = PersistentRemoteHostSessionStore(observedFile, keys)

            assertEquals(
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                store.commitAttempt(CONTEXT, attemptId, activation("8", OLD_TOKEN)),
            )
            assertEquals(pendingCiphertext.toList(), Files.readAllBytes(recordFile.toPath()).toList())
            assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
            assertEquals(0, observedFile.writeCount)
            assertEquals(0, observedFile.deleteCount)
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
            assertEquals(
                RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                store.load(CONTEXT),
            )
            assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
            assertEquals(pendingCiphertext.toList(), Files.readAllBytes(recordFile.toPath()).toList())
        }
    }

    @Test
    fun unreadableExistingAtomicRecordCannotStartAttemptOrCreateKey() = runBlocking {
        withTemporaryAtomicRecordFile { recordFile ->
            Files.createFile(recordFile.toPath())
            val operations = ControlledAtomicReadOperations(
                openRead = { throw FileNotFoundException("synthetic inaccessible record") },
            )
            val observedFile = ObservedAtomicReadFile(
                AndroidAtomicRemoteHostFile(recordFile, operations),
            )
            val keys = JceTestKeyProvider()
            val store = PersistentRemoteHostSessionStore(observedFile, keys)

            assertEquals(
                RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
                store.beginAttempt(CONTEXT, generation("9")),
            )
            assertEquals(0, keys.keyCreations)
            assertEquals(0, observedFile.writeCount)
            assertEquals(0, observedFile.deleteCount)
            assertEquals(atomicRecordFiles(recordFile), operations.probedFiles)
        }
    }

    @Test
    fun durablePendingReplacementHidesOlderActiveSessionAfterReload() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)

        val initialAttempt = pendingAttempt(
            store.beginAttempt(CONTEXT, generation("4")),
        )
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(
                expectedContext = CONTEXT,
                attemptId = initialAttempt,
                activation = activation("5", OLD_TOKEN),
            ),
        )
        assertTrue(store.load(CONTEXT) is RemoteHostSessionLoad.ActiveSession)

        val replacementAttempt = pendingAttempt(
            store.beginAttempt(CONTEXT, generation("5")),
        )
        val reopenedStore = PersistentRemoteHostSessionStore(file, keys)

        val reloaded = reopenedStore.load(CONTEXT)

        assertEquals(
            RemoteHostSessionLoad.PendingAttempt(replacementAttempt, generation("5")),
            reloaded,
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.ATTEMPT_ALREADY_PENDING,
            ),
            reopenedStore.beginAttempt(CONTEXT, generation("6")),
        )
        assertEquals(
            RemoteHostSessionLoad.PendingAttempt(replacementAttempt, generation("5")),
            reopenedStore.load(CONTEXT),
        )
        assertFalse(reloaded.toString().contains(OLD_TOKEN))
    }

    @Test
    fun confirmedRecoveryReplacesMatchingPendingJournalAndMakesOldAttemptStale() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val initialAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("5")))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, initialAttemptId, activation("6", OLD_TOKEN)),
        )
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val previousPendingRecord = file.snapshot()

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.ATTEMPT_ALREADY_PENDING),
            store.beginAttempt(CONTEXT, generation("12")),
        )
        assertEquals(previousPendingRecord.toList(), file.snapshot().toList())

        val recoveryResult = store.beginRecoveryAttempt(
            expectedContext = CONTEXT,
            previousAttemptId = previousAttemptId,
            expectedGeneration = generation("12"),
        )
        assertTrue(
            "Expected recovery to durably start a new attempt, got $recoveryResult",
            recoveryResult is RemoteHostSessionWriteResult.PendingAttemptStarted,
        )
        val recoveryAttemptId =
            (recoveryResult as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId
        assertNotEquals(previousAttemptId, recoveryAttemptId)
        val replacementRecord = file.snapshot()
        val replacementPending = RemoteHostSessionLoad.PendingAttempt(
            recoveryAttemptId,
            generation("12"),
        )
        assertEquals(replacementPending, store.load(CONTEXT))
        assertFalse(store.load(CONTEXT).toString().contains(OLD_TOKEN))
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.commitAttempt(CONTEXT, previousAttemptId, activation("13", OLD_TOKEN)),
        )
        assertEquals(replacementRecord.toList(), file.snapshot().toList())
        assertEquals(replacementPending, store.load(CONTEXT))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, recoveryAttemptId, activation("13", OLD_TOKEN)),
        )
        assertTrue(store.load(CONTEXT) is RemoteHostSessionLoad.ActiveSession)
    }

    @Test
    fun recoveryRejectsWrongAttemptOwnerOriginActiveAndEmptyWithoutMutation() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val originalPending = file.snapshot()
        val writesBeforeRejections = file.writeCount
        val deletesBeforeRejections = file.deleteCount
        val keyCreationsBeforeRejections = keys.keyCreations
        val otherOwner = RemoteHostOwnerContext(
            OwnerAccount("other-project", "other-uid", "Other"),
            ORIGIN,
        )
        val otherOrigin = RemoteHostOwnerContext(OWNER, "https://other.example.test")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.beginRecoveryAttempt(
                CONTEXT,
                RemoteHostAttemptId.generated("00000000000000000000000000000001"),
                generation("12"),
            ),
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.OWNER_MISMATCH),
            store.beginRecoveryAttempt(otherOwner, previousAttemptId, generation("12")),
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.ORIGIN_MISMATCH),
            store.beginRecoveryAttempt(otherOrigin, previousAttemptId, generation("12")),
        )
        assertEquals(originalPending.toList(), file.snapshot().toList())
        assertEquals(writesBeforeRejections, file.writeCount)
        assertEquals(deletesBeforeRejections, file.deleteCount)
        assertEquals(keyCreationsBeforeRejections, keys.keyCreations)

        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, previousAttemptId, activation("8", OLD_TOKEN)),
        )
        val activeRecord = file.snapshot()
        val writesBeforeActiveRejection = file.writeCount
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12")),
        )
        assertEquals(activeRecord.toList(), file.snapshot().toList())
        assertEquals(writesBeforeActiveRejection, file.writeCount)
        assertEquals(deletesBeforeRejections, file.deleteCount)
        assertEquals(
            keyCreationsBeforeRejections,
            keys.keyCreations,
        )

        val emptyKeys = JceTestKeyProvider()
        val emptyFile = MemoryAtomicRemoteHostFile()
        val emptyStore = PersistentRemoteHostSessionStore(emptyFile, emptyKeys)
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            emptyStore.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12")),
        )
        assertNull(emptyFile.record)
        assertEquals(0, emptyFile.writeCount)
        assertEquals(0, emptyFile.deleteCount)
        assertEquals(0, emptyKeys.keyCreations)
    }

    @Test
    fun failedRecoveryWriteRetainsOriginalPendingCiphertextAndAttempt() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val previousCiphertext = file.snapshot()
        val writesBeforeRecovery = file.writeCount
        val deletesBeforeRecovery = file.deleteCount
        val keyCreationsBeforeRecovery = keys.keyCreations
        file.nextWriteFailure = IOException("synthetic recovery write failure")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12")),
        )
        assertEquals(previousCiphertext.toList(), file.snapshot().toList())
        assertEquals(writesBeforeRecovery + 1, file.writeCount)
        assertEquals(deletesBeforeRecovery, file.deleteCount)
        assertEquals(keyCreationsBeforeRecovery, keys.keyCreations)
        assertEquals(
            RemoteHostSessionLoad.PendingAttempt(previousAttemptId, generation("7")),
            store.load(CONTEXT),
        )
    }

    @Test
    fun concurrentRecoveryForSameAttemptAllowsOnlyOneReplacement() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))

        val results = listOf("12", "13").map { expectedGeneration ->
            async(Dispatchers.Default) {
                store.beginRecoveryAttempt(
                    CONTEXT,
                    previousAttemptId,
                    generation(expectedGeneration),
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it is RemoteHostSessionWriteResult.PendingAttemptStarted })
        assertEquals(
            1,
            results.count {
                it == RemoteHostSessionWriteResult.Rejected(
                    RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
                )
            },
        )
        val winner = results.single {
            it is RemoteHostSessionWriteResult.PendingAttemptStarted
        } as RemoteHostSessionWriteResult.PendingAttemptStarted
        assertNotEquals(previousAttemptId, winner.attemptId)
        val persisted = store.load(CONTEXT) as RemoteHostSessionLoad.PendingAttempt
        assertEquals(winner.attemptId, persisted.attemptId)
        assertTrue(persisted.expectedGeneration == generation("12") ||
            persisted.expectedGeneration == generation("13"))
    }

    @Test
    fun cancellationWaitsForRecoveryWriteSettlementAndDoesNotReturnSuccess() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        file.writeStarted = writeStarted
        file.releaseWrite = releaseWrite

        val cancelledRecovery = async(Dispatchers.Default) {
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12"))
        }
        assertTrue(withContext(Dispatchers.IO) { writeStarted.await(5, TimeUnit.SECONDS) })
        cancelledRecovery.cancel()
        releaseWrite.countDown()
        try {
            cancelledRecovery.await()
            throw AssertionError("Expected cancellation instead of a recovery result")
        } catch (_: CancellationException) {
            // The atomic write settles before cancellation reaches the caller.
        }

        val persisted = store.load(CONTEXT) as RemoteHostSessionLoad.PendingAttempt
        assertNotEquals(previousAttemptId, persisted.attemptId)
        assertEquals(generation("12"), persisted.expectedGeneration)
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("13")),
        )
    }

    @Test
    fun unrecoverableExistingKeyRejectsRecoveryWithoutChangingPendingRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val original = file.snapshot()
        val writesBeforeFailure = file.writeCount
        val deletesBeforeFailure = file.deleteCount
        val keyCreationsBeforeFailure = keys.keyCreations
        keys.existingKeyFailure = UnrecoverableKeyException("synthetic invalidated keystore key")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12")),
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            store.commitAttempt(CONTEXT, previousAttemptId, activation("13", OLD_TOKEN)),
        )
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.KEY_UNAVAILABLE)
        assertEquals(original.toList(), file.snapshot().toList())
        assertEquals(writesBeforeFailure, file.writeCount)
        assertEquals(deletesBeforeFailure, file.deleteCount)
        assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
    }

    @Test
    fun invalidJceAesKeyRejectsRecoveryWithoutChangingPendingRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val previousAttemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        val original = file.snapshot()
        val writesBeforeFailure = file.writeCount
        val deletesBeforeFailure = file.deleteCount
        val keyCreationsBeforeFailure = keys.keyCreations
        keys.key = SecretKeySpec(ByteArray(15) { it.toByte() }, "AES")

        val cipherFailure = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, keys.key)
        }.exceptionOrNull()
        assertTrue("Expected real JCE Cipher.init to reject the AES key", cipherFailure is InvalidKeyException)
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            store.beginRecoveryAttempt(CONTEXT, previousAttemptId, generation("12")),
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            store.commitAttempt(CONTEXT, previousAttemptId, activation("13", OLD_TOKEN)),
        )
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.KEY_UNAVAILABLE)
        assertEquals(original.toList(), file.snapshot().toList())
        assertEquals(writesBeforeFailure, file.writeCount)
        assertEquals(deletesBeforeFailure, file.deleteCount)
        assertEquals(keyCreationsBeforeFailure, keys.keyCreations)
    }

    @Test
    fun encryptedPayloadUsesFreshNonceAndContainsNoPlaintextInstallationToken() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("4")))
        val pendingEnvelope = file.snapshot()

        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("5", OLD_TOKEN)),
        )
        val activeEnvelope = file.snapshot()
        val originLength = CONTEXT.origin.toByteArray(Charsets.US_ASCII).size
        val nonceOffset = 8 + Int.SIZE_BYTES + Short.SIZE_BYTES + originLength + 1 + Int.SIZE_BYTES
        val nonceStart = pendingEnvelope.copyOfRange(nonceOffset, nonceOffset + REMOTE_HOST_GCM_NONCE_BYTES)
        val nonceEnd = activeEnvelope.copyOfRange(nonceOffset, nonceOffset + REMOTE_HOST_GCM_NONCE_BYTES)

        assertNotEquals(nonceStart.toList(), nonceEnd.toList())
        assertFalse(activeEnvelope.toString(Charsets.ISO_8859_1).contains(OLD_TOKEN))
        val active = store.load(CONTEXT) as RemoteHostSessionLoad.ActiveSession
        assertEquals("monitor-opaque", active.activation.monitorId)
        assertEquals("installation-opaque", active.activation.installationId)
        assertEquals("membership-opaque", active.activation.membershipId)
        assertEquals("5", active.activation.generation.decimal)
        assertEquals(1_790_000_000_000L, active.activation.createdAtMillis)
        assertEquals(OLD_TOKEN, active.activation.accessToken.reveal())
        assertFalse(active.toString().contains(OLD_TOKEN))
    }

    @Test
    fun badTagModifiedOriginTruncationOversizeAndUnknownVersionAreUnavailable() = runBlocking {
        val (store, file) = activeStore()
        val original = file.snapshot()

        file.record = original.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 0x01).toByte()
        }
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.AUTHENTICATION_FAILED)

        val originOffset = 8 + Int.SIZE_BYTES + Short.SIZE_BYTES
        file.record = original.copyOf().apply {
            "dev".toByteArray(Charsets.US_ASCII).copyInto(this, originOffset + "https://".length)
        }
        val alternateOrigin = RemoteHostOwnerContext(OWNER, "https://dev.example.test")
        assertUnavailable(store.load(alternateOrigin), RemoteHostSessionProblem.AUTHENTICATION_FAILED)

        file.record = original.copyOf(original.size - 1)
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.CORRUPT_RECORD)

        file.record = ByteArray(MAX_REMOTE_HOST_RECORD_BYTES + 1)
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.CORRUPT_RECORD)

        file.record = original.copyOf().apply {
            this[11] = 2
        }
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.UNSUPPORTED_VERSION)
    }

    @Test
    fun accountAndOriginMismatchesNeverExposeOrOverwriteExistingSession() = runBlocking {
        val (store, file) = activeStore()
        val original = file.snapshot()
        val otherOwner = RemoteHostOwnerContext(
            OwnerAccount("different-firebase-project", "different-uid", "Other"),
            ORIGIN,
        )
        val otherOrigin = RemoteHostOwnerContext(OWNER, "https://other.example.test")

        assertUnavailable(store.load(otherOwner), RemoteHostSessionProblem.OWNER_MISMATCH)
        assertUnavailable(store.load(otherOrigin), RemoteHostSessionProblem.ORIGIN_MISMATCH)
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.OWNER_MISMATCH),
            store.beginAttempt(otherOwner, generation("9")),
        )
        assertEquals(original.toList(), file.snapshot().toList())
        assertFalse(store.load(otherOwner).toString().contains(OLD_TOKEN))
    }

    @Test
    fun missingKeyDoesNotAutoCreateAReplacementKeyOrExposeOldSession() = runBlocking {
        val (store, file, keys) = activeStoreWithKeys()
        val original = file.snapshot()
        val keyCreationsBeforeLoss = keys.keyCreations
        keys.keyAvailable = false

        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.KEY_UNAVAILABLE)
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            store.beginAttempt(CONTEXT, generation("9")),
        )
        assertEquals(keyCreationsBeforeLoss, keys.keyCreations)
        assertEquals(original.toList(), file.snapshot().toList())
    }

    @Test
    fun activeCommitRequiresMatchingDurableAttemptButAllowsReturnedNextGeneration() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("40")))

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.commitAttempt(
                CONTEXT,
                RemoteHostAttemptId.generated("00000000000000000000000000000001"),
                activation("41", OLD_TOKEN),
            ),
        )
        assertEquals(RemoteHostSessionLoad.PendingAttempt(attemptId, generation("40")), store.load(CONTEXT))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("41", OLD_TOKEN)),
        )
        val active = store.load(CONTEXT) as RemoteHostSessionLoad.ActiveSession
        assertEquals("41", active.activation.generation.decimal)
    }

    @Test
    fun beginAttemptReturnsUnsupportedVersionWithoutReplacingUnreadableRecord() = runBlocking {
        val (store, file, keys) = activeStoreWithKeys()
        file.record = unsupportedVersion(file.snapshot())
        val unsupportedRecord = file.snapshot()
        val keyCreationsBeforeAttempt = keys.keyCreations
        val writesBeforeAttempt = file.writeCount
        val deletesBeforeAttempt = file.deleteCount

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION),
            store.beginAttempt(CONTEXT, generation("6")),
        )
        assertEquals(unsupportedRecord.toList(), file.snapshot().toList())
        assertEquals(writesBeforeAttempt, file.writeCount)
        assertEquals(deletesBeforeAttempt, file.deleteCount)
        assertEquals(keyCreationsBeforeAttempt, keys.keyCreations)
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.UNSUPPORTED_VERSION)
    }

    @Test
    fun commitAttemptReturnsUnsupportedVersionAndPreservesDurablePendingRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        file.record = unsupportedVersion(file.snapshot())
        val unsupportedRecord = file.snapshot()
        val keyCreationsBeforeCommit = keys.keyCreations
        val writesBeforeCommit = file.writeCount
        val deletesBeforeCommit = file.deleteCount

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.UNSUPPORTED_VERSION),
            store.commitAttempt(CONTEXT, attemptId, activation("8", OLD_TOKEN)),
        )
        assertEquals(unsupportedRecord.toList(), file.snapshot().toList())
        assertEquals(writesBeforeCommit, file.writeCount)
        assertEquals(deletesBeforeCommit, file.deleteCount)
        assertEquals(keyCreationsBeforeCommit, keys.keyCreations)
        assertUnavailable(store.load(CONTEXT), RemoteHostSessionProblem.UNSUPPORTED_VERSION)
    }

    @Test
    fun commitRejectsDifferentOwnerOrOriginAndKeepsPendingAttempt() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("4")))
        val otherOwner = OwnerAccount("other-project", "other-uid", "Other")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.OWNER_MISMATCH),
            store.commitAttempt(
                CONTEXT,
                attemptId,
                activation("5", OLD_TOKEN, owner = otherOwner),
            ),
        )
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.ORIGIN_MISMATCH),
            store.commitAttempt(
                CONTEXT,
                attemptId,
                activation("5", OLD_TOKEN, origin = "https://other.example.test"),
            ),
        )
        val reloaded = store.load(CONTEXT)
        assertEquals(RemoteHostSessionLoad.PendingAttempt(attemptId, generation("4")), reloaded)
        assertFalse(reloaded.toString().contains(OLD_TOKEN))
    }

    @Test
    fun explicitKnownNoWriteClearsOnlyTheMatchingPendingAttempt() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("4")))
        val unrelatedAttempt = RemoteHostAttemptId.generated("00000000000000000000000000000001")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.NO_MATCHING_PENDING_ATTEMPT,
            ),
            store.clearPendingAttempt(CONTEXT, unrelatedAttempt),
        )
        assertEquals(RemoteHostSessionLoad.PendingAttempt(attemptId, generation("4")), store.load(CONTEXT))
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.OWNER_MISMATCH),
            store.clearPendingAttempt(
                RemoteHostOwnerContext(
                    OwnerAccount("other-project", "other-uid", "Other"),
                    ORIGIN,
                ),
                attemptId,
            ),
        )
        assertEquals(
            RemoteHostSessionWriteResult.PendingAttemptCleared,
            store.clearPendingAttempt(CONTEXT, attemptId),
        )
        assertEquals(RemoteHostSessionLoad.Empty, PersistentRemoteHostSessionStore(file, keys).load(CONTEXT))
        assertEquals(1, keys.keyCreations)
    }

    @Test
    fun explicitSignOutClearsOnlyTheExactActiveHostContextAndKeepsKey() = runBlocking {
        val (store, file, keys) = activeStoreWithKeys()
        val activation = (store.load(CONTEXT) as RemoteHostSessionLoad.ActiveSession).activation
        val previousRecord = file.snapshot()

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.SESSION_CONTEXT_MISMATCH,
            ),
            store.clearActiveSession(
                activation.expectedHostContext().copy(installationId = "other-installation"),
            ),
        )
        assertEquals(previousRecord.toList(), file.snapshot().toList())
        assertTrue(store.load(CONTEXT) is RemoteHostSessionLoad.ActiveSession)

        file.nextDeleteFailure = IOException("synthetic session clear failure")
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
            store.clearActiveSession(activation.expectedHostContext()),
        )
        assertTrue(store.load(CONTEXT) is RemoteHostSessionLoad.ActiveSession)
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCleared,
            store.clearActiveSession(activation.expectedHostContext()),
        )
        assertEquals(RemoteHostSessionLoad.Empty, store.load(CONTEXT))
        assertEquals(1, keys.keyCreations)
        assertTrue(keys.keyAvailable)
    }

    @Test
    fun failedAtomicReplacementRestoresOldActiveOrDurablePendingState() = runBlocking {
        val (activeStore, file) = activeStore()
        val previousActive = file.snapshot()
        file.nextWriteFailure = IOException("synthetic pending write failure")

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
            activeStore.beginAttempt(CONTEXT, generation("5")),
        )
        assertEquals(previousActive.toList(), file.snapshot().toList())
        assertTrue(activeStore.load(CONTEXT) is RemoteHostSessionLoad.ActiveSession)

        val pendingAttempt = pendingAttempt(activeStore.beginAttempt(CONTEXT, generation("5")))
        val durablePending = file.snapshot()
        file.nextWriteFailure = IOException("synthetic activation write failure")
        assertEquals(
            RemoteHostSessionWriteResult.Rejected(RemoteHostSessionProblem.PERSISTENCE_FAILURE),
            activeStore.commitAttempt(CONTEXT, pendingAttempt, activation("6", OLD_TOKEN)),
        )
        assertEquals(durablePending.toList(), file.snapshot().toList())
        val reloaded = activeStore.load(CONTEXT)
        assertEquals(
            RemoteHostSessionLoad.PendingAttempt(pendingAttempt, generation("5")),
            reloaded,
        )
        assertFalse(reloaded.toString().contains(OLD_TOKEN))
    }

    @Test
    fun concurrentAttemptWritersSerializeAgainstTheSameDurableRecord() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())

        val results = (0 until 12).map {
            async(Dispatchers.Default) {
                store.beginAttempt(CONTEXT, generation("12"))
            }
        }.awaitAll()

        assertEquals(1, results.count { it is RemoteHostSessionWriteResult.PendingAttemptStarted })
        assertEquals(
            11,
            results.count {
                it == RemoteHostSessionWriteResult.Rejected(
                    RemoteHostSessionProblem.ATTEMPT_ALREADY_PENDING,
                )
            },
        )
        val persisted = store.load(CONTEXT) as RemoteHostSessionLoad.PendingAttempt
        assertEquals(
            (results.single { it is RemoteHostSessionWriteResult.PendingAttemptStarted }
                as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId,
            persisted.attemptId,
        )
    }

    @Test
    fun cancellationWaitsForAtomicWriteSettlementBeforeAnotherMutation() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteHostSessionStore(file, JceTestKeyProvider())
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        file.writeStarted = writeStarted
        file.releaseWrite = releaseWrite

        val cancelledWrite = async(Dispatchers.Default) {
            store.beginAttempt(CONTEXT, generation("15"))
        }
        assertTrue(withContext(Dispatchers.IO) { writeStarted.await(5, TimeUnit.SECONDS) })
        cancelledWrite.cancel()
        releaseWrite.countDown()
        try {
            cancelledWrite.await()
        } catch (_: CancellationException) {
            // The durable operation settles before the caller observes cancellation.
        }

        assertEquals(
            RemoteHostSessionWriteResult.Rejected(
                RemoteHostSessionProblem.ATTEMPT_ALREADY_PENDING,
            ),
            store.beginAttempt(CONTEXT, generation("16")),
        )
        assertTrue(store.load(CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
    }

    private fun pendingAttempt(
        result: RemoteHostSessionWriteResult,
    ): RemoteHostAttemptId {
        assertTrue(result is RemoteHostSessionWriteResult.PendingAttemptStarted)
        return (result as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId
    }

    private fun activation(
        generation: String,
        token: String,
        owner: OwnerAccount = OWNER,
        origin: String = ORIGIN,
    ): HostActivation =
        HostActivation(
            monitorId = "monitor-opaque",
            installationId = "installation-opaque",
            membershipId = "membership-opaque",
            generation = generation(generation),
            accessToken = SecretValue.of(token),
            createdAtMillis = 1_790_000_000_000L,
            owner = owner,
            origin = origin,
        )

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private fun unsupportedVersion(record: ByteArray): ByteArray =
        record.copyOf().apply { this[11] = 2 }

    private fun atomicRecordFiles(recordFile: File): List<File> =
        listOf(recordFile, File("${recordFile.path}.bak"), File("${recordFile.path}.new"))

    private suspend fun <T> withTemporaryAtomicRecordFile(block: suspend (File) -> T): T {
        val directory = Files.createTempDirectory("g5-atomic-read-test").toFile()
        val recordFile = File(directory, "session.bin")
        return try {
            block(recordFile)
        } finally {
            atomicRecordFiles(recordFile).forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(directory.toPath())
        }
    }

    private suspend fun activeStore(): Pair<PersistentRemoteHostSessionStore, MemoryAtomicRemoteHostFile> {
        val (store, file, _) = activeStoreWithKeys()
        return store to file
    }

    private suspend fun activeStoreWithKeys(): Triple<
        PersistentRemoteHostSessionStore,
        MemoryAtomicRemoteHostFile,
        JceTestKeyProvider,
    > {
        val file = MemoryAtomicRemoteHostFile()
        val keys = JceTestKeyProvider()
        val store = PersistentRemoteHostSessionStore(file, keys)
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("4")))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("5", OLD_TOKEN)),
        )
        return Triple(store, file, keys)
    }

    private fun assertUnavailable(
        result: RemoteHostSessionLoad,
        problem: RemoteHostSessionProblem,
    ) {
        assertEquals(RemoteHostSessionLoad.Unavailable(problem), result)
    }

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var record: ByteArray? = null
        @Volatile
        var nextWriteFailure: IOException? = null
        @Volatile
        var nextDeleteFailure: IOException? = null
        @Volatile
        var writeStarted: CountDownLatch? = null
        @Volatile
        var releaseWrite: CountDownLatch? = null
        var writeCount = 0
            private set
        var deleteCount = 0
            private set

        override fun readRecord(maximumBytes: Int): ByteArray? {
            require(maximumBytes > 0)
            val current = record ?: return null
            return current.copyOf()
        }

        override fun writeAtomically(record: ByteArray) {
            writeCount += 1
            writeStarted?.countDown()
            releaseWrite?.await()
            nextWriteFailure?.let { failure ->
                nextWriteFailure = null
                throw failure
            }
            this.record = record.copyOf()
        }

        override fun deleteRecord() {
            deleteCount += 1
            nextDeleteFailure?.let { failure ->
                nextDeleteFailure = null
                throw failure
            }
            record = null
        }

        fun snapshot(): ByteArray = requireNotNull(record).copyOf()
    }

    private class ControlledAtomicReadOperations(
        private val openRead: () -> InputStream,
        private val absenceOverrides: Map<String, Boolean> = emptyMap(),
    ) : RemoteHostAtomicReadOperations {
        val probedFiles = mutableListOf<File>()

        override fun openRead(): InputStream = openRead.invoke()

        override fun isDefinitelyAbsent(file: File): Boolean {
            probedFiles += file
            return absenceOverrides[file.path]
                ?: super<RemoteHostAtomicReadOperations>.isDefinitelyAbsent(file)
        }
    }

    private class ObservedAtomicReadFile(
        private val readDelegate: AndroidAtomicRemoteHostFile,
    ) : RemoteHostAtomicFile {
        var writeCount = 0
            private set
        var deleteCount = 0
            private set

        override fun readRecord(maximumBytes: Int): ByteArray? =
            readDelegate.readRecord(maximumBytes)

        override fun writeAtomically(record: ByteArray) {
            writeCount += 1
        }

        override fun deleteRecord() {
            deleteCount += 1
        }
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        @Volatile
        var key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        @Volatile
        var keyAvailable = true
        @Volatile
        var existingKeyFailure: GeneralSecurityException? = null
        var keyCreations = 0

        override fun existingKey(): SecretKey? {
            existingKeyFailure?.let { failure -> throw failure }
            return key.takeIf { keyAvailable }
        }

        override fun keyForNewRecord(): SecretKey {
            keyCreations += 1
            if (!keyAvailable) {
                throw RemoteHostKeyUnavailableException()
            }
            return key
        }
    }

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val OLD_TOKEN = "synthetic-old-installation-token"
        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val CONTEXT = RemoteHostOwnerContext(OWNER, ORIGIN)
    }
}
