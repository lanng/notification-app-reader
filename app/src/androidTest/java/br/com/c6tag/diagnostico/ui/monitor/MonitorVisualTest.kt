package br.com.c6tag.diagnostico.ui.monitor

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.capture.CaptureConnectionState
import br.com.c6tag.diagnostico.capture.CaptureStatusState
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.monitor.alerts.AlertCycle
import br.com.c6tag.diagnostico.monitor.alerts.AlertStatus
import br.com.c6tag.diagnostico.ui.AuthorizationStatus
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Only synthetic fixtures. Never capture the user's real database or notification shade. */
class MonitorVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureApprovedThemesAndVerifyLargeTextRemainsScrollable() {
        val theme = mutableStateOf(ThemePreference.LIGHT)
        val large = mutableStateOf(false)
        val ui = MonitorUiState(
            overview = MonitorOverview(
                reference = BalanceReference(1L, 24_720L, 1L, 0L), balanceCents = 24_720L,
                continuityPreserved = true, alertCycle = AlertCycle("synthetic", AlertStatus.ACTIVE, 1L),
                lastObservedAtMs = 1_800_000_000_000L,
            ),
            capture = CaptureStatusState(CaptureConnectionState.CONNECTED, targetPackageConfigured = true),
            authorization = AuthorizationStatus.AUTHORIZED, notices = NoticeAccess.AVAILABLE, isLoading = false,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 2f else 1f)) {
                MonitorTheme(theme.value) {
                    MonitorRoot(ui, MonitorActions(), ReferenceActions(), onEnableNotices = {}, diagnostic = {})
                }
            }
        }
        compose.onNodeWithText("Saldo abaixo do limite").assertIsDisplayed()
        save("synthetic-monitor-light.png")
        compose.runOnIdle { theme.value = ThemePreference.DARK }
        compose.onNodeWithText("Saldo abaixo do limite").assertIsDisplayed()
        save("synthetic-monitor-dark.png")
        compose.runOnIdle { large.value = true }
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasTestTag("acknowledge"))
        compose.onNodeWithTag("acknowledge").assertIsDisplayed()
        compose.onNodeWithTag("overview-scroll").performScrollToNode(hasText("Configurar avisos"))
        compose.onNodeWithText("Configurar avisos").assertIsDisplayed()
        compose.onNodeWithTag("tab-settings").assertIsDisplayed()
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
