package com.rohanc.navgate.ui.ar

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArProjectionTest {
    // Phone upright, camera looking north: forward=N, right=E, up=Up.
    private val north = CameraBasis(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
    private val projection = ArProjection(1080f, 2400f, 65.0)

    @Test fun roadAheadIsBelowCentreAndNarrowsWithDistance() {
        val near = assertNotNull(projection.ground(north, 0.0, 5.0)); val far = assertNotNull(projection.ground(north, 0.0, 40.0))
        assertTrue(near.second > 1200f && far.second > 1200f && near.second > far.second)
        assertTrue(kotlin.math.abs(near.first - 540f) < 1f)
    }
    @Test fun eastIsToTheRightAndBehindIsHidden() {
        assertTrue(projection.ground(north, 3.0, 10.0)!!.first > 540f)
        assertNull(projection.ground(north, 0.0, -5.0))
    }
    @Test fun basisFromIdentityRotationFacesDownward() {
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // phone flat, screen up: camera points at the ground
        assertTrue(cameraBasis(m, 0.0).pitchDegrees < -80)
    }
}
