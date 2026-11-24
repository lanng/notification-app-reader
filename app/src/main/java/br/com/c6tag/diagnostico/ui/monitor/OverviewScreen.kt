package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.capture.CaptureConnectionState
import br.com.c6tag.diagnostico.monitor.FinancialEntry
import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.ui.AuthorizationStatus

@Composable
internal fun OverviewScreen(state: MonitorUiState, actions: MonitorActions) {
    val overview = state.overview
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("overview-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.isLoading) {
            item { Text(stringResource(R.string.monitor_loading)) }
        }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onRetry, enabled = !state.isBusy) { Text(stringResource(R.string.monitor_retry)) }
            }
        }
        if (!state.isLoading && overview.reference == null) {
            item {
                Text(stringResource(R.string.monitor_setup_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.monitor_setup_explanation))
                Button(onClick = actions.onReference, enabled = !state.isBusy) {
                    Text(stringResource(R.string.monitor_setup_action))
                }
            }
        } else if (overview.reference != null) {
            item {
                Text(stringResource(R.string.monitor_estimated_balance), color = MaterialTheme.colorScheme.onSurfaceVariant)
                overview.balanceCents?.let {
                    Text(brl(it), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("balance-value"))
                }
                Text(stringResource(R.string.monitor_balance_note), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    overview.lastObservedAtMs?.let { stringResource(R.string.monitor_last_observed, localTime(it)) }
                        ?: stringResource(R.string.monitor_no_observation),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                val cycle = overview.alertCycle
                val low = (overview.balanceCents ?: Long.MAX_VALUE) < overview.settings.limitCents
                val attention = overview.needsReference || low
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (attention) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (attention) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                )) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (overview.needsReference) {
                            Text(stringResource(R.string.monitor_needs_reference), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.monitor_review_explanation))
                            if (overview.pendingCount > 0) Text(pluralStringResource(
                                R.plurals.monitor_pending_count,
                                overview.pendingCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), overview.pendingCount,
                            ))
                        }
                        Text(stringResource(when {
                            cycle?.status == AlertStatus.PAUSED -> R.string.monitor_paused
                            low -> R.string.monitor_low
                            overview.needsReference -> R.string.monitor_limit_label
                            else -> R.string.monitor_no_known_pending
                        }), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.monitor_limit_value, brl(overview.settings.limitCents)))
                        if (cycle?.status == AlertStatus.PAUSED) {
                            cycle.pauseUntilMs?.let { Text(stringResource(R.string.monitor_pause_expected, localTime(it))) }
                            OutlinedButton(
                                onClick = { actions.onResume(cycle.id) }, enabled = !state.isBusy,
                                modifier = Modifier.testTag("resume"),
                            ) { Text(stringResource(R.string.monitor_resume)) }
                        } else if (cycle?.status == AlertStatus.ACTIVE) {
                            Text(pluralStringResource(
                                R.plurals.monitor_repeat_expected,
                                overview.settings.alerts.repeatMinutes, overview.settings.alerts.repeatMinutes,
                            ))
                            OutlinedButton(
                                onClick = { actions.onAcknowledge(cycle.id) }, enabled = !state.isBusy,
                                modifier = Modifier.testTag("acknowledge"),
                            ) { Text(stringResource(R.string.monitor_acknowledge, overview.settings.alerts.pauseMinutes)) }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = actions.onReference, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.monitor_adjust_balance))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.monitor_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onHistory) { Text(stringResource(R.string.monitor_see_all)) }
                }
            }
            if (state.recent.isEmpty()) item { Text(stringResource(R.string.monitor_no_entries)) }
            items(state.recent, key = { it.id }) { FinancialEntryRow(it) }
        }
        item {
            MonitorCaptureStatus(state, actions)
        }
        item {
            Text(stringResource(when (state.notices) {
                NoticeAccess.UNKNOWN -> R.string.monitor_notices_unknown
                NoticeAccess.DENIED -> R.string.monitor_notices_denied
                NoticeAccess.CHANNEL_BLOCKED -> R.string.monitor_notices_blocked
                NoticeAccess.SILENT -> R.string.monitor_notices_silent
                NoticeAccess.AVAILABLE -> R.string.monitor_notices_available
            }), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.monitor_notices_limitations), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions.onNoticeExplanation) { Text(stringResource(R.string.monitor_notices_configure)) }
        }
    }
}

@Composable
internal fun MonitorCaptureStatus(state: MonitorUiState, actions: MonitorActions) {
    Text(stringResource(R.string.monitor_capture), style = MaterialTheme.typography.titleSmall)
    state.captureSettingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text(stringResource(when (state.authorization) {
        AuthorizationStatus.AUTHORIZED -> R.string.monitor_capture_authorized
        AuthorizationStatus.NOT_AUTHORIZED -> R.string.monitor_capture_denied
        AuthorizationStatus.CHECK_PENDING -> R.string.access_pending
        AuthorizationStatus.CHECK_FAILED -> R.string.access_check_failed
    }))
    if (!state.capture.targetPackageConfigured) Text(stringResource(R.string.target_not_configured))
    Text(stringResource(when (state.capture.connection) {
        CaptureConnectionState.CONNECTED -> R.string.connection_connected
        CaptureConnectionState.DISCONNECTED -> R.string.connection_disconnected
        CaptureConnectionState.UNKNOWN -> R.string.connection_unknown
    }), style = MaterialTheme.typography.bodySmall)
    if (state.authorization != AuthorizationStatus.AUTHORIZED) {
        OutlinedButton(
            onClick = actions.onOpenCaptureSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                stringResource(R.string.monitor_capture_authorize),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
internal fun FinancialEntryRow(entry: FinancialEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(when (entry.kind) {
            FinancialEntryKind.REFERENCE -> if (entry.referenceId == 1L) R.string.monitor_initial_reference else R.string.monitor_reference_adjustment
            FinancialEntryKind.TAG_DEBIT -> R.string.monitor_tag_debit
            FinancialEntryKind.PIX_CREDIT -> R.string.monitor_pix_credit
        }), style = MaterialTheme.typography.titleSmall)
        Text(
            (when (entry.kind) {
                FinancialEntryKind.TAG_DEBIT -> "− "
                FinancialEntryKind.PIX_CREDIT -> "+ "
                FinancialEntryKind.REFERENCE -> ""
            }) + brl(entry.amountCents),
        )
        Text(localTime(entry.recordedAtMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
