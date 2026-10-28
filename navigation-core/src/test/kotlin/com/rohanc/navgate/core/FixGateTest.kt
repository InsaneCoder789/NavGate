package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class FixGateTest {
    private val origin = GeoPoint(20.3500, 85.8180)
    private fun at(e: Double, n: Double, t: Long, acc: Double = 5.0) = LocationFix(origin.offset(n, 0.0).offset(e, 90.0), t, acc)

    @Test fun `a vehicle driving along is always accepted`() {
        val gate = FixGate(); var rejected = 0
        for (i in 0..120) if (!gate.accept(at(i * 12.0, 0.0, i * 1000L))) rejected++
        assertEquals(0, rejected)
    }

    @Test fun `a single fix in a random place is dropped even when it claims to be accurate`() {
        val gate = FixGate()
        for (i in 0..20) assertTrue(gate.accept(at(i * 10.0, 0.0, i * 1000L)))
        assertFalse(gate.accept(at(210.0 + 380.0, 290.0, 21_000L, acc = 3.0)))
        assertTrue("the next real fix is still accepted", gate.accept(at(230.0, 0.0, 22_000L)))
    }

    @Test fun `two jumps in a row to different places are both dropped`() {
        val gate = FixGate()
        for (i in 0..20) gate.accept(at(i * 10.0, 0.0, i * 1000L))
        assertFalse(gate.accept(at(900.0, 400.0, 21_000L))); assertFalse(gate.accept(at(-500.0, 700.0, 22_000L)))
        assertTrue(gate.accept(at(230.0, 0.0, 23_000L)))
    }

    @Test fun `a real relocation is accepted once three fixes in a row agree`() {
        val gate = FixGate()
        for (i in 0..10) gate.accept(at(i * 10.0, 0.0, i * 1000L))
        assertFalse("first fix of the new place is held", gate.accept(at(600.0, 0.0, 12_000L)))
        assertFalse("so is the second", gate.accept(at(610.0, 0.0, 13_000L)))
        assertTrue("the third carries on from them", gate.accept(at(620.0, 0.0, 14_000L)))
        assertTrue(gate.accept(at(630.0, 0.0, 15_000L)))
    }

    @Test fun `two matching outliers are not enough to move the vehicle`() {
        val gate = FixGate()
        for (i in 0..20) gate.accept(at(i * 10.0, 0.0, i * 1000L))
        assertFalse(gate.accept(at(900.0, 500.0, 21_000L))); assertFalse(gate.accept(at(905.0, 502.0, 22_000L)))
        assertTrue("the real track continues and is accepted", gate.accept(at(230.0, 0.0, 23_000L)))
    }

    @Test fun `noisy but honest fixes are almost never thrown away`() {
        val rng = Random(7); val gate = FixGate(); var dropped = 0; val n = 600
        for (i in 0 until n) { val noise = 6.0; if (!gate.accept(at(i * 11.0 + rng.nextGaussian() * noise, rng.nextGaussian() * noise, i * 1000L, acc = 6.0))) dropped++ }
        assertTrue("dropped $dropped of $n honest fixes", dropped <= n * 0.02)
    }

    @Test fun `a walker cannot teleport at driving speed`() {
        val gate = FixGate(driving = false)
        for (i in 0..10) gate.accept(at(i * 1.3, 0.0, i * 1000L))
        assertFalse(gate.accept(at(14.0 + 70.0, 0.0, 11_000L)))
    }

    @Test fun `out of order and invalid fixes are ignored`() {
        val gate = FixGate(); gate.accept(at(0.0, 0.0, 5_000L))
        assertFalse(gate.accept(at(5.0, 0.0, 4_000L))); assertFalse(gate.accept(LocationFix(GeoPoint(Double.NaN, 0.0), 6_000L, 5.0)))
    }

    @Test fun `after a long silence the vehicle may be anywhere reachable`() {
        val gate = FixGate()
        for (i in 0..10) gate.accept(at(i * 10.0, 0.0, i * 1000L))
        assertTrue(gate.accept(at(100.0 + 1500.0, 0.0, 70_000L)))
    }
}

class DistanceToManeuverAtTest {
    private fun snap(distance: Double, speed: Double, fixAt: Long, status: SessionStatus = SessionStatus.Navigating) =
        SessionSnapshot(status = status, distanceToManeuver = distance, speedAlongMps = speed, fixElapsedMillis = fixAt, lockedToRoute = true)

    @org.junit.Test fun `the countdown carries on at the speed between fixes and never goes below zero`() {
        org.junit.Assert.assertEquals(91.0, snap(100.0, 9.0, 1_000).distanceToManeuverAt(2_000), 1e-9)
        org.junit.Assert.assertEquals(0.0, snap(5.0, 9.0, 1_000).distanceToManeuverAt(2_500), 1e-9)
    }

    @org.junit.Test fun `a stale fix is not trusted for more than two and a half seconds`() {
        org.junit.Assert.assertEquals(100.0 - 9.0 * 2.5, snap(100.0, 9.0, 1_000).distanceToManeuverAt(20_000), 1e-9)
    }

    @org.junit.Test fun `off the navigating state the figure is left alone`() {
        org.junit.Assert.assertEquals(100.0, snap(100.0, 9.0, 1_000, SessionStatus.Preview).distanceToManeuverAt(2_000), 1e-9)
    }
}
