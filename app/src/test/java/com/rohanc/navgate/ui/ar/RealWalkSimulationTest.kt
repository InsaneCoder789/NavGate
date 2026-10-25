package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.RouteTracker
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The AR engine run on a real person's walk. `walk_sim.csv` is the path and camera direction recovered (by optical flow) from a 4K
 * recording of someone walking down a busy Kandivali street with the phone held up: it weaves, swings the phone and changes direction.
 * The road is the smoothed version of that walk, as a router's centre line would be; the compass is biased and noisy, GPS is noisy and
 * ARCore drifts. Each frame we ask how far the engine's idea of the road direction is from the truth, and where the line lands.
 */
class RealWalkSimulationTest {
    private class Row(val t: Double, val east: Double, val north: Double, val walk: Double, val cam: Double, val pitch: Double)
    private val rows: List<Row> = javaClass.getResourceAsStream("/ar/walk_sim.csv")!!.bufferedReader().readLines().drop(1).map { l ->
        val c = l.split(','); Row(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
    }
    private val origin = GeoPoint(19.2, 72.87)
    private fun geo(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    /** A road that follows the walk loosely: the path averaged over about 8 m, so the weaving and the phone-swing are not in it. */
    private fun road(): RouteGeometry {
        val n = rows.size; val w = 100
        val pts = (0 until n step 10).map { i ->
            val a = maxOf(0, i - w / 2); val b = minOf(n - 1, i + w / 2)
            geo(rows.subList(a, b + 1).map { it.east }.average(), rows.subList(a, b + 1).map { it.north }.average())
        }
        return RouteGeometry(pts)
    }

    class Result(val yawErrorDeg: List<Double>, val lineErrorDeg: List<Double>, val lateral10m: List<Double>, val startup: List<Pair<Double, Double>> = emptyList()) {
        fun pct(l: List<Double>, p: Double) = l.sorted()[((l.size - 1) * p).toInt()]
        fun summary(name: String) = "%s: yaw error median %.1f p90 %.1f max %.1f deg | line misses their feet by median %.2f p90 %.2f m | line misses where they walk to 10 m on by median %.2f p90 %.2f m".format(
            name, pct(yawErrorDeg, .5), pct(yawErrorDeg, .9), yawErrorDeg.max(), pct(lateral10m, .5), pct(lateral10m, .9), pct(lineErrorDeg, .5), pct(lineErrorDeg, .9))
    }

    private fun simulate(compassBiasDeg: Double, seed: Long, gpsSigma: Double = 3.5, aligner: ArGeoAligner = ArGeoAligner(), dump: java.io.PrintWriter? = null,
        compassNoiseDeg: Double = 5.0, relocalizeAtSeconds: Double? = null, settleSeconds: Double = 8.0): Result {
        val rng = Random(seed); val route = road(); val tracker = RouteTracker(route, walking = true); val world = ArWorld(aligner)
        var theta0 = Math.toRadians(137.0); var relocalized = false; var lastReset = 0.0
        fun ar(e: Double, n: Double) = doubleArrayOf(e * cos(theta0) - n * sin(theta0), n * cos(theta0) + e * sin(theta0))   // true (east, north) -> ARCore (east, north)
        var driftX = 0.0; var driftZ = 0.0
        val startup = ArrayList<Pair<Double, Double>>()
        val yaw = ArrayList<Double>(); val line = ArrayList<Double>(); val lat = ArrayList<Double>()   // line = misses future position, lat = misses feet
        var gpsE = 0.0; var gpsN = 0.0
        for ((i, r) in rows.withIndex()) {
            val ms = (r.t * 1000).toLong()
            if (relocalizeAtSeconds != null && !relocalized && r.t >= relocalizeAtSeconds) {
                // ARCore loses tracking and comes back with its world turned and shifted: the engine starts the alignment again.
                relocalized = true; theta0 += Math.toRadians(73.0); driftX += 14.0; driftZ -= 9.0; world.relocalized(); lastReset = r.t
            }
            val az = r.cam; val p = r.pitch
            fun dirAr(e: Double, n: Double, u: Double): DoubleArray { val a = ar(e, n); return doubleArrayOf(a[0], u, -a[1]) }
            val fwd = dirAr(sin(az) * cos(p), cos(az) * cos(p), -sin(p))
            val right = dirAr(cos(az), -sin(az), 0.0)
            val up = dirAr(sin(az) * sin(p), cos(az) * sin(p), cos(p))
            val pos = ar(r.east, r.north)
            driftX += rng.nextGaussian() * 0.0007 * 1.3; driftZ += rng.nextGaussian() * 0.0007 * 1.3
            if (i % 20 == 0) {
                // Urban GPS: errors that drift over several seconds rather than being independent each second.
                gpsE = gpsE * 0.85 + rng.nextGaussian() * gpsSigma * 0.5; gpsN = gpsN * 0.85 + rng.nextGaussian() * gpsSigma * 0.5
                tracker.update(geo(r.east + gpsE, r.north + gpsN), 5.0 + gpsSigma, 1.3 + rng.nextGaussian() * 0.25, ms)
            }
            val estimate = tracker.predict(ms)
            val azDeg = Math.toDegrees(az); val compass = (azDeg + compassBiasDeg + rng.nextGaussian() * compassNoiseDeg + 720.0) % 360.0
            val upAz = Math.toDegrees(atan2(sin(az) * sin(p), cos(az) * sin(p)))
            world.align(ArCameraReading(doubleArrayOf(pos[0] + driftX, 1.4, -pos[1] + driftZ), fwd, right, up, ms), compass, (upAz + compassBiasDeg + 720.0) % 360.0, route, estimate.alongMeters, estimate.speedMps)
            val est = world.aligner.yawDegrees
            if (est != null && r.t < 16.0) startup += r.t to abs(((est - Math.toDegrees(theta0) + 540.0) % 360.0) - 180.0)
            if (est != null && r.t > 8.0 && r.t - lastReset > settleSeconds) {
                yaw += abs(((est - Math.toDegrees(theta0) + 540.0) % 360.0) - 180.0)
                // What the user sees: the road drawn 30 m back to 40 m ahead of where the engine thinks they are.
                val placed = (-30..40 step 2).mapNotNull { world.aligner.toGround(route.pointAt((estimate.alongMeters + it).coerceIn(0.0, route.length))) }
                fun distToLine(x: Double, z: Double): Double = placed.zipWithNext().minOfOrNull { (a, b) ->
                    val dx = b.x - a.x; val dz = b.z - a.z; val l2 = dx * dx + dz * dz
                    val t = if (l2 < 1e-9) 0.0 else (((x - a.x) * dx + (z - a.z) * dz) / l2).coerceIn(0.0, 1.0)
                    hypot(x - (a.x + dx * t), z - (a.z + dz * t)) } ?: Double.MAX_VALUE
                val camX = pos[0] + driftX; val camZ = -pos[1] + driftZ
                if (dump != null && i % 50 == 0) {
                    // What the app would draw this frame: the road on the user's lane side, then started under the phone.
                    val line = smoothGround(anchorToCamera(placed, GroundPoint(camX, camZ)))
                    fun bearingFromCam(x: Double, z: Double) = Math.toDegrees(atan2(x - camX, -(z - camZ)))        // ARCore azimuth, degrees clockwise from its north
                    val camAz = Math.toDegrees(atan2(fwd[0], -fwd[2]))
                    val lineAt10 = line.firstOrNull { hypot(it.x - camX, it.z - camZ) >= 10.0 }
                    val q10 = rows.getOrNull(i + 154)?.let { ar(it.east, it.north) }?.let { doubleArrayOf(it[0] + driftX, -it[1] + driftZ) }
                    fun rel(a: Double) = ((a - camAz + 540.0) % 360.0) - 180.0
                    if (System.getProperty("navgate.probe") != null) System.err.println("PROBE t=%.0f cameraAz=%.0f line@10m %s truth@10m %s yawErr=%.0f".format(r.t, camAz, lineAt10?.let { "%.0f".format(rel(bearingFromCam(it.x, it.z))) } ?: "none", q10?.let { "%.0f".format(rel(bearingFromCam(it[0], it[1]))) } ?: "none", abs(((est - Math.toDegrees(theta0) + 540.0) % 360.0) - 180.0)))
                    dump.println((listOf(r.t, camX, camZ) + fwd.toList() + right.toList() + up.toList() + line.flatMap { listOf(it.x, it.z) }).joinToString(","))
                }
                lat += distToLine(camX, camZ)                                                         // the line passes this far from their feet
                val ahead = rows.getOrNull(i + 154)                                                  // where they actually are 10 m (about 8 s) later
                if (ahead != null) { val q = ar(ahead.east, ahead.north); line += distToLine(q[0] + driftX, -q[1] + driftZ) }   // the line misses their future position by this much
            }
        }
        return Result(yaw, line, lat, startup)
    }

    private fun check(name: String, all: Result, yaw: Double, feet: Double, ahead: Double): String {
        assertTrue("$name: yaw error p90 ${all.pct(all.yawErrorDeg, .9)} deg (limit $yaw)", all.pct(all.yawErrorDeg, .9) < yaw)
        assertTrue("$name: line misses their feet by p90 ${all.pct(all.lateral10m, .9)} m (limit $feet)", all.pct(all.lateral10m, .9) < feet)
        assertTrue("$name: line misses where they walk by p90 ${all.pct(all.lineErrorDeg, .9)} m (limit $ahead)", all.pct(all.lineErrorDeg, .9) < ahead)
        return all.summary(name)
    }
    private fun many(seeds: Int, f: (Long) -> Result): Result { val rs = (1L..seeds).map(f); return Result(rs.flatMap { it.yawErrorDeg }, rs.flatMap { it.lineErrorDeg }, rs.flatMap { it.lateral10m }, rs.flatMap { it.startup }) }

    @Test fun `on a real weaving walk the line stays on the walker and the road direction is within a few degrees`() {
        val lines = ArrayList<String>()
        for ((name, bias) in listOf("compass 25 deg off" to 25.0, "compass -40 deg off" to -40.0, "compass right" to 0.0))
            lines += check(name, many(8) { simulate(bias, it) }, 9.0, 1.0, 3.0)
        File("build").mkdirs(); File("build/real-walk-report.txt").writeText(lines.joinToString("\n"))
    }

    @Test fun `poor urban GPS and a magnetically disturbed compass do not move the line off the walker`() {
        val lines = ArrayList<String>()
        // Poor fixes make the road direction harder to read; the line should degrade gracefully, not fall apart.
        // (GPS error in metres, limits for yaw error deg / miss at the feet m / miss ahead m)
        for ((gps, limits) in listOf(6.0 to doubleArrayOf(12.0, 1.2, 3.5), 9.0 to doubleArrayOf(14.0, 1.6, 4.2), 14.0 to doubleArrayOf(17.0, 2.2, 5.5)))
            lines += check("GPS ${gps.toInt()} m, compass 15 deg noise", many(8) { simulate(25.0, it, gpsSigma = gps, compassNoiseDeg = 15.0) }, limits[0], limits[1], limits[2])
        File("build/real-walk-report-hard.txt").writeText(lines.joinToString("\n"))
    }

    @Test fun `after ARCore loses tracking the line is back on the road within ten seconds`() {
        val all = many(8) { simulate(25.0, it, relocalizeAtSeconds = 25.0, settleSeconds = 10.0) }
        File("build/real-walk-report-reloc.txt").writeText(check("relocalised at 25 s", all, 11.5, 1.2, 3.5))
    }

    @Test fun `dump - the line the app would draw on each sampled frame`() {
        File("build").mkdirs()
        java.io.PrintWriter(File("build/real-walk-overlay.csv")).use { w -> simulate(25.0, 1L, dump = w) }
        assertTrue(File("build/real-walk-overlay.csv").length() > 0)
    }

    @Test fun `from the first second the line is usable and it settles within ten seconds of walking`() {
        val lines = ArrayList<String>()
        for ((name, bias) in listOf("compass 25 deg off" to 25.0, "compass -40 deg off" to -40.0, "compass 0 off" to 0.0)) {
            val r = many(8) { simulate(bias, it) }
            fun at(from: Double, to: Double) = r.startup.filter { it.first in from..to }.map { it.second }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
            val bins = listOf(0.0 to 1.0, 2.0 to 3.0, 4.0 to 5.0, 6.0 to 7.0, 8.0 to 9.0, 10.0 to 11.0, 14.0 to 15.0).map { (a, b) -> "%.0fs: %.0f deg".format(a, at(a, b)) }
            lines += "$name  yaw error (median) at " + bins.joinToString("  ")
            assertTrue("$name: still ${at(10.0, 11.0)} deg off at 10 s", at(10.0, 11.0) < 12.0)
        }
        File("build").mkdirs(); File("build/real-walk-startup.txt").writeText(lines.joinToString("\n"))
    }
}
