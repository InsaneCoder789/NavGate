package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class CourseMatchingTest {
    private val origin = GeoPoint(19.0, 72.8)
    private fun at(north: Double, east: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    /** Out 300 m northbound on x=0, a U-turn, then 300 m back southbound on x=+8: a carriageway pair. */
    private val outAndBack = RouteGeometry(
        (0..30).map { at(it * 10.0, 0.0) } + (30 downTo 0).map { at(it * 10.0, 8.0) })

    @Test fun `heading picks the carriageway you are on when both are close`() {
        // 5 m east of the northbound lane (3 m from the southbound one), driving north: distance alone would say southbound.
        val point = at(150.0, 5.0)
        val byDistance = outAndBack.nearest(point, 0.0, outAndBack.length, null)!!
        assertTrue("without a course the closer, wrong lane wins", byDistance.alongMeters > 300)
        val byCourse = outAndBack.nearest(point, 0.0, outAndBack.length, null, courseDegrees = 0.0)!!
        assertTrue("with a course the northbound lane wins (${byCourse.alongMeters})", byCourse.alongMeters in 140.0..160.0)
    }

    @Test fun `a driver on the return lane keeps the return lane`() {
        val point = at(150.0, 3.0)
        val hit = outAndBack.nearest(point, 0.0, outAndBack.length, null, courseDegrees = 180.0)!!
        assertTrue("southbound (${hit.alongMeters})", hit.alongMeters > 300)
    }

    @Test fun `a noisy heading within thirty degrees costs nothing`() {
        val point = at(150.0, 0.5)
        val hit = outAndBack.nearest(point, 0.0, outAndBack.length, null, courseDegrees = 25.0)!!
        assertTrue(hit.alongMeters in 140.0..160.0)
    }

    @Test fun `tracker follows the right lane through a drive with sideways wobble`() {
        val tracker = RouteTracker(outAndBack)
        var last = tracker.update(at(0.0, 5.0), 5.0, 12.0, 0, courseDegrees = 0.0)
        for (s in 1..20) last = tracker.update(at(s * 12.0, 5.0), 5.0, 12.0, s * 1000L, courseDegrees = 0.0)
        assertTrue("stayed on the northbound lane: ${last.alongMeters}", last.alongMeters in 200.0..260.0)
    }
}
