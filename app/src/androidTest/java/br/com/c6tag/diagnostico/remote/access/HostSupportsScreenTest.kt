package br.com.c6tag.diagnostico.remote.access

import android.content.ClipDescription
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.buildSensitiveInvitationClipData
import br.com.c6tag.diagnostico.ui.remote.HostSupportsScreen
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HostSupportsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displaysRealCapacityListsAndExplicitActionsWithoutTriggeringOnComposition() {
        var refreshCalls = 0
        var issueCalls = 0
        val cancelledInvitations = mutableListOf<String>()

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = HostSupportViewState(
                        phase = HostSupportPhase.READY,
                        listSnapshotFresh = true,
                        invitations = listOf(
                            PairingInvitationSummary(
                                id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                                status = "Available",
                                createdAtMillis = 1_764_000_000_000L,
                                expiresAtMillis = 1_764_000_900_000L,
                            ),
                        ),
                        supports = listOf(
                            PairingSupportMembership(
                                membershipId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                                installationId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                                label = "Tablet casa",
                                role = "Support",
                                status = "Active",
                                approvedAtMillis = 1_764_000_100_000L,
                            ),
                            PairingSupportMembership(
                                membershipId = "dddddddd-dddd-dddd-dddd-dddddddddddd",
                                installationId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                                label = null,
                                role = "Support",
                                status = "Active",
                                approvedAtMillis = 1_764_000_200_000L,
                            ),
                        ),
                    ),
                    actionInProgress = false,
                    notice = null,
                    onRefresh = { refreshCalls++ },
                    onRevalidateSession = {},
                    onIssueInvitation = { issueCalls++ },
                    onCancelInvitation = { cancelledInvitations += it },
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {},
                    onConfirmDecision = {},
                    onCopyInvitation = {},
                    onBack = {},
                )
            }
        }

        assertEquals(0, refreshCalls)
        assertEquals(0, issueCalls)
        assertEquals(0, cancelledInvitations.size)

        compose.onNodeWithText("2 de 3 suportes ativos").assertExists()
        compose.onNodeWithText("online", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag("host-supports-refresh").performClick()
        compose.onNodeWithTag("host-supports-issue-invitation").performClick()
        assertEquals(1, refreshCalls)
        assertEquals(1, issueCalls)

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-cancel-invitation-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        compose.onNodeWithTag("host-supports-cancel-invitation-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .performClick()
        assertEquals(listOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), cancelledInvitations)
    }

    @Test
    fun capacityLimitDisablesApprovingPendingRequests() {
        val supports = listOf("1", "2", "3").map { idx ->
            PairingSupportMembership(
                membershipId = "0000000$idx-0000-0000-0000-00000000000$idx",
                installationId = "1000000$idx-0000-0000-0000-00000000000$idx",
                label = "Suporte $idx",
                role = "Support",
                status = "Active",
                approvedAtMillis = 1_764_000_000_000L + idx.toLong() * 1_000L,
            )
        }
        val request = PairingRequestSummary(
            id = "99999999-9999-9999-9999-999999999999",
            status = "Pending",
            installationId = "88888888-8888-8888-8888-888888888888",
            installationLabel = "Quarto aparelho",
            createdAtMillis = 1_764_000_500_000L,
            requestExpiresAtMillis = 1_764_001_400_000L,
        )

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = HostSupportViewState(
                        phase = HostSupportPhase.READY,
                        listSnapshotFresh = true,
                        pendingRequests = listOf(request),
                        supports = supports,
                    ),
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = {},
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {},
                    onConfirmDecision = {},
                    onCopyInvitation = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("3 de 3 suportes ativos").assertExists()
        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-approve-${request.id}"))
        compose.onNodeWithTag("host-supports-approve-${request.id}").assertIsNotEnabled()
        compose.onNodeWithTag("host-supports-reject-${request.id}").assertIsEnabled()
    }

    @Test
    fun identicalLabelsDistinguishedByIdentifierAndConfirmOnlySecondTargetDecisionId() {
        val requestOne = PairingRequestSummary(
            id = "11111111-1111-1111-1111-111111111111",
            status = "Pending",
            installationId = "aaaa1111-1111-1111-1111-111111111111",
            installationLabel = "Celular compartilhado",
            createdAtMillis = 1_764_000_010_000L,
            requestExpiresAtMillis = 1_764_000_910_000L,
        )
        val requestTwo = PairingRequestSummary(
            id = "22222222-2222-2222-2222-222222222222",
            status = "Pending",
            installationId = "bbbb2222-2222-2222-2222-222222222222",
            installationLabel = "Celular compartilhado",
            createdAtMillis = 1_764_000_075_000L,
            requestExpiresAtMillis = 1_764_000_975_000L,
        )
        val ui = mutableStateOf(
            HostSupportViewState(
                phase = HostSupportPhase.READY,
                listSnapshotFresh = true,
                pendingRequests = listOf(requestOne, requestTwo),
            ),
        )
        val requested = mutableListOf<Pair<HostSupportDecisionAction, String>>()
        val confirmedDecisionIds = mutableListOf<String>()

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = ui.value,
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = {},
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { action, targetId ->
                        requested += action to targetId
                        if (targetId == requestTwo.id) {
                            ui.value = ui.value.copy(
                                pendingConfirmation = HostSupportDecisionConfirmation(
                                    decisionId = "decision-target-two",
                                    action = action,
                                    targetId = requestTwo.id,
                                    installationId = requestTwo.installationId,
                                    targetLabel = requestTwo.installationLabel,
                                    targetTimestampMillis = requestTwo.createdAtMillis,
                                ),
                            )
                        }
                    },
                    onCancelDecision = {
                        ui.value = ui.value.copy(pendingConfirmation = null)
                    },
                    onConfirmDecision = { decisionId ->
                        confirmedDecisionIds += decisionId
                        ui.value = ui.value.copy(pendingConfirmation = null)
                    },
                    onCopyInvitation = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-approve-${requestTwo.id}"))
        compose.onNodeWithText("aaaa1111", substring = true).assertExists()
        compose.onNodeWithText("bbbb2222", substring = true).assertExists()

        compose.onNodeWithTag("host-supports-approve-${requestTwo.id}").performClick()
        assertEquals(
            listOf(HostSupportDecisionAction.APPROVE_REQUEST to requestTwo.id),
            requested,
        )

        compose.onNodeWithTag("host-support-decision-dialog").assertIsDisplayed()
        compose.onNode(hasText("22222222", substring = true) and hasAnyAncestor(isDialog()))
            .assertExists()
        compose.onNode(hasText("bbbb2222", substring = true) and hasAnyAncestor(isDialog()))
            .assertExists()
        compose.onNode(hasText("11111111", substring = true) and hasAnyAncestor(isDialog()))
            .assertDoesNotExist()
        compose.onNodeWithTag("host-support-decision-confirm").performClick()

        assertEquals(listOf("decision-target-two"), confirmedDecisionIds)
    }

    @Test
    fun cancellingDecisionDialogOrStaleListsSendsZeroDecisions() {
        val support = PairingSupportMembership(
            membershipId = "55555555-5555-5555-5555-555555555555",
            installationId = "66666666-6666-6666-6666-666666666666",
            label = "Aparelho antigo",
            role = "Support",
            status = "Active",
            approvedAtMillis = 1_764_000_000_000L,
        )
        val ui = mutableStateOf(
            HostSupportViewState(
                phase = HostSupportPhase.READY,
                listSnapshotFresh = true,
                supports = listOf(support),
                pendingConfirmation = HostSupportDecisionConfirmation(
                    decisionId = "decision-revoke-1",
                    action = HostSupportDecisionAction.REVOKE_SUPPORT,
                    targetId = support.membershipId,
                    installationId = support.installationId,
                    targetLabel = support.label,
                    targetTimestampMillis = support.approvedAtMillis,
                ),
            ),
        )
        var cancellations = 0
        var confirmations = 0

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = ui.value,
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = {},
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {
                        cancellations++
                        ui.value = ui.value.copy(pendingConfirmation = null)
                    },
                    onConfirmDecision = { confirmations++ },
                    onCopyInvitation = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("host-support-decision-dialog").assertIsDisplayed()
        compose.onNode(
            hasText("não promete apagar o arquivo salvo", substring = true) and hasAnyAncestor(isDialog()),
        ).assertExists()
        compose.onNodeWithTag("host-support-decision-cancel").performClick()

        assertEquals(1, cancellations)
        assertEquals(0, confirmations)

        ui.value = ui.value.copy(
            phase = HostSupportPhase.STALE,
            listSnapshotFresh = false,
        )
        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-revoke-${support.membershipId}"))
        compose.onNodeWithTag("host-supports-revoke-${support.membershipId}").assertIsNotEnabled()
    }

    @Test
    fun copyInvitationRunsOnlyOnExplicitGestureAndRespectsAvailability() {
        val ui = mutableStateOf(
            HostSupportViewState(
                phase = HostSupportPhase.READY,
                listSnapshotFresh = true,
                emittedInvitation = HostEmittedInvitationNotice(
                    id = "77777777-7777-7777-7777-777777777777",
                    createdAtMillis = 1_764_000_000_000L,
                    expiresAtMillis = 1_764_000_900_000L,
                    copyAvailable = true,
                ),
            ),
        )
        val copiedIds = mutableListOf<String>()

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = ui.value,
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = {},
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {},
                    onConfirmDecision = {},
                    onCopyInvitation = { copiedIds += it },
                    onBack = {},
                )
            }
        }

        assertEquals(0, copiedIds.size)
        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-copy-invitation"))
        compose.onNodeWithTag("host-supports-copy-invitation").assertIsEnabled().performClick()
        assertEquals(listOf("77777777-7777-7777-7777-777777777777"), copiedIds)

        ui.value = ui.value.copy(
            emittedInvitation = ui.value.emittedInvitation!!.copy(copyAvailable = false),
        )
        compose.onNodeWithTag("host-supports-copy-invitation").assertIsNotEnabled()
    }

    @Test
    fun authorizationRequiredAndReconciliationNoticesRenderHonestly() {
        val ui = mutableStateOf(
            HostSupportViewState(
                phase = HostSupportPhase.AUTHORIZATION_REQUIRED,
                reconciliationNotice = HostSupportReconciliationNotice(
                    kind = HostSupportReconciliationKind.ISSUE_UNCERTAIN_SECRET_UNRECOVERABLE,
                ),
            ),
        )
        var revalidations = 0

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = ui.value,
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = { revalidations++ },
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {},
                    onConfirmDecision = {},
                    onCopyInvitation = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("host-supports-revalidate").assertIsEnabled().performClick()
        assertEquals(1, revalidations)
        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-reconciliation"))
        compose.onNodeWithTag("host-supports-reconciliation").assertIsDisplayed()
    }

    @Test
    fun buildSensitiveInvitationClipDataMarksSyntheticPayloadAsSensitive() {
        val syntheticSecret = SecretValue.of("synthetic-invitation-value-for-clipboard-test")
        val clipData = buildSensitiveInvitationClipData(
            label = "Convite de suporte",
            secret = syntheticSecret,
        )

        assertEquals(1, clipData.itemCount)
        assertEquals(
            "synthetic-invitation-value-for-clipboard-test",
            clipData.getItemAt(0).text.toString(),
        )
        val extras = clipData.description.extras
        assertNotNull(extras)
        val expectedKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ClipDescription.EXTRA_IS_SENSITIVE
        } else {
            "android.content.extra.IS_SENSITIVE"
        }
        assertTrue(extras!!.getBoolean(expectedKey, false))
    }

    @Test
    fun losingHostAvailabilityDisablesAllActionsHidesDialogAndPreservesBackReturn() {
        val invitation = PairingInvitationSummary(
            id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            status = "Available",
            createdAtMillis = 1_764_000_000_000L,
            expiresAtMillis = 1_764_000_900_000L,
        )
        val request = PairingRequestSummary(
            id = "11111111-1111-1111-1111-111111111111",
            status = "Pending",
            installationId = "22222222-2222-2222-2222-222222222222",
            installationLabel = "Celular de apoio",
            createdAtMillis = 1_764_000_010_000L,
            requestExpiresAtMillis = 1_764_000_910_000L,
        )
        val support = PairingSupportMembership(
            membershipId = "33333333-3333-3333-3333-333333333333",
            installationId = "44444444-4444-4444-4444-444444444444",
            label = "Tablet",
            role = "Support",
            status = "Active",
            approvedAtMillis = 1_764_000_020_000L,
        )
        val hostAvailable = mutableStateOf(true)
        val canRevalidate = mutableStateOf(false)
        var backClicks = 0

        compose.setContent {
            MonitorTheme {
                HostSupportsScreen(
                    state = HostSupportViewState(
                        phase = HostSupportPhase.READY,
                        listSnapshotFresh = true,
                        emittedInvitation = HostEmittedInvitationNotice(
                            id = invitation.id,
                            createdAtMillis = invitation.createdAtMillis,
                            expiresAtMillis = invitation.expiresAtMillis,
                            copyAvailable = true,
                        ),
                        invitations = listOf(invitation),
                        pendingRequests = listOf(request),
                        supports = listOf(support),
                        pendingConfirmation = HostSupportDecisionConfirmation(
                            decisionId = "decision-stale-host",
                            action = HostSupportDecisionAction.APPROVE_REQUEST,
                            targetId = request.id,
                            installationId = request.installationId,
                            targetLabel = request.installationLabel,
                            targetTimestampMillis = request.createdAtMillis,
                        ),
                    ),
                    hostAvailable = hostAvailable.value,
                    canRevalidateSession = canRevalidate.value,
                    actionInProgress = false,
                    notice = null,
                    onRefresh = {},
                    onRevalidateSession = {},
                    onIssueInvitation = {},
                    onCancelInvitation = {},
                    onRequestDecision = { _, _ -> },
                    onCancelDecision = {},
                    onConfirmDecision = {},
                    onCopyInvitation = {},
                    onBack = { backClicks++ },
                )
            }
        }

        compose.onNodeWithTag("host-support-decision-dialog").assertIsDisplayed()

        hostAvailable.value = false
        compose.waitForIdle()

        compose.onNodeWithTag("host-support-decision-dialog").assertDoesNotExist()
        compose.onNodeWithText("Este aparelho não está confirmado como host ativo agora.")
            .assertExists()
        compose.onNodeWithTag("host-supports-revalidate").assertDoesNotExist()
        compose.onNodeWithTag("host-supports-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("host-supports-issue-invitation").assertIsNotEnabled()

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-copy-invitation"))
        compose.onNodeWithTag("host-supports-copy-invitation").assertIsNotEnabled()
        compose.onNodeWithText("A cópia deste convite não está mais disponível nesta sessão.")
            .assertExists()

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-cancel-invitation-${invitation.id}"))
        compose.onNodeWithTag("host-supports-cancel-invitation-${invitation.id}").assertIsNotEnabled()

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-approve-${request.id}"))
        compose.onNodeWithTag("host-supports-approve-${request.id}").assertIsNotEnabled()
        compose.onNodeWithTag("host-supports-reject-${request.id}").assertIsNotEnabled()

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-revoke-${support.membershipId}"))
        compose.onNodeWithTag("host-supports-revoke-${support.membershipId}").assertIsNotEnabled()

        canRevalidate.value = true
        compose.waitForIdle()
        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("host-supports-revalidate"))
        compose.onNodeWithTag("host-supports-revalidate").assertIsEnabled()

        compose.onNodeWithTag("host-supports-scroll")
            .performScrollToNode(hasTestTag("remote-back-main"))
        compose.onNodeWithTag("remote-back-main").assertIsEnabled().performClick()
        assertEquals(1, backClicks)
    }
}
