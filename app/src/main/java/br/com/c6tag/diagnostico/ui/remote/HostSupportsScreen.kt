package br.com.c6tag.diagnostico.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.R
import br.com.c6tag.diagnostico.remote.access.HostSupportDecisionAction
import br.com.c6tag.diagnostico.remote.access.HostSupportDecisionConfirmation
import br.com.c6tag.diagnostico.remote.access.HostSupportPhase
import br.com.c6tag.diagnostico.remote.access.HostSupportReconciliationKind
import br.com.c6tag.diagnostico.remote.access.HostSupportViewState
import br.com.c6tag.diagnostico.remote.access.RemoteAccessNotice

@Composable
internal fun HostSupportsScreen(
    state: HostSupportViewState,
    actionInProgress: Boolean,
    notice: RemoteAccessNotice?,
    onRefresh: () -> Unit,
    onRevalidateSession: () -> Unit,
    onIssueInvitation: () -> Unit,
    onCancelInvitation: (invitationId: String) -> Unit,
    onRequestDecision: (action: HostSupportDecisionAction, targetId: String) -> Unit,
    onCancelDecision: () -> Unit,
    onConfirmDecision: (decisionId: String) -> Unit,
    onCopyInvitation: (invitationId: String) -> Unit,
    onBack: () -> Unit,
    hostAvailable: Boolean = true,
    canRevalidateSession: Boolean = true,
) {
    val busy = actionInProgress ||
        state.phase == HostSupportPhase.LOADING ||
        state.phase == HostSupportPhase.MUTATING
    val effectivePhase = if (hostAvailable) state.phase else HostSupportPhase.HOST_UNAVAILABLE
    val activePendingConfirmation = if (hostAvailable) state.pendingConfirmation else null

    BackHandler(enabled = activePendingConfirmation != null) {
        onCancelDecision()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .testTag("host-supports-scroll"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(
            onClick = {
                if (state.pendingConfirmation != null) {
                    onCancelDecision()
                }
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
            text = stringResource(R.string.host_supports_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.host_supports_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        RemoteSectionSurface(modifier = Modifier.testTag("host-supports-capacity")) {
            Text(
                text = pluralStringResource(
                    R.plurals.host_supports_capacity,
                    state.maxActiveSupports,
                    state.activeSupportCount,
                    state.maxActiveSupports,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.isSupportLimitReached) {
                Text(
                    text = stringResource(R.string.host_supports_limit_reached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = stringResource(hostSupportPhaseMessage(effectivePhase)),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.safeFailure?.let { failure ->
                Text(
                    text = failure.safeMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            if (
                canRevalidateSession && (
                    state.phase == HostSupportPhase.AUTHORIZATION_REQUIRED ||
                        state.phase == HostSupportPhase.HOST_UNAVAILABLE ||
                        !hostAvailable
                    )
            ) {
                Button(
                    onClick = onRevalidateSession,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("host-supports-revalidate"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.host_supports_revalidate))
                }
            }

            OutlinedButton(
                onClick = onRefresh,
                enabled = hostAvailable && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("host-supports-refresh"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.host_supports_refresh))
            }

            Button(
                onClick = onIssueInvitation,
                enabled = hostAvailable &&
                    !busy &&
                    state.phase != HostSupportPhase.AUTHORIZATION_REQUIRED &&
                    state.phase != HostSupportPhase.HOST_UNAVAILABLE,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("host-supports-issue-invitation"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.host_supports_issue_invitation))
            }
        }

        state.reconciliationNotice?.let { reconciliation ->
            RemoteSectionSurface(modifier = Modifier.testTag("host-supports-reconciliation")) {
                Text(
                    text = stringResource(reconciliationNoticeMessage(reconciliation.kind)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.emittedInvitation?.let { emitted ->
            val canCopyInvitation = hostAvailable && emitted.copyAvailable && !busy
            RemoteSectionSurface(modifier = Modifier.testTag("host-supports-emitted-invitation")) {
                Text(
                    text = stringResource(R.string.host_supports_emitted_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        R.string.host_supports_emitted_meta,
                        shortIdentifier(emitted.id),
                        formatPairingTimestamp(emitted.expiresAtMillis),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.host_supports_emitted_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onCopyInvitation(emitted.id) },
                    enabled = canCopyInvitation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("host-supports-copy-invitation"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.host_supports_copy_invitation))
                }
                if (!hostAvailable || !emitted.copyAvailable) {
                    Text(
                        text = stringResource(R.string.host_supports_copy_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        RemoteSectionSurface(modifier = Modifier.testTag("host-supports-invitations-section")) {
            Text(
                text = stringResource(R.string.host_supports_invitations_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.host_supports_invitations_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.invitations.isEmpty()) {
                Text(
                    text = stringResource(R.string.host_supports_invitations_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.invitations.forEachIndexed { index, invitation ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.host_supports_invitation_item_id,
                                shortIdentifier(invitation.id),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.host_supports_invitation_item_times,
                                formatPairingTimestamp(invitation.createdAtMillis),
                                formatPairingTimestamp(invitation.expiresAtMillis),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { onCancelInvitation(invitation.id) },
                            enabled = hostAvailable &&
                                state.listSnapshotFresh &&
                                state.phase == HostSupportPhase.READY &&
                                !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("host-supports-cancel-invitation-${invitation.id}"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(stringResource(R.string.host_supports_cancel_invitation))
                        }
                    }
                }
            }
        }

        RemoteSectionSurface(modifier = Modifier.testTag("host-supports-pending-section")) {
            Text(
                text = stringResource(R.string.host_supports_pending_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.host_supports_pending_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.pendingRequests.isEmpty()) {
                Text(
                    text = stringResource(R.string.host_supports_pending_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.pendingRequests.forEachIndexed { index, request ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = request.installationLabel?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.host_supports_unnamed_device),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.host_supports_pending_item_ids,
                                shortIdentifier(request.id),
                                shortIdentifier(request.installationId),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.host_supports_pending_item_times,
                                formatPairingTimestamp(request.createdAtMillis),
                                formatPairingTimestamp(request.requestExpiresAtMillis),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    onRequestDecision(
                                        HostSupportDecisionAction.APPROVE_REQUEST,
                                        request.id,
                                    )
                                },
                                enabled = hostAvailable &&
                                    state.canRequestDecisions &&
                                    !state.isSupportLimitReached &&
                                    !busy,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("host-supports-approve-${request.id}"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Text(stringResource(R.string.host_supports_approve))
                            }
                            OutlinedButton(
                                onClick = {
                                    onRequestDecision(
                                        HostSupportDecisionAction.REJECT_REQUEST,
                                        request.id,
                                    )
                                },
                                enabled = hostAvailable && state.canRequestDecisions && !busy,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("host-supports-reject-${request.id}"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Text(stringResource(R.string.host_supports_reject))
                            }
                        }
                    }
                }
            }
        }

        RemoteSectionSurface(modifier = Modifier.testTag("host-supports-active-section")) {
            Text(
                text = stringResource(R.string.host_supports_active_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.supports.isEmpty()) {
                Text(
                    text = stringResource(R.string.host_supports_active_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.supports.forEachIndexed { index, support ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = support.label?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.host_supports_unnamed_device),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.host_supports_active_item_ids,
                                shortIdentifier(support.membershipId),
                                shortIdentifier(support.installationId),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.host_supports_active_item_approved,
                                formatPairingTimestamp(support.approvedAtMillis),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                onRequestDecision(
                                    HostSupportDecisionAction.REVOKE_SUPPORT,
                                    support.membershipId,
                                )
                            },
                            enabled = hostAvailable && state.canRequestDecisions && !busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("host-supports-revoke-${support.membershipId}"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(stringResource(R.string.host_supports_revoke))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (activePendingConfirmation != null) {
        HostSupportDecisionDialog(
            confirmation = activePendingConfirmation,
            busy = busy,
            onConfirm = { onConfirmDecision(activePendingConfirmation.decisionId) },
            onCancel = onCancelDecision,
        )
    }
}

@Composable
private fun HostSupportDecisionDialog(
    confirmation: HostSupportDecisionConfirmation,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val titleRes = when (confirmation.action) {
        HostSupportDecisionAction.APPROVE_REQUEST -> R.string.host_supports_decision_approve_title
        HostSupportDecisionAction.REJECT_REQUEST -> R.string.host_supports_decision_reject_title
        HostSupportDecisionAction.REVOKE_SUPPORT -> R.string.host_supports_decision_revoke_title
    }
    val actionNoticeRes = when (confirmation.action) {
        HostSupportDecisionAction.APPROVE_REQUEST -> R.string.host_supports_decision_approve_notice
        HostSupportDecisionAction.REJECT_REQUEST -> R.string.host_supports_decision_reject_notice
        HostSupportDecisionAction.REVOKE_SUPPORT -> R.string.host_supports_decision_revoke_notice
    }
    val confirmLabelRes = when (confirmation.action) {
        HostSupportDecisionAction.APPROVE_REQUEST -> R.string.host_supports_decision_approve_confirm
        HostSupportDecisionAction.REJECT_REQUEST -> R.string.host_supports_decision_reject_confirm
        HostSupportDecisionAction.REVOKE_SUPPORT -> R.string.host_supports_decision_revoke_confirm
    }
    val targetLabel = confirmation.targetLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.host_supports_unnamed_device)

    AlertDialog(
        modifier = Modifier.testTag("host-support-decision-dialog"),
        onDismissRequest = onCancel,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.host_supports_decision_target_details,
                        targetLabel,
                        shortIdentifier(confirmation.targetId),
                        shortIdentifier(confirmation.installationId),
                        formatPairingTimestamp(confirmation.targetTimestampMillis),
                    ),
                )
                Text(
                    text = stringResource(actionNoticeRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier.testTag("host-support-decision-confirm"),
            ) {
                Text(stringResource(confirmLabelRes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("host-support-decision-cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun hostSupportPhaseMessage(phase: HostSupportPhase): Int =
    when (phase) {
        HostSupportPhase.IDLE -> R.string.host_supports_phase_idle
        HostSupportPhase.LOADING -> R.string.host_supports_phase_loading
        HostSupportPhase.READY -> R.string.host_supports_phase_ready
        HostSupportPhase.MUTATING -> R.string.host_supports_phase_mutating
        HostSupportPhase.STALE -> R.string.host_supports_phase_stale
        HostSupportPhase.AUTHORIZATION_REQUIRED -> R.string.host_supports_phase_auth_required
        HostSupportPhase.HOST_UNAVAILABLE -> R.string.host_supports_phase_host_unavailable
        HostSupportPhase.RECOVERABLE_ERROR -> R.string.host_supports_phase_recoverable_error
    }

private fun reconciliationNoticeMessage(kind: HostSupportReconciliationKind): Int =
    when (kind) {
        HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE ->
            R.string.host_supports_reconcile_issue_uncertain

        HostSupportReconciliationKind.APPROVAL_CONFIRMED_BY_SUPPORT_LIST ->
            R.string.host_supports_reconcile_approval_confirmed

        HostSupportReconciliationKind.APPROVAL_UNCERTAIN ->
            R.string.host_supports_reconcile_approval_uncertain

        HostSupportReconciliationKind.REJECTION_INDETERMINATE ->
            R.string.host_supports_reconcile_rejection_indeterminate

        HostSupportReconciliationKind.CANCEL_CONFIRMED_ABSENT_CAUSE_UNKNOWN ->
            R.string.host_supports_reconcile_cancel_absent

        HostSupportReconciliationKind.REVOKE_CONFIRMED_ABSENT_CAUSE_UNKNOWN ->
            R.string.host_supports_reconcile_revoke_absent

        HostSupportReconciliationKind.STILL_PENDING ->
            R.string.host_supports_reconcile_still_pending

        HostSupportReconciliationKind.STILL_ACTIVE ->
            R.string.host_supports_reconcile_still_active

        HostSupportReconciliationKind.STILL_AVAILABLE ->
            R.string.host_supports_reconcile_still_available

        HostSupportReconciliationKind.RECONCILIATION_UNAVAILABLE ->
            R.string.host_supports_reconcile_unavailable
    }
