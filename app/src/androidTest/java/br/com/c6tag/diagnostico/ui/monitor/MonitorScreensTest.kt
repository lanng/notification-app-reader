package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.capture.CaptureConnectionState
import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.monitor.alerts.AlertCycle
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MonitorScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unconfiguredNeverPretendsZeroAndOffersExplicitReference() {
        var requested = false
        compose.setContent {
            MonitorTheme {
                OverviewScreen(state().copy(overview = MonitorOverview()), MonitorActions(onReference = { requested = true }))
            }
        }
        compose.onNodeWithText("Informe o saldo de referência").assertIsDisplayed()
        compose.onNodeWithText("R$ 0,00").assertDoesNotExist()
        compose.onNodeWithText("Configurar saldo").performClick()
        assertTrue(requested)
    }

    @Test fun normalLowPausedAndUncertainKeepBalanceEstimatedAndUseCurrentCycle() {
        val ui = mutableStateOf(state())
        var acknowledged: String? = null
        var resumed: String? = null
        compose.setContent {
            MonitorTheme {
                OverviewScreen(ui.value, MonitorActions(
                    onAcknowledge = { acknowledged = it }, onResume = { resumed = it },
                ))
            }
        }
        compose.onNodeWithText("Saldo estimado").assertIsDisplayed()
        compose.onNodeWithText("Sem pendências conhecidas").assertIsDisplayed()
        compose.onNodeWithText("Saldo abaixo do limite").assertDoesNotExist()
        ui.value = state(20_000L).let {
            it.copy(overview = it.overview.copy(alertCycle = AlertCycle("cycle-a", AlertStatus.ACTIVE, 1L)))
        }
        compose.onNodeWithText("Saldo abaixo do limite").assertIsDisplayed()
        compose.onNodeWithTag("acknowledge").performClick()
        assertEquals("cycle-a", acknowledged)
        ui.value = ui.value.copy(overview = ui.value.overview.copy(
            alertCycle = AlertCycle("cycle-b", AlertStatus.PAUSED, 1L, pauseUntilMs = 1_800_000L),
        ))
        compose.onNodeWithText("Lembretes pausados").assertIsDisplayed()
        compose.onNodeWithTag("resume").performClick()
        assertEquals("cycle-b", resumed)
        ui.value = ui.value.copy(overview = ui.value.overview.copy(pendingCount = 1L))
        compose.onNodeWithText("1 pendência sem impacto automático").assertIsDisplayed()
        ui.value = ui.value.copy(overview = ui.value.overview.copy(pendingCount = 2L))
        compose.onNodeWithText("Saldo a conferir").assertIsDisplayed()
        compose.onNodeWithText("2 pendências sem impacto automático").assertIsDisplayed()
    }

    @Test fun noticesDeniedDoNotBlockBalanceAndCaptureAuthorizationIsSeparate() {
        val ui = mutableStateOf(state().copy(notices = NoticeAccess.DENIED, authorization = AuthorizationStatus.NOT_AUTHORIZED))
        var noticeRequests = 0
        var captureRequests = 0
        compose.setContent {
            MonitorTheme {
                OverviewScreen(ui.value, MonitorActions(
                    onNoticeExplanation = { noticeRequests++ }, onOpenCaptureSettings = { captureRequests++ },
                ))
            }
        }
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Avisos do app não permitidos"))
        compose.onNodeWithText("Configurar avisos").performClick()
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Autorizar captura"))
        compose.onNodeWithText("Autorizar captura").performClick()
        assertEquals(1, noticeRequests)
        assertEquals(1, captureRequests)
        ui.value = ui.value.copy(authorization = AuthorizationStatus.AUTHORIZED)
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Captura autorizada"))
        compose.onNodeWithText("Captura autorizada").assertIsDisplayed()
        compose.onNodeWithText("Avisos do app não permitidos").assertExists()
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Saldo estimado"))
        compose.onNodeWithTag("balance-value").assertIsDisplayed()
    }

    @Test fun settingsLimitUsesTheSharedCurrencyEditorAndEmitsCanonicalText() {
        val ui = mutableStateOf(state().copy(settingsDraft = MonitorSettingsDraft(
            limit = "1245,00",
            repeatMinutes = 30,
            pauseMinutes = 60,
        )))
        var lastChange: BrlMoneyInputChange? = null
        compose.setContent {
            MonitorTheme {
                MonitorSettingsScreen(
                    ui.value,
                    MonitorActions(onSettingsLimit = { change ->
                        lastChange = change
                        if (!change.invalid) {
                            ui.value = ui.value.copy(
                                settingsDraft = ui.value.settingsDraft!!.copy(
                                    limit = change.canonicalText,
                                    moneyInputError = false,
                                ),
                            )
                        }
                    }),
                )
            }
        }

        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-limit"))
        compose.onNodeWithTag("settings-limit").assertTextContains("1.245,00")
        compose.onNodeWithTag("settings-limit").performTextReplacement("60000")

        assertEquals(BrlMoneyInputChange("600,00"), lastChange)
        compose.onNodeWithTag("settings-limit").assertTextContains("600,00")
        compose.onNodeWithText("R$").assertIsDisplayed()
    }

    @Test fun financialHistoryUsesCoordinatorRowsAndDistinguishesReferenceFromIncome() {
        val history = listOf(
            FinancialEntry(2L, FinancialEntryKind.PIX_CREDIT, 1L, 7L, 1_000L, 85_720L, 2L, 7L),
            FinancialEntry(1L, FinancialEntryKind.REFERENCE, 1L, null, 84_720L, 84_720L, 1L, null),
        )
        compose.setContent {
            MonitorTheme {
                FinancialHistoryScreen(
                    state().copy(history = history, historyLoading = false),
                    MonitorActions(),
                )
            }
        }
        compose.onNodeWithText("Todas").assertIsDisplayed()
        compose.onNodeWithText("Pix recebido").assertIsDisplayed()
        compose.onNodeWithText("Saldo de referência inicial").assertIsDisplayed()
        compose.onNodeWithText("Prévia dos últimos três registros").assertDoesNotExist()
    }

    @Test fun financialHistoryRowsOpenTheirOwnLocalDetail() {
        val credit = FinancialEntry(2L, FinancialEntryKind.PIX_CREDIT, 1L, 7L, 1_000L, 85_720L, 2L, 7L)
        val ui = mutableStateOf(state().copy(history = listOf(credit), historyLoading = false))
        compose.setContent {
            MonitorTheme {
                FinancialHistoryScreen(ui.value, MonitorActions(
                    onOpenFinancialEntry = { id ->
                        ui.value = ui.value.copy(financialSelection = ui.value.history.single { it.id == id })
                    },
                    onCloseFinancialEntry = { ui.value = ui.value.copy(financialSelection = null) },
                ))
            }
        }
        compose.onNodeWithText("Pix recebido").assertHasClickAction().performClick()
        compose.onNodeWithText("Detalhe financeiro").assertIsDisplayed()
        compose.onNodeWithText("Saldo estimado após o registro · R$ 857,20").assertIsDisplayed()
        compose.onNodeWithText("Origem local · 7").assertIsDisplayed()
        compose.onNodeWithTag("close-financial-detail").performClick()
        compose.onNodeWithText("Pix recebido").assertIsDisplayed()
    }

    @Test fun pendingDetailShowsOnlyMinimalEvidenceAndRequiresExplicitChoice() {
        val movement = PendingMovement(
            id = 9L,
            referenceId = 1L,
            sourceId = 4L,
            value = SourceValue(MovementKind.PIX_CREDIT, 1_000L),
            reason = PendingReason.CONFLICTING_PUBLICATION,
            observationId = 5L,
            createdAtMs = 1_800_000_000_000L,
            rawEvidenceAvailable = false,
        )
        val ui = mutableStateOf(state().copy(
            pendingSelection = PendingReviewSelection(movement, expectedReferenceId = 1L),
        ))
        var requested: PendingChoice? = null
        var resolutions = 0
        compose.setContent {
            MonitorTheme {
                PendingReviewScreen(ui.value, MonitorActions(
                    onRequestPendingDecision = {
                        requested = it
                        ui.value = ui.value.copy(pendingSelection = ui.value.pendingSelection!!.copy(confirmation = it))
                    },
                    onConfirmPendingDecision = { resolutions++ },
                ))
            }
        }
        compose.onNodeWithText("Pix recebido · R$ 10,00").assertIsDisplayed()
        compose.onNodeWithText("A observação original não está mais disponível. O resumo salvo é limitado; não informe senha nem dados da conta.")
            .assertIsDisplayed()
        compose.runOnIdle {
            ui.value = ui.value.copy(pendingSelection = ui.value.pendingSelection!!.copy(
                movement = movement.copy(rawEvidenceAvailable = true),
            ))
        }
        compose.onNodeWithText("Observação original disponível. O conteúdo bruto não é exibido; apenas metadados mínimos são mostrados.")
            .assertIsDisplayed()
        compose.onNodeWithText("A observação original não está mais disponível. O resumo salvo é limitado; não informe senha nem dados da conta.")
            .assertDoesNotExist()
        compose.runOnIdle {
            ui.value = ui.value.copy(pendingSelection = ui.value.pendingSelection!!.copy(
                movement = movement.copy(rawEvidenceAvailable = false),
            ))
        }
        compose.onNodeWithText("A observação original não está mais disponível. O resumo salvo é limitado; não informe senha nem dados da conta.")
            .assertIsDisplayed()
        compose.onNodeWithTag("pending-confirm-new").performClick()
        assertEquals(PendingChoice.CONFIRM_NEW, requested)
        compose.onNodeWithText("Adicionar este movimento?").assertIsDisplayed()
        assertEquals(0, resolutions)
        compose.onNodeWithTag("pending-confirm-decision").performClick()
        assertEquals(1, resolutions)
        compose.runOnIdle {
            ui.value = ui.value.copy(pendingSelection = PendingReviewSelection(
                movement.copy(sourceAlreadyApplied = true), expectedReferenceId = 1L,
            ))
        }
        compose.onNodeWithTag("pending-confirm-new").assertDoesNotExist()
        compose.onNodeWithText("Esta origem já aparece no histórico financeiro. Não a conte novamente.")
            .assertIsDisplayed()
        compose.onNodeWithTag("pending-do-not-count").assertIsDisplayed()
    }

    @Test fun unreadablePendingAmountCannotBeConfirmedAsANewMovement() {
        val movement = PendingMovement(
            id = 10L,
            referenceId = 1L,
            sourceId = 5L,
            value = SourceValue(null, null),
            reason = PendingReason.INVALID_AMOUNT,
            observationId = 6L,
            createdAtMs = 1_800_000_000_000L,
            rawEvidenceAvailable = true,
        )
        compose.setContent {
            MonitorTheme {
                PendingReviewScreen(
                    state().copy(pendingSelection = PendingReviewSelection(movement, 1L)),
                    MonitorActions(),
                )
            }
        }
        compose.onNodeWithText("Movimento não identificado · Valor não identificado").assertIsDisplayed()
        compose.onNodeWithTag("pending-confirm-new").assertDoesNotExist()
        compose.onNodeWithTag("pending-do-not-count").assertIsDisplayed()
    }

    private fun state(cents: Long = 84_720L) = MonitorUiState(
        overview = MonitorOverview(
            reference = BalanceReference(1L, cents, 1L, 0L), balanceCents = cents,
            continuityPreserved = true, lastObservedAtMs = 1_800_000_000_000L,
        ),
        capture = CaptureStatusState(CaptureConnectionState.CONNECTED, targetPackageConfigured = true),
        authorization = AuthorizationStatus.AUTHORIZED, notices = NoticeAccess.AVAILABLE, isLoading = false,
    )
}
