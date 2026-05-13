package com.rohanc.navgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NavGateColors =
    darkColorScheme(
        primary = NavPrimary,
        onPrimary = NavOnPrimary,
        primaryContainer = NavPrimaryContainer,
        onPrimaryContainer = Color.White,
        secondary = NavSecondary,
        onSecondary = Color.Black,
        secondaryContainer = NavSecondaryContainer,
        onSecondaryContainer = Color.White,
        tertiary = NavTertiary,
        onTertiary = Color.Black,
        background = NavBackground,
        onBackground = NavOnSurface,
        surface = NavSurface,
        onSurface = NavOnSurface,
        surfaceVariant = NavSurfaceHigh,
        onSurfaceVariant = NavOnSurfaceVariant,
        outline = GlassStroke,
        error = NavLowConfidence,
        onError = Color.White,
    )

@Composable
fun NavGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NavGateColors,
        typography = NavGateTypography,
        content = content,
    )
}
