package br.com.c6tag.diagnostico.ui.monitor

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import br.com.c6tag.diagnostico.monitor.ThemePreference
import br.com.c6tag.diagnostico.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MonitorThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitChoiceOverridesSystemAndSystemFollowsDeviceWithApprovedTokens() {
        val preference = mutableStateOf(ThemePreference.LIGHT)
        val systemDark = mutableStateOf(true)
        var background: Color? = null
        var foreground: Color? = null
        var primary: Color? = null
        compose.setContent {
            MonitorTheme(preference.value, systemDark.value) {
                val colors = MaterialTheme.colorScheme
                SideEffect { background = colors.background; foreground = colors.onBackground; primary = colors.primary }
            }
        }
        compose.runOnIdle {
            assertEquals(Color(0xFFF8F7FC), background)
            assertEquals(Color(0xFF292232), foreground)
            assertEquals(Color(0xFF6C42AE), primary)
            preference.value = ThemePreference.DARK
            systemDark.value = false
        }
        compose.runOnIdle {
            assertEquals(Color(0xFF0D0B10), background)
            assertEquals(Color(0xFFF2EEF8), foreground)
            assertEquals(Color(0xFFC6A6F2), primary)
            preference.value = ThemePreference.SYSTEM
        }
        compose.runOnIdle {
            assertEquals(Color(0xFFF8F7FC), background)
            systemDark.value = true
        }
        compose.runOnIdle { assertEquals(Color(0xFF0D0B10), background) }
    }
}
