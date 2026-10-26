package com.rohanc.navgate.ui.ar

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnchorToCameraTest {
    private val road = (0..20).map { GroundPoint(0.0, -it * 5.0) }   // straight ahead, 100 m

    @Test fun startsExactlyAtThePhoneEvenWhenOffTheRoad() {
        val out = anchorToCamera(road, GroundPoint(3.0, -10.0), hold = 1.0)
        assertEquals(3.0, out.first().x, 1e-9); assertEquals(-10.0, out.first().z, 1e-9)
    }

    @Test fun theLineStaysOnTheRoadRatherThanStartingUnderThePhone() {
        val out = anchorToCamera(road, GroundPoint(3.0, -10.0))
        assertTrue(out.first().x < 1.0, "starts ${out.first().x} m off the road")
    }

    @Test fun dropsRoadBehindThePhoneAndSettlesBackOntoTheRoad() {
        val out = anchorToCamera(road, GroundPoint(3.0, -10.0), blendMeters = 14.0, hold = 1.0)
        assertTrue(out.all { it.z <= -10.0 + 1e-9 })
        assertEquals(0.0, out.last().x, 1e-9)
        assertTrue(out[3].x < 3.0 && out[3].x > 0.0)
    }

    @Test fun capsAnAbsurdOffsetInsteadOfSwingingTheLine() {
        val out = anchorToCamera(road, GroundPoint(40.0, -10.0), hold = 1.0)
        assertTrue(abs(out.first().x) <= 8.0 + 1e-9)
    }
}
