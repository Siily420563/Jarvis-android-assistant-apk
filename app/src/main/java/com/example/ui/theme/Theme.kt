package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CyberBg,
    primaryContainer = CyberCardBg,
    onPrimaryContainer = NeonCyan,
    secondary = ElegantPurple,
    onSecondary = CyberBg,
    background = CyberBg,
    onBackground = TextPrimary,
    surface = CyberCardBg,
    onSurface = TextPrimary,
    surfaceVariant = ElegantCardBg,
    onSurfaceVariant = TextSecondary,
    outline = CyberCardBorder
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JarvisDarkColorScheme,
        typography = Typography,
        content = content
    )
}
