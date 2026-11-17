package br.com.c6tag.diagnostico.ui

import androidx.lifecycle.ViewModel
import br.com.c6tag.diagnostico.capture.CaptureStatus
import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorder
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderState
import br.com.c6tag.diagnostico.diagnostic.DiagnosticExportResult
import br.com.c6tag.diagnostico.diagnostic.DiagnosticExporter
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class AuthorizationStatus {
    CHECK_PENDING,
    AUTHORIZED,
    NOT_AUTHORIZED,
    CHECK_FAILED,
}

internal enum class HistoryError {
    LOAD_FAILED,
    CLEAR_FAILED,
}

internal enum class SettingsLaunchError {
    OPEN_FAILED,
}

internal enum class ExportUiStatus {
    SUCCESS,
    FAILURE,
}

internal data class DiagnosticUiState(
    val targetPackage: String,
    val authorization: AuthorizationStatus = AuthorizationStatus.CHECK_PENDING,
    val captureStatus: CaptureStatusState,
    val recorderState: DiagnosticRecorderState = DiagnosticRecorderState(),
    val events: List<StoredDiagnosticEvent> = emptyList(),
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val historyError: HistoryError? = null,
    val settingsLaunchError: SettingsLaunchError? = null,
    val selectedEvent: StoredDiagnosticEvent? = null,
    val clearConfirmationVisible: Boolean = false,
    val exportConfirmationVisible: Boolean = false,
    val isExporting: Boolean = false,
    val exportStatus: ExportUiStatus? = null,
)

internal class DiagnosticViewModel(
    private val recorder: DiagnosticRecorder,
    private val exporter: DiagnosticExporter,
    captureStatus: CaptureStatus,
    targetPackage: String,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationMutex = Mutex()
    private val pageGeneration = AtomicLong()

    private val mutableUiState = MutableStateFlow(
        DiagnosticUiState(
            targetPackage = targetPackage,
            captureStatus = captureStatus.state.value,
        ),
    )
    val uiState: StateFlow<DiagnosticUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            recorder.state.collect { recorderState ->
                mutableUiState.update { it.copy(recorderState = recorderState) }
            }
        }
        scope.launch {
            captureStatus.state.collect { captureState ->
                mutableUiState.update { it.copy(captureStatus = captureState) }
            }
        }
        scope.launch {
            recorder.state
                .map { it.lastSuccessfulId }
                .distinctUntilChanged()
                .drop(1)
                .filterNotNull()
                .collect { refreshFirstPage() }
        }
        refreshFirstPage()
    }

    fun updateAuthorization(status: AuthorizationStatus) {
        mutableUiState.update { it.copy(authorization = status) }
    }

    fun reportSettingsLaunchFailure() {
        mutableUiState.update { it.copy(settingsLaunchError = SettingsLaunchError.OPEN_FAILED) }
    }

    fun reportSettingsLaunchSuccess() {
        mutableUiState.update { it.copy(settingsLaunchError = null) }
    }

    fun loadPrevious() {
        val generation = pageGeneration.get()
        scope.launch {
            operationMutex.withLock {
                if (generation != pageGeneration.get()) return@withLock
                val current = mutableUiState.value
                if (current.isLoading || !current.hasMore) return@withLock
                val cursor = current.events.lastOrNull()?.id ?: return@withLock

                mutableUiState.update { it.copy(isLoading = true) }
                try {
                    val previous = recorder.page(beforeId = cursor, limit = PAGE_SIZE)
                    if (generation == pageGeneration.get()) {
                        mutableUiState.update { state ->
                            val knownIds = state.events.mapTo(mutableSetOf(), StoredDiagnosticEvent::id)
                            state.copy(
                                events = state.events + previous.filterNot { it.id in knownIds },
                                hasMore = previous.size == PAGE_SIZE,
                                isLoading = false,
                                historyError = null,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == pageGeneration.get()) {
                        mutableUiState.update {
                            it.copy(isLoading = false, historyError = HistoryError.LOAD_FAILED)
                        }
                    }
                }
            }
        }
    }

    fun retryHistory() {
        refreshFirstPage()
    }

    fun openEvent(id: Long) {
        mutableUiState.update { current ->
            current.copy(selectedEvent = current.events.firstOrNull { it.id == id })
        }
    }

    fun closeDetail() {
        mutableUiState.update { it.copy(selectedEvent = null) }
    }

    fun requestClear() {
        mutableUiState.update { it.copy(clearConfirmationVisible = true) }
    }

    fun cancelClear() {
        mutableUiState.update { it.copy(clearConfirmationVisible = false) }
    }

    fun confirmClear() {
        if (!mutableUiState.value.clearConfirmationVisible || mutableUiState.value.isExporting) return
        pageGeneration.incrementAndGet()
        mutableUiState.update {
            it.copy(clearConfirmationVisible = false, isLoading = true)
        }
        scope.launch {
            operationMutex.withLock {
                try {
                    recorder.clear()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableUiState.update {
                        it.copy(isLoading = false, historyError = HistoryError.CLEAR_FAILED)
                    }
                    return@withLock
                }

                mutableUiState.update {
                    it.copy(
                        events = emptyList(),
                        hasMore = false,
                        selectedEvent = null,
                        historyError = null,
                    )
                }
                try {
                    val events = recorder.page(limit = PAGE_SIZE)
                    mutableUiState.update {
                        it.copy(
                            events = events,
                            hasMore = events.size == PAGE_SIZE,
                            isLoading = false,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableUiState.update {
                        it.copy(isLoading = false, historyError = HistoryError.LOAD_FAILED)
                    }
                }
            }
        }
    }

    fun requestExport() {
        if (mutableUiState.value.isExporting) return
        mutableUiState.update {
            it.copy(
                exportConfirmationVisible = true,
                exportStatus = null,
            )
        }
    }

    fun cancelExport() {
        mutableUiState.update { it.copy(exportConfirmationVisible = false) }
    }

    /** Hides the warning. The Activity launches SAF only after this explicit confirmation. */
    fun confirmExport(): Boolean {
        if (!mutableUiState.value.exportConfirmationVisible) return false
        mutableUiState.update { it.copy(exportConfirmationVisible = false) }
        return true
    }

    /** A null CreateDocument result is cancellation, not a failed export. */
    fun onExportDestinationCancelled() = Unit

    fun reportExportLaunchFailure() {
        mutableUiState.update {
            it.copy(
                exportConfirmationVisible = false,
                isExporting = false,
                exportStatus = ExportUiStatus.FAILURE,
            )
        }
    }

    /**
     * [openOutput] is retained only by the queued recorder callback and invoked
     * on its IO worker. That callback owns and closes the returned stream before
     * its cursor/snapshot transaction is released.
     */
    fun exportToDestination(openOutput: () -> OutputStream?) {
        if (mutableUiState.value.isExporting) return
        mutableUiState.update {
            it.copy(
                exportConfirmationVisible = false,
                isExporting = true,
                exportStatus = null,
            )
        }
        scope.launch {
            operationMutex.withLock {
                val status = try {
                    when (exporter.export(openOutput)) {
                        DiagnosticExportResult.Success -> ExportUiStatus.SUCCESS
                        is DiagnosticExportResult.Failure -> ExportUiStatus.FAILURE
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ExportUiStatus.FAILURE
                }
                mutableUiState.update {
                    it.copy(
                        isExporting = false,
                        exportStatus = status,
                    )
                }
            }
        }
    }

    private fun refreshFirstPage() {
        val generation = pageGeneration.incrementAndGet()
        scope.launch {
            operationMutex.withLock {
                if (generation != pageGeneration.get()) return@withLock
                mutableUiState.update { it.copy(isLoading = true) }
                try {
                    val events = recorder.page(limit = PAGE_SIZE)
                    if (generation == pageGeneration.get()) {
                        mutableUiState.update {
                            it.copy(
                                events = events,
                                hasMore = events.size == PAGE_SIZE,
                                isLoading = false,
                                historyError = null,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation == pageGeneration.get()) {
                        mutableUiState.update {
                            it.copy(isLoading = false, historyError = HistoryError.LOAD_FAILED)
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
