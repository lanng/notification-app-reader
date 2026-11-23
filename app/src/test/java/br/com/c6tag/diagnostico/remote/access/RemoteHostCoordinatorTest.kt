package br.com.c6tag.diagnostico.remote.access

import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.diagnostic.DiagnosticSnapshotMetadata
import br.com.c6tag.diagnostico.diagnostic.DiagnosticStore
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import br.com.c6tag.diagnostico.monitor.ChangeSettings
import br.com.c6tag.diagnostico.monitor.ConfirmReference
import br.com.c6tag.diagnostico.monitor.FinancialEntry
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.MonitorAlertEffects
import br.com.c6tag.diagnostico.monitor.MonitorCoordinator
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.MonitorResult
import br.com.c6tag.diagnostico.monitor.MonitorRevision
import br.com.c6tag.diagnostico.monitor.MonitorStore
import br.com.c6tag.diagnostico.monitor.PendingMovement
import br.com.c6tag.diagnostico.monitor.RecordSubmission
import br.com.c6tag.diagnostico.monitor.ResolvePending
import br.com.c6tag.diagnostico.monitor.RevisionCause
import br.com.c6tag.diagnostico.monitor.alerts.AlertEvaluation
import br.com.c6tag.diagnostico.monitor.alerts.EmissionIntent
import br.com.c6tag.diagnostico.monitor.alerts.SubmissionOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHostCoordinatorTest {
    @Test
    fun loginAndDiscoveryNeverActivateUntilBoundConfirmationIsExplicitlyAccepted() = runBlocking {
        val events = mutableListOf<String>()
        val owner = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val identity = RecordingOwnerIdentity(events, owner)
        val transport = ScriptedOwnerAccessTransport(events)
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = RemoteHostCoordinator(
            identity = identity,
            ownerAccessClient = OwnerAccessClient(transport),
            sessionStore = store,
            parentScope = scope,
            confirmationId = { "confirmation-0001" },
        )

        try {
            assertEquals(RemoteHostPhase.LOCAL_ONLY, coordinator.state.value.phase)
            assertEquals(0, transport.activationPostCount)

            val signIn = coordinator.signIn(SecretValue.of("synthetic-google-id-token"))

            assertEquals(IdentityOutcome.SIGNED_IN_CANDIDATE, signIn.outcome)
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
            assertEquals(0, transport.activationPostCount)
            assertEquals(listOf("identity.sign-in"), events)

            coordinator.discoverOwnerMonitor()

            val ready = coordinator.state.value
            val confirmation = ready.confirmation
            assertEquals(RemoteHostPhase.CONFIRMATION_READY, ready.phase)
            assertNotNull(confirmation)
            assertEquals(owner.firebaseProjectId, confirmation?.firebaseProjectId)
            assertEquals(owner.uid, confirmation?.uid)
            assertEquals("https://api.example.test", confirmation?.origin)
            assertEquals("0", confirmation?.generation?.decimal)
            assertEquals(0, transport.activationPostCount)
            assertEquals(
                listOf(OwnerAccessPath.OWNER_MONITOR),
                transport.calls.map { call -> call.path },
            )

            coordinator.confirmHostActivation(requireNotNull(confirmation).confirmationId)

            assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
            assertNull(coordinator.state.value.confirmation)
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                listOf(
                    "identity.sign-in",
                    "GET:${OwnerAccessPath.OWNER_MONITOR}",
                    "journal.pending",
                    "POST:${OwnerAccessPath.HOST_ACTIVATIONS}",
                    "journal.commit",
                    "GET:${OwnerAccessPath.INSTALLATION}",
                ),
                events,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun responseLossLeavesDurablePendingAndRestartNeverReplaysActivation() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(
            events = events,
            signedInAccount = OwnerAccount("firebase-project", "firebase-uid", "Owner"),
        )
        val realStore = persistentStore()
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val firstTransport = ScriptedOwnerAccessTransport(
            events = events,
            activationResponses = listOf(OwnerAccessTransportResult.NetworkFailure),
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstCoordinator = coordinator(identity, firstTransport, store, firstScope)

        val pending: RemoteHostSessionLoad.PendingAttempt
        try {
            firstCoordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            firstCoordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(firstCoordinator.state.value.confirmation)
            firstCoordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, firstCoordinator.state.value.phase)
            assertEquals(1, firstTransport.activationPostCount)
            pending = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.PendingAttempt
            assertEquals(generation("0"), pending.expectedGeneration)
        } finally {
            firstCoordinator.close()
            firstScope.cancel()
        }

        val restartEvents = mutableListOf<String>()
        val restartedTransport = ScriptedOwnerAccessTransport(restartEvents)
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val restarted = coordinator(identity, restartedTransport, store, restartedScope)

        try {
            restarted.initialize()
            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, restarted.state.value.phase)
            assertNull(restarted.state.value.confirmation)
            restarted.discoverOwnerMonitor()

            assertEquals(0, restartedTransport.activationPostCount)
            assertEquals(0, restartedTransport.calls.count { call ->
                call.path == OwnerAccessPath.OWNER_MONITOR
            })
            assertEquals(
                RemoteHostSessionLoad.PendingAttempt(pending.attemptId, generation("0")),
                realStore.load(REMOTE_CONTEXT),
            )
        } finally {
            restarted.close()
            restartedScope.cancel()
        }
    }

    @Test
    fun uncertainActivationRequiresReauthenticationRediscoveryAndFreshConfirmationToRecover() =
        runBlocking {
            val events = mutableListOf<String>()
            val realStore = persistentStore()
            val initial = realStore.beginAttempt(REMOTE_CONTEXT, generation("5"))
                as RemoteHostSessionWriteResult.PendingAttemptStarted
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(
                    response(200, ownerMonitorFixture("6", "unused-host")),
                ),
                activationResponses = listOf(
                    response(201, activationFixture("recovered-host", "7", NEW_PAT)),
                ),
                installationResponses = listOf(
                    response(200, installationFixture("recovered-host", "7")),
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(
                identity,
                transport,
                RecordingRemoteHostSessionStore(events, realStore),
                scope,
            )

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.initialize()
                assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
                assertTrue(transport.calls.isEmpty())

                coordinator.discoverOwnerMonitor()
                assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
                assertTrue(transport.calls.isEmpty())

                assertEquals(
                    IdentityOutcome.REAUTHENTICATED,
                    coordinator.reauthenticate(
                        SecretValue.of("synthetic-fresh-google-token"),
                    ).outcome,
                )
                assertTrue(transport.calls.isEmpty())

                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                assertEquals("6", confirmation.generation.decimal)
                assertEquals(0, transport.activationPostCount)
                assertEquals(
                    RemoteHostSessionLoad.PendingAttempt(initial.attemptId, generation("5")),
                    realStore.load(REMOTE_CONTEXT),
                )

                coordinator.confirmHostActivation(
                    confirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )

                assertEquals(1, transport.activationPostCount)
                assertEquals(
                    """{"confirmation":true,"expectedGeneration":"6"}""",
                    transport.calls.last { call ->
                        call.path == OwnerAccessPath.HOST_ACTIVATIONS
                    }.body,
                )
                assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
                val active = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
                assertEquals("recovered-host", active.activation.installationId)
                assertEquals(NEW_PAT, active.activation.accessToken.reveal())
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun reauthenticationBeforeNormalActivationCannotAuthorizeLaterPendingRecovery() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, ownerMonitorFixture("0", "existing-host")),
                response(200, ownerMonitorFixture("1", "existing-host")),
            ),
            activationResponses = listOf(
                OwnerAccessTransportResult.NetworkFailure,
                response(201, activationFixture("recovered-host", "2", NEW_PAT)),
            ),
            installationResponses = listOf(
                response(200, installationFixture("recovered-host", "2")),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            assertEquals(
                IdentityOutcome.REAUTHENTICATED,
                coordinator.reauthenticate(SecretValue.of("synthetic-fresh-google-token")).outcome,
            )
            coordinator.discoverOwnerMonitor()
            val firstConfirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(
                firstConfirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertEquals(1, transport.activationPostCount)
            assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)

            coordinator.discoverOwnerMonitor()

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertEquals(1, transport.calls.count { call ->
                call.path == OwnerAccessPath.OWNER_MONITOR
            })
            assertEquals(1, transport.activationPostCount)

            assertEquals(
                IdentityOutcome.REAUTHENTICATED,
                coordinator.reauthenticate(SecretValue.of("synthetic-new-google-token")).outcome,
            )
            coordinator.discoverOwnerMonitor()
            val recoveryConfirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(
                recoveryConfirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )

            assertEquals(2, transport.calls.count { call ->
                call.path == OwnerAccessPath.OWNER_MONITOR
            })
            assertEquals(2, transport.activationPostCount)
            assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
            val active = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
            assertEquals("recovered-host", active.activation.installationId)
            assertEquals(NEW_PAT, active.activation.accessToken.reveal())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun reauthenticationImmediatelyHidesConfirmationUntilFreshDiscovery() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, EMPTY_OWNER_MONITOR),
                response(200, EMPTY_OWNER_MONITOR),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val reauthenticationStarted = CompletableDeferred<Unit>()
        val releaseReauthentication = CompletableDeferred<IdentityResult>()
        identity.reauthenticationRequest = {
            reauthenticationStarted.complete(Unit)
            releaseReauthentication.await()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val staleConfirmation = requireNotNull(coordinator.state.value.confirmation)
            val apiCallCountBeforeReauthentication = transport.calls.size
            val reauthentication = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.reauthenticate(SecretValue.of("synthetic-fresh-google-token"))
            }
            reauthenticationStarted.await()

            coordinator.confirmHostActivation(staleConfirmation.confirmationId)

            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostSessionLoad.Empty, realStore.load(REMOTE_CONTEXT))
            assertEquals(apiCallCountBeforeReauthentication, transport.calls.size)
            assertEquals(RemoteHostPhase.SIGNING_IN, coordinator.state.value.phase)
            assertNull(coordinator.state.value.confirmation)

            releaseReauthentication.complete(IdentityResult(IdentityOutcome.REAUTHENTICATED))
            assertEquals(IdentityOutcome.REAUTHENTICATED, reauthentication.await().outcome)

            assertEquals(
                RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                coordinator.state.value.phase,
            )
            assertNull(coordinator.state.value.confirmation)
            assertEquals(apiCallCountBeforeReauthentication, transport.calls.size)
            coordinator.confirmHostActivation(staleConfirmation.confirmationId)
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostSessionLoad.Empty, realStore.load(REMOTE_CONTEXT))

            coordinator.discoverOwnerMonitor()
            val freshConfirmation = requireNotNull(coordinator.state.value.confirmation)
            assertFalse(staleConfirmation.confirmationId == freshConfirmation.confirmationId)
            assertEquals(apiCallCountBeforeReauthentication + 1, transport.calls.size)
            assertEquals(0, transport.activationPostCount)

            coordinator.confirmHostActivation(freshConfirmation.confirmationId)

            assertEquals(1, transport.activationPostCount)
        } finally {
            releaseReauthentication.complete(IdentityResult(IdentityOutcome.REAUTHENTICATED))
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationAfterActivationDispatchPreservesUncertaintyAndDoesNotReplay() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val dispatched = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = ScriptedOwnerAccessTransport(events).apply {
            onRequest = { call ->
                if (call.path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    dispatched.complete(Unit)
                    releaseResponse.await()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            store,
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val activation = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }
            dispatched.await()
            activation.cancelAndJoin()

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertEquals(1, transport.activationPostCount)
            assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
            coordinator.confirmHostActivation(confirmation.confirmationId)
            assertEquals(1, transport.activationPostCount)
        } finally {
            releaseResponse.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun activationIsNotDispatchedWhenDurablePendingJournalCannotBeWritten() = runBlocking {
        val events = mutableListOf<String>()
        val file = MemoryAtomicRemoteHostFile().apply { failOnWrite = 1 }
        val store = persistentStore(file)
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events, store),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostSessionLoad.Empty, store.load(REMOTE_CONTEXT))
            assertEquals(1, file.writeCount)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun accountSwitchDuringActivationResponseCannotCommitOrValidateOldOwnerCredential() =
        runBlocking {
            val events = mutableListOf<String>()
            val realStore = persistentStore()
            val store = RecordingRemoteHostSessionStore(events, realStore)
            val identity = RecordingOwnerIdentity(events, OWNER)
            val dispatched = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val transport = ScriptedOwnerAccessTransport(events).apply {
                onRequest = { call ->
                    if (call.path == OwnerAccessPath.HOST_ACTIVATIONS) {
                        dispatched.complete(Unit)
                        releaseResponse.await()
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                val activation = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.confirmHostActivation(confirmation.confirmationId)
                }
                dispatched.await()

                identity.changeAccount(OwnerAccount("different-project", "different-uid", null))
                releaseResponse.complete(Unit)
                activation.await()

                assertEquals(1, transport.activationPostCount)
                assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
                assertEquals(
                    listOf(
                        OwnerAccessPath.OWNER_MONITOR,
                        OwnerAccessPath.HOST_ACTIVATIONS,
                    ),
                    transport.calls.map { call -> call.path },
                )
                assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
                assertNull(coordinator.state.value.confirmation)
                assertFalse(coordinator.state.value.toString().contains(NEW_PAT))
            } finally {
                releaseResponse.complete(Unit)
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun knownNoWriteResponseAfterAccountSwitchClearsOnlyTheOldAttemptsJournal() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore().apply {
            seedActiveSession(this, hostActivation("old-host", "1", OLD_PAT))
        }
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val dispatched = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            activationResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
        ).apply {
            onRequest = { call ->
                if (call.path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    dispatched.complete(Unit)
                    releaseResponse.await()
                }
            }
        }
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val activation = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }
            dispatched.await()

            identity.changeAccount(OwnerAccount("different-project", "different-uid", null))
            releaseResponse.complete(Unit)
            activation.await()

            assertFalse(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            assertEquals(
                0,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
            assertEquals(1, transport.activationPostCount)
        } finally {
            releaseResponse.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun staleKnownNoWriteAfterOriginChangeSignOutOrCloseNeverRestoresTheSavedPat() = runBlocking {
        listOf("origin", "sign-out", "close").forEach { invalidation ->
            val events = mutableListOf<String>()
            val realStore = persistentStore().apply {
                seedActiveSession(this, hostActivation("old-host", "1", OLD_PAT))
            }
            val dispatched = CompletableDeferred<Unit>()
            val releaseResponse = CompletableDeferred<Unit>()
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                activationResponses = listOf(
                    response(401, """{"code":"access_unauthenticated"}"""),
                ),
            ).apply {
                onRequest = { call ->
                    if (call.path == OwnerAccessPath.HOST_ACTIVATIONS) {
                        dispatched.complete(Unit)
                        releaseResponse.await()
                    }
                }
            }
            val identity = RecordingOwnerIdentity(events, OWNER)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(
                identity,
                transport,
                RecordingRemoteHostSessionStore(events, realStore),
                scope,
            )

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                val activation = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.confirmHostActivation(confirmation.confirmationId)
                }
                dispatched.await()

                when (invalidation) {
                    "origin" -> transport.origin = "https://other.example.test"
                    "sign-out" -> coordinator.signOut()
                    "close" -> coordinator.close()
                }
                releaseResponse.complete(Unit)
                activation.await()

                assertFalse(
                    "stale $invalidation callback restored the old active PAT",
                    realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession,
                )
                assertEquals(
                    0,
                    transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
                )
                assertEquals(1, transport.activationPostCount)
            } finally {
                releaseResponse.complete(Unit)
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun accountChangeDuringKnownNoWriteRestoreReconcilesTheStaleActiveWrite() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore().apply {
            seedActiveSession(this, hostActivation("old-host", "1", OLD_PAT))
        }
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val store = RecordingRemoteHostSessionStore(events, realStore) {
            commitStarted.complete(Unit)
            releaseCommit.await()
        }
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            activationResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val activation = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }
            commitStarted.await()

            identity.changeAccount(OwnerAccount("different-project", "different-uid", null))
            releaseCommit.complete(Unit)
            activation.await()

            assertFalse(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            assertEquals(
                0,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
            assertEquals(1, transport.activationPostCount)
        } finally {
            releaseCommit.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun callerCancellationDuringStaleKnownNoWriteRestoreClearsTheRestoredSession() = runBlocking {
        val events = mutableListOf<String>()
        val restoreWriteStarted = CompletableDeferred<Unit>()
        val releaseRestoreWrite = CountDownLatch(1)
        val file = MemoryAtomicRemoteHostFile().apply {
            blockOnWrite = 4
            blockedWriteStarted = restoreWriteStarted
            releaseBlockedWrite = releaseRestoreWrite
        }
        val realStore = persistentStore(file, Dispatchers.IO)
        seedActiveSession(realStore, hostActivation("old-host", "1", OLD_PAT))
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            activationResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val installationGetsBeforeActivation = transport.calls.count { call ->
                call.path == OwnerAccessPath.INSTALLATION
            }
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val activation = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }

            assertEquals(
                true,
                withTimeoutOrNull(5_000L) {
                    restoreWriteStarted.await()
                    true
                },
            )
            assertEquals(4, file.writeCount)
            identity.changeAccount(OwnerAccount("different-project", "different-uid", null))
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)

            val callerCancellation = CancellationException("synthetic caller cancellation")
            activation.cancel(callerCancellation)
            releaseRestoreWrite.countDown()
            val propagatedCancellation = try {
                activation.await()
                null
            } catch (cancelled: CancellationException) {
                cancelled
            }

            assertNotNull(propagatedCancellation)
            assertTrue(propagatedCancellation is CancellationException)
            assertFalse(
                "stale cancelled restore left the old active PAT persisted",
                realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession,
            )
            assertEquals(
                installationGetsBeforeActivation,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
            assertEquals(1, transport.activationPostCount)
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
            assertNull(coordinator.state.value.verifiedHost)
        } finally {
            releaseRestoreWrite.countDown()
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedDiscoveryTransportExceptionIsReportedToTheCaller() = runBlocking {
        val events = mutableListOf<String>()
        val unexpected = IllegalStateException("synthetic-private-detail")
        val realStore = persistentStore()
        val transport = ScriptedOwnerAccessTransport(events).apply {
            onRequest = { throw unexpected }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            val thrown = try {
                coordinator.discoverOwnerMonitor()
                null
            } catch (exception: Exception) {
                exception
            }

            assertTrue(thrown === unexpected)
            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertFalse(coordinator.state.value.toString().contains("synthetic-private-detail"))
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostSessionLoad.Empty, realStore.load(REMOTE_CONTEXT))
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedPreJournalStoreExceptionIsReportedWithoutDispatchingPost() = runBlocking {
        val events = mutableListOf<String>()
        val unexpected = IllegalStateException("synthetic-private-detail")
        val realStore = persistentStore()
        val store = object : RemoteHostSessionStore by realStore {
            override suspend fun beginAttempt(
                expectedContext: RemoteHostOwnerContext,
                expectedGeneration: OwnerGeneration,
            ): RemoteHostSessionWriteResult = throw unexpected
        }
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            store,
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val thrown = try {
                coordinator.confirmHostActivation(confirmation.confirmationId)
                null
            } catch (exception: Exception) {
                exception
            }

            assertTrue(thrown === unexpected)
            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertFalse(coordinator.state.value.toString().contains("synthetic-private-detail"))
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostSessionLoad.Empty, realStore.load(REMOTE_CONTEXT))
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedActivationCommitStorageExceptionEscapesAndKeepsPendingJournal() = runBlocking {
        val events = mutableListOf<String>()
        val unexpected = IllegalStateException("synthetic-private-detail")
        val file = MemoryAtomicRemoteHostFile().apply {
            failOnWrite = 2
            failureOnWrite = unexpected
        }
        val realStore = persistentStore(file)
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val thrown = try {
                coordinator.confirmHostActivation(confirmation.confirmationId)
                null
            } catch (exception: Exception) {
                exception
            }

            assertTrue(thrown === unexpected)
            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertFalse(coordinator.state.value.toString().contains("synthetic-private-detail"))
            assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                0,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun unexpectedInstallationValidationExceptionEscapesWithoutClaimingActiveConfirmation() =
        runBlocking {
            val events = mutableListOf<String>()
            val unexpected = IllegalStateException("synthetic-private-detail")
            val realStore = persistentStore()
            val transport = ScriptedOwnerAccessTransport(events).apply {
                onRequest = { call ->
                    if (call.path == OwnerAccessPath.INSTALLATION) {
                        throw unexpected
                    }
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(
                RecordingOwnerIdentity(events, OWNER),
                transport,
                RecordingRemoteHostSessionStore(events, realStore),
                scope,
            )

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                val thrown = try {
                    coordinator.confirmHostActivation(confirmation.confirmationId)
                    null
                } catch (exception: Exception) {
                    exception
                }

                assertTrue(thrown === unexpected)
                assertEquals(
                    RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                    coordinator.state.value.phase,
                )
                assertFalse(coordinator.state.value.toString().contains("synthetic-private-detail"))
                assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
                assertEquals(1, transport.activationPostCount)
                assertEquals(
                    1,
                    transport.calls.count { call ->
                        call.path == OwnerAccessPath.INSTALLATION
                    },
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun confirmationIdGeneratorExceptionEscapesInsteadOfBecomingOrdinaryUiFailure() = runBlocking {
        val events = mutableListOf<String>()
        val unexpected = IllegalStateException("synthetic-private-detail")
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = RemoteHostCoordinator(
            identity = RecordingOwnerIdentity(events, OWNER),
            ownerAccessClient = OwnerAccessClient(transport),
            sessionStore = RecordingRemoteHostSessionStore(events),
            parentScope = scope,
            confirmationId = { throw unexpected },
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            val thrown = try {
                coordinator.discoverOwnerMonitor()
                null
            } catch (exception: Exception) {
                exception
            }

            assertTrue(thrown === unexpected)
            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertFalse(coordinator.state.value.toString().contains("synthetic-private-detail"))
            assertEquals(0, transport.activationPostCount)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun identityTokenUnavailableRemainsAnExpectedSafeOperationalFailure() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER).apply {
            firebaseTokenFailure =
                IdentityOperationException(IdentityFailure.FIREBASE_TOKEN_UNAVAILABLE)
        }
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()

            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(
                OwnerAccessFailureReason.NOT_AUTHENTICATED,
                coordinator.state.value.safeFailure,
            )
            assertTrue(transport.calls.isEmpty())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun cancellationAfterPendingWriteBeforeBeginReturnsShowsDurableUncertainty() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        val pendingWritten = CompletableDeferred<Unit>()
        val returnFromBegin = CompletableDeferred<Unit>()
        val store = object : RemoteHostSessionStore by realStore {
            override suspend fun beginAttempt(
                expectedContext: RemoteHostOwnerContext,
                expectedGeneration: OwnerGeneration,
            ): RemoteHostSessionWriteResult {
                val result = realStore.beginAttempt(expectedContext, expectedGeneration)
                pendingWritten.complete(Unit)
                returnFromBegin.await()
                return result
            }
        }
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            store,
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            val activation = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }
            pendingWritten.await()
            activation.cancelAndJoin()

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
            assertEquals(0, transport.activationPostCount)
        } finally {
            returnFromBegin.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun installationCredentialUnauthorizedClearsOnlyItsMatchingSavedHostSession() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        seedActiveSession(realStore, hostActivation("installation-opaque", "1", OLD_PAT))
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            installationResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.initialize()

            assertEquals(RemoteHostPhase.CREDENTIAL_LOST, coordinator.state.value.phase)
            assertEquals(OwnerAccessFailureReason.NOT_AUTHENTICATED, coordinator.state.value.safeFailure)
            assertEquals(RemoteHostSessionLoad.Empty, realStore.load(REMOTE_CONTEXT))
            assertEquals(0, identity.signOutCalls)
            assertEquals(
                listOf(OwnerAccessPath.INSTALLATION),
                transport.calls.map { call -> call.path },
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun remoteHostActionsLeaveTheObservedLocalMonitorSnapshotAndJournalUntouched() = runBlocking {
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val localStore = PassiveLocalMonitorStore(
            MonitorOverview(
                reference = br.com.c6tag.diagnostico.monitor.BalanceReference(
                    id = 12L,
                    balanceCents = 54_321L,
                    confirmedAtMs = 1_700_000_000_000L,
                    observationCursor = 9L,
                ),
                balanceCents = 54_321L,
                continuityPreserved = true,
                pendingCount = 2L,
                lastObservedAtMs = 1_700_000_000_100L,
            ),
        )
        val localEffects = RecordingLocalMonitorEffects()
        val diagnosticRecorder = DiagnosticRecorder(
            store = EmptyDiagnosticStore(),
            allowedPackage = "synthetic.bank",
            ioDispatcher = Dispatchers.IO,
            scope = localScope,
        )
        val localMonitor = MonitorCoordinator(
            recorder = diagnosticRecorder,
            store = localStore,
            effects = localEffects,
            scope = localScope,
            clock = { 1_700_000_000_200L },
            processSessionId = "synthetic-monitor-process",
        )

        val remoteEvents = mutableListOf<String>()
        val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val remoteTransport = ScriptedOwnerAccessTransport(remoteEvents)
        val remoteCoordinator = coordinator(
            RecordingOwnerIdentity(remoteEvents, OWNER),
            remoteTransport,
            RecordingRemoteHostSessionStore(remoteEvents),
            remoteScope,
        )

        try {
            localMonitor.overview()
            awaitObserved(localStore.revisions, localStore.revisions.value.value)
            localMonitor.overview()
            val baselineOverview = localStore.current
            val baselineRevision = localStore.revisions.value
            val baselineMutationCount = localStore.mutations.size
            val baselineEffects = localEffects.snapshot()
            val baselineCoordinatorState = localMonitor.state.value
            val baselineDiagnosticState = diagnosticRecorder.state.value

            remoteCoordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            remoteCoordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(remoteCoordinator.state.value.confirmation)
            remoteCoordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, remoteCoordinator.state.value.phase)
            assertEquals(baselineOverview, localStore.current)
            assertEquals(baselineRevision, localStore.revisions.value)
            assertEquals(baselineMutationCount, localStore.mutations.size)
            assertEquals(baselineEffects, localEffects.snapshot())
            assertEquals(baselineCoordinatorState, localMonitor.state.value)
            assertEquals(baselineDiagnosticState, diagnosticRecorder.state.value)
            assertEquals(1, remoteTransport.activationPostCount)
        } finally {
            remoteCoordinator.close()
            remoteScope.cancel()
            localMonitor.close()
            diagnosticRecorder.closeAndJoin()
            localScope.cancel()
        }
    }

    @Test
    fun replacementNeedsItsWarningAndDuplicateConfirmationTapsShareOnePost() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        seedActiveSession(realStore, hostActivation("old-host", "8", OLD_PAT))
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val identity = RecordingOwnerIdentity(events, OWNER)
        val dispatched = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, ownerMonitorFixture("9", "old-host")),
            ),
            activationResponses = listOf(
                response(201, activationFixture("new-host", "10", NEW_PAT)),
            ),
            installationResponses = listOf(
                response(200, installationFixture("new-host", "10")),
            ),
        ).apply {
            onRequest = { call ->
                if (call.path == OwnerAccessPath.HOST_ACTIVATIONS) {
                    dispatched.complete(Unit)
                    releaseResponse.await()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            assertTrue(confirmation.requiresHostReplacementWarning)

            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostPhase.CONFIRMATION_READY, coordinator.state.value.phase)

            val firstTap = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(
                    confirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )
            }
            dispatched.await()
            coordinator.confirmHostActivation(
                confirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )
            assertEquals(1, transport.activationPostCount)
            releaseResponse.complete(Unit)
            firstTap.await()

            assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                RemoteHostPhase.ACTIVE_CONFIRMED,
                coordinator.state.value.phase,
            )
            val persisted = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
            assertEquals("new-host", persisted.activation.installationId)
            assertEquals(NEW_PAT, persisted.activation.accessToken.reveal())
            assertFalse(coordinator.state.value.toString().contains(NEW_PAT))
            coordinator.confirmHostActivation(
                confirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )
            assertEquals(1, transport.activationPostCount)
        } finally {
            releaseResponse.complete(Unit)
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun accountAndCanonicalOriginChangesInvalidateTheBoundConfirmation() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, EMPTY_OWNER_MONITOR),
                response(200, EMPTY_OWNER_MONITOR),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val originalConfirmation = requireNotNull(coordinator.state.value.confirmation)

            identity.changeAccount(OWNER.copy(label = "Renamed display label"))

            assertEquals(
                originalConfirmation.confirmationId,
                coordinator.state.value.confirmation?.confirmationId,
            )

            transport.origin = "https://other.example.test"
            coordinator.confirmHostActivation(originalConfirmation.confirmationId)

            assertNull(coordinator.state.value.confirmation)
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
            assertEquals(0, transport.activationPostCount)

            coordinator.discoverOwnerMonitor()
            val otherConfirmation = requireNotNull(coordinator.state.value.confirmation)
            identity.changeAccount(OwnerAccount("different-project", "different-uid", null))
            assertNull(coordinator.state.value.confirmation)
            assertEquals(RemoteHostPhase.SIGNED_IN_API_NOT_READY, coordinator.state.value.phase)
            coordinator.confirmHostActivation(otherConfirmation.confirmationId)
            assertEquals(0, transport.activationPostCount)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun accountSwitchBeforeActivationDispatchClearsOnlyTheKnownUnsentJournal() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore().apply {
            seedActiveSession(this, hostActivation("old-host", "1", OLD_PAT))
        }
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val identity = RecordingOwnerIdentity(events, OWNER)
        val tokenRequested = CompletableDeferred<Unit>()
        val releaseToken = CompletableDeferred<SecretValue>()
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            identity.firebaseTokenRequest = {
                tokenRequested.complete(Unit)
                releaseToken.await()
            }
            val confirmationJob = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.confirmHostActivation(confirmation.confirmationId)
            }
            tokenRequested.await()

            identity.changeAccount(OwnerAccount("other-project", "other-uid", null))
            releaseToken.complete(SecretValue.of("synthetic-firebase-id-token"))
            confirmationJob.await()

            assertEquals(0, transport.activationPostCount)
            assertFalse(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            assertEquals(
                0,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
            assertEquals(
                RemoteHostPhase.SIGNED_IN_API_NOT_READY,
                coordinator.state.value.phase,
            )
        } finally {
            releaseToken.complete(SecretValue.of("synthetic-firebase-id-token"))
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun generationConflictRequiresFreshDiscoveryAndNewConfirmationWithoutIncrementingGeneration() =
        runBlocking {
            val events = mutableListOf<String>()
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(
                    response(200, ownerMonitorFixture("0", "unused-host")),
                    response(200, ownerMonitorFixture("41", "unused-host")),
                ),
                activationResponses = listOf(
                    response(409, """{"code":"generation_conflict"}"""),
                    response(201, ACTIVATION),
                ),
            )
            val identity = RecordingOwnerIdentity(events, OWNER)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(
                identity,
                transport,
                RecordingRemoteHostSessionStore(events),
                scope,
            )

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val firstConfirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(
                    firstConfirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )

                assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                assertEquals(
                    OwnerAccessFailureReason.GENERATION_CONFLICT,
                    coordinator.state.value.safeFailure,
                )
                assertEquals(1, transport.activationPostCount)
                coordinator.confirmHostActivation(
                    firstConfirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )
                assertEquals(1, transport.activationPostCount)

                coordinator.discoverOwnerMonitor()
                val freshConfirmation = requireNotNull(coordinator.state.value.confirmation)
                assertFalse(firstConfirmation.confirmationId == freshConfirmation.confirmationId)
                assertEquals("41", freshConfirmation.generation.decimal)
                assertEquals(1, transport.activationPostCount)

                coordinator.confirmHostActivation(
                    freshConfirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )

                assertEquals(2, transport.activationPostCount)
                assertEquals(
                    listOf(
                        """{"confirmation":true,"expectedGeneration":"0"}""",
                        """{"confirmation":true,"expectedGeneration":"41"}""",
                    ),
                    transport.calls
                        .filter { call -> call.path == OwnerAccessPath.HOST_ACTIVATIONS }
                        .map { call -> call.body },
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun rateLimitDisplaysValidatedSecondsAndNeverAutomaticallyRepeatsPost() = runBlocking {
        val events = mutableListOf<String>()
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            activationResponses = listOf(
                OwnerAccessTransportResult.Response(
                    statusCode = 429,
                    body = """{"code":"rate_limited","message":"synthetic raw detail"}"""
                        .toByteArray(Charsets.UTF_8),
                    retryAfterSeconds = 37L,
                ),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(OwnerAccessFailureReason.RATE_LIMITED, coordinator.state.value.safeFailure)
            assertEquals(37L, coordinator.state.value.retryAfterSeconds)
            assertFalse(coordinator.state.value.toString().contains("synthetic raw detail"))
            assertEquals(1, transport.activationPostCount)
            coordinator.confirmHostActivation(confirmation.confirmationId)
            assertEquals(1, transport.activationPostCount)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun recentAuthenticationMismatchRequiresFreshDiscoveryAndNewConfirmation() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER).apply {
            reauthenticationOutcome = IdentityOutcome.ACCOUNT_MISMATCH
        }
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, ownerMonitorFixture("0", "unused-host")),
                response(200, ownerMonitorFixture("20", "unused-host")),
            ),
            activationResponses = listOf(
                response(403, """{"code":"recent_authentication_required"}"""),
                response(201, ACTIVATION),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            identity,
            transport,
            RecordingRemoteHostSessionStore(events),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val staleConfirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(
                staleConfirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )

            assertEquals(
                RemoteHostPhase.REAUTHENTICATION_REQUIRED,
                coordinator.state.value.phase,
            )
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                IdentityOutcome.ACCOUNT_MISMATCH,
                coordinator.reauthenticate(SecretValue.of("synthetic-mismatched-google-token")).outcome,
            )
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                1,
                transport.calls.count { call -> call.path == OwnerAccessPath.OWNER_MONITOR },
            )
            assertNull(coordinator.state.value.confirmation)

            identity.reauthenticationOutcome = IdentityOutcome.REAUTHENTICATED
            assertEquals(
                IdentityOutcome.REAUTHENTICATED,
                coordinator.reauthenticate(SecretValue.of("synthetic-fresh-google-token")).outcome,
            )
            coordinator.discoverOwnerMonitor()
            val freshConfirmation = requireNotNull(coordinator.state.value.confirmation)
            assertEquals("20", freshConfirmation.generation.decimal)
            assertFalse(staleConfirmation.confirmationId == freshConfirmation.confirmationId)
            assertEquals(1, transport.activationPostCount)

            coordinator.confirmHostActivation(
                freshConfirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )

            assertEquals(2, transport.activationPostCount)
            assertEquals(
                """{"confirmation":true,"expectedGeneration":"20"}""",
                transport.calls.last { call ->
                    call.path == OwnerAccessPath.HOST_ACTIVATIONS
                }.body,
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun ownerFirebase401DoesNotClearIndependentlySavedInstallationCredential() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        seedActiveSession(realStore, hostActivation("installation-opaque", "1", OLD_PAT))
        val store = RecordingRemoteHostSessionStore(events, realStore)
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
        )
        val identity = RecordingOwnerIdentity(events, OWNER)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()

            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
            assertEquals(OwnerAccessFailureReason.NOT_AUTHENTICATED, coordinator.state.value.safeFailure)
            assertEquals(0, identity.signOutCalls)
            assertEquals(
                listOf(OwnerAccessPath.OWNER_MONITOR),
                transport.calls.map { call -> call.path },
            )
            val saved = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
            assertEquals(OLD_PAT, saved.activation.accessToken.reveal())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun activation401And403RestoreAndRevalidateTheIndependentSavedPat() = runBlocking {
        listOf(
            401 to "access_unauthenticated",
            403 to "owner_not_authorized",
        ).forEach { (status, code) ->
            val events = mutableListOf<String>()
            val realStore = persistentStore()
            seedActiveSession(realStore, hostActivation("installation-opaque", "1", OLD_PAT))
            val store = RecordingRemoteHostSessionStore(events, realStore)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(
                    response(200, ownerMonitorFixture("1", "installation-opaque")),
                ),
                activationResponses = listOf(
                    response(status, """{"code":"$code"}"""),
                ),
                installationResponses = listOf(
                    response(200, INSTALLATION),
                ),
            )
            val identity = RecordingOwnerIdentity(events, OWNER)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(
                    confirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )

                assertEquals(1, transport.activationPostCount)
                assertEquals(
                    RemoteHostPhase.RECOVERABLE_ERROR,
                    coordinator.state.value.phase,
                )
                val saved = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
                assertEquals(OLD_PAT, saved.activation.accessToken.reveal())
                assertEquals(
                    listOf(
                        OwnerAccessPath.OWNER_MONITOR,
                        OwnerAccessPath.HOST_ACTIVATIONS,
                        OwnerAccessPath.INSTALLATION,
                    ),
                    transport.calls.map { call -> call.path },
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun serverErrorsAndMalformedSuccessBodiesRemainPendingAcrossRestart() = runBlocking {
        val ambiguousResponses = listOf(
            response(503, """{"code":"identity_verification_unavailable"}"""),
            response(200, "{}"),
            response(201, "{}"),
        )

        ambiguousResponses.forEach { activationResponse ->
            val realStore = persistentStore()
            val firstEvents = mutableListOf<String>()
            val firstTransport = ScriptedOwnerAccessTransport(
                events = firstEvents,
                activationResponses = listOf(activationResponse),
            )
            val identity = RecordingOwnerIdentity(firstEvents, OWNER)
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val firstCoordinator = coordinator(
                identity,
                firstTransport,
                RecordingRemoteHostSessionStore(firstEvents, realStore),
                firstScope,
            )
            val pending: RemoteHostSessionLoad.PendingAttempt

            try {
                firstCoordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                firstCoordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(firstCoordinator.state.value.confirmation)
                firstCoordinator.confirmHostActivation(confirmation.confirmationId)

                assertEquals(
                    RemoteHostPhase.ACTIVATION_UNCERTAIN,
                    firstCoordinator.state.value.phase,
                )
                assertEquals(1, firstTransport.activationPostCount)
                assertEquals(
                    0,
                    firstTransport.calls.count { call ->
                        call.path == OwnerAccessPath.INSTALLATION
                    },
                )
                pending = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.PendingAttempt
            } finally {
                firstCoordinator.close()
                firstScope.cancel()
            }

            val restartEvents = mutableListOf<String>()
            val restartTransport = ScriptedOwnerAccessTransport(restartEvents)
            val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val restarted = coordinator(
                identity,
                restartTransport,
                RecordingRemoteHostSessionStore(restartEvents, realStore),
                restartScope,
            )

            try {
                restarted.initialize()

                assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, restarted.state.value.phase)
                assertEquals(0, restartTransport.activationPostCount)
                assertTrue(restartTransport.calls.isEmpty())
                assertEquals(
                    RemoteHostSessionLoad.PendingAttempt(pending.attemptId, pending.expectedGeneration),
                    realStore.load(REMOTE_CONTEXT),
                )
            } finally {
                restarted.close()
                restartScope.cancel()
            }
        }
    }

    @Test
    fun activeCommitPersistenceFailureKeepsPendingAndNeverValidatesOrRetries() = runBlocking {
        val events = mutableListOf<String>()
        val file = MemoryAtomicRemoteHostFile().apply { failOnWrite = 2 }
        val realStore = persistentStore(file)
        val transport = ScriptedOwnerAccessTransport(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(RemoteHostPhase.ACTIVATION_UNCERTAIN, coordinator.state.value.phase)
            assertTrue(realStore.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.PendingAttempt)
            assertEquals(2, file.writeCount)
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                0,
                transport.calls.count { call -> call.path == OwnerAccessPath.INSTALLATION },
            )
            coordinator.confirmHostActivation(confirmation.confirmationId)
            assertEquals(1, transport.activationPostCount)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun offlineSavedPatAfterKnownNoWriteRetainsStalePhaseAndOwnerFailure() = runBlocking {
        val events = mutableListOf<String>()
        val realStore = persistentStore()
        seedActiveSession(realStore, hostActivation("installation-opaque", "1", OLD_PAT))
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            ownerResponses = listOf(
                response(200, ownerMonitorFixture("2", "installation-opaque")),
            ),
            activationResponses = listOf(
                response(401, """{"code":"access_unauthenticated"}"""),
            ),
            installationResponses = listOf(OwnerAccessTransportResult.NetworkFailure),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(
            RecordingOwnerIdentity(events, OWNER),
            transport,
            RecordingRemoteHostSessionStore(events, realStore),
            scope,
        )

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(
                confirmation.confirmationId,
                hostReplacementWarningConfirmed = true,
            )

            assertEquals(
                RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                coordinator.state.value.phase,
            )
            assertEquals(
                OwnerAccessFailureReason.NOT_AUTHENTICATED,
                coordinator.state.value.safeFailure,
            )
            assertEquals(
                OwnerAccessFailureReason.NETWORK_FAILURE,
                coordinator.state.value.hostValidationFailure,
            )
            assertEquals(1, transport.activationPostCount)
            assertEquals(
                listOf(
                    OwnerAccessPath.OWNER_MONITOR,
                    OwnerAccessPath.HOST_ACTIVATIONS,
                    OwnerAccessPath.INSTALLATION,
                ),
                transport.calls.map { call -> call.path },
            )
            val saved = realStore.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession
            assertEquals(OLD_PAT, saved.activation.accessToken.reveal())
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    private class RecordingOwnerIdentity(
        private val events: MutableList<String>,
        private val signedInAccount: OwnerAccount,
    ) : OwnerIdentity {
        private val mutableAccount = MutableStateFlow<OwnerAccount?>(null)
        override val account: StateFlow<OwnerAccount?> = mutableAccount
        var signOutCalls = 0
        var reauthenticationOutcome = IdentityOutcome.REAUTHENTICATED
        var reauthenticationCalls = 0
        var reauthenticationRequest: (suspend () -> IdentityResult)? = null
        var firebaseTokenRequest: (suspend () -> SecretValue)? = null
        var firebaseTokenFailure: Exception? = null

        override suspend fun signIn(googleIdToken: SecretValue): IdentityResult {
            events += "identity.sign-in"
            mutableAccount.value = signedInAccount
            return IdentityResult(IdentityOutcome.SIGNED_IN_CANDIDATE)
        }

        override suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult {
            events += "identity.reauthenticate"
            reauthenticationCalls += 1
            return reauthenticationRequest?.invoke() ?: IdentityResult(reauthenticationOutcome)
        }

        override suspend fun firebaseIdToken(): SecretValue {
            firebaseTokenFailure?.let { failure -> throw failure }
            return firebaseTokenRequest?.invoke()
                ?: SecretValue.of("synthetic-firebase-id-token")
        }

        override suspend fun signOut(): IdentityResult {
            signOutCalls += 1
            mutableAccount.value = null
            return IdentityResult(IdentityOutcome.SIGNED_OUT)
        }

        fun changeAccount(account: OwnerAccount?) {
            mutableAccount.value = account
        }
    }

    private class ScriptedOwnerAccessTransport(
        private val events: MutableList<String>,
        activationResponses: List<OwnerAccessTransportResult> = listOf(
            response(201, ACTIVATION),
        ),
        ownerResponses: List<OwnerAccessTransportResult> = listOf(
            response(200, EMPTY_OWNER_MONITOR),
        ),
        installationResponses: List<OwnerAccessTransportResult> = listOf(
            response(200, INSTALLATION),
        ),
    ) : OwnerAccessTransport {
        override var origin: String? = ORIGIN
        val calls = mutableListOf<Call>()
        private val responses = mutableMapOf(
            OwnerAccessPath.OWNER_MONITOR to ArrayDeque(ownerResponses),
            OwnerAccessPath.HOST_ACTIVATIONS to ArrayDeque(activationResponses),
            OwnerAccessPath.INSTALLATION to ArrayDeque(installationResponses),
        )
        var onRequest: (suspend (Call) -> Unit)? = null
        val activationPostCount: Int
            get() = calls.count { call -> call.path == OwnerAccessPath.HOST_ACTIVATIONS }

        override suspend fun execute(
            method: OwnerAccessMethod,
            path: OwnerAccessPath,
            credential: OwnerAccessCredential,
            body: String?,
        ): OwnerAccessTransportResult {
            val call = Call(method, path, credential.kind, body)
            calls += call
            events += "${method.name}:$path"
            onRequest?.invoke(call)
            return responses.getValue(path).removeFirstOrNull()
                ?: OwnerAccessTransportResult.NetworkFailure
        }

        data class Call(
            val method: OwnerAccessMethod,
            val path: OwnerAccessPath,
            val credentialKind: OwnerAccessCredentialKind,
            val body: String?,
        )
    }

    private class RecordingRemoteHostSessionStore(
        private val events: MutableList<String>,
        private val delegate: RemoteHostSessionStore = persistentStore(),
        private val beforeCommit: suspend () -> Unit = {},
    ) : RemoteHostSessionStore {
        override suspend fun load(expectedContext: RemoteHostOwnerContext): RemoteHostSessionLoad =
            delegate.load(expectedContext)

        override suspend fun beginAttempt(
            expectedContext: RemoteHostOwnerContext,
            expectedGeneration: OwnerGeneration,
        ): RemoteHostSessionWriteResult {
            val result = delegate.beginAttempt(expectedContext, expectedGeneration)
            if (result is RemoteHostSessionWriteResult.PendingAttemptStarted) {
                assertEquals(
                    RemoteHostSessionLoad.PendingAttempt(result.attemptId, expectedGeneration),
                    delegate.load(expectedContext),
                )
                events += "journal.pending"
            }
            return result
        }

        override suspend fun beginRecoveryAttempt(
            expectedContext: RemoteHostOwnerContext,
            previousAttemptId: RemoteHostAttemptId,
            expectedGeneration: OwnerGeneration,
        ): RemoteHostSessionWriteResult {
            val result = delegate.beginRecoveryAttempt(
                expectedContext,
                previousAttemptId,
                expectedGeneration,
            )
            if (result is RemoteHostSessionWriteResult.PendingAttemptStarted) {
                assertEquals(
                    RemoteHostSessionLoad.PendingAttempt(result.attemptId, expectedGeneration),
                    delegate.load(expectedContext),
                )
                events += "journal.pending"
            }
            return result
        }

        override suspend fun commitAttempt(
            expectedContext: RemoteHostOwnerContext,
            attemptId: RemoteHostAttemptId,
            activation: HostActivation,
        ): RemoteHostSessionWriteResult {
            beforeCommit()
            val result = delegate.commitAttempt(expectedContext, attemptId, activation)
            if (result == RemoteHostSessionWriteResult.ActiveSessionCommitted) {
                events += "journal.commit"
            }
            return result
        }

        override suspend fun clearPendingAttempt(
            expectedContext: RemoteHostOwnerContext,
            attemptId: RemoteHostAttemptId,
        ): RemoteHostSessionWriteResult {
            return delegate.clearPendingAttempt(expectedContext, attemptId)
        }

        override suspend fun clearActiveSession(
            expectedContext: ExpectedHostAccessContext,
        ): RemoteHostSessionWriteResult {
            return delegate.clearActiveSession(expectedContext)
        }
    }

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        private var record: ByteArray? = null
        var writeCount = 0
            private set
        var failOnWrite: Int? = null
        var failureOnWrite: RuntimeException? = null
        var blockOnWrite: Int? = null
        var blockedWriteStarted: CompletableDeferred<Unit>? = null
        var releaseBlockedWrite: CountDownLatch? = null

        override fun readRecord(maximumBytes: Int): ByteArray? {
            val current = record ?: return null
            return current.copyOf(minOf(current.size, maximumBytes))
        }

        override fun writeAtomically(record: ByteArray) {
            writeCount += 1
            if (writeCount == blockOnWrite) {
                blockedWriteStarted?.complete(Unit)
                releaseBlockedWrite?.await()
            }
            if (writeCount == failOnWrite) {
                throw failureOnWrite ?: IOException("synthetic persistent store write failure")
            }
            this.record = record.copyOf()
        }

        override fun deleteRecord() {
            record = null
        }
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        private val key: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

        override fun existingKey(): SecretKey? = key

        override fun keyForNewRecord(): SecretKey = key
    }

    private class EmptyDiagnosticStore : DiagnosticStore {
        override fun append(
            event: br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent,
        ): Long = 1L

        override fun page(beforeId: Long?, limit: Int): List<StoredDiagnosticEvent> = emptyList()

        override fun clear() = Unit

        override fun <T> exportSnapshot(
            block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
        ): T = block(DiagnosticSnapshotMetadata(0L, null, null), emptySequence())
    }

    private class PassiveLocalMonitorStore(
        initial: MonitorOverview,
    ) : MonitorStore {
        private var overview = initial
        override val revisions = ObservedMonitorRevisions(initial.revision)
        val mutations = mutableListOf<String>()
        val current: MonitorOverview
            get() = overview

        override fun overview(): MonitorOverview = overview

        override fun history(
            beforeId: Long?,
            limit: Int,
            filter: HistoryFilter,
        ): List<FinancialEntry> = emptyList()

        override fun pending(beforeId: Long?, limit: Int): List<PendingMovement> = emptyList()

        override fun beginReference(nowMs: Long): MonitorResult {
            mutations += "beginReference"
            return MonitorResult(overview)
        }

        override fun confirmReference(command: ConfirmReference): MonitorResult {
            mutations += "confirmReference"
            return MonitorResult(overview)
        }

        override fun cancelReference(sessionId: String, nowMs: Long): MonitorResult {
            mutations += "cancelReference"
            return MonitorResult(overview)
        }

        override fun refresh(nowMs: Long): MonitorResult {
            mutations += "refresh"
            return MonitorResult(overview)
        }

        override fun resolvePending(command: ResolvePending): MonitorResult {
            mutations += "resolvePending"
            return MonitorResult(overview)
        }

        override fun changeSettings(command: ChangeSettings): MonitorResult {
            mutations += "changeSettings"
            return MonitorResult(overview)
        }

        override fun acknowledge(cycleId: String, nowMs: Long): MonitorResult {
            mutations += "acknowledge"
            return MonitorResult(overview)
        }

        override fun resume(cycleId: String, nowMs: Long): MonitorResult {
            mutations += "resume"
            return MonitorResult(overview)
        }

        override fun evaluateAlert(nowMs: Long): AlertEvaluation =
            AlertEvaluation(overview.alertCycle)

        override fun recordSubmission(command: RecordSubmission): MonitorResult {
            mutations += "recordSubmission"
            return MonitorResult(overview)
        }

        override fun startProcessSession(sessionId: String, nowMs: Long): MonitorResult {
            mutations += "startProcessSession"
            overview = overview.copy(
                continuityPreserved = false,
                revision = MonitorRevision(
                    value = overview.revision.value + 1L,
                    cause = RevisionCause.PROCESS_SESSION,
                ),
            )
            revisions.publish(overview.revision)
            return MonitorResult(overview)
        }
    }

    private suspend fun awaitObserved(
        revisions: ObservedMonitorRevisions,
        revision: Long,
    ) {
        val observed = withTimeoutOrNull(5_000L) {
            while (true) {
                if (revisions.observed.receive().value >= revision) {
                    return@withTimeoutOrNull true
                }
            }
        }
        assertEquals(true, observed)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ObservedMonitorRevisions(initial: MonitorRevision) : StateFlow<MonitorRevision> {
        private val state = MutableStateFlow(initial)
        val observed = Channel<MonitorRevision>(Channel.UNLIMITED)

        override val value: MonitorRevision
            get() = state.value

        override val replayCache: List<MonitorRevision>
            get() = state.replayCache

        fun publish(revision: MonitorRevision) {
            state.value = revision
        }

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<MonitorRevision>): Nothing {
            state.collect { revision ->
                collector.emit(revision)
                observed.send(revision)
            }
        }
    }

    private class RecordingLocalMonitorEffects : MonitorAlertEffects {
        private val synchronizedRevisions = mutableListOf<MonitorRevision>()
        private val submissions = mutableListOf<EmissionIntent>()

        override fun synchronize(overview: MonitorOverview) {
            synchronizedRevisions += overview.revision
        }

        override fun submit(
            overview: MonitorOverview,
            intent: EmissionIntent,
        ): SubmissionOutcome {
            submissions += intent
            return SubmissionOutcome.ACCEPTED
        }

        fun snapshot(): Pair<List<MonitorRevision>, List<EmissionIntent>> =
            synchronizedRevisions.toList() to submissions.toList()
    }

    private fun coordinator(
        identity: OwnerIdentity,
        transport: ScriptedOwnerAccessTransport,
        store: RemoteHostSessionStore,
        scope: CoroutineScope,
    ): RemoteHostCoordinator {
        val confirmationSequence = AtomicInteger()
        return RemoteHostCoordinator(
            identity = identity,
            ownerAccessClient = OwnerAccessClient(transport),
            sessionStore = store,
            parentScope = scope,
            confirmationId = {
                "confirmation-${confirmationSequence.incrementAndGet()}"
            },
        )
    }

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private suspend fun seedActiveSession(
        store: RemoteHostSessionStore,
        activation: HostActivation,
    ) {
        val attempt = store.beginAttempt(REMOTE_CONTEXT, generation("0"))
        assertTrue(attempt is RemoteHostSessionWriteResult.PendingAttemptStarted)
        assertEquals(
            RemoteHostSessionWriteResult.ActiveSessionCommitted,
            store.commitAttempt(
                REMOTE_CONTEXT,
                (attempt as RemoteHostSessionWriteResult.PendingAttemptStarted).attemptId,
                activation,
            ),
        )
    }

    private companion object {
        const val ORIGIN = "https://api.example.test"
        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val REMOTE_CONTEXT = RemoteHostOwnerContext(OWNER, ORIGIN)

        fun persistentStore(
            file: MemoryAtomicRemoteHostFile = MemoryAtomicRemoteHostFile(),
            ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        ): PersistentRemoteHostSessionStore =
            PersistentRemoteHostSessionStore(
                file = file,
                keys = JceTestKeyProvider(),
                ioDispatcher = ioDispatcher,
            )

        fun response(
            statusCode: Int,
            body: String,
        ): OwnerAccessTransportResult.Response =
            OwnerAccessTransportResult.Response(
                statusCode = statusCode,
                body = body.toByteArray(Charsets.UTF_8),
            )
        const val EMPTY_OWNER_MONITOR =
            """{"data":{"monitor":null,"generation":"0","host":null,"requiresReference":true}}"""
        const val ACTIVATION =
            """{"data":{"monitorId":"monitor-opaque","installationId":"installation-opaque","membershipId":"membership-opaque","role":"Host","generation":"1","requiresReference":true,"accessToken":"synthetic-one-time-pat","createdAt":1790000000000}}"""
        const val INSTALLATION =
            """{"data":{"installationId":"installation-opaque","monitorId":"monitor-opaque","membershipId":"membership-opaque","role":"Host","status":"Active","monitorGeneration":"1"}}"""
        const val OLD_PAT = "synthetic-old-host-pat"
        const val NEW_PAT = "synthetic-new-host-pat"

        fun ownerMonitorFixture(generation: String, installationId: String): String =
            """{"data":{"monitor":{"id":"monitor-opaque"},"generation":"$generation","host":{"installationId":"$installationId","label":null},"requiresReference":true}}"""

        fun activationFixture(
            installationId: String,
            generation: String,
            accessToken: String,
        ): String =
            """{"data":{"monitorId":"monitor-opaque","installationId":"$installationId","membershipId":"membership-opaque","role":"Host","generation":"$generation","requiresReference":true,"accessToken":"$accessToken","createdAt":1790000000000}}"""

        fun installationFixture(installationId: String, generation: String): String =
            """{"data":{"installationId":"$installationId","monitorId":"monitor-opaque","membershipId":"membership-opaque","role":"Host","status":"Active","monitorGeneration":"$generation"}}"""

        fun hostActivation(
            installationId: String,
            generation: String,
            accessToken: String,
        ): HostActivation =
            HostActivation(
                monitorId = "monitor-opaque",
                installationId = installationId,
                membershipId = "membership-opaque",
                generation = requireNotNull(OwnerGeneration.parse(generation)),
                accessToken = SecretValue.of(accessToken),
                createdAtMillis = 1_790_000_000_000L,
                owner = OWNER,
                origin = ORIGIN,
            )
    }
}
