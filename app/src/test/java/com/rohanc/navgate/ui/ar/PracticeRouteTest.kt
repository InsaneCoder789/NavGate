package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteIntegrity
import org.junit.Assert.*
import org.junit.Test

class PracticeRouteTest {
    private val here = GeoPoint(19.0, 72.85)

    @Test fun `practice route is a valid walking route that starts at your feet`() {
        val route = PracticeRoute.build(here, 90.0, 1_000)
        assertTrue(RouteIntegrity.shapeErrors(route).toString(), RouteIntegrity.shapeErrors(route).isEmpty())
        assertEquals("walking", route.profile)
        assertTrue(route.geometry.first().distanceTo(here) < 0.5)
        assertEquals(90.0, route.distanceMeters, 3.0)
    }

    @Test fun `it heads the way the phone faces and turns right`() {
        val east = PracticeRoute.build(here, 90.0, 1_000)
        val corner = east.maneuvers[1].target
        assertTrue("first leg runs east: ${east.geometry.first().bearingTo(corner)}", Math.abs(east.geometry.first().bearingTo(corner) - 90.0) < 3.0)
        val afterTurn = corner.bearingTo(east.geometry.last())
        assertTrue("then south: $afterTurn", Math.abs(afterTurn - 180.0) < 3.0)
        val north = PracticeRoute.build(here, 0.0, 1_000)
        assertTrue(Math.abs(north.geometry.first().bearingTo(north.maneuvers[1].target) - 0.0) < 3.0 || north.geometry.first().bearingTo(north.maneuvers[1].target) > 357.0)
    }
}
