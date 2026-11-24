package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.BuildConfig
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.remote.probe.DebugAlertProbeEntry

@Composable
internal fun MonitorSettingsScreen(
    state: MonitorUiState,
    actions: MonitorActions,
    onOpenRemoteAccess: () -> Unit = {},
) {
    val draft = state.settingsDraft ?: MonitorSettingsDraft(
        limit = brlEditable(state.overview.settings.limitCents),
        repeatMinutes = state.overview.settings.alerts.repeatMinutes,
        pauseMinutes = state.overview.settings.alerts.pauseMinutes,
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().testTag("settings-scroll"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(stringResource(R.string.monitor_tab_settings), style = MaterialTheme.typography.headlineSmall) }
        item { TextButton(onClick = actions.onDiagnostic) { Text(stringResource(R.string.monitor_diagnostic)) } }
        item {
            TextButton(
                onClick = onOpenRemoteAccess,
                modifier = Modifier.testTag("open-remote-access"),
            ) {
                Text(stringResource(R.string.remote_settings_entry))
            }
        }
        item {
            Column {
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
        }
        item {
            TextButton(onClick = actions.onReference, enabled = !state.isBusy) {
                Text(stringResource(R.string.monitor_adjust_balance))
            }
        }
        item {
            BrlMoneyField(
                value = draft.limit,
                onValueChange = actions.onSettingsLimit,
                label = { Text(stringResource(R.string.monitor_settings_limit)) },
                supportingText = {
                    Text(stringResource(
                        if (draft.moneyInputError) R.string.monitor_money_input_invalid
                        else R.string.monitor_settings_limit_help,
                    ))
                },
                isError = draft.invalid || draft.moneyInputError,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("settings-limit"),
            )
            if (draft.invalid) Text(stringResource(R.string.monitor_settings_invalid), color = MaterialTheme.colorScheme.error)
        }
        item {
            Text(stringResource(R.string.monitor_settings_repeat), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = SUPPORTED_REPEAT_MINUTES,
                selected = draft.repeatMinutes,
                tagPrefix = "repeat",
                label = { minutes -> stringResource(R.string.monitor_settings_minutes, minutes) },
                onSelect = actions.onSettingsRepeat,
                enabled = !state.isBusy,
            )
        }
        item {
            Text(stringResource(R.string.monitor_settings_pause), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                choices = SUPPORTED_PAUSE_MINUTES,
                selected = draft.pauseMinutes,
                tagPrefix = "pause",
                label = { minutes -> stringResource(R.string.monitor_settings_minutes, minutes) },
                onSelect = actions.onSettingsPause,
                enabled = !state.isBusy,
            )
        }
        item {
            Button(
                onClick = actions.onSaveSettings,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag("save-settings"),
            ) { Text(stringResource(R.string.monitor_settings_save)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(stringResource(R.string.monitor_notice_settings_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(when (state.notices) {
                NoticeAccess.UNKNOWN -> R.string.monitor_notices_unknown
                NoticeAccess.DENIED -> R.string.monitor_notices_denied
                NoticeAccess.CHANNEL_BLOCKED -> R.string.monitor_notices_blocked
                NoticeAccess.SILENT -> R.string.monitor_notices_silent
                NoticeAccess.AVAILABLE -> R.string.monitor_notices_available
            }))
            Text(stringResource(R.string.monitor_notices_limitations), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = actions.onOpenNoticeSettings) {
                Text(stringResource(R.string.monitor_notice_settings_action))
            }
            state.noticeSettingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Text(stringResource(R.string.monitor_capture), style = MaterialTheme.typography.titleMedium)
            MonitorCaptureStatus(state, actions)
        }
        item { TextButton(onClick = actions.onHistory) { Text(stringResource(R.string.monitor_history_title)) } }
        if (BuildConfig.DEBUG) {
            item { DebugAlertProbeEntry.Content() }
        }
    }
}

@Composable
private fun ChoiceRow(
    choices: List<Int>,
    selected: Int,
    tagPrefix: String,
    label: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(3).forEach { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { value ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(value) },
                        enabled = enabled,
                        label = { Text(label(value)) },
                        modifier = Modifier.testTag("$tagPrefix-$value"),
                    )
                }
            }
        }
    }
}

private val SUPPORTED_REPEAT_MINUTES = listOf(15, 30, 60)
private val SUPPORTED_PAUSE_MINUTES = listOf(15, 30, 60, 120)
