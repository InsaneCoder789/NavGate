package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.Maneuver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckpointTrackTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun north(meters: Double) = GeoPoint(origin.latitude + meters / 111_320.0, origin.longitude)
    private fun east(from: GeoPoint, meters: Double) = GeoPoint(from.latitude, from.longitude + meters / (111_320.0 * kotlin.math.cos(Math.toRadians(from.latitude))))

    // 600 m north, a right turn, then 400 m east.
    private val corner = north(600.0)
    private val route = CampusRoute("r", 1, "x", "v", "d", "e", listOf(origin, corner, east(corner, 400.0)), emptyList(), 1000.0, 600.0,
        listOf(Maneuver("Head north", origin, 0.0, "Start"), Maneuver("Turn right", corner, 600.0, "Right"), Maneuver("Arrive", east(corner, 400.0), 1000.0, "Arrive")), "now")

    @Test fun onlyTheCurrentRoadIsLoaded() {
        val track = CheckpointTrack(route)
        track.update(origin)
        assertEquals(1, track.loadedStages)
        assertTrue(track.upcoming().size in 1..6)
        assertEquals(2, track.stageCount)
    }

    @Test fun passingGatesAdvancesAndLoadsTheNextRoad() {
        val track = CheckpointTrack(route)
        track.update(origin)
        val first = track.upcoming().first()
        val collected = track.update(north(first.alongMeters + 1))
        assertTrue(collected.any { it.id == first.id })
        // Walk to the corner: the turn gate is collected and the second road becomes active.
        val atCorner = track.update(north(599.0)) + track.update(east(corner, 5.0))
        assertTrue(atCorner.any { it.kind == GateKind.Turn })
        assertEquals(1, track.stage)
        assertTrue(track.upcoming().first().alongMeters > 600)
    }

    @Test fun lastGateIsTheFinishAndGatesFaceAlongTheRoad() {
        val track = CheckpointTrack(route)
        track.update(origin)
        val gate = track.upcoming().first()
        assertTrue(gate.headingDegrees < 5 || gate.headingDegrees > 355, "first road runs north")
        track.update(east(corner, 399.0)); track.update(east(corner, 399.5))
        assertEquals(GateKind.Finish, track.upcoming().lastOrNull()?.kind ?: GateKind.Finish)
    }
}
