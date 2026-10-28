package com.rohanc.navgate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BFF), onPrimary = Color.White, primaryContainer = Color(0xFFE4ECFF), onPrimaryContainer = Color(0xFF0F2A66),
    secondary = Color(0xFF13B8FF), onSecondary = Color.White, secondaryContainer = Color(0xFFE4ECFF), onSecondaryContainer = Color(0xFF0F2A66),
    tertiary = Color(0xFFB26A00), background = Color(0xFFFBF7F5), onBackground = Color(0xFF18202C),
    surface = Color.White, onSurface = Color(0xFF18202C), surfaceVariant = Color(0xFFEEF2F8), onSurfaceVariant = Color(0xFF5A6678),
    outline = Color(0xFF6B778C), outlineVariant = Color(0xFFD0D9E6), error = Color(0xFFD93025), onError = Color.White,
    surfaceContainerHighest = Color(0xFFD9E0EC), surfaceContainerHigh = Color(0xFFE3E9F3), surfaceContainer = Color(0xFFEDF1F8),
    inverseSurface = Color(0xFF1F2937), inverseOnSurface = Color(0xFFEEF3FA),
)

private val DarkColors = darkColorScheme(
    primary = NavPrimary, onPrimary = NavOnPrimary, primaryContainer = NavPrimaryContainer, onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = NavSecondary, onSecondary = Color(0xFF0F2A57), secondaryContainer = NavSecondaryContainer, onSecondaryContainer = Color(0xFFDCE8FF),
    tertiary = NavTertiary, background = NavBackground, onBackground = NavOnSurface,
    surface = NavSurface, onSurface = NavOnSurface, surfaceVariant = NavSurfaceHigh, onSurfaceVariant = NavOnSurfaceVariant,
    outline = Color(0xFF8191AB), outlineVariant = Color(0xFF33415C), error = Color(0xFFFF8A80), onError = Color(0xFF601410),
    inverseSurface = Color(0xFFEEF3FA), inverseOnSurface = Color(0xFF121B2E),
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
