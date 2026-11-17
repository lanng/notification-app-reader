package br.com.c6tag.diagnostico.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.capture.CaptureConnectionState
import br.com.c6tag.diagnostico.capture.CaptureFailureCode
import br.com.c6tag.diagnostico.diagnostic.CandidateKind
import br.com.c6tag.diagnostico.diagnostic.DiagnosticEventType
import br.com.c6tag.diagnostico.diagnostic.DiagnosticRecorderError
import br.com.c6tag.diagnostico.diagnostic.StoredDiagnosticEvent

internal data class DiagnosticScreenActions(
    val onOpenSettings: () -> Unit = {},
    val onLoadPrevious: () -> Unit = {},
    val onRetryHistory: () -> Unit = {},
    val onOpenEvent: (Long) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onRequestClear: () -> Unit = {},
    val onCancelClear: () -> Unit = {},
    val onConfirmClear: () -> Unit = {},
    val onRequestExport: () -> Unit = {},
    val onCancelExport: () -> Unit = {},
    val onConfirmExport: () -> Unit = {},
)

@Composable
internal fun DiagnosticScreen(
    state: DiagnosticUiState,
    actions: DiagnosticScreenActions,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        val selected = state.selectedEvent
        if (selected != null) {
            EventDetail(
                storedEvent = selected,
                onBack = actions.onCloseDetail,
            )
        } else {
            Overview(state = state, actions = actions)
        }
    }

    if (state.clearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = actions.onCancelClear,
            title = { Text(stringResource(R.string.clear_confirmation_title)) },
            text = { Text(stringResource(R.string.clear_confirmation_message)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmClear) {
                    Text(stringResource(R.string.confirm_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelClear) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (state.exportConfirmationVisible) {
        AlertDialog(
            onDismissRequest = actions.onCancelExport,
            title = { Text(stringResource(R.string.export_confirmation_title)) },
            text = { Text(stringResource(R.string.export_confirmation_message)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmExport) {
                    Text(stringResource(R.string.confirm_export))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onCancelExport) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Overview(
    state: DiagnosticUiState,
    actions: DiagnosticScreenActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.diagnostic_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.diagnostic_no_balance),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.estimate_warning),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.capture_may_be_incomplete),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            text = if (state.targetPackage.isBlank() || !state.captureStatus.targetPackageConfigured) {
                stringResource(R.string.target_not_configured)
            } else {
                stringResource(R.string.target_configured, state.targetPackage)
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = when (state.authorization) {
                AuthorizationStatus.CHECK_PENDING -> stringResource(R.string.access_pending)
                AuthorizationStatus.AUTHORIZED -> stringResource(R.string.access_authorized)
                AuthorizationStatus.NOT_AUTHORIZED -> stringResource(R.string.access_not_authorized)
                AuthorizationStatus.CHECK_FAILED -> stringResource(R.string.access_check_failed)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = actions.onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.open_access_settings))
        }
        if (state.settingsLaunchError == SettingsLaunchError.OPEN_FAILED) {
            Text(
                text = stringResource(R.string.settings_launch_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = actions.onRequestExport,
            enabled = !state.isExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.export_records))
        }
        if (state.isExporting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.export_in_progress), style = MaterialTheme.typography.bodySmall)
        }
        when (state.exportStatus) {
            ExportUiStatus.SUCCESS -> Text(stringResource(R.string.export_success))
            ExportUiStatus.FAILURE -> Text(
                stringResource(R.string.export_failure),
                color = MaterialTheme.colorScheme.error,
            )

            null -> Unit
        }

        Text(
            text = when (state.captureStatus.connection) {
                CaptureConnectionState.UNKNOWN -> stringResource(R.string.connection_unknown)
                CaptureConnectionState.CONNECTED -> stringResource(R.string.connection_connected)
                CaptureConnectionState.DISCONNECTED -> stringResource(R.string.connection_disconnected)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        val connectionTime = state.captureStatus.connectionObservedAtMs
        Text(
            text = if (connectionTime == null) {
                stringResource(R.string.no_connection_observation_time)
            } else {
                stringResource(R.string.connection_observed_at, formatDiagnosticTimestamp(connectionTime))
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        state.captureStatus.lastError?.let { error ->
            if (state.captureStatus.errorCount > 0L) {
                Text(
                    text = captureErrorText(error, state.captureStatus.errorCount),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        state.recorderState.lastError?.let { error ->
            if (state.recorderState.errorCount > 0L) {
                Text(
                    text = recorderErrorText(error, state.recorderState.errorCount),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        when (state.historyError) {
            HistoryError.LOAD_FAILED -> {
                Text(stringResource(R.string.history_load_failed), color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = actions.onRetryHistory,
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.retry_history))
                }
            }

            HistoryError.CLEAR_FAILED ->
                Text(stringResource(R.string.history_clear_failed), color = MaterialTheme.colorScheme.error)

            null -> Unit
        }

        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.history_order), style = MaterialTheme.typography.bodySmall)
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.history_loading), style = MaterialTheme.typography.bodySmall)
        }
        if (state.events.isEmpty() && !state.isLoading) {
            Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium)
        }
        state.events.forEach { storedEvent ->
            HistoryRow(
                storedEvent = storedEvent,
                onClick = { actions.onOpenEvent(storedEvent.id) },
            )
        }
        if (state.hasMore) {
            Button(
                onClick = actions.onLoadPrevious,
                enabled = !state.isLoading && !state.isExporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.load_previous))
            }
        }
        Button(
            onClick = actions.onRequestClear,
            enabled = !state.isLoading && !state.isExporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.clear_records))
        }
    }
}

@Composable
private fun HistoryRow(
    storedEvent: StoredDiagnosticEvent,
    onClick: () -> Unit,
) {
    val event = storedEvent.event
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic-event-${storedEvent.id}")
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.event_id, storedEvent.id),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.event_type, eventTypeLabel(event.type)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.event_classification, classificationLabel(event)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = event.title ?: stringResource(R.string.missing_value),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun captureErrorText(error: CaptureFailureCode, count: Long): String {
    val occurrences = occurrenceLabel(count)
    return when (error) {
        CaptureFailureCode.ACTIVE_SNAPSHOT_QUERY_FAILED ->
            stringResource(
                if (count == 1L) R.string.capture_error_snapshot_singular else R.string.capture_error_snapshot_plural,
                occurrences,
            )

        CaptureFailureCode.NOTIFICATION_MAPPING_FAILED ->
            stringResource(
                if (count == 1L) R.string.capture_error_mapping_singular else R.string.capture_error_mapping_plural,
                occurrences,
            )

        CaptureFailureCode.CAPTURE_QUEUE_CLOSED ->
            stringResource(
                if (count == 1L) R.string.capture_error_queue_singular else R.string.capture_error_queue_plural,
                occurrences,
            )
    }
}

@Composable
private fun recorderErrorText(error: DiagnosticRecorderError, count: Long): String {
    val occurrences = occurrenceLabel(count)
    val resource = when (error) {
        DiagnosticRecorderError.EVENT_CREATION_FAILED ->
            if (count == 1L) R.string.recorder_error_creation_singular else R.string.recorder_error_creation_plural

        DiagnosticRecorderError.EVENT_PACKAGE_MISMATCH ->
            if (count == 1L) R.string.recorder_error_package_singular else R.string.recorder_error_package_plural

        DiagnosticRecorderError.STORE_OPERATION_FAILED ->
            if (count == 1L) R.string.recorder_error_store_singular else R.string.recorder_error_store_plural

        DiagnosticRecorderError.RECORDER_CLOSED ->
            if (count == 1L) R.string.recorder_error_closed_singular else R.string.recorder_error_closed_plural
    }
    return stringResource(resource, occurrences)
}

@Composable
private fun occurrenceLabel(count: Long): String =
    pluralStringResource(
        R.plurals.occurrences,
        count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        count,
    )

@Composable
internal fun eventTypeLabel(type: DiagnosticEventType): String = when (type) {
    DiagnosticEventType.POSTED_OR_UPDATED -> stringResource(R.string.type_posted)
    DiagnosticEventType.REMOVED -> stringResource(R.string.type_removed)
    DiagnosticEventType.ACTIVE_SNAPSHOT -> stringResource(R.string.type_snapshot)
    DiagnosticEventType.LISTENER_CONNECTED -> stringResource(R.string.type_listener_connected)
    DiagnosticEventType.LISTENER_DISCONNECTED -> stringResource(R.string.type_listener_disconnected)
}

@Composable
internal fun classificationLabel(event: br.com.c6tag.diagnostico.diagnostic.DiagnosticEvent): String {
    val resource = when {
        event.type == DiagnosticEventType.ACTIVE_SNAPSHOT -> R.string.classification_snapshot
        event.type == DiagnosticEventType.REMOVED -> R.string.classification_removed
        event.groupSummary == true || event.interpretation?.kind == CandidateKind.GROUP_SUMMARY ->
            R.string.classification_summary

        event.interpretation?.kind == CandidateKind.UNKNOWN -> R.string.classification_unknown
        event.interpretation?.kind == CandidateKind.TAG_DEBIT -> R.string.classification_candidate_debit
        event.interpretation?.kind == CandidateKind.PIX_CREDIT -> R.string.classification_candidate_credit
        else -> R.string.classification_unavailable
    }
    return stringResource(resource)
}
