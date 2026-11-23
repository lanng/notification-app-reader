package br.com.c6tag.diagnostico.remote.access

import android.content.Context
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
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
class KeystoreRemoteHostSessionStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val fileName = "g5-remote-host-session-$suffix.bin"
    private val keyAlias = "g5.remote-host-session.$suffix"
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
    fun actualKeystoreKeyIsNonExportableAndAtomicFilePersistsSyntheticActivation() = runBlocking {
        val store = newStore()
        val attemptId = pendingAttempt(
            store.beginAttempt(CONTEXT, generation("7")),
        )
        val key = withContext(Dispatchers.IO) {
            AndroidKeystoreRemoteHostKeyProvider(keyAlias).existingKey()
        }
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("8", SYNTHETIC_PAT)),
        )

        val reloaded = newStore().load(CONTEXT)

        assertNull(requireNotNull(key).encoded)
        assertTrue(recordFile.isFile)
        assertTrue(recordFile.parentFile == context.noBackupFilesDir)
        assertTrue(reloaded is RemoteHostSessionLoad.ActiveSession)
        assertEquals(
            SYNTHETIC_PAT,
            (reloaded as RemoteHostSessionLoad.ActiveSession).activation.accessToken.reveal(),
        )
    }

    @Test
    fun atomicFailWriteRestoresLastCompletedRecordWithoutInventingPendingState() = runBlocking {
        val store = newStore()
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("8", SYNTHETIC_PAT)),
        )
        val atomicFile = AtomicFile(recordFile)
        val interruptedWrite = atomicFile.startWrite()
        interruptedWrite.write(byteArrayOf(0x01, 0x02, 0x03))
        interruptedWrite.fd.sync()
        atomicFile.failWrite(interruptedWrite)

        val reloaded = newStore().load(CONTEXT)

        assertTrue(reloaded is RemoteHostSessionLoad.ActiveSession)
        assertEquals(
            SYNTHETIC_PAT,
            (reloaded as RemoteHostSessionLoad.ActiveSession).activation.accessToken.reveal(),
        )
    }

    @Test
    fun missingKeystoreKeyDoesNotAutoCreateOrExposeAnOlderCredential() = runBlocking {
        val store = newStore()
        val attemptId = pendingAttempt(store.beginAttempt(CONTEXT, generation("7")))
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(CONTEXT, attemptId, activation("8", SYNTHETIC_PAT)),
        )
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry(keyAlias)

        val reloaded = newStore().load(CONTEXT)

        assertEquals(
            RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.KEY_UNAVAILABLE),
            reloaded,
        )
        assertFalse(keyStore.containsAlias(keyAlias))
    }

    private fun newStore(): KeystoreRemoteHostSessionStore =
        KeystoreRemoteHostSessionStore(
            context = context,
            recordFileName = fileName,
            keyAlias = keyAlias,
        )

    private fun activation(generation: String, token: String): HostActivation =
        HostActivation(
            monitorId = "synthetic-monitor",
            installationId = "synthetic-installation",
            membershipId = "synthetic-membership",
            generation = generation(generation),
            accessToken = SecretValue.of(token),
            createdAtMillis = 1_790_000_000_000L,
            owner = OWNER,
            origin = ORIGIN,
        )

    private fun pendingAttempt(
        result: RemoteHostSessionWriteResult,
    ): RemoteHostAttemptId {
        assertTrue(result is RemoteHostSessionWriteResult.PendingAttemptStarted)
        return (result as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId
    }

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val SYNTHETIC_PAT = "synthetic-android-keystore-pat"
        val OWNER = OwnerAccount("synthetic-firebase-project", "synthetic-firebase-uid", "Synthetic")
        val CONTEXT = RemoteHostOwnerContext(OWNER, ORIGIN)
    }
}
