package br.com.c6tag.diagnostico.remote.access

import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnerIdentityFlowTest {
    @Test
    fun signInExchangesGoogleCredentialForFirebaseIdentityCandidate() = runBlocking {
        val session = FakeFirebaseSession()
        val identity = FirebaseOwnerIdentity(session, FakeCredentialStateCleaner())

        val result = identity.signIn(SecretValue.of("google-id-token"))

        assertEquals(IdentityOutcome.SIGNED_IN_CANDIDATE, result.outcome)
        assertEquals(listOf("google-id-token"), session.googleTokens)
        assertEquals("firebase-project", identity.account.value?.firebaseProjectId)
        assertEquals("firebase-uid", identity.account.value?.uid)
        assertEquals(0, session.reauthenticateCalls)
    }

    @Test
    fun signInFailureDoesNotPublishAnAccount() = runBlocking {
        val session = FakeFirebaseSession().apply {
            signInOutcome = FirebaseOperationOutcome.FAILED
        }
        val identity = FirebaseOwnerIdentity(session, FakeCredentialStateCleaner())

        val result = identity.signIn(SecretValue.of("google-id-token"))

        assertEquals(IdentityOutcome.FAILED, result.outcome)
        assertEquals(null, identity.account.value)
    }

    @Test
    fun cancellationBeforeIdentityCallDoesNotDispatchFirebaseMutation() = runBlocking {
        val originalAccount = OwnerAccount("firebase-project", "existing-uid", null)
        val session = FakeFirebaseSession(account = originalAccount)
        val identity = FirebaseOwnerIdentity(session, FakeCredentialStateCleaner())
        val signIn = launch(start = CoroutineStart.LAZY) {
            identity.signIn(SecretValue.of("google-id-token"))
        }

        signIn.cancel()
        signIn.join()

        assertEquals(0, session.signInCalls)
        assertEquals(originalAccount, identity.account.value)
    }

    @Test
    fun reauthenticationUsesFreshCredentialAndDoesNotReplaceMismatchedAccount() = runBlocking {
        val originalAccount = OwnerAccount("firebase-project", "original-uid", null)
        val session = FakeFirebaseSession(account = originalAccount).apply {
            reauthenticationOutcome = FirebaseOperationOutcome.ACCOUNT_MISMATCH
        }
        val identity = FirebaseOwnerIdentity(session, FakeCredentialStateCleaner())

        val result = identity.reauthenticate(SecretValue.of("fresh-google-id-token"))

        assertEquals(IdentityOutcome.ACCOUNT_MISMATCH, result.outcome)
        assertEquals(listOf("original-uid" to "fresh-google-id-token"), session.reauthenticationCalls)
        assertEquals(originalAccount, identity.account.value)
        assertEquals(0, session.signInCalls)
    }

    @Test
    fun firebaseIdTokenIsReturnedAsAnOpaqueRedactedValue() = runBlocking {
        val session = FakeFirebaseSession(
            account = OwnerAccount("firebase-project", "firebase-uid", null),
        ).apply {
            firebaseToken = SecretValue.of("firebase-id-token")
        }
        val identity = FirebaseOwnerIdentity(session, FakeCredentialStateCleaner())

        val token = identity.firebaseIdToken()

        assertEquals("firebase-id-token", token?.reveal())
        assertEquals("[REDACTED]", token.toString())
        assertFalse(token.toString().contains("firebase-id-token"))
    }

    @Test
    fun signOutClearsFirebaseSessionAndCredentialManagerState() = runBlocking {
        val session = FakeFirebaseSession(
            account = OwnerAccount("firebase-project", "firebase-uid", null),
        )
        val cleaner = FakeCredentialStateCleaner()
        val identity = FirebaseOwnerIdentity(session, cleaner)

        val result = identity.signOut()

        assertEquals(IdentityOutcome.SIGNED_OUT, result.outcome)
        assertEquals(1, session.signOutCalls)
        assertEquals(1, cleaner.clearCalls)
        assertEquals(null, identity.account.value)
    }

    @Test
    fun signOutReportsCredentialStateClearFailureWithoutRestoringAccount() = runBlocking {
        val session = FakeFirebaseSession(
            account = OwnerAccount("firebase-project", "firebase-uid", null),
        )
        val cleaner = FakeCredentialStateCleaner().apply {
            clearResult = false
        }
        val identity = FirebaseOwnerIdentity(session, cleaner)

        val result = identity.signOut()

        assertEquals(IdentityOutcome.FAILED, result.outcome)
        assertEquals(null, identity.account.value)
        assertEquals(1, cleaner.clearCalls)
    }

    @Test
    fun cancelledSignInWaitsForSdkCompletionAndCannotResurrectAfterSignOut() = runBlocking {
        val completion = CompletableDeferred<FirebaseOperationOutcome>()
        val session = FakeFirebaseSession().apply {
            signInCompletion = completion
        }
        val cleaner = FakeCredentialStateCleaner()
        val identity = FirebaseOwnerIdentity(session, cleaner)

        val signIn = launch {
            identity.signIn(SecretValue.of("late-google-id-token"))
        }
        yield()
        signIn.cancel()
        val signOut = async {
            identity.signOut()
        }
        completion.complete(FirebaseOperationOutcome.SUCCEEDED)

        signIn.join()
        val result = signOut.await()

        assertEquals(IdentityOutcome.SIGNED_OUT, result.outcome)
        assertEquals(2, session.signOutCalls)
        assertEquals(2, cleaner.clearCalls)
        assertEquals(null, identity.account.value)
    }

    @Test
    fun cancelledQueuedSignInDoesNotSignOutAccountEstablishedByEarlierMutation() = runBlocking {
        val initialAccount = OwnerAccount("firebase-project", "initial-uid", null)
        val firstAccount = OwnerAccount("firebase-project", "first-uid", null)
        val firstCompletion = CompletableDeferred<FirebaseOperationOutcome>()
        val secondCompletion = CompletableDeferred<FirebaseOperationOutcome>()
        val firstDispatch = CompletableDeferred<Unit>()
        val secondDispatch = CompletableDeferred<Unit>()
        val session = FakeFirebaseSession(account = initialAccount).apply {
            signInCompletions["first-token"] = firstCompletion
            signInCompletions["second-token"] = secondCompletion
            signInDispatchSignals["first-token"] = firstDispatch
            signInDispatchSignals["second-token"] = secondDispatch
            signInSuccessAccounts["first-token"] = firstAccount
        }
        val cleaner = FakeCredentialStateCleaner()
        val identity = FirebaseOwnerIdentity(session, cleaner)
        val firstSignIn = async(start = CoroutineStart.UNDISPATCHED) {
            identity.signIn(SecretValue.of("first-token"))
        }
        firstDispatch.await()

        val secondSignIn = async(start = CoroutineStart.UNDISPATCHED) {
            identity.signIn(SecretValue.of("second-token"))
        }
        assertEquals(listOf("first-token"), session.googleTokens)

        firstCompletion.complete(FirebaseOperationOutcome.SUCCEEDED)
        assertEquals(IdentityOutcome.SIGNED_IN_CANDIDATE, firstSignIn.await().outcome)
        assertEquals(firstAccount, identity.account.value)
        secondDispatch.await()

        secondSignIn.cancel()
        secondCompletion.complete(FirebaseOperationOutcome.FAILED)
        secondSignIn.join()
        val cancellation = try {
            secondSignIn.await()
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertFalse(cancellation == null)
        assertEquals(listOf("first-token", "second-token"), session.googleTokens)
        assertEquals(firstAccount, identity.account.value)
        assertEquals(0, session.signOutCalls)
        assertEquals(0, cleaner.clearCalls)
    }

    @Test
    fun authorizedAccountRequestFallsBackOnlyOnceWhenNoCredentialExists() = runBlocking {
        var authorizedRequests = 0
        var allAccountRequests = 0

        val result = requestGoogleCredentialWithFallback(
            requestAuthorizedAccounts = {
                authorizedRequests += 1
                GoogleCredentialResult(GoogleCredentialOutcome.NO_CREDENTIAL)
            },
            requestAllAccounts = {
                allAccountRequests += 1
                GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
            },
        )

        assertEquals(GoogleCredentialOutcome.FAILED, result.outcome)
        assertEquals(1, authorizedRequests)
        assertEquals(1, allAccountRequests)
    }

    @Test
    fun authorizedAccountRequestDoesNotFallbackAfterCancellationOrFailure() = runBlocking {
        var allAccountRequests = 0

        val cancelled = requestGoogleCredentialWithFallback(
            requestAuthorizedAccounts = {
                GoogleCredentialResult(GoogleCredentialOutcome.CANCELLED)
            },
            requestAllAccounts = {
                allAccountRequests += 1
                GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
            },
        )
        val failed = requestGoogleCredentialWithFallback(
            requestAuthorizedAccounts = {
                GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
            },
            requestAllAccounts = {
                allAccountRequests += 1
                GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
            },
        )

        assertEquals(GoogleCredentialOutcome.CANCELLED, cancelled.outcome)
        assertEquals(GoogleCredentialOutcome.FAILED, failed.outcome)
        assertEquals(0, allAccountRequests)
    }

    @Test
    fun onlyNonBlankGoogleIdTokensBecomeRedactedCredentials() {
        val accepted = googleCredentialResult(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            "google-id-token",
        )
        val unexpected = googleCredentialResult("password", "not-a-google-token")
        val blank = googleCredentialResult(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            " ",
        )

        assertEquals(GoogleCredentialOutcome.ACQUIRED, accepted.outcome)
        assertEquals("google-id-token", accepted.token?.reveal())
        assertEquals("[REDACTED]", accepted.token.toString())
        assertEquals(GoogleCredentialOutcome.FAILED, unexpected.outcome)
        assertEquals(null, unexpected.token)
        assertEquals(GoogleCredentialOutcome.FAILED, blank.outcome)
        assertEquals(null, blank.token)
    }

    @Test
    fun accountStateLabelsFirebaseIdentityAsCandidateOnly() {
        val account = OwnerAccount("firebase-project", "firebase-uid", "Owner")

        assertEquals(AccountState.SignedOut, null.toAccountState())
        assertEquals(AccountState.SignedInCandidate(account), account.toAccountState())
    }

    private class FakeFirebaseSession(
        account: OwnerAccount? = null,
    ) : FirebaseSession {
        private val mutableAccount = MutableStateFlow(account)
        override val account: StateFlow<OwnerAccount?> = mutableAccount

        var signInCalls = 0
        var signOutCalls = 0
        var reauthenticateCalls = 0
        var googleTokens = mutableListOf<String>()
        var reauthenticationCalls = mutableListOf<Pair<String, String>>()
        var signInCompletion: CompletableDeferred<FirebaseOperationOutcome>? = null
        val signInCompletions = mutableMapOf<String, CompletableDeferred<FirebaseOperationOutcome>>()
        val signInDispatchSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
        val signInSuccessAccounts = mutableMapOf<String, OwnerAccount>()
        var signInOutcome = FirebaseOperationOutcome.SUCCEEDED
        var reauthenticationOutcome = FirebaseOperationOutcome.SUCCEEDED
        var firebaseToken: SecretValue? = null

        override suspend fun signInWithGoogleCredential(
            googleIdToken: SecretValue,
        ): FirebaseOperationOutcome {
            signInCalls += 1
            val token = googleIdToken.reveal()
            googleTokens += token
            signInDispatchSignals[token]?.complete(Unit)
            val outcome = signInCompletions[token]?.await()
                ?: signInCompletion?.await()
                ?: signInOutcome
            if (outcome == FirebaseOperationOutcome.SUCCEEDED) {
                mutableAccount.value = signInSuccessAccounts[token]
                    ?: OwnerAccount("firebase-project", "firebase-uid", null)
            }
            return outcome
        }

        override suspend fun reauthenticate(
            expectedUid: String,
            googleIdToken: SecretValue,
        ): FirebaseOperationOutcome {
            reauthenticateCalls += 1
            reauthenticationCalls += expectedUid to googleIdToken.reveal()
            return reauthenticationOutcome
        }

        override suspend fun firebaseIdToken(): SecretValue? = firebaseToken

        override suspend fun signOut(): Boolean {
            signOutCalls += 1
            mutableAccount.value = null
            return true
        }
    }

    private class FakeCredentialStateCleaner : CredentialStateCleaner {
        var clearCalls = 0
        var clearResult = true

        override suspend fun clear(): Boolean {
            clearCalls += 1
            return clearResult
        }
    }
}
