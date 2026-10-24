package com.rohanc.navgate.core

import org.junit.Test
import java.util.Random
import kotlin.math.*

/** Measures along-route error with and without the GPS course on curvy roads with noisy position and heading. */
class CoursePrecisionTest {
    private val o = GeoPoint(19.0, 72.85)
    private fun at(e: Double, n: Double) = GeoPoint(o.latitude + n / 111_320.0, o.longitude + e / (111_320.0 * cos(Math.toRadians(o.latitude))))
    /** A winding city road: straights, a hairpin-ish bend, an S-curve, a roundabout-like arc. */
    private fun road(): List<GeoPoint> {
        val pts = ArrayList<GeoPoint>(); var x = 0.0; var y = 0.0; var h = 0.0
        fun go(len: Double, turnPerStep: Double) { repeat((len / 5).toInt()) { h += turnPerStep; x += 5 * sin(Math.toRadians(h)); y += 5 * cos(Math.toRadians(h)); pts += at(x, y) } }
        pts += at(0.0, 0.0); go(300.0, 0.0); go(60.0, 1.5); go(250.0, 0.0); go(120.0, -1.2); go(120.0, 1.2); go(200.0, 0.0); go(90.0, 2.0); go(400.0, 0.0)
        return pts
    }

    private fun run(useCourse: Boolean, seed: Long): Double {
        val line = RouteGeometry(road()); val rng = Random(seed); val tracker = RouteTracker(line)
        var truth = 0.0; var sq = 0.0; var n = 0; val speed = 12.0
        var t = 0L
        tracker.update(line.pointAt(0.0), 5.0, speed, 0, null)
        while (truth < line.length - 20) {
            truth += speed; t += 1000
            val p = line.pointAt(truth); val bearing = line.bearingAt(truth) ?: 0.0
            val noisy = GeoPoint(p.latitude + rng.nextGaussian() * 6.0 / 111_320.0, p.longitude + rng.nextGaussian() * 6.0 / (111_320.0 * cos(Math.toRadians(p.latitude))))
            val course = if (useCourse) (bearing + rng.nextGaussian() * 12.0 + 360) % 360 else null
            val r = tracker.update(noisy, 6.0, speed + rng.nextGaussian() * 0.4, t, course)
            if (t > 8000) { sq += (r.alongMeters - truth).pow(2); n++ }
        }
        return sqrt(sq / n)
    }

    @Test fun compare() {
        val seeds = 1L..20L
        val without = seeds.map { run(false, it) }.average(); val with = seeds.map { run(true, it) }.average()
        println("PRECISION rms along-route error: without course %.2f m, with course %.2f m".format(without, with))
    }
}
