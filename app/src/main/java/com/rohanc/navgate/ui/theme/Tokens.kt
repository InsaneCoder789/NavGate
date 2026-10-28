package com.rohanc.navgate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** The shared look of NavGate Next: one place for the colours and shapes every screen draws from (see docs/NAVGATE_NEXT_BUILD_SPEC.html). */
object NavTokens {
    val PulseLightStart = Color(0xFF2F6BFF); val PulseLightEnd = Color(0xFF13B8FF)
    val PulseDarkStart = Color(0xFF3F73F5); val PulseDarkEnd = Color(0xFF1FA8E6)
    val CoralLight = Color(0xFFFF6B4A); val CoralDark = Color(0xFFFF7D5E)
    val GoLight = Color(0xFF0E9F5B); val GoDark = Color(0xFF34D399)
    val PorcelainTop = Color(0xFFFFFFFF); val PorcelainBottom = Color(0xFFEAF0F9); val PorcelainEdge = Color(0x33405A82)
    val RadiusRound = 52.dp; val RadiusCard = 24.dp; val RadiusSheet = 28.dp

    @Composable @ReadOnlyComposable fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    /** The blue-to-cyan gradient of the primary action, the route line and active states. */
    @Composable fun pulse(): Brush = if (isDark()) Brush.linearGradient(listOf(PulseDarkStart, PulseDarkEnd)) else Brush.linearGradient(listOf(PulseLightStart, PulseLightEnd))
    @Composable @ReadOnlyComposable fun coral(): Color = if (isDark()) CoralDark else CoralLight
    @Composable @ReadOnlyComposable fun go(): Color = if (isDark()) GoDark else GoLight
    /** Light controls and sheets: bright at the top, a cool pale white at the bottom. */
    fun porcelain(): Brush = Brush.verticalGradient(listOf(PorcelainTop, PorcelainBottom))
}
