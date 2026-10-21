package com.rohanc.navgate.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.R
import com.rohanc.navgate.data.local.NavGateServices
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A bounded, one-shot brand reveal. It never owns or advances navigation state. */
@Composable
internal fun BrandLaunch(onFinished: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion by produceState<Boolean?>(null, context) {
        val systemDisabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        value = systemDisabled || runCatching { NavGateServices.get(context).preferences.frontendPreferences().reduceMotion }.getOrDefault(true)
    }
    BrandLaunchAnimation(reducedMotion, onFinished)
}

@Composable
internal fun BrandLaunchAnimation(reducedMotion: Boolean?, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reducedMotion) {
        when (reducedMotion) {
            null -> Unit
            true -> finished()
            false -> { progress.animateTo(1f, tween(1_450, easing = LinearEasing)); finished() }
        }
    }
    BrandLaunchFrame(progress.value, Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    })
}

/** Deterministic frames let device checks inspect formation and dissolution without slowing real launches. */
@Composable
internal fun BrandLaunchFrame(progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    val forming = smoothPhase(p, 0.04f, 0.40f)
    val leaving = smoothPhase(p, 0.64f, 1f)
    val logoOpacity = forming * (1f - leaving)
    val veilOpacity = 1f - smoothPhase(p, 0.83f, 1f)
    val ink = Color(0xFF08111F)
    Box(modifier.fillMaxSize().graphicsLayer { alpha = veilOpacity }.background(ink)
        .semantics { contentDescription = "NavGate starting" }, contentAlignment = Alignment.Center) {
        // Every wisp has a stable seed. The field gathers into the mark, then unravels along a gentle curl.
        Canvas(Modifier.size(300.dp).graphicsLayer { alpha = (1f - smoothPhase(p, 0.88f, 1f)) }) {
            val spread = 0.15f + (1f - forming) * 0.72f + leaving * 1.12f
            val mistAlpha = (sin(PI * p).toFloat() * 0.17f).coerceAtLeast(0f)
            repeat(18) { i ->
                val phase = i * 2.39996f
                val angle = phase + p * 1.6f + leaving * 0.7f
                val orbit = size.minDimension * (0.13f + (i % 5) * 0.034f) * spread
                val x = center.x + cos(angle) * orbit
                val y = center.y + sin(angle * 1.14f) * orbit * 0.72f - leaving * size.height * 0.10f
                val radius = size.minDimension * (0.11f + (i % 4) * 0.015f)
                val tint = if (i % 3 == 0) Color(0xFF397CFF) else Color(0xFF73DEED)
                withTransform({
                    translate(x, y)
                    rotate(angle * 57.2958f, Offset.Zero)
                    scale(1.75f, 0.62f, Offset.Zero)
                }) {
                    drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = mistAlpha), tint.copy(alpha = mistAlpha * 0.35f), Color.Transparent),
                        center = Offset.Zero, radius = radius), radius, center = Offset.Zero)
                }
            }
        }
        val softness = (1f - forming) * 15f + leaving * 13f
        Image(painterResource(R.drawable.navgate_brand_mark), "NavGate", contentScale = ContentScale.Fit,
            modifier = Modifier.size(136.dp).graphicsLayer {
                alpha = logoOpacity
                scaleX = 0.96f + forming * 0.04f + leaving * 0.025f
                scaleY = scaleX
                translationY = -leaving * 6.dp.toPx()
            }.blur(softness.dp, BlurredEdgeTreatment.Unbounded))
    }
}

private fun smoothPhase(progress: Float, start: Float, end: Float): Float {
    val t = ((progress - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
