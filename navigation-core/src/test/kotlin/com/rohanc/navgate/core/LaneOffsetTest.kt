package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LaneOffsetTest {
    private val origin = GeoPoint(19.0, 72.8)
    private val north = RouteGeometry((0..20).map { origin.offset(it * 10.0, 0.0) })
    private fun side(along: Double, right: Double) = origin.offset(along, 0.0).offset(right, 90.0)

    @Test fun `a walker on the right of the road reads positive and on the left negative`() {
        assertEquals(3.0, LaneTracker.signedLateral(side(80.0, 3.0), north, 80.0)!!, 0.1)
        assertEquals(-2.0, LaneTracker.signedLateral(side(80.0, -2.0), north, 80.0)!!, 0.1)
    }

    @Test fun `a walker in the right-hand lane is put in the middle of it`() {
        val lane = LaneTracker(driving = false)
        var t = 1_000L; var o = 0.0
        repeat(120) { o = lane.update(side(20.0 + it * 1.4, 2.0), north, 20.0 + it * 1.4, 1.4, t); t += 250 }
        assertEquals(1.6, o, 0.1)
        assertEquals(LaneTracker.Lane.Right, lane.lane)
    }

    @Test fun `someone on the pavement beyond the lanes keeps their own distance`() {
        val lane = LaneTracker(driving = false)
        var t = 1_000L; var o = 0.0
        repeat(120) { o = lane.update(side(20.0 + it * 1.4, 4.0), north, 20.0 + it * 1.4, 1.4, t); t += 250 }
        assertEquals(4.0, o, 0.2)
    }

    @Test fun `driving starts in the left lane and moves to the right one only after clearly crossing`() {
        val lane = LaneTracker(driving = true)
        assertEquals(-1.6, lane.offsetMeters, 1e-9); assertEquals(LaneTracker.Lane.Left, lane.lane)
        var t = 1_000L; var o = 0.0
        // A wobble around the left lane's middle never flips it.
        repeat(160) { o = lane.update(side(20.0 + it * 3.0, -1.6 + if (it % 2 == 0) 0.7 else -0.7), north, 20.0 + it * 3.0, 11.0, t); t += 250 }
        assertEquals(LaneTracker.Lane.Left, lane.lane); assertEquals(-1.6, o, 0.15)
        // Then the car really moves over.
        repeat(160) { o = lane.update(side(20.0 + it * 3.0, 1.6), north, 20.0 + it * 3.0, 11.0, t); t += 250 }
        assertEquals(LaneTracker.Lane.Right, lane.lane); assertEquals(1.6, o, 0.15)
    }

    @Test fun `being far from the road does not move the lane and never exceeds five metres`() {
        val lane = LaneTracker(driving = false)
        assertEquals(0.0, lane.update(side(50.0, 40.0), north, 50.0, 1.4, 1_000L), 1e-9)
        var o = 0.0; var t = 2_000L
        repeat(80) { o = lane.update(side(50.0, 11.0), north, 50.0, 1.4, t); t += 250 }
        assertTrue(abs(o) <= LaneTracker.MAX_LANE_METERS + 1e-9)
    }

    @Test fun `the shifted line sits on the users side near them and eases back onto the road later and before a turn`() {
        val path = (0..20).map { origin.offset(it * 5.0, 0.0) }
        val shifted = LaneTracker.shift(path, 3.0, holdMeters = 20.0, fadeMeters = 20.0)
        fun lateral(p: GeoPoint) = LaneTracker.signedLateral(p, north, 0.0)!!
        assertEquals(3.0, lateral(shifted[2]), 0.1)
        assertEquals(0.0, lateral(shifted[15]), 0.1)
        val beforeTurn = LaneTracker.shift(path, 3.0, holdMeters = 100.0, fadeMeters = 100.0, turnAtMeters = 60.0)
        assertEquals(3.0, lateral(beforeTurn[2]), 0.1)
        assertEquals(0.0, lateral(beforeTurn[11]), 0.1)
    }

    @Test fun `the user can say which lane they are in and GPS does not argue`() {
        val lane = LaneTracker(driving = false)
        lane.force(LaneTracker.Lane.Right)
        var t = 1_000L; var o = 0.0
        repeat(80) { o = lane.update(side(20.0 + it * 1.4, -2.0), north, 20.0 + it * 1.4, 1.4, t); t += 250 }   // GPS says left; the user said right
        assertEquals(LaneTracker.Lane.Right, lane.lane); assertEquals(1.6, o, 0.1)
        lane.force(null)
        repeat(160) { o = lane.update(side(20.0 + it * 1.4, -2.0), north, 20.0 + it * 1.4, 1.4, t); t += 250 }
        assertEquals(LaneTracker.Lane.Left, lane.lane)
    }
}
