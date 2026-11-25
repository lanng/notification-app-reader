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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun hostActivationDoesNotBeginJournalWhenSupportSessionIsPresent() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(events)
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val guard = RemoteRoleGuard(
            hostPresence = { store.presence() },
            supportPresence = { RemoteSessionPresence.Present },
        )
        val coordinator = coordinator(identity, transport, store, scope, guard)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)

            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(0, store.beginAttemptCalls)
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun hostRecoveryDoesNotReplacePendingJournalWhenSupportSessionIsPresent() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(events)
        val store = RecordingRemoteHostSessionStore(events)
        val pending = store.delegate.beginAttempt(REMOTE_CONTEXT, generation("0"))
            as RemoteHostSessionWriteResult.PendingAttemptStarted
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val guard = RemoteRoleGuard(
            hostPresence = { store.presence() },
            supportPresence = { RemoteSessionPresence.Present },
        )
        val coordinator = coordinator(identity, transport, store, scope, guard)

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.initialize()
            coordinator.reauthenticate(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)

            coordinator.confirmHostActivation(confirmation.confirmationId)

            assertEquals(0, store.beginRecoveryAttemptCalls)
            assertEquals(
                RemoteHostSessionLoad.PendingAttempt(pending.attemptId, generation("0")),
                store.delegate.load(REMOTE_CONTEXT),
            )
            assertEquals(0, transport.activationPostCount)
            assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun administrationLeaseRequiresAnActiveConfirmedHostAndBindsOnlyVerifiedCredentials() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                    ),
                    provider.acquireLease(),
                )

                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                    ),
                    provider.acquireLease(),
                )

                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(confirmation.confirmationId)

                val lease = readyLease(provider.acquireLease())
                assertEquals(
                    HostAdministrationSessionResult.Ready(Unit),
                    provider.validateLease(lease),
                )
                assertFalse(lease.toString().contains(ACTIVATION_PAT))
                assertFalse(coordinator.state.value.toString().contains(ACTIVATION_PAT))

                var callbackCount = 0
                val operation = provider.withValidatedLease(lease) { hostLease ->
                    callbackCount += 1
                    assertEquals(ORIGIN, hostLease.origin)
                    assertEquals("monitor-opaque", hostLease.monitorId)
                    assertEquals(ACTIVATION_PAT, hostLease.accessToken.reveal())
                    "synthetic-admin-result"
                }

                assertEquals(
                    HostAdministrationSessionResult.Ready("synthetic-admin-result"),
                    operation,
                )
                assertEquals(1, callbackCount)
                assertEquals(1, transport.activationPostCount)
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

    @Test
    fun administrationLeaseRejectsMissingPendingUnavailableAndMismatchedHostRecords() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(confirmation.confirmationId)
                val activationPostsBeforeChecks = transport.activationPostCount

                val mismatchedRecords = listOf(
                    RemoteHostSessionLoad.Empty,
                    RemoteHostSessionLoad.PendingAttempt(
                        RemoteHostAttemptId.generated("00000000000000000000000000000001"),
                        generation("1"),
                    ),
                    RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.KEY_UNAVAILABLE),
                    RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.CORRUPT_RECORD),
                    RemoteHostSessionLoad.Unavailable(
                        RemoteHostSessionProblem.PERSISTENCE_FAILURE,
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation("different-installation", "1", ACTIVATION_PAT),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "1",
                            ACTIVATION_PAT,
                            monitorId = "different-monitor",
                        ),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "1",
                            ACTIVATION_PAT,
                            membershipId = "different-membership",
                        ),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "2",
                            ACTIVATION_PAT,
                        ),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "1",
                            "synthetic-replaced-host-pat",
                        ),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "1",
                            ACTIVATION_PAT,
                            owner = OwnerAccount("different-project", "different-uid", null),
                        ),
                    ),
                    RemoteHostSessionLoad.ActiveSession(
                        hostActivation(
                            "installation-opaque",
                            "1",
                            ACTIVATION_PAT,
                            origin = "https://other.example.test",
                        ),
                    ),
                )

                mismatchedRecords.forEach { mismatchedRecord ->
                    store.loadOverride = mismatchedRecord

                    assertEquals(
                        HostAdministrationSessionResult.Rejected(
                            HostAdministrationSessionFailure.SESSION_UNAVAILABLE,
                        ),
                        provider.acquireLease(),
                    )
                }
                assertEquals(activationPostsBeforeChecks, transport.activationPostCount)
                assertTrue(
                    (store.delegate.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession)
                        .activation.accessToken.reveal() == ACTIVATION_PAT,
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun administrationLeaseRevalidatesTheSavedRecordAfterTheOperationBeforeReturningResult() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(confirmation.confirmationId)
                val lease = readyLease(provider.acquireLease())

                val operation = provider.withValidatedLease(lease) {
                    store.loadOverride = RemoteHostSessionLoad.PendingAttempt(
                        RemoteHostAttemptId.generated("00000000000000000000000000000002"),
                        generation("1"),
                    )
                    "response-must-not-be-published"
                }

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.SESSION_UNAVAILABLE,
                    ),
                    operation,
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun changedSavedPatIsRejectedBeforePairingCredentialsReachAnOperation() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(events)
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)
        val provider: HostAdministrationSessionProvider = coordinator

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            coordinator.confirmHostActivation(
                requireNotNull(coordinator.state.value.confirmation).confirmationId,
            )
            val lease = readyLease(provider.acquireLease())
            store.loadOverride = RemoteHostSessionLoad.ActiveSession(
                hostActivation(
                    "installation-opaque",
                    "1",
                    "synthetic-file-replaced-host-pat",
                ),
            )
            var callbackCount = 0

            val result = provider.withValidatedLease(lease) {
                callbackCount += 1
                "must-not-run"
            }

            assertEquals(
                HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.SESSION_UNAVAILABLE,
                ),
                result,
            )
            assertEquals(0, callbackCount)
            assertEquals(
                ACTIVATION_PAT,
                (store.delegate.load(REMOTE_CONTEXT) as RemoteHostSessionLoad.ActiveSession)
                    .activation.accessToken.reveal(),
            )
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun adminAuthorizationRejectionInvalidatesLeaseUntilExplicitHostRevalidation() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                    OwnerAccessTransportResult.NetworkFailure,
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(confirmation.confirmationId)
                val oldLease = readyLease(provider.acquireLease())

                provider.invalidateLease(
                    oldLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
                assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)

                coordinator.initialize()

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                    ),
                    provider.revalidateAdministrationSession(),
                )
                coordinator.initialize()

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)

                assertEquals(
                    HostAdministrationSessionResult.Ready(Unit),
                    provider.revalidateAdministrationSession(),
                )
                val newLease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    oldLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                assertEquals(
                    HostAdministrationSessionResult.Ready(Unit),
                    provider.validateLease(newLease),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun oldLeaseCallbackCannotPublishOrInvalidateLeaseFromNewOwnerEpoch() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            installationResponses = listOf(
                response(200, INSTALLATION),
                response(200, INSTALLATION),
            ),
        )
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)
        val provider: HostAdministrationSessionProvider = coordinator
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        var oldOperation: kotlinx.coroutines.Deferred<HostAdministrationSessionResult<String>>? = null

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            val confirmation = requireNotNull(coordinator.state.value.confirmation)
            coordinator.confirmHostActivation(confirmation.confirmationId)
            val oldLease = readyLease(provider.acquireLease())

            oldOperation = async(start = CoroutineStart.UNDISPATCHED) {
                provider.withValidatedLease(oldLease) {
                    callbackStarted.complete(Unit)
                    releaseCallback.await()
                    "stale-response"
                }
            }
            withTimeout(BARRIER_TIMEOUT_MILLIS) {
                callbackStarted.await()
            }

            coordinator.initialize()
            val newLease = readyLease(provider.acquireLease())
            provider.invalidateLease(
                oldLease,
                HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
            )
            assertEquals(
                HostAdministrationSessionResult.Ready(Unit),
                provider.validateLease(newLease),
            )

            releaseCallback.complete(Unit)
            assertEquals(
                HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                ),
                withTimeout(BARRIER_TIMEOUT_MILLIS) { requireNotNull(oldOperation).await() },
            )
        } finally {
            releaseCallback.complete(Unit)
            withTimeout(BARRIER_TIMEOUT_MILLIS) {
                oldOperation?.cancelAndJoin()
            }
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun signOutInvalidatesAdministrationLeaseWithoutDeletingSavedHostRecord() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(events)
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)
        val provider: HostAdministrationSessionProvider = coordinator

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            coordinator.confirmHostActivation(
                requireNotNull(coordinator.state.value.confirmation).confirmationId,
            )
            val lease = readyLease(provider.acquireLease())

            coordinator.signOut()

            assertEquals(
                HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                ),
                provider.validateLease(lease),
            )
            assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun ownerChangeInvalidatesAdministrationLeaseWithoutDeletingPreviousOwnersRecord() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val lease = readyLease(provider.acquireLease())

                identity.changeAccount(OwnerAccount("other-project", "other-uid", null))

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    ),
                    provider.validateLease(lease),
                )
                assertTrue(
                    store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession,
                )
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun coordinatorCloseInvalidatesAdministrationLeaseWithoutDeletingSavedHostRecord() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val lease = readyLease(provider.acquireLease())

                coordinator.close()

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    ),
                    provider.validateLease(lease),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun offlineStaleHostStateCannotAcquireAdministrationLeaseOrClearSavedCredential() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    OwnerAccessTransportResult.NetworkFailure,
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                val confirmation = requireNotNull(coordinator.state.value.confirmation)
                coordinator.confirmHostActivation(confirmation.confirmationId)
                coordinator.initialize()

                assertEquals(
                    RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                    coordinator.state.value.phase,
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                    ),
                    provider.acquireLease(),
                )
                assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun realSupportStoreStatesBlockHostActivationAndRecoveryWithoutModifyingSupportRecord() =
        runBlocking {
            listOf(
                "attempt",
                "pending",
                "approved",
                "corrupt",
                "key-unavailable",
            ).forEach { supportState ->
                listOf(false, true).forEach { recoveryFlow ->
                    val events = mutableListOf<String>()
                    val identity = RecordingOwnerIdentity(events, OWNER)
                    val transport = ScriptedOwnerAccessTransport(events)
                    val hostStore = RecordingRemoteHostSessionStore(events)
                    val pendingAttempt = if (recoveryFlow) {
                        hostStore.delegate.beginAttempt(REMOTE_CONTEXT, generation("0"))
                            as RemoteHostSessionWriteResult.PendingAttemptStarted
                    } else {
                        null
                    }
                    val supportFile = MemoryAtomicRemoteHostFile()
                    val supportKeys = JceTestKeyProvider()
                    val supportStore = PersistentRemoteSupportSessionStore(
                        file = supportFile,
                        keys = supportKeys,
                        ioDispatcher = Dispatchers.Unconfined,
                    )
                    seedSupportState(supportState, supportStore, supportFile, supportKeys)
                    val originalSupportBytes = supportFile.snapshot()
                    val supportWritesBefore = supportFile.writeCount
                    val supportDeletesBefore = supportFile.deleteCount
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    val guard = RemoteRoleGuard(
                        hostPresence = { hostStore.presence() },
                        supportPresence = { supportStore.presence() },
                    )
                    val coordinator = coordinator(identity, transport, hostStore, scope, guard)

                    try {
                        coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                        if (recoveryFlow) {
                            coordinator.initialize()
                            coordinator.reauthenticate(SecretValue.of("synthetic-google-id-token"))
                        }
                        coordinator.discoverOwnerMonitor()
                        val confirmation = requireNotNull(coordinator.state.value.confirmation)

                        coordinator.confirmHostActivation(confirmation.confirmationId)

                        if (recoveryFlow) {
                            assertEquals(
                                "Support state '$supportState' must block host recovery",
                                0,
                                hostStore.beginRecoveryAttemptCalls,
                            )
                            assertEquals(
                                RemoteHostSessionLoad.PendingAttempt(
                                    requireNotNull(pendingAttempt).attemptId,
                                    generation("0"),
                                ),
                                hostStore.delegate.load(REMOTE_CONTEXT),
                            )
                        } else {
                            assertEquals(
                                "Support state '$supportState' must block host activation",
                                0,
                                hostStore.beginAttemptCalls,
                            )
                            assertEquals(
                                RemoteHostSessionLoad.Empty,
                                hostStore.delegate.load(REMOTE_CONTEXT),
                            )
                        }
                        assertEquals(0, transport.activationPostCount)
                        assertEquals(
                            RemoteHostPhase.RECOVERABLE_ERROR,
                            coordinator.state.value.phase,
                        )
                        assertEquals(
                            originalSupportBytes.toList(),
                            supportFile.snapshot().toList(),
                        )
                        assertEquals(supportWritesBefore, supportFile.writeCount)
                        assertEquals(supportDeletesBefore, supportFile.deleteCount)
                    } finally {
                        coordinator.close()
                        scope.cancel()
                    }
                }
            }
        }

    @Test
    fun unavailableSupportPresenceBlocksHostActivationAndRecoveryFailClosedWithoutHttpOrDeletion() =
        runBlocking {
            listOf(false, true).forEach { recoveryFlow ->
                val events = mutableListOf<String>()
                val identity = RecordingOwnerIdentity(events, OWNER)
                val transport = ScriptedOwnerAccessTransport(events)
                val hostFile = MemoryAtomicRemoteHostFile()
                val hostStore = RecordingRemoteHostSessionStore(
                    events = events,
                    delegate = persistentStore(hostFile),
                )
                val pendingAttempt = if (recoveryFlow) {
                    hostStore.delegate.beginAttempt(REMOTE_CONTEXT, generation("0"))
                        as RemoteHostSessionWriteResult.PendingAttemptStarted
                } else {
                    null
                }
                val supportFile = MemoryAtomicRemoteHostFile().apply {
                    nextReadFailure = IOException("synthetic support presence read failure")
                }
                val supportStore = PersistentRemoteSupportSessionStore(
                    file = supportFile,
                    keys = JceTestKeyProvider(),
                    ioDispatcher = Dispatchers.Unconfined,
                )
                assertEquals(
                    RemoteSessionPresence.Unavailable(
                        RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
                    ),
                    supportStore.presence(),
                )
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val guard = RemoteRoleGuard(
                    hostPresence = { hostStore.presence() },
                    supportPresence = { supportStore.presence() },
                )
                val coordinator = coordinator(identity, transport, hostStore, scope, guard)

                try {
                    coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                    if (recoveryFlow) {
                        coordinator.initialize()
                        coordinator.reauthenticate(SecretValue.of("synthetic-google-id-token"))
                    }
                    coordinator.discoverOwnerMonitor()
                    val confirmation = requireNotNull(coordinator.state.value.confirmation)

                    coordinator.confirmHostActivation(confirmation.confirmationId)

                    if (recoveryFlow) {
                        assertEquals(0, hostStore.beginRecoveryAttemptCalls)
                        assertEquals(
                            RemoteHostSessionLoad.PendingAttempt(
                                requireNotNull(pendingAttempt).attemptId,
                                generation("0"),
                            ),
                            hostStore.delegate.load(REMOTE_CONTEXT),
                        )
                    } else {
                        assertEquals(0, hostStore.beginAttemptCalls)
                        assertEquals(
                            RemoteHostSessionLoad.Empty,
                            hostStore.delegate.load(REMOTE_CONTEXT),
                        )
                    }
                    assertEquals(0, transport.activationPostCount)
                    assertEquals(RemoteHostPhase.RECOVERABLE_ERROR, coordinator.state.value.phase)
                    assertEquals(0, hostFile.deleteCount)
                    assertEquals(0, supportFile.deleteCount)
                } finally {
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun originChangeInvalidatesAdministrationLeaseWithoutDeletingSavedHostRecord() = runBlocking {
        val events = mutableListOf<String>()
        val identity = RecordingOwnerIdentity(events, OWNER)
        val transport = ScriptedOwnerAccessTransport(
            events = events,
            installationResponses = listOf(
                response(200, INSTALLATION),
                response(200, INSTALLATION),
            ),
        )
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = coordinator(identity, transport, store, scope)
        val provider: HostAdministrationSessionProvider = coordinator

        try {
            coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
            coordinator.discoverOwnerMonitor()
            coordinator.confirmHostActivation(
                requireNotNull(coordinator.state.value.confirmation).confirmationId,
            )
            val firstLease = readyLease(provider.acquireLease())

            transport.origin = "https://other.example.test"

            assertEquals(
                HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                ),
                provider.validateLease(firstLease),
            )

            transport.origin = ORIGIN
            coordinator.initialize()
            val secondLease = readyLease(provider.acquireLease())
            transport.origin = "https://other.example.test"
            var callbackCount = 0

            val operationResult = provider.withValidatedLease(secondLease) {
                callbackCount += 1
                "must-not-run"
            }

            assertEquals(
                HostAdministrationSessionResult.Rejected(
                    HostAdministrationSessionFailure.LEASE_INVALIDATED,
                ),
                operationResult,
            )
            assertEquals(0, callbackCount)
            assertTrue(store.delegate.load(REMOTE_CONTEXT) is RemoteHostSessionLoad.ActiveSession)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun revalidateAdministrationSessionDoesNotClearNewerAuthorizationRejectionDuringStoreLoad() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator
            val postInitLoadStarted = CompletableDeferred<Unit>()
            val releasePostInitLoad = CompletableDeferred<Unit>()
            var revalidation: kotlinx.coroutines.Deferred<HostAdministrationSessionResult<Unit>>? =
                null

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )

                val targetPostInitLoad = store.loadCalls + 2
                store.onLoad = { callNumber ->
                    if (callNumber == targetPostInitLoad) {
                        postInitLoadStarted.complete(Unit)
                        releasePostInitLoad.await()
                    }
                }

                revalidation = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.revalidateAdministrationSession()
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    postInitLoadStarted.await()
                }

                val newerLease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    newerLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                releasePostInitLoad.complete(Unit)
                val revalidationResult = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    revalidation.await()
                }

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    revalidationResult,
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
            } finally {
                releasePostInitLoad.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    revalidation?.cancelAndJoin()
                }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun revalidateAdministrationSessionReturnsLeaseInvalidatedWhenClosedBeforeOrDuringRevalidation() =
        runBlocking {
            listOf(1, 2).forEach { loadOffsetToClose ->
                val events = mutableListOf<String>()
                val identity = RecordingOwnerIdentity(events, OWNER)
                val transport = ScriptedOwnerAccessTransport(
                    events = events,
                    installationResponses = listOf(
                        response(200, INSTALLATION),
                        response(200, INSTALLATION),
                    ),
                )
                val store = RecordingRemoteHostSessionStore(events)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = coordinator(identity, transport, store, scope)
                val provider: HostAdministrationSessionProvider = coordinator
                val loadStarted = CompletableDeferred<Unit>()
                val releaseLoad = CompletableDeferred<Unit>()
                var revalidation:
                    kotlinx.coroutines.Deferred<HostAdministrationSessionResult<Unit>>? = null

                try {
                    coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                    coordinator.discoverOwnerMonitor()
                    coordinator.confirmHostActivation(
                        requireNotNull(coordinator.state.value.confirmation).confirmationId,
                    )
                    val lease = readyLease(provider.acquireLease())
                    provider.invalidateLease(
                        lease,
                        HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                    )

                    val targetLoad = store.loadCalls + loadOffsetToClose
                    store.onLoad = { callNumber ->
                        if (callNumber == targetLoad) {
                            loadStarted.complete(Unit)
                            releaseLoad.await()
                        }
                    }

                    revalidation = async(start = CoroutineStart.UNDISPATCHED) {
                        provider.revalidateAdministrationSession()
                    }
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        loadStarted.await()
                    }

                    coordinator.close()
                    releaseLoad.complete(Unit)

                    assertEquals(
                        "Expected LEASE_INVALIDATED when closed at load offset $loadOffsetToClose",
                        HostAdministrationSessionResult.Rejected(
                            HostAdministrationSessionFailure.LEASE_INVALIDATED,
                        ),
                        withTimeout(BARRIER_TIMEOUT_MILLIS) { revalidation.await() },
                    )
                    assertEquals(
                        HostAdministrationSessionResult.Rejected(
                            HostAdministrationSessionFailure.LEASE_INVALIDATED,
                        ),
                        provider.revalidateAdministrationSession(),
                    )
                } finally {
                    releaseLoad.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        revalidation?.cancelAndJoin()
                    }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun accountSwitchDuringRevalidateAdministrationSessionDoesNotClearEitherOwnersRejection() =
        runBlocking {
            val ownerA = OWNER
            val ownerB = OwnerAccount("other-project", "other-uid", "Owner B")
            val ownerAContext = REMOTE_CONTEXT
            val ownerAActivation = hostActivation(
                installationId = "installation-opaque",
                generation = "1",
                accessToken = ACTIVATION_PAT,
                owner = ownerA,
            )
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, ownerB)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(response(200, EMPTY_OWNER_MONITOR)),
                activationResponses = listOf(
                    response(201, activationFixture("installation-owner-b", "1", NEW_PAT)),
                ),
                installationResponses = listOf(
                    response(200, installationFixture("installation-owner-b", "1")),
                    response(200, installationFixture("installation-owner-b", "1")),
                ),
            )
            val multiOwnerStore = object : RemoteHostSessionStore {
                val loads = AtomicInteger()
                var onLoad: (suspend (Int, RemoteHostOwnerContext) -> Unit)? = null
                private val byOwner = mutableMapOf(
                    ownerAContext to ownerAActivation,
                )
                private val delegate = persistentStore()

                override suspend fun load(
                    expectedContext: RemoteHostOwnerContext,
                ): RemoteHostSessionLoad {
                    val call = loads.incrementAndGet()
                    onLoad?.invoke(call, expectedContext)
                    val stored = byOwner[expectedContext]
                    return if (stored != null) {
                        RemoteHostSessionLoad.ActiveSession(stored)
                    } else {
                        delegate.load(expectedContext)
                    }
                }

                override suspend fun presence(): RemoteSessionPresence = delegate.presence()

                override suspend fun beginAttempt(
                    expectedContext: RemoteHostOwnerContext,
                    expectedGeneration: OwnerGeneration,
                ): RemoteHostSessionWriteResult =
                    delegate.beginAttempt(expectedContext, expectedGeneration)

                override suspend fun beginRecoveryAttempt(
                    expectedContext: RemoteHostOwnerContext,
                    previousAttemptId: RemoteHostAttemptId,
                    expectedGeneration: OwnerGeneration,
                ): RemoteHostSessionWriteResult =
                    delegate.beginRecoveryAttempt(
                        expectedContext,
                        previousAttemptId,
                        expectedGeneration,
                    )

                override suspend fun commitAttempt(
                    expectedContext: RemoteHostOwnerContext,
                    attemptId: RemoteHostAttemptId,
                    activation: HostActivation,
                ): RemoteHostSessionWriteResult {
                    val result = delegate.commitAttempt(expectedContext, attemptId, activation)
                    if (result == RemoteHostSessionWriteResult.ActiveSessionCommitted) {
                        byOwner[expectedContext] = activation
                    }
                    return result
                }

                override suspend fun clearPendingAttempt(
                    expectedContext: RemoteHostOwnerContext,
                    attemptId: RemoteHostAttemptId,
                ): RemoteHostSessionWriteResult =
                    delegate.clearPendingAttempt(expectedContext, attemptId)

                override suspend fun clearActiveSession(
                    expectedContext: ExpectedHostAccessContext,
                ): RemoteHostSessionWriteResult = delegate.clearActiveSession(expectedContext)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, multiOwnerStore, scope)
            val provider: HostAdministrationSessionProvider = coordinator
            val ownerALoadStarted = CompletableDeferred<Unit>()
            val releaseOwnerALoad = CompletableDeferred<Unit>()
            var ownerARevalidation:
                kotlinx.coroutines.Deferred<HostAdministrationSessionResult<Unit>>? = null

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val ownerBLease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    ownerBLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                identity.changeAccount(ownerA)
                multiOwnerStore.onLoad = { _, context ->
                    if (context == ownerAContext) {
                        ownerALoadStarted.complete(Unit)
                        releaseOwnerALoad.await()
                    }
                }
                ownerARevalidation = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.revalidateAdministrationSession()
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    ownerALoadStarted.await()
                }

                identity.changeAccount(ownerB)
                coordinator.initialize()
                assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )

                releaseOwnerALoad.complete(Unit)
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.HOST_NOT_ACTIVE,
                    ),
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { ownerARevalidation.await() },
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
            } finally {
                releaseOwnerALoad.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    ownerARevalidation?.cancelAndJoin()
                }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun freshConfirmedHostActivationClearsPriorAuthorizationRejectionWhileKnownNoWriteAndInitializeDoNot() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(
                    response(200, EMPTY_OWNER_MONITOR),
                    response(200, ownerMonitorFixture("1", "installation-opaque")),
                    response(200, ownerMonitorFixture("1", "installation-opaque")),
                ),
                activationResponses = listOf(
                    response(201, ACTIVATION),
                    response(401, """{"code":"access_unauthenticated"}"""),
                    response(201, activationFixture("host-2", "2", NEW_PAT)),
                ),
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                    response(200, installationFixture("host-2", "2")),
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val host1Lease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    host1Lease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                coordinator.initialize()
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )

                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                    hostReplacementWarningConfirmed = true,
                )
                coordinator.initialize()
                assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )

                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                    hostReplacementWarningConfirmed = true,
                )
                assertEquals(RemoteHostPhase.ACTIVE_CONFIRMED, coordinator.state.value.phase)

                val host2Lease = readyLease(provider.acquireLease())
                val tokenResult = provider.withValidatedLease(host2Lease) { pairingLease ->
                    pairingLease.accessToken.reveal()
                }
                assertEquals(HostAdministrationSessionResult.Ready(NEW_PAT), tokenResult)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun stateObserverReactingImmediatelyToFreshActiveConfirmedHostAcquiresLeaseAtomically() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                ownerResponses = listOf(
                    response(200, EMPTY_OWNER_MONITOR),
                    response(200, ownerMonitorFixture("1", "installation-opaque")),
                ),
                activationResponses = listOf(
                    response(201, ACTIVATION),
                    response(201, activationFixture("host-2", "2", NEW_PAT)),
                ),
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    response(200, installationFixture("host-2", "2")),
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator
            val observerLeaseResult =
                CompletableDeferred<HostAdministrationSessionResult<HostAdministrationLease>>()
            var observerJob: Job? = null

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val host1Lease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    host1Lease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )

                coordinator.discoverOwnerMonitor()
                val host2Confirmation = requireNotNull(coordinator.state.value.confirmation)

                observerJob = scope.launch(Dispatchers.Unconfined) {
                    coordinator.state.collect { viewState ->
                        if (
                            viewState.phase == RemoteHostPhase.ACTIVE_CONFIRMED &&
                            viewState.verifiedHost?.installationId == "host-2" &&
                            !observerLeaseResult.isCompleted
                        ) {
                            observerLeaseResult.complete(provider.acquireLease())
                        }
                    }
                }

                coordinator.confirmHostActivation(
                    host2Confirmation.confirmationId,
                    hostReplacementWarningConfirmed = true,
                )

                val observedLease = readyLease(
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { observerLeaseResult.await() },
                )
                assertEquals(
                    HostAdministrationSessionResult.Ready(NEW_PAT),
                    provider.withValidatedLease(observedLease) { pairingLease ->
                        pairingLease.accessToken.reveal()
                    },
                )
            } finally {
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    observerJob?.cancelAndJoin()
                }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun closedCoordinatorAfterAuthorizationRejectionReturnsLeaseInvalidatedForAllLeaseOperations() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(events)
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val lease = readyLease(provider.acquireLease())
                provider.invalidateLease(
                    lease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.acquireLease(),
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    provider.validateLease(lease),
                )

                coordinator.close()
                var callbackCount = 0

                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    ),
                    provider.validateLease(lease),
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    ),
                    provider.acquireLease(),
                )
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.LEASE_INVALIDATED,
                    ),
                    provider.withValidatedLease(lease) {
                        callbackCount += 1
                        "must-not-run"
                    },
                )
                assertEquals(0, callbackCount)
            } finally {
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun inFlightAuthorizationRejectionDuringValidateLeaseAndWithValidatedLeaseReturnsAuthorizationRejected() =
        runBlocking {
            val events = mutableListOf<String>()
            val identity = RecordingOwnerIdentity(events, OWNER)
            val transport = ScriptedOwnerAccessTransport(
                events = events,
                installationResponses = listOf(
                    response(200, INSTALLATION),
                    response(200, INSTALLATION),
                ),
            )
            val store = RecordingRemoteHostSessionStore(events)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val coordinator = coordinator(identity, transport, store, scope)
            val provider: HostAdministrationSessionProvider = coordinator
            val firstLoadStarted = CompletableDeferred<Unit>()
            val releaseFirstLoad = CompletableDeferred<Unit>()
            val secondLoadStarted = CompletableDeferred<Unit>()
            val releaseSecondLoad = CompletableDeferred<Unit>()
            var validationJob:
                kotlinx.coroutines.Deferred<HostAdministrationSessionResult<Unit>>? = null
            var operationJob:
                kotlinx.coroutines.Deferred<HostAdministrationSessionResult<String>>? = null

            try {
                coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                coordinator.discoverOwnerMonitor()
                coordinator.confirmHostActivation(
                    requireNotNull(coordinator.state.value.confirmation).confirmationId,
                )
                val firstLease = readyLease(provider.acquireLease())

                val firstTargetLoad = store.loadCalls + 1
                store.onLoad = { callNumber ->
                    if (callNumber == firstTargetLoad) {
                        firstLoadStarted.complete(Unit)
                        releaseFirstLoad.await()
                    }
                }
                validationJob = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.validateLease(firstLease)
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    firstLoadStarted.await()
                }
                provider.invalidateLease(
                    firstLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )
                releaseFirstLoad.complete(Unit)
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { validationJob.await() },
                )

                store.onLoad = null
                assertEquals(
                    HostAdministrationSessionResult.Ready(Unit),
                    provider.revalidateAdministrationSession(),
                )
                val secondLease = readyLease(provider.acquireLease())
                val secondTargetLoad = store.loadCalls + 1
                var callbackCount = 0
                store.onLoad = { callNumber ->
                    if (callNumber == secondTargetLoad) {
                        secondLoadStarted.complete(Unit)
                        releaseSecondLoad.await()
                    }
                }
                operationJob = async(start = CoroutineStart.UNDISPATCHED) {
                    provider.withValidatedLease(secondLease) {
                        callbackCount += 1
                        "must-not-run"
                    }
                }
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    secondLoadStarted.await()
                }
                provider.invalidateLease(
                    secondLease,
                    HostAdministrationSessionInvalidation.ADMIN_AUTHORIZATION_REJECTED,
                )
                releaseSecondLoad.complete(Unit)
                assertEquals(
                    HostAdministrationSessionResult.Rejected(
                        HostAdministrationSessionFailure.AUTHORIZATION_REJECTED,
                    ),
                    withTimeout(BARRIER_TIMEOUT_MILLIS) { operationJob.await() },
                )
                assertEquals(0, callbackCount)
            } finally {
                releaseFirstLoad.complete(Unit)
                releaseSecondLoad.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    validationJob?.cancelAndJoin()
                    operationJob?.cancelAndJoin()
                }
                coordinator.close()
                scope.cancel()
            }
        }

    @Test
    fun withValidatedLeaseRejectsBeforeOperationWhenAccountOriginOrCoordinatorChangesInFlight() =
        runBlocking {
            listOf("account", "origin", "close").forEach { mutation ->
                val events = mutableListOf<String>()
                val identity = RecordingOwnerIdentity(events, OWNER)
                val transport = ScriptedOwnerAccessTransport(events)
                val store = RecordingRemoteHostSessionStore(events)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                val coordinator = coordinator(identity, transport, store, scope)
                val provider: HostAdministrationSessionProvider = coordinator
                val loadStarted = CompletableDeferred<Unit>()
                val releaseLoad = CompletableDeferred<Unit>()
                var operationJob:
                    kotlinx.coroutines.Deferred<HostAdministrationSessionResult<String>>? = null

                try {
                    coordinator.signIn(SecretValue.of("synthetic-google-id-token"))
                    coordinator.discoverOwnerMonitor()
                    coordinator.confirmHostActivation(
                        requireNotNull(coordinator.state.value.confirmation).confirmationId,
                    )
                    val lease = readyLease(provider.acquireLease())
                    val targetLoad = store.loadCalls + 1
                    var callbackCount = 0
                    store.onLoad = { callNumber ->
                        if (callNumber == targetLoad) {
                            loadStarted.complete(Unit)
                            releaseLoad.await()
                        }
                    }

                    operationJob = async(start = CoroutineStart.UNDISPATCHED) {
                        provider.withValidatedLease(lease) {
                            callbackCount += 1
                            "must-not-run"
                        }
                    }
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        loadStarted.await()
                    }

                    when (mutation) {
                        "account" -> identity.changeAccount(
                            OwnerAccount("other-project", "other-uid", null),
                        )

                        "origin" -> transport.origin = "https://other.example.test"
                        "close" -> coordinator.close()
                    }
                    releaseLoad.complete(Unit)

                    assertEquals(
                        "Expected LEASE_INVALIDATED for in-flight $mutation change",
                        HostAdministrationSessionResult.Rejected(
                            HostAdministrationSessionFailure.LEASE_INVALIDATED,
                        ),
                        withTimeout(BARRIER_TIMEOUT_MILLIS) { operationJob.await() },
                    )
                    assertEquals(0, callbackCount)
                } finally {
                    releaseLoad.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        operationJob?.cancelAndJoin()
                    }
                    coordinator.close()
                    scope.cancel()
                }
            }
        }

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
            roleGuard = testRoleGuard(store),
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
        val store = RecordingRemoteHostSessionStore(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = RemoteHostCoordinator(
            identity = RecordingOwnerIdentity(events, OWNER),
            ownerAccessClient = OwnerAccessClient(transport),
            sessionStore = store,
            roleGuard = testRoleGuard(store),
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

    private fun readyLease(
        result: HostAdministrationSessionResult<HostAdministrationLease>,
    ): HostAdministrationLease =
        (result as? HostAdministrationSessionResult.Ready)?.value
            ?: throw AssertionError("Expected an administration lease, got $result")

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
        val delegate: RemoteHostSessionStore = persistentStore(),
        private val beforeCommit: suspend () -> Unit = {},
    ) : RemoteHostSessionStore {
        var loadOverride: RemoteHostSessionLoad? = null
        var loadCalls = 0
            private set
        var onLoad: (suspend (Int) -> Unit)? = null
        var beginAttemptCalls = 0
            private set
        var beginRecoveryAttemptCalls = 0
            private set

        override suspend fun load(expectedContext: RemoteHostOwnerContext): RemoteHostSessionLoad {
            val callNumber = ++loadCalls
            onLoad?.invoke(callNumber)
            return loadOverride ?: delegate.load(expectedContext)
        }

        override suspend fun presence(): RemoteSessionPresence = delegate.presence()

        override suspend fun beginAttempt(
            expectedContext: RemoteHostOwnerContext,
            expectedGeneration: OwnerGeneration,
        ): RemoteHostSessionWriteResult {
            beginAttemptCalls += 1
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
            beginRecoveryAttemptCalls += 1
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
        @Volatile
        var record: ByteArray? = null
        @Volatile
        var nextReadFailure: IOException? = null
        var writeCount = 0
            private set
        var deleteCount = 0
            private set
        var failOnWrite: Int? = null
        var failureOnWrite: RuntimeException? = null
        var blockOnWrite: Int? = null
        var blockedWriteStarted: CompletableDeferred<Unit>? = null
        var releaseBlockedWrite: CountDownLatch? = null

        override fun readRecord(maximumBytes: Int): ByteArray? {
            nextReadFailure?.let { failure ->
                throw failure
            }
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
        roleGuard: RemoteRoleGuard = testRoleGuard(store),
    ): RemoteHostCoordinator {
        val confirmationSequence = AtomicInteger()
        return RemoteHostCoordinator(
            identity = identity,
            ownerAccessClient = OwnerAccessClient(transport),
            sessionStore = store,
            roleGuard = roleGuard,
            parentScope = scope,
            confirmationId = {
                "confirmation-${confirmationSequence.incrementAndGet()}"
            },
        )
    }

    private fun testRoleGuard(store: RemoteHostSessionStore): RemoteRoleGuard =
        RemoteRoleGuard(
            hostPresence = { store.presence() },
            supportPresence = { RemoteSessionPresence.Absent },
        )

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private suspend fun seedSupportState(
        supportState: String,
        supportStore: PersistentRemoteSupportSessionStore,
        supportFile: MemoryAtomicRemoteHostFile,
        supportKeys: JceTestKeyProvider,
    ) {
        when (supportState) {
            "attempt" -> {
                assertTrue(
                    supportStore.beginAttempt(ORIGIN) is
                        RemoteSupportSessionWriteResult.AttemptStarted,
                )
            }

            "pending" -> {
                val attempt = (
                    supportStore.beginAttempt(ORIGIN)
                        as RemoteSupportSessionWriteResult.AttemptStarted
                    ).record
                assertTrue(
                    supportStore.commitPending(
                        origin = ORIGIN,
                        attemptId = attempt.attemptId,
                        candidate = PairingCandidateInstallation(
                            origin = ORIGIN,
                            requestId = "11111111-1111-4111-8111-111111111111",
                            installationId = "22222222-2222-4222-8222-222222222222",
                            requestExpiresAtMillis = 1_790_000_000_000L,
                            accessToken = SecretValue.of("synthetic-support-pat"),
                            status = "Pending",
                        ),
                    ) is RemoteSupportSessionWriteResult.Committed,
                )
            }

            "approved" -> {
                val attempt = (
                    supportStore.beginAttempt(ORIGIN)
                        as RemoteSupportSessionWriteResult.AttemptStarted
                    ).record
                val pending = (
                    supportStore.commitPending(
                        origin = ORIGIN,
                        attemptId = attempt.attemptId,
                        candidate = PairingCandidateInstallation(
                            origin = ORIGIN,
                            requestId = "11111111-1111-4111-8111-111111111111",
                            installationId = "22222222-2222-4222-8222-222222222222",
                            requestExpiresAtMillis = 1_790_000_000_000L,
                            accessToken = SecretValue.of("synthetic-support-pat"),
                            status = "Pending",
                        ),
                    ) as RemoteSupportSessionWriteResult.Committed
                    ).record
                assertTrue(
                    supportStore.commitApproved(
                        expectedRecord = pending,
                        supportInstallation = PairingSupportInstallation(
                            installationId = "22222222-2222-4222-8222-222222222222",
                            monitorId = "44444444-4444-4444-8444-444444444444",
                            membershipId = "55555555-5555-4555-8555-555555555555",
                            role = "Support",
                            status = "Active",
                            monitorGeneration = generation("1"),
                        ),
                    ) is RemoteSupportSessionWriteResult.Committed,
                )
            }

            "corrupt" -> {
                supportFile.record = byteArrayOf(0x09, 0x08, 0x07, 0x06)
            }

            "key-unavailable" -> {
                seedSupportState("approved", supportStore, supportFile, supportKeys)
                supportKeys.keyAvailable = false
                assertEquals(
                    RemoteSupportSessionLoad.Unavailable(
                        RemoteSupportSessionProblem.KEY_UNAVAILABLE,
                    ),
                    supportStore.load(ORIGIN),
                )
            }

            else -> error("Unsupported synthetic support state: $supportState")
        }
    }

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
        const val BARRIER_TIMEOUT_MILLIS = 5_000L
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
        const val ACTIVATION_PAT = "synthetic-one-time-pat"
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
            monitorId: String = "monitor-opaque",
            membershipId: String = "membership-opaque",
            owner: OwnerAccount = OWNER,
            origin: String = ORIGIN,
        ): HostActivation =
            HostActivation(
                monitorId = monitorId,
                installationId = installationId,
                membershipId = membershipId,
                generation = requireNotNull(OwnerGeneration.parse(generation)),
                accessToken = SecretValue.of(accessToken),
                createdAtMillis = 1_790_000_000_000L,
                owner = owner,
                origin = origin,
            )
    }
}
