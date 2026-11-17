package br.com.c6tag.diagnostico.diagnostic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.Collections

class DiagnosticRecorderTest {
    @Test
    fun rejectsOtherPackagesBeforeBuildingEventAndBlankPackageDisablesCapture() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        var supplierCalls = 0

        try {
            recorder.recordNotification("other.package") {
                supplierCalls++
                notification("other.package")
            }
            assertTrue(recorder.page(limit = 10).isEmpty())
            assertEquals(0, supplierCalls)

            val disabled = DiagnosticRecorder(
                store = store,
                allowedPackage = "",
                ioDispatcher = Dispatchers.IO,
                scope = scope,
            )
            disabled.recordNotification("authorized.package") {
                supplierCalls++
                notification("authorized.package")
            }
            assertTrue(disabled.page(limit = 10).isEmpty())
            assertEquals(0, supplierCalls)
            disabled.close()
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun repeatedAndEqualNotificationsArePersistedIndependentlyInQueueOrder() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        val sameEvent = interpretedNotification(key = "same-key")

        try {
            recorder.recordNotification("authorized.package") { sameEvent }
            recorder.recordNotification("authorized.package") { sameEvent }

            val page = recorder.page(limit = 10)

            assertEquals(2, page.size)
            assertNotEquals(page[0].id, page[1].id)
            assertEquals(listOf(sameEvent, sameEvent), page.map(StoredDiagnosticEvent::event))
            assertTrue(page[0].id > page[1].id)
            assertEquals(listOf("same-key", "same-key"), page.map { it.event.key })
            assertEquals(
                listOf(CandidateKind.TAG_DEBIT, CandidateKind.TAG_DEBIT),
                page.map { it.event.interpretation?.kind },
            )
            assertEquals(listOf(1691L, 1691L), page.map { it.event.interpretation?.amountCents })
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun sameKeyAndInterpretationAtDifferentObservationTimesRemainIndependent() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        val earlier = interpretedNotification(key = "same-key", observedAtMs = 100L)
        val later = interpretedNotification(key = "same-key", observedAtMs = 200L)

        try {
            recorder.recordNotification("authorized.package") { earlier }
            recorder.recordNotification("authorized.package") { later }

            val page = recorder.page(limit = 10)

            assertEquals(2, page.size)
            assertNotEquals(page[0].id, page[1].id)
            assertEquals(listOf(later, earlier), page.map(StoredDiagnosticEvent::event))
            assertEquals(listOf(200L, 100L), page.map { it.event.observedAtMs })
            assertEquals(listOf("same-key", "same-key"), page.map { it.event.key })
            assertEquals(
                listOf(CandidateKind.TAG_DEBIT, CandidateKind.TAG_DEBIT),
                page.map { it.event.interpretation?.kind },
            )
            assertEquals(listOf(1691L, 1691L), page.map { it.event.interpretation?.amountCents })
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun appendsThenClearThenAppendPreservesOnlyLaterEventAndItsType() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        val snapshot = notification(
            "authorized.package",
            type = DiagnosticEventType.ACTIVE_SNAPSHOT,
            key = "snapshot",
        )
        val removal = notification(
            "authorized.package",
            type = DiagnosticEventType.REMOVED,
            key = "removed",
        )

        try {
            recorder.recordNotification("authorized.package") { snapshot }
            recorder.recordNotification("authorized.package") { removal }
            assertEquals(2, recorder.page(limit = 10).size)

            recorder.clear()
            recorder.recordNotification("authorized.package") { removal }

            val page = recorder.page(limit = 10)
            assertEquals(1, page.size)
            assertEquals(removal, page.single().event)
            assertEquals(DiagnosticEventType.REMOVED, page.single().event.type)
            assertNull(page.single().event.interpretation)
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun lifecycleEventsAreRecordedSeparatelyWithoutNotificationPackage() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)

        try {
            recorder.recordLifecycle(DiagnosticEventType.LISTENER_CONNECTED, observedAtMs = 10L)
            assertThrows(IllegalArgumentException::class.java) {
                recorder.recordLifecycle(DiagnosticEventType.POSTED_OR_UPDATED, observedAtMs = 11L)
            }

            val event = recorder.page(limit = 10).single().event
            assertEquals(DiagnosticEventType.LISTENER_CONNECTED, event.type)
            assertEquals(10L, event.observedAtMs)
            assertNull(event.packageName)
            assertNull(event.interpretation)
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun pagesUseExclusiveCursorAndRejectInvalidLimits() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)

        try {
            repeat(3) { index ->
                recorder.recordNotification("authorized.package") {
                    notification("authorized.package", key = "key-$index")
                }
            }

            val firstPage = recorder.page(limit = 2)
            val secondPage = recorder.page(beforeId = firstPage.last().id, limit = 2)

            assertEquals(2, firstPage.size)
            assertEquals(1, secondPage.size)
            assertTrue(firstPage.first().id > firstPage.last().id)
            assertTrue(firstPage.last().id > secondPage.single().id)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { recorder.page(limit = 0) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { recorder.page(limit = 101) }
            }
            Unit
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun snapshotRunsAfterQueuedAppendsAndProvidesMetadataAndDescendingEvents() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)

        try {
            repeat(3) { index ->
                recorder.recordNotification("authorized.package") {
                    notification("authorized.package", key = "key-$index")
                }
            }

            var observedMetadata: DiagnosticSnapshotMetadata? = null
            var observedEvents: List<StoredDiagnosticEvent> = emptyList()
            recorder.exportSnapshot { metadata, events ->
                observedMetadata = metadata
                observedEvents = events.toList()
            }

            assertEquals(3L, observedMetadata?.total)
            assertEquals(1L, observedMetadata?.minId)
            assertEquals(3L, observedMetadata?.maxId)
            assertEquals(listOf(3L, 2L, 1L), observedEvents.map(StoredDiagnosticEvent::id))
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun inconsistentSupplierPackageIsRejectedWithoutPersistingEvent() = runBlocking {
        val store = FakeDiagnosticStore()
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)

        try {
            recorder.recordNotification("authorized.package") { notification("other.package") }
            recorder.recordNotification("authorized.package") { notification("authorized.package") }

            val page = recorder.page(limit = 10)
            val state = recorder.state.value

            assertEquals(1, page.size)
            assertEquals(1L, state.errorCount)
            assertEquals(DiagnosticRecorderError.EVENT_PACKAGE_MISMATCH, state.lastError)
            assertEquals(DiagnosticRecorderOperation.PAGE, state.lastSuccess)
            assertFalse(state.toString().contains("texto privado"))
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun appendFailureIsObservableAndWorkerContinuesWithStickySafeError() = runBlocking {
        val store = FakeDiagnosticStore().apply {
            failNextAppend = IllegalStateException("conteúdo potencialmente sensível")
        }
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)

        try {
            recorder.recordNotification("authorized.package") { notification("authorized.package") }
            recorder.page(limit = 10)
            val stateAfterFailure = recorder.state.value
            assertEquals(1L, stateAfterFailure.errorCount)
            assertEquals(DiagnosticRecorderError.STORE_OPERATION_FAILED, stateAfterFailure.lastError)

            recorder.recordNotification("authorized.package") {
                notification("authorized.package", key = "later")
            }
            assertEquals(1, recorder.page(limit = 10).size)

            val stateAfterSuccess = recorder.state.value
            assertEquals(1L, stateAfterSuccess.errorCount)
            assertEquals(DiagnosticRecorderError.STORE_OPERATION_FAILED, stateAfterSuccess.lastError)
            assertEquals(DiagnosticRecorderOperation.PAGE, stateAfterSuccess.lastSuccess)
            assertFalse(stateAfterSuccess.toString().contains("conteúdo potencialmente sensível"))
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun closingDrainsQueuedAppendsAndWorkerCancellationCompletesWaitingQueries() = runBlocking {
        val store = FakeDiagnosticStore()
        val closeScope = CoroutineScope(SupervisorJob())
        val closeRecorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, closeScope)
        closeRecorder.recordNotification("authorized.package") {
            notification("authorized.package", key = "close-drain")
        }
        closeRecorder.closeAndJoin()
        assertEquals(1, store.page(limit = 10).size)
        closeScope.cancel()

        val appendStarted = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val blockedStore = FakeDiagnosticStore().apply {
            appendStartedSignal = appendStarted
            appendReleaseSignal = releaseAppend
        }
        val cancelledScope = CoroutineScope(SupervisorJob())
        val cancelledRecorder =
            DiagnosticRecorder(blockedStore, "authorized.package", Dispatchers.IO, cancelledScope)

        try {
            cancelledRecorder.recordNotification("authorized.package") {
                notification("authorized.package", key = "cancel")
            }
            assertTrue(withContext(Dispatchers.IO) {
                appendStarted.await(5, TimeUnit.SECONDS)
            })
            val waitingPage = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { cancelledRecorder.page(limit = 10) }
            }

            cancelledScope.cancel()
            releaseAppend.countDown()
            val result = withTimeout(5_000) { waitingPage.await() }
            assertTrue(waitingPage.isCompleted)
            assertTrue(result.isFailure)
        } finally {
            releaseAppend.countDown()
            cancelledRecorder.closeAndJoin()
            cancelledScope.cancel()
        }
    }

    @Test
    fun financialCommandSharesAppendOrderAndWrongPackageSupplierStillNeverRuns() = runBlocking {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = FakeDiagnosticStore().apply {
            appendStartedSignal = started
            appendReleaseSignal = release
            onAppend = { operations += it.key!! }
        }
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        var supplierCalls = 0
        try {
            recorder.recordNotification("authorized.package") { notification("authorized.package", key = "first") }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            recorder.recordNotification("other.package") {
                supplierCalls++
                notification("other.package")
            }
            val financial = async(start = CoroutineStart.UNDISPATCHED) {
                recorder.runSerialized { operations += "financial"; 42L }
            }
            recorder.recordNotification("authorized.package") { notification("authorized.package", key = "second") }
            release.countDown()
            recorder.page(limit = 10)
            assertEquals(42L, financial.await())
            assertEquals(listOf("first", "financial", "second"), operations.toList())
            assertEquals(0, supplierCalls)
        } finally {
            release.countDown()
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun serializedFailureIsSafeWorkerContinuesAndClosedQueueNeverExecutesCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(FakeDiagnosticStore(), "authorized.package", Dispatchers.IO, scope)
        try {
            val error = runCatching {
                recorder.runSerialized<Unit> { throw IllegalStateException("conteúdo bancário privado") }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertFalse(error!!.message.orEmpty().contains("conteúdo bancário"))
            assertEquals(9L, recorder.runSerialized { 9L })
            recorder.closeAndJoin()
            var executed = false
            assertTrue(runCatching { recorder.runSerialized { executed = true } }.isFailure)
            assertFalse(executed)
        } finally {
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun workerCancellationCompletesQueuedSerializedCommandWithoutExecutingIt() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = FakeDiagnosticStore().apply {
            appendStartedSignal = started
            appendReleaseSignal = release
        }
        val scope = CoroutineScope(SupervisorJob())
        val recorder = DiagnosticRecorder(store, "authorized.package", Dispatchers.IO, scope)
        try {
            recorder.recordNotification("authorized.package") { notification("authorized.package") }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            var executed = false
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { recorder.runSerialized { executed = true; 42L } }
            }
            scope.cancel()
            release.countDown()
            val result = withTimeout(5_000L) { waiting.await() }
            assertTrue(result.isFailure)
            assertFalse(executed)
        } finally {
            release.countDown()
            recorder.closeAndJoin()
            scope.cancel()
        }
    }

    private fun notification(
        packageName: String,
        type: DiagnosticEventType = DiagnosticEventType.POSTED_OR_UPDATED,
        key: String = "key",
    ) = DiagnosticEvent(
        type = type,
        observedAtMs = 1_800_000_000_000L,
        packageName = packageName,
        key = key,
        title = "Título fictício",
        text = "Conteúdo fictício",
    )

    private fun interpretedNotification(
        key: String,
        observedAtMs: Long = 1_800_000_000_000L,
    ) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = observedAtMs,
        packageName = "authorized.package",
        key = key,
        title = "Débito C6 Tag",
        text = "Valor a debitar R$ 16,91.",
        interpretation = NotificationInterpretation(
            kind = CandidateKind.TAG_DEBIT,
            amountCents = 1691L,
        ),
    )

    private class FakeDiagnosticStore : DiagnosticStore {
        private val stored = mutableListOf<StoredDiagnosticEvent>()
        private var nextId = 1L
        var failNextAppend: Exception? = null
        var appendStartedSignal: CountDownLatch? = null
        var appendReleaseSignal: CountDownLatch? = null
        var onAppend: ((DiagnosticEvent) -> Unit)? = null

        @Synchronized
        override fun append(event: DiagnosticEvent): Long {
            appendStartedSignal?.countDown()
            appendReleaseSignal?.await()
            failNextAppend?.let {
                failNextAppend = null
                throw it
            }
            val id = nextId++
            stored += StoredDiagnosticEvent(id, event)
            onAppend?.invoke(event)
            return id
        }

        @Synchronized
        override fun page(beforeId: Long?, limit: Int): List<StoredDiagnosticEvent> {
            require(limit in 1..100)
            return stored.asSequence()
                .filter { beforeId == null || it.id < beforeId }
                .sortedByDescending(StoredDiagnosticEvent::id)
                .take(limit)
                .toList()
        }

        @Synchronized
        override fun clear() {
            stored.clear()
        }

        @Synchronized
        override fun <T> exportSnapshot(
            block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
        ): T {
            val snapshot = stored.sortedByDescending(StoredDiagnosticEvent::id).toList()
            val metadata = DiagnosticSnapshotMetadata(
                total = snapshot.size.toLong(),
                minId = snapshot.minOfOrNull(StoredDiagnosticEvent::id),
                maxId = snapshot.maxOfOrNull(StoredDiagnosticEvent::id),
            )
            return block(metadata, snapshot.asSequence())
        }
    }
}
