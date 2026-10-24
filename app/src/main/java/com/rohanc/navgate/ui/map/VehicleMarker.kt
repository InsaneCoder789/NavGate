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
 * Everything the marker and the following camera need, advanced once per display frame. All smoothing is time-based
 * (exponential, so it behaves the same at 60 and 120 Hz) rather than stepped at the 10 Hz sensor rate.
 */
private class MarkerState {
    var ready = false
    var point = GeoPoint(0.0, 0.0); var heading = 0.0; var cameraBearing = 0.0; var onRoute = false; var accuracy = 10.0
    var zoom = Double.NaN; var tilt = Double.NaN
    private var along = Double.NaN
    fun reset() { along = Double.NaN; ready = false }
    private fun ease(dt: Double, tau: Double) = 1.0 - Math.exp(-dt / tau)
    private fun turnToward(from: Double, to: Double, k: Double): Double { val d = ((to - from + 540.0) % 360.0) - 180.0; return (from + d * k + 360.0) % 360.0 }

    fun step(dt: Double, e: MotionEstimate, route: RouteGeometry?, track: TrackInput?, tiltTarget: Double, cameraZoom: Double, cameraTilt: Double) {
        var headingTarget: Double? = e.headingDegrees
        if (route != null && track != null && track.locked) {
            val sinceFix = ((android.os.SystemClock.elapsedRealtime() - track.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
            val target = (track.alongShapeMeters + track.speedMps * sinceFix).coerceIn(0.0, route.length)
            if (along.isNaN() || abs(target - along) > 80.0) along = target
            else { val delta = target - along; along += if (delta >= 0) delta * ease(dt, 0.05) else max(delta * ease(dt, 0.05), -0.3) }
            point = route.pointAt(along); headingTarget = route.bearingAt(along) ?: headingTarget; onRoute = true
        } else {
            along = Double.NaN; onRoute = false
            val k = if (ready) ease(dt, 0.15) else 1.0
            point = GeoPoint(point.latitude + (e.point.latitude - point.latitude) * k, point.longitude + (e.point.longitude - point.longitude) * k)
        }
        accuracy = e.accuracyMeters
        headingTarget?.let { heading = if (ready) turnToward(heading, it, ease(dt, 0.10)) else it }
        cameraBearing = if (ready) turnToward(cameraBearing, heading, ease(dt, 0.12)) else heading
        val zoomTarget = zoomFor(e.speedMps, tiltTarget > 0)
        // The first frame starts from wherever the map is, so beginning guidance sweeps in to the vehicle instead of jumping.
        if (zoom.isNaN()) { zoom = cameraZoom; tilt = cameraTilt }
        zoom += (zoomTarget - zoom) * ease(dt, 0.55); tilt += (tiltTarget - tilt) * ease(dt, 0.6)
        ready = true
    }
}

/**
 * A top-down car (or arrow when walking) drawn over the map. While the vehicle is on the planned route it glides along the route
 * itself; if it leaves the road it follows the sensor-fused free position instead. When following, the camera is moved every
 * display frame, so the map glides at the screen's refresh rate rather than stepping with each sensor update.
 */
@Composable
fun VehicleMarker(camera: CameraState, motion: StateFlow<MotionEstimate?>, route: RouteGeometry?, track: TrackInput?, driving: Boolean, following: Boolean, tilt: Double, bodyColor: Color) {
    val estimate by motion.collectAsStateWithLifecycle()
    val latestEstimate by rememberUpdatedState(estimate)
    val latestRoute by rememberUpdatedState(route); val latestTrack by rememberUpdatedState(track)
    val latestFollowing by rememberUpdatedState(following); val latestTilt by rememberUpdatedState(tilt)
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val state = remember { MarkerState() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(route) { state.reset() }
    LaunchedEffect(following) { if (!following) { state.zoom = Double.NaN; state.tilt = Double.NaN } }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceIn(0.0, 0.1); last = now
                val e = latestEstimate
                if (e != null) {
                    state.step(dt, e, latestRoute, latestTrack, latestTilt, camera.position.zoom, camera.position.tilt)
                    if (latestFollowing) camera.position = camera.position.copy(target = Position(longitude = state.point.longitude, latitude = state.point.latitude),
                        bearing = state.cameraBearing, tilt = state.tilt, zoom = state.zoom, padding = PaddingValues(top = (screenHeightDp * 0.34f).dp))
                }
                frame = now
            }
        }
    }
    if (frame < 0 || !state.ready) return
    val projection = camera.projection ?: return
    val screen = projection.screenLocationFromPosition(Position(longitude = state.point.longitude, latitude = state.point.latitude))
    val mapBearing = camera.position.bearing.toFloat()
    val halo = (state.accuracy / camera.metersPerDpAtTarget).toFloat().coerceIn(10f, 90f)
    val heading = state.heading.toFloat()
    Box(Modifier.offset(screen.x - 26.dp, screen.y - 26.dp).size(52.dp)) {
        Canvas(Modifier.size(52.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(bodyColor.copy(alpha = 0.14f), radius = halo.dp.toPx().coerceAtMost(size.width * 1.6f), center = c)
            rotate(heading - mapBearing, c) { if (driving) drawCar(bodyColor) else drawArrow(bodyColor) }
        }
    }
}

/** Zoom for the speed: faster, further out. The tipped 3D view already shows far ahead, so it can stay a little closer. */
private fun zoomFor(speedMps: Double, tipped: Boolean): Double = (when { speedMps > 22 -> 15.8; speedMps > 13 -> 16.4; speedMps > 6 -> 17.0; else -> 17.6 }) + if (tipped) 0.3 else 0.0

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
