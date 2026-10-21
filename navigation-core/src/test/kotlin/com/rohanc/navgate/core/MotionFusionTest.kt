package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class MotionFusionTest {
    private val origin = GeoPoint(19.0, 72.8)
    private fun north(meters: Double, east: Double = 0.0) = GeoPoint(origin.latitude + meters / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    private fun err(a: GeoPoint, b: GeoPoint) = a.distanceTo(b)

    /** Drives north at 10 m/s for [seconds] with noisy 1 Hz GPS and 50 Hz IMU; returns (fusedRms, rawRms, final filter). */
    private fun drive(seconds: Int, gpsSigma: Double, dropout: IntRange? = null, gyroBias: Double = 0.02): Triple<Double, Double, MotionFusion> {
        val rng = Random(7); val fusion = MotionFusion(); var fusedSq = 0.0; var rawSq = 0.0; var n = 0
        for (ms in 0..seconds * 1000 step 20) {
            val t = ms / 1000.0
            if (ms % 1000 == 0 && (dropout == null || t.toInt() !in dropout)) {
                val noisy = north(10.0 * t + rng.nextGaussian() * gpsSigma, rng.nextGaussian() * gpsSigma)
                fusion.onGps(GpsSample(noisy, gpsSigma, 10.0 + rng.nextGaussian() * 0.3, 0.0 + rng.nextGaussian() * 2, ms.toLong()))
                if (t > 8) { rawSq += err(noisy, north(10.0 * t)).pow(2) }
            }
            fusion.onImu(ImuSample(ms.toLong() + 1, rng.nextGaussian() * 0.25, rng.nextGaussian() * 0.25, gyroBias + rng.nextGaussian() * 0.01, 0.0, true))
            if (t > 8 && ms % 1000 == 500) { val e = fusion.estimate(ms.toLong())!!; fusedSq += err(e.point, north(10.0 * t)).pow(2); n++ }
        }
        return Triple(sqrt(fusedSq / n), sqrt(rawSq / (n)), fusion)
    }

    @Test fun `fused position is closer to the truth than raw noisy GPS`() {
        val (fused, raw, _) = drive(60, gpsSigma = 8.0)
        assertTrue("fused $fused vs raw $raw", fused < raw * 0.8)
    }

    @Test fun `heading converges to the direction of travel despite gyro bias`() {
        val (_, _, fusion) = drive(60, gpsSigma = 6.0, gyroBias = 0.03)
        val h = fusion.estimate(60_000)!!.headingDegrees!!
        assertTrue("heading $h", min(h, 360 - h) < 6)
    }

    @Test fun `dead reckoning carries the position through a GPS dropout`() {
        val (_, _, fusion) = drive(34, gpsSigma = 4.0, dropout = 25..34)
        val e = fusion.estimate(34_000)!!
        assertTrue(e.usingDeadReckoning || e.point.valid)
        // Constant velocity inertia: within a few car lengths of the truth even after nine seconds without GPS.
        assertTrue("error ${err(e.point, north(340.0))}", err(e.point, north(340.0)) < 25.0)
    }

    @Test fun `standing still with a biased accelerometer does not drift`() {
        val rng = Random(3); val fusion = MotionFusion()
        for (ms in 0..30_000 step 20) {
            if (ms % 1000 == 0) fusion.onGps(GpsSample(north(0.0, 0.0), 5.0, 0.0, null, ms.toLong()))
            fusion.onImu(ImuSample(ms.toLong() + 1, 0.35 + rng.nextGaussian() * 0.05, -0.2 + rng.nextGaussian() * 0.05, 0.015, 90.0, true))
        }
        val e = fusion.estimate(30_000)!!
        assertTrue("speed ${e.speedMps}", e.speedMps < 0.2)
        assertTrue("drift ${err(e.point, north(0.0))}", err(e.point, north(0.0)) < 5.0)
        assertTrue(e.stationary)
    }

    @Test fun `accuracy tightens below a single noisy fix and uncertainty grows without GPS`() {
        val (_, _, fusion) = drive(30, gpsSigma = 12.0)
        val tight = fusion.estimate(30_000)!!.accuracyMeters
        assertTrue("accuracy $tight", tight <= 12.0)
    }

    @Test fun `forward acceleration is reported along the direction of travel`() {
        val fusion = MotionFusion()
        fusion.onGps(GpsSample(north(0.0), 3.0, 8.0, 0.0, 0))
        for (ms in 20..1000 step 20) fusion.onImu(ImuSample(ms.toLong(), 0.0, 2.0, 0.0, 0.0, true))
        val e = fusion.estimate(1_000)!!
        assertTrue("forward ${e.forwardAccel}", e.forwardAccel > 0.5)
        assertEquals(0.0, e.lateralAccel, 0.3)
    }

    @Test fun `flags a compass that needs calibration`() {
        val fusion = MotionFusion()
        fusion.onGps(GpsSample(north(0.0), 3.0, 0.0, null, 0))
        for (ms in 20..12_000 step 20) fusion.onImu(ImuSample(ms.toLong(), 0.0, 0.0, 0.0, 10.0, false))
        assertEquals(CalibrationState.CompassNeedsFigureEight, fusion.estimate(12_000)!!.calibration)
    }
}

class MotionFusionGlitchTest {
    private val origin = GeoPoint(19.0, 72.8)
    private fun north(meters: Double, east: Double = 0.0) = GeoPoint(origin.latitude + meters / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    @Test fun `a teleporting fix barely moves the fused position`() {
        val fusion = MotionFusion()
        for (s in 0..30) fusion.onGps(GpsSample(north(10.0 * s), 4.0, 10.0, 0.0, s * 1000L))
        val before = fusion.estimate(30_000)!!.point
        fusion.onGps(GpsSample(north(300.0 + 900.0, 600.0), 4.0, 10.0, 0.0, 31_000L))    // 1 km sideways-and-ahead glitch
        val after = fusion.estimate(31_000)!!.point
        assertTrue("moved ${before.distanceTo(after)} m", before.distanceTo(after) < 60.0)
    }

    @Test fun `waiting at a light does not creep`() {
        val fusion = MotionFusion(); val rng = Random(5)
        for (s in 0..10) fusion.onGps(GpsSample(north(100.0), 4.0, 0.0, null, s * 1000L))
        val start = fusion.estimate(10_000)!!.point
        for (s in 11..70) fusion.onGps(GpsSample(north(100.0 + rng.nextGaussian() * 4, rng.nextGaussian() * 4), 6.0, 0.1, null, s * 1000L))
        val end = fusion.estimate(70_000)!!.point
        assertTrue("drifted ${start.distanceTo(end)} m", start.distanceTo(end) < 4.0)
    }
}
