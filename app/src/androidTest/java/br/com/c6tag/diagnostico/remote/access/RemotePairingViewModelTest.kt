package br.com.c6tag.diagnostico.remote.access

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.CollectorApplication
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemotePairingViewModelTest {
    @Test
    fun attachDetachReopenAndStaleDetachPreserveActiveLease(): Unit = runBlocking {
        Fixture().use { f ->
            f.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.RedeemInvitation ->
                        pairingResponse(201, redemptionJson())

                    else -> error("Unexpected operation: $operation")
                }
            }

            val lease1 = f.main { f.vm.attachSurface() }
            f.await { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

            val lease2 = f.main { f.vm.attachSurface() }
            assertTrue(lease2 > lease1)
            f.await { it.roleMode == RemoteAccessRoleMode.UNASSIGNED }

            // Stale detach for lease1 must not close lease2.
            f.main { f.vm.detachSurface(lease1) }
            f.main { f.vm.submitSupportInvitation(lease1, VALID_INVITATION_SECRET, "Stale") }
            assertEquals(0, f.pairingTransport.totalCalls)

            f.main { f.vm.submitSupportInvitation(lease2, VALID_INVITATION_SECRET, "Tablet") }
            val state = f.await {
                it.support.phase == SupportPairingPhase.PENDING &&
                    it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    !it.supportActionInProgress
            }
            assertEquals(REQUEST_ID_1, state.support.pending?.requestId)
            assertFalse(state.canActivateHost)
        }
    }

    @Test
    fun onClearedClosesAllCoordinatorsAndCancelsInFlightWork(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        Fixture().use { f ->
            try {
                f.seedPendingSupport()
                f.pairingTransport.handler = { operation, _, _ ->
                    when (operation) {
                        PairingAccessOperation.CurrentRequest -> {
                            entered.complete(Unit)
                            try {
                                hold.await()
                            } catch (cancellation: CancellationException) {
                                cancelled.complete(Unit)
                                throw cancellation
                            }
                            pairingResponse(200, pendingCurrentRequestJson())
                        }

                        else -> error("Unexpected operation: $operation")
                    }
                }

                f.main { f.vm.attachSurface() }
                withTimeout(BARRIER_TIMEOUT_MILLIS) { entered.await() }

                f.clearOwner()
                withTimeout(BARRIER_TIMEOUT_MILLIS) { cancelled.await() }
            } finally {
                hold.complete(Unit)
            }
        }
    }

    @Test
    fun supportPresentRestoresWithoutGoogleAccountAndNeverExposesPatInUiState(): Unit = runBlocking {
        Fixture(initialAccount = null).use { f ->
            f.seedApprovedSupport()
            f.pairingTransport.handler = { operation, _, _ ->
                when (operation) {
                    PairingAccessOperation.ValidateSupportInstallation ->
                        pairingResponse(200, supportInstallationJson("2"))

                    else -> error("Unexpected operation: $operation")
                }
            }

            f.main { f.vm.attachSurface() }
            val state = f.await {
                it.roleMode == RemoteAccessRoleMode.SUPPORT_PRESENT &&
                    it.support.phase == SupportPairingPhase.CONFIRMED
            }

            assertNull(state.account)
            assertTrue(state.canEnterSupportFlow)
            assertFalse(state.canActivateHost)
            assertEquals(0, f.ownerTransport.totalCalls)
            assertFalse(state.toString().contains(SUPPORT_PAT))
        }
    }

    @Test
    fun hostSupportsExplicitCopyIsBoundToActiveLeaseAndNeverLeaksSecrets(): Unit = runBlocking {
        Fixture(initialAccount = OWNER).use { f ->
            f.seedActiveHost()
            f.ownerTransport.installationResponse = {
                ownerResponse(200, hostInstallationJson())
            }
            f.pairingTransport.handler = { operation, _, _ ->
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

            val lease = f.main { f.vm.attachSurface() }
            f.await { it.canManageSupports }

            f.main { f.vm.openHostSupports(lease) }
            f.await {
                it.hostSupports.phase == HostSupportPhase.READY && !it.hostSupportActionInProgress
            }

            f.main { f.vm.issueSupportInvitation(lease) }
            val emitted = f.await {
                it.hostSupports.emittedInvitation?.copyAvailable == true &&
                    !it.hostSupportActionInProgress
            }

            assertFalse(emitted.toString().contains(HOST_PAT))
            assertFalse(emitted.toString().contains(VALID_INVITATION_SECRET))

            val copied = f.vm.invitationForExplicitCopy(lease, INVITATION_ID_1)
            assertNotNull(copied)
            assertEquals(VALID_INVITATION_SECRET, requireNotNull(copied).reveal())

            f.main { f.vm.detachSurface(lease) }
            assertNull(f.vm.invitationForExplicitCopy(lease, INVITATION_ID_1))
        }
    }

    @Test
    fun applicationSharesSingleLazyStoresGuardAndPairingClient() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as CollectorApplication
        assertSame(app.remoteHostSessionStore, app.remoteHostSessionStore)
        assertSame(app.remoteSupportSessionStore, app.remoteSupportSessionStore)
        assertSame(app.remoteRoleGuard, app.remoteRoleGuard)
        assertSame(app.pairingAccessClient, app.pairingAccessClient)
    }

    private class Fixture(
        initialAccount: OwnerAccount? = null,
    ) : AutoCloseable {
        val identity = FakeOwnerIdentity(initialAccount)
        val ownerTransport = ScriptedOwnerTransport(ORIGIN)
        val ownerAccessClient = OwnerAccessClient(ownerTransport)
        val pairingTransport = ScriptedPairingTransport(ORIGIN)
        val pairingAccessClient = PairingAccessClient(pairingTransport)
        val hostFile = MemoryAtomicRemoteHostFile()
        val supportFile = MemoryAtomicRemoteHostFile()
        val hostStore = PersistentRemoteHostSessionStore(
            hostFile,
            JceTestKeyProvider(),
            Dispatchers.IO,
        )
        val supportStore = PersistentRemoteSupportSessionStore(
            supportFile,
            JceTestKeyProvider(),
            Dispatchers.IO,
        )
        val roleGuard = RemoteRoleGuard(
            hostPresence = { hostStore.presence() },
            supportPresence = { supportStore.presence() },
        )
        private val owner = ViewModelStore()
        lateinit var vm: RemoteAccessViewModel
            private set

        init {
            attachViewModel()
        }

        private fun attachViewModel() {
            var created: RemoteAccessViewModel? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                created = ViewModelProvider(
                    owner,
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
                            ) as T
                    },
                )[RemoteAccessViewModel::class.java]
            }
            vm = requireNotNull(created)
        }

        fun clearOwner() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
        }

        suspend fun <T> main(action: () -> T): T = withContext(Dispatchers.Main) { action() }

        suspend fun await(predicate: (RemoteAccessUiState) -> Boolean): RemoteAccessUiState =
            withTimeout(BARRIER_TIMEOUT_MILLIS) {
                vm.uiState.first(predicate)
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
            clearOwner()
        }
    }

    private class FakeOwnerIdentity(
        initialAccount: OwnerAccount?,
    ) : OwnerIdentity {
        private val mutableAccount = MutableStateFlow(initialAccount)
        override val account: StateFlow<OwnerAccount?> = mutableAccount

        override suspend fun signIn(googleIdToken: SecretValue): IdentityResult {
            mutableAccount.value = OWNER
            return IdentityResult(IdentityOutcome.SIGNED_IN_CANDIDATE)
        }

        override suspend fun reauthenticate(googleIdToken: SecretValue): IdentityResult =
            IdentityResult(IdentityOutcome.REAUTHENTICATED)

        override suspend fun firebaseIdToken(): SecretValue =
            SecretValue.of("synthetic-firebase-id-token")

        override suspend fun signOut(): IdentityResult {
            mutableAccount.value = null
            return IdentityResult(IdentityOutcome.SIGNED_OUT)
        }
    }

    private class ScriptedOwnerTransport(
        override val origin: String?,
    ) : OwnerAccessTransport {
        private val callCount = AtomicInteger(0)
        val totalCalls: Int
            get() = callCount.get()
        var installationResponse: suspend () -> OwnerAccessTransportResult = {
            ownerResponse(200, hostInstallationJson())
        }

        override suspend fun execute(
            method: OwnerAccessMethod,
            path: OwnerAccessPath,
            credential: OwnerAccessCredential,
            body: String?,
        ): OwnerAccessTransportResult {
            callCount.incrementAndGet()
            return when (path) {
                OwnerAccessPath.INSTALLATION -> installationResponse()
                else -> ownerResponse(
                    200,
                    """{"data":{"monitor":null,"generation":"0","host":null,"requiresReference":true}}""",
                )
            }
        }
    }

    private class ScriptedPairingTransport(
        override val origin: String?,
        var handler: (suspend (PairingAccessOperation, PairingAccessCredential?, PairingAccessRequestBody?) -> PairingAccessTransportResult)? = null,
    ) : PairingAccessTransport {
        private val callCount = AtomicInteger(0)
        val totalCalls: Int
            get() = callCount.get()

        override suspend fun execute(
            operation: PairingAccessOperation,
            credential: PairingAccessCredential?,
            body: PairingAccessRequestBody?,
        ): PairingAccessTransportResult {
            callCount.incrementAndGet()
            val currentHandler = handler ?: error("Unexpected pairing network call: $operation")
            return currentHandler(operation, credential, body)
        }
    }

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var failReads = false
        private var stored: ByteArray? = null

        override fun readRecord(maximumBytes: Int): ByteArray? {
            if (failReads) {
                throw IOException("Synthetic read failure")
            }
            val current = stored ?: return null
            return current.copyOf(minOf(current.size, maximumBytes))
        }

        override fun writeAtomically(record: ByteArray) {
            stored = record.copyOf()
        }

        override fun deleteRecord() {
            stored = null
        }
    }

    private class JceTestKeyProvider : RemoteHostKeyProvider {
        private var key: SecretKey? = null

        override fun existingKey(): SecretKey? = key

        override fun keyForNewRecord(): SecretKey =
            key ?: SecretKeySpec(ByteArray(32) { index -> (index + 23).toByte() }, "AES").also {
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
