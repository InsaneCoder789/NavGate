package com.rohanc.navgate.ui.journey

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohanc.navgate.core.MotionEstimate
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A car-style speedometer: a three-quarter ring of arrowheads that light up one after another as the speed rises, each glowing in the colour of
 * its speed band, with the speed itself as a digital figure in the middle. Full scale is 260 km/h.
 */
@Composable
fun SpeedGauge(motion: StateFlow<MotionEstimate?>, onClick: () -> Unit, modifier: Modifier = Modifier, noSignal: Boolean = false) {
    val e by motion.collectAsStateWithLifecycle()
    val kmh = ((e?.speedMps ?: 0.0) * 3.6).coerceAtLeast(0.0)
    // Without a fresh position the speed is a guess: show a dash, never a number that looks live.
    SpeedGaugeFace(if (noSignal) 0.0 else kmh, onClick, modifier, unknown = noSignal)
}

internal const val GAUGE_MAX_KMH = 260.0
private const val ARROWS = 30
private val GAUGE_SIZE = 92.dp

/** Cool blue while cruising, brighter cyan above 90, amber from 130 and red from 190: the colour says how fast without reading the number. */
internal fun gaugeColor(kmh: Double): Color = when {
    kmh >= 190 -> Color(0xFFFF4D4D)
    kmh >= 130 -> Color(0xFFFFB020)
    kmh >= 90 -> Color(0xFF38D0F8)
    else -> Color(0xFF5B9BFF)
}

@Composable
internal fun SpeedGaugeFace(kmh: Double, onClick: () -> Unit, modifier: Modifier = Modifier, unknown: Boolean = false) {
    // The ring follows the speed smoothly instead of stepping with every reading.
    val shown by animateFloatAsState(kmh.toFloat(), tween(220), label = "gauge")
    // A fixed-size dial: large accessibility text may not push the figure out of the ring, so its scaling stops at 1.2x.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val keep = if (fontScale > 1.2f) 1.2f / fontScale else 1f
    val lit = (shown / GAUGE_MAX_KMH * ARROWS).coerceIn(0.0, ARROWS.toDouble()).toFloat()
    val colour = gaugeColor(shown.toDouble())
    Box(modifier.size(GAUGE_SIZE).testTag("gauge").clip(CircleShape).background(Color(0xCC0B1426)).clickable(onClick = onClick).semantics { contentDescription = "Speed ${kmh.roundToInt()} kilometres per hour. Tap for sensor readings" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(GAUGE_SIZE)) { drawArrows(lit, colour, shown.toDouble()) }
        // The figure sits right on top of the KM/H tag, and the pair is a little below the middle, where the open bottom of the ring leaves room.
        Column(Modifier.offset(y = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (unknown) "—" else "${shown.roundToInt()}", Modifier.testTag("gauge-speed"), color = Color.White, maxLines = 1, softWrap = false,
                style = androidx.compose.ui.text.TextStyle(fontSize = (26f * keep).sp, lineHeight = (26f * keep).sp, fontWeight = FontWeight.Bold, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)))
            Text("KM/H", Modifier.testTag("gauge-unit"), color = Color.White.copy(alpha = 0.66f), maxLines = 1,
                style = androidx.compose.ui.text.TextStyle(fontSize = (8f * keep).sp, lineHeight = (9f * keep).sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.SemiBold, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
        }
    }
}

private fun DrawScope.drawArrows(lit: Float, colour: Color, kmh: Double) {
    val u = size.minDimension / 132.dp.toPx()
    val c = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f - 13.dp.toPx() * u
    val sweep = 270.0; val start = 135.0
    for (i in 0 until ARROWS) {
        val angle = Math.toRadians(start + sweep * i / (ARROWS - 1))
        val at = Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())
        // Each arrowhead points the way the ring turns (clockwise), like the chevrons on a car's speed display.
        val forward = angle + PI / 2
        val tip = Offset(at.x + 6.dp.toPx() * u * cos(forward).toFloat(), at.y + 6.dp.toPx() * u * sin(forward).toFloat())
        val back = Offset(at.x - 4.dp.toPx() * u * cos(forward).toFloat(), at.y - 4.dp.toPx() * u * sin(forward).toFloat())
        val across = angle
        val a = Offset(back.x + 5.dp.toPx() * u * cos(across).toFloat(), back.y + 5.dp.toPx() * u * sin(across).toFloat())
        val b = Offset(back.x - 5.dp.toPx() * u * cos(across).toFloat(), back.y - 5.dp.toPx() * u * sin(across).toFloat())
        val head = Path().apply { moveTo(a.x, a.y); lineTo(tip.x, tip.y); lineTo(b.x, b.y) }
        val on = i + 1 <= lit + 0.001f || (i < lit)
        val fraction = if (on) 1f else (lit - i).coerceIn(0f, 1f)
        if (fraction > 0.02f) {
            // The glow: the same arrowhead wider and fainter, behind the bright one. The newest arrowhead burns brightest.
            val newest = (lit - i) in 0f..1.6f
            drawPath(head, colour.copy(alpha = 0.22f * fraction), style = Stroke(width = (if (newest) 10 else 8).dp.toPx() * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(head, colour.copy(alpha = fraction), style = Stroke(width = 3.2.dp.toPx() * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawPath(head, Color.White.copy(alpha = 0.16f), style = Stroke(width = 2.4.dp.toPx() * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}
