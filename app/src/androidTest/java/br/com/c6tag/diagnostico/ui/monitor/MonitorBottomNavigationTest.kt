package br.com.c6tag.diagnostico.ui.monitor

import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Controlled synthetic content/configuration only; never reads the installed financial database. */
class MonitorBottomNavigationTest {
    @get:Rule val compose = createComposeRule()

    private val tabs = listOf(
        Tab("tab-overview", "tab-icon-overview", "Visão geral", "balance-value"),
        Tab("tab-history", "tab-icon-history", "Histórico", "history-filter-all"),
        Tab("tab-settings", "tab-icon-settings", "Ajustes", "settings-scroll"),
    )

    @Test
    fun iconsAndNavigationRemainCorrectAcrossThemesFontScalesAndWindowSizes() {
        val theme = mutableStateOf(ThemePreference.LIGHT)
        val fontScale = mutableStateOf(1f)
        val size = mutableStateOf(DpSize(390.dp, 844.dp))
        var contentDensity = 1f
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size.value) then
                    DeviceConfigurationOverride.FontScale(fontScale.value),
            ) {
                val density = LocalDensity.current.density
                SideEffect { contentDensity = density }
                MonitorTheme(theme.value) {
                    MonitorRoot(fixture(), MonitorActions(), ReferenceActions(), onEnableNotices = {}, diagnostic = {})
                }
            }
        }

        for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
            for (scale in listOf(1f, 2f)) {
                for (dimensions in listOf(DpSize(390.dp, 844.dp), DpSize(740.dp, 360.dp))) {
                    compose.runOnIdle {
                        theme.value = preference
                        fontScale.value = scale
                        size.value = dimensions
                    }
                    compose.onNodeWithTag("tab-overview").assertIsSelected()
                    for (tab in tabs) {
                        val icon = compose.onNodeWithTag(tab.iconTag, useUnmergedTree = true)
                            .assertIsDisplayed()
                            .assertWidthIsEqualTo(28.dp)
                            .assertHeightIsEqualTo(28.dp)
                            .fetchSemanticsNode()
                        assertTrue("${tab.iconTag} must not expose a content description",
                            !icon.config.contains(SemanticsProperties.ContentDescription))
                        compose.onNodeWithTag(tab.iconTag, useUnmergedTree = true).assertHasNoClickAction()

                        val navigationItem = compose.onNodeWithTag(tab.tag)
                            .assertIsDisplayed()
                            .assert(hasText(tab.label))
                            .assertHasClickAction()
                            .fetchSemanticsNode()
                        val minimumTouchPixels = 48f * contentDensity
                        assertTrue("${tab.tag} width=${navigationItem.touchBoundsInRoot.width}px, minimum=$minimumTouchPixels px",
                            navigationItem.touchBoundsInRoot.width + 0.001f >= minimumTouchPixels)
                        assertTrue("${tab.tag} height=${navigationItem.touchBoundsInRoot.height}px, minimum=$minimumTouchPixels px",
                            navigationItem.touchBoundsInRoot.height + 0.001f >= minimumTouchPixels)
                        compose.onNodeWithText(tab.label).assertIsDisplayed()
                        compose.onAllNodes(hasText(tab.label) and hasClickAction()).assertCountEquals(1)
                    }

                    compose.onNodeWithText("◉", useUnmergedTree = true).assertDoesNotExist()
                    compose.onNodeWithText("≡", useUnmergedTree = true).assertDoesNotExist()
                    compose.onNodeWithText("⚙", useUnmergedTree = true).assertDoesNotExist()

                    for (tab in tabs) {
                        compose.onNodeWithTag(tab.tag).performClick().assertIsSelected()
                        compose.onNodeWithTag(tab.destinationTag).assertExists()
                        for (otherTab in tabs.filterNot { it == tab }) {
                            compose.onNodeWithTag(otherTab.tag).assertIsNotSelected()
                        }
                    }
                    compose.onNodeWithTag("tab-overview").performClick().assertIsSelected()
                }
            }
        }
    }

    @Test
    fun capturesSyntheticOverviewNavigationInLightAndDarkThemes() {
        val theme = mutableStateOf(ThemePreference.LIGHT)
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp)) then
                    DeviceConfigurationOverride.FontScale(1f),
            ) {
                MonitorTheme(theme.value) {
                    MonitorRoot(fixture(), MonitorActions(), ReferenceActions(), onEnableNotices = {}, diagnostic = {})
                }
            }
        }
        compose.onNodeWithTag("tab-overview").assertIsSelected()
        save("synthetic-navigation-light.png")
        compose.runOnIdle { theme.value = ThemePreference.DARK }
        compose.onNodeWithTag("tab-overview").assertIsSelected()
        save("synthetic-navigation-dark.png")
    }

    private fun fixture() = MonitorUiState(
        overview = MonitorOverview(
            reference = BalanceReference(1L, 50_000L, 1_000L, 0L),
            balanceCents = 48_000L, continuityPreserved = true, pendingCount = 1L,
        ),
        history = listOf(
            FinancialEntry(3L, FinancialEntryKind.PIX_CREDIT, 1L, 2L, 10_000L, 48_000L, 3_000L, 2L),
            FinancialEntry(2L, FinancialEntryKind.TAG_DEBIT, 1L, 1L, 12_000L, 38_000L, 2_000L, 1L),
            FinancialEntry(1L, FinancialEntryKind.REFERENCE, 1L, null, 50_000L, 50_000L, 1_000L, null),
        ),
        pending = listOf(PendingMovement(
            1L, 1L, 3L, SourceValue(MovementKind.TAG_DEBIT, 1_000L),
            reason = PendingReason.REUSED_KEY, createdAtMs = 4_000L,
        )),
        isLoading = false,
    )

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class Tab(
        val tag: String,
        val iconTag: String,
        val label: String,
        val destinationTag: String,
    )
}
