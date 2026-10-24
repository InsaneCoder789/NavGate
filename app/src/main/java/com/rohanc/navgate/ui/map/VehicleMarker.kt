package com.rohanc.navgate.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
fun VehicleMarker(camera: CameraState, motion: StateFlow<MotionEstimate?>, route: RouteGeometry?, track: TrackInput?, driving: Boolean, following: Boolean, tilt: Double, bodyColor: Color,
    vehicle: VehicleModel = VehicleModel.Sedan, vehiclePaint: Color = VehicleColor.White.paint, paddingFraction: Float = 0.34f) {
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
                        bearing = state.cameraBearing, tilt = state.tilt, zoom = state.zoom, padding = PaddingValues(top = (screenHeightDp * paddingFraction).dp))
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
    val canvasDp = 140.dp
    val shownModel = if (driving) vehicle else VehicleModel.Arrow
    val metresPerDp = camera.metersPerDpAtTarget.toFloat().takeIf { it.isFinite() && it > 0f } ?: 0.1f
    val lengthDp = (shownModel.length / metresPerDp).coerceIn(if (driving) 44f else 48f, if (driving) 100f else 76f).dp
    Box(Modifier.offset(screen.x - canvasDp / 2, screen.y - canvasDp / 2).size(canvasDp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(bodyColor.copy(alpha = 0.12f), radius = halo.dp.toPx().coerceAtMost(size.width * 0.5f), center = c)
        }
        VehicleModelView(
            model = shownModel,
            paint = if (driving) vehiclePaint else bodyColor,
            yawDegrees = heading - mapBearing,
            pitchDegrees = camera.position.tilt.toFloat(),
            length = lengthDp,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Zoom for the speed: faster, further out. The tipped 3D view already shows far ahead, so it can stay a little closer. */
private fun zoomFor(speedMps: Double, tipped: Boolean): Double = (when { speedMps > 22 -> 15.8; speedMps > 13 -> 16.4; speedMps > 6 -> 17.0; else -> 17.6 }) + 0.8 + if (tipped) 0.3 else 0.0
