package br.com.c6tag.diagnostico.remote.access

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPairingCoordinatorTest {
    @Test
    fun initializeOnEmptyStorePerformsZeroNetworkCallsAndHandlesInvalidOrigin() = runBlocking {
        val transport = ScriptedPairingTransport()
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        var currentOrigin: String? = ORIGIN
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { currentOrigin },
            parentScope = scope,
        )

        try {
            coordinator.initialize()
            assertEquals(SupportPairingPhase.EMPTY, coordinator.state.value.phase)
            assertEquals(0, transport.totalCalls)
            assertEquals(0, file.writeCount)
            assertEquals(0, file.deleteCount)

            currentOrigin = null
            coordinator.initialize()
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.NOT_CONFIGURED, coordinator.state.value.safeFailure)
            assertEquals(0, transport.totalCalls)

            currentOrigin = "http://insecure.example.test"
            coordinator.initialize()
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.INVALID_ORIGIN, coordinator.state.value.safeFailure)
            assertEquals(0, transport.totalCalls)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun submitInvitationValidatesSecretLabelAndOriginLocallyBeforeGuardStoreOrNetwork() = runBlocking {
        val events = mutableListOf<String>()
        val transport = ScriptedPairingTransport(events = events)
        val file = MemoryAtomicRemoteHostFile()
        val store = RecordingSupportSessionStore(
            delegate = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined),
            events = events,
        )
        var hostPresenceReads = 0
        val guard = RemoteRoleGuard(
            hostPresence = {
                hostPresenceReads += 1
                RemoteSessionPresence.Absent
            },
            supportPresence = { store.presence() },
        )
        var configuredOrigin: String? = ORIGIN
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { configuredOrigin },
            parentScope = scope,
        )

        try {
            coordinator.submitInvitation("short-secret", "Tablet")
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, coordinator.state.value.safeFailure)

            coordinator.submitInvitation(VALID_INVITATION_SECRET, "a".repeat(129))
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, coordinator.state.value.safeFailure)

            coordinator.submitInvitation(VALID_INVITATION_SECRET, "\uD800")
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, coordinator.state.value.safeFailure)

            configuredOrigin = ""
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.NOT_CONFIGURED, coordinator.state.value.safeFailure)

            assertEquals(0, hostPresenceReads)
            assertEquals(0, store.beginAttemptCalls)
            assertEquals(0, transport.totalCalls)
            assertTrue(events.isEmpty())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun submitInvitationTrimsOuterWhitespaceReservesGuardBeforePostPersistsPendingBeforePublishingAndRedactsSecrets() =
        runBlocking {
            val events = mutableListOf<String>()
            val postStarted = CompletableDeferred<Unit>()
            val releasePost = CompletableDeferred<Unit>()
            val transport = ScriptedPairingTransport(events = events) { operation, credential, body ->
                assertEquals(PairingAccessOperation.RedeemInvitation, operation)
                assertNull(credential)
                val json = requireNotNull(body).utf8()
                assertTrue(json.contains("\"invitationSecret\":\"$VALID_INVITATION_SECRET\""))
                assertTrue(json.contains("\"installationLabel\":\"Tablet Sala\""))
                postStarted.complete(Unit)
                releasePost.await()
                PairingAccessTransportResult.Response(
                    statusCode = 201,
                    body = redemptionJson(),
                )
            }
            val file = MemoryAtomicRemoteHostFile()
            val delegateStore = PersistentRemoteSupportSessionStore(
                file = file,
                keys = JceTestKeyProvider(),
                ioDispatcher = Dispatchers.Unconfined,
            )
            lateinit var coordinator: SupportPairingCoordinator
            val store = RecordingSupportSessionStore(
                delegate = delegateStore,
                events = events,
                onCommitPending = {
                    assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)
                },
            )
            val guard = RemoteRoleGuard(
                hostPresence = {
                    events += "guard.hostPresence"
                    RemoteSessionPresence.Absent
                },
                supportPresence = { store.presence() },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            coordinator = SupportPairingCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                sessionStore = store,
                roleGuard = guard,
                configuredOrigin = { ORIGIN },
                parentScope = scope,
            )

            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.submitInvitation(
                    invitationSecret = "  \n$VALID_INVITATION_SECRET\t ",
                    installationLabel = "Tablet Sala",
                )
            }
            try {
                withTimeout(BARRIER_TIMEOUT_MILLIS) { postStarted.await() }
                assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)
                assertTrue(delegateStore.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)

                // Prove roleGuard lock is already released before HTTP completes.
                val hostReserveDuringPost = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    guard.beginHostAttempt { "must-not-run" }
                }
                assertEquals(
                    RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                    hostReserveDuringPost,
                )

                releasePost.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { job.join() }

                val state = coordinator.state.value
                assertEquals(SupportPairingPhase.PENDING, state.phase)
                assertEquals(
                    PendingSupportPairingInfo(
                        requestId = REQUEST_ID,
                        installationId = INSTALLATION_ID,
                        requestExpiresAtMillis = REQUEST_EXPIRES_AT,
                    ),
                    state.pending,
                )
                assertFalse(state.previousAttemptMayRemainActiveOnHost)
                assertFalse(state.toString().contains(SYNTHETIC_PAT))
                assertFalse(state.toString().contains(VALID_INVITATION_SECRET))

                val persisted = delegateStore.load(ORIGIN) as RemoteSupportSessionLoad.Pending
                assertEquals(SYNTHETIC_PAT, persisted.record.accessToken.reveal())
                assertEquals(
                    listOf(
                        "guard.hostPresence",
                        "store.beginAttempt",
                        "transport.POST:/api/v1/pairing-requests",
                        "store.commitPending",
                    ),
                    events,
                )
            } finally {
                releasePost.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) { job.cancelAndJoin() }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun concurrentSubmitInvitationAndRefreshWhileRedeemingAreDroppedSingleFlight() = runBlocking {
        val postStarted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        val transport = ScriptedPairingTransport { _, _, _ ->
            postStarted.complete(Unit)
            releasePost.await()
            PairingAccessTransportResult.Response(201, redemptionJson())
        }
        val store = PersistentRemoteSupportSessionStore(
            MemoryAtomicRemoteHostFile(),
            JceTestKeyProvider(),
            Dispatchers.Unconfined,
        )
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "First")
        }
        try {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { postStarted.await() }
            coordinator.submitInvitation(SECOND_INVITATION_SECRET, "Second")
            coordinator.refreshStatus()

            assertEquals(1, transport.postCount)
            assertEquals(0, transport.getCount)
            assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)

            releasePost.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { first.join() }

            assertEquals(1, transport.postCount)
            assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
        } finally {
            releasePost.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { first.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun knownNotWrittenAndNotSentFailuresClearFreshAttemptWithoutRetrying() = runBlocking {
        val scenarios = listOf(
            Triple(
                PairingAccessTransportResult.Response(
                    410,
                    errorJson("invitation_unavailable", "Invitation is no longer available."),
                ),
                PairingAccessFailureReason.INVITATION_UNAVAILABLE,
                null as Long?,
            ),
            Triple(
                PairingAccessTransportResult.Response(
                    422,
                    errorJson("invalid_payload", "Validation failed."),
                ),
                PairingAccessFailureReason.INVALID_PAYLOAD,
                null,
            ),
            Triple(
                PairingAccessTransportResult.Response(
                    429,
                    errorJson("rate_limited", "Too many requests."),
                    retryAfterSeconds = 60L,
                ),
                PairingAccessFailureReason.RATE_LIMITED,
                60L,
            ),
            Triple(
                PairingAccessTransportResult.DispatchRejected,
                PairingAccessFailureReason.NETWORK_FAILURE,
                null,
            ),
        )

        scenarios.forEach { (transportResult, expectedReason, expectedRetryAfter) ->
            val transport = ScriptedPairingTransport { _, _, _ -> transportResult }
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val guard = RemoteRoleGuard(
                hostPresence = { RemoteSessionPresence.Absent },
                supportPresence = { store.presence() },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SupportPairingCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                sessionStore = store,
                roleGuard = guard,
                configuredOrigin = { ORIGIN },
                parentScope = scope,
            )
            try {
                coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")

                assertEquals(1, transport.postCount)
                assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
                assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(expectedReason, coordinator.state.value.safeFailure)
                assertEquals(expectedRetryAfter, coordinator.state.value.retryAfterSeconds)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun ambiguousPostFailuresPreserveAttemptAndPublishUncertainWithoutRetrying() = runBlocking {
        val scenarios = listOf(
            PairingAccessTransportResult.NetworkFailure to PairingAccessFailureReason.NETWORK_FAILURE,
            PairingAccessTransportResult.Response(
                500,
                errorJson("internal_error", "Unexpected server error."),
            ) to PairingAccessFailureReason.SERVICE_UNAVAILABLE,
            PairingAccessTransportResult.Response(
                201,
                "{\"data\":{\"broken\":true}}".toByteArray(StandardCharsets.UTF_8),
            ) to PairingAccessFailureReason.INVALID_RESPONSE,
        )

        scenarios.forEach { (transportResult, expectedReason) ->
            val transport = ScriptedPairingTransport { _, _, _ -> transportResult }
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val guard = RemoteRoleGuard(
                hostPresence = { RemoteSessionPresence.Absent },
                supportPresence = { store.presence() },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SupportPairingCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                sessionStore = store,
                roleGuard = guard,
                configuredOrigin = { ORIGIN },
                parentScope = scope,
            )
            try {
                coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")

                assertEquals(1, transport.postCount)
                assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
                assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
                assertEquals(expectedReason, coordinator.state.value.safeFailure)
                assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
                assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun cancellationDuringBeginAttemptAfterWriteSettlesToUncertainWithoutPost() = runBlocking {
        val attemptWritten = CompletableDeferred<Unit>()
        val releaseBegin = CompletableDeferred<Unit>()
        val transport = ScriptedPairingTransport()
        val delegateStore = PersistentRemoteSupportSessionStore(
            MemoryAtomicRemoteHostFile(),
            JceTestKeyProvider(),
            Dispatchers.Unconfined,
        )
        val store = RecordingSupportSessionStore(
            delegate = delegateStore,
            afterBeginAttempt = {
                attemptWritten.complete(Unit)
                releaseBegin.await()
            },
        )
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        val attemptJob = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
        }
        try {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { attemptWritten.await() }
            attemptJob.cancel(CancellationException("cancelled right after journal write"))
            val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                runCatching { attemptJob.await() }.exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
            assertEquals(0, transport.postCount)
            assertTrue(delegateStore.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
        } finally {
            releaseBegin.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { attemptJob.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationBeforePostStartsClearsFreshUnsentAttemptAndRethrows() = runBlocking {
        val transport = ScriptedPairingTransport()
        val delegateStore = PersistentRemoteSupportSessionStore(
            MemoryAtomicRemoteHostFile(),
            JceTestKeyProvider(),
            Dispatchers.Unconfined,
        )
        val submitJobControl = Job()
        val store = RecordingSupportSessionStore(
            delegate = delegateStore,
            afterBeginAttempt = {
                // Cancel caller just as beginAttempt returns, BEFORE POST dispatch begins.
                submitJobControl.cancel(CancellationException("cancelled before post"))
            },
        )
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        val submitJob = async(submitJobControl, start = CoroutineStart.UNDISPATCHED) {
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
        }
        try {
            val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                runCatching { submitJob.await() }.exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
            assertEquals(0, transport.postCount)
            assertEquals(RemoteSupportSessionLoad.Empty, delegateStore.load(ORIGIN))
        } finally {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationDuringInFlightPostPreservesAttemptAndPublishesUncertain() = runBlocking {
        val postStarted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        val transport = ScriptedPairingTransport { _, _, _ ->
            postStarted.complete(Unit)
            releasePost.await()
            PairingAccessTransportResult.Response(201, redemptionJson())
        }
        val store = PersistentRemoteSupportSessionStore(
            MemoryAtomicRemoteHostFile(),
            JceTestKeyProvider(),
            Dispatchers.Unconfined,
        )
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        val submitJob = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
        }
        try {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { postStarted.await() }
            submitJob.cancel(CancellationException("cancelled during post"))
            val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                runCatching { submitJob.await() }.exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
            assertEquals(1, transport.postCount)
            assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
        } finally {
            releasePost.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationImmediatelyAfterValid201CommitsPendingBeforePropagatingAndReopenResumes() =
        runBlocking {
            val file = MemoryAtomicRemoteHostFile()
            val keys = JceTestKeyProvider()
            val store = PersistentRemoteSupportSessionStore(file, keys, Dispatchers.Unconfined)
            val submitJobControl = Job()
            val transport = ScriptedPairingTransport { operation, credential, _ ->
                when (operation) {
                    PairingAccessOperation.RedeemInvitation -> {
                        // Valid 201 already received; caller cancels right before commitPending runs.
                        submitJobControl.cancel(CancellationException("surface closed after 201"))
                        PairingAccessTransportResult.Response(201, redemptionJson())
                    }

                    PairingAccessOperation.CurrentRequest -> {
                        val candidate = credential as PairingCandidateInstallation
                        assertEquals(SYNTHETIC_PAT, candidate.accessToken.reveal())
                        PairingAccessTransportResult.Response(200, currentPendingJson())
                    }

                    else -> throw AssertionError("Unexpected operation $operation")
                }
            }
            val guard = RemoteRoleGuard(
                hostPresence = { RemoteSessionPresence.Absent },
                supportPresence = { store.presence() },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val firstCoordinator = SupportPairingCoordinator(
                    pairingAccessClient = PairingAccessClient(transport),
                    sessionStore = store,
                    roleGuard = guard,
                    configuredOrigin = { ORIGIN },
                    parentScope = scope,
                )

                val submitJob = async(submitJobControl, start = CoroutineStart.UNDISPATCHED) {
                    firstCoordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
                }
                try {
                    val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        runCatching { submitJob.await() }.exceptionOrNull()
                    }
                    assertTrue(failure is CancellationException)

                    val persisted = store.load(ORIGIN) as RemoteSupportSessionLoad.Pending
                    assertEquals(SYNTHETIC_PAT, persisted.record.accessToken.reveal())
                    assertEquals(REQUEST_ID, persisted.record.requestId)
                } finally {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                    firstCoordinator.close()
                }

                // Reopen a new coordinator and verify it resumes Pending without losing the PAT.
                val reopenedCoordinator = SupportPairingCoordinator(
                    pairingAccessClient = PairingAccessClient(transport),
                    sessionStore = store,
                    roleGuard = guard,
                    configuredOrigin = { ORIGIN },
                    parentScope = scope,
                )
                try {
                    reopenedCoordinator.initialize()
                    assertEquals(SupportPairingPhase.PENDING, reopenedCoordinator.state.value.phase)
                    assertEquals(REQUEST_ID, reopenedCoordinator.state.value.pending?.requestId)
                    assertEquals(1, transport.postCount)
                    assertEquals(1, transport.getCount)
                } finally {
                    reopenedCoordinator.close()
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun storageFailureOnCommitPendingAfter201KeepsAttemptUncertain() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        val transport = ScriptedPairingTransport { _, _, _ ->
            file.nextWriteFailure = IOException("disk full after 201")
            PairingAccessTransportResult.Response(201, redemptionJson())
        }
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        try {
            coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")

            assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertEquals(
                RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                coordinator.state.value.storageProblem,
            )
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
            assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun reopenAttemptRequiresExplicitReplacementAndNeverReplacesPendingOrApproved() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        val originalAttempt = (store.beginAttempt(ORIGIN) as RemoteSupportSessionWriteResult.AttemptStarted).record
        val transport = ScriptedPairingTransport { operation, _, _ ->
            when (operation) {
                PairingAccessOperation.RedeemInvitation ->
                    PairingAccessTransportResult.Response(201, redemptionJson())

                PairingAccessOperation.CurrentRequest ->
                    PairingAccessTransportResult.Response(200, currentPendingJson())

                PairingAccessOperation.ValidateSupportInstallation ->
                    PairingAccessTransportResult.Response(200, supportInstallationJson("1"))

                else -> throw AssertionError("Unexpected operation $operation")
            }
        }
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        try {
            coordinator.initialize()
            assertEquals(0, transport.totalCalls)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
            assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)

            // Without explicit replacement flag, existing Attempt is NOT replaced.
            coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = false)
            assertEquals(0, transport.postCount)
            assertEquals(originalAttempt, (store.load(ORIGIN) as RemoteSupportSessionLoad.Attempt).record)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)

            // Invalid secret while replacing keeps uncertain state and does not touch store.
            coordinator.submitInvitation("bad", replaceUncertainAttempt = true)
            assertEquals(0, transport.postCount)
            assertEquals(originalAttempt, (store.load(ORIGIN) as RemoteSupportSessionLoad.Attempt).record)
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertEquals(PairingAccessFailureReason.INVALID_PAYLOAD, coordinator.state.value.safeFailure)
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)

            // Explicit replacement replaces ONLY the matching Attempt and flags prior host possibility.
            coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = true)
            assertEquals(1, transport.postCount)
            assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)
            val pendingRecord = (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record
            assertTrue(pendingRecord.attemptId != originalAttempt.attemptId)

            // Pending record can NEVER be replaced by replaceUncertainAttempt = true.
            coordinator.submitInvitation(SECOND_INVITATION_SECRET, replaceUncertainAttempt = true)
            assertEquals(1, transport.postCount)
            assertSamePendingRecord(
                pendingRecord,
                (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
            )

            // Approved record can NEVER be replaced by replaceUncertainAttempt = true either.
            val approvedRecord = (
                store.commitApproved(pendingRecord, supportInstallation("1"))
                    as RemoteSupportSessionWriteResult.Committed
                ).record as RemoteSupportSessionRecord.Approved
            coordinator.initialize()
            assertEquals(SupportPairingPhase.CONFIRMED, coordinator.state.value.phase)
            coordinator.submitInvitation(SECOND_INVITATION_SECRET, replaceUncertainAttempt = true)
            assertEquals(1, transport.postCount)
            assertSameApprovedRecord(
                approvedRecord,
                (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun reopenPendingPollsPastLocalExpiryAndValidatesInstallationBeforeConfirming() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        val attempt = (store.beginAttempt(ORIGIN) as RemoteSupportSessionWriteResult.AttemptStarted).record
        store.commitPending(
            origin = ORIGIN,
            attemptId = attempt.attemptId,
            candidate = PairingCandidateInstallation(
                origin = ORIGIN,
                requestId = REQUEST_ID,
                installationId = INSTALLATION_ID,
                requestExpiresAtMillis = 1L, // Far in the past according to device clock.
                accessToken = SecretValue.of(SYNTHETIC_PAT),
            ),
        )
        val writesBeforePoll = file.writeCount
        var returnApproved = false
        val installationStarted = CompletableDeferred<Unit>()
        val releaseInstallation = CompletableDeferred<Unit>()
        val transport = ScriptedPairingTransport { operation, _, _ ->
            when (operation) {
                PairingAccessOperation.CurrentRequest -> {
                    if (!returnApproved) {
                        PairingAccessTransportResult.Response(200, currentPendingJson(expiresAt = 1L))
                    } else {
                        PairingAccessTransportResult.Response(200, currentApprovedJson(expiresAt = 1L))
                    }
                }

                PairingAccessOperation.ValidateSupportInstallation -> {
                    installationStarted.complete(Unit)
                    releaseInstallation.await()
                    PairingAccessTransportResult.Response(200, supportInstallationJson("1"))
                }

                else -> throw AssertionError("Unexpected operation $operation")
            }
        }
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = SupportPairingCoordinator(
            pairingAccessClient = PairingAccessClient(transport),
            sessionStore = store,
            roleGuard = guard,
            configuredOrigin = { ORIGIN },
            parentScope = scope,
        )

        var refreshJob: kotlinx.coroutines.Job? = null
        try {
            coordinator.initialize()
            assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
            assertEquals(1, transport.currentRequestCount)
            assertEquals(0, transport.validateInstallationCount)
            assertEquals(0, transport.postCount)
            assertEquals(0, transport.deleteCount)
            assertEquals(writesBeforePoll, file.writeCount)
            assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)

            returnApproved = true
            refreshJob = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.refreshStatus()
            }
            withTimeout(BARRIER_TIMEOUT_MILLIS) { installationStarted.await() }
            // Must NOT announce CONFIRMED before /api/v1/installation validates!
            assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)

            releaseInstallation.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.join() }

            assertEquals(SupportPairingPhase.CONFIRMED, coordinator.state.value.phase)
            assertEquals(
                ConfirmedSupportAccess(
                    requestId = REQUEST_ID,
                    installationId = INSTALLATION_ID,
                    monitorId = MONITOR_ID,
                    membershipId = MEMBERSHIP_ID,
                    observedGeneration = generation("1"),
                ),
                coordinator.state.value.confirmed,
            )
            assertEquals(1, transport.validateInstallationCount)
            assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Approved)
        } finally {
            releaseInstallation.complete(Unit)
            refreshJob?.let { job -> withTimeout(BARRIER_TIMEOUT_MILLIS) { job.cancelAndJoin() } }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun reopenApprovedValidatesInstallationDirectlyUpdatesHigherGenerationAndRejectsRegressedGeneration() =
        runBlocking {
            val file = MemoryAtomicRemoteHostFile()
            val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
            seedApproved(store, generation = "1")

            var serverGeneration = "2"
            val transport = ScriptedPairingTransport { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.ValidateSupportInstallation ->
                        PairingAccessTransportResult.Response(
                            200,
                            supportInstallationJson(serverGeneration),
                        )

                    else -> throw AssertionError(
                        "Unexpected operation $operation; Approved reopen must call /installation directly",
                    )
                }
            }
            val guard = RemoteRoleGuard(
                hostPresence = { RemoteSessionPresence.Absent },
                supportPresence = { store.presence() },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = SupportPairingCoordinator(
                pairingAccessClient = PairingAccessClient(transport),
                sessionStore = store,
                roleGuard = guard,
                configuredOrigin = { ORIGIN },
                parentScope = scope,
            )

            try {
                coordinator.initialize()
                assertEquals(0, transport.currentRequestCount)
                assertEquals(1, transport.validateInstallationCount)
                assertEquals(SupportPairingPhase.CONFIRMED, coordinator.state.value.phase)
                assertEquals(generation("2"), coordinator.state.value.confirmed?.observedGeneration)
                assertEquals(
                    generation("2"),
                    (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record.observedGeneration,
                )

                // Numeric generation 10 > 2 (not lexicographic).
                serverGeneration = "10"
                coordinator.refreshStatus()
                assertEquals(SupportPairingPhase.CONFIRMED, coordinator.state.value.phase)
                assertEquals(generation("10"), coordinator.state.value.confirmed?.observedGeneration)
                assertEquals(
                    generation("10"),
                    (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record.observedGeneration,
                )

                // Regressed generation 9 < 10 must be rejected without announcing CONFIRMED or deleting credential.
                serverGeneration = "9"
                coordinator.refreshStatus()
                assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(
                    RemoteSupportSessionProblem.INVALID_SUPPORT_CONTEXT,
                    coordinator.state.value.storageProblem,
                )
                val preserved = (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record
                assertEquals(generation("10"), preserved.observedGeneration)
                assertEquals(SYNTHETIC_PAT, preserved.accessToken.reveal())
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun pollAndValidationFailureSemanticsHandle401Cas403404429Offline5xxAndUnavailableStorage() =
        runBlocking {
            // 1. 401 clears matching record -> CREDENTIAL_LOST.
            run {
                val file = MemoryAtomicRemoteHostFile()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                seedPending(store)
                val transport = ScriptedPairingTransport { _, _, _ ->
                    PairingAccessTransportResult.Response(
                        401,
                        errorJson("access_unauthenticated", "Unauthenticated."),
                    )
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                try {
                    coordinator.initialize()
                    assertEquals(SupportPairingPhase.CREDENTIAL_LOST, coordinator.state.value.phase)
                    assertEquals(PairingAccessFailureReason.NOT_AUTHENTICATED, coordinator.state.value.safeFailure)
                    assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 2. Stale 401 from an older record NEVER clears a newer saved record (CAS protection).
            run {
                val file = MemoryAtomicRemoteHostFile()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                val oldRecord = seedPending(store, token = "1|old-synthetic-pat")
                val firstPollStarted = CompletableDeferred<Unit>()
                val releaseFirstPoll = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { _, _, _ ->
                    firstPollStarted.complete(Unit)
                    releaseFirstPoll.await()
                    PairingAccessTransportResult.Response(
                        401,
                        errorJson("access_unauthenticated", "Unauthenticated."),
                    )
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.initialize()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { firstPollStarted.await() }
                    // Replace stored record with a newer session before old 401 returns.
                    assertEquals(RemoteSupportSessionWriteResult.Cleared, store.clearExpected(oldRecord))
                    val newerRecord = seedPending(store, token = "2|new-synthetic-pat")

                    releaseFirstPoll.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { job.join() }

                    assertSamePendingRecord(
                        newerRecord,
                        (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
                    )
                    assertTrue(coordinator.state.value.phase != SupportPairingPhase.CREDENTIAL_LOST)
                } finally {
                    releaseFirstPoll.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { job.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 3. 403 on /installation does NOT delete credential and does NOT become CREDENTIAL_LOST.
            run {
                val file = MemoryAtomicRemoteHostFile()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                val approved = seedApproved(store, generation = "1")
                val transport = ScriptedPairingTransport { _, _, _ ->
                    PairingAccessTransportResult.Response(
                        403,
                        errorJson("access_forbidden", "Forbidden."),
                    )
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                try {
                    coordinator.initialize()
                    assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                    assertEquals(PairingAccessFailureReason.ACCESS_FORBIDDEN, coordinator.state.value.safeFailure)
                    assertSameApprovedRecord(
                        approved,
                        (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record,
                    )
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 4. 404 on poll does NOT become CONFIRMED and does NOT delete credential.
            run {
                val file = MemoryAtomicRemoteHostFile()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                val pending = seedPending(store)
                val transport = ScriptedPairingTransport { _, _, _ ->
                    PairingAccessTransportResult.Response(
                        404,
                        errorJson("resource_not_found", "Not found."),
                    )
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                try {
                    coordinator.initialize()
                    assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                    assertEquals(PairingAccessFailureReason.RESOURCE_NOT_FOUND, coordinator.state.value.safeFailure)
                    assertSamePendingRecord(
                        pending,
                        (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
                    )
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 5. 429, offline, and 5xx preserve credential -> OFFLINE_STALE without auto-retry.
            listOf(
                Triple(
                    PairingAccessTransportResult.Response(
                        429,
                        errorJson("rate_limited", "Rate limited."),
                        retryAfterSeconds = 45L,
                    ),
                    PairingAccessFailureReason.RATE_LIMITED,
                    45L as Long?,
                ),
                Triple(
                    PairingAccessTransportResult.NetworkFailure,
                    PairingAccessFailureReason.NETWORK_FAILURE,
                    null,
                ),
                Triple(
                    PairingAccessTransportResult.Response(
                        500,
                        errorJson("internal_error", "Internal error."),
                    ),
                    PairingAccessFailureReason.SERVICE_UNAVAILABLE,
                    null,
                ),
            ).forEach { (result, expectedReason, expectedRetryAfter) ->
                val file = MemoryAtomicRemoteHostFile()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                val pending = seedPending(store)
                val transport = ScriptedPairingTransport { _, _, _ -> result }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                try {
                    coordinator.initialize()
                    assertEquals(1, transport.getCount)
                    assertEquals(SupportPairingPhase.OFFLINE_STALE, coordinator.state.value.phase)
                    assertEquals(expectedReason, coordinator.state.value.safeFailure)
                    assertEquals(expectedRetryAfter, coordinator.state.value.retryAfterSeconds)
                    assertEquals(REQUEST_ID, coordinator.state.value.pending?.requestId)
                    assertSamePendingRecord(
                        pending,
                        (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
                    )
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 6. Unavailable storage (corrupt bytes) -> RECOVERABLE_ERROR with zero network calls and no delete.
            run {
                val file = MemoryAtomicRemoteHostFile().apply {
                    record = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                }
                val originalBytes = file.snapshot()
                val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
                val transport = ScriptedPairingTransport()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                try {
                    coordinator.initialize()
                    assertEquals(0, transport.totalCalls)
                    assertEquals(0, file.deleteCount)
                    assertEquals(originalBytes.toList(), file.snapshot().toList())
                    assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                    assertEquals(RemoteSupportSessionProblem.CORRUPT_RECORD, coordinator.state.value.storageProblem)
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun roleGuardBlocksSupportSubmissionForRealHostStatesAndUnavailablePresenceWithoutDeleting() =
        runBlocking {
            listOf("active", "pending", "corrupt", "key-unavailable", "signed-out", "io-error").forEach { mode ->
                val hostFile = MemoryAtomicRemoteHostFile()
                val hostKeys = JceTestKeyProvider()
                val hostStore = PersistentRemoteHostSessionStore(hostFile, hostKeys, Dispatchers.Unconfined)
                val supportFile = MemoryAtomicRemoteHostFile()
                val supportStore = PersistentRemoteSupportSessionStore(
                    supportFile,
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                val transport = ScriptedPairingTransport()

                when (mode) {
                    "active", "signed-out" -> seedActiveHost(hostStore)
                    "pending" -> assertTrue(
                        hostStore.beginAttempt(HOST_CONTEXT, generation("0"))
                            is RemoteHostSessionWriteResult.PendingAttemptStarted,
                    )

                    "corrupt" -> hostFile.record = byteArrayOf(0x0A, 0x0B, 0x0C)
                    "key-unavailable" -> {
                        seedActiveHost(hostStore)
                        hostKeys.keyAvailable = false
                    }

                    "io-error" -> hostFile.nextReadFailure = IOException("storage unreadable")
                }

                val originalHostBytes = hostFile.record?.copyOf()
                val hostWritesBefore = hostFile.writeCount
                val guard = RemoteRoleGuard(
                    hostPresence = { hostStore.presence() },
                    supportPresence = { supportStore.presence() },
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = SupportPairingCoordinator(
                    pairingAccessClient = PairingAccessClient(transport),
                    sessionStore = supportStore,
                    roleGuard = guard,
                    configuredOrigin = { ORIGIN },
                    parentScope = scope,
                )
                try {
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")

                    assertEquals(SupportPairingPhase.ROLE_CONFLICT, coordinator.state.value.phase)
                    assertEquals(
                        if (mode == "io-error") {
                            RemoteRoleGuardBlock.PRESENCE_UNAVAILABLE
                        } else {
                            RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT
                        },
                        coordinator.state.value.roleConflict,
                    )
                    assertEquals(0, transport.totalCalls)
                    assertEquals(0, supportFile.writeCount)
                    assertEquals(0, supportFile.deleteCount)
                    assertEquals(hostWritesBefore, hostFile.writeCount)
                    assertEquals(0, hostFile.deleteCount)
                    assertEquals(originalHostBytes?.toList(), hostFile.record?.toList())
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun closeAndNewerOperationPreventStaleCallbacksFromPublishingOrClearing() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        val pending = seedPending(store)
        val pollCount = AtomicInteger()
        val firstPollStarted = CompletableDeferred<Unit>()
        val releaseFirstPoll = CompletableDeferred<Unit>()
        val transport = ScriptedPairingTransport { _, _, _ ->
            if (pollCount.incrementAndGet() == 1) {
                firstPollStarted.complete(Unit)
                releaseFirstPoll.await()
                PairingAccessTransportResult.Response(
                    401,
                    errorJson("access_unauthenticated", "Stale 401."),
                )
            } else {
                PairingAccessTransportResult.Response(200, currentPendingJson())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = newCoordinator(transport, store, scope)

        val staleJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.initialize()
        }
        try {
            withTimeout(BARRIER_TIMEOUT_MILLIS) { firstPollStarted.await() }
            // Newer initialize supersedes the first epoch.
            coordinator.initialize()
            assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)

            // Now release the stale 401 from epoch 1; it must NOT clear storage or overwrite PENDING.
            releaseFirstPoll.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { staleJob.join() }

            assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
            assertSamePendingRecord(
                pending,
                (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
            )

            // Closing prevents further calls.
            coordinator.close()
            assertEquals(SupportPairingPhase.EMPTY, coordinator.state.value.phase)
            val afterClose = runCatching { coordinator.initialize() }.exceptionOrNull()
            assertTrue(afterClose is IllegalStateException)
        } finally {
            releaseFirstPoll.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { staleJob.cancelAndJoin() }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun concurrentRefreshStatusIsDroppedSingleFlightAndCloseDuringPostStillCommitsObserved201() =
        runBlocking {
            // 1. Overlapping refreshStatus calls perform only one GET.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                seedPending(store)
                val firstGetStarted = CompletableDeferred<Unit>()
                val releaseFirstGet = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { _, _, _ ->
                    firstGetStarted.complete(Unit)
                    releaseFirstGet.await()
                    PairingAccessTransportResult.Response(200, currentPendingJson())
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val firstRefresh = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.refreshStatus()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { firstGetStarted.await() }
                    coordinator.refreshStatus()
                    assertEquals(1, transport.getCount)
                    releaseFirstGet.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { firstRefresh.join() }
                    assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                    assertEquals(1, transport.getCount)
                } finally {
                    releaseFirstGet.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { firstRefresh.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 2. Closing coordinator while POST is in flight still durably commits observed 201 PAT
            // without publishing back to the closed coordinator.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                val postStarted = CompletableDeferred<Unit>()
                val releasePost = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { _, _, _ ->
                    postStarted.complete(Unit)
                    releasePost.await()
                    PairingAccessTransportResult.Response(201, redemptionJson())
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val submitJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { postStarted.await() }
                    coordinator.close()
                    assertEquals(SupportPairingPhase.EMPTY, coordinator.state.value.phase)

                    releasePost.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.join() }

                    assertEquals(SupportPairingPhase.EMPTY, coordinator.state.value.phase)
                    val persisted = store.load(ORIGIN) as RemoteSupportSessionLoad.Pending
                    assertEquals(SYNTHETIC_PAT, persisted.record.accessToken.reveal())
                } finally {
                    releasePost.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun unexpectedRuntimeExceptionsPublishSafeStateWithoutLeakingMessageAndRethrow() = runBlocking {
        val secretBugMessage = "private-secret-bug-detail-never-expose"

        // 1. Unexpected exception from transport during POST.
        run {
            val expectedBug = IllegalStateException(secretBugMessage)
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val transport = ScriptedPairingTransport { _, _, _ -> throw expectedBug }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            try {
                val thrown = runCatching {
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
                }.exceptionOrNull()
                assertSame(expectedBug, thrown)
                assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.toString().contains(secretBugMessage))
                assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Attempt)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

        // 2. Unexpected exception from transport during GET poll.
        run {
            val expectedBug = IllegalStateException(secretBugMessage)
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            seedPending(store)
            val transport = ScriptedPairingTransport { _, _, _ -> throw expectedBug }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            try {
                val thrown = runCatching { coordinator.initialize() }.exceptionOrNull()
                assertSame(expectedBug, thrown)
                assertEquals(SupportPairingPhase.OFFLINE_STALE, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.toString().contains(secretBugMessage))
                assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

        // 3. Unexpected exception from store during load.
        run {
            val expectedBug = IllegalStateException(secretBugMessage)
            val delegate = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val store = RecordingSupportSessionStore(
                delegate = delegate,
                onLoad = { throw expectedBug },
            )
            val transport = ScriptedPairingTransport()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            try {
                val thrown = runCatching { coordinator.initialize() }.exceptionOrNull()
                assertSame(expectedBug, thrown)
                assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.toString().contains(secretBugMessage))
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun initializeAndRefreshStatusDuringInFlightSubmitInvitationAreDroppedWithoutSupersedingPending() =
        runBlocking {
            // 1. Suspended BEFORE POST (inside beginAttempt) -> initialize() and refreshStatus() must be dropped.
            run {
                val beginEntered = CompletableDeferred<Unit>()
                val releaseBegin = CompletableDeferred<Unit>()
                val delegateStore = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                val store = RecordingSupportSessionStore(
                    delegate = delegateStore,
                    afterBeginAttempt = {
                        beginEntered.complete(Unit)
                        releaseBegin.await()
                    },
                )
                val transport = ScriptedPairingTransport { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.RedeemInvitation ->
                            PairingAccessTransportResult.Response(201, redemptionJson())

                        else -> throw AssertionError("Unexpected operation $operation")
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val submitJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { beginEntered.await() }
                    assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)

                    coordinator.initialize()
                    coordinator.refreshStatus()
                    assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)

                    releaseBegin.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.join() }

                    assertEquals(1, transport.postCount)
                    assertEquals(0, transport.getCount)
                    assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                    assertTrue(delegateStore.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
                } finally {
                    releaseBegin.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 2. Suspended DURING POST -> initialize() and refreshStatus() must be dropped.
            run {
                val postStarted = CompletableDeferred<Unit>()
                val releasePost = CompletableDeferred<Unit>()
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                val transport = ScriptedPairingTransport { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.RedeemInvitation -> {
                            postStarted.complete(Unit)
                            releasePost.await()
                            PairingAccessTransportResult.Response(201, redemptionJson())
                        }

                        else -> throw AssertionError("Unexpected operation $operation")
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val submitJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { postStarted.await() }
                    assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)

                    coordinator.initialize()
                    coordinator.refreshStatus()
                    assertEquals(SupportPairingPhase.REDEEMING, coordinator.state.value.phase)

                    releasePost.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.join() }

                    assertEquals(1, transport.postCount)
                    assertEquals(0, transport.getCount)
                    assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                    assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
                } finally {
                    releasePost.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun cancellationRightAfterKnownNotWritten410ClearsFreshAttemptAndDoesNotPublishUncertain() =
        runBlocking {
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val submitJobControl = Job()
            var postCalls = 0
            val transport = ScriptedPairingTransport { operation, _, _ ->
                assertEquals(PairingAccessOperation.RedeemInvitation, operation)
                postCalls += 1
                if (postCalls == 1) {
                    submitJobControl.cancel(CancellationException("cancelled right as 410 arrived"))
                    PairingAccessTransportResult.Response(
                        410,
                        errorJson("invitation_unavailable", "Invitation is no longer available."),
                    )
                } else {
                    PairingAccessTransportResult.Response(201, redemptionJson())
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            val submitJob = async(submitJobControl, start = CoroutineStart.UNDISPATCHED) {
                coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
            }
            try {
                val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    runCatching { submitJob.await() }.exceptionOrNull()
                }
                assertTrue(failure is CancellationException)
                assertEquals(RemoteSupportSessionLoad.Empty, store.load(ORIGIN))
                assertTrue(coordinator.state.value.phase != SupportPairingPhase.REDEMPTION_UNCERTAIN)
                assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(
                    PairingAccessFailureReason.INVITATION_UNAVAILABLE,
                    coordinator.state.value.safeFailure,
                )
                assertFalse(coordinator.state.value.canReplaceUncertainAttempt)
                assertFalse(coordinator.state.value.previousAttemptMayRemainActiveOnHost)

                // A fresh submitInvitation (without replaceUncertainAttempt) must now succeed cleanly.
                coordinator.submitInvitation(SECOND_INVITATION_SECRET, "Tablet")
                assertEquals(2, transport.postCount)
                assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                assertFalse(coordinator.state.value.previousAttemptMayRemainActiveOnHost)
                assertTrue(store.load(ORIGIN) is RemoteSupportSessionLoad.Pending)
            } finally {
                withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun submitInvitationDoesNotFlashRedeemingOrClobberActiveSessionsOrUncertainAttemptWithoutFlag() =
        runBlocking {
            // 1. Active PENDING, CONFIRMED, and OFFLINE_STALE are never clobbered or flashed to REDEEMING.
            listOf("pending", "confirmed", "offline_stale").forEach { mode ->
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                when (mode) {
                    "pending", "offline_stale" -> seedPending(store)
                    "confirmed" -> seedApproved(store, generation = "1")
                }
                val transport = ScriptedPairingTransport { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.CurrentRequest ->
                            if (mode == "offline_stale") {
                                PairingAccessTransportResult.NetworkFailure
                            } else {
                                PairingAccessTransportResult.Response(200, currentPendingJson())
                            }

                        PairingAccessOperation.ValidateSupportInstallation ->
                            PairingAccessTransportResult.Response(200, supportInstallationJson("1"))

                        else -> throw AssertionError("Unexpected operation $operation")
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val observedPhases = mutableListOf<SupportPairingPhase>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.state.collect { observedPhases += it.phase }
                }
                try {
                    coordinator.initialize()
                    val before = coordinator.state.value
                    observedPhases.clear()

                    coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = false)
                    coordinator.submitInvitation("bad-secret", replaceUncertainAttempt = false)
                    coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = true)

                    assertEquals(before, coordinator.state.value)
                    assertFalse(
                        "Expected no REDEEMING flash in mode=$mode, observed=$observedPhases",
                        observedPhases.contains(SupportPairingPhase.REDEEMING),
                    )
                    assertEquals(0, transport.postCount)
                } finally {
                    collector.cancelAndJoin()
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 2. While VALIDATING is in flight, invalid or valid submitInvitation must not bump epoch or clobber VALIDATING.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                seedPending(store)
                val getStarted = CompletableDeferred<Unit>()
                val releaseGet = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { operation, _, _ ->
                    assertEquals(PairingAccessOperation.CurrentRequest, operation)
                    getStarted.complete(Unit)
                    releaseGet.await()
                    PairingAccessTransportResult.Response(200, currentPendingJson())
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val observedPhases = mutableListOf<SupportPairingPhase>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.state.collect { observedPhases += it.phase }
                }
                val initJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.initialize()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { getStarted.await() }
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)
                    observedPhases.clear()

                    coordinator.submitInvitation("bad-secret")
                    coordinator.submitInvitation(VALID_INVITATION_SECRET)
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)
                    assertFalse(observedPhases.contains(SupportPairingPhase.REDEEMING))

                    releaseGet.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.join() }

                    assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                    assertEquals(0, transport.postCount)
                } finally {
                    releaseGet.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.cancelAndJoin() }
                    collector.cancelAndJoin()
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 3. In REDEMPTION_UNCERTAIN, submitInvitation(..., replaceUncertainAttempt = false) never flashes REDEEMING.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                store.beginAttempt(ORIGIN)
                val transport = ScriptedPairingTransport()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val observedPhases = mutableListOf<SupportPairingPhase>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.state.collect { observedPhases += it.phase }
                }
                try {
                    coordinator.initialize()
                    assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
                    observedPhases.clear()

                    coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = false)

                    assertFalse(
                        "Expected no REDEEMING flash when replaceUncertainAttempt=false, observed=$observedPhases",
                        observedPhases.contains(SupportPairingPhase.REDEEMING),
                    )
                    assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
                    assertEquals(
                        RemoteSupportSessionProblem.ATTEMPT_ALREADY_EXISTS,
                        coordinator.state.value.storageProblem,
                    )
                    assertEquals(0, transport.postCount)
                } finally {
                    collector.cancelAndJoin()
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun replacementAttemptReturning410PreservesAttemptMarkerAndAllowsThirdExplicitReplacement() =
        runBlocking {
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            val attempt1 =
                (store.beginAttempt(ORIGIN) as RemoteSupportSessionWriteResult.AttemptStarted).record
            var postCalls = 0
            val transport = ScriptedPairingTransport { operation, _, _ ->
                assertEquals(PairingAccessOperation.RedeemInvitation, operation)
                postCalls += 1
                if (postCalls == 1) {
                    PairingAccessTransportResult.Response(
                        410,
                        errorJson("invitation_unavailable", "Invitation is no longer available."),
                    )
                } else {
                    PairingAccessTransportResult.Response(201, redemptionJson())
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            try {
                coordinator.initialize()
                assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)

                // Replacement attempt 2 returns 410 KNOWN_NOT_WRITTEN.
                coordinator.submitInvitation(SECOND_INVITATION_SECRET, replaceUncertainAttempt = true)
                assertEquals(1, transport.postCount)
                val attempt2 = (store.load(ORIGIN) as RemoteSupportSessionLoad.Attempt).record
                assertTrue(attempt2.attemptId != attempt1.attemptId)
                assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(
                    PairingAccessFailureReason.INVITATION_UNAVAILABLE,
                    coordinator.state.value.safeFailure,
                )
                assertTrue(coordinator.state.value.canReplaceUncertainAttempt)
                assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)

                // 3rd explicit replacement replaces Attempt2 and succeeds to PENDING.
                coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = true)
                assertEquals(2, transport.postCount)
                assertEquals(SupportPairingPhase.PENDING, coordinator.state.value.phase)
                assertTrue(coordinator.state.value.previousAttemptMayRemainActiveOnHost)
                val persisted = (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record
                assertTrue(persisted.attemptId != attempt2.attemptId)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun cancellationDuringCommitApprovedCompletesDurablyBeforePropagatingAndReopenLoadsApproved() =
        runBlocking {
            val store = PersistentRemoteSupportSessionStore(
                MemoryAtomicRemoteHostFile(),
                JceTestKeyProvider(),
                Dispatchers.Unconfined,
            )
            seedPending(store)
            val initJobControl = Job()
            val transport = ScriptedPairingTransport { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.CurrentRequest ->
                        PairingAccessTransportResult.Response(200, currentApprovedJson())

                    PairingAccessOperation.ValidateSupportInstallation -> {
                        initJobControl.cancel(CancellationException("cancelled right before commitApproved"))
                        PairingAccessTransportResult.Response(200, supportInstallationJson("2"))
                    }

                    else -> throw AssertionError("Unexpected operation $operation")
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val firstCoordinator = newCoordinator(transport, store, scope)
                val initJob = async(initJobControl, start = CoroutineStart.UNDISPATCHED) {
                    firstCoordinator.initialize()
                }
                try {
                    val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        runCatching { initJob.await() }.exceptionOrNull()
                    }
                    assertTrue(failure is CancellationException)
                    val persisted = (store.load(ORIGIN) as RemoteSupportSessionLoad.Approved).record
                    assertEquals(MONITOR_ID, persisted.monitorId)
                    assertEquals(MEMBERSHIP_ID, persisted.membershipId)
                    assertEquals(generation("2"), persisted.observedGeneration)
                } finally {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.cancelAndJoin() }
                    firstCoordinator.close()
                }

                // Reopen a new coordinator and verify it loads Approved directly (0 calls to currentRequest).
                val reopenTransport = ScriptedPairingTransport { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.ValidateSupportInstallation ->
                            PairingAccessTransportResult.Response(200, supportInstallationJson("2"))

                        else -> throw AssertionError("Unexpected operation $operation on Approved reopen")
                    }
                }
                val reopenedCoordinator = newCoordinator(reopenTransport, store, scope)
                try {
                    reopenedCoordinator.initialize()
                    assertEquals(0, reopenTransport.currentRequestCount)
                    assertEquals(1, reopenTransport.validateInstallationCount)
                    assertEquals(SupportPairingPhase.CONFIRMED, reopenedCoordinator.state.value.phase)
                    assertEquals(generation("2"), reopenedCoordinator.state.value.confirmed?.observedGeneration)
                } finally {
                    reopenedCoordinator.close()
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun midFlightOriginChangeDuringPollPreventsStalePublicationAndPreventsStale401Clear() =
        runBlocking {
            listOf(
                PairingAccessTransportResult.Response(200, currentPendingJson()),
                PairingAccessTransportResult.Response(
                    401,
                    errorJson("access_unauthenticated", "Unauthenticated."),
                ),
            ).forEach { pollResponse ->
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                val pending = seedPending(store)
                var currentOrigin: String? = ORIGIN
                val pollStarted = CompletableDeferred<Unit>()
                val releasePoll = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport(origin = ORIGIN) { operation, _, _ ->
                    assertEquals(PairingAccessOperation.CurrentRequest, operation)
                    pollStarted.complete(Unit)
                    releasePoll.await()
                    pollResponse
                }
                val guard = RemoteRoleGuard(
                    hostPresence = { RemoteSessionPresence.Absent },
                    supportPresence = { store.presence() },
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = SupportPairingCoordinator(
                    pairingAccessClient = PairingAccessClient(transport),
                    sessionStore = store,
                    roleGuard = guard,
                    configuredOrigin = { currentOrigin },
                    parentScope = scope,
                )
                val initJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.initialize()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { pollStarted.await() }
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)

                    // Origin changes mid-flight before the poll returns.
                    currentOrigin = "https://other.example.test"
                    releasePoll.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.join() }

                    // Stale response must neither publish PENDING/CREDENTIAL_LOST nor clear the stored record.
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)
                    assertSamePendingRecord(
                        pending,
                        (store.load(ORIGIN) as RemoteSupportSessionLoad.Pending).record,
                    )
                } finally {
                    releasePoll.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun unavailableLoadClearsStaleUncertainAttemptId() = runBlocking {
        val file = MemoryAtomicRemoteHostFile()
        val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
        store.beginAttempt(ORIGIN)
        val transport = ScriptedPairingTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = newCoordinator(transport, store, scope)
        try {
            coordinator.initialize()
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.canReplaceUncertainAttempt)

            // Storage becomes corrupted; initialize() must clear stale uncertainAttemptId.
            file.record = byteArrayOf(0x01, 0x02, 0x03)
            coordinator.initialize()
            assertEquals(SupportPairingPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(RemoteSupportSessionProblem.CORRUPT_RECORD, coordinator.state.value.storageProblem)
            assertFalse(coordinator.state.value.canReplaceUncertainAttempt)

            // Attempting explicit replacement must fail closed without calling store.beginAttempt or POST.
            coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = true)
            assertEquals(0, transport.postCount)
            assertEquals(
                RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                coordinator.state.value.storageProblem,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationRightAfterCommitPendingNoMatchingRecordPublishesCannotReplaceAndStorageProblem() =
        runBlocking {
            val file = MemoryAtomicRemoteHostFile()
            val store = PersistentRemoteSupportSessionStore(file, JceTestKeyProvider(), Dispatchers.Unconfined)
            val submitJobControl = Job()
            val transport = ScriptedPairingTransport { operation, _, _ ->
                assertEquals(PairingAccessOperation.RedeemInvitation, operation)
                // Remove stored Attempt before commitPending runs so CAS returns NO_MATCHING_EXPECTED_RECORD.
                file.record = null
                submitJobControl.cancel(CancellationException("cancelled right after 201"))
                PairingAccessTransportResult.Response(201, redemptionJson())
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = newCoordinator(transport, store, scope)
            val submitJob = async(submitJobControl, start = CoroutineStart.UNDISPATCHED) {
                coordinator.submitInvitation(VALID_INVITATION_SECRET, "Tablet")
            }
            try {
                val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    runCatching { submitJob.await() }.exceptionOrNull()
                }
                assertTrue(failure is CancellationException)
                assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)
                assertEquals(
                    RemoteSupportSessionProblem.NO_MATCHING_EXPECTED_RECORD,
                    coordinator.state.value.storageProblem,
                )
                assertFalse(coordinator.state.value.canReplaceUncertainAttempt)
            } finally {
                withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.cancelAndJoin() }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun callerCancellationMidPollSettlesValidatingToOfflineStaleAndCommitApprovedPublishesConfirmed() =
        runBlocking {
            // 1. Mid-poll cancellation during Pending GET /current on open coordinator settles to OFFLINE_STALE.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                seedPending(store)
                val pollStarted = CompletableDeferred<Unit>()
                val releasePoll = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { _, _, _ ->
                    pollStarted.complete(Unit)
                    releasePoll.await()
                    PairingAccessTransportResult.Response(200, currentPendingJson())
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val initJob = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.initialize()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { pollStarted.await() }
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)

                    initJob.cancel(CancellationException("caller cancelled poll"))
                    val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        runCatching { initJob.await() }.exceptionOrNull()
                    }
                    assertTrue(failure is CancellationException)
                    assertEquals(SupportPairingPhase.OFFLINE_STALE, coordinator.state.value.phase)
                    assertEquals(REQUEST_ID, coordinator.state.value.pending?.requestId)
                } finally {
                    releasePoll.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 2. Mid-poll cancellation during Approved GET /installation on open coordinator settles to OFFLINE_STALE.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                seedApproved(store, generation = "1")
                val validateStarted = CompletableDeferred<Unit>()
                val releaseValidate = CompletableDeferred<Unit>()
                val transport = ScriptedPairingTransport { _, _, _ ->
                    validateStarted.complete(Unit)
                    releaseValidate.await()
                    PairingAccessTransportResult.Response(200, supportInstallationJson("1"))
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val refreshJob = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.refreshStatus()
                }
                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { validateStarted.await() }
                    assertEquals(SupportPairingPhase.VALIDATING, coordinator.state.value.phase)

                    refreshJob.cancel(CancellationException("caller cancelled installation validation"))
                    val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        runCatching { refreshJob.await() }.exceptionOrNull()
                    }
                    assertTrue(failure is CancellationException)
                    assertEquals(SupportPairingPhase.OFFLINE_STALE, coordinator.state.value.phase)
                    assertEquals(MONITOR_ID, coordinator.state.value.confirmed?.monitorId)
                } finally {
                    releaseValidate.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { refreshJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }

            // 3. Cancellation right before commitApproved on open coordinator publishes CONFIRMED before propagating.
            run {
                val store = PersistentRemoteSupportSessionStore(
                    MemoryAtomicRemoteHostFile(),
                    JceTestKeyProvider(),
                    Dispatchers.Unconfined,
                )
                seedPending(store)
                val initJobControl = Job()
                val transport = ScriptedPairingTransport { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.CurrentRequest ->
                            PairingAccessTransportResult.Response(200, currentApprovedJson())

                        PairingAccessOperation.ValidateSupportInstallation -> {
                            initJobControl.cancel(CancellationException("cancelled before commitApproved"))
                            PairingAccessTransportResult.Response(200, supportInstallationJson("2"))
                        }

                        else -> throw AssertionError("Unexpected operation $operation")
                    }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = newCoordinator(transport, store, scope)
                val initJob = async(initJobControl, start = CoroutineStart.UNDISPATCHED) {
                    coordinator.initialize()
                }
                try {
                    val failure = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        runCatching { initJob.await() }.exceptionOrNull()
                    }
                    assertTrue(failure is CancellationException)
                    assertEquals(SupportPairingPhase.CONFIRMED, coordinator.state.value.phase)
                    assertEquals(generation("2"), coordinator.state.value.confirmed?.observedGeneration)
                } finally {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { initJob.cancelAndJoin() }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun closeRacingDuringReplacementBeginAttemptKeepsEmptyStateAndZeroPost() = runBlocking {
        val delegateStore = PersistentRemoteSupportSessionStore(
            MemoryAtomicRemoteHostFile(),
            JceTestKeyProvider(),
            Dispatchers.Unconfined,
        )
        delegateStore.beginAttempt(ORIGIN)
        val beginEntered = CompletableDeferred<Unit>()
        val releaseBegin = CompletableDeferred<Unit>()
        val store = RecordingSupportSessionStore(
            delegate = delegateStore,
            afterBeginAttempt = {
                beginEntered.complete(Unit)
                releaseBegin.await()
            },
        )
        val transport = ScriptedPairingTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = newCoordinator(transport, store, scope)
        var submitJob: kotlinx.coroutines.Job? = null
        try {
            coordinator.initialize()
            assertEquals(SupportPairingPhase.REDEMPTION_UNCERTAIN, coordinator.state.value.phase)

            submitJob = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.submitInvitation(VALID_INVITATION_SECRET, replaceUncertainAttempt = true)
            }
            withTimeout(BARRIER_TIMEOUT_MILLIS) { beginEntered.await() }

            coordinator.close()
            assertEquals(SupportPairingViewState(), coordinator.state.value)

            releaseBegin.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) { submitJob.join() }

            assertEquals(SupportPairingViewState(), coordinator.state.value)
            assertEquals(0, transport.postCount)
            val afterClose = runCatching {
                coordinator.submitInvitation(SECOND_INVITATION_SECRET, replaceUncertainAttempt = true)
            }.exceptionOrNull()
            assertTrue(afterClose is IllegalStateException)
        } finally {
            releaseBegin.complete(Unit)
            submitJob?.let { job -> withTimeout(BARRIER_TIMEOUT_MILLIS) { job.cancelAndJoin() } }
            coordinator.close()
            scope.cancel()
        }
    }

    private fun assertSamePendingRecord(
        expected: RemoteSupportSessionRecord.Pending,
        actual: RemoteSupportSessionRecord.Pending,
    ) {
        assertEquals(expected.origin, actual.origin)
        assertEquals(expected.attemptId, actual.attemptId)
        assertEquals(expected.requestId, actual.requestId)
        assertEquals(expected.installationId, actual.installationId)
        assertEquals(expected.requestExpiresAtMillis, actual.requestExpiresAtMillis)
        assertEquals(expected.accessToken.reveal(), actual.accessToken.reveal())
    }

    private fun assertSameApprovedRecord(
        expected: RemoteSupportSessionRecord.Approved,
        actual: RemoteSupportSessionRecord.Approved,
    ) {
        assertEquals(expected.origin, actual.origin)
        assertEquals(expected.attemptId, actual.attemptId)
        assertEquals(expected.requestId, actual.requestId)
        assertEquals(expected.installationId, actual.installationId)
        assertEquals(expected.requestExpiresAtMillis, actual.requestExpiresAtMillis)
        assertEquals(expected.accessToken.reveal(), actual.accessToken.reveal())
        assertEquals(expected.monitorId, actual.monitorId)
        assertEquals(expected.membershipId, actual.membershipId)
        assertEquals(expected.observedGeneration, actual.observedGeneration)
    }

    private fun newCoordinator(
        transport: ScriptedPairingTransport,
        store: RemoteSupportSessionStore,
        scope: CoroutineScope,
    ): SupportPairingCoordinator = SupportPairingCoordinator(
        pairingAccessClient = PairingAccessClient(transport),
        sessionStore = store,
        roleGuard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { store.presence() },
        ),
        configuredOrigin = { ORIGIN },
        parentScope = scope,
    )

    private suspend fun seedPending(
        store: PersistentRemoteSupportSessionStore,
        token: String = SYNTHETIC_PAT,
    ): RemoteSupportSessionRecord.Pending {
        val attempt = (store.beginAttempt(ORIGIN) as RemoteSupportSessionWriteResult.AttemptStarted).record
        return (
            store.commitPending(
                origin = ORIGIN,
                attemptId = attempt.attemptId,
                candidate = PairingCandidateInstallation(
                    origin = ORIGIN,
                    requestId = REQUEST_ID,
                    installationId = INSTALLATION_ID,
                    requestExpiresAtMillis = REQUEST_EXPIRES_AT,
                    accessToken = SecretValue.of(token),
                ),
            ) as RemoteSupportSessionWriteResult.Committed
            ).record as RemoteSupportSessionRecord.Pending
    }

    private suspend fun seedApproved(
        store: PersistentRemoteSupportSessionStore,
        generation: String,
    ): RemoteSupportSessionRecord.Approved {
        val pending = seedPending(store)
        return (
            store.commitApproved(pending, supportInstallation(generation))
                as RemoteSupportSessionWriteResult.Committed
            ).record as RemoteSupportSessionRecord.Approved
    }

    private suspend fun seedActiveHost(store: PersistentRemoteHostSessionStore) {
        val pending = store.beginAttempt(HOST_CONTEXT, generation("0"))
            as RemoteHostSessionWriteResult.PendingAttemptStarted
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(
                expectedContext = HOST_CONTEXT,
                attemptId = pending.attemptId,
                activation = HostActivation(
                    monitorId = MONITOR_ID,
                    installationId = HOST_INSTALLATION_ID,
                    membershipId = HOST_MEMBERSHIP_ID,
                    generation = generation("1"),
                    accessToken = SecretValue.of("synthetic-host-pat"),
                    createdAtMillis = 1_790_000_000_000L,
                    owner = OWNER,
                    origin = ORIGIN,
                ),
            ),
        )
    }

    private fun supportInstallation(generation: String): PairingSupportInstallation =
        PairingSupportInstallation(
            installationId = INSTALLATION_ID,
            monitorId = MONITOR_ID,
            membershipId = MEMBERSHIP_ID,
            role = "Support",
            status = "Active",
            monitorGeneration = generation(generation),
        )

    private fun redemptionJson(): ByteArray =
        (
            """{"data":{"requestId":"$REQUEST_ID","installationId":"$INSTALLATION_ID",""" +
                """"status":"Pending","requestExpiresAt":$REQUEST_EXPIRES_AT,""" +
                """"accessToken":"$SYNTHETIC_PAT"}}"""
            ).toByteArray(StandardCharsets.UTF_8)

    private fun currentPendingJson(expiresAt: Long = REQUEST_EXPIRES_AT): ByteArray =
        (
            """{"data":{"id":"$REQUEST_ID","status":"Pending","requestExpiresAt":$expiresAt,""" +
                """"decidedAt":null,"monitor":null}}"""
            ).toByteArray(StandardCharsets.UTF_8)

    private fun currentApprovedJson(expiresAt: Long = REQUEST_EXPIRES_AT): ByteArray =
        (
            """{"data":{"id":"$REQUEST_ID","status":"Approved","requestExpiresAt":$expiresAt,""" +
                """"decidedAt":1795132800000,"monitor":{"id":"$MONITOR_ID",""" +
                """"membershipId":"$MEMBERSHIP_ID","role":"Support"}}}"""
            ).toByteArray(StandardCharsets.UTF_8)

    private fun supportInstallationJson(generation: String): ByteArray =
        (
            """{"data":{"installationId":"$INSTALLATION_ID","monitorId":"$MONITOR_ID",""" +
                """"membershipId":"$MEMBERSHIP_ID","role":"Support","status":"Active",""" +
                """"monitorGeneration":"$generation"}}"""
            ).toByteArray(StandardCharsets.UTF_8)

    private fun errorJson(code: String, message: String): ByteArray =
        """{"code":"$code","message":"$message"}""".toByteArray(StandardCharsets.UTF_8)

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private class ScriptedPairingTransport(
        override val origin: String? = ORIGIN,
        private val events: MutableList<String>? = null,
        private val handler: suspend (
            PairingAccessOperation,
            PairingAccessCredential?,
            PairingAccessRequestBody?,
        ) -> PairingAccessTransportResult = { operation, _, _ ->
            throw AssertionError("Unexpected transport call: $operation")
        },
    ) : PairingAccessTransport {
        var totalCalls = 0
            private set
        var postCount = 0
            private set
        var getCount = 0
            private set
        var deleteCount = 0
            private set
        var currentRequestCount = 0
            private set
        var validateInstallationCount = 0
            private set

        override suspend fun execute(
            operation: PairingAccessOperation,
            credential: PairingAccessCredential?,
            body: PairingAccessRequestBody?,
        ): PairingAccessTransportResult {
            totalCalls += 1
            when (operation.method) {
                PairingAccessMethod.POST -> postCount += 1
                PairingAccessMethod.GET -> getCount += 1
                PairingAccessMethod.DELETE -> deleteCount += 1
            }
            when (operation) {
                PairingAccessOperation.CurrentRequest -> currentRequestCount += 1
                PairingAccessOperation.ValidateSupportInstallation -> validateInstallationCount += 1
                else -> Unit
            }
            events?.add("transport.${operation.method}:${operation.path}")
            return handler(operation, credential, body)
        }
    }

    private class RecordingSupportSessionStore(
        private val delegate: RemoteSupportSessionStore,
        private val events: MutableList<String>? = null,
        private val onLoad: (suspend () -> Unit)? = null,
        private val afterBeginAttempt: (suspend () -> Unit)? = null,
        private val onCommitPending: (suspend () -> Unit)? = null,
    ) : RemoteSupportSessionStore {
        var beginAttemptCalls = 0
            private set

        override suspend fun load(origin: String): RemoteSupportSessionLoad {
            onLoad?.invoke()
            return delegate.load(origin)
        }

        override suspend fun presence(): RemoteSessionPresence = delegate.presence()

        override suspend fun beginAttempt(
            origin: String,
            expectedPreviousAttempt: RemoteSupportAttemptId?,
        ): RemoteSupportSessionWriteResult {
            beginAttemptCalls += 1
            events?.add("store.beginAttempt")
            val result = delegate.beginAttempt(origin, expectedPreviousAttempt)
            afterBeginAttempt?.invoke()
            return result
        }

        override suspend fun commitPending(
            origin: String,
            attemptId: RemoteSupportAttemptId,
            candidate: PairingCandidateInstallation,
        ): RemoteSupportSessionWriteResult {
            events?.add("store.commitPending")
            onCommitPending?.invoke()
            return delegate.commitPending(origin, attemptId, candidate)
        }

        override suspend fun commitApproved(
            expectedRecord: RemoteSupportSessionRecord,
            supportInstallation: PairingSupportInstallation,
        ): RemoteSupportSessionWriteResult =
            delegate.commitApproved(expectedRecord, supportInstallation)

        override suspend fun clearExpected(
            expectedRecord: RemoteSupportSessionRecord,
        ): RemoteSupportSessionWriteResult = delegate.clearExpected(expectedRecord)
    }

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var record: ByteArray? = null
        @Volatile
        var nextReadFailure: IOException? = null
        @Volatile
        var nextWriteFailure: IOException? = null
        var writeCount = 0
            private set
        var deleteCount = 0
            private set

        override fun readRecord(maximumBytes: Int): ByteArray? {
            nextReadFailure?.let { failure ->
                nextReadFailure = null
                throw failure
            }
            val current = record ?: return null
            return current.copyOf(minOf(current.size, maximumBytes))
        }

        override fun writeAtomically(record: ByteArray) {
            nextWriteFailure?.let { failure ->
                nextWriteFailure = null
                throw failure
            }
            this.record = record.copyOf()
            writeCount += 1
        }

        override fun deleteRecord() {
            deleteCount += 1
            record = null
        }

        fun snapshot(): ByteArray = requireNotNull(record).copyOf()
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        var keyAvailable = true

        override fun existingKey(): SecretKey? = key.takeIf { keyAvailable }

        override fun keyForNewRecord(): SecretKey = key
    }

    private companion object {
        const val BARRIER_TIMEOUT_MILLIS = 5_000L
        const val ORIGIN = "https://api.example.test"
        const val VALID_INVITATION_SECRET = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val SECOND_INVITATION_SECRET = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
        const val SYNTHETIC_PAT = "1|synthetic-support-secret"
        const val REQUEST_ID = "550e8400-e29b-41d4-a716-446655440004"
        const val INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440005"
        const val MONITOR_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440006"
        const val HOST_INSTALLATION_ID = "550e8400-e29b-41d4-a716-446655440001"
        const val HOST_MEMBERSHIP_ID = "550e8400-e29b-41d4-a716-446655440002"
        const val REQUEST_EXPIRES_AT = 1_795_133_700_000L
        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val HOST_CONTEXT = RemoteHostOwnerContext(OWNER, ORIGIN)
    }
}
