package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class RouteJoinTest {
    private val origin = GeoPoint(19.0, 72.8)
    private fun at(north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude)

    private fun leg(fromM: Double, toM: Double, dest: String): CampusRoute {
        val pts = (0..10).map { at(fromM + (toM - fromM) * it / 10.0) }
        val len = pts.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        return CampusRoute("r$dest", 1, "m", "v", dest, "", pts, emptyList(), len, len / 10.0,
            listOf(Maneuver("Head north", pts.first(), 0.0, "Start"), Maneuver("Turn left", pts[5], len / 2, "Left"), Maneuver("You have arrived", pts.last(), len, "Arrive")),
            "", source = "valhalla", profile = "driving", provider = "t", fetchedAtEpochMillis = 1, destinationPoint = pts.last(), destinationTitle = dest,
            shapeElapsedSeconds = pts.indices.map { it * len / 100.0 })
    }

    @Test fun `three stops become one continuous trip with a single arrival`() {
        val a = leg(0.0, 500.0, "B"); val b = leg(500.0, 1200.0, "C")
        val trip = RouteJoin.join(listOf(a, b), listOf("B", "C"))!!
        assertEquals(a.distanceMeters + b.distanceMeters, trip.distanceMeters, 0.01)
        assertEquals(a.geometry.size + b.geometry.size - 1, trip.geometry.size)
        assertEquals(trip.geometry.size, trip.shapeElapsedSeconds.size)
        assertTrue(trip.shapeElapsedSeconds.zipWithNext().all { (x, y) -> y >= x })
        assertEquals(1, trip.maneuvers.count { it.kind == "Arrive" })
        assertEquals("Arrive", trip.maneuvers.last().kind)
        assertTrue(trip.maneuvers.any { it.instruction.startsWith("Stop 2 of 3: B") })
        assertEquals(1, trip.maneuvers.count { it.kind == "Start" })
        assertTrue(trip.maneuvers.zipWithNext().all { (x, y) -> x.alongMeters <= y.alongMeters })
        assertEquals("C", trip.destinationTitle)
        assertEquals(emptyList<String>(), RouteIntegrity.shapeErrors(trip))
    }

    @Test fun `mismatched input is refused`() {
        assertNull(RouteJoin.join(emptyList(), emptyList()))
        assertNull(RouteJoin.join(listOf(leg(0.0, 100.0, "B")), emptyList()))
        assertNull(RouteJoin.join(listOf(leg(0.0, 100.0, "B"), leg(100.0, 200.0, "C").copy(profile = "walking")), listOf("B", "C")))
    }

    @Test fun `the session can follow a joined trip to its end`() {
        val trip = RouteJoin.join(listOf(leg(0.0, 400.0, "B"), leg(400.0, 900.0, "C")), listOf("B", "C"))!!
        val geo = RouteGeometry(trip.geometry)
        assertEquals(trip.distanceMeters, geo.length, 1.0)
    }
}
