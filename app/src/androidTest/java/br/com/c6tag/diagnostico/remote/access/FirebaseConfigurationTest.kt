package br.com.c6tag.diagnostico.remote.access

import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.R
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseConfigurationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedFirebaseOptionsMatchTheLocalAndroidRegistration() {
        val firebaseApp = FirebaseApp.getInstance()
        val webClientId = context.getString(R.string.default_web_client_id)
        val packagedPrivateCredentialAssets = context.assets.list("").orEmpty().filter {
            it.contains("service-account", ignoreCase = true) ||
                it.contains("private-key", ignoreCase = true)
        }

        assertEquals("br.com.tagalert.app", context.packageName)
        assertEquals("tag-alert-ba5d8", firebaseApp.options.projectId)
        assertTrue(firebaseApp.options.applicationId.isNotBlank())
        assertTrue(webClientId.endsWith(".apps.googleusercontent.com"))
        assertFalse(packagedPrivateCredentialAssets.isNotEmpty())
    }

    @Test
    fun googleIdCredentialParserAcceptsOnlyTheSdkGoogleTokenType() {
        val sdkCredential = GoogleIdTokenCredential(
            "owner@example.com",
            "synthetic-google-id-token",
            null,
            null,
            null,
            null,
            null,
        )
        val accepted = parseGoogleCredential(sdkCredential)
        val rejected = parseGoogleCredential(
            CustomCredential("example.unknown-credential", Bundle()),
        )

        assertEquals(GoogleCredentialOutcome.ACQUIRED, accepted.outcome)
        assertEquals("synthetic-google-id-token", accepted.token?.reveal())
        assertEquals("[REDACTED]", accepted.token.toString())
        assertEquals(GoogleCredentialOutcome.FAILED, rejected.outcome)
        assertEquals(null, rejected.token)
    }

    @Test
    fun malformedGoogleTokenCredentialBundleIsRejected() {
        val credentialWithMalformedBundle = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            Bundle(),
        )

        val result = parseGoogleCredential(credentialWithMalformedBundle)

        assertEquals(GoogleCredentialOutcome.FAILED, result.outcome)
        assertEquals(null, result.token)
    }
}
