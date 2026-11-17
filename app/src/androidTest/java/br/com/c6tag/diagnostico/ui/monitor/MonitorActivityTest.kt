package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.MainActivity
import br.com.c6tag.diagnostico.BuildConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MonitorActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualActivityUsesNewShellAndPreservesDiagnosticAfterRecreation() {
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("Diagnóstico técnico").performClick()
        compose.onNodeWithText("Diagnóstico: não calcula saldo").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Voltar aos ajustes").assertIsDisplayed()
        compose.onNodeWithText("Diagnóstico: não calcula saldo").assertIsDisplayed()
        compose.onNodeWithText("Voltar aos ajustes").performClick()
        compose.onNodeWithTag("tab-overview").performClick()
        compose.onNodeWithText("Informe o saldo de referência").assertIsDisplayed()
    }

    @Test fun referenceDraftSurvivesActivityRecreationWithoutBecomingAStoredBalance() {
        compose.onNodeWithText("Configurar saldo").performClick()
        compose.onNodeWithTag("reference-balance").performTextInput("123,45")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reference-balance").assertTextContains("123,45")
        compose.onNodeWithTag("reference-scroll").performScrollToNode(hasText("Cancelar conferência"))
        compose.onNodeWithText("Cancelar conferência").performClick()
        compose.onNodeWithText("Informe o saldo de referência").assertIsDisplayed()
    }

    @Test fun installationIdentityIsPreservedWithUpdatedVisibleNameAndVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("br.com.c6tag.diagnostico", context.packageName)
        assertEquals("Monitor de tags", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("1.1", BuildConfig.VERSION_NAME)
    }
}
