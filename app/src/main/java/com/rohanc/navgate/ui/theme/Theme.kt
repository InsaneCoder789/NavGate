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

private val GoogleLightColors = lightColorScheme(
    primary = Color(0xFF2F7A88), onPrimary = Color.White, primaryContainer = Color(0xFFD8F0F5), onPrimaryContainer = Color(0xFF0E4652),
    secondary = Color(0xFF1A73E8), onSecondary = Color.White, secondaryContainer = Color(0xFFE8F0FE), onSecondaryContainer = Color(0xFF174EA6),
    tertiary = Color(0xFF188038), background = Color(0xFFF1F3F4), onBackground = Color(0xFF202124),
    surface = Color.White, onSurface = Color(0xFF202124), surfaceVariant = Color(0xFFF1F3F4), onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFF9AA0A6), outlineVariant = Color(0xFFDADCE0), error = Color(0xFFD93025), onError = Color.White,
    inverseSurface = Color(0xFF303134), inverseOnSurface = Color(0xFFF1F3F4),
)

private val GoogleDarkColors = darkColorScheme(
    primary = Color(0xFF7FD0DF), onPrimary = Color(0xFF00363F), primaryContainer = Color(0xFF1F4A55), onPrimaryContainer = Color(0xFFD8F0F5),
    secondary = Color(0xFF8AB4F8), onSecondary = Color(0xFF062E6F), secondaryContainer = Color(0xFF1F3A63), onSecondaryContainer = Color(0xFFD2E3FC),
    tertiary = Color(0xFF81C995), background = Color(0xFF121212), onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF202124), onSurface = Color(0xFFE8EAED), surfaceVariant = Color(0xFF303134), onSurfaceVariant = Color(0xFFBDC1C6),
    outline = Color(0xFF80868B), outlineVariant = Color(0xFF3C4043), error = Color(0xFFF28B82), onError = Color(0xFF601410),
    inverseSurface = Color(0xFFE8EAED), inverseOnSurface = Color(0xFF202124),
)

@Composable
fun NavGateTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if(dark) GoogleDarkColors else GoogleLightColors,
        shapes = Shapes(
            extraSmall=RoundedCornerShape(8.dp), small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp), large=RoundedCornerShape(24.dp),
            extraLarge=RoundedCornerShape(32.dp),
        ),
        typography = NavGateTypography,
        content = content,
    )
}
