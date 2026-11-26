package br.com.c6tag.diagnostico.remote.access

import br.com.c6tag.diagnostico.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.coroutines.CoroutineContext

class HttpsPairingAccessTransportTest {
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(30)

    @Test
    fun fixedOperationsUseOnlyTheirContractedHttpsRoutesAndCredentials() = runBlocking {
        val hostBody = requireNotNull(PairingAccessJson.encodeEmptyObject())
        val redemptionBody = requireNotNull(
            PairingAccessJson.encodeRedemption(INVITATION_SECRET, "Test installation"),
        )
        val operations = listOf(
            WireCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                HOST,
                hostBody,
                "POST",
                "/api/v1/monitors/$MONITOR_ID/invitations",
                201,
            ),
            WireCase(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                null,
                "GET",
                "/api/v1/monitors/$MONITOR_ID/invitations",
                200,
            ),
            WireCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID),
                HOST,
                null,
                "DELETE",
                "/api/v1/monitors/$MONITOR_ID/invitations/$INVITATION_ID",
                204,
            ),
            WireCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                redemptionBody,
                "POST",
                "/api/v1/pairing-requests",
                201,
            ),
            WireCase(
                PairingAccessOperation.CurrentRequest,
                CANDIDATE,
                null,
                "GET",
                "/api/v1/pairing-requests/current",
                200,
            ),
            WireCase(
                PairingAccessOperation.ListRequests(MONITOR_ID),
                HOST,
                null,
                "GET",
                "/api/v1/monitors/$MONITOR_ID/pairing-requests",
                200,
            ),
            WireCase(
                PairingAccessOperation.ApproveRequest(MONITOR_ID, REQUEST_ID),
                HOST,
                hostBody,
                "POST",
                "/api/v1/monitors/$MONITOR_ID/pairing-requests/$REQUEST_ID/approval",
                200,
            ),
            WireCase(
                PairingAccessOperation.RejectRequest(MONITOR_ID, REQUEST_ID),
                HOST,
                hostBody,
                "POST",
                "/api/v1/monitors/$MONITOR_ID/pairing-requests/$REQUEST_ID/rejection",
                200,
            ),
            WireCase(
                PairingAccessOperation.ListSupports(MONITOR_ID),
                HOST,
                null,
                "GET",
                "/api/v1/monitors/$MONITOR_ID/supports",
                200,
            ),
            WireCase(
                PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID),
                HOST,
                null,
                "DELETE",
                "/api/v1/monitors/$MONITOR_ID/supports/$MEMBERSHIP_ID",
                204,
            ),
            WireCase(
                PairingAccessOperation.ValidateSupportInstallation,
                CANDIDATE,
                null,
                "GET",
                "/api/v1/installation",
                200,
            ),
        )

        operations.forEach { case ->
            val connection = FakeHttpsConnection(
                url = URL("$ORIGIN${case.path}"),
                responseStatusCode = case.statusCode,
            )
            val openedUrls = mutableListOf<URL>()
            val transport = HttpsPairingAccessTransport(
                ownerApiOrigin = "https://API.EXAMPLE.TEST/",
                connectionFactory = HttpsConnectionFactory { url ->
                    openedUrls += url
                    connection
                },
            )

            val result = withTimeout(2_000) {
                transport.execute(case.operation, case.credential, case.body)
            }

            assertTrue("${case.operation}: $result", result is PairingAccessTransportResult.Response)
            assertEquals(1, openedUrls.size)
            assertEquals("$ORIGIN${case.path}", openedUrls.single().toString())
            assertEquals(case.method, connection.requestMethod)
            assertEquals(case.statusCode, (result as PairingAccessTransportResult.Response).statusCode)
            assertEquals("application/json", connection.getRequestProperty("Accept"))
            assertEquals(if (case.body == null) null else "application/json", connection.getRequestProperty("Content-Type"))
            assertEquals(case.body != null, connection.doOutput)
            assertEquals(case.body?.utf8().orEmpty(), connection.output.toString(Charsets.UTF_8.name()))
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(3_000, connection.connectTimeout)
            assertEquals(30_000, connection.readTimeout)
            assertEquals(false, connection.useCaches)
            assertTrue(connection.disconnected)

            when (case.credential) {
                null -> {
                    assertNull(connection.getRequestProperty("Authorization"))
                    assertNull(connection.getRequestProperty("Cookie"))
                    assertNull(connection.getRequestProperty("X-Firebase-UID"))
                    assertNull(connection.getRequestProperty("X-Firebase-Token"))
                }

                else -> assertEquals(
                    "Bearer ${case.credential.accessToken.reveal()}",
                    connection.getRequestProperty("Authorization"),
                )
            }
        }
        assertEquals(11, operations.size)
    }

    @Test
    fun emptyBuildOriginAndUnsafeOriginsAreRejectedBeforeOpeningConnections() = runBlocking {
        assertEquals("", BuildConfig.OWNER_API_ORIGIN)
        assertEquals(
            PairingAccessFailureReason.NOT_CONFIGURED,
            HttpsPairingAccessTransport().configurationFailure,
        )

        val origins = listOf(
            "",
            "http://api.example.test",
            "//api.example.test",
            "https://api.example.test/path",
            "https://api.example.test?query=1",
            "https://api.example.test#fragment",
            "https://user:secret@api.example.test",
            "https://api.example.test@attacker.example",
            "https://api.example.test:",
            "https://api.example.test:0",
            "https://api.example.test:65536",
            "https://api.example.test:invalid",
            " https://api.example.test",
            "https://api.example.test ",
        )
        origins.forEach { origin ->
            val openedConnections = AtomicInteger()
            val transport = HttpsPairingAccessTransport(
                ownerApiOrigin = origin,
                connectionFactory = HttpsConnectionFactory {
                    openedConnections.incrementAndGet()
                    error("Invalid origin must not open a connection")
                },
            )

            val result = transport.execute(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                null,
            )

            assertEquals(PairingAccessTransportResult.RequestRejected, result)
            assertEquals(0, openedConnections.get())
            assertEquals(
                if (origin.isEmpty()) PairingAccessFailureReason.NOT_CONFIGURED
                else PairingAccessFailureReason.INVALID_ORIGIN,
                transport.configurationFailure,
            )
        }
    }

    @Test
    fun invalidIdsCredentialsOperationsAndBodiesAreRejectedBeforeOpeningConnections() = runBlocking {
        val cases = listOf(
            RejectedCase(
                PairingAccessOperation.ListInvitations("$MONITOR_ID/extra"),
                HOST,
                null,
            ),
            RejectedCase(
                PairingAccessOperation.ListInvitations("$MONITOR_ID%2fextra"),
                HOST,
                null,
            ),
            RejectedCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, "$INVITATION_ID?x=1"),
                HOST,
                null,
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                hostLease(monitorId = OTHER_MONITOR_ID),
                PairingAccessJson.encodeEmptyObject(),
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                CANDIDATE,
                PairingAccessJson.encodeEmptyObject(),
            ),
            RejectedCase(
                PairingAccessOperation.CurrentRequest,
                HOST,
                null,
            ),
            RejectedCase(
                PairingAccessOperation.CurrentRequest,
                candidate(origin = "https://other.example.test"),
                null,
            ),
            RejectedCase(
                PairingAccessOperation.CurrentRequest,
                candidate(requestId = "$REQUEST_ID/extra"),
                null,
            ),
            RejectedCase(
                PairingAccessOperation.CurrentRequest,
                candidate(status = "Approved"),
                null,
            ),
            RejectedCase(
                PairingAccessOperation.CurrentRequest,
                candidate(requestExpiresAtMillis = -1),
                null,
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                hostLease(token = "bad\r\nX-Injected: true"),
                PairingAccessJson.encodeEmptyObject(),
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                hostLease(origin = "https://API.EXAMPLE.TEST/"),
                PairingAccessJson.encodeEmptyObject(),
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                HOST,
                null,
            ),
            RejectedCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                HOST,
                PairingAccessRequestBody(" { }".toByteArray()),
            ),
            RejectedCase(
                PairingAccessOperation.ApproveRequest(MONITOR_ID, REQUEST_ID),
                HOST,
                PairingAccessRequestBody("""{"extra":true}""".toByteArray()),
            ),
            RejectedCase(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                PairingAccessJson.encodeEmptyObject(),
            ),
            RejectedCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID),
                HOST,
                PairingAccessRequestBody(byteArrayOf()),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                HOST,
                PairingAccessJson.encodeRedemption(INVITATION_SECRET, ""),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"$INVITATION_SECRET","extra":1}""".toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"$INVITATION_SECRET","invitationSecret":"$INVITATION_SECRET"}"""
                        .toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody("""{"installationLabel":"Phone"}""".toByteArray()),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody("""{"invitationSecret":7}""".toByteArray()),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"$INVITATION_SECRET","installationLabel":null}"""
                        .toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"$INVITATION_SECRET","installationLabel":""}"""
                        .toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"$INVITATION_SECRET"} trailing""".toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(byteArrayOf(0xC3.toByte(), 0x28)),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody("""{"invitationSecret":"bad-secret"}""".toByteArray()),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(
                    """{"invitationSecret":"${"A".repeat(43)}","installationLabel":"${"x".repeat(129)}"}"""
                        .toByteArray(),
                ),
            ),
            RejectedCase(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(ByteArray(MAX_PAIRING_ACCESS_REQUEST_BYTES + 1)),
            ),
        )

        cases.forEach { case ->
            val openedConnections = AtomicInteger()
            val transport = HttpsPairingAccessTransport(
                ownerApiOrigin = ORIGIN,
                connectionFactory = HttpsConnectionFactory {
                    openedConnections.incrementAndGet()
                    error("Invalid request must not open a connection")
                },
            )

            val result = transport.execute(case.operation, case.credential, case.body)

            assertEquals("${case.operation} body=${case.body}", PairingAccessTransportResult.RequestRejected, result)
            assertEquals(0, openedConnections.get())
        }
    }

    @Test
    fun rejectsUnsafeAndOverlongBearerTokensBeforeOpeningConnectionsForHostsAndCandidates() = runBlocking {
        val invalidTokens = listOf(
            "x".repeat(MAX_BEARER_LENGTH + 1),
            "synthetic token",
            "synthetic-token ",
            "synthetic-\u007F",
            "synthetic-\u0000",
            "synthetic-é",
        )
        val emptyObject = PairingAccessJson.encodeEmptyObject()

        invalidTokens.forEach { token ->
            val cases = listOf(
                RejectedCase(
                    PairingAccessOperation.IssueInvitation(MONITOR_ID),
                    hostLease(token = token),
                    emptyObject,
                ),
                RejectedCase(
                    PairingAccessOperation.CurrentRequest,
                    candidate(token = token),
                    null,
                ),
            )

            cases.forEach { case ->
                val openedConnections = AtomicInteger()
                val transport = HttpsPairingAccessTransport(
                    ownerApiOrigin = ORIGIN,
                    connectionFactory = HttpsConnectionFactory {
                        openedConnections.incrementAndGet()
                        error("An unsafe bearer must be rejected before opening a connection")
                    },
                )

                val result = withTimeout(5_000) {
                    transport.execute(case.operation, case.credential, case.body)
                }

                assertEquals(PairingAccessTransportResult.RequestRejected, result)
                assertEquals(0, openedConnections.get())
            }
        }
    }

    @Test
    fun bearerTokensAtTheExactMaximumLengthAreSentForHostAndCandidate() = runBlocking {
        val cases = listOf(
            AuthenticatedRequestCase(
                PairingAccessOperation.IssueInvitation(MONITOR_ID),
                hostLease(token = "H".repeat(MAX_BEARER_LENGTH)),
                requireNotNull(PairingAccessJson.encodeEmptyObject()),
                "/api/v1/monitors/$MONITOR_ID/invitations",
                "H".repeat(MAX_BEARER_LENGTH),
            ),
            AuthenticatedRequestCase(
                PairingAccessOperation.CurrentRequest,
                candidate(token = "C".repeat(MAX_BEARER_LENGTH)),
                null,
                "/api/v1/pairing-requests/current",
                "C".repeat(MAX_BEARER_LENGTH),
            ),
        )

        cases.forEach { case ->
            val openedConnections = AtomicInteger()
            val connection = FakeHttpsConnection(URL("$ORIGIN${case.path}"))
            val transport = HttpsPairingAccessTransport(
                ownerApiOrigin = ORIGIN,
                connectionFactory = HttpsConnectionFactory {
                    openedConnections.incrementAndGet()
                    connection
                },
            )

            val result = withTimeout(5_000) {
                transport.execute(case.operation, case.credential, case.body)
            }

            assertTrue(result is PairingAccessTransportResult.Response)
            assertEquals(1, openedConnections.get())
            assertEquals(MAX_BEARER_LENGTH, case.token.length)
            assertEquals("Bearer ${case.token}", connection.getRequestProperty("Authorization"))
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun secretValueConstructionRejectsEmptyAndWhitespaceTokensBeforeAnyConnection() {
        val openedConnections = AtomicInteger()
        val connectionFactory = HttpsConnectionFactory {
            openedConnections.incrementAndGet()
            error("No credential can be created from a blank token")
        }
        HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = connectionFactory,
        )

        listOf("", " \t").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                SecretValue.of(token)
            }
        }
        assertEquals(0, openedConnections.get())
    }

    @Test
    fun redemptionAcceptsOnlyTheExactPublicJsonShapeAndDoesNotSendAuthorization() = runBlocking {
        val labelPrefix = "á😀\"\\"
        val label = labelPrefix + "x".repeat(
            128 - labelPrefix.codePointCount(0, labelPrefix.length),
        )
        val bodies = listOf(
            """{"invitationSecret":"$INVITATION_SECRET"}""",
            """{"installationLabel":"Kitchen 😀","invitationSecret":"$INVITATION_SECRET"}""",
            requireNotNull(PairingAccessJson.encodeRedemption(INVITATION_SECRET, label)).utf8(),
        )
        assertEquals(128, label.codePointCount(0, label.length))
        assertTrue(bodies.last().contains("á😀"))
        assertTrue(bodies.last().contains("\\\""))
        assertTrue(bodies.last().contains("\\\\"))

        bodies.forEach { body ->
            val connection = FakeHttpsConnection(
                url = URL("$ORIGIN/api/v1/pairing-requests"),
                responseStatusCode = 201,
            )
            val transport = HttpsPairingAccessTransport(
                ORIGIN,
                connectionFactory = HttpsConnectionFactory { connection },
            )
            val result = transport.execute(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessRequestBody(body.toByteArray(Charsets.UTF_8)),
            )

            assertTrue(result is PairingAccessTransportResult.Response)
            assertEquals(body, connection.output.toString(Charsets.UTF_8.name()))
            assertNull(connection.getRequestProperty("Authorization"))
            assertNull(connection.getRequestProperty("Cookie"))
            assertNull(connection.getRequestProperty("X-Firebase-UID"))
            assertNull(connection.getRequestProperty("X-Firebase-Token"))
            assertEquals("application/json", connection.getRequestProperty("Content-Type"))
        }
    }

    @Test
    fun everyOperationOpensAtMostOneConnectionAndHttpFailuresAreNotRetried() = runBlocking {
        val connectionCount = AtomicInteger()
        val transport = HttpsPairingAccessTransport(
            ORIGIN,
            connectionFactory = HttpsConnectionFactory { url ->
                connectionCount.incrementAndGet()
                FakeHttpsConnection(url, responseStatusCode = 503)
            },
        )
        val operations = listOf<Triple<PairingAccessOperation, PairingAccessCredential?, PairingAccessRequestBody?>>(
            Triple(PairingAccessOperation.IssueInvitation(MONITOR_ID), HOST, PairingAccessJson.encodeEmptyObject()),
            Triple(PairingAccessOperation.ListInvitations(MONITOR_ID), HOST, null),
            Triple(PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID), HOST, null),
            Triple(
                PairingAccessOperation.RedeemInvitation,
                null,
                PairingAccessJson.encodeRedemption(INVITATION_SECRET, ""),
            ),
            Triple(PairingAccessOperation.CurrentRequest, CANDIDATE, null),
            Triple(PairingAccessOperation.ListRequests(MONITOR_ID), HOST, null),
            Triple(PairingAccessOperation.ApproveRequest(MONITOR_ID, REQUEST_ID), HOST, PairingAccessJson.encodeEmptyObject()),
            Triple(PairingAccessOperation.RejectRequest(MONITOR_ID, REQUEST_ID), HOST, PairingAccessJson.encodeEmptyObject()),
            Triple(PairingAccessOperation.ListSupports(MONITOR_ID), HOST, null),
            Triple(PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID), HOST, null),
            Triple(PairingAccessOperation.ValidateSupportInstallation, CANDIDATE, null),
        )

        operations.forEach { (operation, credential, body) ->
            val before = connectionCount.get()
            transport.execute(operation, credential, body)
            assertEquals("$operation must not retry", 1, connectionCount.get() - before)
        }
        assertEquals(11, connectionCount.get())
    }

    @Test
    fun responsesAreBoundedByBytesAndKnownOversizedContentLengthIsRejectedEarly() = runBlocking {
        val oversized = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            responseBody = ByteArray(MAX_PAIRING_ACCESS_RESPONSE_BYTES + 1) { 0x20 },
            contentLength = -1,
        )
        val oversizedResult = transportFor(oversized).execute(
            PairingAccessOperation.ListInvitations(MONITOR_ID),
            HOST,
            null,
        )
        assertEquals(
            PairingAccessTransportResult.ResponseTooLarge(200),
            oversizedResult,
        )
        assertTrue(oversized.responseBytesRead <= MAX_PAIRING_ACCESS_RESPONSE_BYTES + 1)
        assertTrue(oversized.disconnected)

        val knownOversized = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            responseBody = ByteArray(0),
            contentLength = MAX_PAIRING_ACCESS_RESPONSE_BYTES.toLong() + 1,
        )
        val knownResult = transportFor(knownOversized).execute(
            PairingAccessOperation.ListInvitations(MONITOR_ID),
            HOST,
            null,
        )
        assertEquals(PairingAccessTransportResult.ResponseTooLarge(200), knownResult)
        assertEquals(0, knownOversized.responseBytesRead)
        assertTrue(knownOversized.disconnected)
    }

    @Test
    fun retryAfterIsParsedOnlyAsBoundedNonnegativeLongOn429() = runBlocking {
        val values = listOf(
            "37" to 37L,
            "9223372036854775807" to Long.MAX_VALUE,
            "" to null,
            "-1" to null,
            "+1" to null,
            "1.0" to null,
            "9223372036854775808" to null,
            "99999999999999999999" to null,
            "Thu, 01 Jan 1970 00:00:37 GMT" to null,
            " 37" to null,
        )
        values.forEach { (header, expected) ->
            val connection = FakeHttpsConnection(
                URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
                responseStatusCode = 429,
            ).apply {
                setResponseProperty("Retry-After", header)
            }
            val result = transportFor(connection).execute(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                null,
            )
            assertEquals(expected, (result as PairingAccessTransportResult.Response).retryAfterSeconds)
        }

        val non429 = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            responseStatusCode = 503,
        ).apply {
            setResponseProperty("Retry-After", "37")
        }
        val non429Result = transportFor(non429).execute(
            PairingAccessOperation.ListInvitations(MONITOR_ID),
            HOST,
            null,
        )
        assertNull((non429Result as PairingAccessTransportResult.Response).retryAfterSeconds)
    }

    @Test
    fun redirectIsReturnedWithoutFollowingAndPairingWriteRemainsAmbiguous() = runBlocking {
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            responseStatusCode = 302,
        ).apply {
            setResponseProperty("Location", "https://attacker.example/$INVITATION_SECRET")
        }
        val opens = AtomicInteger()
        val result = PairingAccessClient(
            HttpsPairingAccessTransport(
                ORIGIN,
                connectionFactory = HttpsConnectionFactory {
                    opens.incrementAndGet()
                    connection
                },
            ),
        ).issueInvitation(HOST)

        assertTrue(result is PairingAccessResult.Failure)
        val failure = result as PairingAccessResult.Failure
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure.reason)
        assertEquals(PairingWriteDisposition.AMBIGUOUS, failure.writeDisposition)
        assertEquals(1, opens.get())
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(failure.toString().contains(INVITATION_SECRET))
        assertTrue(connection.disconnected)
    }

    @Test
    fun connectionLossIsNetworkFailureAndRejectedDispatcherNeverOpensConnection() = runBlocking {
        val failingConnection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            inputFailure = IOException("synthetic connection loss"),
        )
        val result = transportFor(failingConnection).execute(
            PairingAccessOperation.ListInvitations(MONITOR_ID),
            HOST,
            null,
        )
        assertEquals(PairingAccessTransportResult.NetworkFailure, result)
        assertTrue(failingConnection.disconnected)

        val openedConnections = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                throw RejectedExecutionException("synthetic dispatcher rejection")
            }
        }
        val rejected = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            ioDispatcher = dispatcher,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                error("Rejected dispatch must not open a connection")
            },
        ).execute(PairingAccessOperation.IssueInvitation(MONITOR_ID), HOST, PairingAccessJson.encodeEmptyObject())

        assertEquals(PairingAccessTransportResult.DispatchRejected, rejected)
        assertEquals(0, openedConnections.get())
    }

    @Test
    fun cancellationBeforeDispatchAndDuringBlockedReadNeverLeavesAConnectionOpen() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val openedConnections = AtomicInteger()
        val beforeDispatch = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            ioDispatcher = dispatcher,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                error("Cancelled queued work must not open a connection")
            },
        )
        val queuedRequest = async {
            beforeDispatch.execute(PairingAccessOperation.ListInvitations(MONITOR_ID), HOST, null)
        }
        val queuedRunnable = withTimeout(5_000) { dispatcher.pending.await() }
        queuedRequest.cancelAndJoin()
        queuedRunnable.run()
        assertEquals(0, openedConnections.get())

        val readStarted = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val readFinished = CompletableDeferred<Unit>()
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            blockReadStarted = readStarted,
            disconnectSignal = disconnected,
            readFinished = readFinished,
        )
        val request = async {
            transportFor(connection).execute(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                null,
            )
        }

        try {
            withTimeout(5_000) { readStarted.await() }
            request.cancelAndJoin()
            withTimeout(5_000) {
                disconnected.await()
                readFinished.await()
            }
            assertTrue(connection.disconnected)
        } finally {
            request.cancel()
            connection.releaseBlockedOperations()
            withTimeoutOrNull(5_000) { readFinished.await() }
            withTimeoutOrNull(5_000) { request.join() }
        }
    }

    @Test
    fun cancellationDuringConnectDisconnectsAndDoesNotReturnASuccess() = runBlocking {
        assertCancellationDuring(BlockingStage.Connect)
    }

    @Test
    fun cancellationDuringResponseCodeDisconnectsAndDoesNotReturnASuccess() = runBlocking {
        assertCancellationDuring(BlockingStage.ResponseCode)
    }

    @Test
    fun unexpectedRuntimeFailuresPropagateAndOpenConnectionsAreDisconnected() {
        val programmingFailure = IllegalStateException("synthetic programming failure")
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            inputRuntimeFailure = programmingFailure,
        )
        val propagated = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    transportFor(connection).execute(
                        PairingAccessOperation.ListInvitations(MONITOR_ID),
                        HOST,
                        null,
                    )
                }
            }
        }
        assertEquals("synthetic programming failure", propagated.message)
        assertTrue(connection.disconnected)

        val factoryFailure = IllegalArgumentException("synthetic factory failure")
        val propagatedFactoryFailure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                HttpsPairingAccessTransport(
                    ORIGIN,
                    connectionFactory = HttpsConnectionFactory { throw factoryFailure },
                ).execute(PairingAccessOperation.ListInvitations(MONITOR_ID), HOST, null)
            }
        }
        assertEquals("synthetic factory failure", propagatedFactoryFailure.message)

        val cancellationFailure = assertThrows(CancellationException::class.java) {
            runBlocking {
                HttpsPairingAccessTransport(
                    ORIGIN,
                    connectionFactory = HttpsConnectionFactory {
                        throw CancellationException("synthetic transport cancellation")
                    },
                ).execute(PairingAccessOperation.ListInvitations(MONITOR_ID), HOST, null)
            }
        }
        assertEquals("synthetic transport cancellation", cancellationFailure.message)
    }

    @Test
    fun runtimeOperationFailureRemainsPrimaryWhenDisconnectAlsoFails() {
        val operationFailure = SyntheticRuntimeFailure("synthetic operation failure")
        val disconnectFailure = SyntheticRuntimeFailure("synthetic disconnect failure")
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            inputRuntimeFailure = operationFailure,
            disconnectRuntimeFailure = disconnectFailure,
        )
        val openedConnections = AtomicInteger()
        val transport = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                connection
            },
        )

        val propagated = assertThrows(RuntimeException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    transport.execute(
                        PairingAccessOperation.ListInvitations(MONITOR_ID),
                        HOST,
                        null,
                    )
                }
            }
        }

        assertSame(operationFailure, propagated)
        assertEquals(1, propagated.suppressed.size)
        assertSame(disconnectFailure, propagated.suppressed.single())
        assertEquals(1, openedConnections.get())
        assertTrue(connection.disconnected)
    }

    @Test
    fun disconnectFailurePropagatesWhenTheOperationSucceeded() {
        val disconnectFailure = SyntheticRuntimeFailure("synthetic disconnect failure")
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            disconnectRuntimeFailure = disconnectFailure,
        )
        val openedConnections = AtomicInteger()
        val transport = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                connection
            },
        )

        val propagated = assertThrows(RuntimeException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    transport.execute(
                        PairingAccessOperation.ListInvitations(MONITOR_ID),
                        HOST,
                        null,
                    )
                }
            }
        }

        assertSame(disconnectFailure, propagated)
        assertEquals(0, propagated.suppressed.size)
        assertEquals(1, openedConnections.get())
        assertTrue(connection.disconnected)
    }

    @Test
    fun sameOperationAndDisconnectFailureIsNotSelfSuppressed() {
        val sharedFailure = SyntheticRuntimeFailure("synthetic shared failure")
        val connection = FakeHttpsConnection(
            URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            inputRuntimeFailure = sharedFailure,
            disconnectRuntimeFailure = sharedFailure,
        )
        val openedConnections = AtomicInteger()
        val transport = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                connection
            },
        )

        val propagated = assertThrows(RuntimeException::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    transport.execute(
                        PairingAccessOperation.ListInvitations(MONITOR_ID),
                        HOST,
                        null,
                    )
                }
            }
        }

        assertSame(sharedFailure, propagated)
        assertEquals(0, propagated.suppressed.size)
        assertEquals(1, openedConnections.get())
        assertTrue(connection.disconnected)
    }

    private fun transportFor(connection: FakeHttpsConnection): HttpsPairingAccessTransport =
        HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = HttpsConnectionFactory { connection },
        )

    private suspend fun CoroutineScope.assertCancellationDuring(stage: BlockingStage) {
        val stageStarted = CompletableDeferred<Unit>()
        val stageFinished = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val connection = FakeHttpsConnection(
            url = URL("$ORIGIN/api/v1/monitors/$MONITOR_ID/invitations"),
            connectStarted = if (stage == BlockingStage.Connect) stageStarted else null,
            connectFinished = if (stage == BlockingStage.Connect) stageFinished else null,
            responseCodeStarted = if (stage == BlockingStage.ResponseCode) stageStarted else null,
            responseCodeFinished = if (stage == BlockingStage.ResponseCode) stageFinished else null,
            disconnectSignal = disconnected,
        )
        val openedConnections = AtomicInteger()
        val returnedResponses = AtomicInteger()
        val transport = HttpsPairingAccessTransport(
            ownerApiOrigin = ORIGIN,
            connectionFactory = HttpsConnectionFactory {
                openedConnections.incrementAndGet()
                connection
            },
        )
        val request = async {
            val result = transport.execute(
                PairingAccessOperation.ListInvitations(MONITOR_ID),
                HOST,
                null,
            )
            if (result is PairingAccessTransportResult.Response) {
                returnedResponses.incrementAndGet()
            }
            result
        }

        try {
            withTimeout(5_000) { stageStarted.await() }
            request.cancelAndJoin()
            withTimeout(5_000) {
                disconnected.await()
                stageFinished.await()
            }

            assertTrue(request.isCancelled)
            assertEquals(1, openedConnections.get())
            assertEquals(0, returnedResponses.get())
            assertTrue(connection.disconnected)
        } finally {
            request.cancel()
            connection.releaseBlockedOperations()
            withTimeoutOrNull(5_000) { stageFinished.await() }
            withTimeoutOrNull(5_000) { request.join() }
        }
    }

    private fun hostLease(
        origin: String = ORIGIN,
        monitorId: String = MONITOR_ID,
        token: String = HOST_PAT,
    ): PairingHostLease =
        PairingHostLease(origin, monitorId, SecretValue.of(token))

    private fun candidate(
        origin: String = ORIGIN,
        requestId: String = REQUEST_ID,
        installationId: String = INSTALLATION_ID,
        requestExpiresAtMillis: Long = 0,
        token: String = SUPPORT_PAT,
        status: String = "Pending",
    ): PairingCandidateInstallation =
        PairingCandidateInstallation(
            origin = origin,
            requestId = requestId,
            installationId = installationId,
            requestExpiresAtMillis = requestExpiresAtMillis,
            accessToken = SecretValue.of(token),
            status = status,
        )

    private data class WireCase(
        val operation: PairingAccessOperation,
        val credential: PairingAccessCredential?,
        val body: PairingAccessRequestBody?,
        val method: String,
        val path: String,
        val statusCode: Int,
    )

    private data class RejectedCase(
        val operation: PairingAccessOperation,
        val credential: PairingAccessCredential?,
        val body: PairingAccessRequestBody?,
    )

    private data class AuthenticatedRequestCase(
        val operation: PairingAccessOperation,
        val credential: PairingAccessCredential,
        val body: PairingAccessRequestBody?,
        val path: String,
        val token: String,
    )

    private enum class BlockingStage {
        Connect,
        ResponseCode,
    }

    private class SyntheticRuntimeFailure(message: String) : RuntimeException(message)

    private class QueuedDispatcher : CoroutineDispatcher() {
        val pending = CompletableDeferred<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.complete(block)
        }
    }

    private class FakeHttpsConnection(
        url: URL,
        private val responseBody: ByteArray = ByteArray(0),
        private val responseStatusCode: Int = 200,
        private val contentLength: Long = responseBody.size.toLong(),
        private val inputFailure: IOException? = null,
        private val inputRuntimeFailure: RuntimeException? = null,
        private val disconnectRuntimeFailure: RuntimeException? = null,
        private val blockReadStarted: CompletableDeferred<Unit>? = null,
        private val disconnectSignal: CompletableDeferred<Unit>? = null,
        private val readFinished: CompletableDeferred<Unit>? = null,
        private val connectStarted: CompletableDeferred<Unit>? = null,
        private val connectFinished: CompletableDeferred<Unit>? = null,
        private val responseCodeStarted: CompletableDeferred<Unit>? = null,
        private val responseCodeFinished: CompletableDeferred<Unit>? = null,
    ) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        @Volatile
        var disconnected = false
        var responseBytesRead = 0
        private val readRelease = CountDownLatch(1)
        private val connectRelease = CountDownLatch(1)
        private val responseCodeRelease = CountDownLatch(1)
        private val requestProperties = mutableMapOf<String, String>()
        private val responseProperties = mutableMapOf<String, String>()

        override fun connect() {
            connected = true
            connectStarted?.complete(Unit)
            if (connectStarted != null) {
                try {
                    connectRelease.await()
                } finally {
                    connectFinished?.complete(Unit)
                }
            }
        }

        override fun disconnect() {
            disconnected = true
            releaseBlockedOperations()
            disconnectSignal?.complete(Unit)
            disconnectRuntimeFailure?.let { throw it }
        }

        fun releaseBlockedOperations() {
            connectRelease.countDown()
            responseCodeRelease.countDown()
            readRelease.countDown()
        }

        override fun usingProxy(): Boolean = false

        override fun getCipherSuite(): String = "TLS_FAKE"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate> = emptyArray()

        override fun getPeerPrincipal(): Principal = throw SSLPeerUnverifiedException("synthetic")

        override fun getLocalPrincipal(): Principal? = null

        override fun getResponseCode(): Int {
            responseCodeStarted?.complete(Unit)
            if (responseCodeStarted != null) {
                try {
                    responseCodeRelease.await()
                } finally {
                    responseCodeFinished?.complete(Unit)
                }
            }
            return responseStatusCode
        }

        override fun getContentLengthLong(): Long = contentLength

        override fun setRequestProperty(key: String, value: String) {
            requestProperties[key] = value
        }

        override fun getRequestProperty(key: String): String? = requestProperties[key]

        override fun getInputStream(): InputStream {
            inputFailure?.let { throw it }
            inputRuntimeFailure?.let { throw it }
            return responseStream()
        }

        override fun getErrorStream(): InputStream? =
            if (responseStatusCode >= 400) responseStream() else null

        override fun getOutputStream(): OutputStream = output

        fun setResponseProperty(name: String, value: String) {
            responseProperties[name] = value
        }

        override fun getHeaderField(name: String): String? =
            if (name.equals("Content-Length", ignoreCase = true)) contentLength.toString()
            else responseProperties[name]

        private fun responseStream(): InputStream =
            object : ByteArrayInputStream(responseBody) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    blockReadStarted?.complete(Unit)
                    if (blockReadStarted != null) {
                        readRelease.await()
                    }
                    val bytesRead = super.read(buffer, offset, length)
                    if (bytesRead > 0) {
                        responseBytesRead += bytesRead
                    }
                    readFinished?.complete(Unit)
                    return bytesRead
                }

                override fun read(): Int {
                    blockReadStarted?.complete(Unit)
                    if (blockReadStarted != null) {
                        readRelease.await()
                    }
                    val byte = super.read()
                    if (byte >= 0) {
                        responseBytesRead += 1
                    }
                    readFinished?.complete(Unit)
                    return byte
                }
            }
    }

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val MONITOR_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val OTHER_MONITOR_ID = "650e8400-e29b-41d4-a716-446655440000"
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440005"
        const val MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440006"
        const val INVITATION_ID = "550e8400-e29b-41d4-a716-446655440003"
        const val REQUEST_ID = "550e8400-e29b-41d4-a716-446655440004"
        const val INVITATION_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val HOST_PAT = "1|synthetic-host-secret"
        const val SUPPORT_PAT = "1|synthetic-support-secret"
        const val MAX_BEARER_LENGTH = 8 * 1024

        val HOST = PairingHostLease(ORIGIN, MONITOR_ID, SecretValue.of(HOST_PAT))
        val CANDIDATE = PairingCandidateInstallation(
            origin = ORIGIN,
            requestId = REQUEST_ID,
            installationId = INSTALLATION_ID,
            requestExpiresAtMillis = 0,
            accessToken = SecretValue.of(SUPPORT_PAT),
        )
    }
}
