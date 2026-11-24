package br.com.c6tag.diagnostico.ui.monitor

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import br.com.c6tag.diagnostico.monitor.*
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Controlled content/configuration only: never reads the installed financial database. */
class MonitorFinalLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun captureHistoryPendingAndSettingsWithSyntheticDataInBothThemes() {
        val theme = mutableStateOf(ThemePreference.LIGHT)
        compose.setContent {
            MonitorTheme(theme.value) {
                MonitorRoot(fixture(), MonitorActions(), ReferenceActions(), onEnableNotices = {}, diagnostic = {})
            }
        }
        for ((preference, suffix) in listOf(ThemePreference.LIGHT to "light", ThemePreference.DARK to "dark")) {
            compose.runOnIdle { theme.value = preference }
            compose.onNodeWithTag("tab-history").performClick()
            compose.onNodeWithTag("history-filter-all").assertIsDisplayed()
            save("synthetic-history-$suffix.png")
            compose.onNodeWithTag("open-pending-review").performClick()
            compose.onNodeWithTag("pending-row-1").assertIsDisplayed()
            save("synthetic-pending-$suffix.png")
            compose.onNodeWithText("Voltar ao histórico").performClick()
            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithText("Aparência").assertIsDisplayed()
            save("synthetic-settings-$suffix.png")
        }
    }

    @Test
    fun settingsRemainOperableAtDoubleFontScaleInPortraitAndLandscapeConstraints() {
        val size = mutableStateOf(DpSize(390.dp, 844.dp))
        val theme = mutableStateOf(ThemePreference.LIGHT)
        var repeat = 0
        var pause = 0
        var saves = 0
        var contentDensity = 1f
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size.value) then DeviceConfigurationOverride.FontScale(2f),
            ) {
                val density = LocalDensity.current.density
                SideEffect { contentDensity = density }
                MonitorTheme(theme.value) {
                    MonitorRoot(
                        fixture(), MonitorActions(
                            onSettingsRepeat = { repeat = it },
                            onSettingsPause = { pause = it },
                            onSaveSettings = { saves++ },
                        ), ReferenceActions(), onEnableNotices = {}, diagnostic = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("tab-settings").performClick()
        for (preference in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) {
            compose.runOnIdle { theme.value = preference }
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-monitor-access"))
            assertMinimumTarget("open-remote-access", contentDensity)
            assertMinimumTarget("settings-reference", contentDensity)
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-appearance"))
            assertMinimumTarget("theme-system", contentDensity)
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-local-alerts"))
            compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("settings-limit"))
            for (dimensions in listOf(DpSize(390.dp, 844.dp), DpSize(740.dp, 360.dp))) {
                compose.runOnIdle { size.value = dimensions }
                compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("repeat-60"))
                assertMinimumTarget("repeat-60", contentDensity)
                compose.onNodeWithTag("repeat-60").performClick()
                compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("pause-120"))
                assertMinimumTarget("pause-120", contentDensity)
                compose.onNodeWithTag("pause-120").performClick()
                compose.onNodeWithTag("settings-scroll").performScrollToNode(hasTestTag("save-settings"))
                assertMinimumTarget("save-settings", contentDensity)
                compose.onNodeWithTag("save-settings").performClick()
                assertEquals(60, repeat)
                assertEquals(120, pause)
            }
        }
        assertEquals(4, saves)
    }

    private fun assertMinimumTarget(tag: String, density: Float) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        // Material chips can expand hit bounds beyond their visual size.
        // Touch bounds use subpixel floats. Allow only numeric precision noise,
        // not a dp-sized tolerance that could accept a genuinely smaller target.
        val minimumPixels = 48f * density
        val touchPixels = node.touchBoundsInRoot.height
        assertTrue("$tag touch=$touchPixels px, minimum=$minimumPixels px, local density=$density",
            touchPixels + 0.001f >= minimumPixels)
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
}
