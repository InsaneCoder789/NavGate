package com.rohanc.navgate.navigation

import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEngineTest {
    private val origin = Coordinate(20.349884, 85.807529)
    private val library = Coordinate(20.350080, 85.808021)
    private val sportsComplex = Coordinate(20.350412, 85.808665)

    private val route =
        RouteResponse(
            distanceMeters = 142.0,
            durationSeconds = 104.0,
            pathCoordinates = listOf(origin, library, sportsComplex),
            steps =
                listOf(
                    RouteStep(
                        instruction = "Head north-east on Spine Road",
                        distanceMeters = 56.0,
                        targetLatitude = library.latitude,
                        targetLongitude = library.longitude,
                        maneuverType = ManeuverType.Straight,
                    ),
                    RouteStep(
                        instruction = "Turn right toward Sports Complex",
                        distanceMeters = 86.0,
                        targetLatitude = sportsComplex.latitude,
                        targetLongitude = sportsComplex.longitude,
                        maneuverType = ManeuverType.Right,
                    ),
                ),
        )

    @Test
    fun `step advancement happens only when user reaches the current step`() {
        val engine = NavigationEngine()
        engine.setArAvailability(ArAvailabilityState.Supported)
        engine.selectRoute(origin, sportsComplex, route)
        engine.startNavigation()

        val beforeStep = engine.updateUserProgress(origin, 42.0, 1_500)
        assertEquals(0, beforeStep.currentStepIndex)

        val afterStep = engine.updateUserProgress(library, 44.0, 1_500)
        assertEquals(1, afterStep.currentStepIndex)
        assertEquals("Turn right toward Sports Complex", afterStep.currentInstruction)
    }

    @Test
    fun `off route signal flips when user leaves the corridor`() {
        val engine = NavigationEngine()
        engine.selectRoute(origin, sportsComplex, route)
        engine.startNavigation()

        val nearby = Coordinate(20.350050, 85.808050)
        val farAway = Coordinate(20.352000, 85.812000)

        assertFalse(engine.shouldReroute(nearby))
        assertTrue(engine.shouldReroute(farAway))
    }

    @Test
    fun `guidance confidence degrades when gps is stale`() {
        val engine = NavigationEngine()
        engine.setArAvailability(ArAvailabilityState.Supported)
        engine.selectRoute(origin, sportsComplex, route)
        engine.startNavigation()

        val snapshot = engine.updateUserProgress(origin, 90.0, 10_000)

        assertEquals(GuidanceConfidence.Low, snapshot.locationConfidence.label)
        assertEquals(GuidanceConfidence.Low, snapshot.guidanceConfidence.label)
    }

    @Test
    fun `heading delta stays normalized for ar alignment`() {
        val engine = NavigationEngine()
        engine.selectRoute(origin, sportsComplex, route)

        val delta = engine.headingDelta(350.0)

        requireNotNull(delta)
        assertTrue(delta in -180.0..180.0)
    }
}
