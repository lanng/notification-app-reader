package br.com.c6tag.diagnostico.remote.access

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.ui.monitor.MonitorActions
import br.com.c6tag.diagnostico.ui.monitor.MonitorRoot
import br.com.c6tag.diagnostico.ui.monitor.MonitorUiState
import br.com.c6tag.diagnostico.ui.monitor.ReferenceActions
import br.com.c6tag.diagnostico.ui.remote.RemoteAccessScreen
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteAccessScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun signInAndOwnerDiscoveryAreSeparateExplicitActions() {
        val ui = mutableStateOf(remoteUiState())
        var signInRequests = 0
        var discoveryRequests = 0
        var activationRequests = 0

        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = { signInRequests++ },
                    onReauthenticate = {},
                    onDiscover = { discoveryRequests++ },
                    onConfirmActivation = { _, _ -> activationRequests++ },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag("remote-sign-in").assertIsDisplayed().performClick()
        assertEquals(1, signInRequests)
        assertEquals(0, discoveryRequests)
        assertEquals(0, activationRequests)

        ui.value = remoteUiState(account = ownerAccount())
        compose.onNodeWithTag("remote-discover").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, discoveryRequests)
        assertEquals(1, signInRequests)
        assertEquals(0, activationRequests)
    }

    @Test
    fun candidateIdentityIsNotShownAsAnActiveHost() {
        val ui = mutableStateOf(remoteUiState(account = ownerAccount()))
        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = {},
                    onConfirmActivation = { _, _ -> },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("O login Google, por si só, não autoriza este aparelho como host.")
            .assertIsDisplayed()
        compose.onNodeWithText("Host remoto confirmado").assertDoesNotExist()

        ui.value = ui.value.copy(
            host = RemoteHostViewState(
                phase = RemoteHostPhase.ACTIVE_CONFIRMED,
                verifiedHost = VerifiedHostAccess(
                    installationId = "installation-1",
                    monitorId = "monitor-1",
                    membershipId = "membership-1",
                    generation = generation(),
                ),
            ),
        )
        compose.onNodeWithText("Host remoto confirmado").assertIsDisplayed()
        compose.onNodeWithText("O login Google, por si só, não autoriza este aparelho como host.")
            .assertIsDisplayed()
        compose.onNodeWithText("Identidade candidata; ainda não é um host autorizado.")
            .assertDoesNotExist()
    }

    @Test
    fun cancellingActivationDialogNeverConfirmsTheDiscoveredHost() {
        val ui = mutableStateOf(remoteUiState(
            account = ownerAccount(),
            host = RemoteHostViewState(
                phase = RemoteHostPhase.CONFIRMATION_READY,
                confirmation = confirmation(),
            ),
        ))
        var confirmations = 0
        var cancellations = 0

        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = {},
                    onConfirmActivation = { _, _ -> confirmations++ },
                    onCancelConfirmation = { cancellations++ },
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag("remote-activate").assertIsDisplayed().performClick()
        compose.onNodeWithTag("activation-confirmation-dialog").assertIsDisplayed()
        compose.onNodeWithTag("activation-cancel").performClick()

        assertEquals(0, confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun newHostActivationRequiresTheCurrentConfirmationAndNoReplacementWarning() {
        val currentConfirmation = confirmation()
        val ui = mutableStateOf(remoteUiState(
            account = ownerAccount(),
            host = RemoteHostViewState(
                phase = RemoteHostPhase.CONFIRMATION_READY,
                confirmation = currentConfirmation,
            ),
        ))
        val submitted = mutableListOf<Pair<String, Boolean>>()

        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = {},
                    onConfirmActivation = { id, warning -> submitted += id to warning },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("O login Google, por si só, não autoriza este aparelho como host.")
            .assertIsDisplayed()
        compose.onNodeWithText("Ativar ou substituir host").performClick()
        compose.onNodeWithTag("activation-confirm").assertIsEnabled().performClick()

        assertEquals(listOf(currentConfirmation.confirmationId to false), submitted)
    }

    @Test
    fun replacingAnExistingHostRequiresAcknowledgingTheWarning() {
        val currentConfirmation = confirmation(existingHostInstallationId = "old-installation")
        val ui = mutableStateOf(remoteUiState(
            account = ownerAccount(),
            host = RemoteHostViewState(
                phase = RemoteHostPhase.CONFIRMATION_READY,
                confirmation = currentConfirmation,
            ),
        ))
        val submitted = mutableListOf<Pair<String, Boolean>>()

        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = {},
                    onConfirmActivation = { id, warning -> submitted += id to warning },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Ativar ou substituir host").performClick()
        compose.onNodeWithText(
            "Confirmo a substituição do host por este aparelho. O servidor encerrará o acesso do host anterior, cancelará convites e pedidos pendentes e manterá os acessos de suporte já aprovados. A credencial salva no outro aparelho não será apagada, mas deixará de ser aceita pelo servidor.",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "isso não revoga credenciais antigas fora deste app",
            substring = true,
        ).assertDoesNotExist()
        compose.onNodeWithTag("activation-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("host-replacement-warning").assertIsDisplayed().performClick()
        compose.onNodeWithTag("activation-confirm").assertIsEnabled().performClick()

        assertEquals(listOf(currentConfirmation.confirmationId to true), submitted)
    }

    @Test
    fun uidCopyActionCopiesOnlyTheVisibleFirebaseUidAndFailuresStaySafe() {
        val ui = mutableStateOf(remoteUiState(
            account = ownerAccount(),
            host = RemoteHostViewState(
                phase = RemoteHostPhase.RECOVERABLE_ERROR,
                safeFailure = OwnerAccessFailureReason.OWNER_NOT_AUTHORIZED,
                retryAfterSeconds = 41,
            ),
        ))
        var copiedValue: String? = null
        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = {},
                    onConfirmActivation = { _, _ -> },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = { copiedValue = it },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Copiar UID").performClick()
        assertEquals("firebase-uid-123", copiedValue)
        compose.onNodeWithText("Este UID ainda precisa ser autorizado manualmente pelo responsável pelo servidor.")
            .assertIsDisplayed()
        compose.onNodeWithText("Aguarde 41 segundos antes de tentar novamente.")
            .assertIsDisplayed()
        compose.onNodeWithText("private-token").assertDoesNotExist()

        ui.value = ui.value.copy(
            host = ui.value.host.copy(retryAfterSeconds = 1),
        )
        compose.onNodeWithText("Aguarde 1 segundo antes de tentar novamente.")
            .assertIsDisplayed()
    }

    @Test
    fun uncertainAndOfflineStatesDoNotClaimConfirmedAccessOrOfferAutomaticRetry() {
        val ui = mutableStateOf(remoteUiState(
            account = ownerAccount(),
            host = RemoteHostViewState(
                phase = RemoteHostPhase.PREVIOUSLY_ACTIVE_OFFLINE_STALE,
                hostValidationFailure = OwnerAccessFailureReason.NETWORK_FAILURE,
            ),
        ))
        var discoveryRequests = 0
        compose.setContent {
            MonitorTheme {
                RemoteAccessScreen(
                    state = ui.value,
                    onSignIn = {},
                    onReauthenticate = {},
                    onDiscover = { discoveryRequests++ },
                    onConfirmActivation = { _, _ -> },
                    onCancelConfirmation = {},
                    onSignOut = {},
                    onCopyUid = {},
                    onClose = {},
                )
            }
        }

        compose.onNodeWithText("Acesso salvo anteriormente; o estado atual do host está offline ou desatualizado.")
            .assertIsDisplayed()
        compose.onNodeWithText("Host remoto confirmado").assertDoesNotExist()
        compose.onNodeWithTag("remote-discover").performClick()
        assertEquals(1, discoveryRequests)

        ui.value = ui.value.copy(
            host = RemoteHostViewState(phase = RemoteHostPhase.ACTIVATION_UNCERTAIN),
        )
        compose.onNodeWithText("O resultado da ativação é incerto. Não repetiremos a ativação automaticamente.")
            .assertIsDisplayed()
        compose.onNodeWithText("Host remoto confirmado").assertDoesNotExist()
    }

    @Test
    fun remoteSettingsEntryDoesNotGateLocalOverviewHistoryOrSettingsOnNetwork() {
        var remoteOpens = 0
        var networkActions = 0

        compose.setContent {
            MonitorTheme {
                MonitorRoot(
                    state = MonitorUiState(isLoading = false),
                    actions = MonitorActions(),
                    referenceActions = ReferenceActions(),
                    onEnableNotices = {},
                    diagnostic = {},
                    onOpenRemoteAccess = {
                        remoteOpens++
                    },
                    remoteAccess = { onClose ->
                        RemoteAccessScreen(
                            state = RemoteAccessUiState(configuredOrigin = null),
                            onSignIn = { networkActions++ },
                            onReauthenticate = { networkActions++ },
                            onDiscover = { networkActions++ },
                            onConfirmActivation = { _, _ -> networkActions++ },
                            onCancelConfirmation = { networkActions++ },
                            onSignOut = { networkActions++ },
                            onCopyUid = { networkActions++ },
                            onClose = onClose,
                        )
                    },
                )
            }
        }

        compose.onNodeWithText("Informe o saldo de referência").assertIsDisplayed()
        assertEquals(0, remoteOpens)
        assertEquals(0, networkActions)

        compose.onNodeWithTag("tab-history").performClick()
        compose.onNodeWithText("Histórico financeiro").assertIsDisplayed()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-scroll")
            .performScrollToNode(hasText("Acesso remoto"))
        compose.onNodeWithText("Acesso remoto").performClick()
        compose.onNodeWithText("Acesso remoto do host").assertIsDisplayed()
        compose.onNodeWithTag("remote-sign-in").assertIsDisplayed()

        assertEquals(1, remoteOpens)
        assertEquals(0, networkActions)
    }

    private fun remoteUiState(
        account: OwnerAccount? = null,
        host: RemoteHostViewState = RemoteHostViewState(RemoteHostPhase.LOCAL_ONLY),
    ) = RemoteAccessUiState(
        account = account,
        host = host,
        configuredOrigin = "https://api.example.test",
    )

    private fun ownerAccount() = OwnerAccount(
        firebaseProjectId = "tag-alert-test",
        uid = "firebase-uid-123",
        label = "Test Owner",
    )

    private fun confirmation(
        existingHostInstallationId: String? = null,
    ) = HostActivationConfirmation(
        confirmationId = "confirmation-123",
        firebaseProjectId = "tag-alert-test",
        uid = "firebase-uid-123",
        origin = "https://api.example.test",
        generation = generation(),
        monitorId = "monitor-1",
        existingHostInstallationId = existingHostInstallationId,
    )

    private fun generation() = requireNotNull(OwnerGeneration.parse("3"))
}
