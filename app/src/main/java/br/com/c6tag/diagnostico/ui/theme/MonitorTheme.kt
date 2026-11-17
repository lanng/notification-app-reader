package br.com.c6tag.diagnostico.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import br.com.c6tag.diagnostico.monitor.ThemePreference

@Composable
internal fun MonitorTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    systemDark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> systemDark
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

private val LightColors = lightColorScheme(
    background = Color(0xFFF8F7FC), onBackground = Color(0xFF292232),
    surface = Color.White, onSurface = Color(0xFF292232),
    surfaceVariant = Color(0xFFF0E9FB), onSurfaceVariant = Color(0xFF70677E),
    primary = Color(0xFF6C42AE), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E9FB), onPrimaryContainer = Color(0xFF4B247E),
    secondary = Color(0xFF70677E), outline = Color(0xFF70677E),
    outlineVariant = Color(0xFFE6E0EF),
    tertiary = Color(0xFF805015), tertiaryContainer = Color(0xFFFFF3DC),
    onTertiaryContainer = Color(0xFF805015),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF0D0B10), onBackground = Color(0xFFF2EEF8),
    surface = Color(0xFF17141D), onSurface = Color(0xFFF2EEF8),
    surfaceVariant = Color(0xFF2A1D3D), onSurfaceVariant = Color(0xFFB0A6BD),
    primary = Color(0xFFC6A6F2), onPrimary = Color(0xFF29143F),
    primaryContainer = Color(0xFF2A1D3D), onPrimaryContainer = Color(0xFFC6A6F2),
    secondary = Color(0xFFB0A6BD), outline = Color(0xFFB0A6BD),
    outlineVariant = Color(0xFF322A3D),
    tertiary = Color(0xFFF2CF91), tertiaryContainer = Color(0xFF302719),
    onTertiaryContainer = Color(0xFFF2CF91),
)
