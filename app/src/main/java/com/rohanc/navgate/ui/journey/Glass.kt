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

/** 0 = nearly clear glass, 1 = solid. Set from Settings; every panel reads it. */
val LocalGlassOpacity = compositionLocalOf { 0.4f }

enum class GlassWeight { Thin, Regular, Thick }

/**
 * Liquid glass: the map behind the panel is strongly blurred, lifted toward white, and edged with a bright rim and a soft
 * top-left sheen, so every surface in the app looks like the round map buttons. How see-through it is comes from the
 * Settings slider. Without a blur source (older phones, tests) it falls back to a flat tint of the same strength.
 */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(28.dp), weight: GlassWeight = GlassWeight.Regular,
    shadow: Boolean = true, tint: Color? = null, contentColor: Color? = null, onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val solid = LocalSolidGlass.current
    val haze = LocalHazeState.current
    val opacity = LocalGlassOpacity.current.coerceIn(0f, 1f)
    // Same strength for every weight, so sheets and buttons match; weight only nudges it.
    val nudge = when (weight) { GlassWeight.Thin -> -0.04f; GlassWeight.Regular -> 0f; GlassWeight.Thick -> 0.03f }
    val tintAlpha = (0.06f + 0.92f * opacity + nudge).coerceIn(0.04f, 0.98f)
    val raw = tint ?: scheme.surface
    val base = androidx.compose.ui.graphics.lerp(raw, Color.White, if (dark) 0.10f else 0.0f)
    val onBase = contentColor ?: scheme.onSurface
    val blur = (26 - 10 * opacity).dp
    val effect = if (haze != null && !solid) Modifier.hazeEffect(haze, HazeStyle(
        backgroundColor = base, tints = listOf(HazeTint(base.copy(alpha = tintAlpha))), blurRadius = blur, noiseFactor = 0.03f)) else Modifier
    val fallbackAlpha = if (solid) 1f else if (haze == null) (tintAlpha + 0.15f).coerceAtMost(0.98f) else 0f
    val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.55f else 0.95f), Color.White.copy(alpha = if (dark) 0.10f else 0.25f), Color.White.copy(alpha = if (dark) 0.32f else 0.7f)))
    val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.35f), Color.Transparent, Color.White.copy(alpha = if (dark) 0.05f else 0.12f)))
    val shadowDp = if (shadow) (8f * (0.4f + opacity)).dp else 0.dp
    val body: @Composable () -> Unit = { Box(Modifier.clip(shape).then(effect).background(base.copy(alpha = fallbackAlpha)).background(sheen), content = content) }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = Color.Transparent, contentColor = onBase, border = BorderStroke(1.2.dp, rim), shadowElevation = shadowDp) { body() }
    else Surface(modifier = modifier, shape = shape, color = Color.Transparent, contentColor = onBase, border = BorderStroke(1.2.dp, rim), shadowElevation = shadowDp) { body() }
}
