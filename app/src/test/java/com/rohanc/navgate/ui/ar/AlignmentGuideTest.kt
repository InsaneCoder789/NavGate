package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.navigation.NavigationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentGuideTest {
    @Test
    fun `aligned heading reports aligned state`() {
        val snapshot = sampleSnapshot()
        val heading = snapshot.origin!!.bearingTo(snapshot.route!!.steps.first().target)

        val status = alignmentStatus(snapshot, heading)

        assertEquals(AlignmentLevel.Aligned, status.level)
    }

    @Test
    fun `large heading gap reports recover state`() {
        val snapshot = sampleSnapshot()

        val status = alignmentStatus(snapshot, 270.0)

        assertEquals(AlignmentLevel.Recover, status.level)
    }

    private fun sampleSnapshot(): NavigationSnapshot {
        val origin = Coordinate(20.349884, 85.807529)
        val target = Coordinate(20.350080, 85.808021)
        return NavigationSnapshot(
            origin = origin,
            userLocation = origin,
            route =
                RouteResponse(
                    distanceMeters = 42.0,
                    durationSeconds = 30.0,
                    pathCoordinates = listOf(origin, target),
                    steps = listOf(
                        RouteStep(
                            instruction = "Head north-east",
                            distanceMeters = 42.0,
                            targetLatitude = target.latitude,
                            targetLongitude = target.longitude,
                            maneuverType = ManeuverType.Straight,
                        ),
                    ),
                ),
        )
    }
}
