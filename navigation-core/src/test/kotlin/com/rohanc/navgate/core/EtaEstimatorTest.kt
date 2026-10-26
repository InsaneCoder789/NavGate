package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class EtaEstimatorTest {
    /** Drives [seconds] at [speed] along a route whose own estimate is 10 m/s, returning the last estimate. */
    private fun drive(e: EtaEstimator, startAlong: Double, seconds: Int, speed: Double, totalMeters: Double = 10_000.0): Pair<Double, Double> {
        var along = startAlong; var out = 0.0; var t = 0L
        repeat(seconds * 5) { along += speed * 0.2; t += 200; out = e.update(t, along, totalMeters - along, (totalMeters - along) / 10.0, speed) }
        return along to out
    }

    @Test fun `with no driving seen yet the route's own time is shown`() {
        assertEquals(500.0, EtaEstimator().update(1_000, 0.0, 5_000.0, 500.0, 0.0), 0.001)
    }

    @Test fun `a slower trip than the route expects makes the time longer`() {
        val e = EtaEstimator(); val (_, eta) = drive(e, 0.0, 300, 5.0)
        val routeSays = (10_000.0 - 1_500.0) / 10.0
        assertTrue("estimate $eta vs route $routeSays", eta > routeSays * 1.2)
    }

    @Test fun `a faster trip makes it shorter`() {
        val e = EtaEstimator(); val (_, eta) = drive(e, 0.0, 200, 15.0)
        val routeSays = (10_000.0 - 3_000.0) / 10.0
        assertTrue("estimate $eta vs route $routeSays", eta < routeSays * 0.9)
    }

    @Test fun `halting keeps the time to reach instead of losing it`() {
        val e = EtaEstimator(); val (along, moving) = drive(e, 0.0, 200, 8.0)
        var t = 200_000L; var last = moving
        repeat(300) { t += 200; last = e.update(t, along, 10_000.0 - along, (10_000.0 - along) / 10.0, 0.0) }
        assertTrue("still finite: $last", last.isFinite() && last > 0.0)
        assertEquals(moving, last, moving * 0.06)
    }

    @Test fun `a position jump is not counted as driving`() {
        val e = EtaEstimator(); e.update(0, 0.0, 10_000.0, 1_000.0, 5.0)
        val eta = e.update(1_000, 5_000.0, 5_000.0, 500.0, 5.0)
        assertEquals(500.0, eta, 0.001)
    }

    @Test fun `the answer stays within a sensible range of the route's time`() {
        val e = EtaEstimator(); val (_, eta) = drive(e, 0.0, 600, 1.6)
        assertTrue(eta <= (10_000.0 - 960.0) / 10.0 * 1.8 + 1.0)
    }
}
