package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.data.routing.offline.RoutingTiles
import org.junit.Assert.*
import org.junit.Test

class RoutingTilesTest {
    @Test fun `tiles are named after their south west corner`() {
        assertEquals("E70_N15", RoutingTiles.nameFor(19.07, 72.88))      // Mumbai
        assertEquals("E75_N25", RoutingTiles.nameFor(28.6, 77.2))        // Delhi
        assertEquals("W5_N50", RoutingTiles.nameFor(51.5, -0.12))        // London
        assertEquals("W75_S35", RoutingTiles.nameFor(-33.4, -70.6))      // Santiago
        assertEquals("E0_N50", RoutingTiles.nameFor(50.0, 0.0))          // exactly on a corner
        assertEquals("W5_S5", RoutingTiles.nameFor(-0.01, -0.01))
    }

    @Test fun `a trip near a tile border lists both tiles`() {
        val tiles = RoutingTiles.covering(listOf(GeoPoint(19.0, 74.99), GeoPoint(19.1, 75.01)), marginMeters = 5_000.0)
        assertEquals(setOf("E70_N15", "E75_N15"), tiles.toSet())
    }

    @Test fun `a short trip inside a tile needs only that tile with a small margin`() {
        assertEquals(listOf("E70_N15"), RoutingTiles.covering(listOf(GeoPoint(18.95, 72.82), GeoPoint(19.07, 72.88)), marginMeters = 5_000.0))
    }

    @Test fun `names validate`() {
        assertTrue(RoutingTiles.isValid("E70_N15")); assertTrue(RoutingTiles.isValid("W5_S5"))
        assertFalse(RoutingTiles.isValid("E71_N15")); assertFalse(RoutingTiles.isValid("../E70_N15")); assertFalse(RoutingTiles.isValid("E70_N15.rd5"))
    }

    @Test fun `centre of a tile is inside it`() {
        val c = RoutingTiles.centerOf("E70_N15")!!
        assertEquals("E70_N15", RoutingTiles.nameFor(c)); assertEquals(72.5, c.longitude, 1e-9)
    }
}
