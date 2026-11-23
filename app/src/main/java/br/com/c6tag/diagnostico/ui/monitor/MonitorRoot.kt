package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R

@Composable
internal fun MonitorRoot(
    state: MonitorUiState,
    actions: MonitorActions,
    referenceActions: ReferenceActions,
    onEnableNotices: () -> Unit,
    diagnostic: @Composable () -> Unit,
    onOpenRemoteAccess: () -> Unit = {},
    remoteAccess: @Composable (onClose: () -> Unit) -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var diagnosticVisible by rememberSaveable { mutableStateOf(false) }
    var remoteAccessVisible by rememberSaveable { mutableStateOf(false) }
    var pendingVisible by rememberSaveable { mutableStateOf(false) }
    var noticeExplanation by rememberSaveable { mutableStateOf(false) }
    BackHandler(
        enabled = diagnosticVisible || remoteAccessVisible || pendingVisible ||
            state.referenceDraft != null || selected != 0,
    ) {
        when {
            state.referenceDraft != null -> referenceActions.onCancel()
            diagnosticVisible -> diagnosticVisible = false
            remoteAccessVisible -> remoteAccessVisible = false
            pendingVisible && state.pendingSelection?.confirmation != null -> actions.onDismissPendingDecision()
            pendingVisible && state.pendingSelection != null -> actions.onClosePending()
            pendingVisible -> pendingVisible = false
            selected == 1 && state.financialSelection != null -> actions.onCloseFinancialEntry()
            else -> selected = 0
        }
    }
    val routedActions = actions.copy(
        onHistory = {
            selected = 1
            pendingVisible = false
            actions.onHistory()
        },
        onPendingReview = {
            selected = 1
            pendingVisible = true
            actions.onPendingReview()
        },
        onOpenFinancialEntry = actions.onOpenFinancialEntry,
        onDiagnostic = { diagnosticVisible = true },
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
                if (remoteAccessVisible) TextButton(onClick = { remoteAccessVisible = false }) {
                    Text(stringResource(R.string.monitor_back_settings))
                }
                if (pendingVisible) TextButton(
                    onClick = {
                        when {
                            state.pendingSelection?.confirmation != null -> actions.onDismissPendingDecision()
                            state.pendingSelection != null -> actions.onClosePending()
                            else -> pendingVisible = false
                        }
                    },
                ) {
                    Text(stringResource(if (state.pendingSelection == null) R.string.monitor_back_history else R.string.monitor_pending_back))
                }
                if (selected == 1 && state.financialSelection != null && !pendingVisible) {
                    TextButton(onClick = actions.onCloseFinancialEntry) {
                        Text(stringResource(R.string.monitor_back_history))
                    }
                }
            }
        },
        bottomBar = {
            if (!diagnosticVisible && !remoteAccessVisible && !pendingVisible && state.financialSelection == null &&
                state.referenceDraft == null
            ) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(R.string.monitor_tab_overview, R.string.monitor_tab_history, R.string.monitor_tab_settings).forEachIndexed { index, label ->
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
                                modifier = Modifier.size(28.dp).testTag(
                                    listOf("tab-icon-overview", "tab-icon-history", "tab-icon-settings")[index],
                                ),
                            )
                        },
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
                remoteAccessVisible -> remoteAccess { remoteAccessVisible = false }
                pendingVisible -> PendingReviewScreen(state, routedActions)
                selected == 0 -> OverviewScreen(state, routedActions)
                selected == 1 -> FinancialHistoryScreen(state, routedActions)
                else -> MonitorSettingsScreen(
                    state = state,
                    actions = routedActions,
                    onOpenRemoteAccess = {
                        onOpenRemoteAccess()
                        remoteAccessVisible = true
                    },
                )
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
