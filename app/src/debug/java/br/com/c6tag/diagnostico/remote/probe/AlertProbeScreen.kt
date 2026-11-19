package br.com.c6tag.diagnostico.remote.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R

internal data class AlertProbeScreenState(
    val status: ProbeControllerStatus,
    val message: String? = null,
    val showPermissionAction: Boolean = false,
)

internal data class AlertProbeScreenActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
internal fun AlertProbeScreen(
    state: AlertProbeScreenState,
    actions: AlertProbeScreenActions,
) {
    val canStart = state.status.sessionReadStatus == ProbeSessionReadStatus.CONFIRMED_EMPTY &&
        state.status.storedOccurrenceId == null &&
        state.status.permission != ProbeCapabilityStatus.DENIED &&
        state.status.appNotifications != ProbeCapabilityStatus.DENIED &&
        state.status.channel != ProbeChannelStatus.BLOCKED
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("probe-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(
            onClick = actions.onBack,
            modifier = Modifier.testTag("probe-back"),
        ) {
            Text(stringResource(R.string.probe_back_settings))
        }
        Text(
            text = stringResource(R.string.probe_critical_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("probe-screen-title"),
        )
        Text(
            text = stringResource(R.string.probe_synthetic_only),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.probe_permission_status, state.status.permission.label()))
        Text(stringResource(R.string.probe_app_notifications_status, state.status.appNotifications.appLabel()))
        Text(
            stringResource(
                when (state.status.channel) {
                    ProbeChannelStatus.NOT_CREATED -> R.string.probe_channel_not_created
                    ProbeChannelStatus.AVAILABLE -> R.string.probe_channel_available
                    ProbeChannelStatus.BLOCKED -> R.string.probe_channel_blocked
                    ProbeChannelStatus.UNKNOWN -> R.string.probe_channel_unknown
                },
            ),
        )
        if (state.status.sessionReadStatus == ProbeSessionReadStatus.UNKNOWN) {
            Text(stringResource(R.string.probe_session_unavailable))
        }
        Button(
            onClick = actions.onStart,
            enabled = canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-start"),
        ) {
            Text(stringResource(R.string.probe_start_test))
        }
        if (state.status.storedOccurrenceId != null) {
            Button(
                onClick = actions.onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-stop"),
            ) {
                Text(stringResource(R.string.probe_stop_sound))
            }
        }
        Text(stringResource(R.string.probe_permission_explanation))
        if (state.showPermissionAction) {
            Button(
                onClick = actions.onRequestPermission,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-permission"),
            ) {
                Text(stringResource(R.string.probe_permission_action))
            }
        }
        Button(
            onClick = actions.onOpenSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-settings"),
        ) {
            Text(stringResource(R.string.probe_settings_action))
        }
        Text(stringResource(R.string.probe_channel_explanation))
        state.message?.let { Text(it) }
    }
}

@Composable
private fun ProbeCapabilityStatus.label(): String = stringResource(
    when (this) {
        ProbeCapabilityStatus.GRANTED -> R.string.probe_capability_granted
        ProbeCapabilityStatus.DENIED -> R.string.probe_capability_denied
        ProbeCapabilityStatus.UNKNOWN -> R.string.probe_capability_unknown
    },
)

@Composable
private fun ProbeCapabilityStatus.appLabel(): String = stringResource(
    when (this) {
        ProbeCapabilityStatus.GRANTED -> R.string.probe_app_capability_granted
        ProbeCapabilityStatus.DENIED -> R.string.probe_app_capability_denied
        ProbeCapabilityStatus.UNKNOWN -> R.string.probe_capability_unknown
    },
)
