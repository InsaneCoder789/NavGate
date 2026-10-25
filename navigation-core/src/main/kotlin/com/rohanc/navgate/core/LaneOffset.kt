package com.rohanc.navgate.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Which side of the route's centre line the user is really on. A road route is drawn down the middle of the road; a person walks on one
 * side of it and a car keeps to one lane. This watches where the fixes fall (positive = to the right of the direction of travel) and
 * smooths them over about ten seconds, so the line shown can sit where the user is. It never moves the route itself, so it can never
 * cause a reroute.
 *
 * The road is treated as lanes [LANE_WIDTH_METERS] wide either side of the centre line. A walker or car whose smoothed position sits in one
 * of them is put in the middle of that lane, which keeps the distance between the two lanes fixed instead of following every GPS wobble;
 * the side only changes when the user has clearly moved across, so it cannot flicker. Someone on the pavement (beyond the lane) keeps the
 * measured offset.
 */
class LaneTracker(private val driving: Boolean, private val leftHandTraffic: Boolean = true, private val timeConstantSeconds: Double = TIME_CONSTANT_SECONDS, private val trustAfterSeconds: Double = TRUST_AFTER_SECONDS) {
    /** Sideways offset of the user from the route in metres, positive = right of the direction of travel. */
    var offsetMeters: Double = defaultOffset(); private set
    /** The lane the user is in, as seen from the direction of travel. */
    var lane: Lane = if (driving) (if (leftHandTraffic) Lane.Left else Lane.Right) else Lane.Centre; private set
    enum class Lane { Left, Centre, Right }
    private var smoothed = 0.0
    private var pRight = if (driving) (if (leftHandTraffic) 0.2 else 0.8) else 0.5
    private var observedSeconds = 0.0
    private var lastMillis = 0L

    private fun defaultOffset() = if (driving) (if (leftHandTraffic) -LANE_WIDTH_METERS / 2 else LANE_WIDTH_METERS / 2) else 0.0

    private var forced: Lane? = null

    /** The user says which lane they are in ("left" or "right"); null goes back to working it out. Their word beats GPS, which cannot always tell lanes apart. */
    fun force(side: Lane?) {
        forced = side?.takeIf { it != Lane.Centre }
        forced?.let { lane = it; pRight = if (it == Lane.Right) 0.95 else 0.05 }
    }

    fun reset() { pRight = if (driving) (if (leftHandTraffic) 0.2 else 0.8) else 0.5; smoothed = 0.0; observedSeconds = 0.0; lastMillis = 0L; offsetMeters = defaultOffset(); lane = if (driving) (if (leftHandTraffic) Lane.Left else Lane.Right) else Lane.Centre }

    /** Feed one fix. [alongMeters] is where the tracker believes the user is along [line] (distance along the drawn line). */
    fun update(user: GeoPoint, line: RouteGeometry, alongMeters: Double, speedMps: Double, nowMillis: Long): Double {
        val dt = if (lastMillis == 0L) 0.0 else ((nowMillis - lastMillis) / 1000.0).coerceIn(0.0, 3.0)
        lastMillis = nowMillis
        forced?.let { side ->
            offsetMeters += ((if (side == Lane.Right) 1 else -1) * LANE_WIDTH_METERS / 2 - offsetMeters) * (1.0 - exp(-dt / SLIDE_SECONDS)); return offsetMeters
        }
        val lateral = signedLateral(user, line, alongMeters) ?: return offsetMeters
        // Standing still or far from the road says nothing about the lane (a stop at the kerb, or a reroute about to happen).
        if (abs(lateral) > MAX_LANE_METERS * 2.4 || (driving && speedMps < 1.0)) return offsetMeters
        val alpha = 1.0 - exp(-dt / timeConstantSeconds)
        smoothed = if (observedSeconds == 0.0) lateral else smoothed + (lateral - smoothed) * alpha
        observedSeconds += dt
        val trust = (observedSeconds / trustAfterSeconds).coerceIn(0.0, 1.0)
        val measured = defaultOffset() * (1 - trust) + smoothed * trust
        // Which lane: GPS can be wrong by more than half a lane, so no single fix decides. Each one is weighed as a little evidence for
        // "right lane" or "left lane" (fixes are not independent, so each counts for a fraction), and lanes rarely change, so old evidence
        // fades slowly. The lane only changes when one side is clearly ahead.
        val evidence = 2.0 * LANE_WIDTH_METERS / 2 * lateral / (NOISE_METERS * NOISE_METERS) * (dt / CORRELATION_SECONDS)
        val leak = SWITCH_PER_SECOND * dt
        val kept = pRight * (1 - leak) + (1 - pRight) * leak
        val odds = kept / (1 - kept) * exp(evidence.coerceIn(-3.0, 3.0))
        pRight = (odds / (1 + odds)).coerceIn(0.001, 0.999)
        lane = when {
            pRight > CONFIDENT -> Lane.Right
            pRight < 1 - CONFIDENT -> Lane.Left
            lane == Lane.Centre -> Lane.Centre
            else -> lane
        }
        val target = when (lane) {
            Lane.Right -> if (measured <= PAVEMENT_METERS) LANE_WIDTH_METERS / 2 else measured
            Lane.Left -> if (measured >= -PAVEMENT_METERS) -LANE_WIDTH_METERS / 2 else measured
            Lane.Centre -> measured
        }.coerceIn(-MAX_LANE_METERS, MAX_LANE_METERS)
        // Crossing from one lane to the other is a smooth slide, not a jump.
        offsetMeters += (target - offsetMeters) * (1.0 - exp(-dt / SLIDE_SECONDS))
        return offsetMeters
    }

    companion object {
        /** A lane is about this wide on an urban road; the centre of a lane is half of it from the centre line. */
        const val LANE_WIDTH_METERS = 3.2
        const val NOISE_METERS = 2.2
        const val CORRELATION_SECONDS = 2.5
        const val SWITCH_PER_SECOND = 0.02
        const val CONFIDENT = 0.8
        const val PAVEMENT_METERS = 2.7
        const val SLIDE_SECONDS = 1.5
        const val MAX_LANE_METERS = 5.0
        const val TIME_CONSTANT_SECONDS = 10.0
        const val TRUST_AFTER_SECONDS = 8.0

        /** Signed distance of [p] from [line] near [alongMeters], positive on the right of the direction of travel. */
        fun signedLateral(p: GeoPoint, line: RouteGeometry, alongMeters: Double): Double? {
            val pts = line.points
            if (pts.size < 2) return null
            var best = Double.MAX_VALUE; var signed = 0.0
            for (i in 0 until pts.lastIndex) {
                if (line.cumulative[i + 1] < alongMeters - 40.0 || line.cumulative[i] > alongMeters + 40.0) continue
                val a = pts[i]; val b = pts[i + 1]
                val k = 111_320.0 * kotlin.math.cos(Math.toRadians(a.latitude))
                val bx = (b.longitude - a.longitude) * k; val by = (b.latitude - a.latitude) * 111_320.0
                val px = (p.longitude - a.longitude) * k; val py = (p.latitude - a.latitude) * 111_320.0
                val len2 = bx * bx + by * by; if (len2 < 1e-6) continue
                val t = ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
                val d = hypot(px - bx * t, py - by * t)
                if (d < best) { best = d; signed = (bx * py - by * px) / hypot(bx, by) * -1.0 }   // right of travel is positive
            }
            return if (best == Double.MAX_VALUE) null else signed.let { if (it.isNaN()) null else it }
        }

        /**
         * [path] moved sideways by [offsetMeters] (positive = right of travel): all the way for the first [holdMeters], then easing back onto
         * the centre line over [fadeMeters], and also easing out ahead of a turn at [turnAtMeters] so the line joins the next road cleanly.
         */
        fun shift(path: List<GeoPoint>, offsetMeters: Double, holdMeters: Double = 30.0, fadeMeters: Double = 30.0, turnAtMeters: Double? = null): List<GeoPoint> {
            if (path.size < 2 || abs(offsetMeters) < 0.05) return path
            var along = 0.0
            return path.mapIndexed { i, p ->
                if (i > 0) along += path[i - 1].distanceTo(p)
                val a = path[max(0, i - 1)]; val b = path[min(path.lastIndex, i + 1)]
                val k = 111_320.0 * kotlin.math.cos(Math.toRadians(p.latitude))
                val dx = (b.longitude - a.longitude) * k; val dy = (b.latitude - a.latitude) * 111_320.0
                val n = hypot(dx, dy); if (n < 1e-6) return@mapIndexed p
                val byDistance = (1.0 - (along - holdMeters) / fadeMeters).coerceIn(0.0, 1.0)
                val byTurn = turnAtMeters?.let { ((it - along - 8.0) / 17.0).coerceIn(0.0, 1.0) } ?: 1.0
                val w = min(byDistance, byTurn) * offsetMeters
                // Right of travel = (dy, -dx) in (east, north) metres.
                GeoPoint(p.latitude + (-dx / n) * w / 111_320.0, p.longitude + (dy / n) * w / k)
            }
        }
    }
}
