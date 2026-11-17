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

    private fun state(cents: Long = 84_720L) = MonitorUiState(
        overview = MonitorOverview(
            reference = BalanceReference(1L, cents, 1L, 0L), balanceCents = cents,
            continuityPreserved = true, lastObservedAtMs = 1_800_000_000_000L,
        ),
        capture = CaptureStatusState(CaptureConnectionState.CONNECTED, targetPackageConfigured = true),
        authorization = AuthorizationStatus.AUTHORIZED, notices = NoticeAccess.AVAILABLE, isLoading = false,
    )
}
