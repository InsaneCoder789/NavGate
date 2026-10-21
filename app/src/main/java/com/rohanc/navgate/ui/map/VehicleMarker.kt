package com.rohanc.navgate.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RouteGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.compose.camera.CameraState
import io.github.dellisd.spatialk.geojson.Position

/** Where the vehicle is drawn and which way it faces. */
data class VehiclePose(val point: GeoPoint, val headingDegrees: Double?, val onRoute: Boolean, val accuracyMeters: Double)

/**
 * What the navigation session knows about progress along the route at its last fix. The marker uses it to glide the car
 * forward at the tracked speed between fixes, so it never jumps sideways or snaps on and off the road.
 */
data class TrackInput(val alongShapeMeters: Double, val speedMps: Double, val fixElapsedMillis: Long, val locked: Boolean)

/**
 * Smooths the displayed distance along the route. It moves toward the extrapolated target a bit each update, never goes backwards
 * by more than a hair, and only teleports for a genuinely large change (a re-lock after a tunnel).
 */
class AlongSmoother {
    private var shown = Double.NaN
    fun next(target: Double): Double {
        if (shown.isNaN() || abs(target - shown) > 80.0) { shown = target; return shown }
        val delta = target - shown
        shown += if (delta >= 0) delta * 0.5 else max(delta * 0.5, -0.3)
        return shown
    }
    fun reset() { shown = Double.NaN }
}

/** Pose for a vehicle locked to the route: a point on the road and the road's own heading. */
fun poseOnRoute(route: RouteGeometry, shownAlong: Double, accuracyMeters: Double) =
    VehiclePose(route.pointAt(shownAlong), route.bearingAt(shownAlong), true, accuracyMeters)

/**
 * A top-down car (or arrow when walking) drawn over the map. While the vehicle is on the planned route it glides along the route
 * itself; if it leaves the road it follows the sensor-fused free position instead. The previous per-frame snapping of the
 * fused position onto the road is gone: that was the source of the jumping.
 */
@Composable
fun VehicleMarker(camera: CameraState, motion: StateFlow<MotionEstimate?>, route: RouteGeometry?, track: TrackInput?, driving: Boolean, following: Boolean, bodyColor: Color) {
    val estimate by motion.collectAsStateWithLifecycle()
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val e = estimate ?: return
    val smoother = remember(route) { AlongSmoother() }
    val pose = if (route != null && track != null && track.locked) {
        val sinceFix = ((android.os.SystemClock.elapsedRealtime() - track.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
        poseOnRoute(route, smoother.next((track.alongShapeMeters + track.speedMps * sinceFix).coerceIn(0.0, route.length)), e.accuracyMeters)
    } else { smoother.reset(); VehiclePose(e.point, e.headingDegrees, false, e.accuracyMeters) }
    val heading = remember { Animatable(0f) }
    LaunchedEffect(pose.headingDegrees) {
        val target = pose.headingDegrees?.toFloat() ?: return@LaunchedEffect
        // Turn the short way round so the car never spins through 360 degrees.
        val diff = ((target - heading.value + 540f) % 360f) - 180f
        heading.animateTo(heading.value + diff, tween(220))
    }
    LaunchedEffect(pose.point, following) {
        // Keep the vehicle in the lower third of the screen, looking ahead, and drop any padding left over from route overview.
        if (following) camera.position = camera.position.copy(target = Position(longitude = pose.point.longitude, latitude = pose.point.latitude),
            bearing = ((heading.value % 360f) + 360f).toDouble() % 360.0, tilt = 45.0, zoom = zoomFor(e.speedMps), padding = PaddingValues(top = (screenHeightDp * 0.34f).dp))
    }
    val projection = camera.projection ?: return
    val screen = projection.screenLocationFromPosition(Position(longitude = pose.point.longitude, latitude = pose.point.latitude))
    val mapBearing = camera.position.bearing.toFloat()
    val halo = (pose.accuracyMeters / camera.metersPerDpAtTarget).toFloat().coerceIn(10f, 90f)
    Box(Modifier.offset(screen.x - 26.dp, screen.y - 26.dp).size(52.dp)) {
        Canvas(Modifier.size(52.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0x221A73E8), radius = halo.dp.toPx().coerceAtMost(size.width * 1.6f), center = c)
            rotate(heading.value - mapBearing, c) { if (driving) drawCar(bodyColor) else drawArrow(bodyColor) }
        }
    }
}

private fun zoomFor(speedMps: Double): Double = when { speedMps > 22 -> 15.8; speedMps > 13 -> 16.4; speedMps > 6 -> 17.0; else -> 17.6 }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCar(body: Color) {
    val w = size.width * 0.46f; val l = size.height * 0.86f
    val left = (size.width - w) / 2; val top = (size.height - l) / 2
    drawRoundRect(Color(0x55000000), Offset(left + 2.5f, top + 4f), Size(w, l), CornerRadius(w * 0.42f))
    drawRoundRect(Color.White, Offset(left - 2.2f, top - 2.2f), Size(w + 4.4f, l + 4.4f), CornerRadius(w * 0.46f))
    drawRoundRect(body, Offset(left, top), Size(w, l), CornerRadius(w * 0.42f))
    val glass = Color(0xCC0D1B2A)
    val windshield = Path().apply {
        moveTo(left + w * 0.16f, top + l * 0.30f); lineTo(left + w * 0.84f, top + l * 0.30f)
        lineTo(left + w * 0.76f, top + l * 0.47f); lineTo(left + w * 0.24f, top + l * 0.47f); close()
    }
    drawPath(windshield, glass)
    drawRoundRect(glass, Offset(left + w * 0.2f, top + l * 0.62f), Size(w * 0.6f, l * 0.13f), CornerRadius(3f))
    drawCircle(Color(0xFFFFF3B0), radius = w * 0.07f, center = Offset(left + w * 0.2f, top + l * 0.1f))
    drawCircle(Color(0xFFFFF3B0), radius = w * 0.07f, center = Offset(left + w * 0.8f, top + l * 0.1f))
    drawRoundRect(Color(0x66FFFFFF), Offset(left + 3f, top + 3f), Size(w - 6f, l - 6f), CornerRadius(w * 0.38f), style = Stroke(1.2f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrow(body: Color) {
    val c = Offset(size.width / 2, size.height / 2); val r = size.width * 0.34f
    val path = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.78f, c.y + r * 0.85f); lineTo(c.x, c.y + r * 0.45f); lineTo(c.x - r * 0.78f, c.y + r * 0.85f); close() }
    drawPath(path, Color.White, style = Stroke(5f)); drawPath(path, body)
}
