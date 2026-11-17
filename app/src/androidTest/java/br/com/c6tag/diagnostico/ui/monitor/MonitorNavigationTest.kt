package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MonitorNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun balanceEditorRequestsAnInputTypeThatAllowsTypingANegativeAmount() {
        val ui = mutableStateOf(MonitorUiState(
            referenceDraft = BalanceDraft(ReferenceSession("signed-input", 1L, 0L, null)),
            isLoading = false,
        ))
        var host: View? = null
        compose.setContent {
            host = LocalView.current
            MonitorTheme {
                BalanceReferenceScreen(ui.value, ReferenceActions(
                    onBalance = { value -> ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(balance = value)) },
                ))
            }
        }
        compose.onNodeWithTag("reference-balance").performClick()
        compose.runOnIdle {
            val info = EditorInfo()
            assertNotNull(host!!.onCreateInputConnection(info))
            val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
            assertTrue("The IME must allow a minus sign, not unsigned numeric-only input",
                inputClass == InputType.TYPE_CLASS_TEXT ||
                    (inputClass == InputType.TYPE_CLASS_NUMBER && info.inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0))
        }
        compose.onNodeWithTag("reference-balance").performTextInput("-20,25")
        assertEquals(-2_025L, parseBrlInput(ui.value.referenceDraft!!.balance))
    }

    @Test fun formCanOpenCaptureSettingsWithoutDiscardingReferenceSession() {
        var captureRequests = 0
        compose.setContent {
            MonitorTheme {
                MonitorRoot(
                    MonitorUiState(
                        isLoading = false, referenceDraft = BalanceDraft(ReferenceSession("session", 1L, 0L, null)),
                    ), MonitorActions(onOpenCaptureSettings = { captureRequests++ }),
                    ReferenceActions(), onEnableNotices = {}, diagnostic = {},
                )
            }
        }
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText("Autorizar captura"))
        compose.onNodeWithText("Autorizar captura").performClick()
        assertEquals(1, captureRequests)
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
                    onBalance = { value -> ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(balance = value)) },
                    onLimit = { value -> ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(limit = value)) },
                    onReview = { ui.value = ui.value.copy(referenceDraft = ui.value.referenceDraft!!.copy(confirmationVisible = true)) },
                    onConfirm = { commits++ }, onCancel = { cancels++ },
                ))
            }
        }
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
