package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteIntegrityTest {
    @Test fun `checkpoint must follow pinned edges rather than shortcut between endpoints`() = runTest {
        val pack=fixturePack()
        val route=(CampusRoutingProvider(pack).route(PlanRequest(1,"test",pack.nodes.first().point,"target")) as RouteResult.Ready).route
        val saved=SessionSnapshot(sessionId="trip",route=route,status=SessionStatus.Navigating,
            remainingMeters=route.distanceMeters,remainingSeconds=route.estimatedSeconds)
        assertTrue(RouteIntegrity.checkpointErrors(SessionCheckpoint(saved),pack).isEmpty())
        val shortcut=listOf(route.geometry.first(),route.geometry.last())
        val forged=route.copy(geometry=shortcut,distanceMeters=shortcut.lengthMeters(),
            maneuvers=listOf(Maneuver("Arrive",shortcut.last(),shortcut.lengthMeters(),"Arrive")))
        assertTrue(RouteIntegrity.shapeErrors(forged).isEmpty())
        assertTrue(RouteIntegrity.checkpointErrors(SessionCheckpoint(saved.copy(route=forged,remainingMeters=forged.distanceMeters)),pack).isNotEmpty())
        assertTrue(RouteIntegrity.checkpointErrors(SessionCheckpoint(saved.copy(alongMeters=Double.NaN)),pack).isNotEmpty())
        assertTrue(RouteIntegrity.checkpointErrors(SessionCheckpoint(saved),pack.copy(version="v2")).isNotEmpty())
        assertTrue(RouteIntegrity.checkpointErrors(SessionCheckpoint(saved),pack.copy(edges=pack.edges.map{it.copy(access=Access.Restricted)})).isNotEmpty())
    }

    @Test fun `region capabilities distinguish catalogue graph and invalid region`() {
        val pack=fixturePack()
        assertEquals(RegionReadiness.Missing,regionStatus("test",null).readiness)
        assertEquals(RegionReadiness.CatalogueOnly,regionStatus("test",pack.copy(edges=emptyList(),entrances=emptyList())).readiness)
        assertEquals(listOf("walking"),regionStatus("test",pack).supportedProfiles)
        assertEquals(RegionReadiness.Invalid,regionStatus("other",pack).readiness)
        assertTrue(regionStatus("test",pack.copy(entrances=emptyList())).supportedProfiles.isEmpty())
    }
}
