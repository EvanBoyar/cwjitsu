package com.cwjitsu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Every role is set explicitly. Material 3's darkColorScheme() leaves unset
// roles at its baseline purple, which is exactly what was bleeding through
// the selected category cards (primaryContainer) and other containers, so we
// pin them all to the dial-glow palette.
private val CwColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    primaryContainer = AmberContainer,
    onPrimaryContainer = OnAmberContainer,
    inversePrimary = AmberDim,

    // Secondary/tertiary stay in the neutral/dimmed-amber family so amber
    // remains the single accent rather than competing with a second hue.
    secondary = AmberDim,
    onSecondary = OnAmber,
    secondaryContainer = PanelRaised,
    onSecondaryContainer = Ink,
    tertiary = InkMuted,
    onTertiary = Charcoal,
    tertiaryContainer = PanelRaised,
    onTertiaryContainer = Ink,

    background = Charcoal,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = InkMuted,
    surfaceTint = Amber,
    inverseSurface = Ink,
    inverseOnSurface = Panel,

    outline = Hairline,
    outlineVariant = HairlineDim,

    error = SignalRed,
    onError = OnSignalRed,
    errorContainer = SignalRedContainer,
    onErrorContainer = OnSignalRedContainer,
)

@Composable
fun CWJitsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CwColorScheme,
        typography = CwTypography,
        content = content,
    )
}
