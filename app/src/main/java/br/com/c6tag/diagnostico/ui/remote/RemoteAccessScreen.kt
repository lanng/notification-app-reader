package br.com.c6tag.diagnostico.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.remote.access.OwnerAccessFailureReason
import br.com.c6tag.diagnostico.remote.access.RemoteAccessNotice
import br.com.c6tag.diagnostico.remote.access.RemoteAccessUiState
import br.com.c6tag.diagnostico.remote.access.RemoteHostPhase

@Composable
internal fun RemoteAccessScreen(
    state: RemoteAccessUiState,
    onSignIn: () -> Unit,
    onReauthenticate: () -> Unit,
    onDiscover: () -> Unit,
    onConfirmActivation: (confirmationId: String, hostReplacementWarningConfirmed: Boolean) -> Unit,
    onCancelConfirmation: () -> Unit,
    onSignOut: () -> Unit,
    onCopyUid: (String) -> Unit,
    onClose: () -> Unit,
) {
    var dialogConfirmationId by remember { mutableStateOf<String?>(null) }
    var replacementWarningConfirmed by remember(dialogConfirmationId) {
        mutableStateOf(false)
    }
    val confirmation = state.host.confirmation?.takeIf { candidate ->
        state.host.phase == RemoteHostPhase.CONFIRMATION_READY &&
            state.account?.firebaseProjectId == candidate.firebaseProjectId &&
            state.account?.uid == candidate.uid &&
            state.configuredOrigin == candidate.origin
    }
    val activationDialogConfirmation = confirmation?.takeIf {
        it.confirmationId == dialogConfirmationId
    }
    val showActivationDialog = activationDialogConfirmation != null
    val busy = state.actionInProgress || state.googleRequestInProgress
    val requiresReauthentication = state.host.phase == RemoteHostPhase.REAUTHENTICATION_REQUIRED ||
        state.host.phase == RemoteHostPhase.ACTIVATION_UNCERTAIN

    fun cancelConfirmationDialog() {
        if (showActivationDialog) {
            dialogConfirmationId = null
            replacementWarningConfirmed = false
            onCancelConfirmation()
        }
    }

    BackHandler(enabled = showActivationDialog) {
        cancelConfirmationDialog()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("remote-access-scroll"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.testTag("remote-back-settings"),
        ) {
            Text(stringResource(R.string.remote_back_settings))
        }
        Text(
            text = stringResource(R.string.remote_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.remote_local_monitor_explanation),
            style = MaterialTheme.typography.bodyMedium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.remote_api_configuration_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.configuredOrigin == null) {
                    Text(stringResource(R.string.remote_api_not_configured))
                } else {
                    Text(stringResource(R.string.remote_api_configured, state.configuredOrigin))
                }
                Text(stringResource(R.string.remote_owner_setup_explanation))
            }
        }

        state.account?.let { account ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.remote_candidate_identity),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.remote_project_id, account.firebaseProjectId))
                Text(stringResource(R.string.remote_uid, account.uid))
                OutlinedButton(
                    onClick = { onCopyUid(account.uid) },
                    enabled = !busy,
                    modifier = Modifier.testTag("remote-copy-uid"),
                ) {
                    Text(stringResource(R.string.remote_copy_uid))
                }
                Text(stringResource(R.string.remote_login_does_not_authorize_host))
            }
        }

        Text(
            text = stringResource(remotePhaseMessage(state)),
            style = MaterialTheme.typography.bodyLarge,
        )
        state.host.safeFailure?.let { failure ->
            Text(
                text = when (failure) {
                    OwnerAccessFailureReason.OWNER_NOT_AUTHORIZED ->
                        stringResource(R.string.remote_owner_not_authorized)

                    OwnerAccessFailureReason.IDENTITY_VERIFICATION_UNAVAILABLE ->
                        stringResource(R.string.remote_identity_verification_unavailable)

                    else -> failure.safeMessage
                },
                color = if (
                    failure == OwnerAccessFailureReason.OWNER_NOT_AUTHORIZED ||
                    failure == OwnerAccessFailureReason.IDENTITY_VERIFICATION_UNAVAILABLE
                ) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        state.host.hostValidationFailure?.let { failure ->
            if (failure != state.host.safeFailure) {
                Text(
                    text = failure.safeMessage,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.host.retryAfterSeconds?.takeIf { it > 0L }?.let { seconds ->
            Text(
                pluralStringResource(
                    R.plurals.remote_retry_after,
                    if (seconds == 1L) 1 else 2,
                    seconds,
                ),
            )
        }
        state.notice?.let { notice ->
            Text(
                text = stringResource(remoteNoticeMessage(notice)),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.account == null) {
            Button(
                onClick = onSignIn,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("remote-sign-in"),
            ) {
                Text(
                    stringResource(
                        if (state.googleRequestInProgress) {
                            R.string.remote_google_requesting
                        } else {
                            R.string.remote_sign_in
                        },
                    ),
                )
            }
        } else {
            if (requiresReauthentication) {
                Button(
                    onClick = onReauthenticate,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("remote-reauthenticate"),
                ) {
                    Text(stringResource(R.string.remote_reauthenticate))
                }
            }
            Button(
                onClick = onDiscover,
                enabled = !busy &&
                    state.configuredOrigin != null &&
                    !requiresReauthentication,
                modifier = Modifier.fillMaxWidth().testTag("remote-discover"),
            ) {
                Text(
                    stringResource(
                        if (state.host.phase == RemoteHostPhase.DISCOVERING) {
                            R.string.remote_discovering
                        } else {
                            R.string.remote_discover
                        },
                    ),
                )
            }
            if (confirmation != null) {
                Button(
                    onClick = {
                        replacementWarningConfirmed = false
                        dialogConfirmationId = confirmation.confirmationId
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("remote-activate"),
                ) {
                    Text(stringResource(R.string.remote_activate))
                }
            }
            OutlinedButton(
                onClick = onSignOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("remote-sign-out"),
            ) {
                Text(stringResource(R.string.remote_sign_out))
            }
            Text(stringResource(R.string.remote_sign_out_semantics))
        }

        Text(
            text = stringResource(R.string.remote_no_financial_sync),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (activationDialogConfirmation != null) {
        AlertDialog(
            modifier = Modifier.testTag("activation-confirmation-dialog"),
            onDismissRequest = ::cancelConfirmationDialog,
            title = { Text(stringResource(R.string.remote_activation_confirmation_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.remote_activation_confirmation_details,
                            activationDialogConfirmation.firebaseProjectId,
                            activationDialogConfirmation.uid,
                            activationDialogConfirmation.generation.decimal,
                            activationDialogConfirmation.origin,
                        ),
                    )
                    if (activationDialogConfirmation.requiresHostReplacementWarning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = replacementWarningConfirmed,
                                onCheckedChange = { replacementWarningConfirmed = it },
                                modifier = Modifier.testTag("host-replacement-warning"),
                            )
                            Text(stringResource(R.string.remote_host_replacement_warning))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val currentConfirmation = activationDialogConfirmation
                        if (
                            currentConfirmation.confirmationId == dialogConfirmationId &&
                            (!currentConfirmation.requiresHostReplacementWarning ||
                                replacementWarningConfirmed)
                        ) {
                            dialogConfirmationId = null
                            onConfirmActivation(
                                currentConfirmation.confirmationId,
                                replacementWarningConfirmed,
                            )
                        }
                    },
                    enabled = !busy &&
                        (!activationDialogConfirmation.requiresHostReplacementWarning ||
                            replacementWarningConfirmed),
                    modifier = Modifier.testTag("activation-confirm"),
                ) {
                    Text(stringResource(R.string.remote_confirm_activation))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = ::cancelConfirmationDialog,
                    modifier = Modifier.testTag("activation-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun remotePhaseMessage(state: RemoteAccessUiState): Int =
    when (state.host.phase) {
        RemoteHostPhase.LOCAL_ONLY -> R.string.remote_phase_local_only
        RemoteHostPhase.SIGNING_IN -> R.string.remote_phase_signing_in
        RemoteHostPhase.SIGNED_IN_API_NOT_READY -> R.string.remote_phase_candidate
        RemoteHostPhase.DISCOVERING -> R.string.remote_phase_discovering
        RemoteHostPhase.CONFIRMATION_READY -> R.string.remote_phase_confirmation_ready
        RemoteHostPhase.REAUTHENTICATION_REQUIRED -> R.string.remote_phase_reauthentication
        RemoteHostPhase.ACTIVATING -> R.string.remote_phase_activating
        RemoteHostPhase.ACTIVE_CONFIRMED -> if (state.host.verifiedHost != null) {
            R.string.remote_phase_active_confirmed
        } else {
            R.string.remote_phase_recoverable_error
        }
        RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE -> R.string.remote_phase_offline_stale
        RemoteHostPhase.CREDENTIAL_LOST -> R.string.remote_phase_credential_lost
        RemoteHostPhase.ACTIVATION_UNCERTAIN -> R.string.remote_phase_activation_uncertain
        RemoteHostPhase.RECOVERABLE_ERROR -> R.string.remote_phase_recoverable_error
    }

@Composable
private fun remoteNoticeMessage(notice: RemoteAccessNotice): Int =
    when (notice) {
        RemoteAccessNotice.GOOGLE_CANCELLED -> R.string.remote_google_cancelled
        RemoteAccessNotice.GOOGLE_UNAVAILABLE -> R.string.remote_google_unavailable
        RemoteAccessNotice.SIGN_IN_FAILED -> R.string.remote_sign_in_failed
        RemoteAccessNotice.REAUTHENTICATION_FAILED -> R.string.remote_reauthentication_failed
        RemoteAccessNotice.ACCOUNT_MISMATCH -> R.string.remote_account_mismatch
        RemoteAccessNotice.SIGN_OUT_FAILED -> R.string.remote_sign_out_failed
        RemoteAccessNotice.ACTION_FAILED -> R.string.remote_action_failed
    }
