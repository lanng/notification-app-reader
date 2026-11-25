package br.com.c6tag.diagnostico.remote.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import br.com.c6tag.diagnostico.BuildConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAccessViewModelTest {
    @Test
    fun attachSurfaceWithEmptyStoresResolvesUnassignedWithZeroNetworkGoogleUiOrWrites() =
        runBlocking {
            val fixture = Fixture()
            try {
                val lease = fixture.viewModel.attachSurface()
                val state = fixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.UNASSIGNED &&
                        it.support.phase == SupportPairingPhase.EMPTY &&
                        it.host.phase == RemoteHostPhase.LOCAL_ONLY
                }

                assertTrue(lease > 0L)
                assertTrue(state.canEnterSupportFlow)
                assertTrue(state.canActivateHost)
                assertFalse(state.canManageSupports)
                assertEquals(0, fixture.ownerTransport.totalCalls)
                assertEquals(0, fixture.pairingTransport.totalCalls)
                assertEquals(0, fixture.identity.signInCalls)
                assertEquals(0, fixture.identity.reauthenticateCalls)
                assertEquals(0, fixture.identity.signOutCalls)
                assertEquals(0, fixture.hostFile.writeCount)
                assertEquals(0, fixture.hostFile.deleteCount)
                assertEquals(0, fixture.supportFile.writeCount)
                assertEquals(0, fixture.supportFile.deleteCount)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun attachSurfaceWithSupportPresentAndNoGoogleAccountInitializesOnlySupport() = runBlocking {
        val fixture = Fixture(initialAccount = null)
        try {
            fixture.seedApprovedSupport()
            fixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.ValidateSupportInstallation ->
                        pairingResponse(200, supportInstallationJson(generation = "2"))

                    else -> error("Unexpected pairing operation: $operation")
                }
            }

            fixture.viewModel.attachSurface()
            val state = fixture.awaitState {
                it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    it.support.phase == SupportPairingPhase.CONFIRMED
            }

            assertNull(state.account)
            assertTrue(state.canEnterSupportFlow)
            assertFalse(state.canActivateHost)
            assertFalse(state.canManageSupports)
            assertEquals(MONITOR_ID, state.support.confirmed?.monitorId)
            assertEquals(MEMBERSHIP_ID, state.support.confirmed?.membershipId)
            assertEquals(0, fixture.ownerTransport.totalCalls)
            assertEquals(0, fixture.hostStore.loadCalls)
            assertEquals(0, fixture.identity.signInCalls)
            assertEquals(0, fixture.identity.reauthenticateCalls)
            assertEquals(1, fixture.pairingTransport.totalCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun attachSurfaceWithHostPresentSignedOutBlocksSupportRedemptionWithZeroWritesOrHttp() =
        runBlocking {
            val fixture = Fixture(initialAccount = null)
            try {
                fixture.seedActiveHost()

                val lease = fixture.viewModel.attachSurface()
                val attached = fixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.HOST_PRESENT &&
                        it.host.phase == RemoteHostPhase.LOCAL_ONLY
                }

                assertNull(attached.account)
                assertFalse(attached.canEnterSupportFlow)
                assertTrue(attached.canActivateHost)
                assertFalse(attached.canManageSupports)
                assertEquals(0, fixture.ownerTransport.totalCalls)
                assertEquals(0, fixture.pairingTransport.totalCalls)

                fixture.viewModel.submitSupportInvitation(
                    lease = lease,
                    invitationSecret = VALID_INVITATION_SECRET,
                    installationLabel = "Segundo aparelho",
                )

                assertEquals(0, fixture.supportStore.beginAttemptCalls)
                assertEquals(0, fixture.supportFile.writeCount)
                assertEquals(0, fixture.supportFile.deleteCount)
                assertEquals(0, fixture.pairingTransport.totalCalls)
                assertEquals(RemoteSessionPresence.Present, fixture.hostStore.presence())
                assertEquals(RemoteSessionPresence.Absent, fixture.supportStore.presence())
                assertEquals(RemoteAccessRoleMode.HOST_PRESENT, fixture.viewModel.uiState.value.roleMode)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun attachSurfaceWithBothHostAndSupportPresentPublishesDualRoleConflictAndBlocksMutationsWithoutDeleting() =
        runBlocking {
            val fixture = Fixture(initialAccount = OWNER)
            try {
                fixture.seedActiveHost()
                fixture.seedApprovedSupport()
                val hostWritesBefore = fixture.hostFile.writeCount
                val supportWritesBefore = fixture.supportFile.writeCount

                val lease = fixture.viewModel.attachSurface()
                val state = fixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.DUAL_ROLE_CONFLICT
                }

                assertFalse(state.canEnterSupportFlow)
                assertFalse(state.canActivateHost)
                assertFalse(state.canManageSupports)
                assertEquals(0, fixture.ownerTransport.totalCalls)
                assertEquals(0, fixture.pairingTransport.totalCalls)
                assertEquals(0, fixture.hostStore.loadCalls)
                assertEquals(0, fixture.supportStore.loadCalls)

                fixture.viewModel.submitSupportInvitation(lease, VALID_INVITATION_SECRET, "Tablet")
                fixture.viewModel.confirmHostActivation(lease, "stale-confirmation", false)
                fixture.viewModel.openHostSupports(lease)
                fixture.viewModel.issueSupportInvitation(lease)
                fixture.viewModel.cancelSupportInvitation(lease, INVITATION_ID_1)
                assertNull(
                    fixture.viewModel.requestHostSupportDecision(
                        lease,
                        HostSupportDecisionAction.APPROVE_REQUEST,
                        REQUEST_ID_1,
                    ),
                )
                fixture.viewModel.confirmHostSupportDecision(lease, "decision-1")
                assertNull(fixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))

                assertEquals(0, fixture.ownerTransport.totalCalls)
                assertEquals(0, fixture.pairingTransport.totalCalls)
                assertEquals(hostWritesBefore, fixture.hostFile.writeCount)
                assertEquals(supportWritesBefore, fixture.supportFile.writeCount)
                assertEquals(0, fixture.hostFile.deleteCount)
                assertEquals(0, fixture.supportFile.deleteCount)
                assertEquals(RemoteSessionPresence.Present, fixture.hostStore.presence())
                assertEquals(RemoteSessionPresence.Present, fixture.supportStore.presence())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun attachSurfaceWithUnavailablePresenceFailsClosedAndBlocksAllMutationsWithoutDeleting() =
        runBlocking {
            // Case 1: Host presence unavailable while support record exists.
            val hostUnavailableFixture = Fixture(initialAccount = OWNER)
            try {
                hostUnavailableFixture.seedApprovedSupport()
                hostUnavailableFixture.hostFile.failReads = true
                val supportWritesBefore = hostUnavailableFixture.supportFile.writeCount

                val lease = hostUnavailableFixture.viewModel.attachSurface()
                val state = hostUnavailableFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.STORAGE_UNAVAILABLE
                }

                assertFalse(state.canEnterSupportFlow)
                assertFalse(state.canActivateHost)
                assertFalse(state.canManageSupports)
                assertEquals(0, hostUnavailableFixture.ownerTransport.totalCalls)
                assertEquals(0, hostUnavailableFixture.pairingTransport.totalCalls)

                hostUnavailableFixture.viewModel.submitSupportInvitation(
                    lease,
                    VALID_INVITATION_SECRET,
                    "Tablet",
                )
                hostUnavailableFixture.viewModel.confirmHostActivation(lease, "conf-1", false)
                hostUnavailableFixture.viewModel.issueSupportInvitation(lease)

                assertEquals(0, hostUnavailableFixture.ownerTransport.totalCalls)
                assertEquals(0, hostUnavailableFixture.pairingTransport.totalCalls)
                assertEquals(supportWritesBefore, hostUnavailableFixture.supportFile.writeCount)
                assertEquals(0, hostUnavailableFixture.hostFile.deleteCount)
                assertEquals(0, hostUnavailableFixture.supportFile.deleteCount)
                assertEquals(
                    RemoteSessionPresence.Present,
                    hostUnavailableFixture.supportStore.presence(),
                )
            } finally {
                hostUnavailableFixture.close()
            }

            // Case 2: Support presence unavailable while host record exists.
            val supportUnavailableFixture = Fixture(initialAccount = OWNER)
            try {
                supportUnavailableFixture.seedActiveHost()
                supportUnavailableFixture.supportFile.failReads = true
                val hostWritesBefore = supportUnavailableFixture.hostFile.writeCount

                val lease = supportUnavailableFixture.viewModel.attachSurface()
                val state = supportUnavailableFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.STORAGE_UNAVAILABLE
                }

                assertFalse(state.canEnterSupportFlow)
                assertFalse(state.canActivateHost)
                assertFalse(state.canManageSupports)
                assertEquals(0, supportUnavailableFixture.ownerTransport.totalCalls)
                assertEquals(0, supportUnavailableFixture.pairingTransport.totalCalls)

                supportUnavailableFixture.viewModel.submitSupportInvitation(
                    lease,
                    VALID_INVITATION_SECRET,
                    "Tablet",
                )
                supportUnavailableFixture.viewModel.confirmHostActivation(lease, "conf-2", false)
                supportUnavailableFixture.viewModel.issueSupportInvitation(lease)

                assertEquals(0, supportUnavailableFixture.ownerTransport.totalCalls)
                assertEquals(0, supportUnavailableFixture.pairingTransport.totalCalls)
                assertEquals(hostWritesBefore, supportUnavailableFixture.hostFile.writeCount)
                assertEquals(0, supportUnavailableFixture.hostFile.deleteCount)
                assertEquals(0, supportUnavailableFixture.supportFile.deleteCount)
                assertEquals(
                    RemoteSessionPresence.Present,
                    supportUnavailableFixture.hostStore.presence(),
                )
            } finally {
                supportUnavailableFixture.close()
            }
        }

    @Test
    fun oldDetachAfterReopenDoesNotCloseNewLeaseAndStaleLeaseActionsAreRejected() = runBlocking {
        val fixture = Fixture(initialAccount = null)
        try {
            fixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.RedeemInvitation ->
                        pairingResponse(201, redemptionJson())

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease1 = fixture.viewModel.attachSurface()
            fixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

            val lease2 = fixture.viewModel.attachSurface()
            assertTrue(lease2 > lease1)
            fixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

            // Stale detach for lease1 MUST NOT close lease2.
            fixture.viewModel.detachSurface(lease1)

            // Every stale action using lease1 MUST be rejected with zero HTTP/writes.
            fixture.viewModel.openSupportPairing(lease1)
            fixture.viewModel.submitSupportInvitation(lease1, VALID_INVITATION_SECRET, "Stale")
            fixture.viewModel.refreshSupportStatus(lease1)
            fixture.viewModel.discoverOwnerMonitor(lease1)
            fixture.viewModel.confirmHostActivation(lease1, "stale-conf", false)
            fixture.viewModel.signOut(lease1)
            fixture.viewModel.openHostSupports(lease1)
            fixture.viewModel.refreshHostSupports(lease1)
            fixture.viewModel.revalidateHostSupportsSession(lease1)
            fixture.viewModel.issueSupportInvitation(lease1)
            fixture.viewModel.cancelSupportInvitation(lease1, INVITATION_ID_1)
            assertNull(
                fixture.viewModel.requestHostSupportDecision(
                    lease1,
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                ),
            )
            fixture.viewModel.cancelHostSupportDecision(lease1)
            fixture.viewModel.confirmHostSupportDecision(lease1, "stale-decision")
            assertNull(fixture.viewModel.invitationForExplicitCopy(lease1, INVITATION_ID_1))

            assertEquals(0, fixture.pairingTransport.totalCalls)
            assertEquals(0, fixture.ownerTransport.totalCalls)
            assertEquals(0, fixture.supportFile.writeCount)

            // Lease2 MUST still be alive and fully operational.
            fixture.viewModel.submitSupportInvitation(lease2, VALID_INVITATION_SECRET, "Active")
            val redeemed = fixture.awaitState {
                it.support.phase == SupportPairingPhase.PENDING &&
                    it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    !it.supportActionInProgress
            }

            assertEquals(REQUEST_ID_1, redeemed.support.pending?.requestId)
            assertEquals(1, fixture.pairingTransport.totalCalls)
            assertFalse(redeemed.canActivateHost)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun lateResponseFromDetachedLeaseNeverOverwritesOrClearsNewLease() = runBlocking {
        val fixture = Fixture(initialAccount = null)
        val firstCurrentEntered = CompletableDeferred<Unit>()
        val releaseFirstCurrent = CompletableDeferred<Unit>()
        val firstLateResponseReturned = CompletableDeferred<Unit>()
        val currentCalls = AtomicInteger(0)
        try {
            fixture.seedPendingSupport()
            fixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.CurrentRequest -> {
                        val call = currentCalls.incrementAndGet()
                        if (call == 1) {
                            firstCurrentEntered.complete(Unit)
                            withContext(NonCancellable) {
                                releaseFirstCurrent.await()
                            }
                            firstLateResponseReturned.complete(Unit)
                            // Late 401 from detached lease1 would otherwise clear session if not isolated!
                            pairingResponse(401, """{"code":"unauthenticated","message":"nope"}""")
                        } else {
                            pairingResponse(200, approvedCurrentRequestJson())
                        }
                    }

                    PairingAccessOperation.ValidateSupportInstallation ->
                        pairingResponse(200, supportInstallationJson(generation = "2"))

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease1 = fixture.viewModel.attachSurface()
            withTimeout(BARRIER_TIMEOUT_MILLIS) { firstCurrentEntered.await() }

            fixture.viewModel.detachSurface(lease1)
            val lease2 = fixture.viewModel.attachSurface()
            assertTrue(lease2 > lease1)

            val confirmed = fixture.awaitState {
                it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    it.support.phase == SupportPairingPhase.CONFIRMED
            }
            assertEquals(MONITOR_ID, confirmed.support.confirmed?.monitorId)

            // Release late response from lease1 and prove the 401 actually returned.
            releaseFirstCurrent.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { firstLateResponseReturned.await() }

            // Verify lease2 state and persisted Approved record remain intact.
            assertEquals(SupportPairingPhase.CONFIRMED, fixture.viewModel.uiState.value.support.phase)
            assertEquals(RemoteAccessRoleMode.SUPPORT_PRESENT, fixture.viewModel.uiState.value.roleMode)
            assertTrue(
                fixture.supportStore.load(ORIGIN) is RemoteSupportSessionLoad.Approved,
            )
        } finally {
            releaseFirstCurrent.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun onClearedViaViewModelStoreClosesAllThreeCoordinatorsAndCancelsInFlightWork() = runBlocking {
        // Part A: Active host + HostSupportCoordinator in-flight refresh & copyable secret
        val hostFixture = Fixture(initialAccount = OWNER)
        val hostRefreshEntered = CompletableDeferred<Unit>()
        val hostRefreshCancelled = CompletableDeferred<Unit>()
        val neverCompleteHostRefresh = CompletableDeferred<Unit>()
        val listInvitationsCalls = AtomicInteger(0)
        try {
            hostFixture.seedActiveHost()
            hostFixture.ownerTransport.installationResponse = {
                ownerResponse(200, hostInstallationJson())
            }
            hostFixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> {
                        val call = listInvitationsCalls.incrementAndGet()
                        if (call >= 3) {
                            hostRefreshEntered.complete(Unit)
                            try {
                                neverCompleteHostRefresh.await()
                            } catch (cancelled: CancellationException) {
                                hostRefreshCancelled.complete(Unit)
                                throw cancelled
                            }
                        }
                        pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))
                    }

                    is PairingAccessOperation.ListRequests ->
                        pairingResponse(200, requestsListJson())

                    is PairingAccessOperation.ListSupports ->
                        pairingResponse(200, supportsListJson())

                    is PairingAccessOperation.IssueInvitation ->
                        pairingResponse(201, issuedInvitationJson())

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease = hostFixture.viewModel.attachSurface()
            hostFixture.awaitState { it.canManageSupports }

            hostFixture.viewModel.openHostSupports(lease)
            hostFixture.awaitState {
                it.hostSupports.phase == HostSupportPhase.READY && !it.hostSupportActionInProgress
            }

            hostFixture.viewModel.issueSupportInvitation(lease)
            hostFixture.awaitState {
                it.hostSupports.emittedInvitation?.copyAvailable == true &&
                    !it.hostSupportActionInProgress
            }
            assertNotNull(hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))

            // Start an in-flight refresh that blocks on the barrier.
            hostFixture.viewModel.refreshHostSupports(lease)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { hostRefreshEntered.await() }

            // Clearing the ViewModelStore invokes onCleared() and MUST close all 3 coordinators.
            hostFixture.viewModelStore.clear()
            withTimeout(BARRIER_TIMEOUT_MILLIS) { hostRefreshCancelled.await() }

            assertNull(hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))
            val callsAfterClear = hostFixture.pairingTransport.totalCalls
            hostFixture.viewModel.issueSupportInvitation(lease)
            hostFixture.viewModel.discoverOwnerMonitor(lease)
            assertEquals(callsAfterClear, hostFixture.pairingTransport.totalCalls)
        } finally {
            neverCompleteHostRefresh.complete(Unit)
            hostFixture.close()
        }

        // Part B: SupportPairingCoordinator in-flight refresh cancelled by ViewModelStore.clear()
        val supportFixture = Fixture(initialAccount = null)
        val supportRefreshEntered = CompletableDeferred<Unit>()
        val supportRefreshCancelled = CompletableDeferred<Unit>()
        val neverCompleteSupport = CompletableDeferred<Unit>()
        try {
            supportFixture.seedPendingSupport()
            supportFixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.CurrentRequest -> {
                        supportRefreshEntered.complete(Unit)
                        try {
                            neverCompleteSupport.await()
                        } catch (cancelled: CancellationException) {
                            supportRefreshCancelled.complete(Unit)
                            throw cancelled
                        }
                        pairingResponse(200, pendingCurrentRequestJson())
                    }

                    else -> error("Unexpected operation: $operation")
                }
            }

            supportFixture.viewModel.attachSurface()
            withTimeout(BARRIER_TIMEOUT_MILLIS) { supportRefreshEntered.await() }

            supportFixture.viewModelStore.clear()
            withTimeout(BARRIER_TIMEOUT_MILLIS) { supportRefreshCancelled.await() }
        } finally {
            neverCompleteSupport.complete(Unit)
            supportFixture.close()
        }
    }

    @Test
    fun cancelConfirmationReplacesAllCoordinatorsInvalidatingConfirmationsAndSecretsWithoutReplay() =
        runBlocking {
            // Part A: Active host with emitted invitation & pending host-support confirmation
            val hostFixture = Fixture(initialAccount = OWNER)
            try {
                hostFixture.seedActiveHost()
                hostFixture.ownerTransport.installationResponse = {
                    ownerResponse(200, hostInstallationJson())
                }
                hostFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        is PairingAccessOperation.ListInvitations ->
                            pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))

                        is PairingAccessOperation.ListRequests ->
                            pairingResponse(200, requestsListJson())

                        is PairingAccessOperation.ListSupports ->
                            pairingResponse(200, supportsListJson())

                        is PairingAccessOperation.IssueInvitation ->
                            pairingResponse(201, issuedInvitationJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }

                val lease = hostFixture.viewModel.attachSurface()
                hostFixture.awaitState { it.canManageSupports }

                hostFixture.viewModel.openHostSupports(lease)
                hostFixture.awaitState {
                    it.hostSupports.phase == HostSupportPhase.READY &&
                        !it.hostSupportActionInProgress
                }
                hostFixture.viewModel.issueSupportInvitation(lease)
                hostFixture.awaitState {
                    it.hostSupports.emittedInvitation?.copyAvailable == true &&
                        !it.hostSupportActionInProgress
                }
                val decision = hostFixture.viewModel.requestHostSupportDecision(
                    lease,
                    HostSupportDecisionAction.APPROVE_REQUEST,
                    REQUEST_ID_1,
                )
                assertNotNull(decision)
                assertNotNull(hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))

                val ownerCallsBefore = hostFixture.ownerTransport.totalCalls
                val pairingCallsBefore = hostFixture.pairingTransport.totalCalls

                hostFixture.viewModel.cancelConfirmation(lease)
                val afterCancel = hostFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.HOST_PRESENT &&
                        it.hostSupports.phase == HostSupportPhase.IDLE &&
                        it.hostSupports.pendingConfirmation == null &&
                        it.hostSupports.emittedInvitation == null
                }

                // Zero replay network calls on cancelConfirmation!
                assertEquals(ownerCallsBefore, hostFixture.ownerTransport.totalCalls)
                assertEquals(pairingCallsBefore, hostFixture.pairingTransport.totalCalls)
                assertNull(afterCancel.host.confirmation)

                // Old emitted invitation copy and old host-support decision MUST be unusable!
                assertNull(hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))
                hostFixture.viewModel.confirmHostSupportDecision(lease, requireNotNull(decision).decisionId)
                assertEquals(pairingCallsBefore, hostFixture.pairingTransport.totalCalls)
            } finally {
                hostFixture.close()
            }

            // Part B: Owner confirmation becomes unusable after cancelConfirmation
            val ownerFixture = Fixture(initialAccount = OWNER)
            try {
                val lease = ownerFixture.viewModel.attachSurface()
                ownerFixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

                ownerFixture.viewModel.discoverOwnerMonitor(lease)
                val ready = ownerFixture.awaitState {
                    it.host.phase == RemoteHostPhase.CONFIRMATION_READY && !it.actionInProgress
                }
                val oldConfirmationId = requireNotNull(ready.host.confirmation).confirmationId
                val ownerCallsBefore = ownerFixture.ownerTransport.totalCalls

                ownerFixture.viewModel.cancelConfirmation(lease)
                ownerFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.UNASSIGNED && it.host.confirmation == null
                }
                assertEquals(ownerCallsBefore, ownerFixture.ownerTransport.totalCalls)

                ownerFixture.viewModel.confirmHostActivation(lease, oldConfirmationId, false)
                ownerFixture.awaitState { !it.actionInProgress }
                assertEquals(ownerCallsBefore, ownerFixture.ownerTransport.totalCalls)
                assertEquals(0, ownerFixture.hostFile.writeCount)
            } finally {
                ownerFixture.close()
            }
        }

    @Test
    fun supportReservationBlocksHostActivationVisuallyAndViaRoleGuardEvenWithStaleOwnerConfirmation() =
        runBlocking {
            // Part A: Support reserved via ViewModel -> visual block (canActivateHost == false) + VM block
            val vmReservedFixture = Fixture(initialAccount = OWNER)
            try {
                vmReservedFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.RedeemInvitation ->
                            pairingResponse(201, redemptionJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }
                val lease = vmReservedFixture.viewModel.attachSurface()
                vmReservedFixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

                vmReservedFixture.viewModel.discoverOwnerMonitor(lease)
                val ready = vmReservedFixture.awaitState {
                    it.host.phase == RemoteHostPhase.CONFIRMATION_READY && !it.actionInProgress
                }
                val confirmationId = requireNotNull(ready.host.confirmation).confirmationId

                vmReservedFixture.viewModel.submitSupportInvitation(
                    lease = lease,
                    invitationSecret = VALID_INVITATION_SECRET,
                    installationLabel = "Suporte",
                )
                val supportReserved = vmReservedFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                        it.support.phase == SupportPairingPhase.PENDING &&
                        !it.supportActionInProgress
                }
                assertFalse(supportReserved.canActivateHost)

                val ownerCallsBefore = vmReservedFixture.ownerTransport.totalCalls
                vmReservedFixture.viewModel.confirmHostActivation(lease, confirmationId, false)

                assertEquals(ownerCallsBefore, vmReservedFixture.ownerTransport.totalCalls)
                assertEquals(0, vmReservedFixture.hostFile.writeCount)
                assertEquals(RemoteSessionPresence.Absent, vmReservedFixture.hostStore.presence())
            } finally {
                vmReservedFixture.close()
            }

            // Part B: Support reserved out-of-band via shared RemoteRoleGuard -> S4 guard blocks confirmHostActivation
            val guardFixture = Fixture(initialAccount = OWNER)
            try {
                val lease = guardFixture.viewModel.attachSurface()
                guardFixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

                guardFixture.viewModel.discoverOwnerMonitor(lease)
                val ready = guardFixture.awaitState {
                    it.host.phase == RemoteHostPhase.CONFIRMATION_READY && !it.actionInProgress
                }
                val confirmationId = requireNotNull(ready.host.confirmation).confirmationId

                // Reserve support role out-of-band so VM still holds UNASSIGNED when activation is triggered.
                val guarded = guardFixture.roleGuard.beginSupportAttempt {
                    guardFixture.supportStore.beginAttempt(ORIGIN)
                }
                assertTrue(guarded is RemoteRoleGuardResult.Begun)

                val ownerCallsBefore = guardFixture.ownerTransport.totalCalls
                guardFixture.viewModel.confirmHostActivation(lease, confirmationId, false)
                val afterAttempt = guardFixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT && !it.actionInProgress
                }

                assertFalse(afterAttempt.canActivateHost)
                assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, afterAttempt.host.phase)
                assertEquals(ownerCallsBefore, guardFixture.ownerTransport.totalCalls)
                assertEquals(0, guardFixture.hostFile.writeCount)
                assertEquals(RemoteSessionPresence.Absent, guardFixture.hostStore.presence())
                assertEquals(RemoteSessionPresence.Present, guardFixture.supportStore.presence())
            } finally {
                guardFixture.close()
            }
        }

    @Test
    fun signOutPreservesSupportSessionAndKeepsHostPresentWhenHostJournalExists() = runBlocking {
        // Part A: Sign-out Google does NOT delete support session and support stays usable.
        val supportFixture = Fixture(initialAccount = OWNER)
        try {
            supportFixture.seedApprovedSupport()
            supportFixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.ValidateSupportInstallation ->
                        pairingResponse(200, supportInstallationJson(generation = "2"))

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease = supportFixture.viewModel.attachSurface()
            supportFixture.awaitState {
                it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    it.support.phase == SupportPairingPhase.CONFIRMED
            }

            supportFixture.viewModel.signOut(lease)
            val afterSignOut = supportFixture.awaitState {
                it.account == null && !it.actionInProgress
            }

            assertEquals(RemoteAccessRoleMode.SUPPORT_PRESENT, afterSignOut.roleMode)
            assertTrue(afterSignOut.canEnterSupportFlow)
            assertFalse(afterSignOut.canActivateHost)
            assertEquals(0, supportFixture.supportFile.deleteCount)
            assertEquals(RemoteSessionPresence.Present, supportFixture.supportStore.presence())

            // Refresh support status STILL works with zero Google account.
            supportFixture.viewModel.refreshSupportStatus(lease)
            supportFixture.awaitState {
                it.support.phase == SupportPairingPhase.CONFIRMED && !it.supportActionInProgress
            }
            assertEquals(2, supportFixture.pairingTransport.totalCalls)
        } finally {
            supportFixture.close()
        }

        // Part B: Sign-out Google does NOT delete host journal and STILL blocks support redemption.
        val hostFixture = Fixture(initialAccount = OWNER)
        try {
            hostFixture.seedActiveHost()
            hostFixture.ownerTransport.installationResponse = {
                ownerResponse(200, hostInstallationJson())
            }

            val lease = hostFixture.viewModel.attachSurface()
            hostFixture.awaitState {
                it.roleMode == RemoteAccessRoleMode.HOST_PRESENT && it.canManageSupports
            }

            hostFixture.viewModel.signOut(lease)
            val afterSignOut = hostFixture.awaitState {
                it.account == null && !it.actionInProgress
            }

            assertEquals(RemoteAccessRoleMode.HOST_PRESENT, afterSignOut.roleMode)
            assertFalse(afterSignOut.canManageSupports)
            assertFalse(afterSignOut.canEnterSupportFlow)
            assertEquals(0, hostFixture.hostFile.deleteCount)
            assertEquals(RemoteSessionPresence.Present, hostFixture.hostStore.presence())

            hostFixture.viewModel.submitSupportInvitation(lease, VALID_INVITATION_SECRET, "Blocked")
            assertEquals(0, hostFixture.supportStore.beginAttemptCalls)
            assertEquals(0, hostFixture.pairingTransport.totalCalls)
        } finally {
            hostFixture.close()
        }
    }

    @Test
    fun invitationForExplicitCopyReturnsSecretOnlyForCurrentLeaseAndNeverLeaksSecretsToUiState() =
        runBlocking {
            val hostFixture = Fixture(initialAccount = OWNER)
            try {
                hostFixture.seedActiveHost()
                hostFixture.ownerTransport.installationResponse = {
                    ownerResponse(200, hostInstallationJson())
                }
                hostFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        is PairingAccessOperation.ListInvitations ->
                            pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))

                        is PairingAccessOperation.ListRequests ->
                            pairingResponse(200, requestsListJson())

                        is PairingAccessOperation.ListSupports ->
                            pairingResponse(200, supportsListJson())

                        is PairingAccessOperation.IssueInvitation ->
                            pairingResponse(201, issuedInvitationJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }

                val lease = hostFixture.viewModel.attachSurface()
                hostFixture.awaitState { it.canManageSupports }

                hostFixture.viewModel.openHostSupports(lease)
                hostFixture.awaitState {
                    it.hostSupports.phase == HostSupportPhase.READY &&
                        !it.hostSupportActionInProgress
                }

                hostFixture.viewModel.issueSupportInvitation(lease)
                val emittedState = hostFixture.awaitState {
                    it.hostSupports.emittedInvitation?.copyAvailable == true &&
                        !it.hostSupportActionInProgress
                }

                // UI state MUST NEVER expose host PAT or invitation secret!
                val uiDump = emittedState.toString()
                assertFalse(uiDump.contains(HOST_PAT))
                assertFalse(uiDump.contains(VALID_INVITATION_SECRET))
                assertFalse(emittedState.hostSupports.toString().contains(HOST_PAT))
                assertFalse(emittedState.hostSupports.toString().contains(VALID_INVITATION_SECRET))

                // Current lease CAN obtain the invitation secret for explicit copy.
                val secret = hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1)
                assertNotNull(secret)
                assertEquals(VALID_INVITATION_SECRET, requireNotNull(secret).reveal())
                assertEquals("[REDACTED]", secret.toString())

                // Stale lease MUST return null.
                assertNull(hostFixture.viewModel.invitationForExplicitCopy(lease + 99L, INVITATION_ID_1))

                // Detached lease MUST return null.
                hostFixture.viewModel.detachSurface(lease)
                assertNull(hostFixture.viewModel.invitationForExplicitCopy(lease, INVITATION_ID_1))
            } finally {
                hostFixture.close()
            }

            // Support state MUST NEVER expose support PAT or invitation secret!
            val supportFixture = Fixture(initialAccount = null)
            try {
                supportFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.RedeemInvitation ->
                            pairingResponse(201, redemptionJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }
                val lease = supportFixture.viewModel.attachSurface()
                supportFixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

                supportFixture.viewModel.submitSupportInvitation(
                    lease = lease,
                    invitationSecret = VALID_INVITATION_SECRET,
                    installationLabel = "Tablet",
                )
                val pending = supportFixture.awaitState {
                    it.support.phase == SupportPairingPhase.PENDING &&
                        !it.supportActionInProgress
                }
                val dump = pending.toString()
                assertFalse(dump.contains(SUPPORT_PAT))
                assertFalse(dump.contains(VALID_INVITATION_SECRET))
            } finally {
                supportFixture.close()
            }
        }

    @Test
    fun hostSessionInvalidationBySignOutOrAccountChangeClearsAndBlocksSupportDecisionConfirmation() =
        runBlocking {
            // Part A: signOut(lease) clears open confirmation and blocks new requestHostSupportDecision
            val signOutFixture = Fixture(initialAccount = OWNER)
            try {
                signOutFixture.seedActiveHost()
                signOutFixture.ownerTransport.installationResponse = {
                    ownerResponse(200, hostInstallationJson())
                }
                signOutFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        is PairingAccessOperation.ListInvitations ->
                            pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))

                        is PairingAccessOperation.ListRequests ->
                            pairingResponse(200, requestsListJson())

                        is PairingAccessOperation.ListSupports ->
                            pairingResponse(200, supportsListJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }

                val lease = signOutFixture.viewModel.attachSurface()
                signOutFixture.awaitState { it.canManageSupports }

                signOutFixture.viewModel.openHostSupports(lease)
                signOutFixture.awaitState {
                    it.hostSupports.phase == HostSupportPhase.READY &&
                        !it.hostSupportActionInProgress
                }

                val opened = signOutFixture.viewModel.requestHostSupportDecision(
                    lease = lease,
                    action = HostSupportDecisionAction.APPROVE_REQUEST,
                    targetId = REQUEST_ID_1,
                )
                assertNotNull(opened)
                signOutFixture.awaitState { it.hostSupports.pendingConfirmation != null }

                signOutFixture.viewModel.signOut(lease)
                val afterSignOut = signOutFixture.awaitState {
                    it.account == null && !it.actionInProgress
                }

                assertEquals(RemoteAccessRoleMode.HOST_PRESENT, afterSignOut.roleMode)
                assertFalse(afterSignOut.canManageSupports)
                assertNull(afterSignOut.hostSupports.pendingConfirmation)

                val blocked = signOutFixture.viewModel.requestHostSupportDecision(
                    lease = lease,
                    action = HostSupportDecisionAction.APPROVE_REQUEST,
                    targetId = REQUEST_ID_1,
                )
                assertNull(blocked)
                assertNull(signOutFixture.viewModel.uiState.value.hostSupports.pendingConfirmation)
            } finally {
                signOutFixture.close()
            }

            // Part B: external account change clears open confirmation and blocks new decision
            val accountChangeFixture = Fixture(initialAccount = OWNER)
            try {
                accountChangeFixture.seedActiveHost()
                accountChangeFixture.ownerTransport.installationResponse = {
                    ownerResponse(200, hostInstallationJson())
                }
                accountChangeFixture.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        is PairingAccessOperation.ListInvitations ->
                            pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))

                        is PairingAccessOperation.ListRequests ->
                            pairingResponse(200, requestsListJson())

                        is PairingAccessOperation.ListSupports ->
                            pairingResponse(200, supportsListJson())

                        else -> error("Unexpected operation: $operation")
                    }
                }

                val lease = accountChangeFixture.viewModel.attachSurface()
                accountChangeFixture.awaitState { it.canManageSupports }

                accountChangeFixture.viewModel.openHostSupports(lease)
                accountChangeFixture.awaitState {
                    it.hostSupports.phase == HostSupportPhase.READY &&
                        !it.hostSupportActionInProgress
                }

                val opened = accountChangeFixture.viewModel.requestHostSupportDecision(
                    lease = lease,
                    action = HostSupportDecisionAction.APPROVE_REQUEST,
                    targetId = REQUEST_ID_1,
                )
                assertNotNull(opened)
                accountChangeFixture.awaitState { it.hostSupports.pendingConfirmation != null }

                accountChangeFixture.identity.changeAccount(
                    OwnerAccount("firebase-project", "other-firebase-uid", "Other"),
                )
                val afterAccountChange = accountChangeFixture.awaitState {
                    it.account?.uid == "other-firebase-uid" && !it.canManageSupports
                }

                assertEquals(RemoteAccessRoleMode.HOST_PRESENT, afterAccountChange.roleMode)
                assertNull(afterAccountChange.hostSupports.pendingConfirmation)

                val blocked = accountChangeFixture.viewModel.requestHostSupportDecision(
                    lease = lease,
                    action = HostSupportDecisionAction.APPROVE_REQUEST,
                    targetId = REQUEST_ID_1,
                )
                assertNull(blocked)
                assertNull(
                    accountChangeFixture.viewModel.uiState.value.hostSupports.pendingConfirmation,
                )
            } finally {
                accountChangeFixture.close()
            }
        }

    @Test
    fun immediateFollowUpActionTriggeredWhenActionInProgressClearsIsNotDropped() = runBlocking {
        val immediateDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                block.run()
            }
        }
        // 1. Support action lane (supportActionInProgress true -> false)
        val supportFixture = Fixture(initialAccount = null)
        val firstRefreshEntered = CompletableDeferred<Unit>()
        val releaseFirstRefresh = CompletableDeferred<Unit>()
        val secondRefreshEntered = CompletableDeferred<Unit>()
        val supportCalls = AtomicInteger(0)
        try {
            supportFixture.seedApprovedSupport()
            supportFixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.ValidateSupportInstallation -> {
                        when (supportCalls.incrementAndGet()) {
                            1 -> pairingResponse(200, supportInstallationJson(generation = "2"))
                            2 -> {
                                firstRefreshEntered.complete(Unit)
                                releaseFirstRefresh.await()
                                pairingResponse(200, supportInstallationJson(generation = "2"))
                            }

                            3 -> {
                                secondRefreshEntered.complete(Unit)
                                pairingResponse(200, supportInstallationJson(generation = "2"))
                            }

                            else -> pairingResponse(200, supportInstallationJson(generation = "2"))
                        }
                    }

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease = supportFixture.viewModel.attachSurface()
            supportFixture.awaitState {
                it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    it.support.phase == SupportPairingPhase.CONFIRMED
            }

            var sawSupportInProgress = false
            var triggeredFollowUp = false
            val collector = launch(immediateDispatcher, start = CoroutineStart.UNDISPATCHED) {
                supportFixture.viewModel.uiState.collect { state ->
                    if (state.supportActionInProgress) {
                        sawSupportInProgress = true
                    } else if (sawSupportInProgress && !triggeredFollowUp) {
                        triggeredFollowUp = true
                        supportFixture.viewModel.refreshSupportStatus(lease)
                    }
                }
            }
            try {
                supportFixture.viewModel.refreshSupportStatus(lease)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { firstRefreshEntered.await() }
                releaseFirstRefresh.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { secondRefreshEntered.await() }
                assertTrue(triggeredFollowUp)
                assertEquals(3, supportCalls.get())
            } finally {
                collector.cancel()
            }
        } finally {
            releaseFirstRefresh.complete(Unit)
            supportFixture.close()
        }

        // 2. Host supports action lane (hostSupportActionInProgress true -> false)
        val hostSupportFixture = Fixture(initialAccount = OWNER)
        val firstHostRefreshEntered = CompletableDeferred<Unit>()
        val releaseFirstHostRefresh = CompletableDeferred<Unit>()
        val secondHostRefreshEntered = CompletableDeferred<Unit>()
        val listInvitationsCalls = AtomicInteger(0)
        try {
            hostSupportFixture.seedActiveHost()
            hostSupportFixture.ownerTransport.installationResponse = {
                ownerResponse(200, hostInstallationJson())
            }
            hostSupportFixture.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    is PairingAccessOperation.ListInvitations -> {
                        when (listInvitationsCalls.incrementAndGet()) {
                            1 -> {
                                firstHostRefreshEntered.complete(Unit)
                                releaseFirstHostRefresh.await()
                                pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))
                            }

                            2 -> {
                                secondHostRefreshEntered.complete(Unit)
                                pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))
                            }

                            else -> pairingResponse(200, invitationsListJson(listOf(INVITATION_ID_1)))
                        }
                    }

                    is PairingAccessOperation.ListRequests ->
                        pairingResponse(200, requestsListJson())

                    is PairingAccessOperation.ListSupports ->
                        pairingResponse(200, supportsListJson())

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease = hostSupportFixture.viewModel.attachSurface()
            hostSupportFixture.awaitState { it.canManageSupports }

            var sawHostSupportInProgress = false
            var triggeredFollowUp = false
            val collector = launch(immediateDispatcher, start = CoroutineStart.UNDISPATCHED) {
                hostSupportFixture.viewModel.uiState.collect { state ->
                    if (state.hostSupportActionInProgress) {
                        sawHostSupportInProgress = true
                    } else if (sawHostSupportInProgress && !triggeredFollowUp) {
                        triggeredFollowUp = true
                        hostSupportFixture.viewModel.refreshHostSupports(lease)
                    }
                }
            }
            try {
                hostSupportFixture.viewModel.openHostSupports(lease)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { firstHostRefreshEntered.await() }
                releaseFirstHostRefresh.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { secondHostRefreshEntered.await() }
                assertTrue(triggeredFollowUp)
                assertEquals(2, listInvitationsCalls.get())
            } finally {
                collector.cancel()
            }
        } finally {
            releaseFirstHostRefresh.complete(Unit)
            hostSupportFixture.close()
        }

        // 3. Host owner action lane (actionInProgress true -> false)
        val ownerFixture = Fixture(initialAccount = OWNER)
        val firstDiscoverEntered = CompletableDeferred<Unit>()
        val releaseFirstDiscover = CompletableDeferred<Unit>()
        val secondDiscoverEntered = CompletableDeferred<Unit>()
        val discoverCalls = AtomicInteger(0)
        try {
            ownerFixture.ownerTransport.ownerMonitorResponse = {
                when (discoverCalls.incrementAndGet()) {
                    1 -> {
                        firstDiscoverEntered.complete(Unit)
                        releaseFirstDiscover.await()
                        ownerResponse(200, EMPTY_OWNER_MONITOR)
                    }

                    2 -> {
                        secondDiscoverEntered.complete(Unit)
                        ownerResponse(200, EMPTY_OWNER_MONITOR)
                    }

                    else -> ownerResponse(200, EMPTY_OWNER_MONITOR)
                }
            }

            val lease = ownerFixture.viewModel.attachSurface()
            ownerFixture.awaitState { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

            var sawHostInProgress = false
            var triggeredFollowUp = false
            val collector = launch(immediateDispatcher, start = CoroutineStart.UNDISPATCHED) {
                ownerFixture.viewModel.uiState.collect { state ->
                    if (state.actionInProgress) {
                        sawHostInProgress = true
                    } else if (sawHostInProgress && !triggeredFollowUp) {
                        triggeredFollowUp = true
                        ownerFixture.viewModel.discoverOwnerMonitor(lease)
                    }
                }
            }
            try {
                ownerFixture.viewModel.discoverOwnerMonitor(lease)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { firstDiscoverEntered.await() }
                releaseFirstDiscover.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { secondDiscoverEntered.await() }
                assertTrue(triggeredFollowUp)
                assertEquals(2, discoverCalls.get())
            } finally {
                collector.cancel()
            }
        } finally {
            releaseFirstDiscover.complete(Unit)
            ownerFixture.close()
        }
    }

    @Test
    fun defaultOriginRemainsEmptyAndAttachWithUnconfiguredOriginPerformsZeroNetworkOrWrites() =
        runBlocking {
            assertEquals("", BuildConfig.OWNER_API_ORIGIN)
            val defaultTransport = HttpsPairingAccessTransport(BuildConfig.OWNER_API_ORIGIN)
            assertNull(defaultTransport.origin)
            assertEquals(
                PairingAccessFailureReason.NOT_CONFIGURED,
                defaultTransport.configurationFailure,
            )

            val fixture = Fixture(origin = null)
            try {
                fixture.viewModel.attachSurface()
                val state = fixture.awaitState {
                    it.roleMode == RemoteAccessRoleMode.UNASSIGNED &&
                        it.support.phase == SupportPairingPhase.RECOVERABLE_ERROR &&
                        it.support.safeFailure == PairingAccessFailureReason.NOT_CONFIGURED
                }
                assertNull(state.configuredOrigin)
                assertEquals(0, fixture.ownerTransport.totalCalls)
                assertEquals(0, fixture.pairingTransport.totalCalls)
                assertEquals(0, fixture.hostFile.writeCount)
                assertEquals(0, fixture.supportFile.writeCount)
            } finally {
                fixture.close()
            }
        }

    private class Fixture(
        initialAccount: OwnerAccount? = null,
        private val origin: String? = ORIGIN,
    ) : AutoCloseable {
        val identity = FakeOwnerIdentity(initialAccount)
        val ownerTransport = ScriptedOwnerTransport(origin)
        val ownerAccessClient = OwnerAccessClient(ownerTransport)
        val pairingTransport = ScriptedPairingTransport(origin)
        val pairingAccessClient = PairingAccessClient(pairingTransport)
        val hostFile = MemoryAtomicRemoteHostFile()
        val supportFile = MemoryAtomicRemoteHostFile()
        val hostStore = RecordingHostSessionStore(
            PersistentRemoteHostSessionStore(hostFile, JceTestKeyProvider(), Dispatchers.Unconfined),
        )
        val supportStore = RecordingSupportSessionStore(
            PersistentRemoteSupportSessionStore(supportFile, JceTestKeyProvider(), Dispatchers.Unconfined),
        )
        val roleGuard = RemoteRoleGuard(
            hostPresence = { hostStore.presence() },
            supportPresence = { supportStore.presence() },
        )
        private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModelStore = ViewModelStore()
        val viewModel: RemoteAccessViewModel = ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemoteAccessViewModel(
                        identity = identity,
                        ownerAccessClient = ownerAccessClient,
                        sessionStore = hostStore,
                        pairingAccessClient = pairingAccessClient,
                        supportSessionStore = supportStore,
                        roleGuard = roleGuard,
                        configuredPairingOrigin = { ownerAccessClient.canonicalOrigin },
                        baseCoroutineContext = testScope.coroutineContext,
                    ) as T
            },
        )[RemoteAccessViewModel::class.java]

        suspend fun awaitState(predicate: (RemoteAccessUiState) -> Boolean): RemoteAccessUiState =
            withTimeout(BARRIER_TIMEOUT_MILLIS) {
                viewModel.uiState.first(predicate)
            }

        suspend fun seedActiveHost() {
            val context = RemoteHostOwnerContext(OWNER, ORIGIN)
            val attempt = hostStore.beginAttempt(context, generation("0"))
            assertTrue(attempt is RemoteHostSessionWriteResult.PendingAttemptStarted)
            val activation = HostActivation(
                monitorId = MONITOR_ID,
                installationId = HOST_INSTALLATION_ID,
                membershipId = HOST_MEMBERSHIP_ID,
                generation = generation("1"),
                accessToken = SecretValue.of(HOST_PAT),
                createdAtMillis = 1_790_000_000_000L,
                owner = OWNER,
                origin = ORIGIN,
            )
            assertEquals(
                RemoteHostSessionWriteResult.ActiveSessionCommitted,
                hostStore.commitAttempt(
                    context,
                    (attempt as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId,
                    activation,
                ),
            )
        }

        suspend fun seedPendingSupport(): RemoteSupportSessionRecord.Pending {
            val attempt = supportStore.beginAttempt(ORIGIN)
            assertTrue(attempt is RemoteSupportSessionWriteResult.AttemptStarted)
            val attemptRecord = (attempt as RemoteSupportSessionWriteResult.AttemptStarted).record
            val committed = supportStore.commitPending(
                origin = ORIGIN,
                attemptId = attemptRecord.attemptId,
                candidate = PairingCandidateInstallation(
                    origin = ORIGIN,
                    requestId = REQUEST_ID_1,
                    installationId = SUPPORT_INSTALLATION_ID,
                    requestExpiresAtMillis = 1_795_133_700_000L,
                    accessToken = SecretValue.of(SUPPORT_PAT),
                    status = "Pending",
                ),
            )
            assertTrue(committed is RemoteSupportSessionWriteResult.Committed)
            return (committed as RemoteSupportSessionWriteResult.Committed).record
                as RemoteSupportSessionRecord.Pending
        }

        suspend fun seedApprovedSupport(): RemoteSupportSessionRecord.Approved {
            val pending = seedPendingSupport()
            val committed = supportStore.commitApproved(
                expectedRecord = pending,
                supportInstallation = PairingSupportInstallation(
                    installationId = SUPPORT_INSTALLATION_ID,
                    monitorId = MONITOR_ID,
                    membershipId = MEMBERSHIP_ID,
                    role = "Support",
                    status = "Active",
                    monitorGeneration = generation("2"),
                ),
            )
            assertTrue(committed is RemoteSupportSessionWriteResult.Committed)
            return (committed as RemoteSupportSessionWriteResult.Committed).record
                as RemoteSupportSessionRecord.Approved
        }

        override fun close() {
            viewModelStore.clear()
            testScope.cancel()
        }
    }

    private class FakeOwnerIdentity(
        initialAccount: OwnerAccount?,
    ) : OwnerIdentity {
        private val mutableAccount = MutableStateFlow(initialAccount)
        override val account: StateFlow<OwnerAccount?> = mutableAccount

        var signInCalls = 0
        var reauthenticateCalls = 0
        var signOutCalls = 0

        fun changeAccount(next: OwnerAccount?) {
            mutableAccount.value = next
        }

        override suspend fun signIn(googleIdToken: SecretValue): IdentityResult {
            signInCalls += 1
            mutableAccount.value = OWNER
            return IdentityResult(IdentityOutcome.SIGNED_IN_CANDIDATE)
        }

        override suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult {
            reauthenticateCalls += 1
            return IdentityResult(IdentityOutcome.REAUTHENTICATED)
        }

        override suspend fun firebaseIdToken(): SecretValue =
            SecretValue.of("synthetic-firebase-id-token")

        override suspend fun signOut(): IdentityResult {
            signOutCalls += 1
            mutableAccount.value = null
            return IdentityResult(IdentityOutcome.SIGNED_OUT)
        }
    }

    private class ScriptedOwnerTransport(
        override val origin: String?,
    ) : OwnerAccessTransport {
        override val configurationFailure: OwnerAccessFailureReason? =
            if (origin == null) OwnerAccessFailureReason.NOT_CONFIGURED else null

        var totalCalls = 0
        var ownerMonitorResponse: suspend () -> OwnerAccessTransportResult = {
            ownerResponse(200, EMPTY_OWNER_MONITOR)
        }
        var hostActivationResponse: suspend () -> OwnerAccessTransportResult = {
            ownerResponse(201, HOST_ACTIVATION_JSON)
        }
        var installationResponse: suspend () -> OwnerAccessTransportResult = {
            ownerResponse(200, hostInstallationJson())
        }

        override suspend fun execute(
            method: OwnerAccessMethod,
            path: OwnerAccessPath,
            credential: OwnerAccessCredential,
            body: String?,
        ): OwnerAccessTransportResult {
            totalCalls += 1
            return when (path) {
                OwnerAccessPath.OWNER_MONITOR -> ownerMonitorResponse()
                OwnerAccessPath.HOST_ACTIVATIONS -> hostActivationResponse()
                OwnerAccessPath.INSTALLATION -> installationResponse()
            }
        }
    }

    private class ScriptedPairingTransport(
        override val origin: String?,
        var handler: (suspend (PairingAccessOperation, PairingAccessCredential?, PairingAccessRequestBody?) -> PairingAccessTransportResult)? = null,
    ) : PairingAccessTransport {
        override val configurationFailure: PairingAccessFailureReason? =
            if (origin == null) PairingAccessFailureReason.NOT_CONFIGURED else null

        var totalCalls = 0

        override suspend fun execute(
            operation: PairingAccessOperation,
            credential: PairingAccessCredential?,
            body: PairingAccessRequestBody?,
        ): PairingAccessTransportResult {
            totalCalls += 1
            val currentHandler = handler ?: error("Unexpected pairing network call: $operation")
            return currentHandler(operation, credential, body)
        }
    }

    private class RecordingHostSessionStore(
        private val delegate: RemoteHostSessionStore,
    ) : RemoteHostSessionStore by delegate {
        var loadCalls = 0

        override suspend fun load(expectedContext: RemoteHostOwnerContext): RemoteHostSessionLoad {
            loadCalls += 1
            return delegate.load(expectedContext)
        }
    }

    private class RecordingSupportSessionStore(
        private val delegate: RemoteSupportSessionStore,
    ) : RemoteSupportSessionStore by delegate {
        var loadCalls = 0
        var beginAttemptCalls = 0

        override suspend fun load(origin: String): RemoteSupportSessionLoad {
            loadCalls += 1
            return delegate.load(origin)
        }

        override suspend fun beginAttempt(
            origin: String,
            expectedPreviousAttempt: RemoteSupportAttemptId?,
        ): RemoteSupportSessionWriteResult {
            beginAttemptCalls += 1
            return delegate.beginAttempt(origin, expectedPreviousAttempt)
        }
    }

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var failReads = false
        private var stored: ByteArray? = null
        var writeCount = 0
        var deleteCount = 0

        override fun readRecord(maximumBytes: Int): ByteArray? {
            if (failReads) {
                throw IOException("Synthetic storage read error")
            }
            val current = stored ?: return null
            return current.copyOf(minOf(current.size, maximumBytes))
        }

        override fun writeAtomically(record: ByteArray) {
            writeCount += 1
            stored = record.copyOf()
        }

        override fun deleteRecord() {
            deleteCount += 1
            stored = null
        }
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        private var key: SecretKey? = null

        override fun existingKey(): SecretKey? = key

        override fun keyForNewRecord(): SecretKey =
            key ?: SecretKeySpec(ByteArray(32) { index -> (index + 17).toByte() }, "AES").also {
                key = it
            }
    }

    private companion object {
        const val BARRIER_TIMEOUT_MILLIS = 5_000L
        const val ORIGIN = "https://api.example.test"
        const val MONITOR_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val HOST_INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440001"
        const val HOST_MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440002"
        const val INVITATION_ID_1 = "550e8400-e29b-41d4-a716-446655440003"
        const val REQUEST_ID_1 = "550e8400-e29b-41d4-a716-446655440004"
        const val SUPPORT_INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440005"
        const val MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440006"
        const val HOST_PAT = "1|synthetic-host-pat-secret"
        const val SUPPORT_PAT = "1|synthetic-support-pat-secret"
        const val VALID_INVITATION_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")

        const val EMPTY_OWNER_MONITOR =
            """{"data":{"monitor":null,"generation":"0","host":null,"requiresReference":true}}"""

        const val HOST_ACTIVATION_JSON =
            """{"data":{"monitorId":"$MONITOR_ID","installationId":"$HOST_INSTALLATION_ID","membershipId":"$HOST_MEMBERSHIP_ID","role":"Host","generation":"1","requiresReference":true,"accessToken":"$HOST_PAT","createdAt":1790000000000}}"""

        fun generation(value: String): OwnerGeneration = requireNotNull(OwnerGeneration.parse(value))

        fun ownerResponse(statusCode: Int, body: String): OwnerAccessTransportResult.Response =
            OwnerAccessTransportResult.Response(
                statusCode = statusCode,
                body = body.toByteArray(StandardCharsets.UTF_8),
            )

        fun pairingResponse(statusCode: Int, body: String): PairingAccessTransportResult.Response =
            PairingAccessTransportResult.Response(
                statusCode = statusCode,
                body = body.toByteArray(StandardCharsets.UTF_8),
            )

        fun hostInstallationJson(): String =
            """{"data":{"installationId":"$HOST_INSTALLATION_ID","monitorId":"$MONITOR_ID","membershipId":"$HOST_MEMBERSHIP_ID","role":"Host","status":"Active","monitorGeneration":"1"}}"""

        fun redemptionJson(): String =
            """{"data":{"requestId":"$REQUEST_ID_1","installationId":"$SUPPORT_INSTALLATION_ID","status":"Pending","requestExpiresAt":1795133700000,"accessToken":"$SUPPORT_PAT"}}"""

        fun pendingCurrentRequestJson(): String =
            """{"data":{"id":"$REQUEST_ID_1","status":"Pending","requestExpiresAt":1795133700000,"decidedAt":null,"monitor":null}}"""

        fun approvedCurrentRequestJson(): String =
            """{"data":{"id":"$REQUEST_ID_1","status":"Approved","requestExpiresAt":1795133700000,"decidedAt":1795132800000,"monitor":{"id":"$MONITOR_ID","membershipId":"$MEMBERSHIP_ID","role":"Support"}}}"""

        fun supportInstallationJson(generation: String): String =
            """{"data":{"installationId":"$SUPPORT_INSTALLATION_ID","monitorId":"$MONITOR_ID","membershipId":"$MEMBERSHIP_ID","role":"Support","status":"Active","monitorGeneration":"$generation"}}"""

        fun issuedInvitationJson(): String =
            """{"data":{"id":"$INVITATION_ID_1","invitationSecret":"$VALID_INVITATION_SECRET","createdAt":1795130000000,"expiresAt":1795130900000}}"""

        fun invitationsListJson(ids: List<String>): String =
            ids.joinToString(prefix = """{"data":[""", postfix = """]}""") { id ->
                """{"id":"$id","status":"Available","createdAt":1795130000000,"expiresAt":1795130900000}"""
            }

        fun requestsListJson(): String =
            """{"data":[{"id":"$REQUEST_ID_1","status":"Pending","createdAt":1795130100000,"requestExpiresAt":1795131000000,"installation":{"id":"$SUPPORT_INSTALLATION_ID","label":"Tablet Sala"}}]}"""

        fun supportsListJson(): String =
            """{"data":[{"membershipId":"$MEMBERSHIP_ID","installationId":"$SUPPORT_INSTALLATION_ID","label":"Celular Maria","role":"Support","status":"Active","approvedAt":1795130200000}]}"""
    }
}
