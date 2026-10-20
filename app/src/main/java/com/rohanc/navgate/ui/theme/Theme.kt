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

@Composable
fun NavGateTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if(dark) NavGateColors else lightColorScheme(
            primary=Color(0xFF1557B0),onPrimary=Color.White,primaryContainer=Color(0xFFDDEAFF),onPrimaryContainer=Color(0xFF123C70),background=Color(0xFFEEF3F8),
            surface=Color(0xFFF8FBFF),surfaceVariant=Color(0xFFE6EEF7),onSurface=Color(0xFF142435),
            onBackground=Color(0xFF142435),onSurfaceVariant=Color(0xFF40566B),secondary=Color(0xFF00687C),
            secondaryContainer=Color(0xFFE1EDF5),onSecondaryContainer=Color(0xFF244358),outline=Color(0xFF718498),
        ),
        shapes = Shapes(
            extraSmall=RoundedCornerShape(8.dp), small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp), large=RoundedCornerShape(24.dp),
            extraLarge=RoundedCornerShape(32.dp),
        ),
        typography = NavGateTypography,
        content = content,
    )
}
