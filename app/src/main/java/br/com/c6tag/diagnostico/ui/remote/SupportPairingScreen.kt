package br.com.c6tag.diagnostico.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.remote.access.RemoteAccessNotice
import br.com.c6tag.diagnostico.remote.access.RemoteAccessRoleMode
import br.com.c6tag.diagnostico.remote.access.RemoteRoleGuardBlock
import br.com.c6tag.diagnostico.remote.access.SupportPairingPhase
import br.com.c6tag.diagnostico.remote.access.SupportPairingViewState

private const val MAX_INSTALLATION_LABEL_CODE_POINTS = 128

@Composable
internal fun SupportPairingScreen(
    state: SupportPairingViewState,
    configuredOrigin: String?,
    roleMode: RemoteAccessRoleMode,
    actionInProgress: Boolean,
    notice: RemoteAccessNotice?,
    onSubmitInvitation: (
        invitationSecret: String,
        installationLabel: String,
        replaceUncertainAttempt: Boolean,
    ) -> Unit,
    onRefreshStatus: () -> Unit,
    onBack: () -> Unit,
) {
    // Ephemeral in-memory form state only (never rememberSaveable or SavedState).
    var deviceLabel by remember { mutableStateOf("") }
    var invitationSecret by remember { mutableStateOf("") }
    var showReplaceDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            invitationSecret = ""
            deviceLabel = ""
            showReplaceDialog = false
        }
    }

    val busy = actionInProgress ||
        state.phase == SupportPairingPhase.REDEEMING ||
        state.phase == SupportPairingPhase.VALIDATING
    val roleChecking = roleMode == RemoteAccessRoleMode.CHECKING
    val roleBlocked = state.phase == SupportPairingPhase.ROLE_CONFLICT ||
        roleMode == RemoteAccessRoleMode.HOST_PRESENT ||
        roleMode == RemoteAccessRoleMode.DUAL_ROLE_CONFLICT ||
        roleMode == RemoteAccessRoleMode.STORAGE_UNAVAILABLE
    val hasSavedSession = state.pending != null || state.confirmed != null
    val requiresExplicitReplace = state.phase == SupportPairingPhase.REDEMPTION_UNCERTAIN ||
        state.canReplaceUncertainAttempt

    val showForm = !roleBlocked && when (state.phase) {
        SupportPairingPhase.EMPTY,
        SupportPairingPhase.CREDENTIAL_LOST -> true

        SupportPairingPhase.RECOVERABLE_ERROR -> state.storageProblem == null && !hasSavedSession
        SupportPairingPhase.REDEMPTION_UNCERTAIN -> state.canReplaceUncertainAttempt
        SupportPairingPhase.REDEEMING,
        SupportPairingPhase.PENDING,
        SupportPairingPhase.VALIDATING,
        SupportPairingPhase.CONFIRMED,
        SupportPairingPhase.OFFLINE_STALE,
        SupportPairingPhase.ROLE_CONFLICT -> false
    }

    val replaceDialogVisible = showReplaceDialog &&
        showForm &&
        requiresExplicitReplace &&
        !roleBlocked &&
        !roleChecking

    LaunchedEffect(showForm, requiresExplicitReplace, roleBlocked, roleChecking) {
        if (!showForm || !requiresExplicitReplace || roleBlocked || roleChecking) {
            showReplaceDialog = false
        }
        if (!showForm || roleBlocked) {
            invitationSecret = ""
        }
    }

    fun clearEphemeralAndSubmit(replaceUncertainAttempt: Boolean) {
        val secretToSend = invitationSecret
        val labelToSend = deviceLabel
        if (
            secretToSend.isBlank() ||
            busy ||
            configuredOrigin == null ||
            roleBlocked ||
            roleChecking ||
            (replaceUncertainAttempt && !requiresExplicitReplace) ||
            (!replaceUncertainAttempt && requiresExplicitReplace)
        ) {
            return
        }
        invitationSecret = ""
        showReplaceDialog = false
        onSubmitInvitation(secretToSend, labelToSend, replaceUncertainAttempt)
    }

    BackHandler(enabled = replaceDialogVisible) {
        showReplaceDialog = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("support-scroll"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(
            onClick = {
                invitationSecret = ""
                deviceLabel = ""
                showReplaceDialog = false
                onBack()
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("remote-back-main"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.remote_back_main))
        }

        Text(
            text = stringResource(R.string.support_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.support_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (configuredOrigin == null) {
            RemoteSectionSurface {
                Text(
                    text = stringResource(R.string.remote_api_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SupportStatusSection(
            state = state,
            roleMode = roleMode,
            roleBlocked = roleBlocked,
            roleChecking = roleChecking,
            hasSavedSession = hasSavedSession,
            busy = busy,
            notice = notice,
            onRefreshStatus = onRefreshStatus,
        )

        if (showForm) {
            RemoteSectionSurface {
                Text(
                    text = stringResource(R.string.support_form_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = deviceLabel,
                    onValueChange = { updated ->
                        if (updated.codePointCount(0, updated.length) <= MAX_INSTALLATION_LABEL_CODE_POINTS) {
                            deviceLabel = updated
                        }
                    },
                    label = { Text(stringResource(R.string.support_label_field)) },
                    supportingText = { Text(stringResource(R.string.support_label_help)) },
                    enabled = !busy && !roleChecking,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("support-label-input"),
                )
                OutlinedTextField(
                    value = invitationSecret,
                    onValueChange = { invitationSecret = it },
                    label = { Text(stringResource(R.string.support_invitation_field)) },
                    supportingText = { Text(stringResource(R.string.support_invitation_help)) },
                    enabled = !busy && !roleChecking,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("support-invitation-input"),
                )

                val canSubmit = !busy &&
                    !roleChecking &&
                    configuredOrigin != null &&
                    invitationSecret.isNotBlank()

                if (requiresExplicitReplace) {
                    Button(
                        onClick = { showReplaceDialog = true },
                        enabled = canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("support-replace-uncertain"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.support_replace_uncertain))
                    }
                } else {
                    Button(
                        onClick = { clearEphemeralAndSubmit(replaceUncertainAttempt = false) },
                        enabled = canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("support-submit-invitation"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (state.phase == SupportPairingPhase.REDEEMING) {
                                    R.string.support_submitting_invitation
                                } else {
                                    R.string.support_submit_invitation
                                },
                            ),
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.support_no_financial_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (replaceDialogVisible) {
        AlertDialog(
            modifier = Modifier.testTag("support-replace-confirm-dialog"),
            onDismissRequest = { showReplaceDialog = false },
            title = { Text(stringResource(R.string.support_replace_dialog_title)) },
            text = { Text(stringResource(R.string.support_replace_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = { clearEphemeralAndSubmit(replaceUncertainAttempt = true) },
                    enabled = !busy &&
                        !roleChecking &&
                        configuredOrigin != null &&
                        invitationSecret.isNotBlank(),
                    modifier = Modifier.testTag("support-replace-confirm"),
                ) {
                    Text(stringResource(R.string.support_replace_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showReplaceDialog = false },
                    modifier = Modifier.testTag("support-replace-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SupportStatusSection(
    state: SupportPairingViewState,
    roleMode: RemoteAccessRoleMode,
    roleBlocked: Boolean,
    roleChecking: Boolean,
    hasSavedSession: Boolean,
    busy: Boolean,
    notice: RemoteAccessNotice?,
    onRefreshStatus: () -> Unit,
) {
    RemoteSectionSurface {
        if (roleChecking) {
            Text(
                text = stringResource(R.string.remote_role_checking),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (roleBlocked) {
            Text(
                text = stringResource(R.string.support_phase_role_conflict_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val conflictMessage = if (
                state.roleConflict == RemoteRoleGuardBlock.PRESENCE_UNAVAILABLE ||
                roleMode == RemoteAccessRoleMode.STORAGE_UNAVAILABLE
            ) {
                R.string.support_phase_role_conflict_storage
            } else {
                R.string.support_phase_role_conflict_opposite
            }
            Text(
                text = stringResource(conflictMessage),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            when (state.phase) {
                SupportPairingPhase.EMPTY -> {
                    Text(
                        text = stringResource(R.string.support_phase_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.REDEEMING -> {
                    Text(
                        text = stringResource(R.string.support_phase_redeeming),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.PENDING -> {
                    Text(
                        text = stringResource(R.string.support_phase_pending_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.pending?.let { pending ->
                        Text(
                            text = stringResource(
                                R.string.support_phase_pending_details,
                                shortIdentifier(pending.requestId),
                                shortIdentifier(pending.installationId),
                                formatPairingTimestamp(pending.requestExpiresAtMillis),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.support_phase_pending_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SupportPairingPhase.VALIDATING -> {
                    Text(
                        text = stringResource(R.string.support_phase_validating),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.CONFIRMED -> {
                    Text(
                        text = stringResource(R.string.support_phase_confirmed_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.confirmed?.let { confirmed ->
                        Text(
                            text = stringResource(
                                R.string.support_phase_confirmed_details,
                                shortIdentifier(confirmed.installationId),
                                shortIdentifier(confirmed.monitorId),
                                shortIdentifier(confirmed.membershipId),
                                confirmed.observedGeneration.decimal,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(R.string.support_confirmed_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("support-confirmed-disclaimer"),
                    )
                }

                SupportPairingPhase.OFFLINE_STALE -> {
                    Text(
                        text = stringResource(R.string.support_phase_offline_stale),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.CREDENTIAL_LOST -> {
                    Text(
                        text = stringResource(R.string.support_phase_credential_lost_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.support_phase_credential_lost_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.REDEMPTION_UNCERTAIN -> {
                    Text(
                        text = stringResource(R.string.support_phase_uncertain_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.support_phase_uncertain_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.canReplaceUncertainAttempt) {
                        Text(
                            text = stringResource(R.string.support_phase_uncertain_cannot_replace),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SupportPairingPhase.ROLE_CONFLICT -> {
                    Text(
                        text = stringResource(R.string.support_phase_role_conflict_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.support_phase_role_conflict_opposite),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                SupportPairingPhase.RECOVERABLE_ERROR -> {
                    Text(
                        text = stringResource(R.string.support_phase_recoverable_error_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (hasSavedSession) {
                        Text(
                            text = stringResource(R.string.support_recoverable_error_saved_session),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (state.previousAttemptMayRemainActiveOnHost) {
                        Text(
                            text = stringResource(R.string.support_previous_attempt_may_remain_active),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        state.safeFailure?.let { failure ->
            Text(
                text = failure.safeMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.storageProblem != null) {
            Text(
                text = stringResource(R.string.support_storage_problem_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.retryAfterSeconds?.takeIf { it > 0L }?.let { seconds ->
            Text(
                text = pluralStringResource(
                    R.plurals.remote_retry_after,
                    if (seconds == 1L) 1 else 2,
                    seconds,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        notice?.let { currentNotice ->
            Text(
                text = stringResource(remoteNoticeMessage(currentNotice)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val showRefresh = !roleBlocked && (
            state.phase == SupportPairingPhase.PENDING ||
                state.phase == SupportPairingPhase.CONFIRMED ||
                state.phase == SupportPairingPhase.OFFLINE_STALE ||
                (state.phase == SupportPairingPhase.RECOVERABLE_ERROR && hasSavedSession)
            )

        if (showRefresh) {
            OutlinedButton(
                onClick = onRefreshStatus,
                enabled = !busy && !roleChecking,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("support-refresh-status"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.support_refresh_status))
            }
        }
    }
}
