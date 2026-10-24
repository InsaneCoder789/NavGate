package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class RouteIntegrityTest {
    private val route = fixtureRoute()

    @Test fun `a provider-shaped route is valid and a checkpoint of it restores`() {
        assertEquals(emptyList<String>(), RouteIntegrity.shapeErrors(route))
        val saved = SessionSnapshot(sessionId = "trip", route = route, status = SessionStatus.Navigating, remainingMeters = route.distanceMeters, remainingSeconds = route.estimatedSeconds)
        assertEquals(emptyList<String>(), RouteIntegrity.externalCheckpointErrors(SessionCheckpoint(saved)))
        assertTrue(RouteIntegrity.externalCheckpointErrors(SessionCheckpoint(saved.copy(alongMeters = Double.NaN))).isNotEmpty())
    }

    @Test fun `forged geometry or an unknown source is rejected`() {
        val shortcut = listOf(route.geometry.first(), route.geometry.last())
        assertTrue(RouteIntegrity.shapeErrors(route.copy(geometry = shortcut)).isNotEmpty())
        assertTrue(RouteIntegrity.shapeErrors(route.copy(source = "local_campus_astar")).isNotEmpty())
    }

    @Test fun `region status reports the catalogue without promising routes`() {
        val pack = fixturePack()
        assertEquals(RegionReadiness.Missing, regionStatus("test", null).readiness)
        assertEquals(RegionReadiness.Catalogue, regionStatus("test", pack).readiness)
        assertEquals(RegionReadiness.Invalid, regionStatus("other", pack).readiness)
        assertEquals(pack.places.size, regionStatus("test", pack).placeCount)
    }
}
