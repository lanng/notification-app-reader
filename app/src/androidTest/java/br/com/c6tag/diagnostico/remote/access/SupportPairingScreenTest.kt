package br.com.c6tag.diagnostico.remote.access

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.ui.remote.SupportPairingScreen
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SupportPairingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyFormSubmitsExplicitlyClearsSecretAndDoesNotTriggerOnComposition() {
        val submitted = mutableListOf<Triple<String, String, Boolean>>()
        var refreshCalls = 0

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = SupportPairingViewState(phase = SupportPairingPhase.EMPTY),
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.UNASSIGNED,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { secret, label, replace ->
                        submitted += Triple(secret, label, replace)
                    },
                    onRefreshStatus = { refreshCalls++ },
                    onBack = {},
                )
            }
        }

        assertEquals(0, submitted.size)
        assertEquals(0, refreshCalls)
        compose.onNodeWithTag("remote-sign-in").assertDoesNotExist()
        compose.onNodeWithText("Autorizar captura").assertDoesNotExist()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-submit-invitation"))
        compose.onNodeWithTag("support-submit-invitation").assertIsNotEnabled()

        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-label-input"))
        compose.onNodeWithTag("support-label-input").performTextInput("Celular de apoio")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .performTextInput("synthetic-invite-token-43-characters-000001")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-submit-invitation"))
        compose.onNodeWithTag("support-submit-invitation")
            .assertIsEnabled()
            .performClick()

        assertEquals(
            listOf(Triple("synthetic-invite-token-43-characters-000001", "Celular de apoio", false)),
            submitted,
        )
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .assert(hasText("synthetic-invite-token-43-characters-000001").not())
    }

    @Test
    fun formStateIsEphemeralAndNotRestoredOnReopen() {
        val visible = mutableStateOf(true)

        compose.setContent {
            MonitorTheme {
                if (visible.value) {
                    SupportPairingScreen(
                        state = SupportPairingViewState(phase = SupportPairingPhase.EMPTY),
                        configuredOrigin = "https://api.example.test",
                        roleMode = RemoteAccessRoleMode.UNASSIGNED,
                        actionInProgress = false,
                        notice = null,
                        onSubmitInvitation = { _, _, _ -> },
                        onRefreshStatus = {},
                        onBack = { visible.value = false },
                    )
                }
            }
        }

        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-label-input"))
        compose.onNodeWithTag("support-label-input").performTextInput("Rótulo temporário")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .performTextInput("synthetic-invite-not-saved-in-savedstate-01")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("remote-back-main"))
        compose.onNodeWithTag("remote-back-main").performClick()

        visible.value = true
        compose.waitForIdle()

        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .assert(hasText("synthetic-invite-not-saved-in-savedstate-01").not())
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-label-input"))
        compose.onNodeWithTag("support-label-input")
            .assert(hasText("Rótulo temporário").not())
    }

    @Test
    fun pendingValidatingAndConfirmedRenderHonestStatesWithoutFinancialClaims() {
        val ui = mutableStateOf(
            SupportPairingViewState(
                phase = SupportPairingPhase.PENDING,
                pending = PendingSupportPairingInfo(
                    requestId = "11111111-1111-1111-1111-111111111111",
                    installationId = "22222222-2222-2222-2222-222222222222",
                    requestExpiresAtMillis = 1_764_000_900_000L,
                ),
            ),
        )
        var refreshCalls = 0

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = ui.value,
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.SUPPORT_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { _, _, _ -> },
                    onRefreshStatus = { refreshCalls++ },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Aguardando aprovação do host").assertIsDisplayed()
        compose.onNodeWithText("Sair desta tela não cancela o pedido no servidor", substring = true)
            .assertExists()
        assertEquals(0, refreshCalls)
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-refresh-status"))
        compose.onNodeWithTag("support-refresh-status").performClick()
        assertEquals(1, refreshCalls)

        ui.value = SupportPairingViewState(
            phase = SupportPairingPhase.VALIDATING,
            pending = PendingSupportPairingInfo(
                requestId = "11111111-1111-1111-1111-111111111111",
                installationId = "22222222-2222-2222-2222-222222222222",
                requestExpiresAtMillis = 1_764_000_900_000L,
            ),
        )
        compose.onNodeWithText(
            "Consultando e validando o acesso de suporte com o servidor antes de confirmar.",
        ).assertExists()
        compose.onNodeWithText("Pedido aprovado pelo host.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Conectado como suporte").assertDoesNotExist()

        ui.value = SupportPairingViewState(
            phase = SupportPairingPhase.CONFIRMED,
            confirmed = ConfirmedSupportAccess(
                requestId = "11111111-1111-1111-1111-111111111111",
                installationId = "22222222-2222-2222-2222-222222222222",
                monitorId = "33333333-3333-3333-3333-333333333333",
                membershipId = "44444444-4444-4444-4444-444444444444",
                observedGeneration = requireNotNull(OwnerGeneration.parse("5")),
            ),
        )
        compose.onNodeWithText("Conectado como suporte").assertIsDisplayed()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-confirmed-disclaimer"))
        compose.onNodeWithTag("support-confirmed-disclaimer").assertIsDisplayed()
        compose.onNodeWithText("online", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag("support-submit-invitation").assertDoesNotExist()
    }

    @Test
    fun offlineStaleCredentialLostAndRecoverableErrorRenderHonestly() {
        val ui = mutableStateOf(
            SupportPairingViewState(
                phase = SupportPairingPhase.OFFLINE_STALE,
                safeFailure = PairingAccessFailureReason.RATE_LIMITED,
                retryAfterSeconds = 25L,
            ),
        )
        var refreshCalls = 0

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = ui.value,
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.SUPPORT_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { _, _, _ -> },
                    onRefreshStatus = { refreshCalls++ },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("offline ou desatualizado", substring = true, ignoreCase = true)
            .assertExists()
        compose.onNodeWithText("Aguarde 25 segundos antes de tentar novamente.").assertExists()
        compose.onNodeWithText("Conectado como suporte").assertDoesNotExist()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-refresh-status"))
        compose.onNodeWithTag("support-refresh-status").performClick()
        assertEquals(1, refreshCalls)

        ui.value = SupportPairingViewState(phase = SupportPairingPhase.CREDENTIAL_LOST)
        compose.onNodeWithText("Acesso indisponível").assertExists()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-submit-invitation"))
        compose.onNodeWithTag("support-submit-invitation").assertExists()

        ui.value = SupportPairingViewState(
            phase = SupportPairingPhase.RECOVERABLE_ERROR,
            safeFailure = PairingAccessFailureReason.INVITATION_UNAVAILABLE,
        )
        compose.onNodeWithText("O convite não está disponível.", substring = true).assertExists()
    }

    @Test
    fun redemptionUncertainRequiresExplicitConfirmationToReplaceAttempt() {
        val submitted = mutableListOf<Triple<String, String, Boolean>>()

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = SupportPairingViewState(
                        phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
                        canReplaceUncertainAttempt = true,
                        previousAttemptMayRemainActiveOnHost = true,
                    ),
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.SUPPORT_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { secret, label, replace ->
                        submitted += Triple(secret, label, replace)
                    },
                    onRefreshStatus = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Resultado da solicitação incerto").assertIsDisplayed()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .performTextInput("synthetic-replacement-invite-token-00000002")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-replace-uncertain"))
        compose.onNodeWithTag("support-replace-uncertain").performClick()

        compose.onNodeWithTag("support-replace-confirm-dialog").assertIsDisplayed()
        compose.onNodeWithTag("support-replace-cancel").performClick()
        assertEquals(0, submitted.size)

        compose.onNodeWithTag("support-replace-uncertain").performClick()
        compose.onNodeWithTag("support-replace-confirm").performClick()
        assertEquals(
            listOf(Triple("synthetic-replacement-invite-token-00000002", "", true)),
            submitted,
        )
    }

    @Test
    fun roleConflictBlocksSubmissionWithoutSuggestingUninstallOrWipe() {
        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = SupportPairingViewState(
                        phase = SupportPairingPhase.ROLE_CONFLICT,
                        roleConflict = RemoteRoleGuardBlock.OPPOSITE_SESSION_PRESENT,
                    ),
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.HOST_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { _, _, _ -> },
                    onRefreshStatus = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("use outro aparelho", substring = true, ignoreCase = true)
            .assertExists()
        compose.onNodeWithText("desinstalar", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        compose.onNodeWithText("limpar dados", substring = true, ignoreCase = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("support-submit-invitation").assertDoesNotExist()
    }

    @Test
    fun recoverableErrorWithSavedSessionKeepsRefreshAndHidesInvitationForm() {
        val ui = mutableStateOf(
            SupportPairingViewState(
                phase = SupportPairingPhase.RECOVERABLE_ERROR,
                pending = PendingSupportPairingInfo(
                    requestId = "11111111-1111-1111-1111-111111111111",
                    installationId = "22222222-2222-2222-2222-222222222222",
                    requestExpiresAtMillis = 1_764_000_900_000L,
                ),
                safeFailure = PairingAccessFailureReason.ACCESS_FORBIDDEN,
            ),
        )
        var refreshCalls = 0

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = ui.value,
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.SUPPORT_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { _, _, _ -> },
                    onRefreshStatus = { refreshCalls++ },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("support-invitation-input").assertDoesNotExist()
        compose.onNodeWithTag("support-submit-invitation").assertDoesNotExist()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-refresh-status"))
        compose.onNodeWithTag("support-refresh-status").assertIsEnabled().performClick()
        assertEquals(1, refreshCalls)

        ui.value = SupportPairingViewState(
            phase = SupportPairingPhase.RECOVERABLE_ERROR,
            confirmed = ConfirmedSupportAccess(
                requestId = "11111111-1111-1111-1111-111111111111",
                installationId = "22222222-2222-2222-2222-222222222222",
                monitorId = "33333333-3333-3333-3333-333333333333",
                membershipId = "44444444-4444-4444-4444-444444444444",
                observedGeneration = requireNotNull(OwnerGeneration.parse("5")),
            ),
            safeFailure = PairingAccessFailureReason.INVALID_RESPONSE,
        )
        compose.onNodeWithTag("support-invitation-input").assertDoesNotExist()
        compose.onNodeWithTag("support-refresh-status").assertIsEnabled()
    }

    @Test
    fun recoverableErrorWithUncertainReplacementUsesExplicitConfirmationDialog() {
        val submitted = mutableListOf<Triple<String, String, Boolean>>()

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = SupportPairingViewState(
                        phase = SupportPairingPhase.RECOVERABLE_ERROR,
                        safeFailure = PairingAccessFailureReason.INVITATION_UNAVAILABLE,
                        canReplaceUncertainAttempt = true,
                        previousAttemptMayRemainActiveOnHost = true,
                    ),
                    configuredOrigin = "https://api.example.test",
                    roleMode = RemoteAccessRoleMode.SUPPORT_PRESENT,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { secret, label, replace ->
                        submitted += Triple(secret, label, replace)
                    },
                    onRefreshStatus = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("tentativa anterior pode continuar pendente ou ativa", substring = true)
            .assertExists()
        compose.onNodeWithTag("support-submit-invitation").assertDoesNotExist()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .performTextInput("synthetic-retry-after-410-replacement-00003")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-replace-uncertain"))
        compose.onNodeWithTag("support-replace-uncertain").performClick()

        compose.onNodeWithTag("support-replace-confirm-dialog").assertIsDisplayed()
        compose.onNodeWithTag("support-replace-confirm").performClick()

        assertEquals(
            listOf(Triple("synthetic-retry-after-410-replacement-00003", "", true)),
            submitted,
        )
    }

    @Test
    fun checkingRoleDisablesActionsAndRoleFlipDismissesOpenReplaceDialog() {
        val roleMode = mutableStateOf(RemoteAccessRoleMode.CHECKING)
        val ui = mutableStateOf(
            SupportPairingViewState(
                phase = SupportPairingPhase.PENDING,
                pending = PendingSupportPairingInfo(
                    requestId = "11111111-1111-1111-1111-111111111111",
                    installationId = "22222222-2222-2222-2222-222222222222",
                    requestExpiresAtMillis = 1_764_000_900_000L,
                ),
            ),
        )
        var backClicks = 0
        var submitted = 0

        compose.setContent {
            MonitorTheme {
                SupportPairingScreen(
                    state = ui.value,
                    configuredOrigin = "https://api.example.test",
                    roleMode = roleMode.value,
                    actionInProgress = false,
                    notice = null,
                    onSubmitInvitation = { _, _, _ -> submitted++ },
                    onRefreshStatus = {},
                    onBack = { backClicks++ },
                )
            }
        }

        compose.onNodeWithText("Verificando acesso remoto salvo neste aparelho…").assertExists()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-refresh-status"))
        compose.onNodeWithTag("support-refresh-status").assertIsNotEnabled()

        ui.value = SupportPairingViewState(
            phase = SupportPairingPhase.REDEMPTION_UNCERTAIN,
            canReplaceUncertainAttempt = true,
            previousAttemptMayRemainActiveOnHost = true,
        )
        compose.waitForIdle()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-replace-uncertain"))
        compose.onNodeWithTag("support-replace-uncertain").assertIsNotEnabled()

        roleMode.value = RemoteAccessRoleMode.SUPPORT_PRESENT
        compose.waitForIdle()
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-invitation-input"))
        compose.onNodeWithTag("support-invitation-input")
            .performTextInput("synthetic-invite-before-role-flip-000000004")
        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("support-replace-uncertain"))
        compose.onNodeWithTag("support-replace-uncertain").assertIsEnabled().performClick()
        compose.onNodeWithTag("support-replace-confirm-dialog").assertIsDisplayed()

        roleMode.value = RemoteAccessRoleMode.HOST_PRESENT
        compose.waitForIdle()

        compose.onNodeWithTag("support-replace-confirm-dialog").assertDoesNotExist()
        compose.onNodeWithTag("support-replace-uncertain").assertDoesNotExist()
        compose.onNodeWithTag("support-submit-invitation").assertDoesNotExist()
        assertEquals(0, submitted)

        compose.onNodeWithTag("support-scroll")
            .performScrollToNode(hasTestTag("remote-back-main"))
        compose.onNodeWithTag("remote-back-main").assertIsEnabled().performClick()
        assertEquals(1, backClicks)
    }
}
