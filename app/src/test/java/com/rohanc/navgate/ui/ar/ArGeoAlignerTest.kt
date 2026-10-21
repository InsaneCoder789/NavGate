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

    @Test fun gpsPullsAccumulatedDriftBack() {
        val aligner = ArGeoAligner()
        aligner.update(reading(0.0, 0), 0.0, start, 5.0, null, 0.0)
        var last = aligner.update(reading(0.0, 1), 0.0, start, 5.0, null, 0.0)!!
        repeat(400) { last = aligner.update(reading(-20.0, 2L + it * 33L), 0.0, start, 5.0, null, 0.0)!! } // ARCore claims 20 m north, GPS says stationary
        assertTrue(abs(last.position.latitude - start.latitude) * 111_320 < 8.0)
    }
}
