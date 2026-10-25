package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** The Lokhandwala Marg / Akurli Road roundabout in Kandivali East, taken from the map tiles the app draws (centre and arm bearings). */
class RoundaboutRewriterTest {
    private val centre = GeoPoint(19.197055, 72.872471)
    private val ring = RoundaboutRing(centre, 9.0, listOf(14.0, 78.0, 347.0, 268.0))

    /** A walking route that comes in from the west arm, crosses the middle, and leaves by the north arm. */
    private fun route(): CampusRoute {
        val inbound = (6 downTo 1).map { centre.offset(it * 10.0, 268.0) }
        val outbound = (1..6).map { centre.offset(it * 10.0, 14.0) }
        val pts = inbound + listOf(centre) + outbound
        val len = pts.lengthMeters()
        var run = 0.0
        val along = pts.mapIndexed { i, p -> if (i > 0) run += pts[i - 1].distanceTo(p); run }
        return CampusRoute("r", 1, "m", "v", "dest", "", pts, emptyList(), len, len / 1.4,
            listOf(
                Maneuver("Walk east.", pts.first(), 0.0, "Start"),
                Maneuver("Bear right.", pts[5], along[5], "SlightRight"),
                Maneuver("Turn left onto Akurli Road.", pts[8], along[8], "Left"),
                Maneuver("Your destination is on the left.", pts.last(), len, "Arrive")),
            "", source = "valhalla", profile = "walking", provider = "t", fetchedAtEpochMillis = 1, destinationPoint = pts.last(), destinationTitle = "dest",
            shapeElapsedSeconds = along.map { it / 1.4 })
    }

    @Test fun `two corners become one roundabout step with the exit number`() {
        val fixed = RoundaboutRewriter.apply(route(), listOf(ring))
        val kinds = fixed.maneuvers.map { it.kind }
        assertEquals(listOf("Start", "Roundabout", "Arrive"), kinds)
        val step = fixed.maneuvers[1]
        assertEquals(2, step.exitNumber)
        assertEquals("At the roundabout, take the 2nd exit onto Akurli Road", step.instruction)
    }

    @Test fun `the route follows the ring instead of crossing the middle`() {
        val original = route()
        val fixed = RoundaboutRewriter.apply(original, listOf(ring))
        val nearMiddle = fixed.geometry.filter { it.distanceTo(centre) < ring.radiusMeters * 0.5 }
        assertTrue("route still passes through the middle", nearMiddle.isEmpty())
        assertTrue(original.geometry.any { it.distanceTo(centre) < 1.0 })
        val onRing = fixed.geometry.filter { abs(it.distanceTo(centre) - ring.radiusMeters) < 0.5 }
        assertTrue("arc points on the ring: ${onRing.size}", onRing.size >= 4)
    }

    @Test fun `the rewritten route is still a valid route`() {
        val fixed = RoundaboutRewriter.apply(route(), listOf(ring))
        assertEquals(emptyList<String>(), RouteIntegrity.shapeErrors(fixed))
        assertEquals(fixed.geometry.size, fixed.shapeElapsedSeconds.size)
        assertTrue(fixed.shapeElapsedSeconds.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(fixed.maneuvers.zipWithNext().all { (a, b) -> a.alongMeters <= b.alongMeters })
    }

    @Test fun `a route that only passes beside the roundabout is left alone`() {
        val pts = (-6..6).map { centre.offset(60.0, 90.0).offset(it * 10.0, 0.0) }
        val len = pts.lengthMeters()
        val r = route().copy(geometry = pts, distanceMeters = len, estimatedSeconds = len / 1.4, shapeElapsedSeconds = pts.indices.map { it * len / 12 / 1.4 },
            maneuvers = listOf(Maneuver("Walk north.", pts.first(), 0.0, "Start"), Maneuver("Arrive", pts.last(), len, "Arrive")))
        assertEquals(r, RoundaboutRewriter.apply(r, listOf(ring)))
    }

    @Test fun `going straight on takes the exit opposite the entry`() {
        val inbound = (6 downTo 1).map { centre.offset(it * 10.0, 268.0) }; val outbound = (1..6).map { centre.offset(it * 10.0, 78.0) }
        val pts = inbound + centre + outbound; val len = pts.lengthMeters()
        var run = 0.0; val along = pts.mapIndexed { i, p -> if (i > 0) run += pts[i - 1].distanceTo(p); run }
        val r = route().copy(geometry = pts, distanceMeters = len, estimatedSeconds = len / 1.4, shapeElapsedSeconds = along.map { it / 1.4 },
            maneuvers = listOf(Maneuver("Walk east.", pts.first(), 0.0, "Start"), Maneuver("Continue onto Lokhandwala Marg.", pts[7], along[7], "Straight"), Maneuver("Arrive", pts.last(), len, "Arrive")))
        val fixed = RoundaboutRewriter.apply(r, listOf(ring))
        assertEquals(3, fixed.maneuvers[1].exitNumber)
    }
}
