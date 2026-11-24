package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import br.com.c6tag.diagnostico.monitor.MonitorOverview
import br.com.c6tag.diagnostico.monitor.MonitorSettings
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MonitorSettingsLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsGroupActionsNavigateAndThemeSelectionReflectsTheCurrentPreference() {
        val ui = mutableStateOf(
            MonitorUiState(
                overview = MonitorOverview(settings = MonitorSettings(theme = ThemePreference.SYSTEM)),
                isLoading = false,
            ),
        )
        var remoteOpens = 0
        var referenceRequests = 0
        var noticeSettingsOpens = 0
        var density = 1f

        compose.setContent {
            density = LocalDensity.current.density
            MonitorTheme {
                MonitorRoot(
                    state = ui.value,
                    actions = MonitorActions(
                        onReference = { referenceRequests++ },
                        onOpenNoticeSettings = { noticeSettingsOpens++ },
                        onTheme = { theme ->
                            ui.value = ui.value.copy(
                                overview = ui.value.overview.copy(
                                    settings = ui.value.overview.settings.copy(theme = theme),
                                ),
                            )
                        },
                    ),
                    referenceActions = ReferenceActions(),
                    onEnableNotices = {},
                    diagnostic = {},
                    onOpenRemoteAccess = { remoteOpens++ },
                    remoteAccess = { onClose ->
                        Column {
                            Text("Acesso remoto de teste")
                            TextButton(onClick = onClose, modifier = androidx.compose.ui.Modifier.testTag("remote-back-settings")) {
                                Text("Voltar aos ajustes")
                            }
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-monitor-access"))
        compose.onNodeWithText("Monitor e acesso").assertIsDisplayed()
        compose.onNodeWithTag("open-remote-access")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasText("Acesso remoto"))
            .assertHasClickAction()
        assertMinimumTarget("open-remote-access", density)
        assertMinimumTarget("settings-reference", density)
        compose.onNodeWithTag("settings-reference").performClick()
        assertEquals(1, referenceRequests)

        compose.onNodeWithTag("open-remote-access").performClick()
        compose.onNodeWithText("Acesso remoto de teste").assertIsDisplayed()
        compose.onAllNodesWithText("Voltar aos ajustes").assertCountEquals(1)
        assertEquals(1, remoteOpens)
        compose.onNodeWithTag("remote-back-settings").performClick()
        compose.onNodeWithTag("tab-settings").assertIsSelected()

        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-appearance"))
        compose.onNodeWithText("Aparência").assertIsDisplayed()
        compose.onNodeWithTag("theme-system")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(hasText("Acompanhar sistema"))
            .assertIsSelected()
        assertMinimumTarget("theme-system", density)
        compose.onNodeWithTag("theme-light").performClick()
        compose.onNodeWithTag("theme-light").assertIsSelected()
        compose.onNodeWithTag("theme-system")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        assertEquals(ThemePreference.LIGHT, ui.value.overview.settings.theme)

        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-local-alerts"))
        compose.onNodeWithText("Alertas locais").assertIsDisplayed()

        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-permissions"))
        compose.onNodeWithText("Permissões e diagnóstico").assertIsDisplayed()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-notice-settings"))
        compose.onNodeWithTag("settings-notice-settings")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(hasText("Abrir ajustes de avisos e som"))
            .assertIsEnabled()
        assertMinimumTarget("settings-notice-settings", density)
        compose.onNodeWithTag("settings-notice-settings").performClick()
        assertEquals(1, noticeSettingsOpens)
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasText("Diagnóstico técnico"))
        compose.onNodeWithText("Diagnóstico técnico").assertIsDisplayed()
        compose.onNodeWithText("Histórico financeiro").assertDoesNotExist()

        ui.value = ui.value.copy(isBusy = true)
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-appearance"))
        compose.onNodeWithTag("theme-system").assertIsNotEnabled()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-monitor-access"))
        compose.onNodeWithTag("settings-reference").assertIsNotEnabled()
        compose.onNodeWithTag("open-remote-access").assertIsEnabled()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-limit"))
        compose.onNodeWithTag("settings-limit").assertIsNotEnabled()
        compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-permissions"))
        compose.onNodeWithTag("settings-notice-settings").assertIsEnabled()
        compose.onNodeWithTag("repeat-15").assertIsNotEnabled()
        compose.onNodeWithTag("pause-15").assertIsNotEnabled()
        compose.onNodeWithTag("save-settings").assertIsNotEnabled()
    }

    private fun assertMinimumTarget(tag: String, density: Float) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val minimumPixels = 48f * density
        assertTrue(
            "$tag touch=${node.touchBoundsInRoot.height}px, minimum=$minimumPixels px",
            node.touchBoundsInRoot.height + 0.001f >= minimumPixels,
        )
    }
}
