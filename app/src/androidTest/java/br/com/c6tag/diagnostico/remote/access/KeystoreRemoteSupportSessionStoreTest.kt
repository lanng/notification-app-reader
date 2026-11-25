package br.com.c6tag.diagnostico.remote.access

import android.content.Context
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreRemoteSupportSessionStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val suffix = UUID.randomUUID().toString()
    private val fileName = "g5-remote-support-session-$suffix.bin"
    private val keyAlias = "g5.remote-support-session.$suffix"
    private val recordFile = File(context.noBackupFilesDir, fileName)

    @After
    fun removeOnlySyntheticTestArtifacts() {
        listOf(recordFile, File("$recordFile.bak"), File("$recordFile.new")).forEach(File::delete)
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(keyAlias)) {
            keyStore.deleteEntry(keyAlias)
        }
    }

    @Test
    fun actualKeystoreAndAtomicFilePersistApprovedSupportSession() = runBlocking {
        val store = newStore()
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val key = withContext(Dispatchers.IO) {
            AndroidKeystoreRemoteHostKeyProvider(keyAlias).existingKey()
        }
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        assertFalse(recordFile.readBytes().containsBytes(SYNTHETIC_PAT))
        val approved = committed(
            store.commitApproved(pending, supportInstallation()),
        ).record as RemoteSupportSessionRecord.Approved

        val reloaded = (newStore().load(ORIGIN) as RemoteSupportSessionLoad.Approved).record

        assertNull(requireNotNull(key).encoded)
        assertTrue(recordFile.isFile)
        assertEquals(context.noBackupFilesDir, recordFile.parentFile)
        assertEquals(approved.attemptId, reloaded.attemptId)
        assertEquals(approved.requestId, reloaded.requestId)
        assertEquals(approved.installationId, reloaded.installationId)
        assertEquals(approved.monitorId, reloaded.monitorId)
        assertEquals(approved.membershipId, reloaded.membershipId)
        assertEquals(approved.observedGeneration, reloaded.observedGeneration)
        assertEquals(SYNTHETIC_PAT, reloaded.accessToken.reveal())
        assertFalse(reloaded.toString().contains(SYNTHETIC_PAT))
    }

    @Test
    fun originMismatchDoesNotExposeOrReplacePendingRecord() = runBlocking {
        val store = newStore()
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val savedBytes = recordFile.readBytes()

        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.ORIGIN_MISMATCH),
            newStore().load(OTHER_ORIGIN),
        )
        assertEquals(savedBytes.toList(), recordFile.readBytes().toList())
        val reloaded = (newStore().load(ORIGIN) as RemoteSupportSessionLoad.Pending).record
        assertEquals(pending.attemptId, reloaded.attemptId)
        assertEquals(pending.requestId, reloaded.requestId)
        assertEquals(pending.installationId, reloaded.installationId)
        assertEquals(pending.requestExpiresAtMillis, reloaded.requestExpiresAtMillis)
        assertEquals(pending.accessToken.reveal(), reloaded.accessToken.reveal())
    }

    @Test
    fun atomicFailWriteRestoresTheLastCompletedPendingRecord() = runBlocking {
        val store = newStore()
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        val pending = committed(
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        ).record as RemoteSupportSessionRecord.Pending
        val beforeInterruptedWrite = recordFile.readBytes()
        val atomicFile = AtomicFile(recordFile)
        val interruptedWrite = atomicFile.startWrite()
        interruptedWrite.write(byteArrayOf(0x01, 0x02, 0x03))
        interruptedWrite.fd.sync()
        atomicFile.failWrite(interruptedWrite)

        assertEquals(beforeInterruptedWrite.toList(), recordFile.readBytes().toList())
        val reloaded = (newStore().load(ORIGIN) as RemoteSupportSessionLoad.Pending).record
        assertEquals(pending.attemptId, reloaded.attemptId)
        assertEquals(pending.requestId, reloaded.requestId)
        assertEquals(pending.installationId, reloaded.installationId)
        assertEquals(pending.requestExpiresAtMillis, reloaded.requestExpiresAtMillis)
        assertEquals(pending.accessToken.reveal(), reloaded.accessToken.reveal())
    }

    @Test
    fun postFinishReadbackDivergenceRejectsPendingCommitAsPersistenceFailure() = runBlocking {
        val initialStore = newStore()
        val attempt = attemptStarted(initialStore.beginAttempt(ORIGIN)).record
        val initialAttemptBytes = recordFile.readBytes()
        val readCount = AtomicInteger()
        val divergentReads = object : RemoteHostAtomicReadOperations {
            override fun openRead(): InputStream =
                when (readCount.incrementAndGet()) {
                    1 -> ByteArrayInputStream(initialAttemptBytes)
                    2 -> ByteArrayInputStream(ByteArray(initialAttemptBytes.size) { 0x5a })
                    else -> throw AssertionError("Unexpected support-session read")
                }
        }
        val store = PersistentRemoteSupportSessionStore(
            file = AndroidAtomicRemoteHostFile(recordFile, divergentReads),
            keys = AndroidKeystoreRemoteHostKeyProvider(keyAlias),
        )

        assertEquals(
            RemoteSupportSessionWriteResult.Rejected(
                RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
            ),
            store.commitPending(ORIGIN, attempt.attemptId, candidate()),
        )
        assertEquals(2, readCount.get())
    }

    @Test
    fun missingKeystoreKeyDoesNotCreateReplacementOrExposeStoredCredential() = runBlocking {
        val store = newStore()
        val attempt = attemptStarted(store.beginAttempt(ORIGIN)).record
        committed(store.commitPending(ORIGIN, attempt.attemptId, candidate()))
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry(keyAlias)

        assertEquals(
            RemoteSupportSessionLoad.Unavailable(RemoteSupportSessionProblem.KEY_UNAVAILABLE),
            newStore().load(ORIGIN),
        )
        assertFalse(keyStore.containsAlias(keyAlias))
    }

    private fun newStore(): KeystoreRemoteSupportSessionStore =
        KeystoreRemoteSupportSessionStore(
            context = context,
            recordFileName = fileName,
            keyAlias = keyAlias,
        )

    private fun candidate(): PairingCandidateInstallation =
        PairingCandidateInstallation(
            origin = ORIGIN,
            requestId = REQUEST_ID,
            installationId = INSTALLATION_ID,
            requestExpiresAtMillis = 1_790_000_000_000L,
            accessToken = SecretValue.of(SYNTHETIC_PAT),
            status = "Pending",
        )

    private fun supportInstallation(): PairingSupportInstallation =
        PairingSupportInstallation(
            installationId = INSTALLATION_ID,
            monitorId = MONITOR_ID,
            membershipId = MEMBERSHIP_ID,
            role = "Support",
            status = "Active",
            monitorGeneration = requireNotNull(OwnerGeneration.parse("8")),
        )

    private fun attemptStarted(
        result: RemoteSupportSessionWriteResult,
    ): RemoteSupportSessionWriteResult.AttemptStarted =
        result as? RemoteSupportSessionWriteResult.AttemptStarted
            ?: throw AssertionError("Expected a durable attempt")

    private fun committed(
        result: RemoteSupportSessionWriteResult,
    ): RemoteSupportSessionWriteResult.Committed =
        result as? RemoteSupportSessionWriteResult.Committed
            ?: throw AssertionError("Expected a committed record")

    private fun ByteArray.containsBytes(value: String): Boolean =
        toString(Charsets.ISO_8859_1).contains(value)

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val OTHER_ORIGIN = "https://dev.example.test"
        const val SYNTHETIC_PAT = "synthetic-android-support-session-token"
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val MONITOR_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
    }
}
