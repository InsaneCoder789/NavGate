package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.CalibrationState
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RouteGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class MarkerStateTest {
    private val start = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(start.latitude + north / 111_320.0, start.longitude + east / (111_320.0 * cos(Math.toRadians(start.latitude))))
    private val route = RouteGeometry(listOf(at(0.0, 0.0), at(0.0, 300.0)))        // due north
    private fun angle(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)
    private fun estimate(speed: Double, ms: Long) = MotionEstimate(at(0.0, 5.0), speed, null, 0.0, 0.0, 4.0, CalibrationState.Good, speed < 0.5, false, ms)

    private fun run(seconds: Double, override: Double?, northUp: Boolean = false, speed: Double = 1.4): MarkerState {
        val m = MarkerState(); var t = 0.0; var ms = 1_000L
        while (t < seconds) { m.step(1.0 / 60, estimate(speed, ms), route, TrackInput(5.0 + speed * t, speed, 1_000L, true), 0.0, 17.0, 0.0, bearingOverride = override, nowMillis = ms, northUp = northUp); t += 1.0 / 60; ms += 16 }
        return m
    }

    @Test fun `the first frame has the road's heading, not north (no skid)`() {
        val m = MarkerState(); m.step(1.0 / 60, estimate(0.0, 1_000L), route, TrackInput(5.0, 0.0, 1_000L, true), 0.0, 17.0, 0.0, nowMillis = 1_000L)
        assertTrue("heading ${m.heading}", angle(m.heading, 0.0) < 3.0); assertTrue("camera ${m.cameraBearing}", angle(m.cameraBearing, 0.0) < 3.0)
    }

    @Test fun `under the AR camera the arrow faces where the camera faces, from the first frame`() {
        val first = MarkerState().also { it.step(1.0 / 60, estimate(1.4, 1_000L), route, TrackInput(5.0, 1.4, 1_000L, true), 0.0, 17.0, 0.0, bearingOverride = 95.0, nowMillis = 1_000L) }
        assertEquals(95.0, first.heading, 1e-6)
        val later = run(2.0, 95.0)
        assertTrue("arrow ${later.heading} against camera 95", angle(later.heading, 95.0) < 2.0); assertTrue("map ${later.cameraBearing}", angle(later.cameraBearing, 95.0) < 2.0)
    }

    @Test fun `the arrow follows the camera as it swings round`() {
        val m = MarkerState(); var ms = 1_000L; var worst = 0.0
        for (i in 0 until 600) {
            val camera = (40.0 * Math.sin(i / 40.0) + 360.0) % 360.0
            m.step(1.0 / 60, estimate(1.4, ms), route, TrackInput(5.0 + i * 0.023, 1.4, 1_000L, true), 0.0, 17.0, 0.0, bearingOverride = camera, nowMillis = ms); ms += 16
            if (i > 120) worst = maxOf(worst, angle(m.heading, camera))
        }
        assertTrue("the arrow lags the camera by up to $worst degrees", worst < 8.0)
    }

    @Test fun `north up keeps the map at north`() { assertTrue(angle(run(2.0, null, northUp = true).cameraBearing, 0.0) < 1.0) }

    @Test fun `guidance zoom is a quarter further out`() { assertEquals(0.75, Math.pow(2.0, zoomFor(7.0, false) - (17.0 + 2.6)), 0.0375) }
}

class FollowPaddingTest {
    @org.junit.Test fun theFlatMapPutsTheArrowInTheMiddleOfTheFreeMapNotAtTheBottom() {
        val (top, bottom) = followPaddingDp(800f, 0.46f, headerDp = 230f, footerDp = 190f, centred = true)
        val y = markerScreenY(800f, top, bottom)
        org.junit.Assert.assertEquals((230f + (800f - 190f)) / 2f, y, 0.01f)       // halfway between the header's bottom and the footer's top
        org.junit.Assert.assertTrue("the arrow sits at $y of 800", y < 800f * 0.62f)
    }

    @org.junit.Test fun theTippedViewKeepsTheArrowLowToShowMoreRoad() {
        val (top, bottom) = followPaddingDp(800f, 0.46f, 230f, 190f, centred = false)
        org.junit.Assert.assertEquals(0f, bottom, 0f)
        org.junit.Assert.assertTrue(markerScreenY(800f, top, bottom) > 800f * 0.65f)
    }

    @org.junit.Test fun theFloatingWindowCentresBetweenItsTwoCards() {
        val (top, bottom) = followPaddingDp(224f, 0.46f, headerDp = 58f, footerDp = 40f, centred = true)
        org.junit.Assert.assertEquals(121f, markerScreenY(224f, top, bottom), 0.01f)
    }

    @org.junit.Test fun unknownInsetsNeverGiveNegativePadding() {
        val (top, bottom) = followPaddingDp(800f, 0.46f, -5f, -9f, centred = true)
        org.junit.Assert.assertEquals(0f, top, 0f); org.junit.Assert.assertEquals(0f, bottom, 0f)
    }
}
