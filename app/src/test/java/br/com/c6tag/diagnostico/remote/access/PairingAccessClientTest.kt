package br.com.c6tag.diagnostico.remote.access

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAccessClientTest {
    @Test
    fun issueInvitationUsesHostLeaseAndParsesOneTimeSecret() = runBlocking {
        val transport = FakePairingAccessTransport(response(201, ISSUE_FIXTURE))

        val result = PairingAccessClient(transport).issueInvitation(HOST_LEASE)

        val invitation = success(result)
        assertEquals(INVITATION_ID, invitation.id)
        assertEquals(INVITATION_SECRET, invitation.invitationSecret.reveal())
        assertEquals(1_795_132_800_000L, invitation.createdAtMillis)
        assertEquals(1_795_133_700_000L, invitation.expiresAtMillis)
        val call = transport.calls.single()
        assertEquals(PairingAccessMethod.POST, call.operation.method)
        assertEquals("/api/v1/monitors/$MONITOR_ID/invitations", call.operation.path)
        assertEquals(PairingAccessOperation.IssueInvitation(MONITOR_ID), call.operation)
        assertHostCredential(call.credential)
        assertEquals("{}", call.body?.utf8())
    }

    @Test
    fun listInvitationsAcceptsNonEmptyNativeArrayAndNeverReturnsSecret() = runBlocking {
        val transport = FakePairingAccessTransport(
            response(200, """{"data":[{"id":"$INVITATION_ID","status":"Available","createdAt":10,"expiresAt":20}]}"""),
        )

        val invitations = success(PairingAccessClient(transport).listInvitations(HOST_LEASE))

        assertEquals(1, invitations.size)
        assertEquals(INVITATION_ID, invitations.single().id)
        assertEquals("Available", invitations.single().status)
        assertEquals(10L, invitations.single().createdAtMillis)
        assertEquals(20L, invitations.single().expiresAtMillis)
        assertEquals(PairingAccessMethod.GET, transport.calls.single().operation.method)
        assertNull(transport.calls.single().body)
        assertFalse(invitations.toString().contains(INVITATION_SECRET))
    }

    @Test
    fun listInvitationsRejectsUnknownStatus() = runBlocking {
        val result = PairingAccessClient(
            FakePairingAccessTransport(
                response(
                    200,
                    """{"data":[{"id":"$INVITATION_ID","status":"Unknown","createdAt":10,"expiresAt":20}]}""",
                ),
            ),
        ).listInvitations(HOST_LEASE)

        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
    }

    @Test
    fun cancelInvitationUsesUuidPathAndRequiresEmpty204() = runBlocking {
        val transport = FakePairingAccessTransport(response(204))

        val result = PairingAccessClient(transport).cancelInvitation(HOST_LEASE, INVITATION_ID)

        assertTrue(result is PairingAccessResult.Success)
        val call = transport.calls.single()
        assertEquals(PairingAccessMethod.DELETE, call.operation.method)
        assertEquals(
            "/api/v1/monitors/$MONITOR_ID/invitations/$INVITATION_ID",
            call.operation.path,
        )
        assertNull(call.body)
        assertHostCredential(call.credential)

        val nonempty = FakePairingAccessTransport(response(204, "{}"))
        val invalid = failure(PairingAccessClient(nonempty).cancelInvitation(HOST_LEASE, INVITATION_ID))
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, invalid.reason)
        assertEquals(PairingWriteDisposition.AMBIGUOUS, invalid.writeDisposition)
    }

    @Test
    fun redeemInvitationSendsOnlyTheInvitationSecretWithoutAuthorizationCredential() = runBlocking {
        val transport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))

        val candidate = success(
            PairingAccessClient(transport).redeemInvitation(INVITATION_SECRET, "Kitchen phone"),
        )

        assertEquals(REQUEST_ID, candidate.requestId)
        assertEquals(INSTALLATION_ID, candidate.installationId)
        assertEquals("Pending", candidate.status)
        assertEquals(1_795_133_700_000L, candidate.requestExpiresAtMillis)
        assertEquals(SUPPORT_PAT, candidate.accessToken.reveal())
        assertEquals("[REDACTED]", candidate.accessToken.toString())
        assertFalse(candidate.toString().contains(SUPPORT_PAT))
        val call = transport.calls.single()
        assertEquals(PairingAccessMethod.POST, call.operation.method)
        assertEquals("/api/v1/pairing-requests", call.operation.path)
        assertNull(call.credential)
        assertEquals(
            """{"invitationSecret":"$INVITATION_SECRET","installationLabel":"Kitchen phone"}""",
            call.body?.utf8(),
        )
    }

    @Test
    fun redemptionOmitsEmptyLabelAndTrimsOnlyOuterSecretWhitespace() = runBlocking {
        val transport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))

        success(PairingAccessClient(transport).redeemInvitation(" \t$INVITATION_SECRET\n", ""))

        assertEquals(
            """{"invitationSecret":"$INVITATION_SECRET"}""",
            transport.calls.single().body?.utf8(),
        )
        assertNull(transport.calls.single().credential)
    }

    @Test
    fun redemptionJsonEscapesUnicodeAndAcceptsExactly128CodePoints() = runBlocking {
        val prefix = "á😀\"\\"
        val label = prefix + "x".repeat(128 - prefix.codePointCount(0, prefix.length))
        val transport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))

        success(PairingAccessClient(transport).redeemInvitation(INVITATION_SECRET, label))

        val sent = requireNotNull(transport.calls.single().body).utf8()
        assertTrue(sent.contains("installationLabel"))
        assertTrue(sent.contains("á😀"))
        assertTrue(sent.contains("\\\""))
        assertTrue(sent.contains("\\\\"))
        assertEquals(128, label.codePointCount(0, label.length))
    }

    @Test
    fun currentRequestParsesPendingWithoutLeakingMonitorOrRole() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, CURRENT_PENDING_FIXTURE))

        val current = success(PairingAccessClient(transport).currentRequest(CANDIDATE))

        assertEquals(REQUEST_ID, current.requestId)
        assertEquals("Pending", current.status)
        assertEquals(1_795_133_700_000L, current.requestExpiresAtMillis)
        assertNull(current.decidedAtMillis)
        assertNull(current.monitor)
        assertCandidateCredential(transport.calls.single().credential)
        assertEquals("/api/v1/pairing-requests/current", transport.calls.single().operation.path)
        assertNull(transport.calls.single().body)
    }

    @Test
    fun currentRequestParsesApprovedSupportMembership() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, CURRENT_APPROVED_FIXTURE))

        val current = success(PairingAccessClient(transport).currentRequest(CANDIDATE))

        assertEquals("Approved", current.status)
        assertEquals(1_795_132_800_000L, current.decidedAtMillis)
        assertEquals(MONITOR_ID, current.monitor?.id)
        assertEquals(MEMBERSHIP_ID, current.monitor?.membershipId)
        assertEquals("Support", current.monitor?.role)
    }

    @Test
    fun listRequestsParsesNonEmptyNativeArrayAndNullableInstallationLabel() = runBlocking {
        val transport = FakePairingAccessTransport(
            response(
                200,
                """{"data":[{"id":"$REQUEST_ID","status":"Pending","installation":{"id":"$INSTALLATION_ID","label":null},"createdAt":10,"requestExpiresAt":20}]}""",
            ),
        )

        val requests = success(PairingAccessClient(transport).listRequests(HOST_LEASE))

        assertEquals(1, requests.size)
        assertEquals(REQUEST_ID, requests.single().id)
        assertEquals(INSTALLATION_ID, requests.single().installationId)
        assertNull(requests.single().installationLabel)
        assertEquals(10L, requests.single().createdAtMillis)
        assertEquals(20L, requests.single().requestExpiresAtMillis)
        assertEquals(
            "/api/v1/monitors/$MONITOR_ID/pairing-requests",
            transport.calls.single().operation.path,
        )
    }

    @Test
    fun approveRequestUsesTargetUuidAndEmptyObjectAndChecksResponseTarget() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, APPROVAL_FIXTURE))

        val approval = success(PairingAccessClient(transport).approveRequest(HOST_LEASE, REQUEST_ID))

        assertEquals(REQUEST_ID, approval.requestId)
        assertEquals(MEMBERSHIP_ID, approval.membershipId)
        assertEquals("Approved", approval.status)
        assertEquals("Support", approval.role)
        assertEquals(1_795_132_800_000L, approval.decidedAtMillis)
        val call = transport.calls.single()
        assertEquals(PairingAccessMethod.POST, call.operation.method)
        assertEquals(
            "/api/v1/monitors/$MONITOR_ID/pairing-requests/$REQUEST_ID/approval",
            call.operation.path,
        )
        assertEquals("{}", call.body?.utf8())

        val mismatched = FakePairingAccessTransport(
            response(200, APPROVAL_FIXTURE.replace(REQUEST_ID, OTHER_REQUEST_ID)),
        )
        val invalid = failure(PairingAccessClient(mismatched).approveRequest(HOST_LEASE, REQUEST_ID))
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, invalid.reason)
        assertEquals(PairingWriteDisposition.AMBIGUOUS, invalid.writeDisposition)
    }

    @Test
    fun rejectRequestUsesTargetUuidAndParsesRejectedResult() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, REJECTION_FIXTURE))

        val rejection = success(PairingAccessClient(transport).rejectRequest(HOST_LEASE, REQUEST_ID))

        assertEquals(REQUEST_ID, rejection.requestId)
        assertEquals("Rejected", rejection.status)
        assertEquals(1_795_132_800_000L, rejection.decidedAtMillis)
        assertEquals(
            "/api/v1/monitors/$MONITOR_ID/pairing-requests/$REQUEST_ID/rejection",
            transport.calls.single().operation.path,
        )
        assertEquals("{}", transport.calls.single().body?.utf8())
    }

    @Test
    fun listSupportsParsesNonEmptyArrayWithActiveSupportOnly() = runBlocking {
        val transport = FakePairingAccessTransport(
            response(
                200,
                """{"data":[{"membershipId":"$MEMBERSHIP_ID","installationId":"$INSTALLATION_ID","label":"Kitchen phone","role":"Support","status":"Active","approvedAt":10}]}""",
            ),
        )

        val supports = success(PairingAccessClient(transport).listSupports(HOST_LEASE))

        assertEquals(1, supports.size)
        assertEquals(MEMBERSHIP_ID, supports.single().membershipId)
        assertEquals(INSTALLATION_ID, supports.single().installationId)
        assertEquals("Kitchen phone", supports.single().label)
        assertEquals("Support", supports.single().role)
        assertEquals("Active", supports.single().status)
        assertEquals(10L, supports.single().approvedAtMillis)
        assertEquals("/api/v1/monitors/$MONITOR_ID/supports", transport.calls.single().operation.path)
    }

    @Test
    fun revokeSupportUsesMembershipUuidAndRequiresEmpty204() = runBlocking {
        val transport = FakePairingAccessTransport(response(204))

        val result = PairingAccessClient(transport).revokeSupport(HOST_LEASE, MEMBERSHIP_ID)

        assertTrue(result is PairingAccessResult.Success)
        assertEquals(PairingAccessMethod.DELETE, transport.calls.single().operation.method)
        assertEquals(
            "/api/v1/monitors/$MONITOR_ID/supports/$MEMBERSHIP_ID",
            transport.calls.single().operation.path,
        )
        assertNull(transport.calls.single().body)
    }

    @Test
    fun supportInstallationAllowsLegitimateGenerationAdvanceAndReturnsObservedGeneration() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, SUPPORT_INSTALLATION_FIXTURE))
        val approved = success(
            PairingAccessClient(
                FakePairingAccessTransport(response(200, CURRENT_APPROVED_FIXTURE)),
            ).currentRequest(CANDIDATE),
        )

        val installation = success(
            PairingAccessClient(transport).validateSupportInstallation(CANDIDATE, approved),
        )

        assertEquals(INSTALLATION_ID, installation.installationId)
        assertEquals(MONITOR_ID, installation.monitorId)
        assertEquals(MEMBERSHIP_ID, installation.membershipId)
        assertEquals("2", installation.monitorGeneration.decimal)
        assertEquals("/api/v1/installation", transport.calls.single().operation.path)
        assertCandidateCredential(transport.calls.single().credential)
    }

    @Test
    fun supportInstallationRejectsWrongIdsRoleStatusAndGenerationEncoding() = runBlocking {
        val approved = success(
            PairingAccessClient(
                FakePairingAccessTransport(response(200, CURRENT_APPROVED_FIXTURE)),
            ).currentRequest(CANDIDATE),
        )
        val mismatchedIdFixtures = listOf(
            SUPPORT_INSTALLATION_FIXTURE.replace(INSTALLATION_ID, OTHER_INSTALLATION_ID),
            SUPPORT_INSTALLATION_FIXTURE.replace(MONITOR_ID, OTHER_MONITOR_ID),
            SUPPORT_INSTALLATION_FIXTURE.replace(MEMBERSHIP_ID, OTHER_MEMBERSHIP_ID),
        )

        mismatchedIdFixtures.forEach { fixture ->
            val result = PairingAccessClient(FakePairingAccessTransport(response(200, fixture)))
                .validateSupportInstallation(CANDIDATE, approved)
            assertEquals(PairingAccessFailureReason.CONTEXT_MISMATCH, failure(result).reason)
        }

        listOf(
            SUPPORT_INSTALLATION_FIXTURE.replace("Support", "Host"),
            SUPPORT_INSTALLATION_FIXTURE.replace("Active", "Pending"),
        ).forEach { fixture ->
            val result = PairingAccessClient(FakePairingAccessTransport(response(200, fixture)))
                .validateSupportInstallation(CANDIDATE, approved)
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        }

        val invalidGeneration = SUPPORT_INSTALLATION_FIXTURE.replace("\"2\"", "2")
        val generationFailure = PairingAccessClient(
            FakePairingAccessTransport(response(200, invalidGeneration)),
        ).validateSupportInstallation(CANDIDATE, approved)
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(generationFailure).reason)
    }

    @Test
    fun currentRequestCannotBeUsedForAnotherCandidateRequest() = runBlocking {
        val transport = FakePairingAccessTransport(response(200, SUPPORT_INSTALLATION_FIXTURE))
        val otherRequest = approvedCurrentRequest(OTHER_REQUEST_ID)

        val result = PairingAccessClient(transport).validateSupportInstallation(CANDIDATE, otherRequest)

        assertEquals(PairingAccessFailureReason.CONTEXT_MISMATCH, failure(result).reason)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun allThreeNativeListsAcceptTheirBackendLimitsAndRejectOneOver() = runBlocking {
        val invitationsAtLimit = (0 until 50).joinToString(",") { index ->
            invitationJson(index)
        }
        val requestsAtLimit = (0 until 50).joinToString(",") { index ->
            requestJson(index)
        }
        val supportsAtLimit = (0 until 3).joinToString(",") { index ->
            supportJson(index)
        }

        assertEquals(
            50,
            success(
                PairingAccessClient(
                    FakePairingAccessTransport(response(200, """{"data":[$invitationsAtLimit]}""")),
                ).listInvitations(HOST_LEASE),
            ).size,
        )
        assertEquals(
            50,
            success(
                PairingAccessClient(
                    FakePairingAccessTransport(response(200, """{"data":[$requestsAtLimit]}""")),
                ).listRequests(HOST_LEASE),
            ).size,
        )
        assertEquals(
            3,
            success(
                PairingAccessClient(
                    FakePairingAccessTransport(response(200, """{"data":[$supportsAtLimit]}""")),
                ).listSupports(HOST_LEASE),
            ).size,
        )

        val oversizedLists = listOf(
            PairingAccessClient(
                FakePairingAccessTransport(
                    response(200, """{"data":[$invitationsAtLimit,${invitationJson(51)}]}"""),
                ),
            ).listInvitations(HOST_LEASE),
            PairingAccessClient(
                FakePairingAccessTransport(
                    response(200, """{"data":[$requestsAtLimit,${requestJson(51)}]}"""),
                ),
            ).listRequests(HOST_LEASE),
            PairingAccessClient(
                FakePairingAccessTransport(
                    response(200, """{"data":[$supportsAtLimit,${supportJson(4)}]}"""),
                ),
            ).listSupports(HOST_LEASE),
        )
        oversizedLists.forEach { result ->
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        }
    }

    @Test
    fun strictParserRejectsMalformedDuplicateMissingTrailingInvalidUtf8AndOversizedBodies() = runBlocking {
        val invalidBodies = listOf(
            "{",
            """{"data":[{"id":"$INVITATION_ID","status":"Available","createdAt":10,"expiresAt":20}]} trailing""",
            """{"data":[{"id":"$INVITATION_ID","id":"$OTHER_REQUEST_ID","status":"Available","createdAt":10,"expiresAt":20}]}""",
            """{"data":[{"id":"$INVITATION_ID","status":"Available","createdAt":10,"expiresAt":20,"future":{"key":1,"key":2}}]}""",
            """{"data":[{"id":"$INVITATION_ID","status":"Available","createdAt":10}]}""",
        )
        invalidBodies.forEach { body ->
            val result = PairingAccessClient(
                FakePairingAccessTransport(response(200, body)),
            ).listInvitations(HOST_LEASE)
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        }

        val invalidUtf8 = PairingAccessClient(
            FakePairingAccessTransport(
                PairingAccessTransportResult.Response(200, byteArrayOf(0xC3.toByte(), 0x28)),
            ),
        ).listInvitations(HOST_LEASE)
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(invalidUtf8).reason)

        val oversized = PairingAccessClient(
            FakePairingAccessTransport(
                PairingAccessTransportResult.Response(
                    200,
                    ByteArray(MAX_PAIRING_ACCESS_RESPONSE_BYTES + 1) { 0x20 },
                ),
            ),
        ).listInvitations(HOST_LEASE)
        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(oversized).reason)
    }

    @Test
    fun responseFieldsRequireNativeTypesStatusesUuidsAndNonnegativeLongTimestamps() = runBlocking {
        val invalidFixtures = listOf(
            ISSUE_FIXTURE.replace("1795132800000", "\"1795132800000\""),
            ISSUE_FIXTURE.replace("1795132800000", "1795132800000.0"),
            ISSUE_FIXTURE.replace("1795132800000", "1e3"),
            ISSUE_FIXTURE.replace("1795132800000", "true"),
            ISSUE_FIXTURE.replace("1795132800000", "-1"),
            ISSUE_FIXTURE.replace("1795132800000", "9223372036854775808"),
            ISSUE_FIXTURE.replace(INVITATION_SECRET, "invalid-secret"),
            ISSUE_FIXTURE.replace(INVITATION_ID, "not-a-uuid"),
            ISSUE_FIXTURE.replace(",\"expiresAt\":1795133700000", ""),
        )
        invalidFixtures.forEach { fixture ->
            val result = PairingAccessClient(
                FakePairingAccessTransport(response(201, fixture)),
            ).issueInvitation(HOST_LEASE)
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
            assertEquals(PairingWriteDisposition.AMBIGUOUS, failure(result).writeDisposition)
        }
        val versionSevenUuid = ISSUE_FIXTURE.replace(
            INVITATION_ID,
            "00000000-0000-7000-8000-000000000001",
        )
        assertTrue(
            PairingAccessClient(
                FakePairingAccessTransport(response(201, versionSevenUuid)),
            ).issueInvitation(HOST_LEASE) is PairingAccessResult.Success,
        )

        val badGenerationValues = listOf(
            """"monitorGeneration":2""",
            """"monitorGeneration":"02"""",
            """"monitorGeneration":"-1"""",
            """"monitorGeneration":"9223372036854775808"""",
        )
        val approved = approvedCurrentRequest(REQUEST_ID)
        badGenerationValues.forEach { generationField ->
            val fixture = SUPPORT_INSTALLATION_FIXTURE.replace(""""monitorGeneration":"2"""", generationField)
            val result = PairingAccessClient(
                FakePairingAccessTransport(response(200, fixture)),
            ).validateSupportInstallation(CANDIDATE, approved)
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        }
    }

    @Test
    fun currentRequestRejectsPendingMonitorLeakAndInvalidApprovedShape() = runBlocking {
        val invalidFixtures = listOf(
            CURRENT_PENDING_FIXTURE.replace(""""monitor":null""", """"monitor":{"id":"$MONITOR_ID","membershipId":"$MEMBERSHIP_ID","role":"Support"}"""),
            CURRENT_PENDING_FIXTURE.replace(""""decidedAt":null""", """"decidedAt":10"""),
            CURRENT_APPROVED_FIXTURE.replace("Support", "Host"),
            CURRENT_APPROVED_FIXTURE.replace(MEMBERSHIP_ID, "bad-id"),
            CURRENT_APPROVED_FIXTURE.replace("Approved", "Rejected"),
        )
        invalidFixtures.forEach { fixture ->
            val result = PairingAccessClient(
                FakePairingAccessTransport(response(200, fixture)),
            ).currentRequest(CANDIDATE)
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, failure(result).reason)
        }
    }

    @Test
    fun invalidSecretLabelOriginCredentialAndPathIdsAreRejectedBeforeTransport() = runBlocking {
        val invalidSecrets = listOf("", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "*", "A".repeat(43) + "=")
        invalidSecrets.forEach { secret ->
            val transport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))
            val result = PairingAccessClient(transport).redeemInvitation(secret)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, failure(result).reason)
            assertEquals(PairingWriteDisposition.NOT_SENT, failure(result).writeDisposition)
            assertEquals(0, transport.calls.size)
        }

        val invalidLabels = listOf("x".repeat(129), "\uD800")
        invalidLabels.forEach { label ->
            val transport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))
            val result = PairingAccessClient(transport).redeemInvitation(INVITATION_SECRET, label)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, failure(result).reason)
            assertEquals(0, transport.calls.size)
        }

        val invalidHost = PairingHostLease(
            origin = "https://attacker.example",
            monitorId = MONITOR_ID,
            accessToken = SecretValue.of(HOST_PAT),
        )
        val originTransport = FakePairingAccessTransport(response(201, ISSUE_FIXTURE))
        val originFailure = failure(PairingAccessClient(originTransport).issueInvitation(invalidHost))
        assertEquals(PairingAccessFailureReason.CONTEXT_MISMATCH, originFailure.reason)
        assertEquals(PairingWriteDisposition.NOT_SENT, originFailure.writeDisposition)
        assertEquals(0, originTransport.calls.size)

        val unsafeHost = PairingHostLease(
            origin = ORIGIN,
            monitorId = MONITOR_ID,
            accessToken = SecretValue.of("unsafe\r\nBearer"),
        )
        val credentialTransport = FakePairingAccessTransport(response(201, ISSUE_FIXTURE))
        assertEquals(
            PairingAccessFailureReason.INVALID_CREDENTIAL,
            failure(PairingAccessClient(credentialTransport).issueInvitation(unsafeHost)).reason,
        )
        assertEquals(0, credentialTransport.calls.size)

        val invalidIdTransport = FakePairingAccessTransport(response(204))
        val invalidId = failure(
            PairingAccessClient(invalidIdTransport).cancelInvitation(HOST_LEASE, "$INVITATION_ID?x=1"),
        )
        assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, invalidId.reason)
        assertEquals(PairingWriteDisposition.NOT_SENT, invalidId.writeDisposition)
        assertEquals(0, invalidIdTransport.calls.size)

        val invalidOriginTransport = FakePairingAccessTransport(
            response(201, ISSUE_FIXTURE),
            origin = "https://api.example.test/path",
        )
        val invalidOrigin = failure(
            PairingAccessClient(invalidOriginTransport).issueInvitation(HOST_LEASE),
        )
        assertEquals(PairingAccessFailureReason.INVALID_ORIGIN, invalidOrigin.reason)
        assertEquals(PairingWriteDisposition.NOT_SENT, invalidOrigin.writeDisposition)
        assertEquals(0, invalidOriginTransport.calls.size)
    }

    @Test
    fun hostAndCandidateCredentialsAreBoundToTheirKindsOriginsAndContexts() = runBlocking {
        val invalidMonitor = PairingHostLease(
            origin = ORIGIN,
            monitorId = "$MONITOR_ID?query=1",
            accessToken = SecretValue.of(HOST_PAT),
        )
        val hostTransport = FakePairingAccessTransport(response(200, """{"data":[]}"""))
        val hostFailure = failure(PairingAccessClient(hostTransport).listSupports(invalidMonitor))
        assertEquals(PairingAccessFailureReason.CONTEXT_MISMATCH, hostFailure.reason)
        assertEquals(0, hostTransport.calls.size)

        val wrongCandidate = PairingCandidateInstallation(
            origin = "https://other.example.test",
            requestId = REQUEST_ID,
            installationId = INSTALLATION_ID,
            requestExpiresAtMillis = 10,
            accessToken = SecretValue.of(SUPPORT_PAT),
        )
        val candidateTransport = FakePairingAccessTransport(response(200, CURRENT_PENDING_FIXTURE))
        val candidateFailure = failure(
            PairingAccessClient(candidateTransport).currentRequest(wrongCandidate),
        )
        assertEquals(PairingAccessFailureReason.CONTEXT_MISMATCH, candidateFailure.reason)
        assertEquals(0, candidateTransport.calls.size)
    }

    @Test
    fun issueInvitationTreatsAnotherOperationConflictAsAmbiguousInvalidResponse() = runBlocking {
        val transport = FakePairingAccessTransport(
            response(
                409,
                """{"code":"invitation_already_consumed","message":"synthetic-untrusted-detail"}""",
            ),
        )

        val result = failure(PairingAccessClient(transport).issueInvitation(HOST_LEASE))

        assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, result.reason)
        assertEquals(PairingWriteDisposition.AMBIGUOUS, result.writeDisposition)
        assertNull(result.retryAfterSeconds)
        assertEquals(1, transport.calls.size)
        assertFalse(result.toString().contains("synthetic-untrusted-detail"))
    }

    @Test
    fun bodylessDeletesDoNotTreatJsonPayloadErrorsAsProofOfNoWrite() = runBlocking {
        val cases = listOf(
            KnownServerFailureCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID),
                400,
                "malformed_json",
            ),
            KnownServerFailureCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID),
                415,
                "unsupported_media_type",
            ),
            KnownServerFailureCase(
                PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID),
                422,
                "invalid_payload",
            ),
            KnownServerFailureCase(
                PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID),
                400,
                "malformed_json",
            ),
            KnownServerFailureCase(
                PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID),
                415,
                "unsupported_media_type",
            ),
            KnownServerFailureCase(
                PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID),
                422,
                "invalid_payload",
            ),
        )
        val mismatches = mutableListOf<String>()

        cases.forEach { case ->
            val transport = FakePairingAccessTransport(
                response(case.status, """{"code":"${case.code}","message":"safe"}"""),
            )
            val result = failure(invokeOperation(PairingAccessClient(transport), case.operation))
            val call = transport.calls.single()

            if (
                result.reason != PairingAccessFailureReason.INVALID_RESPONSE ||
                result.writeDisposition != PairingWriteDisposition.AMBIGUOUS ||
                result.retryAfterSeconds != null ||
                call.body != null ||
                transport.calls.size != 1
            ) {
                mismatches +=
                    "${case.operation.method} ${case.status}/${case.code}: " +
                        "${result.reason}/${result.writeDisposition}, body=${call.body != null}"
            }
        }

        assertTrue(
            "Bodyless DELETE payload errors must remain ambiguous: $mismatches",
            mismatches.isEmpty(),
        )
    }

    @Test
    fun publicRedemptionAcceptsFrameworkBadRequestAsKnownNotWritten() = runBlocking {
        val transport = FakePairingAccessTransport(
            response(400, """{"code":"bad_request","message":"Requisição inválida."}"""),
        )

        val result = failure(PairingAccessClient(transport).redeemInvitation(INVITATION_SECRET))

        assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, result.reason)
        assertEquals(PairingWriteDisposition.KNOWN_NOT_WRITTEN, result.writeDisposition)
        assertNull(result.retryAfterSeconds)
        assertEquals(1, transport.calls.size)
        assertNull(transport.calls.single().credential)
    }

    @Test
    fun knownServerFailuresAreScopedToEachContractedOperation() = runBlocking {
        val issue = PairingAccessOperation.IssueInvitation(MONITOR_ID)
        val listInvitations = PairingAccessOperation.ListInvitations(MONITOR_ID)
        val cancel = PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID)
        val redeem = PairingAccessOperation.RedeemInvitation
        val current = PairingAccessOperation.CurrentRequest
        val listRequests = PairingAccessOperation.ListRequests(MONITOR_ID)
        val approve = PairingAccessOperation.ApproveRequest(MONITOR_ID, REQUEST_ID)
        val reject = PairingAccessOperation.RejectRequest(MONITOR_ID, REQUEST_ID)
        val listSupports = PairingAccessOperation.ListSupports(MONITOR_ID)
        val revoke = PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID)
        val validate = PairingAccessOperation.ValidateSupportInstallation
        val hostOperations = listOf(
            issue,
            listInvitations,
            cancel,
            listRequests,
            approve,
            reject,
            listSupports,
            revoke,
        )
        val hostWrites = listOf(issue, cancel, approve, reject, revoke)
        val jsonBodyWrites = listOf(issue, approve, reject, redeem)
        val cases = mutableListOf<KnownServerFailureCase>()

        fun add(
            operation: PairingAccessOperation,
            status: Int,
            code: String,
            reason: PairingAccessFailureReason,
        ) {
            cases += KnownServerFailureCase(operation, status, code, reason)
        }

        hostOperations.forEach { operation ->
            add(operation, 401, "access_unauthenticated", PairingAccessFailureReason.NOT_AUTHENTICATED)
            add(operation, 401, "unauthenticated", PairingAccessFailureReason.NOT_AUTHENTICATED)
            add(operation, 403, "access_forbidden", PairingAccessFailureReason.ACCESS_FORBIDDEN)
            add(operation, 403, "access_denied", PairingAccessFailureReason.ACCESS_FORBIDDEN)
            add(operation, 404, "resource_not_found", PairingAccessFailureReason.RESOURCE_NOT_FOUND)
            add(operation, 404, "not_found", PairingAccessFailureReason.RESOURCE_NOT_FOUND)
        }
        jsonBodyWrites.forEach { operation ->
            add(operation, 400, "malformed_json", PairingAccessFailureReason.INVALID_PAYLOAD)
            add(operation, 415, "unsupported_media_type", PairingAccessFailureReason.UNSUPPORTED_MEDIA_TYPE)
            add(operation, 422, "invalid_payload", PairingAccessFailureReason.INVALID_PAYLOAD)
        }
        hostWrites.forEach { operation ->
            add(operation, 400, "bad_request", PairingAccessFailureReason.INVALID_PAYLOAD)
        }
        add(redeem, 400, "bad_request", PairingAccessFailureReason.INVALID_PAYLOAD)

        listOf(current, validate).forEach { operation ->
            add(operation, 401, "access_unauthenticated", PairingAccessFailureReason.NOT_AUTHENTICATED)
            add(operation, 401, "unauthenticated", PairingAccessFailureReason.NOT_AUTHENTICATED)
        }
        add(validate, 403, "access_forbidden", PairingAccessFailureReason.ACCESS_FORBIDDEN)
        add(validate, 403, "access_denied", PairingAccessFailureReason.ACCESS_FORBIDDEN)
        add(current, 404, "resource_not_found", PairingAccessFailureReason.RESOURCE_NOT_FOUND)
        add(current, 404, "not_found", PairingAccessFailureReason.RESOURCE_NOT_FOUND)

        add(issue, 429, "rate_limited", PairingAccessFailureReason.RATE_LIMITED)
        add(cancel, 409, "invitation_already_consumed", PairingAccessFailureReason.CONFLICT)
        add(redeem, 410, "invitation_unavailable", PairingAccessFailureReason.INVITATION_UNAVAILABLE)
        add(redeem, 429, "rate_limited", PairingAccessFailureReason.RATE_LIMITED)
        add(current, 410, "pairing_request_unavailable", PairingAccessFailureReason.INVITATION_UNAVAILABLE)
        add(current, 429, "rate_limited", PairingAccessFailureReason.RATE_LIMITED)
        add(approve, 409, "support_limit_reached", PairingAccessFailureReason.CONFLICT)
        add(approve, 409, "pairing_request_terminal", PairingAccessFailureReason.CONFLICT)
        add(approve, 410, "pairing_request_unavailable", PairingAccessFailureReason.INVITATION_UNAVAILABLE)
        add(reject, 409, "pairing_request_terminal", PairingAccessFailureReason.CONFLICT)
        add(reject, 410, "pairing_request_unavailable", PairingAccessFailureReason.INVITATION_UNAVAILABLE)

        cases.forEach { case ->
            val secretSentinel = "synthetic-untrusted-server-detail"
            val transport = FakePairingAccessTransport(
                response(
                    case.status,
                    """{"code":"${case.code}","message":"$secretSentinel","errors":{"field":["$secretSentinel"]}}""",
                    retryAfterSeconds = if (case.status == 429) 37 else 99,
                ),
            )

            val result = failure(invokeOperation(PairingAccessClient(transport), case.operation))

            assertEquals("${case.operation} ${case.status} ${case.code}", case.reason, result.reason)
            assertEquals(
                if (case.operation.method == PairingAccessMethod.GET) {
                    null
                } else {
                    PairingWriteDisposition.KNOWN_NOT_WRITTEN
                },
                result.writeDisposition,
            )
            assertEquals(if (case.status == 429) 37L else null, result.retryAfterSeconds)
            assertFalse(result.toString().contains(secretSentinel))
            assertFalse(result.reason.safeMessage.contains(secretSentinel))
        }
    }

    @Test
    fun operationIncompatibleKnownErrorsAreInvalidAndAmbiguous() = runBlocking {
        val issue = PairingAccessOperation.IssueInvitation(MONITOR_ID)
        val cancel = PairingAccessOperation.CancelInvitation(MONITOR_ID, INVITATION_ID)
        val redeem = PairingAccessOperation.RedeemInvitation
        val listInvitations = PairingAccessOperation.ListInvitations(MONITOR_ID)
        val current = PairingAccessOperation.CurrentRequest
        val listRequests = PairingAccessOperation.ListRequests(MONITOR_ID)
        val approve = PairingAccessOperation.ApproveRequest(MONITOR_ID, REQUEST_ID)
        val reject = PairingAccessOperation.RejectRequest(MONITOR_ID, REQUEST_ID)
        val listSupports = PairingAccessOperation.ListSupports(MONITOR_ID)
        val revoke = PairingAccessOperation.RevokeSupport(MONITOR_ID, MEMBERSHIP_ID)
        val validate = PairingAccessOperation.ValidateSupportInstallation
        val invalidCases = listOf(
            KnownServerFailureCase(issue, 409, "invitation_already_consumed"),
            KnownServerFailureCase(issue, 409, "support_limit_reached"),
            KnownServerFailureCase(issue, 409, "pairing_request_terminal"),
            KnownServerFailureCase(issue, 410, "invitation_unavailable"),
            KnownServerFailureCase(issue, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(cancel, 409, "support_limit_reached"),
            KnownServerFailureCase(cancel, 409, "pairing_request_terminal"),
            KnownServerFailureCase(cancel, 410, "invitation_unavailable"),
            KnownServerFailureCase(cancel, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(redeem, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(redeem, 409, "invitation_already_consumed"),
            KnownServerFailureCase(approve, 410, "invitation_unavailable"),
            KnownServerFailureCase(reject, 409, "support_limit_reached"),
            KnownServerFailureCase(reject, 410, "invitation_unavailable"),
            KnownServerFailureCase(revoke, 409, "invitation_already_consumed"),
            KnownServerFailureCase(revoke, 409, "support_limit_reached"),
            KnownServerFailureCase(revoke, 409, "pairing_request_terminal"),
            KnownServerFailureCase(revoke, 410, "invitation_unavailable"),
            KnownServerFailureCase(revoke, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(listInvitations, 409, "pairing_request_terminal"),
            KnownServerFailureCase(listInvitations, 415, "unsupported_media_type"),
            KnownServerFailureCase(listInvitations, 422, "invalid_payload"),
            KnownServerFailureCase(listRequests, 400, "bad_request"),
            KnownServerFailureCase(listRequests, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(listRequests, 429, "rate_limited"),
            KnownServerFailureCase(listSupports, 400, "malformed_json"),
            KnownServerFailureCase(listSupports, 409, "support_limit_reached"),
            KnownServerFailureCase(listSupports, 410, "pairing_request_unavailable"),
            KnownServerFailureCase(listSupports, 429, "rate_limited"),
            KnownServerFailureCase(current, 403, "access_forbidden"),
            KnownServerFailureCase(current, 410, "invitation_unavailable"),
            KnownServerFailureCase(validate, 404, "resource_not_found"),
            KnownServerFailureCase(validate, 409, "pairing_request_terminal"),
            KnownServerFailureCase(validate, 410, "invitation_unavailable"),
            KnownServerFailureCase(validate, 429, "rate_limited"),
            KnownServerFailureCase(issue, 503, "identity_verification_unavailable"),
        )

        invalidCases.forEach { case ->
            val transport = FakePairingAccessTransport(
                response(
                    case.status,
                    """{"code":"${case.code}","message":"synthetic-untrusted-server-detail"}""",
                    retryAfterSeconds = 37,
                ),
            )

            val result = failure(invokeOperation(PairingAccessClient(transport), case.operation))

            assertEquals("${case.operation} ${case.status} ${case.code}", PairingAccessFailureReason.INVALID_RESPONSE, result.reason)
            assertEquals(
                if (case.operation.method == PairingAccessMethod.GET) {
                    null
                } else {
                    PairingWriteDisposition.AMBIGUOUS
                },
                result.writeDisposition,
            )
            assertNull(result.retryAfterSeconds)
            assertEquals(1, transport.calls.size)
            assertFalse(result.toString().contains("synthetic-untrusted-server-detail"))
        }
    }

    @Test
    fun malformedUnknownAndServerErrorsAreAmbiguousAndNeverAutomaticallyRetried() = runBlocking {
        val invalidErrors = listOf(
            403 to """{"code":"unknown","message":"secret"}""",
            409 to "{",
            401 to """{"code":"access_unauthenticated","message":7}""",
            422 to """{"code":"invalid_payload","message":"x","code":"bad_request"}""",
            500 to """{"code":"internal_error","message":"synthetic-secret"}""",
            503 to """{"code":"identity_verification_unavailable","message":"synthetic-secret"}""",
            302 to """{"code":"access_forbidden","message":"synthetic-secret"}""",
        )
        invalidErrors.forEach { (status, body) ->
            val transport = FakePairingAccessTransport(response(status, body))

            val result = failure(PairingAccessClient(transport).issueInvitation(HOST_LEASE))

            assertEquals(
                if (status in 500..599) {
                    if (status == 500 && body.contains("internal_error")) {
                        PairingAccessFailureReason.SERVICE_UNAVAILABLE
                    } else {
                        PairingAccessFailureReason.INVALID_RESPONSE
                    }
                } else {
                    PairingAccessFailureReason.INVALID_RESPONSE
                },
                result.reason,
            )
            assertEquals(PairingWriteDisposition.AMBIGUOUS, result.writeDisposition)
            assertEquals(1, transport.calls.size)
            assertFalse(result.toString().contains("synthetic-secret"))
        }
    }

    @Test
    fun malformedRetryAfterIsIgnoredAndOnlyRateLimited429ExposesRetryMetadata() = runBlocking {
        val cases = listOf(
            429 to "rate_limited" to -1L,
            403 to "access_forbidden" to 10L,
        )
        cases.forEach { (statusAndCode, retry) ->
            val (status, code) = statusAndCode
            val result = failure(
                PairingAccessClient(
                    FakePairingAccessTransport(
                        response(status, """{"code":"$code","message":"Tente novamente"}""", retry),
                    ),
                ).issueInvitation(HOST_LEASE),
            )
            assertNull(result.retryAfterSeconds)
        }
    }

    @Test
    fun malformedSuccessAndUnexpectedEmptyResponsesAreAmbiguousWrites() = runBlocking {
        val cases = listOf(
            201 to "",
            201 to "{",
            200 to ISSUE_FIXTURE,
            204 to "{}",
        )
        cases.forEach { (status, body) ->
            val result = failure(
                PairingAccessClient(FakePairingAccessTransport(response(status, body)))
                    .issueInvitation(HOST_LEASE),
            )
            assertEquals(PairingAccessFailureReason.INVALID_RESPONSE, result.reason)
            assertEquals(PairingWriteDisposition.AMBIGUOUS, result.writeDisposition)
        }
    }

    @Test
    fun transportLossIsAmbiguousForWritesAndNeverRetried() = runBlocking {
        val transport = FakePairingAccessTransport(PairingAccessTransportResult.NetworkFailure)

        val result = failure(PairingAccessClient(transport).issueInvitation(HOST_LEASE))

        assertEquals(PairingAccessFailureReason.NETWORK_FAILURE, result.reason)
        assertEquals(PairingWriteDisposition.AMBIGUOUS, result.writeDisposition)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun dispatcherAndTransportRejectionsAreNotSentButReadFailuresHaveNoWriteDisposition() = runBlocking {
        listOf(
            PairingAccessTransportResult.DispatchRejected to PairingAccessFailureReason.NETWORK_FAILURE,
            PairingAccessTransportResult.RequestRejected to PairingAccessFailureReason.INVALID_REQUEST,
        ).forEach { (transportResult, expectedReason) ->
            val transport = FakePairingAccessTransport(transportResult)

            val result = failure(PairingAccessClient(transport).issueInvitation(HOST_LEASE))

            assertEquals(expectedReason, result.reason)
            assertEquals(PairingWriteDisposition.NOT_SENT, result.writeDisposition)
            assertEquals(1, transport.calls.size)
        }

        val readTransport = FakePairingAccessTransport(PairingAccessTransportResult.NetworkFailure)
        val readFailure = failure(PairingAccessClient(readTransport).listInvitations(HOST_LEASE))
        assertEquals(PairingAccessFailureReason.NETWORK_FAILURE, readFailure.reason)
        assertNull(readFailure.writeDisposition)
    }

    @Test
    fun cancellationPropagatesAndUnexpectedTransportBugsAreNotConvertedToFailures() {
        val cancellationTransport = FakePairingAccessTransport(
            response = PairingAccessTransportResult.NetworkFailure,
            failure = CancellationException("synthetic-cancellation"),
        )
        try {
            runBlocking {
                PairingAccessClient(cancellationTransport).issueInvitation(HOST_LEASE)
            }
            throw AssertionError("Cancellation must propagate")
        } catch (exception: CancellationException) {
            assertEquals("synthetic-cancellation", exception.message)
        }

        val bug = IllegalStateException("synthetic-programming-bug")
        val bugTransport = FakePairingAccessTransport(
            response = PairingAccessTransportResult.NetworkFailure,
            failure = bug,
        )
        try {
            runBlocking { PairingAccessClient(bugTransport).issueInvitation(HOST_LEASE) }
            throw AssertionError("Unexpected transport failures must propagate")
        } catch (exception: IllegalStateException) {
            assertTrue(exception === bug)
        }
    }

    @Test
    fun allSecretAndRawBodyCarriersHaveRedactedTextualRepresentations() = runBlocking {
        val responseBody = """{"data":{"accessToken":"$SUPPORT_PAT"}}"""
        val rawResponse = PairingAccessTransportResult.Response(
            201,
            responseBody.toByteArray(StandardCharsets.UTF_8),
        )
        val hostLeaseText = HOST_LEASE.toString()
        val requestTransport = FakePairingAccessTransport(response(201, REDEMPTION_FIXTURE))
        success(
            PairingAccessClient(requestTransport).redeemInvitation(INVITATION_SECRET),
        )
        val requestBody = requireNotNull(requestTransport.calls.single().body)
        val candidate = PairingCandidateInstallation(
            ORIGIN,
            REQUEST_ID,
            INSTALLATION_ID,
            20,
            SecretValue.of(SUPPORT_PAT),
        )
        val issue = success(
            PairingAccessClient(FakePairingAccessTransport(response(201, ISSUE_FIXTURE)))
                .issueInvitation(HOST_LEASE),
        )

        listOf(
            hostLeaseText,
            candidate.toString(),
            requestBody.toString(),
            rawResponse.toString(),
            issue.toString(),
            failure(
                PairingAccessClient(
                    FakePairingAccessTransport(
                        response(500, """{"code":"internal_error","message":"$SUPPORT_PAT"}"""),
                    ),
                ).issueInvitation(HOST_LEASE),
            ).toString(),
        ).forEach { text ->
            assertFalse(text.contains(HOST_PAT))
            assertFalse(text.contains(SUPPORT_PAT))
            assertFalse(text.contains(INVITATION_SECRET))
            assertFalse(text.contains("internal_error"))
        }
    }

    private suspend fun invokeOperation(
        client: PairingAccessClient,
        operation: PairingAccessOperation,
    ): PairingAccessResult<*> =
        when (operation) {
            is PairingAccessOperation.IssueInvitation -> client.issueInvitation(HOST_LEASE)
            is PairingAccessOperation.ListInvitations -> client.listInvitations(HOST_LEASE)
            is PairingAccessOperation.CancelInvitation ->
                client.cancelInvitation(HOST_LEASE, operation.invitationId)

            PairingAccessOperation.RedeemInvitation -> client.redeemInvitation(INVITATION_SECRET)
            PairingAccessOperation.CurrentRequest -> client.currentRequest(CANDIDATE)
            is PairingAccessOperation.ListRequests -> client.listRequests(HOST_LEASE)
            is PairingAccessOperation.ApproveRequest ->
                client.approveRequest(HOST_LEASE, operation.requestId)

            is PairingAccessOperation.RejectRequest ->
                client.rejectRequest(HOST_LEASE, operation.requestId)

            is PairingAccessOperation.ListSupports -> client.listSupports(HOST_LEASE)
            is PairingAccessOperation.RevokeSupport ->
                client.revokeSupport(HOST_LEASE, operation.membershipId)

            PairingAccessOperation.ValidateSupportInstallation ->
                client.validateSupportInstallation(
                    CANDIDATE,
                    PairingCurrentRequest(
                        requestId = REQUEST_ID,
                        status = "Approved",
                        requestExpiresAtMillis = 1_795_133_700_000L,
                        decidedAtMillis = 1_795_132_800_000L,
                        monitor = PairingApprovedMonitor(
                            id = MONITOR_ID,
                            membershipId = MEMBERSHIP_ID,
                            role = "Support",
                        ),
                    ),
                )
        }

    private data class KnownServerFailureCase(
        val operation: PairingAccessOperation,
        val status: Int,
        val code: String,
        val reason: PairingAccessFailureReason = PairingAccessFailureReason.INVALID_RESPONSE,
    )

    private fun assertHostCredential(credential: PairingAccessCredential?) {
        assertTrue(credential is PairingHostLease)
        credential as PairingHostLease
        assertEquals(ORIGIN, credential.origin)
        assertEquals(MONITOR_ID, credential.monitorId)
        assertEquals(HOST_PAT, credential.accessToken.reveal())
    }

    private fun assertCandidateCredential(credential: PairingAccessCredential?) {
        assertTrue(credential is PairingCandidateInstallation)
        credential as PairingCandidateInstallation
        assertEquals(ORIGIN, credential.origin)
        assertEquals(REQUEST_ID, credential.requestId)
        assertEquals(INSTALLATION_ID, credential.installationId)
        assertEquals(SUPPORT_PAT, credential.accessToken.reveal())
    }

    private suspend fun approvedCurrentRequest(requestId: String): PairingCurrentRequest =
        success(
            PairingAccessClient(
                FakePairingAccessTransport(
                    response(
                        200,
                        CURRENT_APPROVED_FIXTURE.replace(REQUEST_ID, requestId),
                    ),
                ),
            ).currentRequest(
                PairingCandidateInstallation(
                    ORIGIN,
                    requestId,
                    INSTALLATION_ID,
                    1_795_133_700_000L,
                    SecretValue.of(SUPPORT_PAT),
                ),
            ),
        )

    private fun invitationJson(index: Int): String =
        """{"id":"${uuid(index)}","status":"Available","createdAt":10,"expiresAt":20}"""

    private fun requestJson(index: Int): String =
        """{"id":"${uuid(index)}","status":"Pending","installation":{"id":"${uuid(index + 100)}","label":null},"createdAt":10,"requestExpiresAt":20}"""

    private fun supportJson(index: Int): String =
        """{"membershipId":"${uuid(index + 200)}","installationId":"${uuid(index + 300)}","label":null,"role":"Support","status":"Active","approvedAt":10}"""

    private fun uuid(index: Int): String = "%08x-0000-4000-8000-%012x".format(index, index)

    private fun response(
        status: Int,
        body: String = "",
        retryAfterSeconds: Long? = null,
    ): PairingAccessTransportResult.Response =
        PairingAccessTransportResult.Response(
            statusCode = status,
            body = body.toByteArray(StandardCharsets.UTF_8),
            retryAfterSeconds = retryAfterSeconds,
        )

    private fun <T> success(result: PairingAccessResult<T>): T {
        assertTrue("Expected successful pairing result, got $result", result is PairingAccessResult.Success)
        return (result as PairingAccessResult.Success).value
    }

    private fun failure(result: PairingAccessResult<*>): PairingAccessResult.Failure {
        assertTrue("Expected a safe pairing failure, got $result", result is PairingAccessResult.Failure)
        return result as PairingAccessResult.Failure
    }

    private class FakePairingAccessTransport(
        private val response: PairingAccessTransportResult,
        override val origin: String? = ORIGIN,
        override val configurationFailure: PairingAccessFailureReason? = null,
        private val failure: RuntimeException? = null,
    ) : PairingAccessTransport {
        val calls = mutableListOf<Call>()

        override suspend fun execute(
            operation: PairingAccessOperation,
            credential: PairingAccessCredential?,
            body: PairingAccessRequestBody?,
        ): PairingAccessTransportResult {
            calls += Call(operation, credential, body)
            failure?.let { throw it }
            return response
        }

        data class Call(
            val operation: PairingAccessOperation,
            val credential: PairingAccessCredential?,
            val body: PairingAccessRequestBody?,
        )
    }

    private companion object {
        const val ORIGIN = "https://api.example.test"
        const val MONITOR_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440005"
        const val MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440006"
        const val INVITATION_ID = "550e8400-e29b-41d4-a716-446655440003"
        const val REQUEST_ID = "550e8400-e29b-41d4-a716-446655440004"
        const val OTHER_REQUEST_ID = "650e8400-e29b-41d4-a716-446655440004"
        const val OTHER_MONITOR_ID = "650e8400-e29b-41d4-a716-446655440000"
        const val OTHER_INSTALLATION_ID = "650e8400-e29b-41d4-a716-446655440005"
        const val OTHER_MEMBERSHIP_ID = "650e8400-e29b-41d4-a716-446655440006"
        const val INVITATION_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val HOST_PAT = "1|synthetic-host-secret"
        const val SUPPORT_PAT = "1|synthetic-support-secret"

        val HOST_LEASE = PairingHostLease(ORIGIN, MONITOR_ID, SecretValue.of(HOST_PAT))
        val CANDIDATE = PairingCandidateInstallation(
            ORIGIN,
            REQUEST_ID,
            INSTALLATION_ID,
            1_795_133_700_000L,
            SecretValue.of(SUPPORT_PAT),
        )

        const val ISSUE_FIXTURE =
            """{"data":{"id":"$INVITATION_ID","invitationSecret":"$INVITATION_SECRET","createdAt":1795132800000,"expiresAt":1795133700000}}"""
        const val REDEMPTION_FIXTURE =
            """{"data":{"requestId":"$REQUEST_ID","installationId":"$INSTALLATION_ID","status":"Pending","requestExpiresAt":1795133700000,"accessToken":"$SUPPORT_PAT"}}"""
        const val CURRENT_PENDING_FIXTURE =
            """{"data":{"id":"$REQUEST_ID","status":"Pending","requestExpiresAt":1795133700000,"decidedAt":null,"monitor":null}}"""
        const val CURRENT_APPROVED_FIXTURE =
            """{"data":{"id":"$REQUEST_ID","status":"Approved","requestExpiresAt":1795133700000,"decidedAt":1795132800000,"monitor":{"id":"$MONITOR_ID","membershipId":"$MEMBERSHIP_ID","role":"Support"}}}"""
        const val APPROVAL_FIXTURE =
            """{"data":{"requestId":"$REQUEST_ID","status":"Approved","membershipId":"$MEMBERSHIP_ID","role":"Support","decidedAt":1795132800000}}"""
        const val REJECTION_FIXTURE =
            """{"data":{"requestId":"$REQUEST_ID","status":"Rejected","decidedAt":1795132800000}}"""
        const val SUPPORT_INSTALLATION_FIXTURE =
            """{"data":{"installationId":"$INSTALLATION_ID","monitorId":"$MONITOR_ID","membershipId":"$MEMBERSHIP_ID","role":"Support","status":"Active","monitorGeneration":"2"}}"""
    }
}
