package com.rohanc.navgate.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A route as a measured line: distance along it, point and heading at any distance, and a continuity-aware nearest-point search. */
class RouteGeometry(val points: List<GeoPoint>) {
    val cumulative = DoubleArray(points.size).also { for (i in 1 until points.size) it[i] = it[i - 1] + points[i - 1].distanceTo(points[i]) }
    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    data class Hit(val alongMeters: Double, val lateralMeters: Double, val point: GeoPoint, val segment: Int)

    private fun segmentAt(s: Double): Int {
        var lo = 0; var hi = cumulative.lastIndex
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (cumulative[mid] <= s) lo = mid else hi = mid }
        return lo.coerceIn(0, max(0, points.size - 2))
    }

    fun pointAt(s: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        if (s <= 0 || points.size == 1) return points.first()
        if (s >= length) return points.last()
        val i = segmentAt(s); val span = cumulative[i + 1] - cumulative[i]
        val t = if (span <= 0) 0.0 else (s - cumulative[i]) / span
        return GeoPoint(points[i].latitude + (points[i + 1].latitude - points[i].latitude) * t, points[i].longitude + (points[i + 1].longitude - points[i].longitude) * t)
    }

    /** Road direction at [s], looking a few metres ahead so it does not flip at a vertex. */
    fun bearingAt(s: Double): Double? {
        if (points.size < 2) return null
        val a = pointAt(max(0.0, s - 2.0)); val b = pointAt(min(length, s + 6.0))
        return if (a.distanceTo(b) < 0.5) points.getOrNull(segmentAt(s))?.bearingTo(points[segmentAt(s) + 1]) else a.bearingTo(b)
    }

    /**
     * Closest spot on the route within [fromS]..[toS]. Distance from the point is the main cost; [continuityWeight] adds a small
     * penalty for being far from [predictedS], so a parallel road or a loop that crosses itself cannot steal the match.
     */
    fun nearest(point: GeoPoint, fromS: Double, toS: Double, predictedS: Double?, continuityWeight: Double = 0.25): Hit? {
        if (points.size < 2) return null
        var i = segmentAt(max(0.0, fromS)); var best: Hit? = null; var bestCost = Double.MAX_VALUE
        while (i < points.size - 1 && cumulative[i] <= toS) {
            val p = project(point, points[i], points[i + 1])
            val along = cumulative[i] + (cumulative[i + 1] - cumulative[i]) * p.fraction
            if (along in fromS..toS) {
                val cost = p.distanceMeters + (predictedS?.let { continuityWeight * abs(along - it) } ?: 0.0)
                if (cost < bestCost) { bestCost = cost; best = Hit(along, p.distanceMeters, p.point, i) }
            }
            i++
        }
        return best
    }
}

/** Where the vehicle is on the route right now. [locked] is false when it has left the road. */
data class RoutePosition(val alongMeters: Double, val speedMps: Double, val point: GeoPoint, val bearingDegrees: Double?, val lateralMeters: Double, val locked: Boolean,
    val sigmaMeters: Double, val elapsedMillis: Long, val reversing: Boolean = false)

/**
 * Follows a vehicle along an assigned route. Once a route exists the position is one number, distance along that route, so
 * GPS sideways noise, parallel roads and brief multipath jumps cannot move the vehicle off the road or backwards.
 *
 *  - Kalman filter on (distance, speed). GPS projection is the measurement; GPS speed corrects the speed.
 *  - Noise is inferred online: a fix that disagrees with the prediction by far more than expected is treated as a glitch and
 *    nearly ignored, unless it keeps happening, in which case the vehicle really did move (tunnel exit) and the filter re-locks.
 *  - Forward-only while driving: small backward readings are held; real reversing needs several consistent readings.
 *  - Stationary: speed ~0 freezes the position, so a car waiting at a light does not creep.
 *  - Signal gaps: it keeps moving at the last speed, fading out, with growing uncertainty.
 */
class RouteTracker(private val route: RouteGeometry, private val walking: Boolean = false) {
    private var s = 0.0; private var v = 0.0
    private var p00 = 400.0; private var p01 = 0.0; private var p11 = 25.0
    private var lastT = 0L; private var started = false
    private var outliers = 0; private var backward = 0; private var offStreak = 0; private var stillCount = 0
    private var locked = true; private var lateral = 0.0
    private var reversing = false
    private var stillFar = 0
    private val vMax = if (walking) 4.0 else 70.0
    private val accelNoise = if (walking) 0.8 else 2.5

    val along: Double get() = s

    fun reset(atAlong: Double? = null) { started = false; outliers = 0; backward = 0; offStreak = 0; stillCount = 0; locked = true; reversing = false; v = 0.0; p00 = 400.0; p01 = 0.0; p11 = 25.0; if (atAlong != null) { s = atAlong } }

    private fun predictTo(t: Long) {
        val dt = ((t - lastT) / 1000.0).coerceIn(0.0, 40.0)
        if (dt <= 0) return
        // A car in a tunnel keeps going at its speed; only after a long outage does the remembered speed fade, so it does not run on forever.
        val fade = if (dt > 12.0) exp(-(dt - 12.0) / 15.0) else 1.0
        s = (s + travel(v, dt)).coerceIn(0.0, route.length)
        v *= fade
        val q = accelNoise * accelNoise
        val n00 = p00 + dt * (2 * p01 + dt * p11) + q * dt * dt * dt * dt / 4
        val n01 = p01 + dt * p11 + q * dt * dt * dt / 2
        val n11 = p11 + q * dt * dt
        p00 = n00; p01 = n01; p11 = n11
        lastT = t
    }

    fun update(point: GeoPoint, accuracyMeters: Double, speedMps: Double?, tMillis: Long): RoutePosition {
        if (!started) {
            val hit = route.nearest(point, 0.0, route.length, s.takeIf { it > 0 })
            if (hit != null) { s = hit.alongMeters; lateral = hit.lateralMeters }
            v = speedMps?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            p00 = max(accuracyMeters, 3.0).let { it * it }; p01 = 0.0; p11 = 4.0
            lastT = tMillis; started = true; locked = true
            return position(tMillis)
        }
        predictTo(tMillis)
        val sigma = sqrt(max(p00, 1.0))
        val from = (s - 25 - 3 * sigma).coerceAtLeast(0.0)
        val to = s + max(v, 1.0) * 2.0 + 40 + 3 * sigma
        val hit = route.nearest(point, from, to, s)
        val acc = max(accuracyMeters, 3.0)
        val measured = speedMps?.takeIf { it.isFinite() && it >= 0 }
        if (hit == null) { offStreak++; locked = offStreak < 3; return position(tMillis) }
        lateral = hit.lateralMeters
        // Off the road for several fixes in a row, by clearly more than the GPS error, means the vehicle left the route.
        if (hit.lateralMeters > max(35.0, 2.5 * acc)) offStreak++ else offStreak = 0
        locked = offStreak < 3
        if (!locked) return position(tMillis)

        var r = acc * acc
        val y = hit.alongMeters - s
        val gate = 3.5 * sqrt(p00 + r) + abs(v) * 0.5
        if (abs(y) > gate) { outliers++; if (outliers < 4) r *= 100.0 else outliers = 0 } else outliers = 0
        val stationaryNow = measured != null && measured < 0.5
        stillCount = if (stationaryNow) stillCount + 1 else 0
        if (stationaryNow) { v = 0.0; p11 = 1.0 }          // a measured stop ends the remembered speed at once, so the car cannot coast on
        if (stillCount >= 2) {
            // Standing still: GPS wander must not move the car, but it is not frozen either: it settles slowly onto the average of the fixes.
            // A persistent, large disagreement means the stop position was wrong (a sudden stop overshoot): correct it in two steps.
            if (abs(y) > 2.5 * acc + 5.0) stillFar++ else stillFar = 0
            s = if (stillFar >= 2) (s + 0.6 * y).coerceIn(0.0, route.length) else (s + 0.03 * y).coerceIn(0.0, route.length)
            p00 = max(p00 * 0.9, 1.0)
        } else {
            val sInv = 1.0 / (p00 + r); val k0 = p00 * sInv; val k1 = p01 * sInv
            val newS = s + k0 * y
            if (newS < s - 1.0 && (measured ?: v) > 1.5 && backward < 5) { backward++ }   // hold: a driving car does not roll back
            else { if (newS >= s - 1.0) { backward = 0; reversing = false } else { backward++; if (backward >= 5 && (measured ?: 0.0) > 2.0) reversing = true }; s = newS.coerceIn(0.0, route.length); v += k1 * y }
            val n00 = (1 - k0) * p00; val n01 = (1 - k0) * p01; val n11 = p11 - k1 * p01
            p00 = n00; p01 = n01; p11 = n11
        }
        if (measured != null && !(stillCount >= 2)) {
            val rv = if (walking) 0.5 else 1.0
            val sv = 1.0 / (p11 + rv * rv); val kv0 = p01 * sv; val kv1 = p11 * sv; val yv = measured - v
            s = (s + kv0 * yv).coerceIn(0.0, route.length); v += kv1 * yv
            val n00 = p00 - kv0 * p01; val n01 = (1 - kv1) * p01; val n11 = (1 - kv1) * p11
            p00 = n00; p01 = n01; p11 = n11
        }
        v = v.coerceIn(0.0, vMax)
        p00 = max(p00, 0.25); p11 = max(p11, 0.05)
        return position(tMillis)
    }

    /** Distance covered in [dt] seconds without a fix: full speed for 12 s (a tunnel), then slowly fading so it never runs on for ever. */
    private fun travel(speed: Double, dt: Double) = if (dt <= 12.0) speed * dt else speed * (12.0 + 15.0 * (1.0 - exp(-(dt - 12.0) / 15.0)))

    /** The position at [tMillis] without a new fix: moves on at the current speed. Safe to call at any rate. */
    fun predict(tMillis: Long): RoutePosition {
        if (!started) return position(tMillis)
        val dt = ((tMillis - lastT) / 1000.0).coerceIn(0.0, 40.0)
        val ahead = (s + travel(v, dt)).coerceIn(0.0, route.length)
        return position(tMillis, ahead, sqrt(p00 + (dt * sqrt(p11)).let { it * it }))
    }

    private fun position(t: Long, at: Double = s, sigma: Double = sqrt(max(p00, 0.0))) =
        RoutePosition(at, v, route.pointAt(at), route.bearingAt(at), lateral, locked, sigma, t, reversing)
}
