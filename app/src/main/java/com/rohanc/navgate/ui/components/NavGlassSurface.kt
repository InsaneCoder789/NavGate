package com.rohanc.navgate.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.journey.GlassPanel
import com.rohanc.navgate.ui.journey.GlassWeight

/**
 * Frosted glass, as one implementation: this forwards to [GlassPanel] so every panel in the app blurs the map behind it
 * the same way. Dense surfaces protect reading contrast; reduce-transparency makes them solid.
 */
@Composable
fun NavGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    baseColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    dense: Boolean = false,
    shadowElevation: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassPanel(modifier, shape, if (dense) GlassWeight.Thick else GlassWeight.Regular, shadow = shadowElevation > 0.dp,
        tint = baseColor, contentColor = contentColor, onClick = onClick) { content() }
}
