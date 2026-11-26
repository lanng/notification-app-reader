package br.com.c6tag.diagnostico.remote.access

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostSupportCoordinatorTest {
    @Test
    fun refreshRequiresValidLeaseAndPublishesUnavailableOrAuthorizationRequiredWithZeroHttp() =
        runBlocking {
            val transport = ScriptedHostTransport()
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                for (
                    failure in listOf(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                        HostAdministrationSessionFailure.SESSION_UNAVAILABLE,
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    )
                ) {
                    provider.acquireFailure = failure
                    coordinator.refresh()
                    assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                    assertEquals(failure, coordinator.state.value.sessionFailure)
                    assertFalse(coordinator.state.value.listSnapshotFresh)
                    assertFalse(coordinator.state.value.canRequestDecisions)
                    assertEquals(0, transport.totalCalls)
                }

                provider.acquireFailure = HostAdministrationSessionFailure.AUTHORIZATION_REJECTED
                coordinator.refresh()
                assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
                assertEquals(
                    HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    coordinator.state.value.sessionFailure,
                )
                assertFalse(coordinator.state.value.listSnapshotFresh)
                assertEquals(0, transport.totalCalls)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun refreshLoadsAllThreeListsWithValidatedLeaseAndRedactsSecretsInState() = runBlocking {
        val transport = ScriptedHostTransport { operation, credential, body ->
            val hostLease = credential as PairingHostLease
            assertEquals(HOST_PAT, hostLease.accessToken.reveal())
            assertNull(body)
            when (operation) {
                is PairingAccessOperation.ListInvitations ->
                    response(200, invitationsListJson(listOf(INVITATION_ID_1)))

                is PairingAccessOperation.ListRequests ->
                    response(
                        200,
                        requestsListJson(
                            listOf(
                                RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Tablet Sala", 1_700_000_100L),
                            ),
                        ),
                    )

                is PairingAccessOperation.ListSupports ->
                    response(
                        200,
                        supportsListJson(
                            listOf(
                                SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_2, "Celular Maria", 1_700_000_200L),
                            ),
                        ),
                    )

                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()

            val state = coordinator.state.value
            assertEquals(HostSupportPhase.READY, state.phase)
            assertTrue(state.listSnapshotFresh)
            assertTrue(state.canRequestDecisions)
            assertEquals(1, state.invitations.size)
            assertEquals(INVITATION_ID_1, state.invitations.single().id)
            assertEquals(1, state.pendingRequests.size)
            assertEquals(REQUEST_ID_1, state.pendingRequests.single().id)
            assertEquals(1, state.supports.size)
            assertEquals(MEMBERSHIP_ID_1, state.supports.single().membershipId)
            assertEquals(1, state.activeSupportCount)
            assertEquals(3, state.maxActiveSupports)
            assertFalse(state.isSupportLimitReached)
            assertNull(state.safeFailure)
            assertNull(state.sessionFailure)
            assertFalse(state.toString().contains(HOST_PAT))
            assertEquals(3, transport.totalCalls)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun issueInvitationHoldsSecretOnlyInMemoryAndCopyIsEliminatedOnLeaseSwitchInvalidationCancelOrClose() =
        runBlocking {
            var currentInvitations = mutableListOf<String>()
            val transport = ScriptedHostTransport { operation, _, body ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation -> {
                        assertEquals("{}", requireNotNull(body).utf8())
                        currentInvitations = mutableListOf(INVITATION_ID_1)
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )
                    }

                    is PairingAccessOperation.ListInvitations ->
                        response(200, invitationsListJson(currentInvitations))

                    is PairingAccessOperation.ListRequests ->
                        response(200, emptyArrayDataJson())

                    is PairingAccessOperation.ListSupports ->
                        response(200, emptyArrayDataJson())

                    is PairingAccessOperation.CancelInvitation -> {
                        currentInvitations.remove(operation.invitationId)
                        response(204, "")
                    }

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                coordinator.refresh()
                coordinator.issueInvitation()

                assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
                val stateAfterIssue = coordinator.state.value
                assertEquals(HostSupportPhase.READY, stateAfterIssue.phase)
                assertEquals(
                    HostEmittedInvitationNotice(
                        id = INVITATION_ID_1,
                        createdAtMillis = 1_700_000_000L,
                        expiresAtMillis = 1_700_900_000L,
                        copyAvailable = true,
                    ),
                    stateAfterIssue.emittedInvitation,
                )
                assertFalse(stateAfterIssue.toString().contains(INVITATION_SECRET_1))
                assertFalse(stateAfterIssue.toString().contains(HOST_PAT))

                val copied = coordinator.invitationForExplicitCopy(INVITATION_ID_1)
                assertNotNull(copied)
                assertEquals(INVITATION_SECRET_1, copied?.reveal())
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_2))

                // Listing on the same lease keeps copy only while invitation remains available.
                coordinator.refresh()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

                // Cancelling that invitation removes copy capability immediately.
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(1, transport.countFor<PairingAccessOperation.CancelInvitation>())
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

                // Re-issue, then switch lease on next refresh -> secret must NOT survive lease switch
                // and listing metadata must NOT reconstitute the secret.
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                provider.rotateActiveLease()
                coordinator.refresh()
                assertEquals(1, coordinator.state.value.invitations.size)
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

                // Re-issue, then invalidate lease before copy -> copy returns null and updates state.
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                provider.invalidateCurrentLeaseLocally(HostAdministrationSessionFailure.LEASE_INVALIDATED)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
                assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                assertNull(coordinator.state.value.emittedInvitation)

                // Re-issue on active lease, then close coordinator -> copy returns null.
                provider.resetActiveLease()
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                coordinator.close()
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun issueInvitationKeepsCopyAvailableIfFollowUpMetadataRefreshFailsOffline() = runBlocking {
        var failListInvitations = false
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.IssueInvitation -> {
                    failListInvitations = true
                    response(
                        201,
                        issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                    )
                }

                is PairingAccessOperation.ListInvitations ->
                    if (failListInvitations) {
                        PairingAccessTransportResult.NetworkFailure
                    } else {
                        response(200, emptyArrayDataJson())
                    }

                is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            coordinator.issueInvitation()

            val state = coordinator.state.value
            assertEquals(HostSupportPhase.STALE, state.phase)
            assertFalse(state.listSnapshotFresh)
            assertEquals(PairingAccessFailureReason.NETWORK_FAILURE, state.safeFailure)
            assertEquals(true, state.emittedInvitation?.copyAvailable)
            assertEquals(
                INVITATION_SECRET_1,
                coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal(),
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun decisionsBindTargetIdsOverIdenticalLabelsAndRejectStaleDuplicateOrCancelledConfirmations() =
        runBlocking {
            val nextId = AtomicInteger(1)
            val requests = mutableListOf(
                RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Celular Igual", 1_700_000_100L),
                RequestFixture(REQUEST_ID_2, INSTALLATION_ID_2, "Celular Igual", 1_700_000_200L),
            )
            val supports = mutableListOf(
                SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_3, "Celular Igual", 1_700_000_050L),
            )
            val approvedTargets = mutableListOf<String>()
            val rejectedTargets = mutableListOf<String>()
            val revokedTargets = mutableListOf<String>()

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListRequests -> response(200, requestsListJson(requests))
                    is PairingAccessOperation.ListSupports -> response(200, supportsListJson(supports))
                    is PairingAccessOperation.ApproveRequest -> {
                        approvedTargets += operation.requestId
                        val removed = requests.first { it.id == operation.requestId }
                        requests.remove(removed)
                        supports += SupportFixture(
                            MEMBERSHIP_ID_2,
                            removed.installationId,
                            removed.label,
                            1_700_000_300L,
                        )
                        response(200, approvalJson(operation.requestId, MEMBERSHIP_ID_2, 1_700_000_300L))
                    }

                    is PairingAccessOperation.RejectRequest -> {
                        rejectedTargets += operation.requestId
                        requests.removeAll { it.id == operation.requestId }
                        response(200, rejectionJson(operation.requestId, 1_700_000_400L))
                    }

                    is PairingAccessOperation.RevokeSupport -> {
                        revokedTargets += operation.membershipId
                        supports.removeAll { it.membershipId == operation.membershipId }
                        response(204, "")
                    }

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
                decisionIdGenerator = { "decision-${nextId.getAndIncrement()}" },
            )

            try {
                coordinator.refresh()

                // Two requests with identical labels produce distinct confirmations bound by targetId & installationId.
                val firstConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )
                val secondConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_2,
                )
                assertNotNull(firstConfirm)
                assertNotNull(secondConfirm)
                assertNotEquals(firstConfirm!!.decisionId, secondConfirm!!.decisionId)
                assertEquals(REQUEST_ID_2, secondConfirm.targetId)
                assertEquals(INSTALLATION_ID_2, secondConfirm.installationId)
                assertEquals(1_700_000_200L, secondConfirm.targetTimestampMillis)
                assertEquals(secondConfirm, coordinator.state.value.pendingConfirmation)

                // Old/superseded decisionId sends ZERO HTTP.
                val callsBefore = transport.totalCalls
                coordinator.confirmDecision(firstConfirm.decisionId)
                coordinator.confirmDecision("unknown-id")
                assertEquals(callsBefore, transport.totalCalls)

                // Cancel decision sends ZERO HTTP and invalidates confirmation.
                coordinator.cancelDecision()
                assertNull(coordinator.state.value.pendingConfirmation)
                coordinator.confirmDecision(secondConfirm.decisionId)
                assertEquals(callsBefore, transport.totalCalls)

                // Refresh invalidates any open confirmation.
                val preRefreshConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_2,
                )!!
                coordinator.refresh()
                assertNull(coordinator.state.value.pendingConfirmation)
                val callsAfterRefresh = transport.totalCalls
                coordinator.confirmDecision(preRefreshConfirm.decisionId)
                assertEquals(callsAfterRefresh, transport.totalCalls)

                // Confirm approval of REQUEST_ID_2 specifically (same label as REQUEST_ID_1).
                val approveConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_2,
                )!!
                coordinator.confirmDecision(approveConfirm.decisionId)
                assertEquals(listOf(REQUEST_ID_2), approvedTargets)
                assertEquals(2, coordinator.state.value.supports.size)

                // Replaying consumed decisionId sends ZERO HTTP.
                val callsAfterApprove = transport.totalCalls
                coordinator.confirmDecision(approveConfirm.decisionId)
                assertEquals(callsAfterApprove, transport.totalCalls)

                // Confirm rejection of REQUEST_ID_1.
                val rejectConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.REJECT_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(rejectConfirm.decisionId)
                assertEquals(listOf(REQUEST_ID_1), rejectedTargets)
                assertTrue(coordinator.state.value.pendingRequests.isEmpty())

                // Confirm revocation of MEMBERSHIP_ID_1.
                val revokeConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.REVOKE_SUPPORT,
                    MEMBERSHIP_ID_1,
                )!!
                assertEquals(INSTALLATION_ID_3, revokeConfirm.installationId)
                coordinator.confirmDecision(revokeConfirm.decisionId)
                assertEquals(listOf(MEMBERSHIP_ID_1), revokedTargets)
                assertEquals(1, coordinator.state.value.supports.size)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun capacityLimitThreeBlocksApprovalAndKnownNotWrittenServerConflictsRefreshWithoutRetry() =
        runBlocking {
            var invitations = listOf(INVITATION_ID_1)
            var requests = listOf(
                RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Novo Aparelho", 1_700_000_100L),
            )
            var supports = listOf(
                SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_2, "S1", 1_700_000_010L),
                SupportFixture(MEMBERSHIP_ID_2, INSTALLATION_ID_3, "S2", 1_700_000_020L),
                SupportFixture(MEMBERSHIP_ID_3, INSTALLATION_ID_4, "S3", 1_700_000_030L),
            )
            var approveResponse: () -> PairingAccessTransportResult = {
                error("Unexpected approve")
            }
            var rejectResponse: () -> PairingAccessTransportResult = {
                error("Unexpected reject")
            }
            var cancelResponse: () -> PairingAccessTransportResult = {
                error("Unexpected cancel")
            }
            var issueResponse: () -> PairingAccessTransportResult = {
                error("Unexpected issue")
            }

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> response(200, invitationsListJson(invitations))
                    is PairingAccessOperation.ListRequests -> response(200, requestsListJson(requests))
                    is PairingAccessOperation.ListSupports -> response(200, supportsListJson(supports))
                    is PairingAccessOperation.ApproveRequest -> approveResponse()
                    is PairingAccessOperation.RejectRequest -> rejectResponse()
                    is PairingAccessOperation.CancelInvitation -> cancelResponse()
                    is PairingAccessOperation.IssueInvitation -> issueResponse()
                    is PairingAccessOperation.RevokeSupport -> {
                        supports = supports.filterNot { it.membershipId == operation.membershipId }
                        errorResponse(404, "resource_not_found")
                    }
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                coordinator.refresh()
                assertTrue(coordinator.state.value.isSupportLimitReached)
                assertEquals(3, coordinator.state.value.activeSupportCount)

                // Local capacity gate prevents opening approval confirmation when 3 supports are active.
                val blocked = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )
                assertNull(blocked)
                assertEquals(PairingAccessFailureReason.CONFLICT, coordinator.state.value.safeFailure)
                assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
                assertEquals(0, transport.countFor<PairingAccessOperation.ApproveRequest>())

                // Rejection and revocation are still allowed at capacity 3.
                assertNotNull(
                    coordinator.requestDecision(HostSupportDecisionAction.REJECT_REQUEST, REQUEST_ID_1),
                )
                coordinator.cancelDecision()
                assertNotNull(
                    coordinator.requestDecision(HostSupportDecisionAction.REVOKE_SUPPORT, MEMBERSHIP_ID_1),
                )
                coordinator.cancelDecision()

                // Now server has 2 supports, then races to 3 and returns 409 support_limit_reached.
                supports = supports.take(2)
                coordinator.refresh()
                assertFalse(coordinator.state.value.isSupportLimitReached)
                approveResponse = {
                    supports = listOf(
                        SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_2, "S1", 1_700_000_010L),
                        SupportFixture(MEMBERSHIP_ID_2, INSTALLATION_ID_3, "S2", 1_700_000_020L),
                        SupportFixture(MEMBERSHIP_ID_3, INSTALLATION_ID_4, "S3", 1_700_000_030L),
                    )
                    errorResponse(409, "support_limit_reached")
                }
                val approveConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(approveConfirm.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
                assertTrue(coordinator.state.value.isSupportLimitReached)
                assertEquals(PairingAccessFailureReason.CONFLICT, coordinator.state.value.safeFailure)

                // 409 pairing_request_terminal on approve refreshes lists without retry.
                supports = supports.take(2)
                coordinator.refresh()
                approveResponse = {
                    requests = emptyList()
                    errorResponse(409, "pairing_request_terminal")
                }
                val terminalConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(terminalConfirm.decisionId)
                assertEquals(2, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(PairingAccessFailureReason.CONFLICT, coordinator.state.value.safeFailure)
                assertTrue(coordinator.state.value.pendingRequests.isEmpty())

                // 410 pairing_request_unavailable on reject refreshes lists without retry.
                requests = listOf(
                    RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Novo Aparelho", 1_700_000_100L),
                )
                coordinator.refresh()
                rejectResponse = {
                    requests = emptyList()
                    errorResponse(410, "pairing_request_unavailable")
                }
                val rejectConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.REJECT_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(rejectConfirm.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.RejectRequest>())
                assertEquals(
                    PairingAccessFailureReason.INVITATION_UNAVAILABLE,
                    coordinator.state.value.safeFailure,
                )
                assertTrue(coordinator.state.value.pendingRequests.isEmpty())

                // 404 resource_not_found on revoke refreshes lists without retry.
                val revokeConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.REVOKE_SUPPORT,
                    MEMBERSHIP_ID_1,
                )!!
                coordinator.confirmDecision(revokeConfirm.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.RevokeSupport>())
                assertEquals(PairingAccessFailureReason.RESOURCE_NOT_FOUND, coordinator.state.value.safeFailure)
                assertEquals(1, coordinator.state.value.supports.size)

                // 409 invitation_already_consumed on cancel refreshes lists (reveals resulting request,
                // NEVER silently rejects it!).
                cancelResponse = {
                    invitations = emptyList()
                    requests = listOf(
                        RequestFixture(REQUEST_ID_2, INSTALLATION_ID_1, "Candidato", 1_700_000_500L),
                    )
                    errorResponse(409, "invitation_already_consumed")
                }
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(1, transport.countFor<PairingAccessOperation.CancelInvitation>())
                assertEquals(0, transport.countFor<PairingAccessOperation.RejectRequest>() - 1)
                assertEquals(PairingAccessFailureReason.CONFLICT, coordinator.state.value.safeFailure)
                assertTrue(coordinator.state.value.invitations.isEmpty())
                assertEquals(1, coordinator.state.value.pendingRequests.size)
                assertEquals(REQUEST_ID_2, coordinator.state.value.pendingRequests.single().id)

                // 429 rate_limited on issueInvitation preserves Retry-After and does NOT retry.
                issueResponse = { errorResponse(429, "rate_limited", retryAfterSeconds = 45L) }
                coordinator.issueInvitation()
                assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
                assertEquals(PairingAccessFailureReason.RATE_LIMITED, coordinator.state.value.safeFailure)
                assertEquals(45L, coordinator.state.value.retryAfterSeconds)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun uncertainIssueInvitationNeverRetriesClearsPreviousSecretAndRefreshesOrphanMetadata() =
        runBlocking {
            var invitations = listOf<String>()
            var issueAttempt = 0
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation -> {
                        issueAttempt += 1
                        if (issueAttempt == 1) {
                            invitations = listOf(INVITATION_ID_1)
                            response(
                                201,
                                issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                            )
                        } else {
                            // Server actually created INVITATION_ID_2, but response was lost!
                            invitations = listOf(INVITATION_ID_1, INVITATION_ID_2)
                            PairingAccessTransportResult.NetworkFailure
                        }
                    }

                    is PairingAccessOperation.ListInvitations -> response(200, invitationsListJson(invitations))
                    is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

                // Second issue loses its response -> must NOT retry POST, clears prior secret,
                // surfaces ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE, and refreshes metadata showing orphan.
                coordinator.issueInvitation()

                assertEquals(2, transport.countFor<PairingAccessOperation.IssueInvitation>())
                val state = coordinator.state.value
                assertEquals(HostSupportPhase.READY, state.phase)
                assertTrue(state.listSnapshotFresh)
                assertEquals(
                    HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                    state.reconciliationNotice?.kind,
                )
                assertNull(state.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_2))
                assertEquals(listOf(INVITATION_ID_1, INVITATION_ID_2), state.invitations.map { it.id })
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun uncertainApproveRequestReconcilesHonestlyAcrossConfirmedStillPendingAndUncertainWithoutRetry() =
        runBlocking {
            var requests = listOf(
                RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Device A", 1_700_000_100L),
            )
            var supports = emptyList<SupportFixture>()
            var onApprove: () -> PairingAccessTransportResult = { PairingAccessTransportResult.NetworkFailure }

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListRequests -> response(200, requestsListJson(requests))
                    is PairingAccessOperation.ListSupports -> response(200, supportsListJson(supports))
                    is PairingAccessOperation.ApproveRequest -> onApprove()
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                // Case A: disappeared from pending AND installationId present in fresh supports -> confirmed!
                coordinator.refresh()
                onApprove = {
                    requests = emptyList()
                    supports = listOf(
                        SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_1, "Device A", 1_700_000_200L),
                    )
                    PairingAccessTransportResult.NetworkFailure
                }
                val confirmA = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(confirmA.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.APPROVAL_CONFIRMED_BY_SUPPORT_LIST,
                        targetId = REQUEST_ID_1,
                        installationId = INSTALLATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Case B: still in pendingRequests -> STILL_PENDING
                requests = listOf(RequestFixture(REQUEST_ID_2, INSTALLATION_ID_2, "Device B", 1_700_000_300L))
                coordinator.refresh()
                onApprove = { PairingAccessTransportResult.NetworkFailure }
                val confirmB = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_2,
                )!!
                coordinator.confirmDecision(confirmB.decisionId)
                assertEquals(2, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.STILL_PENDING,
                        targetId = REQUEST_ID_2,
                        installationId = INSTALLATION_ID_2,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Case C: disappeared from pendingRequests AND NOT in supports -> APPROVAL_UNCERTAIN
                onApprove = {
                    requests = emptyList()
                    PairingAccessTransportResult.NetworkFailure
                }
                val confirmC = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_2,
                )!!
                coordinator.confirmDecision(confirmC.decisionId)
                assertEquals(3, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.APPROVAL_UNCERTAIN,
                        targetId = REQUEST_ID_2,
                        installationId = INSTALLATION_ID_2,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun uncertainRejectRequestCancelInvitationAndRevokeSupportReconcileHonestlyWithoutRetry() =
        runBlocking {
            var invitations = listOf(INVITATION_ID_1, INVITATION_ID_2)
            var requests = listOf(
                RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Device A", 1_700_000_100L),
            )
            var supports = listOf(
                SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_2, "Support A", 1_700_000_200L),
                SupportFixture(MEMBERSHIP_ID_2, INSTALLATION_ID_3, "Support B", 1_700_000_250L),
            )
            var failReconcileGet = false
            var onReject: () -> PairingAccessTransportResult = { PairingAccessTransportResult.NetworkFailure }
            var onCancel: () -> PairingAccessTransportResult = { PairingAccessTransportResult.NetworkFailure }
            var onRevoke: () -> PairingAccessTransportResult = { PairingAccessTransportResult.NetworkFailure }

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations ->
                        if (failReconcileGet) {
                            PairingAccessTransportResult.NetworkFailure
                        } else {
                            response(200, invitationsListJson(invitations))
                        }

                    is PairingAccessOperation.ListRequests -> response(200, requestsListJson(requests))
                    is PairingAccessOperation.ListSupports -> response(200, supportsListJson(supports))
                    is PairingAccessOperation.RejectRequest -> onReject()
                    is PairingAccessOperation.CancelInvitation -> onCancel()
                    is PairingAccessOperation.RevokeSupport -> onRevoke()
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                coordinator.refresh()

                // Reject lost response + still pending -> STILL_PENDING
                onReject = { PairingAccessTransportResult.NetworkFailure }
                val rejectStillPending = coordinator.requestDecision(
                    HostSupportDecisionAction.REJECT_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(rejectStillPending.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.RejectRequest>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.STILL_PENDING,
                        targetId = REQUEST_ID_1,
                        installationId = INSTALLATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Reject lost response + absent from both lists -> REJECTION_INDETERMINATE
                onReject = {
                    requests = emptyList()
                    PairingAccessTransportResult.NetworkFailure
                }
                val rejectConfirm = coordinator.requestDecision(
                    HostSupportDecisionAction.REJECT_REQUEST,
                    REQUEST_ID_1,
                )!!
                coordinator.confirmDecision(rejectConfirm.decisionId)
                assertEquals(2, transport.countFor<PairingAccessOperation.RejectRequest>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.REJECTION_INDETERMINATE,
                        targetId = REQUEST_ID_1,
                        installationId = INSTALLATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Revoke lost response + still present -> STILL_ACTIVE
                onRevoke = { PairingAccessTransportResult.NetworkFailure }
                val revokeStillActive = coordinator.requestDecision(
                    HostSupportDecisionAction.REVOKE_SUPPORT,
                    MEMBERSHIP_ID_1,
                )!!
                coordinator.confirmDecision(revokeStillActive.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.RevokeSupport>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.STILL_ACTIVE,
                        targetId = MEMBERSHIP_ID_1,
                        installationId = INSTALLATION_ID_2,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Cancel lost response + absent -> CANCEL_CONFIRMED_ABSENT_CAUSE_UNKNOWN
                onCancel = {
                    invitations = listOf(INVITATION_ID_2)
                    PairingAccessTransportResult.NetworkFailure
                }
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(1, transport.countFor<PairingAccessOperation.CancelInvitation>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.CANCEL_CONFIRMED_ABSENT_CAUSE_UNKNOWN,
                        targetId = INVITATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Cancel lost response + still present -> STILL_AVAILABLE
                onCancel = { PairingAccessTransportResult.NetworkFailure }
                coordinator.cancelInvitation(INVITATION_ID_2)
                assertEquals(2, transport.countFor<PairingAccessOperation.CancelInvitation>())
                assertEquals(
                    HostSupportReconciliationKind.STILL_AVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )

                // Revoke lost response + absent -> REVOKE_CONFIRMED_ABSENT_CAUSE_UNKNOWN
                onRevoke = {
                    supports = listOf(
                        SupportFixture(MEMBERSHIP_ID_2, INSTALLATION_ID_3, "Support B", 1_700_000_250L),
                    )
                    PairingAccessTransportResult.NetworkFailure
                }
                val revokeConfirm1 = coordinator.requestDecision(
                    HostSupportDecisionAction.REVOKE_SUPPORT,
                    MEMBERSHIP_ID_1,
                )!!
                coordinator.confirmDecision(revokeConfirm1.decisionId)
                assertEquals(2, transport.countFor<PairingAccessOperation.RevokeSupport>())
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.REVOKE_CONFIRMED_ABSENT_CAUSE_UNKNOWN,
                        targetId = MEMBERSHIP_ID_1,
                        installationId = INSTALLATION_ID_2,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Revoke lost response + reconciliation GET fails offline -> RECONCILIATION_UNAVAILABLE & STALE
                onRevoke = {
                    failReconcileGet = true
                    PairingAccessTransportResult.NetworkFailure
                }
                val revokeConfirm2 = coordinator.requestDecision(
                    HostSupportDecisionAction.REVOKE_SUPPORT,
                    MEMBERSHIP_ID_2,
                )!!
                coordinator.confirmDecision(revokeConfirm2.decisionId)
                assertEquals(3, transport.countFor<PairingAccessOperation.RevokeSupport>())
                assertEquals(HostSupportPhase.STALE, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.listSnapshotFresh)
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                        targetId = MEMBERSHIP_ID_2,
                        installationId = INSTALLATION_ID_3,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun leaseInvalidationPreCheckVersusPostCheckDistinguishesNotSentFromUncertainWithoutRetry() =
        runBlocking {
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListRequests ->
                        response(
                            200,
                            requestsListJson(
                                listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                            ),
                        )

                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.IssueInvitation ->
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )

                    is PairingAccessOperation.ApproveRequest ->
                        response(200, approvalJson(REQUEST_ID_1, MEMBERSHIP_ID_1, 1_700_000_200L))

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                // Pre-check rejection on confirmDecision -> 0 POSTs, NO uncertainty notice.
                coordinator.refresh()
                val confirm1 = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                provider.rejectPreCheckNext = HostAdministrationSessionFailure.LEASE_INVALIDATED
                coordinator.confirmDecision(confirm1.decisionId)
                assertEquals(0, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                assertNull(coordinator.state.value.reconciliationNotice)

                // Post-check rejection on confirmDecision -> 1 POST only, RECONCILIATION_UNAVAILABLE.
                provider.resetActiveLease()
                coordinator.refresh()
                val confirm2 = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                provider.rejectPostCheckNext = HostAdministrationSessionFailure.LEASE_INVALIDATED
                coordinator.confirmDecision(confirm2.decisionId)
                assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                        targetId = REQUEST_ID_1,
                        installationId = INSTALLATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )

                // Post-check rejection on issueInvitation -> 1 POST only, ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE, no copy!
                provider.resetActiveLease()
                coordinator.refresh()
                provider.rejectPostCheckNext = HostAdministrationSessionFailure.LEASE_INVALIDATED
                coordinator.issueInvitation()
                assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
                assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                assertEquals(
                    HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun http401And403InvalidateLeaseClearSecretsAndBlockUntilExplicitRevalidation() = runBlocking {
        var authErrorStatus: Int? = null
        val transport = ScriptedHostTransport { operation, _, _ ->
            val forced = authErrorStatus
            if (forced != null) {
                return@ScriptedHostTransport errorResponse(
                    forced,
                    if (forced == 401) "access_unauthenticated" else "access_forbidden",
                )
            }
            when (operation) {
                is PairingAccessOperation.IssueInvitation ->
                    response(
                        201,
                        issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                    )

                is PairingAccessOperation.ListInvitations -> response(200, invitationsListJson(listOf(INVITATION_ID_1)))
                is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            coordinator.issueInvitation()
            assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

            // 403 on refresh invalidates lease with ADMIN_AUTHORIZATION_REJECTED and wipes secret.
            authErrorStatus = 403
            coordinator.refresh()

            assertEquals(1, provider.invalidationReasons.size)
            assertEquals(
                HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                provider.invalidationReasons.single(),
            )
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.ACCESS_FORBIDDEN, coordinator.state.value.safeFailure)
            assertEquals(
                HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                coordinator.state.value.sessionFailure,
            )
            assertNull(coordinator.state.value.emittedInvitation)
            assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

            // Ordinary refresh does NOT bypass rejected authorization.
            val callsBeforeBlockedRefresh = transport.totalCalls
            coordinator.refresh()
            assertEquals(callsBeforeBlockedRefresh, transport.totalCalls)
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)

            // Failed explicit revalidation keeps authorization required.
            provider.revalidateFailure = HostAdministrationSessionFailure.AUTHORIZATION_REJECTED
            coordinator.revalidateSession()
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
            assertEquals(callsBeforeBlockedRefresh, transport.totalCalls)

            // Successful explicit revalidation recovers cleanly and loads fresh lists.
            authErrorStatus = null
            provider.revalidateFailure = null
            coordinator.revalidateSession()
            assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.listSnapshotFresh)
            assertNull(coordinator.state.value.sessionFailure)

            // 401 on mutation also invalidates lease cleanly.
            authErrorStatus = 401
            coordinator.issueInvitation()
            assertEquals(2, provider.invalidationReasons.size)
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.NOT_AUTHENTICATED, coordinator.state.value.safeFailure)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun partialReadFailurePreservesPreviousListsAsStaleAndDisablesDecisions() = runBlocking {
        var failRequestsGet = false
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations ->
                    response(200, invitationsListJson(listOf(INVITATION_ID_1)))

                is PairingAccessOperation.ListRequests ->
                    if (failRequestsGet) {
                        PairingAccessTransportResult.NetworkFailure
                    } else {
                        response(
                            200,
                            requestsListJson(
                                listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Device", 1_700_000_100L)),
                            ),
                        )
                    }

                is PairingAccessOperation.ListSupports ->
                    response(
                        200,
                        supportsListJson(
                            listOf(SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_2, "Support", 1_700_000_200L)),
                        ),
                    )

                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.listSnapshotFresh)

            val openConfirmation = coordinator.requestDecision(
                HostSupportDecisionAction.APPROVE_REQUEST,
                REQUEST_ID_1,
            )
            assertNotNull(openConfirmation)

            failRequestsGet = true
            coordinator.refresh()

            val stale = coordinator.state.value
            assertEquals(HostSupportPhase.STALE, stale.phase)
            assertFalse(stale.listSnapshotFresh)
            assertFalse(stale.canRequestDecisions)
            assertNull(stale.pendingConfirmation)
            assertEquals(1, stale.invitations.size)
            assertEquals(1, stale.pendingRequests.size)
            assertEquals(1, stale.supports.size)
            assertEquals(PairingAccessFailureReason.NETWORK_FAILURE, stale.safeFailure)

            // Decisions and cancel on stale snapshot are disabled and send ZERO HTTP.
            val callsOnStale = transport.totalCalls
            assertNull(coordinator.requestDecision(HostSupportDecisionAction.APPROVE_REQUEST, REQUEST_ID_1))
            coordinator.confirmDecision(openConfirmation!!.decisionId)
            coordinator.cancelInvitation(INVITATION_ID_1)
            assertEquals(callsOnStale, transport.totalCalls)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun singleFlightDropsOverlappingMutationsAndRefreshAndCloseDiscardsLateCallbacks() =
        runBlocking {
            val issueStarted = CompletableDeferred<Unit>()
            val releaseIssue = CompletableDeferred<Unit>()
            val lateRefreshStarted = CompletableDeferred<Unit>()
            val releaseLateRefresh = CompletableDeferred<Unit>()
            var blockListInvitations = false

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation -> {
                        issueStarted.complete(Unit)
                        releaseIssue.await()
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )
                    }

                    is PairingAccessOperation.ListInvitations -> {
                        if (blockListInvitations) {
                            lateRefreshStarted.complete(Unit)
                            releaseLateRefresh.await()
                        }
                        response(200, invitationsListJson(listOf(INVITATION_ID_1)))
                    }

                    is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            val issueJob = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.issueInvitation()
            }
            var lateRefreshJob: kotlinx.coroutines.Job? = null
            try {
                withTimeout(BARRIER_TIMEOUT_MILLIS) { issueStarted.await() }
                assertEquals(HostSupportPhase.MUTATING, coordinator.state.value.phase)

                // Overlapping issue, refresh, and cancel are dropped while mutation is in flight.
                coordinator.issueInvitation()
                coordinator.refresh()
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
                assertEquals(0, transport.countFor<PairingAccessOperation.ListInvitations>())

                releaseIssue.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { issueJob.join() }
                assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)

                // Start a refresh, close coordinator mid-flight, then release HTTP: late result must NOT publish.
                blockListInvitations = true
                lateRefreshJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.refresh()
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) { lateRefreshStarted.await() }
                coordinator.close()
                assertEquals(HostSupportViewState(), coordinator.state.value)
                releaseLateRefresh.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { lateRefreshJob.join() }
                assertEquals(HostSupportViewState(), coordinator.state.value)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
            } finally {
                releaseIssue.complete(Unit)
                releaseLateRefresh.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { issueJob.cancelAndJoin() }
                lateRefreshJob?.let { withTimeout(BARRIER_TIMEOUT_MILLIS) { it.cancelAndJoin() } }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun cancellationDuringInFlightDecisionSettlesToStaleUncertainAndRethrows() = runBlocking {
        val approveStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListRequests ->
                    response(
                        200,
                        requestsListJson(
                            listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                        ),
                    )

                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ApproveRequest -> {
                    approveStarted.complete(Unit)
                    neverCompletes.await()
                    response(200, approvalJson(REQUEST_ID_1, MEMBERSHIP_ID_1, 1_700_000_200L))
                }

                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        var confirmJob: kotlinx.coroutines.Job? = null
        try {
            coordinator.refresh()
            val confirmation = coordinator.requestDecision(
                HostSupportDecisionAction.APPROVE_REQUEST,
                REQUEST_ID_1,
            )!!
            var caughtCancellation: CancellationException? = null
            confirmJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    coordinator.confirmDecision(confirmation.decisionId)
                } catch (cancelled: CancellationException) {
                    caughtCancellation = cancelled
                    throw cancelled
                }
            }
            withTimeout(BARRIER_TIMEOUT_MILLIS) { approveStarted.await() }
            withTimeout(BARRIER_TIMEOUT_MILLIS) { confirmJob.cancelAndJoin() }

            assertNotNull(caughtCancellation)
            assertEquals(HostSupportPhase.STALE, coordinator.state.value.phase)
            assertFalse(coordinator.state.value.listSnapshotFresh)
            assertEquals(
                HostSupportReconciliationNotice(
                    kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    targetId = REQUEST_ID_1,
                    installationId = INSTALLATION_ID_1,
                ),
                coordinator.state.value.reconciliationNotice,
            )
        } finally {
            neverCompletes.complete(Unit)
            confirmJob?.let { withTimeout(BARRIER_TIMEOUT_MILLIS) { it.cancelAndJoin() } }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedRuntimeExceptionPublishesSafeStateWithoutLeakingMessageAndRethrowsExactInstance() =
        runBlocking {
            val readBoom = IllegalStateException("secret-internal-read-exception-details")
            val mutationBoom = IllegalStateException("secret-internal-mutation-exception-details")
            var throwOnList = true
            var throwOnApprove = false

            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations ->
                        if (throwOnList) throw readBoom else response(200, emptyArrayDataJson())

                    is PairingAccessOperation.ListRequests ->
                        response(
                            200,
                            requestsListJson(
                                listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                            ),
                        )

                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ApproveRequest ->
                        if (throwOnApprove) {
                            throw mutationBoom
                        } else {
                            response(200, approvalJson(REQUEST_ID_1, MEMBERSHIP_ID_1, 1_700_000_200L))
                        }

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                try {
                    coordinator.refresh()
                    fail("Expected exact readBoom to be rethrown")
                } catch (caught: IllegalStateException) {
                    assertSame(readBoom, caught)
                }
                assertEquals(HostSupportPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.listSnapshotFresh)
                assertFalse(coordinator.state.value.toString().contains("secret-internal-read-exception-details"))

                throwOnList = false
                coordinator.refresh()
                val confirmation = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!

                throwOnApprove = true
                try {
                    coordinator.confirmDecision(confirmation.decisionId)
                    fail("Expected exact mutationBoom to be rethrown")
                } catch (caught: IllegalStateException) {
                    assertSame(mutationBoom, caught)
                }
                assertEquals(HostSupportPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.listSnapshotFresh)
                assertEquals(
                    HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertFalse(
                    coordinator.state.value.toString().contains("secret-internal-mutation-exception-details"),
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun refreshDropsOverlappingIssueInvitationAndRevalidateSessionWithoutClobberingLease() =
        runBlocking {
            val listStarted = CompletableDeferred<Unit>()
            val releaseList = CompletableDeferred<Unit>()
            val blockedOnce = java.util.concurrent.atomic.AtomicBoolean(false)
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> {
                        if (blockedOnce.compareAndSet(false, true)) {
                            listStarted.complete(Unit)
                            releaseList.await()
                        }
                        response(200, invitationsListJson(listOf(INVITATION_ID_1)))
                    }

                    is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.IssueInvitation ->
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_2, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider(rotateOnEachAcquire = true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.refresh()
            }
            try {
                withTimeout(BARRIER_TIMEOUT_MILLIS) { listStarted.await() }
                assertEquals(HostSupportPhase.LOADING, coordinator.state.value.phase)

                // Overlapping issueInvitation and revalidateSession must be dropped while refresh holds operationMutex.
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    coordinator.issueInvitation()
                    coordinator.revalidateSession()
                }
                assertEquals(1, provider.acquireCalls)
                assertEquals(0, provider.revalidateCalls)
                assertEquals(0, transport.countFor<PairingAccessOperation.IssueInvitation>())

                releaseList.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.join() }

                assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
                assertTrue(coordinator.state.value.listSnapshotFresh)
                assertEquals(1, coordinator.state.value.invitations.size)
                assertNull(coordinator.state.value.sessionFailure)
            } finally {
                releaseList.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.cancelAndJoin() }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun explicitCopyRejectionDuringInFlightMutationClearsSecretWithoutSwallowingUncertaintyNotice() =
        runBlocking {
            val approveStarted = CompletableDeferred<Unit>()
            val releaseApprove = CompletableDeferred<Unit>()
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation ->
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )

                    is PairingAccessOperation.ListInvitations -> response(200, invitationsListJson(listOf(INVITATION_ID_1)))
                    is PairingAccessOperation.ListRequests ->
                        response(
                            200,
                            requestsListJson(
                                listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                            ),
                        )

                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ApproveRequest -> {
                        approveStarted.complete(Unit)
                        releaseApprove.await()
                        response(200, approvalJson(REQUEST_ID_1, MEMBERSHIP_ID_1, 1_700_000_200L))
                    }

                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            var approveJob: kotlinx.coroutines.Job? = null
            try {
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

                val confirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                approveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.confirmDecision(confirm.decisionId)
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) { approveStarted.await() }

                // Lease is invalidated externally while ApproveRequest is in flight, and UI attempts explicit copy.
                provider.invalidateCurrentLeaseLocally(HostAdministrationSessionFailure.LEASE_INVALIDATED)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
                assertNull(coordinator.state.value.emittedInvitation)

                // Release ApproveRequest -> post-check fails and MUST publish RECONCILIATION_UNAVAILABLE.
                releaseApprove.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { approveJob.join() }

                assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
                assertEquals(HostSupportPhase.HOST_UNAVAILABLE, coordinator.state.value.phase)
                assertEquals(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    coordinator.state.value.sessionFailure,
                )
                assertEquals(
                    HostSupportReconciliationNotice(
                        kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                        targetId = REQUEST_ID_1,
                        installationId = INSTALLATION_ID_1,
                    ),
                    coordinator.state.value.reconciliationNotice,
                )
            } finally {
                releaseApprove.complete(Unit)
                approveJob?.let { withTimeout(BARRIER_TIMEOUT_MILLIS) { it.cancelAndJoin() } }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun closeDuringInFlightRefreshStopsRemainingListRequests() = runBlocking {
        val listInvitationsStarted = CompletableDeferred<Unit>()
        val releaseListInvitations = CompletableDeferred<Unit>()
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations -> {
                    listInvitationsStarted.complete(Unit)
                    releaseListInvitations.await()
                    response(200, emptyArrayDataJson())
                }

                is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.refresh()
        }
        try {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { listInvitationsStarted.await() }
            coordinator.close()
            releaseListInvitations.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.join() }

            assertEquals(1, transport.countFor<PairingAccessOperation.ListInvitations>())
            assertEquals(0, transport.countFor<PairingAccessOperation.ListRequests>())
            assertEquals(0, transport.countFor<PairingAccessOperation.ListSupports>())
            assertEquals(HostSupportViewState(), coordinator.state.value)
        } finally {
            releaseListInvitations.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun issueInvitation404ResourceNotFoundRefreshesListsWithoutRetry() = runBlocking {
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.IssueInvitation -> errorResponse(404, "resource_not_found")
                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            assertEquals(1, transport.countFor<PairingAccessOperation.ListInvitations>())

            coordinator.issueInvitation()

            assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
            assertEquals(2, transport.countFor<PairingAccessOperation.ListInvitations>())
            assertEquals(2, transport.countFor<PairingAccessOperation.ListRequests>())
            assertEquals(2, transport.countFor<PairingAccessOperation.ListSupports>())
            assertEquals(HostSupportPhase.READY, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.RESOURCE_NOT_FOUND, coordinator.state.value.safeFailure)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun reconciliation401And403InvalidateLeaseAndPreserveUncertaintyNotice() = runBlocking {
        var listAuthStatus: Int? = null
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations -> {
                    val forced = listAuthStatus
                    if (forced != null) {
                        errorResponse(
                            forced,
                            if (forced == 401) "access_unauthenticated" else "access_forbidden",
                        )
                    } else {
                        response(200, emptyArrayDataJson())
                    }
                }

                is PairingAccessOperation.ListRequests ->
                    response(
                        200,
                        requestsListJson(
                            listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                        ),
                    )

                is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ApproveRequest -> {
                    listAuthStatus = 401
                    PairingAccessTransportResult.NetworkFailure
                }

                is PairingAccessOperation.IssueInvitation -> {
                    listAuthStatus = 403
                    PairingAccessTransportResult.NetworkFailure
                }

                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            val confirm = coordinator.requestDecision(
                HostSupportDecisionAction.APPROVE_REQUEST,
                REQUEST_ID_1,
            )!!
            coordinator.confirmDecision(confirm.decisionId)

            assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
            assertEquals(
                listOf(HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED),
                provider.invalidationReasons,
            )
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.NOT_AUTHENTICATED, coordinator.state.value.safeFailure)
            assertEquals(
                HostSupportReconciliationNotice(
                    kind = HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    targetId = REQUEST_ID_1,
                    installationId = INSTALLATION_ID_1,
                ),
                coordinator.state.value.reconciliationNotice,
            )

            // Revalidate and verify 403 during issueInvitation reconciliation preserves ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE.
            listAuthStatus = null
            coordinator.revalidateSession()
            coordinator.issueInvitation()

            assertEquals(1, transport.countFor<PairingAccessOperation.IssueInvitation>())
            assertEquals(2, provider.invalidationReasons.size)
            assertEquals(HostSupportPhase.AUTHORIZATION_REQUIRED, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.ACCESS_FORBIDDEN, coordinator.state.value.safeFailure)
            assertEquals(
                HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                coordinator.state.value.reconciliationNotice?.kind,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun closedConfirmDecisionSendsZeroHttpAndDisappearedInvitationClearsCopyOnSameLease() =
        runBlocking {
            var invitations = listOf<String>()
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation -> {
                        invitations = listOf(INVITATION_ID_1)
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )
                    }

                    is PairingAccessOperation.ListInvitations -> response(200, invitationsListJson(invitations))
                    is PairingAccessOperation.ListRequests ->
                        response(
                            200,
                            requestsListJson(
                                listOf(RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Phone", 1_700_000_100L)),
                            ),
                        )

                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            try {
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

                // Same lease, but invitation expired/consumed externally -> refresh clears emittedInvitation and copy.
                invitations = emptyList()
                coordinator.refresh()
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

                // Confirming an earlier decision after close() sends zero HTTP.
                val confirm = coordinator.requestDecision(
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )!!
                val callsBeforeClose = transport.totalCalls
                coordinator.close()
                coordinator.confirmDecision(confirm.decisionId)
                assertEquals(callsBeforeClose, transport.totalCalls)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun uncertainCancelInvitationClearsEmittedSecretOnUnreconcilableReadFailureCancellationAndException() =
        runBlocking {
            var failListInvitations = false
            var cancelBehavior: suspend () -> PairingAccessTransportResult = {
                PairingAccessTransportResult.NetworkFailure
            }
            val transport = ScriptedHostTransport { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.IssueInvitation ->
                        response(
                            201,
                            issuedInvitationJson(INVITATION_ID_1, INVITATION_SECRET_1, 1_700_000_000L, 1_700_900_000L),
                        )

                    is PairingAccessOperation.ListInvitations ->
                        if (failListInvitations) {
                            PairingAccessTransportResult.NetworkFailure
                        } else {
                            response(200, invitationsListJson(listOf(INVITATION_ID_1)))
                        }

                    is PairingAccessOperation.ListRequests -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.ListSupports -> response(200, emptyArrayDataJson())
                    is PairingAccessOperation.CancelInvitation -> cancelBehavior()
                    else -> error("Unexpected operation: $operation")
                }
            }
            val provider = FakeHostAdministrationSessionProvider()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = HostSupportCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                administrationSessionProvider = provider,
                parentScope = scope,
            )

            var cancelJob: kotlinx.coroutines.Job? = null
            val neverCompletes = CompletableDeferred<Unit>()
            try {
                // Subcase 0: uncertain DELETE reconciled as STILL_AVAILABLE preserves copy.
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                cancelBehavior = { PairingAccessTransportResult.NetworkFailure }
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(
                    HostSupportReconciliationKind.STILL_AVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())

                // Subcase A: uncertain DELETE + reconciliation GET fails offline -> clears emittedSecret & copy.
                cancelBehavior = {
                    failListInvitations = true
                    PairingAccessTransportResult.NetworkFailure
                }
                coordinator.cancelInvitation(INVITATION_ID_1)
                assertEquals(HostSupportPhase.STALE, coordinator.state.value.phase)
                assertEquals(
                    HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

                // Subcase B: cancellation while DELETE is in flight -> clears emittedSecret & copy.
                failListInvitations = false
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                val deleteStarted = CompletableDeferred<Unit>()
                cancelBehavior = {
                    deleteStarted.complete(Unit)
                    neverCompletes.await()
                    response(204, "")
                }
                cancelJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.cancelInvitation(INVITATION_ID_1)
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) { deleteStarted.await() }
                withTimeout(BARRIER_TIMEOUT_MILLIS) { cancelJob.cancelAndJoin() }
                assertEquals(HostSupportPhase.STALE, coordinator.state.value.phase)
                assertEquals(
                    HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))

                // Subcase C: unexpected exception while DELETE is in flight -> clears emittedSecret & copy.
                coordinator.refresh()
                coordinator.issueInvitation()
                assertEquals(INVITATION_SECRET_1, coordinator.invitationForExplicitCopy(INVITATION_ID_1)?.reveal())
                val deleteBoom = IllegalStateException("secret-cancel-boom")
                cancelBehavior = { throw deleteBoom }
                try {
                    coordinator.cancelInvitation(INVITATION_ID_1)
                    fail("Expected exact deleteBoom to be rethrown")
                } catch (caught: IllegalStateException) {
                    assertSame(deleteBoom, caught)
                }
                assertEquals(HostSupportPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(
                    HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE,
                    coordinator.state.value.reconciliationNotice?.kind,
                )
                assertNull(coordinator.state.value.emittedInvitation)
                assertNull(coordinator.invitationForExplicitCopy(INVITATION_ID_1))
            } finally {
                neverCompletes.complete(Unit)
                cancelJob?.let { withTimeout(BARRIER_TIMEOUT_MILLIS) { it.cancelAndJoin() } }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun uncertainApproveRequestPrefersStillPendingOverSupportListMatch() = runBlocking {
        val requests = listOf(
            RequestFixture(REQUEST_ID_1, INSTALLATION_ID_1, "Device A", 1_700_000_100L),
        )
        var supports = emptyList<SupportFixture>()
        val transport = ScriptedHostTransport { operation, _, _ ->
            when (operation) {
                is PairingAccessOperation.ListInvitations -> response(200, emptyArrayDataJson())
                is PairingAccessOperation.ListRequests -> response(200, requestsListJson(requests))
                is PairingAccessOperation.ListSupports -> response(200, supportsListJson(supports))
                is PairingAccessOperation.ApproveRequest -> {
                    // Request remains pending even while supports contains INSTALLATION_ID_1.
                    supports = listOf(
                        SupportFixture(MEMBERSHIP_ID_1, INSTALLATION_ID_1, "Device A", 1_700_000_200L),
                    )
                    PairingAccessTransportResult.NetworkFailure
                }

                else -> error("Unexpected operation: $operation")
            }
        }
        val provider = FakeHostAdministrationSessionProvider()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = HostSupportCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            administrationSessionProvider = provider,
            parentScope = scope,
        )

        try {
            coordinator.refresh()
            val confirm = coordinator.requestDecision(
                HostSupportDecisionAction.APPROVE_REQUEST,
                REQUEST_ID_1,
            )!!
            coordinator.confirmDecision(confirm.decisionId)

            assertEquals(1, transport.countFor<PairingAccessOperation.ApproveRequest>())
            assertEquals(
                HostSupportReconciliationNotice(
                    kind = HostSupportReconciliationKind.STILL_PENDING,
                    targetId = REQUEST_ID_1,
                    installationId = INSTALLATION_ID_1,
                ),
                coordinator.state.value.reconciliationNotice,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    private data class RequestFixture(
        val id: String,
        val installationId: String,
        val label: String?,
        val createdAtMillis: Long,
        val expiresAtMillis: Long = createdAtMillis + 900_000L,
    )

    private data class SupportFixture(
        val membershipId: String,
        val installationId: String,
        val label: String?,
        val approvedAtMillis: Long,
    )

    private class FakeHostAdministrationSessionProvider(
        private val rotateOnEachAcquire: Boolean = false,
    ) : HostAdministrationSessionProvider {
        private var currentLease: HostAdministrationLease = HostAdministrationLease.generated()
        private var currentLeaseFailure: HostAdministrationSessionFailure? = null

        var acquireFailure: HostAdministrationSessionFailure? = null
        var revalidateFailure: HostAdministrationSessionFailure? = null
        var rejectPreCheckNext: HostAdministrationSessionFailure? = null
        var rejectPostCheckNext: HostAdministrationSessionFailure? = null
        var acquireCalls: Int = 0
            private set
        var revalidateCalls: Int = 0
            private set
        val invalidationReasons = mutableListOf<HostAdministrationSessionInvalidation>()

        fun rotateActiveLease() {
            currentLease = HostAdministrationLease.generated()
            currentLeaseFailure = null
            acquireFailure = null
        }

        fun invalidateCurrentLeaseLocally(failure: HostAdministrationSessionFailure) {
            currentLeaseFailure = failure
            acquireFailure = failure
        }

        fun resetActiveLease() {
            currentLease = HostAdministrationLease.generated()
            currentLeaseFailure = null
            acquireFailure = null
            rejectPreCheckNext = null
            rejectPostCheckNext = null
        }

        override suspend fun acquireLease(): HostAdministrationSessionResult<HostAdministrationLease> {
            acquireCalls += 1
            acquireFailure?.let { return HostAdministrationSessionResult.Rejected(it) }
            currentLeaseFailure?.let { return HostAdministrationSessionResult.Rejected(it) }
            if (rotateOnEachAcquire) {
                currentLease = HostAdministrationLease.generated()
            }
            return HostAdministrationSessionResult.Ready(currentLease)
        }

        override suspend fun validateLease(
            lease: HostAdministrationLease,
        ): HostAdministrationSessionResult<Unit> {
            currentLeaseFailure?.let { return HostAdministrationSessionResult.Rejected(it) }
            if (lease !== currentLease) {
                return HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                )
            }
            return HostAdministrationSessionResult.Ready(Unit)
        }

        override suspend fun <T> withValidatedLease(
            lease: HostAdministrationLease,
            operation: suspend (PairingHostLease) -> T,
        ): HostAdministrationSessionResult<T> {
            rejectPreCheckNext?.let { failure ->
                rejectPreCheckNext = null
                return HostAdministrationSessionResult.Rejected(failure)
            }
            val pre = validateLease(lease)
            if (pre is HostAdministrationSessionResult.Rejected) {
                return pre
            }
            val result = operation(
                PairingHostLease(
                    origin = ORIGIN,
                    monitorId = MONITOR_ID,
                    accessToken = SecretValue.of(HOST_PAT),
                ),
            )
            rejectPostCheckNext?.let { failure ->
                rejectPostCheckNext = null
                return HostAdministrationSessionResult.Rejected(failure)
            }
            val post = validateLease(lease)
            return when (post) {
                is HostAdministrationSessionResult.Ready -> HostAdministrationSessionResult.Ready(result)
                is HostAdministrationSessionResult.Rejected -> post
            }
        }

        override fun invalidateLease(
            lease: HostAdministrationLease,
            reason: HostAdministrationSessionInvalidation,
        ) {
            invalidationReasons += reason
            if (lease === currentLease) {
                currentLeaseFailure = HostAdministrationSessionFailure.AUTHORIZATION_REJECTED
                acquireFailure = HostAdministrationSessionFailure.AUTHORIZATION_REJECTED
            }
        }

        override suspend fun revalidateAdministrationSession(): HostAdministrationSessionResult<Unit> {
            revalidateCalls += 1
            revalidateFailure?.let { return HostAdministrationSessionResult.Rejected(it) }
            resetActiveLease()
            return HostAdministrationSessionResult.Ready(Unit)
        }
    }

    private class ScriptedHostTransport(
        private val handler: suspend (
            PairingAccessOperation,
            PairingAccessCredential?,
            PairingAccessRequestBody?,
        ) -> PairingAccessTransportResult = { _, _, _ -> error("Unexpected HTTP call") },
    ) : PairingAccessTransport {
        override val origin: String = ORIGIN
        val recordedOperations = mutableListOf<PairingAccessOperation>()

        val totalCalls: Int
            get() = recordedOperations.size

        inline fun <reified T : PairingAccessOperation> countFor(): Int =
            recordedOperations.count { it is T }

        override suspend fun execute(
            operation: PairingAccessOperation,
            credential: PairingAccessCredential?,
            body: PairingAccessRequestBody?,
        ): PairingAccessTransportResult {
            recordedOperations += operation
            return handler(operation, credential, body)
        }
    }

    private fun response(statusCode: Int, json: String): PairingAccessTransportResult.Response =
        PairingAccessTransportResult.Response(
            statusCode = statusCode,
            body = json.toByteArray(StandardCharsets.UTF_8),
        )

    private fun errorResponse(
        statusCode: Int,
        code: String,
        retryAfterSeconds: Long? = null,
    ): PairingAccessTransportResult.Response =
        PairingAccessTransportResult.Response(
            statusCode = statusCode,
            body = """{"code":"$code","message":"synthetic"}""".toByteArray(StandardCharsets.UTF_8),
            retryAfterSeconds = retryAfterSeconds,
        )

    private fun emptyArrayDataJson(): String = """{"data":[]}"""

    private fun issuedInvitationJson(
        id: String,
        secret: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
    ): String =
        """{"data":{"id":"$id","invitationSecret":"$secret","createdAt":$createdAtMillis,"expiresAt":$expiresAtMillis}}"""

    private fun invitationsListJson(ids: List<String>): String {
        val items = ids.joinToString(",") { id ->
            """{"id":"$id","status":"Available","createdAt":1700000000,"expiresAt":1700900000}"""
        }
        return """{"data":[$items]}"""
    }

    private fun requestsListJson(requests: List<RequestFixture>): String {
        val items = requests.joinToString(",") { item ->
            val labelJson = item.label?.let { "\"$it\"" } ?: "null"
            """{"id":"${item.id}","status":"Pending","installation":{"id":"${item.installationId}","label":$labelJson},"createdAt":${item.createdAtMillis},"requestExpiresAt":${item.expiresAtMillis}}"""
        }
        return """{"data":[$items]}"""
    }

    private fun supportsListJson(supports: List<SupportFixture>): String {
        val items = supports.joinToString(",") { item ->
            val labelJson = item.label?.let { "\"$it\"" } ?: "null"
            """{"membershipId":"${item.membershipId}","installationId":"${item.installationId}","label":$labelJson,"role":"Support","status":"Active","approvedAt":${item.approvedAtMillis}}"""
        }
        return """{"data":[$items]}"""
    }

    private fun approvalJson(
        requestId: String,
        membershipId: String,
        decidedAtMillis: Long,
    ): String =
        """{"data":{"requestId":"$requestId","status":"Approved","membershipId":"$membershipId","role":"Support","decidedAt":$decidedAtMillis}}"""

    private fun rejectionJson(
        requestId: String,
        decidedAtMillis: Long,
    ): String =
        """{"data":{"requestId":"$requestId","status":"Rejected","decidedAt":$decidedAtMillis}}"""

    private companion object {
        const val BARRIER_TIMEOUT_MILLIS = 5_000L
        const val ORIGIN = "https://pairing.example.test"
        const val MONITOR_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val HOST_PAT = "1|synthetic-host-admin-secret"
        const val INVITATION_SECRET_1 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val INVITATION_ID_1 = "550e8400-e29b-41d4-a716-446655440011"
        const val INVITATION_ID_2 = "550e8400-e29b-41d4-a716-446655440012"
        const val REQUEST_ID_1 = "550e8400-e29b-41d4-a716-446655440021"
        const val REQUEST_ID_2 = "550e8400-e29b-41d4-a716-446655440022"
        const val INSTALLATION_ID_1 = "550e8400-e29b-41d4-a716-446655440031"
        const val INSTALLATION_ID_2 = "550e8400-e29b-41d4-a716-446655440032"
        const val INSTALLATION_ID_3 = "550e8400-e29b-41d4-a716-446655440033"
        const val INSTALLATION_ID_4 = "550e8400-e29b-41d4-a716-446655440034"
        const val MEMBERSHIP_ID_1 = "550e8400-e29b-41d4-a716-446655440041"
        const val MEMBERSHIP_ID_2 = "550e8400-e29b-41d4-a716-446655440042"
        const val MEMBERSHIP_ID_3 = "550e8400-e29b-41d4-a716-446655440043"
    }
}
