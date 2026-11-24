package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(R.string.monitor_tab_settings), style = MaterialTheme.typography.headlineSmall)
        }
        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_settings_group_access),
                modifier = Modifier.testTag("settings-monitor-access"),
            ) {
                MonitorSettingsActionRow(
                    label = stringResource(R.string.remote_settings_entry),
                    onClick = onOpenRemoteAccess,
                    tag = "open-remote-access",
                )
                MonitorSettingsActionRow(
                    label = stringResource(R.string.monitor_adjust_balance),
                    onClick = actions.onReference,
                    enabled = !state.isBusy,
                    tag = "settings-reference",
                )
            }
        }
        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_appearance),
                modifier = Modifier.testTag("settings-appearance"),
            ) {
                Column(Modifier.selectableGroup()) {
                    ThemePreference.entries.forEach { theme ->
                        val selected = state.overview.settings.theme == theme
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .selectable(
                                    selected = selected,
                                    enabled = !state.isBusy,
                                    role = Role.RadioButton,
                                    onClick = { actions.onTheme(theme) },
                                )
                                .padding(vertical = 4.dp)
                                .testTag(themeTag(theme)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = !state.isBusy,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(themeLabel(theme)))
                        }
                    }
                }
            }
        }
        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_settings_group_alerts),
                modifier = Modifier.testTag("settings-local-alerts"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (draft.invalid) {
                        Text(stringResource(R.string.monitor_settings_invalid), color = MaterialTheme.colorScheme.error)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.monitor_settings_repeat), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        choices = SUPPORTED_REPEAT_MINUTES,
                        selected = draft.repeatMinutes,
                        tagPrefix = "repeat",
                        label = { minutes -> stringResource(R.string.monitor_settings_minutes, minutes) },
                        onSelect = actions.onSettingsRepeat,
                        enabled = !state.isBusy,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.monitor_settings_pause), style = MaterialTheme.typography.titleSmall)
                    ChoiceRow(
                        choices = SUPPORTED_PAUSE_MINUTES,
                        selected = draft.pauseMinutes,
                        tagPrefix = "pause",
                        label = { minutes -> stringResource(R.string.monitor_settings_minutes, minutes) },
                        onSelect = actions.onSettingsPause,
                        enabled = !state.isBusy,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions.onSaveSettings,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("save-settings"),
                    ) {
                        Text(stringResource(R.string.monitor_settings_save))
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            MonitorSettingsSection(
                title = stringResource(R.string.monitor_settings_group_permissions),
                modifier = Modifier.testTag("settings-permissions"),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.monitor_notice_settings_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(noticeStatusLabel(state.notices)))
                    Text(stringResource(R.string.monitor_notices_limitations), style = MaterialTheme.typography.bodySmall)
                    MonitorSettingsActionRow(
                        label = stringResource(R.string.monitor_notice_settings_action),
                        onClick = actions.onOpenNoticeSettings,
                        tag = "settings-notice-settings",
                    )
                    state.noticeSettingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonitorCaptureStatus(state, actions)
                }
                MonitorSettingsActionRow(
                    label = stringResource(R.string.monitor_diagnostic),
                    onClick = actions.onDiagnostic,
                    tag = "settings-diagnostic",
                )
                if (BuildConfig.DEBUG) {
                    DebugAlertProbeEntry.Content()
                }
            }
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
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { value ->
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

private fun themeLabel(theme: ThemePreference): Int =
    when (theme) {
        ThemePreference.SYSTEM -> R.string.monitor_theme_system
        ThemePreference.LIGHT -> R.string.monitor_theme_light
        ThemePreference.DARK -> R.string.monitor_theme_dark
    }

private fun themeTag(theme: ThemePreference): String =
    when (theme) {
        ThemePreference.SYSTEM -> "theme-system"
        ThemePreference.LIGHT -> "theme-light"
        ThemePreference.DARK -> "theme-dark"
    }

private fun noticeStatusLabel(notices: NoticeAccess): Int =
    when (notices) {
        NoticeAccess.UNKNOWN -> R.string.monitor_notices_unknown
        NoticeAccess.DENIED -> R.string.monitor_notices_denied
        NoticeAccess.CHANNEL_BLOCKED -> R.string.monitor_notices_blocked
        NoticeAccess.SILENT -> R.string.monitor_notices_silent
        NoticeAccess.AVAILABLE -> R.string.monitor_notices_available
    }

private val SUPPORTED_REPEAT_MINUTES = listOf(15, 30, 60)
private val SUPPORTED_PAUSE_MINUTES = listOf(15, 30, 60, 120)
