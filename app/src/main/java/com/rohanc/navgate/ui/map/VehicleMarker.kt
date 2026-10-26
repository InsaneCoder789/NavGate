package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.offset
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

/**
 * What the navigation session knows about progress along the route at its last fix. The marker uses it to glide the car
 * forward at the tracked speed between fixes, so it never jumps sideways or snaps on and off the road.
 */
data class TrackInput(val alongShapeMeters: Double, val speedMps: Double, val fixElapsedMillis: Long, val locked: Boolean)

/**
 * Everything the marker and the following camera need, advanced once per display frame. All smoothing is time-based
 * (exponential, so it behaves the same at 60 and 120 Hz) rather than stepped at the 10 Hz sensor rate.
 */
private class MarkerState {
    var ready = false
    var point = GeoPoint(0.0, 0.0); var heading = 0.0; var cameraBearing = 0.0; var onRoute = false; var accuracy = 10.0
    var zoom = Double.NaN; var tilt = Double.NaN; private var lane = 0.0
    private var along = Double.NaN
    /** A new route (a reroute): the car keeps where it is drawn and glides onto the new line instead of being redrawn on it. */
    fun reset() { along = Double.NaN; settling = ready }
    private var settling = false
    var userZoom = 0.0; var lastZoomSet = Double.NaN; var lastTargetSet: GeoPoint? = null
    private fun ease(dt: Double, tau: Double) = 1.0 - Math.exp(-dt / tau)
    private fun turnToward(from: Double, to: Double, k: Double): Double { val d = ((to - from + 540.0) % 360.0) - 180.0; return (from + d * k + 360.0) % 360.0 }

    fun step(dt: Double, e: MotionEstimate, route: RouteGeometry?, track: TrackInput?, tiltTarget: Double, cameraZoom: Double, cameraTilt: Double, zoomBias: Double = 0.0, bearingOverride: Double? = null, laneTarget: Double = 0.0) {
        var headingTarget: Double? = e.headingDegrees
        if (route != null && track != null && track.locked) {
            val sinceFix = ((android.os.SystemClock.elapsedRealtime() - track.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
            val target = (track.alongShapeMeters + track.speedMps * sinceFix).coerceIn(0.0, route.length)
            if (along.isNaN() || abs(target - along) > 80.0) along = target
            else { val delta = target - along; along += if (delta >= 0) delta * ease(dt, 0.05) else max(delta * ease(dt, 0.05), -0.3) }
            // The vehicle drives in its own lane, a lane's width from the centre line the route is drawn on, sliding across when it changes lane.
            lane += (laneTarget - lane) * ease(dt, 0.6)
            val roadBearing = route.bearingAt(along)
            val onLine = route.pointAt(along).let { p -> if (roadBearing != null && abs(lane) > 0.02) p.offset(lane, roadBearing + 90.0) else p }
            if (settling) {
                val k = ease(dt, 0.28)
                point = GeoPoint(point.latitude + (onLine.latitude - point.latitude) * k, point.longitude + (onLine.longitude - point.longitude) * k)
                if (point.distanceTo(onLine) < 0.6) settling = false
            } else point = onLine
            headingTarget = roadBearing ?: headingTarget; onRoute = true
        } else {
            along = Double.NaN; onRoute = false
            val k = if (ready) ease(dt, 0.15) else 1.0
            point = GeoPoint(point.latitude + (e.point.latitude - point.latitude) * k, point.longitude + (e.point.longitude - point.longitude) * k)
        }
        accuracy = e.accuracyMeters
        headingTarget?.let { heading = if (ready) turnToward(heading, it, ease(dt, 0.10)) else it }
        val bearingTarget = bearingOverride ?: heading
        cameraBearing = if (ready) turnToward(cameraBearing, bearingTarget, ease(dt, if (bearingOverride != null) 0.30 else 0.12)) else bearingTarget
        val zoomTarget = (zoomFor(e.speedMps, tiltTarget > 0) + zoomBias).coerceAtMost(MAX_FOLLOW_ZOOM) + userZoom
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
    vehicle: VehicleModel = VehicleModel.Sedan, vehiclePaint: Color = VehicleColor.White.paint, paddingFraction: Float = 0.34f,
    zoomBias: Double = 0.0, bearingOverride: Double? = null, laneOffsetMeters: Double = 0.0, onUserPan: () -> Unit = {}) {
    val estimate by motion.collectAsStateWithLifecycle()
    val latestEstimate by rememberUpdatedState(estimate)
    val latestRoute by rememberUpdatedState(route); val latestTrack by rememberUpdatedState(track)
    val latestLane by rememberUpdatedState(laneOffsetMeters); val latestZoomBias by rememberUpdatedState(zoomBias); val latestBearing by rememberUpdatedState(bearingOverride)
    val latestFollowing by rememberUpdatedState(following); val latestTilt by rememberUpdatedState(tilt)
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val state = remember { MarkerState() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(route) { state.reset() }
    LaunchedEffect(following) { if (!following) { state.zoom = Double.NaN; state.tilt = Double.NaN; state.lastZoomSet = Double.NaN; state.lastTargetSet = null } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = remember(context) { context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
    val latestDriving by rememberUpdatedState(driving)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            // The camera and marker are updated as often as the user can benefit from, not at every display frame (see RenderBudget).
            val e = latestEstimate
            val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else 0
            kotlinx.coroutines.delay(RenderBudget.intervalMillis(power.isPowerSaveMode, thermal, e?.speedMps ?: 0.0, latestDriving, cameraView = latestBearing != null))
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else ((now - last) / 1e9).coerceIn(0.0, 0.35); last = now
                val estimate = latestEstimate
                if (estimate != null) {
                    if (latestFollowing) {
                        val z = camera.position.zoom
                        // Anything the user changed since the last frame we wrote is theirs: a pinch changes the zoom offset, a drag leaves the car.
                        if (!state.lastZoomSet.isNaN() && abs(z - state.lastZoomSet) > 0.01) state.userZoom = (state.userZoom + (z - state.lastZoomSet)).coerceIn(-5.0, 2.0)
                        val t = state.lastTargetSet
                        if (t != null && GeoPoint(camera.position.target.latitude, camera.position.target.longitude).distanceTo(t) > 12.0) onUserPan()
                    } else { state.userZoom = 0.0 }
                    state.step(dt, estimate, latestRoute, latestTrack, latestTilt, camera.position.zoom, camera.position.tilt, latestZoomBias, latestBearing, latestLane)
                    if (latestFollowing) camera.position = camera.position.copy(target = Position(longitude = state.point.longitude, latitude = state.point.latitude),
                        bearing = state.cameraBearing, tilt = state.tilt, zoom = state.zoom, padding = PaddingValues(top = (screenHeightDp * paddingFraction).dp))
                    if (latestFollowing) { state.lastZoomSet = camera.position.zoom; state.lastTargetSet = GeoPoint(state.point.latitude, state.point.longitude) }
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
    val canvasDp = 210.dp
    val shownModel = if (driving) vehicle else VehicleModel.Arrow
    val metresPerDp = camera.metersPerDpAtTarget.toFloat().takeIf { it.isFinite() && it > 0f } ?: 0.1f
    val lengthDp = (shownModel.length / metresPerDp).coerceIn(if (driving) 90f else 78f, if (driving) 168f else 118f).dp
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
private const val MAX_FOLLOW_ZOOM = 21.2

/** Zoom for the speed (up to 250 km/h = 70 m/s and beyond): faster, further out, so the road ahead always fits. */
private fun zoomFor(speedMps: Double, tipped: Boolean): Double = (when { speedMps > 55 -> 14.6; speedMps > 40 -> 15.0; speedMps > 22 -> 15.8; speedMps > 13 -> 16.4; speedMps > 6 -> 17.0; else -> 17.6 }) + 2.6 + if (tipped) 0.3 else 0.0
