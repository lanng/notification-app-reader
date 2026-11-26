package br.com.c6tag.diagnostico.remote.access

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal class KeystoreRemoteSupportSessionStore(
    context: Context,
    recordFileName: String = REMOTE_SUPPORT_SESSION_FILE_NAME,
    keyAlias: String = REMOTE_SUPPORT_SESSION_KEY_ALIAS,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteSupportSessionStore by PersistentRemoteSupportSessionStore(
    file = AndroidAtomicRemoteHostFile(
        remoteHostSessionFile(context.applicationContext.noBackupFilesDir, recordFileName),
    ),
    keys = AndroidKeystoreRemoteHostKeyProvider(keyAlias),
    ioDispatcher = ioDispatcher,
)

internal const val REMOTE_SUPPORT_SESSION_FILE_NAME = "remote-support-session.bin"
internal const val REMOTE_SUPPORT_SESSION_KEY_ALIAS =
    "br.com.tagalert.remote-support-session.v1"
