package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.HistoryFilter

@Composable
internal fun FinancialHistoryScreen(state: MonitorUiState, actions: MonitorActions) {
    state.financialSelection?.let { entry ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("financial-history-detail"),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.monitor_financial_detail_title), style = MaterialTheme.typography.headlineSmall)
                    FinancialEntryRow(entry)
                    Text(stringResource(R.string.monitor_financial_local_id, entry.id))
                    Text(stringResource(R.string.monitor_financial_balance_after, brl(entry.balanceAfterCents)))
                    Text(stringResource(R.string.monitor_financial_reference_id, entry.referenceId))
                    entry.sourceId?.let { Text(stringResource(R.string.monitor_financial_source_id, it)) }
                    entry.observationId?.let { Text(stringResource(R.string.monitor_financial_observation_id, it)) }
                    TextButton(onClick = actions.onCloseFinancialEntry, modifier = Modifier.testTag("close-financial-detail")) {
                        Text(stringResource(R.string.monitor_back_history))
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("financial-history"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.monitor_history_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.monitor_history_order), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.historyFilter == filter,
                        onClick = { actions.onHistoryFilter(filter) },
                        label = {
                            Text(stringResource(when (filter) {
                                HistoryFilter.ALL -> R.string.monitor_filter_all
                                HistoryFilter.ENTRIES -> R.string.monitor_filter_entries
                                HistoryFilter.EXITS -> R.string.monitor_filter_exits
                                HistoryFilter.ADJUSTMENTS -> R.string.monitor_filter_adjustments
                            }))
                        },
                        modifier = Modifier.testTag("history-filter-${filter.name.lowercase()}"),
                    )
                }
            }
        }
        item {
            val count = state.overview.pendingCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            TextButton(onClick = actions.onPendingReview, modifier = Modifier.testTag("open-pending-review")) {
                Text(pluralStringResource(R.plurals.monitor_open_pending, count, state.overview.pendingCount))
            }
        }
        if (state.historyLoading && state.history.isEmpty()) {
            item { Text(stringResource(R.string.monitor_history_loading)) }
        }
        state.historyError?.let {
            item {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onRetryHistory) { Text(stringResource(R.string.monitor_retry)) }
            }
        }
        if (!state.historyLoading && state.historyError == null && state.history.isEmpty()) {
            item { Text(stringResource(R.string.monitor_no_entries)) }
        }
        items(state.history, key = { it.id }) { entry ->
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clickable { actions.onOpenFinancialEntry(entry.id) }
                    .testTag("financial-row-${entry.id}"),
            ) {
                FinancialEntryRow(entry)
            }
        }
        if (state.historyHasMore) {
            item {
                Button(
                    onClick = actions.onLoadMoreHistory,
                    enabled = !state.historyLoading,
                    modifier = Modifier.fillMaxWidth().testTag("load-more-history"),
                ) {
                    Text(stringResource(if (state.historyLoading) R.string.monitor_history_loading else R.string.monitor_load_previous))
                }
            }
        }
    }
}
