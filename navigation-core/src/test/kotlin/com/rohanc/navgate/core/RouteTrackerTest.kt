package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

class RouteTrackerTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    // 1 km north, then 1 km east: an L-shaped road.
    private val corner = RouteGeometry(listOf(at(0.0, 0.0), at(0.0, 1000.0), at(1000.0, 1000.0)))
    private fun truth(s: Double) = corner.pointAt(s)
    private fun offset(p: GeoPoint, east: Double, north: Double) = at(0.0, 0.0).let { GeoPoint(p.latitude + north / 111_320.0, p.longitude + east / (111_320.0 * cos(Math.toRadians(p.latitude)))) }

    private fun drive(tracker: RouteTracker, seconds: Int, speed: Double, noise: Double, rng: Random, startS: Double = 0.0, tStart: Long = 0, glitch: (Int) -> Pair<Double, Double>? = { null }): List<RoutePosition> {
        val out = ArrayList<RoutePosition>()
        for (sec in 0..seconds) {
            val t = tStart + sec * 1000L
            val s = startS + speed * sec
            val g = glitch(sec)
            val dx = g?.first ?: rng.nextDouble(-noise, noise); val dy = g?.second ?: rng.nextDouble(-noise, noise)
            val fix = offset(truth(s), dx, dy)
            out.add(tracker.update(fix, 5.0, speed + rng.nextDouble(-0.4, 0.4), t))
        }
        return out
    }

    @Test fun trackIsMonotonicAndAccurateDespiteSidewaysNoise() {
        val tracker = RouteTracker(corner); val rng = Random(1)
        val track = drive(tracker, 120, 14.0, noise = 6.0, rng = rng)
        track.zipWithNext().forEach { (a, b) -> assertTrue("never moves backward", b.alongMeters >= a.alongMeters - 1e-9) }
        val error = track.drop(10).mapIndexed { i, p -> abs(p.alongMeters - 14.0 * (i + 10)) }.max()
        assertTrue("along error $error", error < 8.0)
        assertTrue(track.all { it.locked })
    }

    @Test fun oneHundredMetreMultipathBurstDoesNotMoveTheCar() {
        val tracker = RouteTracker(corner); val rng = Random(2)
        val track = drive(tracker, 60, 12.0, noise = 3.0, rng = rng) { sec -> if (sec in 30..32) 90.0 to -70.0 else null }
        val before = track[29].alongMeters
        for (i in 30..33) assertTrue("glitch sec $i moved the car ${track[i].alongMeters - before}", abs(track[i].alongMeters - (before + 12.0 * (i - 29))) < 25.0)
        assertTrue("no jump bigger than a plausible step", track.zipWithNext().all { (a, b) -> b.alongMeters - a.alongMeters < 30.0 })
    }

    @Test fun tunnelGapKeepsMovingThenRelocksOnExit() {
        val tracker = RouteTracker(corner)
        for (sec in 0..20) tracker.update(offset(truth(10.0 * sec), 0.0, 0.0), 4.0, 10.0, sec * 1000L)
        val during = tracker.predict(20_000L + 6_000L)           // 6 s with no GPS
        assertTrue("dead reckoned ${during.alongMeters}", during.alongMeters in 230.0..275.0)
        assertTrue("uncertainty grows", during.sigmaMeters > 3.0)
        // Exit 15 s later, ~150 m on from the entry point: a large but real jump forward.
        val exit = tracker.update(truth(200.0 + 150.0 + 40.0), 6.0, 10.0, 35_000L)
        assertTrue("re-locked ahead: ${exit.alongMeters}", exit.alongMeters > 300.0)
    }

    @Test fun standingStillDoesNotCreep() {
        val tracker = RouteTracker(corner); val rng = Random(3)
        for (sec in 0..10) tracker.update(truth(100.0), 4.0, 8.0, sec * 1000L)
        val stopped = (11..70).map { sec -> tracker.update(offset(truth(100.0), rng.nextDouble(-5.0, 5.0), rng.nextDouble(-5.0, 5.0)), 6.0, 0.1, sec * 1000L) }
        val spread = stopped.takeLast(30).map { it.alongMeters }
        assertTrue("waiting at a light moved ${spread.max() - spread.min()} m", spread.max() - spread.min() < 1.0)
    }

    @Test fun leavingTheRoadUnlocksAndPositionIsNotDragged() {
        val tracker = RouteTracker(corner)
        for (sec in 0..20) tracker.update(truth(10.0 * sec), 4.0, 10.0, sec * 1000L)
        var last = tracker.update(offset(truth(210.0), 80.0, 0.0), 4.0, 10.0, 21_000L)
        for (sec in 22..25) last = tracker.update(offset(truth(210.0 + 10.0 * (sec - 21)), 80.0 + 12.0 * (sec - 21), 0.0), 4.0, 10.0, sec * 1000L)
        assertFalse("clearly off the route after several fixes", last.locked)
    }

    @Test fun parallelRoadCannotStealTheMatch() {
        // Two roads 25 m apart; the tracker follows the first even if fixes drift toward the second.
        val road = RouteGeometry(listOf(at(0.0, 0.0), at(0.0, 600.0)))
        val tracker = RouteTracker(road)
        for (sec in 0..30) tracker.update(at(if (sec > 15) 11.0 else 0.0, 10.0 * sec), 6.0, 10.0, sec * 1000L)
        val p = tracker.predict(30_000L)
        assertTrue(p.locked && hypot(p.point.distanceTo(road.pointAt(p.alongMeters)), 0.0) < 0.01)
        assertEquals(300.0, p.alongMeters, 15.0)
    }

    @Test fun reverseNeedsRepeatedEvidence() {
        val tracker = RouteTracker(corner)
        for (sec in 0..20) tracker.update(truth(10.0 * sec), 4.0, 10.0, sec * 1000L)
        val one = tracker.update(truth(190.0), 4.0, 10.0, 21_000L)   // a single reading behind is ignored
        assertTrue("held at ${one.alongMeters}", one.alongMeters >= 195.0)
    }
}
