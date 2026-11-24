package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R

@Composable
internal fun BalanceReferenceScreen(state: MonitorUiState, actions: ReferenceActions) {
    val draft = state.referenceDraft ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("reference-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.monitor_reference_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.monitor_reference_explanation))
        }
        item {
            MonitorCaptureStatus(state, MonitorActions(onOpenCaptureSettings = actions.onOpenCaptureSettings))
        }
        item {
            BrlMoneyField(
                value = draft.balance,
                onValueChange = actions.onBalance,
                label = { Text(stringResource(R.string.monitor_reference_balance_input)) },
                supportingText = {
                    Text(stringResource(
                        if (draft.balanceInputError) R.string.monitor_money_input_invalid
                        else R.string.monitor_reference_money_help,
                    ))
                },
                isError = draft.invalidInput || draft.balanceInputError,
                enabled = !state.isBusy && !draft.sessionUnavailable,
                identityKey = draft.session.id,
                modifier = Modifier.fillMaxWidth().testTag("reference-balance"),
            )
        }
        item {
            BrlMoneyField(
                value = draft.limit,
                onValueChange = actions.onLimit,
                label = { Text(stringResource(R.string.monitor_reference_limit_input)) },
                supportingText = {
                    if (draft.limitInputError) {
                        Text(stringResource(R.string.monitor_money_input_invalid))
                    } else {
                        Text(stringResource(R.string.monitor_settings_limit_help))
                    }
                },
                isError = draft.invalidInput || draft.limitInputError,
                enabled = !state.isBusy && !draft.sessionUnavailable,
                identityKey = draft.session.id,
                modifier = Modifier.fillMaxWidth().testTag("reference-limit"),
            )
        }
        item {
            Text(stringResource(R.string.monitor_reference_boundary, localTime(draft.session.openedAtMs)))
            Text(stringResource(R.string.monitor_reference_concurrent))
            if (state.overview.pendingCount > 0) Text(pluralStringResource(
                R.plurals.monitor_reference_pending,
                state.overview.pendingCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), state.overview.pendingCount,
            ))
            if (draft.invalidInput) Text(stringResource(R.string.monitor_reference_invalid), color = MaterialTheme.colorScheme.error)
            if (draft.sessionUnavailable) Text(stringResource(R.string.monitor_reference_expired), color = MaterialTheme.colorScheme.error)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(
                onClick = actions.onReview, enabled = !state.isBusy && !draft.sessionUnavailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.monitor_reference_review)) }
            OutlinedButton(onClick = actions.onCancel, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.monitor_reference_cancel))
            }
        }
    }
    if (draft.confirmationVisible) {
        AlertDialog(
            onDismissRequest = actions.onDismissConfirmation,
            title = { Text(stringResource(R.string.monitor_reference_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    parseBrlInput(draft.balance)?.let { Text(stringResource(R.string.monitor_reference_new_balance, brl(it))) }
                    Text(stringResource(R.string.monitor_reference_not_credit))
                    Text(stringResource(R.string.monitor_reference_concurrent))
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirm, enabled = !state.isBusy) { Text(stringResource(R.string.monitor_reference_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissConfirmation, enabled = !state.isBusy) { Text(stringResource(R.string.monitor_reference_edit)) }
            },
        )
    }
}
