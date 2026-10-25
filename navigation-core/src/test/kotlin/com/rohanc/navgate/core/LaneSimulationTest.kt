package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs

/**
 * A walker or driver on a straight road who keeps to the left lane, crosses to the right lane and goes back, with GPS that wanders by metres.
 * The question: does the app put them in the right lane, and does it stay there without flickering?
 */
class LaneSimulationTest {
    private val origin = GeoPoint(19.2, 72.87)
    private val road = RouteGeometry((0..80).map { origin.offset(it * 10.0, 0.0) })
    private fun trueLateral(t: Double): Double = when {          // right of travel is positive
        t < 40 -> -1.6
        t < 43 -> -1.6 + (t - 40) / 3.0 * 3.2
        t < 75 -> 1.6
        t < 78 -> 1.6 - (t - 75) / 3.0 * 3.2
        else -> -1.6
    }
    private fun trueLane(t: Double) = if (trueLateral(t) > 0.0) LaneTracker.Lane.Right else LaneTracker.Lane.Left

    private class Outcome(val accuracy: Double, val flips: Int, val rmsMeters: Double)

    private fun run(driving: Boolean, gpsSigma: Double, seed: Long, make: (Boolean) -> LaneTracker = { LaneTracker(it) }): Outcome {
        val rng = Random(seed); val tracker = make(driving)
        val speed = if (driving) 8.0 else 1.3
        var errE = 0.0; var errN = 0.0; var t = 0.0; var right = 0; var total = 0; var flips = 0; var last = tracker.lane; var sq = 0.0; var n = 0
        while (t < 100.0) {
            val along = 20.0 + speed * t
            if (along > road.length - 20) break
            // GPS error that wanders over several seconds rather than being new every time.
            errE = errE * 0.9 + rng.nextGaussian() * gpsSigma * 0.45; errN = errN * 0.9 + rng.nextGaussian() * gpsSigma * 0.45
            val truth = road.pointAt(along).offset(trueLateral(t), 90.0)
            val fix = truth.offset(errE, 90.0).offset(errN, 0.0)
            tracker.update(fix, road, along, speed, (1_000 + t * 1000).toLong())
            if (tracker.lane != last) { flips++; last = tracker.lane }
            if (t > 14.0 && !(t in 40.0..48.0) && !(t in 75.0..83.0)) {          // let it warm up and give a crossing a few seconds to register
                total++; if (tracker.lane == trueLane(t)) right++
                sq += (tracker.offsetMeters - trueLateral(t)).let { it * it }; n++
            }
            t += 0.25
        }
        return Outcome(right.toDouble() / total, flips, Math.sqrt(sq / n))
    }

    @Test fun `the lane is recognised and held from noisy GPS`() {
        val lines = ArrayList<String>()
        // GPS error figures: ~1.5 m after the app's motion fusion, ~2.5 m for a raw urban fix. Lane level is at the edge of what GPS can do,
        // so the bar is "right most of the time and no flicker", not perfection.
        for (c in listOf(Case("walking, fused GPS 1.5 m", false, 1.5, 0.78, 4), Case("walking, raw GPS 2.5 m", false, 2.5, 0.72, 5),
            Case("driving, fused GPS 1.5 m", true, 1.5, 0.78, 3), Case("driving, raw GPS 2.5 m", true, 2.5, 0.72, 4))) {
            val outcomes = (1L..10L).map { run(c.driving, c.sigma, it) }
            val acc = outcomes.map { it.accuracy }.average(); val flips = outcomes.map { it.flips }.average(); val rms = outcomes.map { it.rmsMeters }.average()
            lines += "%s: right lane %.0f%% of the time, %.1f lane changes (true: 2), line %.2f m from the true lane position (rms)".format(c.name, acc * 100, flips, rms)
            assertTrue("${c.name}: right lane only ${acc * 100}% of the time", acc >= c.minAccuracy)
            assertTrue("${c.name}: flickered, $flips lane changes", flips <= c.maxFlips)
        }
        File("build").mkdirs(); File("build/lane-report.txt").writeText(lines.joinToString("\n"))
    }
    private data class Case(val name: String, val driving: Boolean, val sigma: Double, val minAccuracy: Double, val maxFlips: Int)
}
