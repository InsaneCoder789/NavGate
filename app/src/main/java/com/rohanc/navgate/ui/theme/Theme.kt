package com.rohanc.navgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
fun NavGateTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if(dark) NavGateColors else lightColorScheme(
            primary=Color(0xFF1557B0),onPrimary=Color.White,background=Color(0xFFEEF3F8),
            surface=Color(0xFFF8FBFF),surfaceVariant=Color(0xFFE6EEF7),onSurface=Color(0xFF142435),
            onBackground=Color(0xFF142435),onSurfaceVariant=Color(0xFF40566B),secondary=Color(0xFF00687C),
        ),
        typography = NavGateTypography,
        content = content,
    )
}
