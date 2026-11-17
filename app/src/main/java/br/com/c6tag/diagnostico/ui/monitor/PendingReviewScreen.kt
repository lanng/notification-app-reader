package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.MovementKind
import br.com.c6tag.diagnostico.monitor.PendingChoice
import br.com.c6tag.diagnostico.monitor.PendingMovement
import br.com.c6tag.diagnostico.monitor.PendingReason
import br.com.c6tag.diagnostico.monitor.PendingStatus

@Composable
internal fun PendingReviewScreen(state: MonitorUiState, actions: MonitorActions) {
    val selection = state.pendingSelection
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("pending-review"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(if (selection == null) R.string.monitor_pending_title else R.string.monitor_pending_detail_title),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        if (selection == null) {
            item { Text(stringResource(R.string.monitor_pending_explanation)) }
            if (state.pendingLoading && state.pending.isEmpty()) item { Text(stringResource(R.string.monitor_history_loading)) }
            state.pendingError?.let {
                item {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.onRetryPending) { Text(stringResource(R.string.monitor_retry)) }
                }
            }
            if (!state.pendingLoading && state.pendingError == null && state.pending.isEmpty()) {
                item { Text(stringResource(R.string.monitor_pending_empty)) }
            }
            items(state.pending, key = { it.id }) { movement ->
                PendingMovementRow(movement) { actions.onOpenPending(movement.id) }
            }
            if (state.pendingHasMore) {
                item {
                    Button(
                        onClick = actions.onLoadMorePending,
                        enabled = !state.pendingLoading,
                        modifier = Modifier.fillMaxWidth().testTag("load-more-pending"),
                    ) {
                        Text(stringResource(if (state.pendingLoading) R.string.monitor_history_loading else R.string.monitor_load_previous))
                    }
                }
            }
        } else {
            item {
                PendingDetail(selection, onClose = actions.onClosePending)
            }
            if (state.overview.referenceSession != null) {
                item { Text(stringResource(R.string.monitor_pending_wait_reference)) }
            }
            item {
                if (!selection.stale && selection.movement.status == PendingStatus.OPEN &&
                    selection.expectedReferenceId != null && state.overview.referenceSession == null
                ) {
                    if (canConfirmPending(selection.movement)) {
                        Button(
                            onClick = { actions.onRequestPendingDecision(PendingChoice.CONFIRM_NEW) },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().testTag("pending-confirm-new"),
                        ) { Text(stringResource(R.string.monitor_pending_confirm_new)) }
                    }
                    OutlinedButton(
                        onClick = { actions.onRequestPendingDecision(PendingChoice.DO_NOT_COUNT) },
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("pending-do-not-count"),
                    ) { Text(stringResource(R.string.monitor_pending_do_not_count)) }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    selection?.confirmation?.let { choice ->
        AlertDialog(
            onDismissRequest = actions.onDismissPendingDecision,
            title = {
                Text(stringResource(if (choice == PendingChoice.CONFIRM_NEW) {
                    R.string.monitor_pending_confirm_title
                } else {
                    R.string.monitor_pending_ignore_title
                }))
            },
            text = {
                Text(stringResource(if (choice == PendingChoice.CONFIRM_NEW) {
                    R.string.monitor_pending_confirm_message
                } else {
                    R.string.monitor_pending_ignore_message
                }))
            },
            confirmButton = {
                TextButton(
                    onClick = actions.onConfirmPendingDecision,
                    enabled = !state.isBusy,
                    modifier = Modifier.testTag("pending-confirm-decision"),
                ) {
                    Text(stringResource(if (choice == PendingChoice.CONFIRM_NEW) {
                        R.string.monitor_pending_confirm_new
                    } else {
                        R.string.monitor_pending_do_not_count
                    }))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissPendingDecision, enabled = !state.isBusy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PendingMovementRow(movement: PendingMovement, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)
            .testTag("pending-row-${movement.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.monitor_pending_local_id, movement.id), style = MaterialTheme.typography.titleSmall)
        Text(pendingSummary(movement))
        Text(stringResource(R.string.monitor_pending_reason, pendingReason(movement.reason)))
        Text(localTime(movement.createdAtMs), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PendingDetail(selection: PendingReviewSelection, onClose: () -> Unit) {
    val movement = selection.movement
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pendingSummary(movement), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.monitor_pending_local_id, movement.id))
        Text(stringResource(R.string.monitor_pending_source_id, movement.sourceId))
        movement.referenceId?.let { Text(stringResource(R.string.monitor_pending_reference_id, it)) }
        Text(stringResource(R.string.monitor_pending_reason, pendingReason(movement.reason)))
        Text(localTime(movement.createdAtMs), style = MaterialTheme.typography.bodySmall)
        if (movement.sourceAlreadyApplied) Text(
            stringResource(R.string.monitor_pending_already_included),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            stringResource(
                if (movement.rawEvidenceAvailable) R.string.monitor_pending_raw_available
                else R.string.monitor_pending_raw_missing,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selection.expectedReferenceId == null) {
            Text(stringResource(R.string.monitor_pending_requires_reference))
        } else {
            Text(stringResource(R.string.monitor_pending_expected_reference, selection.expectedReferenceId))
        }
        if (selection.stale) Text(
            stringResource(R.string.monitor_pending_stale),
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onClose, modifier = Modifier.testTag("close-pending-detail")) {
            Text(stringResource(R.string.monitor_pending_back))
        }
    }
}

@Composable
private fun pendingSummary(movement: PendingMovement): String {
    val kind = when (movement.value.kind) {
        MovementKind.TAG_DEBIT -> stringResource(R.string.monitor_tag_debit)
        MovementKind.PIX_CREDIT -> stringResource(R.string.monitor_pix_credit)
        null -> stringResource(R.string.monitor_pending_kind_unknown)
    }
    val amount = movement.value.amountCents?.let(::brl)
        ?: stringResource(R.string.monitor_pending_amount_unknown)
    return stringResource(R.string.monitor_pending_summary, kind, amount)
}

@Composable
private fun pendingReason(reason: PendingReason): String = stringResource(when (reason) {
    PendingReason.INVALID_AMOUNT -> R.string.monitor_pending_reason_invalid_amount
    PendingReason.INSUFFICIENT_IDENTITY -> R.string.monitor_pending_reason_identity
    PendingReason.CONFLICTING_PUBLICATION -> R.string.monitor_pending_reason_conflict
    PendingReason.REUSED_KEY -> R.string.monitor_pending_reason_reused
    PendingReason.IDENTICAL_CONTENT -> R.string.monitor_pending_reason_duplicate
    PendingReason.UNCERTAIN_BOUNDARY -> R.string.monitor_pending_reason_boundary
    PendingReason.REFERENCE_IN_PROGRESS -> R.string.monitor_pending_reason_reference_open
    PendingReason.CONCURRENT_REFERENCE -> R.string.monitor_pending_reason_concurrent
    PendingReason.AMOUNT_OVERFLOW -> R.string.monitor_pending_reason_overflow
})

private fun canConfirmPending(movement: PendingMovement): Boolean =
    movement.status == PendingStatus.OPEN && !movement.sourceAlreadyApplied &&
        movement.value.kind != null && movement.value.amountCents?.let { it >= 0L } == true
