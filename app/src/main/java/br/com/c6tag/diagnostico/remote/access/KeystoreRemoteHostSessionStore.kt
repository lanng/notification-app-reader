package br.com.c6tag.diagnostico.remote.access

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal class KeystoreRemoteHostSessionStore(
    context: Context,
    recordFileName: String = REMOTE_HOST_SESSION_FILE_NAME,
    keyAlias: String = REMOTE_HOST_SESSION_KEY_ALIAS,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteHostSessionStore by PersistentRemoteHostSessionStore(
    file = AndroidAtomicRemoteHostFile(
        remoteHostSessionFile(context.applicationContext.noBackupFilesDir, recordFileName),
    ),
    keys = AndroidKeystoreRemoteHostKeyProvider(keyAlias),
    ioDispatcher = ioDispatcher,
)

internal fun remoteHostSessionFile(noBackupDirectory: File, fileName: String): File {
    require(
        fileName.isNotBlank() &&
            fileName != "." &&
            fileName != ".." &&
            '/' !in fileName &&
            '\\' !in fileName,
    )
    return File(noBackupDirectory.absoluteFile, fileName)
}

internal interface RemoteHostAtomicReadOperations {
    @Throws(IOException::class)
    fun openRead(): InputStream

    fun isDefinitelyAbsent(file: File): Boolean =
        try {
            Files.notExists(file.toPath())
        } catch (_: SecurityException) {
            false
        }
}

internal class AndroidAtomicRemoteHostFile(
    private val file: File,
    readOperationsOverride: RemoteHostAtomicReadOperations? = null,
) : RemoteHostAtomicFile {
    private val atomicFile by lazy { AtomicFile(file) }
    private val readOperations = readOperationsOverride ?: object : RemoteHostAtomicReadOperations {
        override fun openRead(): InputStream = atomicFile.openRead()
    }

    init {
        require(file.name.isNotBlank() && file.name == file.path.substringAfterLast(File.separator))
    }

    override fun readRecord(maximumBytes: Int): ByteArray? {
        require(maximumBytes > 0)
        val input = try {
            readOperations.openRead()
        } catch (failure: FileNotFoundException) {
            if (recordFilesDefinitelyAbsent()) {
                return null
            }
            throw failure
        }

        return input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, READ_BUFFER_BYTES))
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (output.size() < maximumBytes) {
                val remaining = maximumBytes - output.size()
                val bytesRead = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (bytesRead < 0) {
                    break
                }
                if (bytesRead == 0) {
                    val singleByte = stream.read()
                    if (singleByte < 0) {
                        break
                    }
                    output.write(singleByte)
                } else {
                    output.write(buffer, 0, bytesRead)
                }
            }
            output.toByteArray()
        }
    }

    private fun recordFilesDefinitelyAbsent(): Boolean {
        var allDefinitelyAbsent = true
        listOf(file, File("${file.path}.bak"), File("${file.path}.new")).forEach { candidate ->
            if (!readOperations.isDefinitelyAbsent(candidate)) {
                allDefinitelyAbsent = false
            }
        }
        return allDefinitelyAbsent
    }

    override fun writeAtomically(record: ByteArray) {
        if (record.size > MAX_REMOTE_HOST_RECORD_BYTES) {
            throw IOException("Remote host session record is too large")
        }
        val output = atomicFile.startWrite()
        var finished = false
        var writeFailure: Throwable? = null
        try {
            output.write(record)
            output.fd.sync()
            atomicFile.finishWrite(output)
            val committedRecord = readRecord(record.size + 1)
            if (committedRecord == null || !committedRecord.contentEquals(record)) {
                throw IOException("Atomic remote host session write was not committed")
            }
            finished = true
        } catch (failure: Throwable) {
            writeFailure = failure
            throw failure
        } finally {
            if (!finished) {
                try {
                    atomicFile.failWrite(output)
                } catch (rollbackFailure: Throwable) {
                    val originalFailure = writeFailure
                    if (originalFailure == null) {
                        throw rollbackFailure
                    }
                    originalFailure.addSuppressed(rollbackFailure)
                }
            }
        }
    }

    override fun deleteRecord() {
        atomicFile.delete()
        if (readRecord(1) != null) {
            throw IOException("Atomic remote host session deletion was not committed")
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 4 * 1024
    }
}

internal class AndroidKeystoreRemoteHostKeyProvider(
    private val keyAlias: String = REMOTE_HOST_SESSION_KEY_ALIAS,
) : RemoteHostKeyProvider {
    override fun existingKey(): SecretKey? {
        val keyStore = loadKeyStore()
        if (!keyStore.containsAlias(keyAlias)) {
            return null
        }
        return try {
            keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw RemoteHostKeyUnavailableException()
        } catch (exception: UnrecoverableKeyException) {
            throw RemoteHostKeyUnavailableException(exception)
        }
    }

    override fun keyForNewRecord(): SecretKey =
        existingKey() ?: try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
        } catch (exception: GeneralSecurityException) {
            throw RemoteHostKeyUnavailableException(exception)
        }

    private fun loadKeyStore(): KeyStore =
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }
        } catch (exception: GeneralSecurityException) {
            throw RemoteHostKeyUnavailableException(exception)
        } catch (exception: IOException) {
            throw RemoteHostKeyUnavailableException(exception)
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

internal const val REMOTE_HOST_SESSION_FILE_NAME = "remote-host-session.bin"
internal const val REMOTE_HOST_SESSION_KEY_ALIAS = "br.com.tagalert.remote-host-session.v1"
