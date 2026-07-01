package com.cwjitsu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CwColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = NavyDeep,
    secondary = Gold,
    onSecondary = NavyDeep,
    tertiary = TealDim,
    background = NavyDeep,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    error = ErrorRed,
    onError = NavyDeep,
)

@Composable
fun CWJitsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CwColorScheme,
        typography = CwTypography,
        content = content,
    )
}
