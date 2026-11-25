package br.com.c6tag.diagnostico.remote.access

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRoleGuardTest {
    @Test
    fun hostReservationIsBlockedByAnySupportRecordWithoutStartingJournal() = runBlocking {
        var journalBegins = 0
        val guard = guard(
            host = RemoteSessionPresence.Absent,
            support = RemoteSessionPresence.Present,
        )

        val result = guard.beginHostAttempt {
            journalBegins += 1
            "host journal"
        }

        assertEquals(
            RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
            result,
        )
        assertEquals(0, journalBegins)
    }

    @Test
    fun supportReservationIsBlockedByAnyHostRecordWithoutStartingJournal() = runBlocking {
        var journalBegins = 0
        val guard = guard(
            host = RemoteSessionPresence.Present,
            support = RemoteSessionPresence.Absent,
        )

        val result = guard.beginSupportAttempt {
            journalBegins += 1
            "support journal"
        }

        assertEquals(
            RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
            result,
        )
        assertEquals(0, journalBegins)
    }

    @Test
    fun unknownOppositePresenceBlocksBothRolesWithoutStartingEitherJournal() = runBlocking {
        var hostJournalBegins = 0
        var supportJournalBegins = 0
        val unavailable = RemoteSessionPresence.Unavailable(
            RemoteSupportSessionProblem.PERSISTENCE_FAILURE,
        )
        val guard = guard(host = unavailable, support = unavailable)

        val hostResult = guard.beginHostAttempt {
            hostJournalBegins += 1
            Unit
        }
        val supportResult = guard.beginSupportAttempt {
            supportJournalBegins += 1
            Unit
        }

        assertEquals(
            RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.PRESENCE_UNAVAILABLE),
            hostResult,
        )
        assertEquals(
            RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.PRESENCE_UNAVAILABLE),
            supportResult,
        )
        assertEquals(0, hostJournalBegins)
        assertEquals(0, supportJournalBegins)
    }

    @Test
    fun unexpectedPresenceProviderFailurePropagatesWithoutStartingJournal() = runBlocking {
        var journalBegins = 0
        val failure = IllegalStateException("synthetic presence programming failure")
        val guard = RemoteRoleGuard(
            hostPresence = { RemoteSessionPresence.Absent },
            supportPresence = { throw failure },
        )

        val observedFailure = runCatching {
            guard.beginHostAttempt {
                journalBegins += 1
                Unit
            }
        }.exceptionOrNull()

        assertTrue(observedFailure === failure)
        assertEquals(0, journalBegins)
    }

    @Test
    fun journalBeginFailureAndCancellationPropagateAndReleaseTheReservationMutex() = runBlocking {
        val guard = guard(
            host = RemoteSessionPresence.Absent,
            support = RemoteSessionPresence.Absent,
        )
        val failure = IllegalStateException("synthetic journal failure")
        val journalFailure = runCatching {
            guard.beginHostAttempt<Unit> { throw failure }
        }.exceptionOrNull()
        assertTrue(journalFailure === failure)

        val cancellation = CancellationException("synthetic journal cancellation")
        val cancelledAttempt = async {
            guard.beginHostAttempt<Unit> { throw cancellation }
        }
        val observedCancellation = runCatching { cancelledAttempt.await() }.exceptionOrNull()
        assertTrue(observedCancellation is CancellationException)
        assertEquals(cancellation.message, observedCancellation?.message)
        assertEquals(
            RemoteRoleGuardResult.Begun("journal-after-failure"),
            guard.beginHostAttempt { "journal-after-failure" },
        )
    }

    @Test
    fun eachRoleCanReserveWhenOppositePresenceIsAbsentAndHostCanReplaceItsOwnActiveRecord() =
        runBlocking {
            val hostBegins = AtomicInteger()
            val supportBegins = AtomicInteger()
            val guard = guard(
                host = RemoteSessionPresence.Present,
                support = RemoteSessionPresence.Absent,
            )

            val hostResult = guard.beginHostAttempt {
                hostBegins.incrementAndGet()
            }

            assertEquals(RemoteRoleGuardResult.Begun(1), hostResult)
            assertEquals(1, hostBegins.get())

            val supportGuard = guard(
                host = RemoteSessionPresence.Absent,
                support = RemoteSessionPresence.Present,
            )
            val supportResult = supportGuard.beginSupportAttempt {
                supportBegins.incrementAndGet()
            }

            assertEquals(RemoteRoleGuardResult.Begun(1), supportResult)
            assertEquals(1, supportBegins.get())
        }

    @Test
    fun hostAndSupportReservationsSerializePresenceCheckWithJournalBeginInEitherOrder() =
        runBlocking {
            listOf(true, false).forEach { hostStartsFirst ->
                val hostPresence = AtomicReference<RemoteSessionPresence>(RemoteSessionPresence.Absent)
                val supportPresence = AtomicReference<RemoteSessionPresence>(RemoteSessionPresence.Absent)
                val hostJournalBegins = AtomicInteger()
                val supportJournalBegins = AtomicInteger()
                val mutationRequests = AtomicInteger()
                val hostPresenceReads = AtomicInteger()
                val supportPresenceReads = AtomicInteger()
                val firstPresenceRead = CompletableDeferred<Unit>()
                val releaseFirstPresenceRead = CompletableDeferred<Unit>()
                val firstJournalBegin = CompletableDeferred<Unit>()
                val releaseFirstJournalBegin = CompletableDeferred<Unit>()
                val guard = RemoteRoleGuard(
                    hostPresence = {
                        hostPresenceReads.incrementAndGet()
                        if (!hostStartsFirst) {
                            firstPresenceRead.complete(Unit)
                            releaseFirstPresenceRead.await()
                        }
                        hostPresence.get()
                    },
                    supportPresence = {
                        supportPresenceReads.incrementAndGet()
                        if (hostStartsFirst) {
                            firstPresenceRead.complete(Unit)
                            releaseFirstPresenceRead.await()
                        }
                        supportPresence.get()
                    },
                )

                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    val result = if (hostStartsFirst) {
                        guard.beginHostAttempt {
                            hostJournalBegins.incrementAndGet()
                            hostPresence.set(RemoteSessionPresence.Present)
                            firstJournalBegin.complete(Unit)
                            releaseFirstJournalBegin.await()
                            "host"
                        }
                    } else {
                        guard.beginSupportAttempt {
                            supportJournalBegins.incrementAndGet()
                            supportPresence.set(RemoteSessionPresence.Present)
                            firstJournalBegin.complete(Unit)
                            releaseFirstJournalBegin.await()
                            "support"
                        }
                    }
                    if (result is RemoteRoleGuardResult.Begun) {
                        mutationRequests.incrementAndGet()
                    }
                    result
                }
                var second: Deferred<RemoteRoleGuardResult<String>>? = null

                try {
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        firstPresenceRead.await()
                    }
                    second = async(start = CoroutineStart.UNDISPATCHED) {
                        val result = if (hostStartsFirst) {
                            guard.beginSupportAttempt {
                                supportJournalBegins.incrementAndGet()
                                supportPresence.set(RemoteSessionPresence.Present)
                                "support"
                            }
                        } else {
                            guard.beginHostAttempt {
                                hostJournalBegins.incrementAndGet()
                                hostPresence.set(RemoteSessionPresence.Present)
                                "host"
                            }
                        }
                        if (result is RemoteRoleGuardResult.Begun) {
                            mutationRequests.incrementAndGet()
                        }
                        result
                    }
                    val secondPresenceReadCount = if (hostStartsFirst) {
                        hostPresenceReads
                    } else {
                        supportPresenceReads
                    }
                    assertEquals(0, secondPresenceReadCount.get())

                    releaseFirstPresenceRead.complete(Unit)
                    val firstOutcome = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        select {
                            firstJournalBegin.onAwait { "journal-begin" }
                            first.onAwait { "reservation-completed" }
                        }
                    }
                    if (firstOutcome == "reservation-completed") {
                        assertTrue(
                            "Absent opposite presence must permit the first journal begin",
                            first.await() is RemoteRoleGuardResult.Begun,
                        )
                        assertEquals(1, hostJournalBegins.get() + supportJournalBegins.get())
                        assertEquals(1, mutationRequests.get())
                        return@forEach
                    }

                    assertEquals(0, secondPresenceReadCount.get())
                    releaseFirstJournalBegin.complete(Unit)
                    val firstResult = withTimeout(BARRIER_TIMEOUT_MILLIS) { first.await() }
                    val secondResult = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        requireNotNull(second).await()
                    }

                    assertTrue(firstResult is RemoteRoleGuardResult.Begun)
                    assertEquals(
                        RemoteRoleGuardResult.Blocked(
                            RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT,
                        ),
                        secondResult,
                    )
                    assertEquals(1, hostJournalBegins.get() + supportJournalBegins.get())
                    assertTrue(mutationRequests.get() <= 1)
                } finally {
                    releaseFirstPresenceRead.complete(Unit)
                    releaseFirstJournalBegin.complete(Unit)
                    withTimeout(BARRIER_TIMEOUT_MILLIS) {
                        first.cancelAndJoin()
                        second?.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun realHostStoreStatesBlockSupportAttemptWithoutModifyingHostRecord() = runBlocking {
        listOf(
            "active",
            "pending",
            "corrupt",
            "key-unavailable",
            "signed-out",
        ).forEach { hostState ->
            val hostFile = MemoryAtomicRemoteHostFile()
            val hostKeys = JceTestKeyProvider()
            val hostStore = PersistentRemoteHostSessionStore(
                file = hostFile,
                keys = hostKeys,
                ioDispatcher = Dispatchers.Unconfined,
            )
            val supportFile = MemoryAtomicRemoteHostFile()
            val supportKeys = JceTestKeyProvider()
            val supportStore = PersistentRemoteSupportSessionStore(
                file = supportFile,
                keys = supportKeys,
                ioDispatcher = Dispatchers.Unconfined,
            )

            when (hostState) {
                "active" -> seedActiveHost(hostStore)
                "pending" -> {
                    val pending = hostStore.beginAttempt(HOST_CONTEXT, generation("0"))
                    assertTrue(pending is RemoteHostSessionWriteResult.PendingAttemptStarted)
                }

                "corrupt" -> {
                    hostFile.record = byteArrayOf(0x01, 0x02, 0x03, 0x04)
                }

                "key-unavailable" -> {
                    seedActiveHost(hostStore)
                    hostKeys.keyAvailable = false
                    assertEquals(
                        RemoteHostSessionLoad.Unavailable(RemoteHostSessionProblem.KEY_UNAVAILABLE),
                        hostStore.load(HOST_CONTEXT),
                    )
                }

                "signed-out" -> {
                    seedActiveHost(hostStore)
                }
            }

            val signedInOwner: OwnerAccount? = if (hostState == "signed-out") null else OWNER
            val originalHostBytes = hostFile.snapshot()
            val hostWritesBefore = hostFile.writeCount
            val hostDeletesBefore = hostFile.deleteCount
            val hostKeyReadsBefore = hostKeys.existingKeyReads
            var supportJournalBegins = 0
            val guard = RemoteRoleGuard(
                hostPresence = { hostStore.presence() },
                supportPresence = { supportStore.presence() },
            )

            if (hostState == "signed-out") {
                assertNull(signedInOwner)
            }
            assertEquals(RemoteSessionPresence.Present, hostStore.presence())
            val result = guard.beginSupportAttempt {
                supportJournalBegins += 1
                supportStore.beginAttempt(ORIGIN)
            }

            assertEquals(
                "Expected host state '$hostState' to block support attempt",
                RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                result,
            )
            assertEquals(0, supportJournalBegins)
            assertNull(supportFile.record)
            assertEquals(0, supportFile.writeCount)
            assertEquals(0, supportFile.deleteCount)
            assertEquals(originalHostBytes.toList(), hostFile.snapshot().toList())
            assertEquals(hostWritesBefore, hostFile.writeCount)
            assertEquals(hostDeletesBefore, hostFile.deleteCount)
            assertEquals(hostKeyReadsBefore, hostKeys.existingKeyReads)
        }
    }

    @Test
    fun realSupportStoreStatesBlockHostAttemptWithoutModifyingSupportRecord() = runBlocking {
        listOf(
            "attempt",
            "pending",
            "approved",
            "corrupt",
            "key-unavailable",
        ).forEach { supportState ->
            val hostFile = MemoryAtomicRemoteHostFile()
            val hostKeys = JceTestKeyProvider()
            val hostStore = PersistentRemoteHostSessionStore(
                file = hostFile,
                keys = hostKeys,
                ioDispatcher = Dispatchers.Unconfined,
            )
            val supportFile = MemoryAtomicRemoteHostFile()
            val supportKeys = JceTestKeyProvider()
            val supportStore = PersistentRemoteSupportSessionStore(
                file = supportFile,
                keys = supportKeys,
                ioDispatcher = Dispatchers.Unconfined,
            )

            when (supportState) {
                "attempt" -> seedSupportAttempt(supportStore)
                "pending" -> seedSupportPending(supportStore)
                "approved" -> seedSupportApproved(supportStore)
                "corrupt" -> {
                    supportFile.record = byteArrayOf(0x09, 0x08, 0x07, 0x06)
                }

                "key-unavailable" -> {
                    seedSupportApproved(supportStore)
                    supportKeys.keyAvailable = false
                    assertEquals(
                        RemoteSupportSessionLoad.Unavailable(
                            RemoteSupportSessionProblem.KEY_UNAVAILABLE,
                        ),
                        supportStore.load(ORIGIN),
                    )
                }
            }

            val originalSupportBytes = supportFile.snapshot()
            val supportWritesBefore = supportFile.writeCount
            val supportDeletesBefore = supportFile.deleteCount
            val supportKeyReadsBefore = supportKeys.existingKeyReads
            var hostJournalBegins = 0
            val guard = RemoteRoleGuard(
                hostPresence = { hostStore.presence() },
                supportPresence = { supportStore.presence() },
            )

            assertEquals(RemoteSessionPresence.Present, supportStore.presence())
            val result = guard.beginHostAttempt {
                hostJournalBegins += 1
                hostStore.beginAttempt(HOST_CONTEXT, generation("0"))
            }

            assertEquals(
                "Expected support state '$supportState' to block host attempt",
                RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                result,
            )
            assertEquals(0, hostJournalBegins)
            assertNull(hostFile.record)
            assertEquals(0, hostFile.writeCount)
            assertEquals(0, hostFile.deleteCount)
            assertEquals(originalSupportBytes.toList(), supportFile.snapshot().toList())
            assertEquals(supportWritesBefore, supportFile.writeCount)
            assertEquals(supportDeletesBefore, supportFile.deleteCount)
            assertEquals(supportKeyReadsBefore, supportKeys.existingKeyReads)
        }
    }

    @Test
    fun externallyPresentHostAndSupportRecordsBlockBothRolesWithoutDeletingEitherRecord() =
        runBlocking {
            val hostFile = MemoryAtomicRemoteHostFile()
            val hostStore = PersistentRemoteHostSessionStore(
                file = hostFile,
                keys = JceTestKeyProvider(),
                ioDispatcher = Dispatchers.Unconfined,
            )
            val supportFile = MemoryAtomicRemoteHostFile()
            val supportStore = PersistentRemoteSupportSessionStore(
                file = supportFile,
                keys = JceTestKeyProvider(),
                ioDispatcher = Dispatchers.Unconfined,
            )
            seedActiveHost(hostStore)
            seedSupportApproved(supportStore)
            val originalHostBytes = hostFile.snapshot()
            val originalSupportBytes = supportFile.snapshot()
            val hostWritesBefore = hostFile.writeCount
            val supportWritesBefore = supportFile.writeCount
            var hostJournalBegins = 0
            var supportJournalBegins = 0
            val guard = RemoteRoleGuard(
                hostPresence = { hostStore.presence() },
                supportPresence = { supportStore.presence() },
            )

            val hostResult = guard.beginHostAttempt {
                hostJournalBegins += 1
                hostStore.beginAttempt(HOST_CONTEXT, generation("1"))
            }
            val supportResult = guard.beginSupportAttempt {
                supportJournalBegins += 1
                supportStore.beginAttempt(ORIGIN)
            }

            assertEquals(
                RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                hostResult,
            )
            assertEquals(
                RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                supportResult,
            )
            assertEquals(0, hostJournalBegins)
            assertEquals(0, supportJournalBegins)
            assertEquals(originalHostBytes.toList(), hostFile.snapshot().toList())
            assertEquals(originalSupportBytes.toList(), supportFile.snapshot().toList())
            assertEquals(hostWritesBefore, hostFile.writeCount)
            assertEquals(supportWritesBefore, supportFile.writeCount)
            assertEquals(0, hostFile.deleteCount)
            assertEquals(0, supportFile.deleteCount)
        }

    @Test
    fun reservationMutexIsReleasedBeforeSubsequentNetworkCallRuns() = runBlocking {
        listOf(true, false).forEach { hostStartsFirst ->
            val hostPresence = AtomicReference<RemoteSessionPresence>(RemoteSessionPresence.Absent)
            val supportPresence = AtomicReference<RemoteSessionPresence>(RemoteSessionPresence.Absent)
            val networkCallStarted = CompletableDeferred<Unit>()
            val releaseNetworkCall = CompletableDeferred<Unit>()
            val secondJournalBegins = AtomicInteger()
            val guard = RemoteRoleGuard(
                hostPresence = { hostPresence.get() },
                supportPresence = { supportPresence.get() },
            )

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                val begun = if (hostStartsFirst) {
                    guard.beginHostAttempt {
                        hostPresence.set(RemoteSessionPresence.Present)
                        "host-journal"
                    }
                } else {
                    guard.beginSupportAttempt {
                        supportPresence.set(RemoteSessionPresence.Present)
                        "support-journal"
                    }
                }
                assertTrue(begun is RemoteRoleGuardResult.Begun)
                networkCallStarted.complete(Unit)
                releaseNetworkCall.await()
                begun
            }
            var second: Deferred<RemoteRoleGuardResult<String>>? = null

            try {
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    networkCallStarted.await()
                }
                assertFalse(first.isCompleted)

                second = async(start = CoroutineStart.UNDISPATCHED) {
                    if (hostStartsFirst) {
                        guard.beginSupportAttempt {
                            secondJournalBegins.incrementAndGet()
                            "support-journal"
                        }
                    } else {
                        guard.beginHostAttempt {
                            secondJournalBegins.incrementAndGet()
                            "host-journal"
                        }
                    }
                }
                val secondResult = withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    second.await()
                }

                assertEquals(
                    RemoteRoleGuardResult.Blocked(RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT),
                    secondResult,
                )
                assertEquals(0, secondJournalBegins.get())
                assertFalse(first.isCompleted)

                releaseNetworkCall.complete(Unit)
                val firstResult = withTimeout(BARRIER_TIMEOUT_MILLIS) { first.await() }
                assertTrue(firstResult is RemoteRoleGuardResult.Begun)
            } finally {
                releaseNetworkCall.complete(Unit)
                withTimeout(BARRIER_TIMEOUT_MILLIS) {
                    first.cancelAndJoin()
                    second?.cancelAndJoin()
                }
            }
        }
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
                    installationId = INSTALLATION_ID,
                    membershipId = MEMBERSHIP_ID,
                    generation = generation("1"),
                    accessToken = SecretValue.of("synthetic-host-pat"),
                    createdAtMillis = 1_790_000_000_000L,
                    owner = OWNER,
                    origin = ORIGIN,
                ),
            ),
        )
    }

    private suspend fun seedSupportAttempt(
        store: PersistentRemoteSupportSessionStore,
    ): RemoteSupportSessionRecord.Attempt =
        (store.beginAttempt(ORIGIN) as RemoteSupportSessionWriteResult.AttemptStarted).record

    private suspend fun seedSupportPending(
        store: PersistentRemoteSupportSessionStore,
    ): RemoteSupportSessionRecord.Pending {
        val attempt = seedSupportAttempt(store)
        return (
            store.commitPending(
                origin = ORIGIN,
                attemptId = attempt.attemptId,
                candidate = PairingCandidateInstallation(
                    origin = ORIGIN,
                    requestId = REQUEST_ID,
                    installationId = SUPPORT_INSTALLATION_ID,
                    requestExpiresAtMillis = 1_790_000_000_000L,
                    accessToken = SecretValue.of("synthetic-support-pat"),
                    status = "Pending",
                ),
            ) as RemoteSupportSessionWriteResult.Committed
            ).record as RemoteSupportSessionRecord.Pending
    }

    private suspend fun seedSupportApproved(
        store: PersistentRemoteSupportSessionStore,
    ): RemoteSupportSessionRecord.Approved {
        val pending = seedSupportPending(store)
        return (
            store.commitApproved(
                expectedRecord = pending,
                supportInstallation = PairingSupportInstallation(
                    installationId = SUPPORT_INSTALLATION_ID,
                    monitorId = MONITOR_ID,
                    membershipId = MEMBERSHIP_ID,
                    role = "Support",
                    status = "Active",
                    monitorGeneration = generation("1"),
                ),
            ) as RemoteSupportSessionWriteResult.Committed
            ).record as RemoteSupportSessionRecord.Approved
    }

    private fun generation(value: String): OwnerGeneration =
        requireNotNull(OwnerGeneration.parse(value))

    private fun guard(
        host: RemoteSessionPresence,
        support: RemoteSessionPresence,
    ): RemoteRoleGuard = RemoteRoleGuard(
        hostPresence = { host },
        supportPresence = { support },
    )

    private class MemoryAtomicRemoteHostFile : RemoteHostAtomicFile {
        @Volatile
        var record: ByteArray? = null
        @Volatile
        var nextReadFailure: IOException? = null
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
        var existingKeyReads = 0

        override fun existingKey(): SecretKey? {
            existingKeyReads += 1
            return key.takeIf { keyAvailable }
        }

        override fun keyForNewRecord(): SecretKey = key
    }

    private companion object {
        const val BARRIER_TIMEOUT_MILLIS = 5_000L
        const val ORIGIN = "https://api.example.test"
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val SUPPORT_INSTALLATION_ID = "33333333-3333-4333-8333-333333333333"
        const val MONITOR_ID = "44444444-4444-4444-8444-444444444444"
        const val MEMBERSHIP_ID = "55555555-5555-4555-8555-555555555555"
        val OWNER = OwnerAccount("firebase-project", "firebase-uid", "Owner")
        val HOST_CONTEXT = RemoteHostOwnerContext(OWNER, ORIGIN)
    }
}
