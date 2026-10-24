package com.rohanc.navgate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
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

private val LightColors = lightColorScheme(
    primary = Color(0xFFD9482B), onPrimary = Color.White, primaryContainer = Color(0xFFFFDBD0), onPrimaryContainer = Color(0xFF4A1204),
    secondary = Color(0xFF6B4FD8), onSecondary = Color.White, secondaryContainer = Color(0xFFE9DDFF), onSecondaryContainer = Color(0xFF260F66),
    tertiary = Color(0xFFB26A00), background = Color(0xFFFBF7F5), onBackground = Color(0xFF201A1D),
    surface = Color.White, onSurface = Color(0xFF201A1D), surfaceVariant = Color(0xFFF3ECEF), onSurfaceVariant = Color(0xFF665A62),
    outline = Color(0xFF9A8E96), outlineVariant = Color(0xFFE2D9DE), error = Color(0xFFD93025), onError = Color.White,
    inverseSurface = Color(0xFF33292F), inverseOnSurface = Color(0xFFF7EEF2),
)

private val DarkColors = darkColorScheme(
    primary = NavPrimary, onPrimary = NavOnPrimary, primaryContainer = Color(0xFF6B2F1D), onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = NavSecondary, onSecondary = Color(0xFF2A1457), secondaryContainer = NavSecondaryContainer, onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = NavTertiary, background = NavBackground, onBackground = NavOnSurface,
    surface = NavSurface, onSurface = NavOnSurface, surfaceVariant = NavSurfaceHigh, onSurfaceVariant = NavOnSurfaceVariant,
    outline = Color(0xFF8C8096), outlineVariant = Color(0xFF3F3548), error = Color(0xFFFF8A80), onError = Color(0xFF601410),
    inverseSurface = Color(0xFFF4EEF7), inverseOnSurface = Color(0xFF1A1422),
)

@Composable
fun NavGateTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if(dark) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall=RoundedCornerShape(8.dp), small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp), large=RoundedCornerShape(24.dp),
            extraLarge=RoundedCornerShape(32.dp),
        ),
        typography = NavGateTypography,
        content = content,
    )
}
