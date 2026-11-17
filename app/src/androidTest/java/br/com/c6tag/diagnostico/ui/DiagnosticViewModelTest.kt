package br.com.c6tag.diagnostico.ui

import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderError

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.diagnostic.CandidateKind
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticExportResult
import br.com.c6tag.diagnostico.diagnostic.DiagnosticExporter
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.diagnostic.DiagnosticSnapshotMetadata
import br.com.c6tag.diagnostico.diagnostic.DiagnosticStore
import br.com.c6tag.diagnostico.diagnostic.NotificationInterpretation
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticViewModelTest {
    @Test
    fun warningCancelAndPickerCancelDoNotStartExportOrReportSuccess() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel

        try {
            viewModel.uiState.first { !it.isLoading }
            viewModel.requestExport()
            assertTrue(awaitState(viewModel) { it.exportConfirmationVisible }.exportConfirmationVisible)
            viewModel.cancelExport()
            assertFalse(awaitState(viewModel) { !it.exportConfirmationVisible }.exportConfirmationVisible)
            assertNull(viewModel.uiState.value.exportStatus)

            viewModel.requestExport()
            viewModel.confirmExport()
            viewModel.onExportDestinationCancelled()
            assertEquals(0, fixture.store.exportCalls)
            assertNull(viewModel.uiState.value.exportStatus)
            assertFalse(viewModel.uiState.value.isExporting)
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun exportSuccessNullProviderAndWriteFailureKeepAuthorizationAndConnectionIndependent() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel
        fixture.captureStatus.markConnected(observedAtMs = 1_800_000_000_000L)
        viewModel.updateAuthorization(AuthorizationStatus.NOT_AUTHORIZED)

        try {
            viewModel.uiState.first { !it.isLoading && it.captureStatus.connection ==
                br.com.c6tag.diagnostico.capture.CaptureConnectionState.CONNECTED }
            viewModel.requestExport()
            viewModel.confirmExport()
            viewModel.exportToDestination { ByteArrayOutputStream() }
            val success = awaitState(viewModel) {
                !it.isExporting && it.exportStatus == ExportUiStatus.SUCCESS
            }
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, success.authorization)
            assertEquals(fixture.captureStatus.state.value, success.captureStatus)

            viewModel.requestExport()
            viewModel.confirmExport()
            viewModel.exportToDestination { null }
            val noProvider = awaitState(viewModel) {
                !it.isExporting && it.exportStatus == ExportUiStatus.FAILURE
            }
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, noProvider.authorization)
            assertEquals(success.captureStatus, noProvider.captureStatus)

            viewModel.requestExport()
            val retryWarning = awaitState(viewModel) { it.exportConfirmationVisible }
            assertNull(retryWarning.exportStatus)
            viewModel.cancelExport()

            viewModel.requestExport()
            viewModel.confirmExport()
            viewModel.exportToDestination {
                object : OutputStream() {
                    override fun write(value: Int) {
                        throw IOException("raw provider detail must never reach UI")
                    }
                }
            }
            val writeFailure = awaitState(viewModel) {
                !it.isExporting && it.exportStatus == ExportUiStatus.FAILURE
            }
            assertEquals(AuthorizationStatus.NOT_AUTHORIZED, writeFailure.authorization)
            assertEquals(success.captureStatus, writeFailure.captureStatus)
            assertFalse(writeFailure.toString().contains("raw provider detail"))
            assertFalse(writeFailure.toString().contains("private exception"))
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun documentPickerLaunchFailureHasAnIndependentSafeExportStatus() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel
        fixture.captureStatus.markDisconnected(observedAtMs = 1_800_000_000_001L)
        viewModel.updateAuthorization(AuthorizationStatus.AUTHORIZED)

        try {
            viewModel.uiState.first { !it.isLoading && it.captureStatus.connection ==
                br.com.c6tag.diagnostico.capture.CaptureConnectionState.DISCONNECTED }
            viewModel.requestExport()
            viewModel.confirmExport()
            viewModel.reportExportLaunchFailure()
            val failure = viewModel.uiState.first { it.exportStatus == ExportUiStatus.FAILURE }

            assertEquals(0, fixture.store.exportCalls)
            assertEquals(AuthorizationStatus.AUTHORIZED, failure.authorization)
            assertEquals(fixture.captureStatus.state.value, failure.captureStatus)
            assertFalse(failure.toString().contains("ActivityNotFoundException"))
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun settingsLaunchFailurePreservesAuthorizationAndIndependentCaptureState() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel
        fixture.captureStatus.markConnected(observedAtMs = 1_800_000_000_000L)
        fixture.recorder.recordNotification(TARGET_PACKAGE) {
            throw IllegalStateException("raw private recorder detail")
        }

        try {
            val captureAndRecorderState = awaitState(viewModel) {
                it.captureStatus.connection ==
                    br.com.c6tag.diagnostico.capture.CaptureConnectionState.CONNECTED &&
                    it.recorderState.errorCount == 1L &&
                    it.recorderState.version > 0L &&
                    !it.isLoading
            }

            listOf(
                AuthorizationStatus.AUTHORIZED,
                AuthorizationStatus.NOT_AUTHORIZED,
                AuthorizationStatus.CHECK_PENDING,
            ).forEach { authorization ->
                viewModel.updateAuthorization(authorization)
                awaitState(viewModel) { it.authorization == authorization }

                viewModel.reportSettingsLaunchFailure()
                val failed = awaitState(viewModel) {
                    it.settingsLaunchError == SettingsLaunchError.OPEN_FAILED
                }

                assertEquals(authorization, failed.authorization)
                assertEquals(captureAndRecorderState.captureStatus, failed.captureStatus)
                assertEquals(captureAndRecorderState.recorderState, failed.recorderState)
                assertFalse(failed.toString().contains("raw private recorder detail"))
            }
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun successfulSettingsLaunchClearsOnlyItsOwnError() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel
        fixture.captureStatus.markConnected(observedAtMs = 1_800_000_000_000L)

        try {
            val connected = awaitState(viewModel) {
                it.captureStatus.connection ==
                    br.com.c6tag.diagnostico.capture.CaptureConnectionState.CONNECTED &&
                    it.recorderState.version > 0L &&
                    !it.isLoading
            }
            viewModel.updateAuthorization(AuthorizationStatus.NOT_AUTHORIZED)
            viewModel.reportSettingsLaunchFailure()
            awaitState(viewModel) {
                it.authorization == AuthorizationStatus.NOT_AUTHORIZED &&
                    it.settingsLaunchError == SettingsLaunchError.OPEN_FAILED
            }

            viewModel.reportSettingsLaunchSuccess()
            val success = awaitState(viewModel) {
                it.settingsLaunchError == null &&
                    it.authorization == AuthorizationStatus.NOT_AUTHORIZED
            }

            assertEquals(connected.captureStatus, success.captureStatus)
            assertEquals(connected.recorderState, success.recorderState)
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun authorizationUpdatesNeverImplyListenerConnection() = runBlocking {
        val fixture = createViewModel(FakeDiagnosticStore())
        val viewModel = fixture.viewModel

        try {
            viewModel.updateAuthorization(AuthorizationStatus.NOT_AUTHORIZED)
            val notAuthorized = awaitState(viewModel) {
                it.authorization == AuthorizationStatus.NOT_AUTHORIZED
            }
            assertEquals(
                br.com.c6tag.diagnostico.capture.CaptureConnectionState.UNKNOWN,
                notAuthorized.captureStatus.connection,
            )

            fixture.captureStatus.markConnected(observedAtMs = 1_800_000_000_000L)
            val connected = awaitState(viewModel) {
                it.captureStatus.connection ==
                    br.com.c6tag.diagnostico.capture.CaptureConnectionState.CONNECTED
            }
            viewModel.updateAuthorization(AuthorizationStatus.AUTHORIZED)
            val authorized = awaitState(viewModel) {
                it.authorization == AuthorizationStatus.AUTHORIZED
            }
            assertEquals(connected.captureStatus.connection, authorized.captureStatus.connection)

            viewModel.updateAuthorization(AuthorizationStatus.CHECK_PENDING)
            val pending = awaitState(viewModel) {
                it.authorization == AuthorizationStatus.CHECK_PENDING
            }
            assertEquals(connected.captureStatus.connection, pending.captureStatus.connection)
        } finally {
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun opensExistingHistoryAndRefreshesOncePerAppendWithoutPageFeedbackLoop() = runBlocking {
        val store = FakeDiagnosticStore(initialEvents = listOf(stored(1L)))
        val (viewModel, recorder, recorderScope, owner) = createViewModel(store)

        try {
            val initial = viewModel.uiState.first { !it.isLoading && it.events.size == 1 }
            assertEquals(1L, initial.events.single().id)
            assertEquals(1, store.pageCalls)

            recorder.recordNotification(TARGET_PACKAGE) { notification("new-event") }
            val refreshed = viewModel.uiState.first {
                !it.isLoading && it.events.firstOrNull()?.event?.key == "new-event"
            }
            assertEquals(2L, refreshed.events.first().id)

            delay(250L)
            assertEquals("A page query must not trigger another page query.", 2, store.pageCalls)
        } finally {
            owner.clear()
            recorder.closeAndJoin()
            recorderScope.cancel()
        }
    }

    @Test
    fun paginationUsesExclusiveIdCursorAndDoesNotDuplicateBoundaryEvent() = runBlocking {
        val store = FakeDiagnosticStore(initialEvents = (1L..120L).map(::stored))
        val (viewModel, recorder, recorderScope, owner) = createViewModel(store)

        try {
            viewModel.uiState.first { !it.isLoading && it.events.size == 100 }
            assertTrue(viewModel.uiState.value.hasMore)

            viewModel.loadPrevious()
            val allEvents = viewModel.uiState.first { !it.isLoading && it.events.size == 120 }.events

            assertEquals((120L downTo 1L).toList(), allEvents.map(StoredDiagnosticEvent::id))
            assertEquals(120, allEvents.map(StoredDiagnosticEvent::id).distinct().size)
        } finally {
            owner.clear()
            recorder.closeAndJoin()
            recorderScope.cancel()
        }
    }

    @Test
    fun confirmedClearRunsAfterQueuedAppendAndRefreshesToOnlyPostClearEvents() = runBlocking {
        val store = FakeDiagnosticStore(initialEvents = listOf(stored(1L), stored(2L)))
        val (viewModel, recorder, recorderScope, owner) = createViewModel(store)

        try {
            viewModel.uiState.first { !it.isLoading && it.events.size == 2 }
            recorder.recordNotification(TARGET_PACKAGE) { notification("before-clear") }

            viewModel.requestClear()
            viewModel.confirmClear()
            val cleared = viewModel.uiState.first { !it.isLoading && it.events.isEmpty() }

            assertFalse(cleared.clearConfirmationVisible)
            assertEquals(1, store.clearCalls)
            assertTrue(store.snapshot().isEmpty())

            recorder.recordNotification(TARGET_PACKAGE) { notification("after-clear") }
            val afterClearAppend = viewModel.uiState.first {
                !it.isLoading && it.events.singleOrNull()?.event?.key == "after-clear"
            }
            assertEquals("after-clear", afterClearAppend.events.single().event.key)
        } finally {
            owner.clear()
            recorder.closeAndJoin()
            recorderScope.cancel()
        }
    }

    @Test
    fun inFlightPageCannotRestoreAnEventAfterClearWasRequested() = runBlocking {
        val store = FakeDiagnosticStore(initialEvents = listOf(stored(1L)))
        val fixture = createViewModel(store)
        val viewModel = fixture.viewModel
        val releasePage = CountDownLatch(1)
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val observedIds = CopyOnWriteArrayList<List<Long>>()
        val observer = observerScope.launch {
            viewModel.uiState
                .map { state -> state.events.map(StoredDiagnosticEvent::id) }
                .collect { ids -> observedIds.add(ids) }
        }

        try {
            viewModel.uiState.first { !it.isLoading && it.events.size == 1 }
            val pageStarted = CountDownLatch(1)
            store.blockNextPage(pageStarted, releasePage)
            fixture.recorder.recordNotification(TARGET_PACKAGE) { notification("in-flight") }
            assertTrue(withContext(Dispatchers.IO) { pageStarted.await(5, TimeUnit.SECONDS) })

            viewModel.requestClear()
            viewModel.confirmClear()
            releasePage.countDown()
            viewModel.uiState.first {
                !it.isLoading && it.events.isEmpty() && store.clearCalls == 1
            }

            assertFalse(observedIds.any { 2L in it })
        } finally {
            releasePage.countDown()
            observer.cancel()
            observerScope.cancel()
            fixture.owner.clear()
            fixture.recorder.closeAndJoin()
            fixture.recorderScope.cancel()
        }
    }

    @Test
    fun storageFailuresKeepHistoryAndExposeOnlySafeCodes() = runBlocking {
        val pageFailureStore = FakeDiagnosticStore(initialEvents = listOf(stored(1L))).apply {
            failNextPage = true
        }
        val (pageFailureViewModel, pageRecorder, pageScope, pageOwner) =
            createViewModel(pageFailureStore)

        try {
            val failedLoad = pageFailureViewModel.uiState.first {
                !it.isLoading && it.historyError == HistoryError.LOAD_FAILED
            }
            assertTrue(failedLoad.events.isEmpty())
            assertFalse(failedLoad.toString().contains("raw private exception"))
        } finally {
            pageOwner.clear()
            pageRecorder.closeAndJoin()
            pageScope.cancel()
        }

        val clearFailureStore = FakeDiagnosticStore(initialEvents = listOf(stored(9L))).apply {
            failClear = true
        }
        val (clearFailureViewModel, clearRecorder, clearScope, clearOwner) =
            createViewModel(clearFailureStore)

        try {
            val loaded = clearFailureViewModel.uiState.first { !it.isLoading && it.events.size == 1 }
            clearFailureViewModel.requestClear()
            clearFailureViewModel.confirmClear()
            val failedClear = clearFailureViewModel.uiState.first {
                !it.isLoading && it.historyError == HistoryError.CLEAR_FAILED &&
                    it.recorderState.lastError == DiagnosticRecorderError.STORE_OPERATION_FAILED
            }

            assertEquals(loaded.events, failedClear.events)
            assertNull(failedClear.selectedEvent)
            assertEquals(DiagnosticRecorderError.STORE_OPERATION_FAILED, failedClear.recorderState.lastError)
            assertFalse(failedClear.toString().contains("raw private exception"))
            assertEquals(0, clearFailureStore.clearCalls)
        } finally {
            clearOwner.clear()
            clearRecorder.closeAndJoin()
            clearScope.cancel()
        }
    }

    private fun createViewModel(store: FakeDiagnosticStore): ViewModelFixture {
        val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = DiagnosticRecorder(
            store = store,
            allowedPackage = TARGET_PACKAGE,
            scope = recorderScope,
        )
        val captureStatus = CaptureStatus(TARGET_PACKAGE)
        val viewModel = DiagnosticViewModel(
            recorder = recorder,
            captureStatus = captureStatus,
            targetPackage = TARGET_PACKAGE,
            exporter = DiagnosticExporter(
                recorder = recorder,
                targetPackage = TARGET_PACKAGE,
                appVersionName = "test",
                appVersionCode = 1L,
                nowEpochMillis = { 1_800_000_000_000L },
            ),
        )
        val owner = ViewModelStore().apply { put("diagnostic", viewModel) }
        return ViewModelFixture(viewModel, recorder, recorderScope, owner, captureStatus, store)
    }

    private data class ViewModelFixture(
        val viewModel: DiagnosticViewModel,
        val recorder: DiagnosticRecorder,
        val recorderScope: CoroutineScope,
        val owner: ViewModelStore,
        val captureStatus: CaptureStatus,
        val store: FakeDiagnosticStore,
    )

    private suspend fun awaitState(
        viewModel: DiagnosticViewModel,
        predicate: (DiagnosticUiState) -> Boolean,
    ): DiagnosticUiState = withTimeout(5_000L) { viewModel.uiState.first(predicate) }

    private fun stored(id: Long) = StoredDiagnosticEvent(
        id = id,
        event = notification("seed-$id"),
    )

    private fun notification(key: String) = DiagnosticEvent(
        type = DiagnosticEventType.POSTED_OR_UPDATED,
        observedAtMs = 1_800_000_000_000L,
        packageName = TARGET_PACKAGE,
        key = key,
        title = "Débito C6 Tag",
        text = "Valor a debitar R$ 16,91.",
        interpretation = NotificationInterpretation(CandidateKind.TAG_DEBIT, amountCents = 1_691L),
    )

    private class FakeDiagnosticStore(
        initialEvents: List<StoredDiagnosticEvent> = emptyList(),
    ) : DiagnosticStore {
        private val events = initialEvents.toMutableList()
        private var nextId = (initialEvents.maxOfOrNull(StoredDiagnosticEvent::id) ?: 0L) + 1L
        private var nextPageBlock: PageBlock? = null
        var pageCalls = 0
            private set
        @Volatile
        var clearCalls = 0
            private set
        var exportCalls = 0
            private set
        var failNextPage = false
        var failClear = false

        @Synchronized
        override fun append(event: DiagnosticEvent): Long {
            val id = nextId++
            events += StoredDiagnosticEvent(id, event)
            return id
        }

        @Synchronized
        override fun page(beforeId: Long?, limit: Int): List<StoredDiagnosticEvent> {
            pageCalls++
            nextPageBlock?.let { block ->
                nextPageBlock = null
                block.started.countDown()
                check(block.release.await(5, TimeUnit.SECONDS))
            }
            if (failNextPage) {
                failNextPage = false
                throw IllegalStateException("raw private exception")
            }
            return events.asSequence()
                .filter { beforeId == null || it.id < beforeId }
                .sortedByDescending(StoredDiagnosticEvent::id)
                .take(limit)
                .toList()
        }

        @Synchronized
        override fun clear() {
            if (failClear) throw IllegalStateException("raw private exception")
            clearCalls++
            events.clear()
        }

        @Synchronized
        override fun <T> exportSnapshot(
            block: (DiagnosticSnapshotMetadata, Sequence<StoredDiagnosticEvent>) -> T,
        ): T {
            exportCalls++
            val snapshot = events.sortedByDescending(StoredDiagnosticEvent::id)
            return block(
                DiagnosticSnapshotMetadata(
                    total = snapshot.size.toLong(),
                    minId = snapshot.minOfOrNull(StoredDiagnosticEvent::id),
                    maxId = snapshot.maxOfOrNull(StoredDiagnosticEvent::id),
                ),
                snapshot.asSequence(),
            )
        }

        @Synchronized
        fun snapshot(): List<StoredDiagnosticEvent> = events.toList()

        @Synchronized
        fun blockNextPage(
            started: CountDownLatch,
            release: CountDownLatch,
        ) {
            nextPageBlock = PageBlock(started, release)
        }

        private data class PageBlock(
            val started: CountDownLatch,
            val release: CountDownLatch,
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "br.com.c6bank"
    }
}
