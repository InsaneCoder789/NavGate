package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.components.LocalSolidGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The blurred-backdrop source shared by every glass panel; null when no map sits behind (tests, previews). */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

enum class GlassWeight { Thin, Regular, Thick }

/**
 * Frosted glass over the map: the map behind the panel is genuinely blurred (Android 12+), then tinted, with a bright
 * edge and a faint sheen. Older phones and "reduce transparency" fall back to a nearly opaque tint, which keeps text legible.
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), weight: GlassWeight = GlassWeight.Regular,
    shadow: Boolean = true, tint: Color? = null, contentColor: Color? = null, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val solid = LocalSolidGlass.current
    val haze = LocalHazeState.current
    val tintAlpha = when (weight) { GlassWeight.Thin -> if (dark) 0.42f else 0.50f; GlassWeight.Regular -> if (dark) 0.58f else 0.66f; GlassWeight.Thick -> if (dark) 0.78f else 0.84f }
    val base = tint ?: scheme.surface
    val onBase = contentColor ?: scheme.onSurface
    val effect = if (haze != null && !solid) Modifier.hazeEffect(haze, HazeStyle(
        backgroundColor = base, tints = listOf(HazeTint(base.copy(alpha = tintAlpha))), blurRadius = if (weight == GlassWeight.Thin) 14.dp else 22.dp, noiseFactor = 0.04f)) else Modifier
    val fallbackAlpha = if (solid) 1f else if (haze == null) (tintAlpha + 0.22f).coerceAtMost(0.96f) else 0f
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.28f else 0.85f), scheme.outlineVariant.copy(alpha = if (dark) 0.25f else 0.4f)))
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.07f else 0.28f), Color.Transparent, scheme.primary.copy(alpha = 0.025f)))
    val body: @Composable () -> Unit = { Box(Modifier.clip(shape).then(effect).background(base.copy(alpha = fallbackAlpha)).background(sheen), content = content) }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = Color.Transparent, contentColor = onBase, border = BorderStroke(1.dp, edge), shadowElevation = if (shadow) 8.dp else 0.dp) { body() }
    else Surface(modifier = modifier, shape = shape, color = Color.Transparent, contentColor = onBase, border = BorderStroke(1.dp, edge), shadowElevation = if (shadow) 8.dp else 0.dp) { body() }
}
