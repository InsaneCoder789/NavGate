package com.rohanc.navgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NavGateColors =
    darkColorScheme(
        primary = NavPrimary,
        onPrimary = NavOnPrimary,
        primaryContainer = NavPrimaryContainer,
        onPrimaryContainer = NavBackground,
        secondary = NavSecondary,
        onSecondary = NavBackground,
        secondaryContainer = NavSecondaryContainer,
        onSecondaryContainer = NavOnSurface,
        tertiary = NavTertiary,
        onTertiary = NavBackground,
        tertiaryContainer = NavTertiaryContainer,
        onTertiaryContainer = NavBackground,
        background = NavBackground,
        onBackground = NavOnSurface,
        surface = NavSurface,
        onSurface = NavOnSurface,
        surfaceVariant = NavSurfaceHighest,
        onSurfaceVariant = NavOnSurfaceVariant,
        surfaceContainer = NavSurface,
        surfaceContainerHigh = NavSurfaceHigh,
        surfaceContainerHighest = NavSurfaceHighest,
        outline = NavOutline,
        outlineVariant = NavOutlineVariant,
        error = NavError,
        onError = NavBackground,
    )

@Composable
fun NavGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NavGateColors,
        typography = NavGateTypography,
        content = content,
    )
}
