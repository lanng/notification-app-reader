package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.ThemePreference

@Composable
internal fun MonitorRoot(
    state: MonitorUiState,
    actions: MonitorActions,
    referenceActions: ReferenceActions,
    onEnableNotices: () -> Unit,
    diagnostic: @Composable () -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var diagnosticVisible by rememberSaveable { mutableStateOf(false) }
    var noticeExplanation by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = diagnosticVisible || state.referenceDraft != null || selected != 0) {
        when {
            state.referenceDraft != null -> referenceActions.onCancel()
            diagnosticVisible -> diagnosticVisible = false
            else -> selected = 0
        }
    }
    val routedActions = actions.copy(
        onHistory = { selected = 1 },
        onNoticeExplanation = { noticeExplanation = true },
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                if (diagnosticVisible) TextButton(onClick = { diagnosticVisible = false }) {
                    Text(stringResource(R.string.monitor_back_settings))
                }
            }
        },
        bottomBar = {
            if (!diagnosticVisible && state.referenceDraft == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(R.string.monitor_tab_overview, R.string.monitor_tab_history, R.string.monitor_tab_settings).forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Text(listOf("◉", "≡", "⚙")[index]) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.testTag(listOf("tab-overview", "tab-history", "tab-settings")[index]),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.referenceDraft != null -> BalanceReferenceScreen(
                    state, referenceActions.copy(onOpenCaptureSettings = actions.onOpenCaptureSettings),
                )
                diagnosticVisible -> diagnostic()
                selected == 0 -> OverviewScreen(state, routedActions)
                selected == 1 -> {
                    // Task 8 replaces this bounded preview with pagination, filters and decisions.
                    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Text(stringResource(R.string.monitor_history_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.monitor_history_preview))
                        }
                        if (state.recent.isEmpty()) item { Text(stringResource(R.string.monitor_no_entries)) }
                        items(state.recent, key = { it.id }) { FinancialEntryRow(it) }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("settings-scroll"),
                        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { Text(stringResource(R.string.monitor_tab_settings), style = MaterialTheme.typography.headlineSmall) }
                        item {
                            OutlinedButton(onClick = actions.onReference, enabled = !state.isBusy) {
                                Text(stringResource(R.string.monitor_adjust_balance))
                            }
                        }
                        item {
                            Text(stringResource(R.string.monitor_appearance), style = MaterialTheme.typography.titleMedium)
                            ThemePreference.entries.forEach { theme ->
                                TextButton(onClick = { actions.onTheme(theme) }, enabled = !state.isBusy) {
                                    Text(
                                        (if (state.overview.settings.theme == theme) "✓ " else "") +
                                            stringResource(when (theme) {
                                                ThemePreference.SYSTEM -> R.string.monitor_theme_system
                                                ThemePreference.LIGHT -> R.string.monitor_theme_light
                                                ThemePreference.DARK -> R.string.monitor_theme_dark
                                            }),
                                    )
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { noticeExplanation = true }) { Text(stringResource(R.string.monitor_notices_configure)) }
                            TextButton(onClick = { diagnosticVisible = true }) { Text(stringResource(R.string.monitor_diagnostic)) }
                        }
                    }
                }
            }
        }
    }
    if (noticeExplanation) AlertDialog(
        onDismissRequest = { noticeExplanation = false },
        title = { Text(stringResource(R.string.monitor_notice_explanation_title)) },
        text = { Text(stringResource(R.string.monitor_notice_explanation)) },
        confirmButton = {
            TextButton(onClick = { noticeExplanation = false; onEnableNotices() }) {
                Text(stringResource(R.string.monitor_notice_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { noticeExplanation = false }) { Text(stringResource(R.string.monitor_notice_later)) }
        },
    )
}
