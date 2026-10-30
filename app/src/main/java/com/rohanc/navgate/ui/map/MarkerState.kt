package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.offset
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Guidance zoom: this much of the old scale. 0.75 is the zoom-out of one quarter (about 0.415 of a zoom level). */
internal const val ZOOM_SCALE = 0.75
internal val ZOOM_OFFSET_LEVELS = 2.6 + ln(ZOOM_SCALE) / ln(2.0)
internal const val MAX_FOLLOW_ZOOM = 20.8
/** Nearer than this to the next turn, the map zooms in a little so the junction can be read. */
internal const val TURN_APPROACH_METERS = 160.0
internal const val TURN_APPROACH_BOOST = 0.30

/** Zoom for the speed (up to 250 km/h and beyond): faster, further out, so the road ahead always fits. */
internal fun zoomFor(speedMps: Double, tipped: Boolean): Double =
    (when { speedMps > 55 -> 14.6; speedMps > 40 -> 15.0; speedMps > 22 -> 15.8; speedMps > 13 -> 16.4; speedMps > 6 -> 17.0; else -> 17.6 }) + ZOOM_OFFSET_LEVELS + if (tipped) 0.3 else 0.0

/** How much closer to look as a turn approaches (0 far from it, [TURN_APPROACH_BOOST] at the turn), and only at town speeds, where the junction can be read. */
internal fun turnApproachBoost(distanceToTurnMeters: Double, speedMps: Double): Double {
    if (!distanceToTurnMeters.isFinite() || speedMps > 22.0 || distanceToTurnMeters >= TURN_APPROACH_METERS) return 0.0
    return TURN_APPROACH_BOOST * (1.0 - distanceToTurnMeters.coerceAtLeast(0.0) / TURN_APPROACH_METERS)
}

/**
 * Everything the marker and the following camera need, advanced once per display frame. All smoothing is time-based
 * (exponential, so it behaves the same at 60 and 120 Hz) rather than stepped at the 10 Hz sensor rate.
 */
internal class MarkerState {
    var ready = false
    var point = GeoPoint(0.0, 0.0); var heading = 0.0; var cameraBearing = 0.0; var onRoute = false; var accuracy = 10.0
    var zoom = Double.NaN; var tilt = Double.NaN; private var lane = 0.0
    private var along = Double.NaN
    /** True once the car's heading has been set from something real. Until then it must never be eased from an invented north. */
    private var headingKnown = false
    private var settling = false
    var userZoom = 0.0; var lastZoomSet = Double.NaN; var lastTargetSet: GeoPoint? = null

    /** A new route (a reroute): the car keeps where it is drawn and glides onto the new line instead of being redrawn on it. */
    fun reset() { along = Double.NaN; settling = ready }
    private fun ease(dt: Double, tau: Double) = 1.0 - Math.exp(-dt / tau)
    private fun turnToward(from: Double, to: Double, k: Double): Double { val d = ((to - from + 540.0) % 360.0) - 180.0; return (from + d * k + 360.0) % 360.0 }

    /**
     * [northUp] keeps north at the top of the screen; otherwise the map turns so the front of the car is up. [turnDistanceMeters] is how far the next turn was at the last fix; it is carried on at the car's speed so it keeps shrinking between fixes.
     */
    fun step(dt: Double, e: MotionEstimate, route: RouteGeometry?, track: TrackInput?, tiltTarget: Double, cameraZoom: Double, cameraTilt: Double, zoomBias: Double = 0.0,
        bearingOverride: Double? = null, laneTarget: Double = 0.0, nowMillis: Long = android.os.SystemClock.elapsedRealtime(), northUp: Boolean = false, turnDistanceMeters: Double = Double.POSITIVE_INFINITY) {
        var headingTarget: Double? = e.headingDegrees?.takeIf { e.speedMps > 1.5 }       // a standing car has no course: GPS then reports an old or invented one
        var lookAhead: Double? = null
        var turnNow = turnDistanceMeters
        if (route != null && track != null && track.locked) {
            val sinceFix = ((nowMillis - track.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
            turnNow = turnDistanceMeters - track.speedMps.coerceAtLeast(0.0) * sinceFix.coerceAtMost(2.5)
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
            // Under the AR camera the arrow follows where the camera faces, so it never points elsewhere than the picture; otherwise it follows the road.
            headingTarget = bearingOverride ?: roadBearing ?: headingTarget; onRoute = true
            // The map turns a little ahead of the car, so a bend is already in view as the car reaches it.
            lookAhead = route.bearingAt(min(route.length, along + 6.0 + 0.45 * e.speedMps.coerceAtMost(25.0))) ?: roadBearing
        } else {
            along = Double.NaN; onRoute = false
            val k = if (ready) ease(dt, 0.15) else 1.0
            point = GeoPoint(point.latitude + (e.point.latitude - point.latitude) * k, point.longitude + (e.point.longitude - point.longitude) * k)
        }
        accuracy = e.accuracyMeters
        // The very first heading is taken as it is (the road's, or the sensor's once the car moves), never eased from north.
        if (bearingOverride != null) headingTarget = bearingOverride
        headingTarget?.let { target -> heading = if (headingKnown) turnToward(heading, target, ease(dt, 0.10)) else target; if (!headingKnown) { headingKnown = true; cameraBearing = target } }
        val bearingTarget = when {
            bearingOverride != null -> bearingOverride
            northUp -> 0.0
            else -> lookAhead ?: heading
        }
        val bearingTau = if (bearingOverride != null) 0.30 else 0.12
        cameraBearing = if (ready && headingKnown) turnToward(cameraBearing, bearingTarget, ease(dt, bearingTau)) else bearingTarget
        val zoomTarget = (zoomFor(e.speedMps, tiltTarget > 0) + zoomBias + turnApproachBoost(turnNow, e.speedMps)).coerceAtMost(MAX_FOLLOW_ZOOM) + userZoom
        // The first frame starts from wherever the map is, so beginning guidance sweeps in to the vehicle instead of jumping.
        if (zoom.isNaN()) { zoom = cameraZoom; tilt = cameraTilt }
        zoom += (zoomTarget - zoom) * ease(dt, 0.45); tilt += (tiltTarget - tilt) * ease(dt, 0.6)
        ready = true
    }
}

/**
 * The camera padding (top, bottom, in dp) that puts the followed marker where it should sit.
 * [centred]: the middle of the map that is actually free, between the header and the footer, so the arrow neither hides under a card nor sinks to the bottom of the screen
 * (the flat 2D map and the floating window). Otherwise the tipped 3D view keeps the marker low by [fraction] of the screen height, to show more road ahead.
 */
fun followPaddingDp(screenHeightDp: Float, fraction: Float, headerDp: Float, footerDp: Float, centred: Boolean): Pair<Float, Float> =
    if (centred) headerDp.coerceAtLeast(0f) to footerDp.coerceAtLeast(0f) else (screenHeightDp * fraction) to 0f

/** Where the marker lands on the screen (dp from the top) for a given padding: the middle of what the padding leaves. */
fun markerScreenY(screenHeightDp: Float, topPaddingDp: Float, bottomPaddingDp: Float): Float = topPaddingDp + (screenHeightDp - topPaddingDp - bottomPaddingDp) / 2f
