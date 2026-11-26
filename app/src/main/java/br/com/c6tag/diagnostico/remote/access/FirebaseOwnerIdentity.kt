package br.com.c6tag.diagnostico.remote.access

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class FirebaseOperationOutcome {
    SUCCEEDED,
    NO_CURRENT_USER,
    ACCOUNT_MISMATCH,
    UNAVAILABLE,
    FAILED,
}

internal interface FirebaseSession {
    val account: StateFlow<OwnerAccount?>

    suspend fun signInWithGoogleCredential(googleIdToken: SecretValue): FirebaseOperationOutcome

    suspend fun reauthenticate(
        expectedUid: String,
        googleIdToken: SecretValue,
    ): FirebaseOperationOutcome

    suspend fun firebaseIdToken(): SecretValue?

    suspend fun signOut(): Boolean
}

internal fun interface CredentialStateCleaner {
    suspend fun clear(): Boolean
}

internal class FirebaseOwnerIdentity(
    private val session: FirebaseSession,
    private val credentialStateCleaner: CredentialStateCleaner,
) : OwnerIdentity {
    internal constructor(context: Context) : this(
        session = FirebaseSdkSession(FirebaseAuth.getInstance()),
        credentialStateCleaner = AndroidCredentialStateCleaner(context.applicationContext),
    )

    override val account: StateFlow<OwnerAccount?> = session.account
    private val mutationMutex = Mutex()

    override suspend fun signIn(googleIdToken: SecretValue): IdentityResult {
        var originalUid: String? = null
        return serializeMutation(
            onCancellation = {
                if (session.account.value?.uid != originalUid) {
                    session.signOut()
                    credentialStateCleaner.clear()
                }
            },
        ) {
            originalUid = session.account.value?.uid
            when (session.signInWithGoogleCredential(googleIdToken)) {
                FirebaseOperationOutcome.SUCCEEDED -> {
                    if (session.account.value == null) {
                        IdentityResult(IdentityOutcome.UNAVAILABLE)
                    } else {
                        IdentityResult(IdentityOutcome.SIGNED_IN_CANDIDATE)
                    }
                }

                FirebaseOperationOutcome.NO_CURRENT_USER ->
                    IdentityResult(IdentityOutcome.NOT_SIGNED_IN)

                FirebaseOperationOutcome.ACCOUNT_MISMATCH ->
                    IdentityResult(IdentityOutcome.ACCOUNT_MISMATCH)

                FirebaseOperationOutcome.UNAVAILABLE ->
                    IdentityResult(IdentityOutcome.UNAVAILABLE)

                FirebaseOperationOutcome.FAILED ->
                    IdentityResult(IdentityOutcome.FAILED)
            }
        }
    }

    override suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult =
        serializeMutation {
            val currentAccount = session.account.value
                ?: return@serializeMutation IdentityResult(IdentityOutcome.NOT_SIGNED_IN)

            when (
                session.reauthenticate(
                    expectedUid = currentAccount.uid,
                    googleIdToken = googleIdToken,
                )
            ) {
                FirebaseOperationOutcome.SUCCEEDED -> {
                    if (session.account.value?.uid == currentAccount.uid) {
                        IdentityResult(IdentityOutcome.REAUTHENTICATED)
                    } else {
                        IdentityResult(IdentityOutcome.ACCOUNT_MISMATCH)
                    }
                }

                FirebaseOperationOutcome.NO_CURRENT_USER ->
                    IdentityResult(IdentityOutcome.NOT_SIGNED_IN)

                FirebaseOperationOutcome.ACCOUNT_MISMATCH ->
                    IdentityResult(IdentityOutcome.ACCOUNT_MISMATCH)

                FirebaseOperationOutcome.UNAVAILABLE ->
                    IdentityResult(IdentityOutcome.UNAVAILABLE)

                FirebaseOperationOutcome.FAILED ->
                    IdentityResult(IdentityOutcome.FAILED)
            }
        }

    override suspend fun firebaseIdToken(): SecretValue {
        mutationMutex.lock()
        try {
            if (session.account.value == null) {
                throw IdentityOperationException(IdentityFailure.NOT_SIGNED_IN)
            }
            return session.firebaseIdToken()
                ?: throw IdentityOperationException(IdentityFailure.FIREBASE_TOKEN_UNAVAILABLE)
        } finally {
            mutationMutex.unlock()
        }
    }

    override suspend fun signOut(): IdentityResult =
        serializeMutation {
            val firebaseSignedOut = session.signOut()
            val credentialsCleared = credentialStateCleaner.clear()
            if (firebaseSignedOut && credentialsCleared && session.account.value == null) {
                IdentityResult(IdentityOutcome.SIGNED_OUT)
            } else {
                IdentityResult(IdentityOutcome.FAILED)
            }
        }

    private suspend fun <T> serializeMutation(
        onCancellation: suspend () -> Unit = {},
        operation: suspend () -> T,
    ): T {
        mutationMutex.lock()
        try {
            val callerJob = currentCoroutineContext()[Job]
                ?: error("Identity operation requires a coroutine job")
            return withContext(NonCancellable) {
                callerJob.ensureActive()
                val result = operation()
                if (!callerJob.isActive) {
                    onCancellation()
                    callerJob.ensureActive()
                }
                result
            }
        } finally {
            mutationMutex.unlock()
        }
    }
}

private class FirebaseSdkSession(
    private val auth: FirebaseAuth,
) : FirebaseSession {
    private val mutableAccount = MutableStateFlow(auth.currentUser.toOwnerAccount())

    override val account: StateFlow<OwnerAccount?> = mutableAccount

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        mutableAccount.value = firebaseAuth.currentUser.toOwnerAccount()
    }

    init {
        auth.addAuthStateListener(authStateListener)
    }

    override suspend fun signInWithGoogleCredential(
        googleIdToken: SecretValue,
    ): FirebaseOperationOutcome {
        return try {
            val credential = GoogleAuthProvider.getCredential(googleIdToken.reveal(), null)
            auth.signInWithCredential(credential).awaitResult()
            publishCurrentAccount()
            if (mutableAccount.value == null) {
                FirebaseOperationOutcome.UNAVAILABLE
            } else {
                FirebaseOperationOutcome.SUCCEEDED
            }
        } catch (_: FirebaseException) {
            FirebaseOperationOutcome.FAILED
        }
    }

    override suspend fun reauthenticate(
        expectedUid: String,
        googleIdToken: SecretValue,
    ): FirebaseOperationOutcome {
        val currentUser = auth.currentUser ?: return FirebaseOperationOutcome.NO_CURRENT_USER
        if (currentUser.uid != expectedUid) {
            return FirebaseOperationOutcome.ACCOUNT_MISMATCH
        }

        return try {
            val credential = GoogleAuthProvider.getCredential(googleIdToken.reveal(), null)
            currentUser.reauthenticate(credential).awaitResult()
            publishCurrentAccount()
            if (auth.currentUser?.uid == expectedUid) {
                FirebaseOperationOutcome.SUCCEEDED
            } else {
                FirebaseOperationOutcome.ACCOUNT_MISMATCH
            }
        } catch (exception: FirebaseAuthException) {
            if (exception.errorCode == "ERROR_USER_MISMATCH") {
                FirebaseOperationOutcome.ACCOUNT_MISMATCH
            } else {
                FirebaseOperationOutcome.FAILED
            }
        } catch (_: FirebaseException) {
            FirebaseOperationOutcome.FAILED
        }
    }

    override suspend fun firebaseIdToken(): SecretValue? {
        val currentUser = auth.currentUser ?: return null
        return try {
            val token = currentUser.getIdToken(false).awaitResult().token
                ?.takeIf(String::isNotBlank)
                ?: return null
            if (auth.currentUser?.uid != currentUser.uid) {
                null
            } else {
                SecretValue.of(token)
            }
        } catch (_: FirebaseException) {
            null
        }
    }

    override suspend fun signOut(): Boolean {
        auth.signOut()
        mutableAccount.value = null
        return auth.currentUser == null
    }

    private fun publishCurrentAccount() {
        mutableAccount.value = auth.currentUser.toOwnerAccount()
    }

    private fun FirebaseUser?.toOwnerAccount(): OwnerAccount? {
        val user = this ?: return null
        val projectId = auth.app.options.projectId
            ?.takeIf(String::isNotBlank)
            ?: return null
        return OwnerAccount(
            firebaseProjectId = projectId,
            uid = user.uid,
            label = user.displayName?.takeIf(String::isNotBlank),
        )
    }
}

private class AndroidCredentialStateCleaner(
    context: Context,
) : CredentialStateCleaner {
    private val credentialManager = CredentialManager.create(context.applicationContext)

    override suspend fun clear(): Boolean =
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
            true
        } catch (_: ClearCredentialException) {
            false
        }
}

private suspend fun <T> Task<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { completedTask ->
            if (completedTask.isSuccessful) {
                continuation.resume(completedTask.result)
            } else {
                val exception = completedTask.exception
                    ?: IllegalStateException("Firebase task failed without an exception")
                continuation.resumeWithException(exception)
            }
        }
    }
