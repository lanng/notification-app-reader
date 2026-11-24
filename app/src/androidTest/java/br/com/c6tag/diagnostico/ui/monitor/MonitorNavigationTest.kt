package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MonitorNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun balanceEditorRequestsUnsignedNumericInputAndInterpretsDigitsAsCents() {
        val ui = mutableStateOf(MonitorUiState(
            referenceDraft = BalanceDraft(ReferenceSession("signed-input", 1L, 0L, null)),
            isLoading = false,
        ))
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            MonitorTheme {
                BalanceReferenceScreen(ui.value, ReferenceActions(
                    onBalance = { change ->
                        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(balance = change.canonicalText))
                    },
                ))
            }
        }
        compose.onNodeWithTag("reference-balance").performClick()
        compose.runOnIdle {
            val info = EditorInfo()
            assertNotNull(host!!.onCreateInputConnection(info))
            val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
            assertEquals(InputType.TYPE_CLASS_NUMBER, inputClass)
            assertEquals(0, info.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED)
        }
        compose.onNodeWithTag("reference-balance").performTextInput("2025")
        assertEquals(2_025L, parseBrlInput(ui.value.referenceDraft!!.balance))
    }

    @Test fun formCanOpenCaptureSettingsWithoutDiscardingReferenceSession() {
        var captureRequests = 0
        var density = 1f
        val ui = mutableStateOf(
            MonitorUiState(
                isLoading = false,
                authorization = AuthorizationStatus.NOT_AUTHORIZED,
                referenceDraft = BalanceDraft(ReferenceSession("session", 1L, 0L, null)),
            ),
        )
        compose.setContent {
            density = LocalDensity.current.density
            MonitorTheme {
                MonitorRoot(
                    ui.value,
                    MonitorActions(onOpenCaptureSettings = { captureRequests++ }),
                    ReferenceActions(), onEnableNotices = {}, diagnostic = {},
                )
            }
        }
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText("Autorizar captura"))
        val authorizeCapture = compose.onNodeWithText("Autorizar captura")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
            .assertHasClickAction()
        val targetHeight = authorizeCapture.fetchSemanticsNode().touchBoundsInRoot.height
        assertTrue(
            "Capture settings target=$targetHeight px, minimum=${48f * density} px",
            targetHeight + 0.001f >= 48f * density,
        )
        authorizeCapture.performClick()
        assertEquals(1, captureRequests)
        ui.value = ui.value.copy(authorization = AuthorizationStatus.AUTHORIZED)
        compose.onNodeWithText("Autorizar captura").assertDoesNotExist()
    }

    @Test fun tabsKeepDiagnosticUnderSettingsAndNeverRequestPermissionOnLaunch() {
        var requests = 0
        compose.setContent {
            MonitorTheme {
                MonitorRoot(MonitorUiState(isLoading = false), MonitorActions(), ReferenceActions(),
                    onEnableNotices = { requests++ }, diagnostic = { Text("Diagnóstico preservado") })
            }
        }
        compose.onNodeWithText("Informe o saldo de referência").assertIsDisplayed()
        compose.onNodeWithTag("tab-history").performClick()
        compose.onNodeWithText("Histórico financeiro").assertIsDisplayed()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("Diagnóstico técnico").performClick()
        compose.onNodeWithText("Diagnóstico preservado").assertIsDisplayed()
        compose.onNodeWithText("Voltar aos ajustes").performClick()
        compose.onNodeWithText("Aparência").assertIsDisplayed()
        assertEquals(0, requests)
    }

    @Test fun historyTabShowsFinancialFiltersInsteadOfAStaticPreview() {
        compose.setContent {
            MonitorTheme {
                MonitorRoot(MonitorUiState(isLoading = false), MonitorActions(), ReferenceActions(),
                    onEnableNotices = {}, diagnostic = {})
            }
        }
        compose.onNodeWithTag("tab-history").performClick()
        compose.onNodeWithText("Todas").assertIsDisplayed()
        compose.onNodeWithText("Entradas").assertIsDisplayed()
        compose.onNodeWithText("Saídas").assertIsDisplayed()
        compose.onNodeWithTag("history-filter-adjustments").assertIsDisplayed()
        compose.onNodeWithText("Prévia dos últimos três registros. Filtros, paginação e pendências serão integrados na próxima etapa.")
            .assertDoesNotExist()
    }

    @Test fun settingsExposeOnlySupportedReminderIntervalsAndSeparatePlatformLinks() {
        var noticeSettingsOpens = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MonitorTheme {
                MonitorRoot(
                    MonitorUiState(isLoading = false, notices = NoticeAccess.SILENT),
                    MonitorActions(onOpenNoticeSettings = { noticeSettingsOpens++ }),
                    ReferenceActions(), onEnableNotices = {}, diagnostic = {},
                )
            }
        }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-monitor-access"))
        compose.onNodeWithText("Monitor e acesso").assertIsDisplayed()
        compose.onNodeWithTag("open-remote-access").assertIsDisplayed()
        compose.onNodeWithTag("settings-reference").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-appearance"))
        compose.onNodeWithText("Aparência").assertIsDisplayed()
        compose.onNodeWithTag("theme-system").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-local-alerts"))
        compose.onNodeWithText("Alertas locais").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-limit"))
        compose.onNodeWithTag("settings-limit").assertIsDisplayed()
        compose.onNodeWithTag("repeat-15").assertIsDisplayed()
        compose.onNodeWithTag("repeat-30").assertIsDisplayed()
        compose.onNodeWithTag("repeat-60").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("pause-120"))
        compose.onNodeWithTag("pause-15").assertExists()
        compose.onNodeWithTag("pause-30").assertExists()
        compose.onNodeWithTag("pause-60").assertExists()
        compose.onNodeWithTag("pause-120").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Abrir ajustes de avisos e som"))
        val noticeSettingsAction = compose.onNodeWithTag("settings-notice-settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
            .assertHasClickAction()
        val targetHeight = noticeSettingsAction.fetchSemanticsNode().touchBoundsInRoot.height
        assertTrue(
            "Avisos settings target=$targetHeight px, minimum=${48f * density} px",
            targetHeight + 0.001f >= 48f * density,
        )
        noticeSettingsAction.performClick()
        assertEquals(1, noticeSettingsOpens)
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Autorizar captura"))
        compose.onNodeWithText("Autorizar captura").assertExists()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-permissions"))
        compose.onNodeWithText("Permissões e diagnóstico").assertIsDisplayed()
        compose.onNodeWithText("Histórico financeiro").assertDoesNotExist()
    }

    @Test fun financialHistoryDetailReturnsToItsFilteredList() {
        val entry = FinancialEntry(15L, FinancialEntryKind.PIX_CREDIT, 1L, 8L, 2_500L, 87_220L, 3L, 8L)
        val ui = mutableStateOf(MonitorUiState(
            overview = MonitorOverview(
                reference = BalanceReference(1L, 84_720L, 1L, 0L),
                balanceCents = 84_720L,
            ),
            history = listOf(entry),
            isLoading = false,
            historyLoading = false,
        ))
        compose.setContent {
            MonitorTheme {
                MonitorRoot(
                    ui.value,
                    MonitorActions(
                        onOpenFinancialEntry = { id ->
                            ui.value = ui.value.copy(financialSelection = ui.value.history.single { it.id == id })
                        },
                        onCloseFinancialEntry = { ui.value = ui.value.copy(financialSelection = null) },
                    ),
                    ReferenceActions(), onEnableNotices = {}, diagnostic = {},
                )
            }
        }
        compose.onNodeWithTag("tab-history").performClick()
        compose.onNodeWithTag("financial-row-15").performClick()
        compose.onNodeWithTag("financial-history-detail").assertIsDisplayed()
        compose.onNodeWithTag("close-financial-detail").performClick()
        compose.onNodeWithTag("financial-history").assertIsDisplayed()
    }

    @Test fun explicitExplanationPrecedesPlatformPermissionAndDismissalKeepsBalanceUsable() {
        var requests = 0
        compose.setContent {
            MonitorTheme {
                MonitorRoot(MonitorUiState(isLoading = false, notices = NoticeAccess.DENIED), MonitorActions(), ReferenceActions(),
                    onEnableNotices = { requests++ }, diagnostic = {})
            }
        }
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Configurar avisos"))
        compose.onNodeWithText("Configurar avisos").performClick()
        compose.onNodeWithText("Permitir avisos locais?").assertIsDisplayed()
        assertEquals(0, requests)
        compose.onNodeWithText("Agora não").performClick()
        compose.onNodeWithTag("tab-history").performClick()
        compose.onNodeWithText("Histórico financeiro").assertIsDisplayed()
        compose.onNodeWithTag("tab-overview").performClick()
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Configurar avisos"))
        compose.onNodeWithText("Configurar avisos").performClick()
        compose.onNodeWithText("Continuar no Android").performClick()
        assertEquals(1, requests)
    }

    @Test fun referenceFormShowsConcurrentWarningAndRequiresReviewBeforeCommit() {
        val ui = mutableStateOf(MonitorUiState(
            overview = MonitorOverview(pendingCount = 1L),
            referenceDraft = BalanceDraft(ReferenceSession("session", 1_000L, 0L, null)),
            isLoading = false,
        ))
        var commits = 0
        var cancels = 0
        compose.setContent {
            MonitorTheme {
                BalanceReferenceScreen(ui.value, ReferenceActions(
                    onBalance = { change ->
                        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(balance = change.canonicalText))
                    },
                    onLimit = { change ->
                        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(limit = change.canonicalText))
                    },
                    onReview = { ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(confirmationVisible = true)) },
                    onConfirm = { commits++ }, onCancel = { cancels++ },
                ))
            }
        }
        val limitHelp = "Digite os algarismos; os centavos são ajustados automaticamente. O limite não pode ser negativo."
        val invalidMoneyHelp = "Não foi possível aplicar esse valor. Corrija os algarismos; o último valor válido foi mantido."
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText(limitHelp))
        compose.onNodeWithText(limitHelp).assertIsDisplayed()
        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(limitInputError = true))
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText(invalidMoneyHelp))
        compose.onNodeWithText(invalidMoneyHelp).assertIsDisplayed()
        compose.onNodeWithText(limitHelp).assertDoesNotExist()
        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(limitInputError = false))
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasTestTag("reference-balance"))
        compose.onNodeWithTag("reference-balance").performTextInput("500,00")
        compose.onNodeWithTag("reference-limit").performTextReplacement("300,00")
        assertEquals("500,00", ui.value.referenceDraft!!.balance)
        assertEquals(0, commits)
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText("Revisar saldo"))
        compose.onNodeWithText("Revisar saldo").performClick()
        compose.onNodeWithText("Confirmar novo saldo de referência?").assertIsDisplayed()
        // The same warning is intentionally present in both form and confirmation dialog.
        compose.onNode(hasText("Novos avisos durante a conferência continuarão pendentes, sem impacto automático.") and
            hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Confirmar saldo").performClick()
        assertEquals(1, commits)
        ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(confirmationVisible = false, sessionUnavailable = true))
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText("Revisar saldo"))
        compose.onNodeWithText("Revisar saldo").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar conferência").performClick()
        assertEquals(1, cancels)
    }
}
