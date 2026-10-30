package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.CalibrationState
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.ui.map.MarkerState
import com.rohanc.navgate.ui.map.TrackInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/**
 * The 14:47 walk to KP-5 (docs/FOOTAGE_2026-10-29_KP5_WALK.md) rebuilt in code: the route the app planned, a walker on the pavement at the pace read off the footage
 * (about 1.55 m/s), the phone swinging about, a compass that is wrong, ARCore drifting, and the GPS outage that froze the screens from 160 s to 240 s. Every second the AR scene
 * the app would draw is built and projected through a phone camera, and the numbers are held against what the good stretches of the footage showed
 * (ribbon base 0.41 to 0.82 of the screen width in the good frames, 0.07 where it hugged the left edge).
 * Pass rule: at most 5% of the samples may break a budget.
 */
class FootageReplayTest {
    private val routeFile = listOf("../data/replay/kp5-walk-route.json", "data/replay/kp5-walk-route.json").map(::File).firstOrNull { it.exists() }
    private val route by lazy { RouteGeometry(Json.parseToJsonElement(routeFile!!.readText()).jsonObject["geometry"]!!.jsonArray.map { GeoPoint(it.jsonArray[0].jsonPrimitive.double, it.jsonArray[1].jsonPrimitive.double) }) }
    private fun angle(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    private class Tally(val name: String) { var n = 0; var bad = 0; fun add(v: Boolean) { n++; if (v) bad++ }; val share get() = if (n == 0) 0.0 else bad.toDouble() / n
        override fun toString() = "%-46s %5.2f%% of %d".format(name, share * 100, n) }

    private val bxs = ArrayList<Double>()
    private fun replay(compassBias: Double, seed: Long, pavement: Double = 3.0): List<Tally> {
        val start = Tally("track starts more than 0.8 m from the phone"); val baseRef = Tally("(for reference) ribbon base outside 0.07-0.90"); val sky = Tally("ribbon above the horizon")
        val along = Tally("progress more than 12 m off the walker (incl. the outage)"); val arrow = Tally("map arrow more than 8 deg off the camera"); val compassArrow = Tally("(for reference) compass arrow more than 8 deg off")
        val marker = MarkerState(); var lastMs = 0L
        val tanHalf = tan(Math.toRadians(30.0)); val aspect = 480.0 / 1040.0
        fun screen(f: ArWalkSimulator.Frame, x: Double, y: Double, z: Double): Pair<Double, Double>? {
            val vx = x - f.pos[0]; val vy = y - f.pos[1]; val vz = z - f.pos[2]
            val zc = vx * f.fwd[0] + vy * f.fwd[1] + vz * f.fwd[2]; if (zc < 0.3) return null
            val xc = vx * f.right[0] + vy * f.right[1] + vz * f.right[2]; val yc = vx * f.up[0] + vy * f.up[1] + vz * f.up[2]
            return (0.5 + 0.5 * (xc / zc) / (tanHalf * aspect)) to (0.5 - 0.5 * (yc / zc) / tanHalf)
        }
        var frames = 0
        ArWalkSimulator(route).simulate(compassBias, seed, seconds = 800.0, pavementOffset = pavement, gpsOutage = 160.0..240.0, speedAt = { 1.55 }, pitchDeg = 20.0, onFrame = { f ->
            frames++
            if (frames % 15 != 0 || f.t < 20.0) return@simulate
            val outage = f.t in 160.0..250.0
            val world = f.world; if (!world.aligner.aligned) return@simulate
            val checkpointPath = (0..30).map { route.pointAt((f.alongEstimate + it * 2.0).coerceAtMost(route.length)) }
            val ahead = world.pathAhead(checkpointPath, route, f.fixAge)
            val scene = world.buildScene(ahead, 60.0, null, world.aligner.let { f.pos[1] - 1.4 }, GroundPoint(f.pos[0], f.pos[2]), GroundPoint(f.fwd[0], f.fwd[2])) ?: return@simulate
            along.add(abs(world.alongNowMeters - f.trueAlong) > 12.0)
            val pitch = Math.toDegrees(Math.asin(f.fwd[1].coerceIn(-1.0, 1.0)))
            val facing = angle(f.azimuth, f.roadBearing)
            val horizonY = 0.5 + (tan(Math.toRadians(pitch)) / tanHalf) / 2.0
            // The ribbon: where its nearest edge meets the bottom of what the camera sees, and whether any of it lies in the sky.
            if (pitch in -60.0..5.0 && facing < 30.0) {
                val v = scene.ribbon.vertices; var bx = Double.NaN; var by = -1.0; var above = false
                for (i in 0 until scene.ribbon.vertexCount) {
                    val s = screen(f, v[i * 5].toDouble(), v[i * 5 + 1].toDouble(), v[i * 5 + 2].toDouble()) ?: continue
                    if (s.second > by && s.second <= 1.0 && s.first in 0.0..1.0) { by = s.second; bx = s.first }
                    if (s.second < horizonY - 0.02) above = true
                }
                if (!bx.isNaN()) { bxs += bx; baseRef.add(bx < 0.07 || bx > 0.90) }
                // What the left-edge errors of the footage were: a line that starts metres from the phone. It must start under it.
                scene.track?.let { tr -> start.add(hypot((tr.vertices[0] + tr.vertices[5]) / 2.0 - f.pos[0], (tr.vertices[2] + tr.vertices[7]) / 2.0 - f.pos[2]) > 0.8) }
                sky.add(above)
            }
            // The map: the arrow must face where the camera faces. The aligned world gives that heading; the phone's compass, with its error, does not.
            val heading = world.cameraHeadingDegrees(f.fwd[0], f.fwd[2])
            if (heading != null && pitch > -70.0) {
                val ms = (f.t * 1000).toLong() + 1_000L
                val est = MotionEstimate(route.pointAt(f.trueAlong), 1.5, null, 0.0, 0.0, 4.0, CalibrationState.Good, false, false, ms)
                var m = ms; var step = lastMs.takeIf { it > 0 } ?: (ms - 250)
                while (step < m) { val dt = ((step + 16).coerceAtMost(m) - step) / 1000.0; step += 16
                    marker.step(dt, est, route, TrackInput(route.cumulative.let { f.trueAlong }, 1.5, step, true), 0.0, 17.0, 0.0, bearingOverride = heading, nowMillis = step) }
                lastMs = ms
                if (f.t > 40.0 && !outage) { arrow.add(angle(marker.heading, f.azimuth) > 8.0); f.compass?.let { compassArrow.add(angle(it, f.azimuth) > 8.0) } }
            }
        })
        return listOf(start, sky, along, arrow, compassArrow, baseRef)
    }

    @Test fun `the KP-5 walk replays within the budgets`() {
        assumeTrue("the KP-5 route is not in this checkout (data/replay is kept local)", routeFile != null)
        val runs = listOf(replay(50.0, 1), replay(-60.0, 2), replay(20.0, 3, pavement = 4.5))
        val sb = StringBuilder("KP-5 REPLAY (3 runs: compass off by +50, -60, +20 degrees; walker 3-4.5 m off the road line; GPS gone 160-240 s)\n"); val failed = ArrayList<String>()
        for (i in runs[0].indices) {
            val n = runs.sumOf { it[i].n }; val bad = runs.sumOf { it[i].bad }; val share = if (n == 0) 0.0 else bad.toDouble() / n
            sb.append("  %-52s %5.2f%% of %d\n".format(runs[0][i].name, share * 100, n))
            if (share > 0.05 && !runs[0][i].name.startsWith("(for reference)")) failed += runs[0][i].name
        }
        fun pc(l: List<Double>) = if (l.isEmpty()) "-" else l.sorted().let { "p10 %.2f p50 %.2f p90 %.2f".format(it[it.size / 10], it[it.size / 2], it[it.size * 9 / 10]) }
        sb.append("  ribbon base x: ${pc(bxs)}\n")
        System.err.print(sb)
        assertTrue("over 5%: $failed", failed.isEmpty())
    }
}
