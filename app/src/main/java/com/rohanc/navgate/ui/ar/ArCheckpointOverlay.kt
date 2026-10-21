package com.rohanc.navgate.ui.ar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.core.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val Lime = Color(0xFFC6FF00)
private val Cyan = Color(0xFF00E5FF)
private val Orange = Color(0xFFFF8A00)
private val Ink = Color(0xFF0B1220)
private const val GATE_RADIUS = 1.9
private const val RING_STEPS = 28

/** A gate that was just collected: where, and when, so the pop animation can play. */
class GateBurst(val gate: Gate, val startedAtMillis: Long)

/**
 * Racing-game checkpoints over the road. The next gate glows and pulses, the few after it fade away behind it, a turn gate
 * shows the direction to take, and collecting a gate pops a ring. Only the handful of gates passed in [gates] are projected
 * per frame, so drawing costs almost nothing however long the route is.
 */
@Composable
fun ArCheckpointOverlay(gates: List<Gate>, position: GeoPoint?, basis: CameraBasis?, burst: GateBurst?, distanceToNext: Double?, modifier: Modifier = Modifier, arFrame: ArFrameState? = null) {
    val fov = rememberVerticalFov()
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val b = basis ?: return@Canvas
        val here = position ?: return@Canvas
        val projection = if (arFrame != null && arFrame.width > 0) ArProjection(size.width, size.height, arFrame.focalY * size.height / arFrame.height, arFrame.focalX * size.width / arFrame.width, arFrame.groundHeightMeters ?: 1.45)
        else ArProjection(size.width, size.height, fov)
        val now = arFrame?.reading?.timeMillis ?: System.currentTimeMillis()
        val pulse = 0.5f + 0.5f * sin((now % 1200L) / 1200f * 2f * PI.toFloat())

        fun at(e: Double, n: Double, lift: Double) = projection.ground(b, e, n, projection.eyeHeightMeters, lift)?.let { Offset(it.first, it.second) }
        // Trail: short dashes on the road from your feet through the gates, so the way between them is clear.
        var previous = here.offsetFrom(here)
        for (gate in gates) {
            val p = gate.point.offsetFrom(here)
            val length = hypot(p.first - previous.first, p.second - previous.second)
            var d = 1.5
            while (d < length - 1.0) {
                val t0 = d / length; val t1 = (d + 1.0) / length
                val a = at(previous.first + (p.first - previous.first) * t0, previous.second + (p.second - previous.second) * t0, 0.0)
                val c = at(previous.first + (p.first - previous.first) * t1, previous.second + (p.second - previous.second) * t1, 0.0)
                if (a != null && c != null) {
                    val metres = hypot(previous.first + (p.first - previous.first) * t0, previous.second + (p.second - previous.second) * t0)
                    val width = (7000f / (metres.toFloat() + 6f)).coerceIn(3f, 16f)
                    drawLine(Ink.copy(alpha = 0.55f), a, c, width * 1.6f, StrokeCap.Round); drawLine(Cyan.copy(alpha = (1f - (metres / 100f).toFloat()).coerceIn(0.2f, 0.9f)), a, c, width, StrokeCap.Round)
                }
                d += 3.0
            }
            previous = p
        }
        // Gates, far to near so the closest sits on top.
        for ((index, gate) in gates.withIndex().reversed()) {
            val active = index == 0
            val colour = when { gate.kind == GateKind.Turn -> Orange; active -> Lime; else -> Cyan }
            val alpha = if (active) 1f else (0.75f - index * 0.12f).coerceIn(0.2f, 0.75f)
            drawGate(projection, b, here, gate, colour, alpha, if (active) pulse else 0f, measurer, if (active) distanceToNext else null)
        }
        // Pop ring for the gate just collected.
        burst?.let {
            val age = (now - it.startedAtMillis).coerceAtLeast(0) / 700f
            if (age in 0f..1f) drawGate(projection, b, here, it.gate, Lime, 1f - age, 0f, measurer, null, scale = 1f + age * 1.8f)
        }
    }
}

private fun DrawScope.drawGate(projection: ArProjection, b: CameraBasis, here: GeoPoint, gate: Gate, colour: Color, alpha: Float, pulse: Float, measurer: TextMeasurer, distance: Double?, scale: Float = 1f) {
    val (ge, gn) = gate.point.offsetFrom(here)
    val heading = Math.toRadians(gate.headingDegrees)
    val lateralE = cos(heading); val lateralN = -sin(heading)           // to the right of travel
    val radius = GATE_RADIUS * scale
    val centreLift = radius
    fun pt(angle: Double, r: Double = radius): Offset? =
        projection.ground(b, ge + lateralE * r * cos(angle), gn + lateralN * r * cos(angle), projection.eyeHeightMeters, centreLift + r * sin(angle))?.let { Offset(it.first, it.second) }
    val ring = (0..RING_STEPS).map { pt(it * 2 * PI / RING_STEPS) }
    val thickness = ((ring.getOrNull(0)?.let { a -> ring.getOrNull(RING_STEPS / 2)?.let { c -> hypot(a.x - c.x, a.y - c.y) } } ?: 80f) * (0.035f + 0.015f * pulse)).coerceIn(4f, 22f)
    for (i in 0 until RING_STEPS) {
        val a = ring[i]; val c = ring[i + 1]
        if (a == null || c == null) continue
        drawLine(colour.copy(alpha = alpha * 0.28f), a, c, thickness * 2.6f, StrokeCap.Round)   // glow
        drawLine(Ink.copy(alpha = alpha * 0.6f), a, c, thickness * 1.5f, StrokeCap.Round)
        drawLine(colour.copy(alpha = alpha), a, c, thickness, StrokeCap.Round)
    }
    // Posts down to the road, like a racing checkpoint.
    for (side in listOf(-1.0, 1.0)) {
        val foot = projection.ground(b, ge + lateralE * radius * side, gn + lateralN * radius * side, projection.eyeHeightMeters, 0.0)?.let { Offset(it.first, it.second) }
        val shoulder = pt(if (side < 0) PI else 0.0)
        if (foot != null && shoulder != null) drawLine(colour.copy(alpha = alpha * 0.8f), shoulder, foot, thickness * 0.8f, StrokeCap.Round)
    }
    val centre = projection.ground(b, ge, gn, projection.eyeHeightMeters, centreLift)?.let { Offset(it.first, it.second) } ?: return
    if (gate.kind == GateKind.Turn && alpha > 0.4f) {
        // Arrow inside the ring points the way to turn.
        val sign = when (gate.turnKind) { "Left", "SlightLeft", "UTurn" -> -1.0; "Right", "SlightRight" -> 1.0; else -> 0.0 }
        if (sign != 0.0) {
            val tail = projection.ground(b, ge - lateralE * 0.9 * sign, gn - lateralN * 0.9 * sign, projection.eyeHeightMeters, centreLift)?.let { Offset(it.first, it.second) }
            val tip = projection.ground(b, ge + lateralE * 0.9 * sign, gn + lateralN * 0.9 * sign, projection.eyeHeightMeters, centreLift)?.let { Offset(it.first, it.second) }
            val wingA = projection.ground(b, ge + lateralE * 0.3 * sign, gn + lateralN * 0.3 * sign, projection.eyeHeightMeters, centreLift + 0.6)?.let { Offset(it.first, it.second) }
            val wingB = projection.ground(b, ge + lateralE * 0.3 * sign, gn + lateralN * 0.3 * sign, projection.eyeHeightMeters, centreLift - 0.6)?.let { Offset(it.first, it.second) }
            if (tail != null && tip != null && wingA != null && wingB != null) {
                drawLine(Ink, tail, tip, thickness * 2.2f, StrokeCap.Round); drawLine(colour, tail, tip, thickness * 1.3f, StrokeCap.Round)
                drawLine(Ink, tip, wingA, thickness * 2.2f, StrokeCap.Round); drawLine(colour, tip, wingA, thickness * 1.3f, StrokeCap.Round)
                drawLine(Ink, tip, wingB, thickness * 2.2f, StrokeCap.Round); drawLine(colour, tip, wingB, thickness * 1.3f, StrokeCap.Round)
            }
        }
    }
    val text = when {
        distance != null && gate.kind == GateKind.Turn -> "${turnWord(gate.turnKind)} · ${distance.toInt().coerceAtLeast(0)} m"
        distance != null && gate.kind == GateKind.Finish -> "Arrive · ${distance.toInt().coerceAtLeast(0)} m"
        distance != null -> "${distance.toInt().coerceAtLeast(0)} m"
        else -> null
    }
    if (text != null) {
        val top = projection.ground(b, ge, gn, projection.eyeHeightMeters, centreLift + radius + 0.7)?.let { Offset(it.first, it.second) } ?: centre
        val layout = measurer.measure(text, TextStyle(color = Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold))
        val pad = 14f; val w = layout.size.width + 2 * pad; val h = layout.size.height + 2 * pad
        val x = (top.x - w / 2).coerceIn(8f, size.width - w - 8f); val y = (top.y - h).coerceAtLeast(8f)
        drawRoundRect(Ink.copy(alpha = 0.9f), Offset(x - 3, y - 3), androidx.compose.ui.geometry.Size(w + 6, h + 6), androidx.compose.ui.geometry.CornerRadius(h / 2 + 3))
        drawRoundRect(colour, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawText(layout, topLeft = Offset(x + pad, y + pad))
    }
}

private fun turnWord(kind: String?) = when (kind) { "Left" -> "Turn left"; "SlightLeft" -> "Bear left"; "Right" -> "Turn right"; "SlightRight" -> "Bear right"; "UTurn" -> "U-turn"; "Roundabout" -> "Roundabout"; else -> "Continue" }
