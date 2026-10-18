package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test

class DestinationAvailabilityTest {
    @Test fun `search readiness never confuses a display coordinate with a routable entrance`() {
        val pack=fixturePack()
        assertEquals(DestinationReadiness.ReviewedEntrance,destinationAvailability(pack)["target"]?.readiness)
        assertEquals(listOf("entry"),destinationAvailability(pack)["target"]?.entranceIds)
        assertEquals(DestinationReadiness.MissingEntrance,destinationAvailability(pack.copy(entrances=emptyList()))["target"]?.readiness)
        val unlocated=pack.copy(places=pack.places.map{it.copy(point=null)})
        assertEquals(false,destinationAvailability(unlocated)["target"]?.hasDisplayCoordinate)
        assertEquals(DestinationReadiness.ReviewedEntrance,destinationAvailability(unlocated)["target"]?.readiness)
        val restricted=pack.copy(edges=pack.edges.map{it.copy(access=Access.Restricted)})
        assertEquals(DestinationReadiness.MissingGraph,destinationAvailability(restricted)["target"]?.readiness)
        assertEquals(DestinationReadiness.InvalidGraph,destinationAvailability(pack.copy(schemaVersion=9))["target"]?.readiness)
    }
}
