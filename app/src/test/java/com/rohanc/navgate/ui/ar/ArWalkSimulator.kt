package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.RouteTracker
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The walk simulation of the AR line, on any route: the walker, the swinging phone, the wrong compass, the wandering GPS and the drifting ARCore world. */
internal class ArWalkSimulator(private val route: RouteGeometry) {
    private fun enu(p: GeoPoint, o: GeoPoint) = ((p.longitude - o.longitude) * 111_320.0 * cos(Math.toRadians(o.latitude))) to ((p.latitude - o.latitude) * 111_320.0)
    private val origin = route.points.first()
    private fun geo(e: Double, n: Double) = GeoPoint(origin.latitude + n / 111_320.0, origin.longitude + e / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    class Result(val yawError: List<Double>, val lineAngle: List<Double>, val lateral: List<Double>, val settleSeconds: Double, val early: List<Double> = emptyList()) {
        fun pct(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]
        fun summary(name: String) = "%s: settles in %.1f s | yaw error p50 %.1f p90 %.1f deg | line direction off the road by p50 %.1f p90 %.1f deg | line off the road at 10 m by p50 %.2f p90 %.2f m".format(
            name, settleSeconds, pct(yawError, .5), pct(yawError, .9), pct(lineAngle, .5), pct(lineAngle, .9), pct(lateral, .5), pct(lateral, .9))
    }

    /** One rendered frame of the walk, for tests that want to look at what the AR layer draws. [pos]/[fwd]/[right]/[up] are the camera in ARCore's world; [azimuth] is where it truly faces (degrees from north). */
    class Frame(val t: Double, val world: ArWorld, val pos: DoubleArray, val fwd: DoubleArray, val right: DoubleArray, val up: DoubleArray, val alongEstimate: Double, val trueAlong: Double, val azimuth: Double, val compass: Double?, val roadBearing: Double, val fixAge: Double = 0.0)

    fun angle(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    fun simulate(compassBiasDeg: Double, seed: Long, reportedSpeed: (Double, Random) -> Double = { v, r -> v + r.nextGaussian() * 0.25 }, aligner: ArGeoAligner = ArGeoAligner(), compassNoise: Double = 6.0,
        gpsSigma: Double = 4.0, seconds: Double = 200.0, pavementOffset: Double = 3.0, standSeconds: Double = 0.0, gpsBias: Double = 0.0, compassBiasAt: ((Double) -> Double)? = null,
        relocalizeAt: Double? = null, swingDeg: Double = 1.0, onFrame: ((Frame) -> Unit)? = null, gpsOutage: ClosedFloatingPointRange<Double>? = null, speedAt: ((Double) -> Double)? = null, pitchDeg: Double = -14.0): Result {
        val rng = Random(seed); val tracker = RouteTracker(route, walking = true); val world = ArWorld(aligner)
        val theta0Start = Math.toRadians(40.0 + rng.nextDouble() * 280.0)          // how ARCore's world is turned against true north: arbitrary
        var theta0 = theta0Start
        fun ar(e: Double, n: Double) = doubleArrayOf(e * cos(theta0) - n * sin(theta0), n * cos(theta0) + e * sin(theta0))
        var driftX = 0.0; var driftZ = 0.0; var gpsE = 0.0; var gpsN = 0.0; var along = 0.0; var relocalized = false; var lastReset = 0.0
        val early = ArrayList<Double>()
        fun truthXZ(p: GeoPoint): Pair<Double, Double> { val (e, n) = enu(p, origin); val a = ar(e, n); return a[0] to -a[1] }
        val yaw = ArrayList<Double>(); val dir = ArrayList<Double>(); val lat = ArrayList<Double>(); var settled = Double.NaN
        val dt = 0.033; var t = 0.0; var frame = 0; var lastFixMs = 0L
        while (t < seconds && along < route.length - 12.0) {
            val moving = t >= standSeconds
            val speed = if (moving) (speedAt?.invoke(t) ?: (1.35 + 0.15 * sin(t / 7.0))) else 0.0; along += speed * dt
            if (relocalizeAt != null && !relocalized && t >= relocalizeAt) { relocalized = true; theta0 += Math.toRadians(73.0); driftX += 14.0; driftZ -= 9.0; world.relocalized(); lastReset = t }
            val centre = route.pointAt(along); val road = route.bearingAt(along) ?: 0.0
            // The pavement is to the right of the road's centre line, and the walker weaves a little about it.
            val side = pavementOffset + 0.7 * sin(t / 3.1)
            val (ce, cn) = enu(centre, origin); val rr = Math.toRadians(road + 90.0)
            val east = ce + side * sin(rr); val north = cn + side * cos(rr)
            // The phone swings about like a person looking round: mostly ahead, now and then well to one side, and tipped a little down.
            val swing = Math.toRadians(swingDeg * (32.0 * sin(t / 4.3) + 22.0 * sin(t / 1.7 + 1.0) + if ((t.toInt() / 23) % 3 == 1) 38.0 else 0.0))
            val az = Math.toRadians(road) + swing; val p = Math.toRadians(pitchDeg + 7.0 * sin(t / 2.3))
            fun dirAr(e: Double, n: Double, u: Double): DoubleArray { val a = ar(e, n); return doubleArrayOf(a[0], u, -a[1]) }
            val fwd = dirAr(sin(az) * cos(p), cos(az) * cos(p), -sin(p)); val right = dirAr(cos(az), -sin(az), 0.0); val up = dirAr(sin(az) * sin(p), cos(az) * sin(p), cos(p))
            val pos = ar(east, north)
            driftX += rng.nextGaussian() * 0.0009; driftZ += rng.nextGaussian() * 0.0009
            val ms = (t * 1000).toLong()
            if (frame % 30 == 0 && (gpsOutage == null || t !in gpsOutage)) {
                gpsE = gpsE * 0.85 + rng.nextGaussian() * gpsSigma * 0.5; gpsN = gpsN * 0.85 + rng.nextGaussian() * gpsSigma * 0.5
                lastFixMs = ms; tracker.update(geo(east + gpsE + gpsBias, north + gpsN), 5.0 + gpsSigma, if (moving) reportedSpeed(speed, rng) else 0.05, ms)
            }
            val estimate = tracker.predict(ms)
            val azDeg = (Math.toDegrees(az) + 720.0) % 360.0
            val bias = compassBiasAt?.invoke(t) ?: compassBiasDeg
            val compass = (azDeg + bias + rng.nextGaussian() * compassNoise + 720.0) % 360.0
            val upAz = Math.toDegrees(atan2(sin(az) * sin(p), cos(az) * sin(p)))
            world.align(ArCameraReading(doubleArrayOf(pos[0] + driftX, 1.4, -pos[1] + driftZ), fwd, right, up, ms), compass, (upAz + bias + 720.0) % 360.0, route, estimate.alongMeters, estimate.speedMps, geo(east + gpsE + gpsBias, north + gpsN), 5.0 + gpsSigma, (ms - lastFixMs) / 1000.0)
            val est = world.aligner.yawDegrees
            onFrame?.invoke(Frame(t, world, doubleArrayOf(pos[0] + driftX, 1.4, -pos[1] + driftZ), fwd, right, up, estimate.alongMeters, along, azDeg, compass, road, (ms - lastFixMs) / 1000.0))
            if (est != null) {
                val err = angle(est, Math.toDegrees(theta0))
                if (settled.isNaN() && err < 8.0) settled = t
                if (t in (standSeconds + 3.0)..(standSeconds + 12.0)) early += err
                if (t > 12.0 + standSeconds && t - lastReset > 10.0) {
                    yaw += err
                    // The line as drawn: the road from the estimated position, mapped into ARCore's world.
                    val camX = pos[0] + driftX; val camZ = -pos[1] + driftZ
                    val whole = (-40..60 step 2).mapNotNull { world.aligner.toGround(route.pointAt((estimate.alongMeters + it).coerceIn(0.0, route.length))) }
                    // As in the app, only the road from the phone onward is drawn.
                    val nearest = whole.indices.minByOrNull { hypot(whole[it].x - camX, whole[it].z - camZ) } ?: 0
                    val placed = whole.drop(nearest)
                    // The direction the drawn line takes 5 to 15 m ahead, against the true direction of the road there (both in ARCore's frame).
                    val a = placed.firstOrNull { hypot(it.x - camX, it.z - camZ) >= 5.0 }; val b = placed.firstOrNull { hypot(it.x - camX, it.z - camZ) >= 15.0 }
                    if (a != null && b != null) {
                        val drawn = Math.toDegrees(atan2(b.x - a.x, -(b.z - a.z)))
                        val ta = truthXZ(route.pointAt((along + 5).coerceAtMost(route.length))); val tb = truthXZ(route.pointAt((along + 15).coerceAtMost(route.length)))
                        val truth = Math.toDegrees(atan2(tb.first - ta.first, -(tb.second - ta.second)))
                        dir += angle(drawn, truth)
                    }
                    // How far from the true road the drawn line is 10 m ahead of the walker.
                    val line = placed.firstOrNull { hypot(it.x - camX, it.z - camZ) >= 10.0 }
                    val trueRoad = truthXZ(route.pointAt((along + 10).coerceAtMost(route.length)))
                    if (line != null) lat += hypot(line.x - (trueRoad.first + driftX), line.z - (trueRoad.second + driftZ))
                }
            }
            t += dt; frame++
        }
        return Result(yaw, dir, lat, settled, early)
    }
}
