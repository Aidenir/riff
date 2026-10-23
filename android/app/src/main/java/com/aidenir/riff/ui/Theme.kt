package com.aidenir.riff.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.aidenir.riff.ffi.ThemePreference

// From the app icon: the green and teal badge, the blue centre.
private val Light = lightColorScheme(
    primary = Color(0xFF0F6E59),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D4),
    onPrimaryContainer = Color(0xFF002118),
    secondary = Color(0xFF4460C8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE1FF),
    onSecondaryContainer = Color(0xFF001551),
    tertiary = Color(0xFF1D7A86),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB0ECF5),
    onTertiaryContainer = Color(0xFF001F24),
    background = Color(0xFFF8FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF8FAF8),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDDE4E0),
    onSurfaceVariant = Color(0xFF414945),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F2),
    surfaceContainer = Color(0xFFECEFEC),
    surfaceContainerHigh = Color(0xFFE6E9E7),
    surfaceContainerHighest = Color(0xFFE1E3E1),
    outline = Color(0xFF717975),
    outlineVariant = Color(0xFFC1C8C4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF52E2A7),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513E),
    onPrimaryContainer = Color(0xFFA6F2D4),
    secondary = Color(0xFFB6C4FF),
    onSecondary = Color(0xFF0B2A8E),
    secondaryContainer = Color(0xFF2B47AE),
    onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = Color(0xFF7FD3E0),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58),
    onTertiaryContainer = Color(0xFFB0ECF5),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF414945),
    onSurfaceVariant = Color(0xFFC1C8C4),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF191C1B),
    surfaceContainer = Color(0xFF1D201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323534),
    outline = Color(0xFF8B938F),
    outlineVariant = Color(0xFF414945),
)

@Composable
fun isDark(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
}

/** The fixed Riff theme, light or dark as the settings say. No dynamic colour. */
@Composable
fun RiffTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
