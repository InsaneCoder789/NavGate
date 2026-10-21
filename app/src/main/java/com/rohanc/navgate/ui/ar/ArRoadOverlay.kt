package com.rohanc.navgate.ui.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.projectPath
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val LOOK_AHEAD_METERS = 90.0
private const val STEP_METERS = 2.0
private const val RIBBON_HALF_WIDTH = 0.7

/** Live rear-camera axes from the rotation-vector sensor, in a true-north frame. Null until the first reading. */
@Composable
fun rememberCameraBasis(enabled: Boolean, position: () -> GeoPoint?): State<CameraBasis?> {
    val context = LocalContext.current
    val basis = remember { mutableStateOf<CameraBasis?>(null) }
    DisposableEffect(context, enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        var declination = 0.0
        var declinationAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val now = System.currentTimeMillis()
                if (now - declinationAt > 60_000) position()?.let {
                    declination = GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, now).declination.toDouble(); declinationAt = now
                }
                basis.value = cameraBasis(matrix, declination)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (enabled && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    return basis
}

/** Vertical (portrait long-side) field of view of the rear camera, from its sensor size and focal length; 65 degrees if unreadable. */
@Composable
fun rememberVerticalFov(): Double {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.first { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            val c = manager.getCameraCharacteristics(id)
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
            Math.toDegrees(2 * atan2(max(size.width, size.height) / 2.0, focal.toDouble()))
        }.getOrDefault(65.0).coerceIn(40.0, 90.0)
    }
}

/** How close to the route the phone must be before AR guidance is offered. */
const val AR_MAX_DISTANCE_FROM_ROUTE = 25.0

/** Null when AR guidance may run; otherwise the reason it may not. AR only works while standing on the planned road. */
fun arBlockedReason(route: CampusRoute?, position: GeoPoint?, accuracyMeters: Double?): String? {
    if (route == null || route.geometry.size < 2) return "Plan a route first to use AR."
    if (position == null) return "Waiting for your location."
    if ((accuracyMeters ?: 0.0) > 35.0) return "Location is too rough for AR. Step into the open and try again."
    val off = projectPath(position, route.geometry)?.distanceMeters ?: return "Waiting for your location."
    return if (off > AR_MAX_DISTANCE_FROM_ROUTE) "AR works once you are on the route. You are ${off.toInt()} m away from it." else null
}

private val Cyan = Color(0xFF00E5FF)
private val Lime = Color(0xFFC6FF00)
private val Ink = Color(0xFF0B1220)

/**
 * Draws only the road you are on: a glowing ribbon from your feet to the end of this road, then a bold turn plate
 * showing left, right or straight, with a short faded stub of the road you will enter. Next road appears when you get there.
 * Pure projection over ARCore's tracking; accuracy is bounded by GPS and compass.
 */
@Composable
fun ArRoadOverlay(route: CampusRoute?, position: GeoPoint?, basis: CameraBasis?, turnKind: String?, turnLabel: String?, distanceToTurnMeters: Double?, modifier: Modifier = Modifier, arFrame: ArFrameState? = null) {
    val fov = rememberVerticalFov()
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val b = basis ?: return@Canvas
        val here = position ?: return@Canvas
        val geometry = route?.geometry ?: return@Canvas
        if (geometry.size < 2) return@Canvas
        val projection = if (arFrame != null && arFrame.width > 0) {
            ArProjection(size.width, size.height, arFrame.focalY * size.height / arFrame.height, arFrame.focalX * size.width / arFrame.width, arFrame.groundHeightMeters ?: 1.45)
        } else ArProjection(size.width, size.height, fov)
        val start = projectPath(here, geometry) ?: return@Canvas
        if (start.distanceMeters > 80) { drawNotice(measurer, "Walk or drive onto the route to see the line"); return@Canvas }
        val phase = ((arFrame?.reading?.timeMillis ?: System.currentTimeMillis()) % 1600L) / 1600f

        // Metres of this road still ahead (the next turn), measured along the drawn shape.
        val roadEnd = if (distanceToTurnMeters != null) route.routeToShapeMeters(distanceToTurnMeters).coerceIn(4.0, ROAD_MAX_METERS) else ROAD_MAX_METERS
        val stub = 14.0
        val points = ArrayList<Pair<Double, Double>>()   // east, north metres from the camera
        val travelAt = ArrayList<Double>()
        var travelled = 0.0
        var previous = start.point
        points.add(start.point.offsetFrom(here)); travelAt.add(0.0)
        loop@ for (i in start.segment + 1 until geometry.size) {
            val next = geometry[i]
            val length = previous.distanceTo(next)
            var d = STEP_METERS
            while (d < length) {
                if (travelled + d > roadEnd + stub) break@loop
                val t = d / length
                points.add(GeoPoint(previous.latitude + (next.latitude - previous.latitude) * t, previous.longitude + (next.longitude - previous.longitude) * t).offsetFrom(here)); travelAt.add(travelled + d)
                d += STEP_METERS
            }
            travelled += length; points.add(next.offsetFrom(here)); travelAt.add(travelled); previous = next
            if (travelled > roadEnd + stub) break
        }
        fun ground(e: Double, n: Double, lift: Double = 0.0) = projection.ground(b, e, n, projection.eyeHeightMeters, lift)?.let { Offset(it.first, it.second) }
        val left = ArrayList<Offset?>(); val right = ArrayList<Offset?>(); val centre = ArrayList<Offset?>()
        for (i in points.indices) {
            val a0 = points[max(0, i - 1)]; val c0 = points[min(points.lastIndex, i + 1)]
            val dx = c0.first - a0.first; val dy = c0.second - a0.second; val n = hypot(dx, dy).takeIf { it > 1e-6 } ?: 1.0
            val px = -dy / n * RIBBON_HALF_WIDTH; val py = dx / n * RIBBON_HALF_WIDTH
            val p = points[i]
            left.add(ground(p.first + px, p.second + py)); right.add(ground(p.first - px, p.second - py)); centre.add(ground(p.first, p.second))
        }
        // Far to near, so the nearest, largest pieces sit on top. Beyond the turn the road is drawn faded.
        for (i in points.lastIndex - 1 downTo 0) {
            val l0 = left[i]; val r0 = right[i]; val l1 = left[i + 1]; val r1 = right[i + 1]
            if (l0 == null || r0 == null || l1 == null || r1 == null) continue
            val beyond = travelAt[i] > roadEnd
            val fade = (if (beyond) 0.28f else (1f - (travelAt[i] / (roadEnd + 40.0)).toFloat() * 0.7f)).coerceIn(0.18f, 1f)
            val quad = Path().apply { moveTo(l0.x, l0.y); lineTo(l1.x, l1.y); lineTo(r1.x, r1.y); lineTo(r0.x, r0.y); close() }
            drawPath(quad, Brush.horizontalGradient(listOf(Cyan.copy(alpha = 0.55f * fade), Cyan.copy(alpha = 0.9f * fade), Cyan.copy(alpha = 0.55f * fade)), minOf(l0.x, r0.x), maxOf(l0.x, r0.x).coerceAtLeast(minOf(l0.x, r0.x) + 1f)))
            val edge = strokeWidthFor(l0, r0, 0.09f)
            drawLine(Lime.copy(alpha = 0.95f * fade), l0, l1, edge, StrokeCap.Round); drawLine(Lime.copy(alpha = 0.95f * fade), r0, r1, edge, StrokeCap.Round)
        }
        // Chevrons flow toward the turn: phase slides them forward along the road.
        var k = 0
        while (k * CHEVRON_SPACING < roadEnd) {
            val metres = ((k + phase) * CHEVRON_SPACING).coerceAtLeast(0.0)
            if (metres < roadEnd - 1.0) {
                val index = (metres / STEP_METERS).toInt().coerceIn(1, points.lastIndex - 1)
                val l = left[index]; val r = right[index]; val tip = centre[index + 1]
                if (l != null && r != null && tip != null) {
                    val w = hypot(l.x - r.x, l.y - r.y) * 0.34f
                    val chevron = Path().apply { moveTo(tip.x - w, tip.y + w * 0.55f); lineTo(tip.x, tip.y - w * 0.4f); lineTo(tip.x + w, tip.y + w * 0.55f) }
                    val alpha = (1f - (metres / (roadEnd + 20)).toFloat()).coerceIn(0.25f, 1f)
                    drawPath(chevron, Ink.copy(alpha = alpha * 0.85f), style = Stroke(width = w * 0.78f, cap = StrokeCap.Round))
                    drawPath(chevron, Color.White.copy(alpha = alpha), style = Stroke(width = w * 0.42f, cap = StrokeCap.Round))
                }
            }
            k++
        }
        // Turn plate at the end of this road.
        val endIndex = travelAt.indexOfFirst { it >= roadEnd }.let { if (it < 0) points.lastIndex else it }
        if (endIndex >= 1 && endIndex < points.lastIndex && turnKind != null) drawTurn(measurer, projection, b, points, endIndex, turnKind, turnLabel, distanceToTurnMeters, phase)
    }
}

private const val ROAD_MAX_METERS = 220.0
private const val CHEVRON_SPACING = 5.0

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTurn(
    measurer: TextMeasurer, projection: ArProjection, b: CameraBasis, points: List<Pair<Double, Double>>, endIndex: Int, kind: String, label: String?, metres: Double?, phase: Float,
) {
    val turn = points[endIndex]
    val before = points[max(0, endIndex - 3)]; val after = points[min(points.lastIndex, endIndex + 3)]
    fun unit(a: Pair<Double, Double>, c: Pair<Double, Double>): Pair<Double, Double> { val dx = c.first - a.first; val dy = c.second - a.second; val n = hypot(dx, dy).takeIf { it > 1e-6 } ?: 1.0; return dx / n to dy / n }
    val d1 = unit(before, turn); val d2 = unit(turn, after)
    fun g(e: Double, n: Double, lift: Double = 0.0) = projection.ground(b, e, n, projection.eyeHeightMeters, lift)?.let { Offset(it.first, it.second) }
    val arrives = kind == "Arrive"
    val pulse = 1f + 0.08f * kotlin.math.sin(phase * 6.2831855f)
    // A bold bent arrow lying on the road: shaft into the turn, head out along the new road.
    val tail = g(turn.first - d1.first * 7, turn.second - d1.second * 7); val knee = g(turn.first, turn.second)
    val head = g(turn.first + d2.first * 3.5, turn.second + d2.second * 3.5)
    if (!arrives && tail != null && knee != null && head != null) {
        val shaft = Path().apply { moveTo(tail.x, tail.y); lineTo(knee.x, knee.y); lineTo(head.x, head.y) }
        val width = (hypot(tail.x - knee.x, tail.y - knee.y) * 0.12f).coerceIn(10f, 60f)
        drawPath(shaft, Ink, style = Stroke(width = width * 1.7f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawPath(shaft, Lime, style = Stroke(width = width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        val tip = g(turn.first + d2.first * 6, turn.second + d2.second * 6)
        val sideL = g(turn.first + d2.first * 3.2 - d2.second * 1.5, turn.second + d2.second * 3.2 + d2.first * 1.5)
        val sideR = g(turn.first + d2.first * 3.2 + d2.second * 1.5, turn.second + d2.second * 3.2 - d2.first * 1.5)
        if (tip != null && sideL != null && sideR != null) {
            val tri = Path().apply { moveTo(tip.x, tip.y); lineTo(sideL.x, sideL.y); lineTo(sideR.x, sideR.y); close() }
            drawPath(tri, Ink); drawPath(tri, Lime)
        }
    }
    // Floating beacon above the turn, with distance and what to do.
    val base = g(turn.first, turn.second) ?: return
    val top = g(turn.first, turn.second, lift = 3.2) ?: return
    drawLine(Brush.verticalGradient(listOf(Lime.copy(alpha = 0f), Lime), top.y, base.y), top, base, 5f * pulse)
    drawCircle(Lime.copy(alpha = 0.35f), 26f * pulse, base); drawCircle(Lime, 10f, base)
    val head1 = if (arrives) "Arrive" else when (kind) { "Left" -> "Turn left"; "SlightLeft" -> "Bear left"; "Right" -> "Turn right"; "SlightRight" -> "Bear right"; "UTurn" -> "U-turn"; "Roundabout" -> "Roundabout"; else -> "Continue" }
    val sub = metres?.let { "${it.toInt().coerceAtLeast(0)} m" } ?: ""
    drawBeacon(measurer, head1, sub, label, top)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeacon(measurer: TextMeasurer, title: String, distance: String, detail: String?, anchor: Offset) {
    val big = measurer.measure(title, TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold))
    val small = measurer.measure(distance, TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
    val pad = 16f
    val w = big.size.width + small.size.width + 3 * pad + 14f
    val h = max(big.size.height, small.size.height) + 2 * pad
    val x = (anchor.x - w / 2).coerceIn(8f, size.width - w - 8f); val y = (anchor.y - h - 6f).coerceAtLeast(8f)
    drawRoundRect(Ink.copy(alpha = 0.92f), Offset(x - 3, y - 3), androidx.compose.ui.geometry.Size(w + 6, h + 6), androidx.compose.ui.geometry.CornerRadius(h / 2 + 3))
    drawRoundRect(Lime, Offset(x, y), androidx.compose.ui.geometry.Size(big.size.width + 2 * pad, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
    drawText(big, topLeft = Offset(x + pad, y + (h - big.size.height) / 2))
    drawText(small, topLeft = Offset(x + big.size.width + 2 * pad + 14f, y + (h - small.size.height) / 2))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNotice(measurer: TextMeasurer, text: String) {
    val layout = measurer.measure(text, TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
    drawRoundRect(Ink.copy(alpha = 0.85f), Offset((size.width - layout.size.width) / 2 - 20, size.height * 0.55f), androidx.compose.ui.geometry.Size(layout.size.width + 40f, layout.size.height + 40f), androidx.compose.ui.geometry.CornerRadius(26f))
    drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2, size.height * 0.55f + 20))
}

private fun strokeWidthFor(l: Offset, r: Offset, fraction: Float) = (hypot(l.x - r.x, l.y - r.y) * fraction).coerceIn(2f, 14f)


