package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArGeoAlignerTest {
    private val start = GeoPoint(19.0, 72.85)
    // ARCore looking along its -Z axis; the compass says that is true east (90 degrees).
    private fun reading(z: Double, t: Long) = ArCameraReading(doubleArrayOf(0.0, 1.4, z), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), t)

    @Test fun compassSetsYawAndArMotionMovesThePositionEast() {
        val aligner = ArGeoAligner()
        val first = assertNotNull(aligner.update(reading(0.0, 0), 90.0, start, 5.0, null, 0.0))
        val later = assertNotNull(aligner.update(reading(-10.0, 1000), 90.0, start, 50.0, null, 0.0)) // 10 m "forward", GPS ignored (poor)
        val east = later.position.longitude - first.position.longitude
        assertTrue(east > 0 && abs(later.position.latitude - first.position.latitude) < 1e-6)
        assertTrue(abs(later.basis.headingDegrees - 90.0) < 1.0)
    }

    @Test fun gpsSlowlyPullsAccumulatedDriftBackWhileWalking() {
        val aligner = ArGeoAligner()
        aligner.update(reading(0.0, 0), 0.0, start, 5.0, 0.0, 1.4)
        var last = aligner.update(reading(0.0, 1), 0.0, start, 5.0, 0.0, 1.4)!!
        // ARCore claims the phone is 20 m further north than the road position says; two minutes of walking.
        repeat(3600) { last = aligner.update(reading(-20.0, 2L + it * 33L), 0.0, start, 5.0, 0.0, 1.4)!! }
        assertTrue(abs(last.position.latitude - start.latitude) * 111_320 < 8.0, "still ${abs(last.position.latitude - start.latitude) * 111_320} m out")
    }

    @Test fun gpsWobbleWhileStandingStillBarelyMovesTheLine() {
        val aligner = ArGeoAligner()
        aligner.update(reading(0.0, 0), 0.0, start, 5.0, 0.0, 0.0)
        // The position fix claims we are 6 m north; standing still, the line must hardly move in 10 s.
        val wobble = GeoPoint(start.latitude + 6.0 / 111_320.0, start.longitude)
        var last = aligner.update(reading(0.0, 1), 0.0, wobble, 6.0, 0.0, 0.0)!!
        repeat(300) { last = aligner.update(reading(0.0, 2L + it * 33L), 0.0, wobble, 6.0, 0.0, 0.0)!! }
        assertTrue(abs(last.position.latitude - start.latitude) * 111_320 < 0.8, "slid ${abs(last.position.latitude - start.latitude) * 111_320} m")
    }
}
