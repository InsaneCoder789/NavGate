package com.rohanc.navgate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Layered tinted glass, without blurring content or claiming map-backdrop blur.
 * Dense surfaces protect reading contrast; reduce-transparency removes every optical layer.
 * The wrapper adds no padding, sizing or navigation semantics to the existing wireframe.
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
    val solid = LocalSolidGlass.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val tint = baseColor.copy(alpha = if (solid) 1f else if (dense) .97f else if (dark) .90f else .88f)
    val edge = if (solid) MaterialTheme.colorScheme.outlineVariant else Color.White
    val border = BorderStroke(1.dp, Brush.verticalGradient(listOf(
        edge.copy(alpha = if (solid) .55f else if (dark) .24f else .82f),
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (solid) .55f else .22f),
    )))
    val sheen = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = if (solid) 0f else if (dark) .075f else .22f),
        Color.White.copy(alpha = if (solid) 0f else .015f),
        MaterialTheme.colorScheme.primary.copy(alpha = if (solid) 0f else .018f),
    ))
    val body: @Composable () -> Unit = {
        Box(Modifier.background(sheen), propagateMinConstraints = true) { content() }
    }
    if (onClick == null) {
        Surface(modifier, shape = shape, color = tint, contentColor = contentColor,
            border = border, shadowElevation = shadowElevation, content = body)
    } else {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = tint,
            contentColor = contentColor, border = border, shadowElevation = shadowElevation, content = body)
    }
}
