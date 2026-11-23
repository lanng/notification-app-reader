package br.com.c6tag.diagnostico.remote.access

import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import br.com.c6tag.diagnostico.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

internal enum class GoogleCredentialOutcome {
    ACQUIRED,
    CANCELLED,
    NO_CREDENTIAL,
    FAILED,
}

internal data class GoogleCredentialResult(
    val outcome: GoogleCredentialOutcome,
    val token: SecretValue? = null,
)

internal fun googleCredentialResult(
    credentialType: String,
    idToken: String?,
): GoogleCredentialResult {
    if (
        credentialType != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
        idToken.isNullOrBlank()
    ) {
        return GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
    }
    return GoogleCredentialResult(
        outcome = GoogleCredentialOutcome.ACQUIRED,
        token = SecretValue.of(idToken),
    )
}

internal suspend fun requestGoogleCredentialWithFallback(
    requestAuthorizedAccounts: suspend () -> GoogleCredentialResult,
    requestAllAccounts: suspend () -> GoogleCredentialResult,
): GoogleCredentialResult {
    val authorizedResult = requestAuthorizedAccounts()
    return if (authorizedResult.outcome == GoogleCredentialOutcome.NO_CREDENTIAL) {
        requestAllAccounts()
    } else {
        authorizedResult
    }
}

internal class GoogleCredentialRequestor(
    private val activity: Activity,
) {
    private val credentialManager = CredentialManager.create(activity)

    internal suspend fun request(): GoogleCredentialResult =
        requestGoogleCredentialWithFallback(
            requestAuthorizedAccounts = {
                requestCredential(filterByAuthorizedAccounts = true)
            },
            requestAllAccounts = {
                requestCredential(filterByAuthorizedAccounts = false)
            },
        )

    private suspend fun requestCredential(
        filterByAuthorizedAccounts: Boolean,
    ): GoogleCredentialResult {
        val serverClientId = activity.getString(R.string.default_web_client_id)
            .takeIf(String::isNotBlank)
            ?: return GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            parseGoogleCredential(credentialManager.getCredential(activity, request).credential)
        } catch (_: NoCredentialException) {
            GoogleCredentialResult(GoogleCredentialOutcome.NO_CREDENTIAL)
        } catch (_: GetCredentialCancellationException) {
            GoogleCredentialResult(GoogleCredentialOutcome.CANCELLED)
        } catch (_: GetCredentialException) {
            GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
        }
    }
}

internal fun parseGoogleCredential(credential: Credential): GoogleCredentialResult {
    if (
        credential !is CustomCredential ||
        credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
    }

    return try {
        val parsedCredential = GoogleIdTokenCredential.createFrom(credential.data)
        googleCredentialResult(credential.type, parsedCredential.idToken)
    } catch (_: GoogleIdTokenParsingException) {
        GoogleCredentialResult(GoogleCredentialOutcome.FAILED)
    }
}
