package com.rohanc.navgate.ui.map

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteResponse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRouteGeoJsonTest {
    @Test
    fun `line source includes all route coordinates`() {
        val route = RouteResponse(
            distanceMeters = 10.0,
            durationSeconds = 8.0,
            pathCoordinates = listOf(
                Coordinate(20.0, 85.0),
                Coordinate(20.1, 85.1),
                Coordinate(20.2, 85.2),
            ),
            steps = emptyList(),
        )

        val json = MapRouteGeoJson.lineString(route)

        assertTrue(json.contains("[85.0,20.0]"))
        assertTrue(json.contains("[85.1,20.1]"))
        assertTrue(json.contains("LineString"))
    }
}
