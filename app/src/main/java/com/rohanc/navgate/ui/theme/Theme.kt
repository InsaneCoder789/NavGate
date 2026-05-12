package com.rohanc.navgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NavGateColors = darkColorScheme(
    primary = SignalTeal,
    secondary = SlateBlue,
    tertiary = CloudWhite,
)

@Composable
fun NavGateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NavGateColors,
        content = content,
    )
}
