package br.com.c6tag.diagnostico.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.FinancialEntryKind
import br.com.c6tag.diagnostico.monitor.HistoryFilter
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.remote.HostContactState
import br.com.c6tag.diagnostico.remote.SharedAlertSeverity
import br.com.c6tag.diagnostico.remote.hostContactState
import br.com.c6tag.diagnostico.remote.sync.GuestAlertPolicy
import br.com.c6tag.diagnostico.remote.sync.GuestMonitorUiState
import br.com.c6tag.diagnostico.remote.sync.SharedHistoryEntry
import br.com.c6tag.diagnostico.ui.monitor.MonitorSettingsActionRow
import br.com.c6tag.diagnostico.ui.monitor.MonitorSettingsSection
import br.com.c6tag.diagnostico.ui.monitor.NoticeAccess
import br.com.c6tag.diagnostico.ui.monitor.brl
import br.com.c6tag.diagnostico.ui.monitor.localTime

internal data class GuestMonitorActions(
    val onSyncNow: () -> Unit,
    val onAcknowledge: () -> Unit,
    val onResume: () -> Unit,
    val onSelectHistoryFilter: (HistoryFilter) -> Unit,
    val onLoadMoreHistory: () -> Unit,
    val onUpdatePauseMinutes: (Int) -> Unit,
    val onUpdateTheme: (ThemePreference) -> Unit,
    val onEnableNotices: () -> Unit,
    val onOpenNoticeSettings: () -> Unit,
)

@Composable
internal fun GuestMonitorRoot(
    state: GuestMonitorUiState,
    notices: NoticeAccess,
    actions: GuestMonitorActions,
    remoteAccess: @Composable (onClose: () -> Unit) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var remoteAccessVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = remoteAccessVisible || selected != 0) {
        if (remoteAccessVisible) {
            remoteAccessVisible = false
        } else {
            selected = 0
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.guest_mode_badge),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        bottomBar = {
            if (!remoteAccessVisible) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        R.string.monitor_tab_overview,
                        R.string.monitor_tab_history,
                        R.string.monitor_tab_settings,
                    ).forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        listOf(
                                            R.drawable.ic_monitor_overview,
                                            R.drawable.ic_monitor_history,
                                            R.drawable.ic_monitor_settings,
                                        )[index],
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.testTag(
                                listOf("guest-tab-overview", "guest-tab-history", "guest-tab-settings")[index],
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                remoteAccessVisible -> remoteAccess { remoteAccessVisible = false }
                selected == 0 -> GuestOverviewScreen(
                    state = state,
                    notices = notices,
                    actions = actions,
                    onSeeAllHistory = { selected = 1 },
                )

                selected == 1 -> GuestHistoryScreen(state = state, actions = actions)
                else -> GuestSettingsScreen(
                    state = state,
                    notices = notices,
                    actions = actions,
                    onOpenRemoteAccess = { remoteAccessVisible = true },
                )
            }
        }
    }
}

@Composable
private fun GuestOverviewScreen(
    state: GuestMonitorUiState,
    notices: NoticeAccess,
    actions: GuestMonitorActions,
    onSeeAllHistory: () -> Unit,
) {
    val snapshot = state.snapshot
    val nowMs = state.lastEvaluatedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    val policy = GuestAlertPolicy()
    val isAlertActiveForGuest = snapshot != null && policy.isGuestAlertActiveAt(snapshot, nowMs)
    val isLocallyPaused = state.localAlertState.isPausedAt(nowMs)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("guest-overview-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (notices != NoticeAccess.AVAILABLE && notices != NoticeAccess.UNKNOWN) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.guest_notices_banner_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.guest_notices_banner_body),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = actions.onEnableNotices,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.guest_notices_banner_action))
                        }
                    }
                }
            }
        }

        if (state.syncError != null) {
            item {
                Text(
                    text = stringResource(R.string.guest_sync_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (snapshot == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.guest_waiting_first_sync_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.guest_waiting_first_sync_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            item {
                Text(
                    stringResource(R.string.monitor_estimated_balance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = snapshot.balanceCents?.let(::brl)
                        ?: stringResource(R.string.guest_balance_unavailable),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.testTag("guest-balance-value"),
                )
                Text(
                    stringResource(R.string.guest_balance_readonly_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.guest_last_synced, localTime(snapshot.syncedAtMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val contactState = hostContactState(snapshot.syncedAtMs, nowMs)
                if (contactState == HostContactState.STALE) {
                    Text(
                        stringResource(R.string.guest_host_contact_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            item {
                val containerColor = when {
                    snapshot.severity == SharedAlertSeverity.CRITICAL ->
                        MaterialTheme.colorScheme.errorContainer

                    snapshot.severity == SharedAlertSeverity.ATTENTION || snapshot.needsReference ->
                        MaterialTheme.colorScheme.tertiaryContainer

                    else -> MaterialTheme.colorScheme.surface
                }
                val contentColor = when {
                    snapshot.severity == SharedAlertSeverity.CRITICAL ->
                        MaterialTheme.colorScheme.onErrorContainer

                    snapshot.severity == SharedAlertSeverity.ATTENTION || snapshot.needsReference ->
                        MaterialTheme.colorScheme.onTertiaryContainer

                    else -> MaterialTheme.colorScheme.onSurface
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val statusTitleRes = when {
                            snapshot.severity == SharedAlertSeverity.CRITICAL && isLocallyPaused ->
                                R.string.guest_status_critical_paused

                            snapshot.severity == SharedAlertSeverity.CRITICAL ->
                                R.string.guest_status_critical

                            isAlertActiveForGuest && isLocallyPaused ->
                                R.string.guest_status_attention_paused

                            isAlertActiveForGuest ->
                                R.string.guest_status_attention_active

                            snapshot.severity == SharedAlertSeverity.ATTENTION ->
                                R.string.guest_status_attention_host_first

                            else -> R.string.guest_status_normal
                        }
                        Text(
                            text = stringResource(statusTitleRes),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("guest-alert-status"),
                        )
                        if (snapshot.needsReference) {
                            Text(
                                text = stringResource(R.string.guest_needs_reference_note),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.guest_inherited_limit_value,
                                brl(snapshot.limitCents),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.guest_inherited_repeat_value,
                                snapshot.repeatMinutes,
                                snapshot.repeatMinutes,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.guest_inherited_readonly_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        if (isAlertActiveForGuest) {
                            if (isLocallyPaused) {
                                state.localAlertState.pausedUntilMs?.let { untilMs ->
                                    Text(
                                        text = stringResource(
                                            R.string.guest_local_pause_until,
                                            localTime(untilMs),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(
                                    onClick = actions.onResume,
                                    modifier = Modifier.fillMaxWidth().testTag("guest-resume"),
                                ) {
                                    Text(stringResource(R.string.guest_resume_local))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = actions.onAcknowledge,
                                    modifier = Modifier.fillMaxWidth().testTag("guest-acknowledge"),
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.guest_acknowledge_local,
                                            state.localAlertState.pauseMinutes,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = actions.onSyncNow,
                enabled = !state.syncInProgress,
                modifier = Modifier.fillMaxWidth().testTag("guest-sync-now"),
            ) {
                Text(
                    stringResource(
                        if (state.syncInProgress) {
                            R.string.guest_syncing
                        } else {
                            R.string.guest_sync_now
                        },
                    ),
                )
            }
        }

        if (snapshot != null) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.monitor_recent),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSeeAllHistory) {
                        Text(stringResource(R.string.monitor_see_all))
                    }
                }
            }
            if (state.history.isEmpty()) {
                item { Text(stringResource(R.string.monitor_no_entries)) }
            } else {
                items(state.history.take(5), key = { it.hostEntryId }) { entry ->
                    SharedHistoryEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun GuestHistoryScreen(
    state: GuestMonitorUiState,
    actions: GuestMonitorActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("guest-history-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.guest_history_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.guest_history_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.historyFilter == filter,
                        onClick = { actions.onSelectHistoryFilter(filter) },
                        label = {
                            Text(
                                stringResource(
                                    when (filter) {
                                        HistoryFilter.ALL -> R.string.monitor_filter_all
                                        HistoryFilter.ENTRIES -> R.string.monitor_filter_entries
                                        HistoryFilter.EXITS -> R.string.monitor_filter_exits
                                        HistoryFilter.ADJUSTMENTS -> R.string.monitor_filter_adjustments
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.testTag("guest-filter-${filter.name.lowercase()}"),
                    )
                }
            }
        }

        if (state.historyError) {
            item {
                Text(
                    stringResource(R.string.guest_history_error),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = actions.onSyncNow) {
                    Text(stringResource(R.string.monitor_retry))
                }
            }
        }

        if (state.historyLoading && state.history.isEmpty()) {
            item { Text(stringResource(R.string.monitor_history_loading)) }
        } else if (!state.historyLoading && state.history.isEmpty()) {
            item { Text(stringResource(R.string.monitor_no_entries)) }
        }

        items(state.history, key = { it.hostEntryId }) { entry ->
            SharedHistoryEntryRow(entry)
        }

        if (state.historyHasMore) {
            item {
                Button(
                    onClick = actions.onLoadMoreHistory,
                    enabled = !state.historyLoading,
                    modifier = Modifier.fillMaxWidth().testTag("guest-load-more"),
                ) {
                    Text(
                        stringResource(
                            if (state.historyLoading) {
                                R.string.monitor_history_loading
                            } else {
                                R.string.monitor_load_previous
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuestSettingsScreen(
    state: GuestMonitorUiState,
    notices: NoticeAccess,
    actions: GuestMonitorActions,
    onOpenRemoteAccess: () -> Unit,
) {
    val snapshot = state.snapshot

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("guest-settings-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.monitor_tab_settings),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        item {
            MonitorSettingsSection(
                title = stringResource(R.string.guest_settings_inherited_section),
                modifier = Modifier.testTag("guest-settings-inherited"),
            ) {
                if (snapshot != null) {
                    Text(
                        stringResource(R.string.guest_inherited_limit_value, brl(snapshot.limitCents)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        pluralStringResource(
                            R.plurals.guest_inherited_repeat_value,
                            snapshot.repeatMinutes,
                            snapshot.repeatMinutes,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.guest_waiting_first_sync_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    stringResource(R.string.guest_settings_inherited_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            MonitorSettingsSection(
                title = stringResource(R.string.guest_settings_local_pause_section),
                modifier = Modifier.testTag("guest-settings-pause"),
            ) {
                Text(
                    stringResource(R.string.guest_settings_local_pause_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GuestAlertPolicy.SUPPORTED_PAUSE_MINUTES.sorted().forEach { minutes ->
                        FilterChip(
                            selected = state.localAlertState.pauseMinutes == minutes,
                            onClick = { actions.onUpdatePauseMinutes(minutes) },
                            label = {
                                Text(stringResource(R.string.monitor_settings_minutes, minutes))
                            },
                            modifier = Modifier.testTag("guest-pause-$minutes"),
                        )
                    }
                }
            }
        }

        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_appearance),
                modifier = Modifier.testTag("guest-settings-appearance"),
            ) {
                Column(Modifier.selectableGroup()) {
                    ThemePreference.entries.forEach { theme ->
                        val selected = state.theme == theme
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { actions.onUpdateTheme(theme) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(
                                    when (theme) {
                                        ThemePreference.SYSTEM -> R.string.monitor_theme_system
                                        ThemePreference.LIGHT -> R.string.monitor_theme_light
                                        ThemePreference.DARK -> R.string.monitor_theme_dark
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_settings_group_access),
                modifier = Modifier.testTag("guest-settings-access"),
            ) {
                MonitorSettingsActionRow(
                    label = stringResource(R.string.remote_settings_entry),
                    onClick = onOpenRemoteAccess,
                    tag = "guest-open-remote-access",
                )
                Text(
                    text = stringResource(
                        when (notices) {
                            NoticeAccess.UNKNOWN -> R.string.monitor_notices_unknown
                            NoticeAccess.DENIED -> R.string.monitor_notices_denied
                            NoticeAccess.CHANNEL_BLOCKED -> R.string.monitor_notices_blocked
                            NoticeAccess.SILENT -> R.string.monitor_notices_silent
                            NoticeAccess.AVAILABLE -> R.string.monitor_notices_available
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MonitorSettingsActionRow(
                    label = stringResource(R.string.monitor_notice_settings_action),
                    onClick = actions.onOpenNoticeSettings,
                    tag = "guest-notice-settings",
                )
            }
        }
    }
}

@Composable
private fun SharedHistoryEntryRow(entry: SharedHistoryEntry) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(
                when (entry.kind) {
                    FinancialEntryKind.REFERENCE -> R.string.monitor_reference_adjustment
                    FinancialEntryKind.TAG_DEBIT -> R.string.monitor_tag_debit
                    FinancialEntryKind.PIX_CREDIT -> R.string.monitor_pix_credit
                },
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            when (entry.kind) {
                FinancialEntryKind.TAG_DEBIT -> "− " + brl(kotlin.math.abs(entry.amountCents))
                FinancialEntryKind.PIX_CREDIT -> "+ " + brl(kotlin.math.abs(entry.amountCents))
                FinancialEntryKind.REFERENCE -> brl(entry.amountCents)
            },
        )
        Text(
            stringResource(R.string.monitor_financial_balance_after, brl(entry.balanceAfterCents)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            localTime(entry.recordedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
