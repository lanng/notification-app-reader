package br.com.c6tag.diagnostico.remote.access

import br.com.c6tag.diagnostico.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class OwnerAccessClientTest {
    @Test
    fun ownerDiscoveryUsesTheReadOnlyBackendFixtureAndFirebaseCredential() = runBlocking {
        val transport = ScriptedOwnerAccessTransport(
            response = OwnerAccessTransportResult.Response(
                statusCode = 200,
                body = EMPTY_OWNER_MONITOR_FIXTURE.toByteArray(Charsets.UTF_8),
            ),
        )
        val owner = OwnerAccount("firebase-project", "firebase-uid", "Owner")

        val result = OwnerAccessClient(transport).discoverOwnerMonitor(
            owner = owner,
            firebaseIdToken = SecretValue.of("synthetic-firebase-id-token"),
        )

        assertTrue("Expected strict discovery parsing", result is OwnerAccessResult.Success)
        val snapshot = (result as OwnerAccessResult.Success).value
        assertNull(snapshot.monitorId)
        assertEquals("0", snapshot.generation.decimal)
        assertNull(snapshot.hostInstallationId)
        assertNull(snapshot.hostLabel)
        assertTrue(snapshot.requiresReference)

        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals(OwnerAccessMethod.GET, call.method)
        assertEquals(OwnerAccessPath.OWNER_MONITOR, call.path)
        assertEquals(OwnerAccessCredentialKind.FIREBASE_ID_TOKEN, call.credential.kind)
        assertEquals(owner, call.credential.owner)
        assertEquals("https://api.example.test", call.credential.origin)
        assertEquals("synthetic-firebase-id-token", call.credential.secret.reveal())
        assertNull(call.body)
    }

    @Test
    fun ownerDiscoveryRequiresNativeTypesAndEveryRequiredField() = runBlocking {
        assertTrue(discover(EMPTY_OWNER_MONITOR_FIXTURE) is OwnerAccessResult.Success)
        val invalidDataObjects = listOf(
            """{"generation":"0","host":null,"requiresReference":true}""",
            """{"monitor":null,"host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":0,"host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":true,"host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":1.0,"host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":1e3,"host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":"01","host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":"-1","host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":"9223372036854775808","host":null,"requiresReference":true}""",
            """{"monitor":{},"generation":"0","host":null,"requiresReference":true}""",
            """{"monitor":{"id":7},"generation":"0","host":null,"requiresReference":true}""",
            """{"monitor":null,"generation":"0","host":{},"requiresReference":true}""",
            """{"monitor":null,"generation":"0","host":{"installationId":7,"label":null},"requiresReference":true}""",
            """{"monitor":null,"generation":"0","host":{"installationId":"host-id"},"requiresReference":true}""",
            """{"monitor":null,"generation":"0","host":null,"requiresReference":"true"}""",
            """{"monitor":null,"generation":"0","host":null,"requiresReference":1}""",
            """{"monitor":null,"generation":"0","host":null}""",
            """{"monitor":null,"generation":"0","host":null,"requiresReference":false}""",
        )

        invalidDataObjects.forEach { dataJson ->
            assertFailureReason(
                OwnerAccessFailureReason.INVALID_RESPONSE,
                discover("""{"data":$dataJson}"""),
            )
        }
    }

    @Test
    fun ownerDiscoveryAcceptsOpaqueIdsNullableLabelAndUnknownResponseFields() = runBlocking {
        val result = discover(
            """{"data":{"monitor":{"id":"opaque-monitor"},"generation":"9223372036854775807","host":{"installationId":"opaque-installation","label":null},"requiresReference":true,"futureField":{"ignored":true}}}""",
        )

        assertTrue(result is OwnerAccessResult.Success)
        val snapshot = (result as OwnerAccessResult.Success).value
        assertEquals("opaque-monitor", snapshot.monitorId)
        assertEquals("9223372036854775807", snapshot.generation.decimal)
        assertEquals("opaque-installation", snapshot.hostInstallationId)
        assertNull(snapshot.hostLabel)
    }

    @Test
    fun ownerDiscoveryRejectsMalformedDuplicateTrailingInvalidUtf8AndOversizedJson() = runBlocking {
        val malformedBodies = listOf(
            "{",
            """{"data":{"monitor":null,"generation":"0","host":null,"requiresReference":true}} trailing""",
            """{"data":{"monitor":null,"generation":"0","generation":"1","host":null,"requiresReference":true}}""",
        )
        malformedBodies.forEach { body ->
            assertFailureReason(
                OwnerAccessFailureReason.INVALID_RESPONSE,
                discover(body),
            )
        }

        assertFailureReason(
            OwnerAccessFailureReason.INVALID_RESPONSE,
            discoverBytes(byteArrayOf(0xC3.toByte(), 0x28)),
        )
        assertFailureReason(
            OwnerAccessFailureReason.INVALID_RESPONSE,
            discoverBytes(ByteArray(MAX_RESPONSE_BYTES + 1) { 0x20 }),
        )
    }

    @Test
    fun activationPostsExactConfirmationAndExpectedGeneration() = runBlocking {
        val transport = ScriptedOwnerAccessTransport(response(201, ACTIVATION_FIXTURE))
        val result = activate(client = OwnerAccessClient(transport))

        assertTrue(result is OwnerAccessResult.Success)
        val activation = (result as OwnerAccessResult.Success).value
        assertEquals("monitor-opaque", activation.monitorId)
        assertEquals("installation-opaque", activation.installationId)
        assertEquals("membership-opaque", activation.membershipId)
        assertEquals("1", activation.generation.decimal)
        assertEquals(1_790_000_000_000L, activation.createdAtMillis)
        assertEquals("one-time-synthetic-pat", activation.accessToken.reveal())
        assertEquals("[REDACTED]", activation.accessToken.toString())
        assertFalse(activation.toString().contains("one-time-synthetic-pat"))
        assertFalse(activation.toString().contains("synthetic-firebase-id-token"))

        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals(OwnerAccessMethod.POST, call.method)
        assertEquals(OwnerAccessPath.HOST_ACTIVATIONS, call.path)
        assertEquals(OwnerAccessCredentialKind.FIREBASE_ID_TOKEN, call.credential.kind)
        assertEquals(
            """{"confirmation":true,"expectedGeneration":"0"}""",
            call.body,
        )
    }

    @Test
    fun activationRejectsInvalidResponseFieldsAndNativeTimestampTypesAsAmbiguous() = runBlocking {
        assertTrue(activate() is OwnerAccessResult.Success)
        val invalidFixtures = listOf(
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":"1790000000000""""),
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":1790000000000.0"""),
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":1e3"""),
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":true"""),
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":9223372036854775808"""),
            ACTIVATION_FIXTURE.replace(""""createdAt":1790000000000""", """"createdAt":null"""),
            ACTIVATION_FIXTURE.replace(",\"createdAt\":1790000000000", ""),
            ACTIVATION_FIXTURE.replace(""""role":"Host"""", """"role":"Support""""),
            ACTIVATION_FIXTURE.replace(""""requiresReference":true""", """"requiresReference":false"""),
            ACTIVATION_FIXTURE.replace(""""generation":"1"""", """"generation":"01""""),
            ACTIVATION_FIXTURE.replace(""""accessToken":"one-time-synthetic-pat"""", """"accessToken":7"""),
        )

        invalidFixtures.forEach { body ->
            val result = activate(body)
            assertFailureReason(OwnerAccessFailureReason.INVALID_RESPONSE, result)
            assertEquals(ActivationDisposition.AMBIGUOUS, failure(result).activationDisposition)
        }
    }

    @Test
    fun activationAcceptsSignedLongTimestampEndpoints() = runBlocking {
        listOf(
            """"createdAt":0""",
            """"createdAt":9223372036854775807""",
            """"createdAt":-9223372036854775808""",
        ).forEach { timestamp ->
            val fixture = ACTIVATION_FIXTURE.replace(
                """"createdAt":1790000000000""",
                timestamp,
            )
            assertTrue(activate(fixture) is OwnerAccessResult.Success)
        }
    }

    @Test
    fun activationResponseLossIsAmbiguousAndNeverAutomaticallyRetried() = runBlocking {
        val transport = ScriptedOwnerAccessTransport(OwnerAccessTransportResult.NetworkFailure)
        val result = activate(client = OwnerAccessClient(transport))

        assertEquals(OwnerAccessFailureReason.NETWORK_FAILURE, failure(result).reason)
        assertEquals(ActivationDisposition.AMBIGUOUS, failure(result).activationDisposition)
        assertEquals(1, transport.calls.count { it.path == OwnerAccessPath.HOST_ACTIVATIONS })
    }

    @Test
    fun activationKnownNoWriteErrorsAreClassifiedWithoutExposingServerMessages() = runBlocking {
        val knownNoWriteResponses = listOf(
            401 to "access_unauthenticated",
            403 to "owner_not_authorized",
            403 to "recent_authentication_required",
            409 to "generation_conflict",
            422 to "invalid_payload",
            429 to "rate_limited",
        )

        knownNoWriteResponses.forEach { (status, code) ->
            val result = activate(
                body = """{"code":"$code","message":"raw server detail $SECRET_SENTINEL"}""",
                status = status,
            )

            assertEquals(ActivationDisposition.KNOWN_NOT_WRITTEN, failure(result).activationDisposition)
            assertFalse(failure(result).toString().contains(SECRET_SENTINEL))
            assertFalse(failure(result).reason.safeMessage.contains(SECRET_SENTINEL))
        }
    }

    @Test
    fun activationServerErrorsUnknownCodesAndMalformedErrorsStayAmbiguous() = runBlocking {
        val ambiguousResponses = listOf(
            500 to """{"code":"internal_error","message":"$SECRET_SENTINEL"}""",
            503 to """{"code":"identity_verification_unavailable","message":"$SECRET_SENTINEL"}""",
        )
        ambiguousResponses.forEach { (status, body) ->
            val result = activate(body = body, status = status)
            assertEquals(ActivationDisposition.AMBIGUOUS, failure(result).activationDisposition)
            assertFalse(failure(result).toString().contains(SECRET_SENTINEL))
        }

        val malformedSuccess = activate(body = "{", status = 201)
        assertEquals(OwnerAccessFailureReason.INVALID_RESPONSE, failure(malformedSuccess).reason)
        assertEquals(ActivationDisposition.AMBIGUOUS, failure(malformedSuccess).activationDisposition)
    }

    @Test
    fun malformedOrUnknownKnownNoWriteHttpResponsesRemainProtocolFailures() = runBlocking {
        listOf(
            403 to """{"code":"unrecognized_secret_code","message":"$SECRET_SENTINEL"}""",
            409 to "{",
        ).forEach { (status, body) ->
            val result = activate(body = body, status = status)
            assertEquals(OwnerAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
            assertEquals(ActivationDisposition.KNOWN_NOT_WRITTEN, failure(result).activationDisposition)
            assertFalse(failure(result).toString().contains(SECRET_SENTINEL))
        }
    }

    @Test
    fun rateLimitedHttpsResponseExposesValidatedRetryAfterSecondsAsSafeMetadata() = runBlocking {
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/host-activations"),
            responseBody = """{"code":"rate_limited","message":"$SECRET_SENTINEL"}"""
                .toByteArray(StandardCharsets.UTF_8),
            responseStatusCode = 429,
        ).apply {
            setResponseProperty("Retry-After", "37")
        }
        val result = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                ownerApiOrigin = "https://api.example.test",
                connectionFactory = HttpsConnectionFactory { connection },
            ),
        ).activateHost(
            owner = OWNER,
            firebaseIdToken = TOKEN,
            expectedGeneration = generation("0"),
        )
        val accessFailure = failure(result)

        assertEquals(OwnerAccessFailureReason.RATE_LIMITED, accessFailure.reason)
        assertEquals(ActivationDisposition.KNOWN_NOT_WRITTEN, accessFailure.activationDisposition)
        assertEquals(37L, accessFailure.retryAfterSeconds)
        assertFalse(accessFailure.toString().contains(SECRET_SENTINEL))
        assertFalse(accessFailure.reason.safeMessage.contains(SECRET_SENTINEL))
    }

    @Test
    fun invalidRetryAfterValuesAreIgnoredAndCanonicalOriginIsReadOnly() = runBlocking {
        val canonicalClient = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                ownerApiOrigin = "https://API.EXAMPLE.TEST/",
                connectionFactory = HttpsConnectionFactory {
                    error("Reading configured origin must not open a connection")
                },
            ),
        )
        assertEquals("https://api.example.test", canonicalClient.canonicalOrigin)

        listOf(
            "",
            "-1",
            "+1",
            "1.0",
            "9223372036854775808",
            "99999999999999999999",
            "Thu, 01 Jan 1970 00:00:37 GMT",
            " 37",
            "٣٧",
        ).forEach { headerValue ->
            val connection = FakeHttpsConnection(
                url = URL("https://api.example.test/api/v1/owner/host-activations"),
                responseBody = """{"code":"rate_limited"}"""
                    .toByteArray(StandardCharsets.UTF_8),
                responseStatusCode = 429,
            ).apply {
                setResponseProperty("Retry-After", headerValue)
            }
            val result = OwnerAccessClient(
                HttpsOwnerAccessTransport(
                    ownerApiOrigin = "https://api.example.test",
                    connectionFactory = HttpsConnectionFactory { connection },
                ),
            ).activateHost(OWNER, TOKEN, generation("0"))

            assertNull(failure(result).retryAfterSeconds)
        }
    }

    @Test
    fun installationDiscoveryUsesPatAndChecksAllSavedHostContextFields() = runBlocking {
        val context = expectedHostContext(OWNER)
        val transport = ScriptedOwnerAccessTransport(response(200, INSTALLATION_FIXTURE))
        val result = OwnerAccessClient(transport).discoverInstallationAccess(
            accessToken = SecretValue.of("synthetic-installation-pat"),
            expectedContext = context,
        )

        assertTrue(result is OwnerAccessResult.Success)
        val access = (result as OwnerAccessResult.Success).value
        assertEquals(context.installationId, access.installationId)
        assertEquals(context.monitorId, access.monitorId)
        assertEquals(context.membershipId, access.membershipId)
        assertEquals(context.generation, access.generation)
        val call = transport.calls.single()
        assertEquals(OwnerAccessPath.INSTALLATION, call.path)
        assertEquals(OwnerAccessCredentialKind.INSTALLATION_ACCESS_TOKEN, call.credential.kind)
        assertEquals(context, call.credential.hostContext)
        assertEquals("synthetic-installation-pat", call.credential.secret.reveal())
        assertNull(call.body)

        val otherOriginContext = context.copy(origin = "https://other.example.test")
        val noRequestTransport = ScriptedOwnerAccessTransport(response(200, INSTALLATION_FIXTURE))
        val mismatch = OwnerAccessClient(noRequestTransport).discoverInstallationAccess(
            accessToken = SecretValue.of("synthetic-installation-pat"),
            expectedContext = otherOriginContext,
        )
        assertEquals(OwnerAccessFailureReason.HOST_CONTEXT_MISMATCH, failure(mismatch).reason)
        assertEquals(0, noRequestTransport.calls.size)

        val blankContextTransport = ScriptedOwnerAccessTransport(response(200, INSTALLATION_FIXTURE))
        val blankContext = OwnerAccessClient(blankContextTransport).discoverInstallationAccess(
            accessToken = SecretValue.of("synthetic-installation-pat"),
            expectedContext = context.copy(membershipId = " "),
        )
        assertEquals(OwnerAccessFailureReason.HOST_CONTEXT_MISMATCH, failure(blankContext).reason)
        assertEquals(0, blankContextTransport.calls.size)
    }

    @Test
    fun installationDiscoveryRejectsSupportInactiveAndMismatchedResponses() = runBlocking {
        assertTrue(verifyInstallation(INSTALLATION_FIXTURE) is OwnerAccessResult.Success)
        val invalidFixtures = listOf(
            INSTALLATION_FIXTURE.replace(""""role":"Host"""", """"role":"Support""""),
            INSTALLATION_FIXTURE.replace(""""status":"Active"""", """"status":"Pending""""),
            INSTALLATION_FIXTURE.replace(""""monitorGeneration":"1"""", """"monitorGeneration":"2""""),
            INSTALLATION_FIXTURE.replace(""""installationId":"installation-opaque"""", """"installationId":"other-installation""""),
            INSTALLATION_FIXTURE.replace(""""membershipId":"membership-opaque"""", """"membershipId":"other-membership""""),
        )

        invalidFixtures.forEach { fixture ->
            val result = verifyInstallation(fixture)
            assertEquals(OwnerAccessFailureReason.HOST_CONTEXT_MISMATCH, failure(result).reason)
        }
    }

    @Test
    fun activationRefusesFalseConfirmationAndInvalidOriginBeforeSending() = runBlocking {
        val transport = ScriptedOwnerAccessTransport(response(201, ACTIVATION_FIXTURE))
        val invalidOriginTransport = ScriptedOwnerAccessTransport(
            response = response(201, ACTIVATION_FIXTURE),
            origin = null,
            configurationFailure = OwnerAccessFailureReason.INVALID_ORIGIN,
        )

        val notConfirmed = OwnerAccessClient(transport).activateHost(
            owner = OWNER,
            firebaseIdToken = TOKEN,
            expectedGeneration = generation("0"),
            confirmation = false,
        )
        val invalidOrigin = OwnerAccessClient(invalidOriginTransport).activateHost(
            owner = OWNER,
            firebaseIdToken = TOKEN,
            expectedGeneration = generation("0"),
        )

        assertEquals(ActivationDisposition.NOT_SENT, failure(notConfirmed).activationDisposition)
        assertEquals(0, transport.calls.size)
        assertEquals(OwnerAccessFailureReason.INVALID_ORIGIN, failure(invalidOrigin).reason)
        assertEquals(ActivationDisposition.NOT_SENT, failure(invalidOrigin).activationDisposition)
        assertEquals(0, invalidOriginTransport.calls.size)
    }

    @Test
    fun failureAndCredentialTextNeverContainServerMessagesOrBearerSecrets() = runBlocking {
        val result = activate(
            body = """{"code":"generation_conflict","message":"$SECRET_SENTINEL"}""",
            status = 409,
        )

        assertEquals(OwnerAccessFailureReason.GENERATION_CONFLICT, failure(result).reason)
        assertFalse(failure(result).toString().contains(SECRET_SENTINEL))
        val credential = OwnerAccessCredential(
            origin = "https://api.example.test",
            kind = OwnerAccessCredentialKind.FIREBASE_ID_TOKEN,
            owner = OWNER,
            secret = TOKEN,
        )
        assertFalse(credential.toString().contains("synthetic-firebase-id-token"))
    }

    @Test
    fun bearerCredentialsWithHeaderControlsAreRejectedBeforeTransport() = runBlocking {
        val transport = ScriptedOwnerAccessTransport(response(200, EMPTY_OWNER_MONITOR_FIXTURE))
        val result = OwnerAccessClient(transport).discoverOwnerMonitor(
            owner = OWNER,
            firebaseIdToken = SecretValue.of("synthetic-token\r\nInjected: value"),
        )

        assertEquals(OwnerAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun httpsTransportRejectsUnconfiguredAndUnsafeOriginsWithoutOpeningConnections() = runBlocking {
        assertEquals("", BuildConfig.OWNER_API_ORIGIN)
        assertEquals(
            OwnerAccessFailureReason.NOT_CONFIGURED,
            failure(OwnerAccessClient().discoverOwnerMonitor(OWNER, TOKEN)).reason,
        )
        val rejectedOrigins = listOf(
            "",
            "http://api.example.test",
            "//api.example.test",
            "https://api.example.test/path",
            "https://api.example.test?query=1",
            "https://api.example.test#fragment",
            "https://user:secret@api.example.test",
            "https://api.example.test@attacker.example",
            "https://api.example.test\\@attacker.example",
            "https://api.example.test:",
            "https://api.example.test:65536",
            "https://api.example.test:invalid",
            "https://api.example.test\u0000",
        )
        rejectedOrigins.forEach { configuredOrigin ->
            val openedConnections = AtomicInteger()
            val transport = HttpsOwnerAccessTransport(
                ownerApiOrigin = configuredOrigin,
                connectionFactory = HttpsConnectionFactory {
                    openedConnections.incrementAndGet()
                    error("Rejected origin must not open a connection")
                },
            )
            val result = OwnerAccessClient(transport).discoverOwnerMonitor(OWNER, TOKEN)

            val expectedReason = if (configuredOrigin.isEmpty()) {
                OwnerAccessFailureReason.NOT_CONFIGURED
            } else {
                OwnerAccessFailureReason.INVALID_ORIGIN
            }
            assertEquals(expectedReason, failure(result).reason)
            assertEquals(0, openedConnections.get())
        }
    }

    @Test
    fun httpsTransportUsesBoundHttpsOriginHeadersTimeoutsAndOffMainThreadExecution() = runBlocking {
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test:8443/api/v1/owner/monitor"),
            responseBody = EMPTY_OWNER_MONITOR_FIXTURE.toByteArray(StandardCharsets.UTF_8),
        )
        val openedUrls = mutableListOf<URL>()
        val transport = HttpsOwnerAccessTransport(
            ownerApiOrigin = "https://API.EXAMPLE.TEST:8443/",
            connectionFactory = HttpsConnectionFactory { url ->
                openedUrls += url
                connection
            },
        )

        val result = OwnerAccessClient(transport).discoverOwnerMonitor(OWNER, TOKEN)

        assertTrue(result is OwnerAccessResult.Success)
        assertEquals("https://api.example.test:8443/api/v1/owner/monitor", openedUrls.single().toString())
        assertEquals("GET", connection.requestMethod)
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("Bearer synthetic-firebase-id-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.doOutput)
        assertEquals("", connection.output.toString(StandardCharsets.UTF_8.name()))
        assertTrue(connection.disconnected)
        assertFalse(connection.connectThreadName == Thread.currentThread().name)
    }

    @Test
    fun httpsTransportWritesTheExactActivationPayloadAndNeverFollowsRedirects() = runBlocking {
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/host-activations"),
            responseBody = ACTIVATION_FIXTURE.toByteArray(StandardCharsets.UTF_8),
            responseStatusCode = 201,
        )
        val openedUrls = mutableListOf<URL>()
        val transport = HttpsOwnerAccessTransport(
            ownerApiOrigin = "https://api.example.test",
            connectionFactory = HttpsConnectionFactory { url ->
                openedUrls += url
                connection
            },
        )
        val result = OwnerAccessClient(transport).activateHost(
            owner = OWNER,
            firebaseIdToken = TOKEN,
            expectedGeneration = generation("0"),
        )

        assertTrue(result is OwnerAccessResult.Success)
        assertEquals("https://api.example.test/api/v1/owner/host-activations", openedUrls.single().toString())
        assertEquals("POST", connection.requestMethod)
        assertTrue(connection.doOutput)
        assertEquals("Bearer synthetic-firebase-id-token", connection.getRequestProperty("Authorization"))
        assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        assertEquals(
            """{"confirmation":true,"expectedGeneration":"0"}""",
            connection.output.toString(StandardCharsets.UTF_8.name()),
        )
        assertFalse(connection.instanceFollowRedirects)
    }

    @Test
    fun httpsTransportUsesTheInstallationPatOnlyForTheInstallationEndpoint() = runBlocking {
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/installation"),
            responseBody = INSTALLATION_FIXTURE.toByteArray(StandardCharsets.UTF_8),
        )
        val result = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                ownerApiOrigin = "https://api.example.test",
                connectionFactory = HttpsConnectionFactory { connection },
            ),
        ).discoverInstallationAccess(
            accessToken = SecretValue.of("synthetic-installation-pat"),
            expectedContext = expectedHostContext(OWNER),
        )

        assertTrue(result is OwnerAccessResult.Success)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("Bearer synthetic-installation-pat", connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Content-Type"))
        assertFalse(connection.doOutput)
    }

    @Test
    fun httpsTransportDoesNotForwardAuthorizationAcrossRedirects() = runBlocking {
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/monitor"),
            responseBody = ByteArray(0),
            responseStatusCode = 302,
        ).apply {
            setResponseProperty("Location", "https://attacker.example/$SECRET_SENTINEL")
        }
        var connectionCount = 0
        val result = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                ownerApiOrigin = "https://api.example.test",
                connectionFactory = HttpsConnectionFactory {
                    connectionCount += 1
                    connection
                },
            ),
        ).discoverOwnerMonitor(OWNER, TOKEN)

        assertEquals(OwnerAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        assertEquals(1, connectionCount)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(failure(result).toString().contains(SECRET_SENTINEL))
    }

    @Test
    fun httpsTransportBoundsResponseReadsAndClassifiesActivationConnectionLoss() = runBlocking {
        val oversizedConnection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/monitor"),
            responseBody = ByteArray(MAX_RESPONSE_BYTES + 1) { 0x20 },
        )
        val oversizedResult = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                "https://api.example.test",
                connectionFactory = HttpsConnectionFactory { oversizedConnection },
            ),
        ).discoverOwnerMonitor(OWNER, TOKEN)

        assertEquals(OwnerAccessFailureReason.INVALID_RESPONSE, failure(oversizedResult).reason)
        assertTrue(oversizedConnection.responseBytesRead <= MAX_RESPONSE_BYTES + 1)

        val lostResponseConnection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/host-activations"),
            responseBody = ByteArray(0),
            responseStatusCode = 201,
            inputFailure = IOException("synthetic connection loss"),
        )
        val lostResponse = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                "https://api.example.test",
                connectionFactory = HttpsConnectionFactory { lostResponseConnection },
            ),
        ).activateHost(
            OWNER,
            TOKEN,
            generation("0"),
        )
        assertEquals(OwnerAccessFailureReason.NETWORK_FAILURE, failure(lostResponse).reason)
        assertEquals(ActivationDisposition.AMBIGUOUS, failure(lostResponse).activationDisposition)
        assertTrue(lostResponseConnection.disconnected)
    }

    @Test
    fun httpsTransportPropagatesUnexpectedFactoryFailuresToTheCallingCoroutine() {
        val programmingFailure = IllegalStateException("synthetic-programming-failure")
        val propagatedFailure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    OwnerAccessClient(
                        HttpsOwnerAccessTransport(
                            "https://api.example.test",
                            connectionFactory = HttpsConnectionFactory {
                                throw programmingFailure
                            },
                        ),
                    ).discoverOwnerMonitor(OWNER, TOKEN)
                }
            }
        }

        assertEquals(IllegalStateException::class.java, propagatedFailure.javaClass)
        assertEquals("synthetic-programming-failure", propagatedFailure.message)
    }

    @Test
    fun httpsTransportDisconnectsAndPropagatesUnexpectedConfigurationFailures() {
        val programmingFailure = IllegalStateException("synthetic-configuration-failure")
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/monitor"),
            responseBody = EMPTY_OWNER_MONITOR_FIXTURE.toByteArray(StandardCharsets.UTF_8),
            requestPropertyFailure = programmingFailure,
        )
        val propagatedFailure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    OwnerAccessClient(
                        HttpsOwnerAccessTransport(
                            "https://api.example.test",
                            connectionFactory = HttpsConnectionFactory { connection },
                        ),
                    ).discoverOwnerMonitor(OWNER, TOKEN)
                }
            }
        }

        assertEquals(IllegalStateException::class.java, propagatedFailure.javaClass)
        assertEquals("synthetic-configuration-failure", propagatedFailure.message)
        assertTrue(connection.disconnected)
    }

    @Test
    fun httpsTransportDisconnectsAndPropagatesUnexpectedReadFailures() {
        val programmingFailure = IllegalArgumentException("synthetic-read-failure")
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/monitor"),
            responseBody = EMPTY_OWNER_MONITOR_FIXTURE.toByteArray(StandardCharsets.UTF_8),
            inputRuntimeFailure = programmingFailure,
        )
        val propagatedFailure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    OwnerAccessClient(
                        HttpsOwnerAccessTransport(
                            "https://api.example.test",
                            connectionFactory = HttpsConnectionFactory { connection },
                        ),
                    ).discoverOwnerMonitor(OWNER, TOKEN)
                }
            }
        }

        assertEquals(IllegalArgumentException::class.java, propagatedFailure.javaClass)
        assertEquals("synthetic-read-failure", propagatedFailure.message)
        assertTrue(connection.disconnected)
    }

    @Test
    fun httpsTransportMapsRejectedDispatcherExecutionToNetworkFailure() = runBlocking {
        val result = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                "https://api.example.test",
                ioDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        throw RejectedExecutionException("dispatcher rejected")
                    }
                },
                connectionFactory = HttpsConnectionFactory {
                    error("Rejected dispatch must not open a connection")
                },
            ),
        ).discoverOwnerMonitor(OWNER, TOKEN)

        assertEquals(OwnerAccessFailureReason.NETWORK_FAILURE, failure(result).reason)
        assertNull(failure(result).activationDisposition)
    }

    @Test
    fun activationDispatcherRejectionIsNotSentAndDoesNotOpenOrRetryAConnection() = runBlocking {
        val dispatchAttempts = AtomicInteger()
        val openedConnections = AtomicInteger()
        val result = OwnerAccessClient(
            HttpsOwnerAccessTransport(
                ownerApiOrigin = "https://api.example.test",
                ioDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        dispatchAttempts.incrementAndGet()
                        throw RejectedExecutionException("dispatcher rejected before execution")
                    }
                },
                connectionFactory = HttpsConnectionFactory {
                    openedConnections.incrementAndGet()
                    error("A rejected dispatch must not open a connection")
                },
            ),
        ).activateHost(
            owner = OWNER,
            firebaseIdToken = TOKEN,
            expectedGeneration = generation("0"),
        )

        val activationFailure = failure(result)
        assertEquals(OwnerAccessFailureReason.NETWORK_FAILURE, activationFailure.reason)
        assertEquals(1, dispatchAttempts.get())
        assertEquals(0, openedConnections.get())
        assertEquals(ActivationDisposition.NOT_SENT, activationFailure.activationDisposition)
    }

    @Test
    fun httpsTransportDisconnectsAConnectionWhenItsCoroutineIsCancelled() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val connection = FakeHttpsConnection(
            url = URL("https://api.example.test/api/v1/owner/monitor"),
            responseBody = EMPTY_OWNER_MONITOR_FIXTURE.toByteArray(StandardCharsets.UTF_8),
            blockedReadStarted = readStarted,
            disconnectSignal = disconnected,
        )
        val request = async {
            OwnerAccessClient(
                HttpsOwnerAccessTransport(
                    "https://api.example.test",
                    connectionFactory = HttpsConnectionFactory { connection },
                ),
            ).discoverOwnerMonitor(OWNER, TOKEN)
        }

        readStarted.await()
        request.cancelAndJoin()
        disconnected.await()

        assertTrue(connection.disconnected)
    }

    private suspend fun discover(body: String): OwnerAccessResult<OwnerMonitorSnapshot> =
        discoverBytes(body.toByteArray(StandardCharsets.UTF_8))

    private suspend fun discoverBytes(body: ByteArray): OwnerAccessResult<OwnerMonitorSnapshot> =
        OwnerAccessClient(
            ScriptedOwnerAccessTransport(
                response = OwnerAccessTransportResult.Response(200, body),
            ),
        ).discoverOwnerMonitor(OWNER, TOKEN)

    private suspend fun activate(
        body: String = ACTIVATION_FIXTURE,
        status: Int = 201,
        client: OwnerAccessClient = OwnerAccessClient(
            ScriptedOwnerAccessTransport(response(status, body)),
        ),
    ): OwnerAccessResult<HostActivation> = client.activateHost(
        owner = OWNER,
        firebaseIdToken = TOKEN,
        expectedGeneration = generation("0"),
    )

    private suspend fun verifyInstallation(
        body: String,
    ): OwnerAccessResult<VerifiedHostAccess> =
        OwnerAccessClient(
            ScriptedOwnerAccessTransport(response(200, body)),
        ).discoverInstallationAccess(
            accessToken = SecretValue.of("synthetic-installation-pat"),
            expectedContext = expectedHostContext(OWNER),
        )

    private fun expectedHostContext(owner: OwnerAccount): ExpectedHostAccessContext =
        ExpectedHostAccessContext(
            owner = owner,
            origin = "https://api.example.test",
            monitorId = "monitor-opaque",
            installationId = "installation-opaque",
            membershipId = "membership-opaque",
            generation = generation("1"),
        )

    private fun generation(decimal: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(decimal))

    private fun response(status: Int, body: String): OwnerAccessTransportResult.Response =
        OwnerAccessTransportResult.Response(
            statusCode = status,
            body = body.toByteArray(StandardCharsets.UTF_8),
        )

    private fun failure(result: OwnerAccessResult<*>): OwnerAccessResult.Failure {
        assertTrue("Expected a safe failure result", result is OwnerAccessResult.Failure)
        return result as OwnerAccessResult.Failure
    }

    private fun assertFailureReason(
        expected: OwnerAccessFailureReason,
        result: OwnerAccessResult<*>,
    ) {
        assertEquals(expected, failure(result).reason)
    }

    private class FakeHttpsConnection(
        url: URL,
        private val responseBody: ByteArray,
        private val responseStatusCode: Int = 200,
        private val inputFailure: IOException? = null,
        private val inputRuntimeFailure: RuntimeException? = null,
        private val requestPropertyFailure: RuntimeException? = null,
        private val blockedReadStarted: CompletableDeferred<Unit>? = null,
        private val disconnectSignal: CompletableDeferred<Unit>? = null,
    ) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        var connectThreadName: String? = null
        var disconnected = false
        var responseBytesRead = 0
        private val readRelease = CountDownLatch(1)
        private val requestProperties = mutableMapOf<String, String>()
        private val responseProperties = mutableMapOf<String, String>()

        override fun connect() {
            connectThreadName = Thread.currentThread().name
            connected = true
        }

        override fun disconnect() {
            disconnected = true
            readRelease.countDown()
            disconnectSignal?.complete(Unit)
        }

        override fun usingProxy(): Boolean = false

        override fun getCipherSuite(): String = "TLS_FAKE"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        override fun getPeerPrincipal(): Principal = throw SSLPeerUnverifiedException("synthetic")

        override fun getLocalPrincipal(): Principal? = null

        override fun getResponseCode(): Int = responseStatusCode

        override fun setRequestProperty(key: String, value: String) {
            requestPropertyFailure?.let { throw it }
            requestProperties[key] = value
        }

        override fun getRequestProperty(key: String): String? = requestProperties[key]

        override fun getInputStream(): InputStream {
            inputFailure?.let { throw it }
            inputRuntimeFailure?.let { throw it }
            blockedReadStarted?.complete(Unit)
            if (blockedReadStarted != null) {
                readRelease.await()
            }
            return object : ByteArrayInputStream(responseBody) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val bytesRead = super.read(buffer, offset, length)
                    if (bytesRead > 0) {
                        responseBytesRead += bytesRead
                    }
                    return bytesRead
                }

                override fun read(): Int {
                    val byte = super.read()
                    if (byte >= 0) {
                        responseBytesRead += 1
                    }
                    return byte
                }
            }
        }

        override fun getErrorStream(): InputStream? =
            if (responseStatusCode >= 400) ByteArrayInputStream(responseBody) else null

        override fun getOutputStream(): OutputStream = output

        fun setResponseProperty(name: String, value: String) {
            responseProperties[name] = value
        }

        override fun getHeaderField(name: String): String? = responseProperties[name]
    }

    private class ScriptedOwnerAccessTransport(
        var response: OwnerAccessTransportResult,
        override val origin: String? = "https://api.example.test",
        override val configurationFailure: OwnerAccessFailureReason? = null,
    ) : OwnerAccessTransport {
        val calls = mutableListOf<Call>()

        override suspend fun execute(
            method: OwnerAccessMethod,
            path: OwnerAccessPath,
            credential: OwnerAccessCredential,
            body: String?,
        ): OwnerAccessTransportResult {
            calls += Call(method, path, credential, body)
            return response
        }

        data class Call(
            val method: OwnerAccessMethod,
            val path: OwnerAccessPath,
            val credential: OwnerAccessCredential,
            val body: String?,
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val SECRET_SENTINEL = "synthetic-secret-server-message"
        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val TOKEN = SecretValue.of("synthetic-firebase-id-token")

        const val EMPTY_OWNER_MONITOR_FIXTURE =
            """{"data":{"monitor":null,"generation":"0","host":null,"requiresReference":true}}"""
        const val ACTIVATION_FIXTURE =
            """{"data":{"monitorId":"monitor-opaque","installationId":"installation-opaque","membershipId":"membership-opaque","role":"Host","generation":"1","requiresReference":true,"accessToken":"one-time-synthetic-pat","createdAt":1790000000000}}"""
        const val INSTALLATION_FIXTURE =
            """{"data":{"installationId":"installation-opaque","monitorId":"monitor-opaque","membershipId":"membership-opaque","role":"Host","status":"Active","monitorGeneration":"1"}}"""
    }
}
