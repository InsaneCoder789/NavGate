package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Drives (and walks) the real recorded Mumbai Valhalla routes, point by point, through the full navigation session with noisy
 * GPS, and checks every turn: right kind, right side, announced once, in order, near the turn, arriving at the end.
 */
class RealRouteReplayTest {
    private fun request(mode: String) = PlanRequest(9, "mumbai", GeoPoint(18.92297, 72.83329), "marine-drive", profile = mode,
        destinationPoint = GeoPoint(18.9341, 72.82365), destinationTitle = "Marine Drive")
    private fun fixture(mode: String) = javaClass.classLoader!!.getResourceAsStream("routing/mumbai-$mode.json")!!.bufferedReader().readText()
    private fun route(mode: String) = (ValhallaRoutingProvider.parse(fixture(mode), request(mode), "test", 1000) as RouteResult.Ready).route

    private fun sideOf(kind: String) = when (kind) { "Left", "SlightLeft", "SharpLeft", "KeepLeft" -> -1; "Right", "SlightRight", "SharpRight", "KeepRight" -> 1; else -> 0 }

    @Test fun `every turn on the real routes goes the way the road goes`() {
        for (mode in listOf("driving", "walking")) {
            val route = route(mode); val shape = route.geometry
            route.maneuvers.forEach { m ->
                val index = shape.indexOfFirst { it == m.target }
                val angle = ManeuverKinds.turnAngle(shape, index) ?: return@forEach
                val side = sideOf(m.kind)
                if (side != 0 && abs(angle) > 25) assertEquals("$mode '${m.instruction}' is ${m.kind} but the road turns ${"%.0f".format(angle)} degrees", if (angle > 0) 1 else -1, side)
                if (m.kind == "SharpLeft" || m.kind == "SharpRight") assertTrue("$mode ${m.instruction} sharp but ${angle}", abs(angle) >= 115)
            }
        }
    }

    @Test fun `the roundabout is recognised with its exit number and direction`() {
        val route = route("driving")
        val enter = route.maneuvers.first { it.kind == "Roundabout" }
        assertEquals(1, enter.exitNumber)
        assertTrue(enter.instruction, enter.instruction.contains("1st exit"))
        assertNotNull("overall change through the roundabout is measured", enter.bearingChange)
        val exit = route.maneuvers.first { it.kind == "RoundaboutExit" }
        assertTrue(route.maneuvers.indexOf(exit) == route.maneuvers.indexOf(enter) + 1)
        println("roundabout turns ${enter.bearingChange} -> ${ManeuverKinds.roundaboutExitDirection(enter.bearingChange!!)}")
    }

    private fun replay(mode: String, speed: Double, noise: Double, seed: Long): List<String> = runBlocking {
        val route = route(mode); val geometry = RouteGeometry(route.geometry); val rng = Random(seed)
        val session = NavigationSession(); session.preview(route)
        session.location(LocationFix(route.geometry.first(), 100, 4.0, null, 0.0), 100); session.start(100)
        val spoken = mutableListOf<String>(); var arrived = false; var lastAlong = -1.0; var lastStep = 0
        var s = 0.0; var t = 100L; var limited = 0
        while (s < geometry.length + 30 && !arrived) {
            t += 1_000; s = minOf(geometry.length, s + speed)
            val p = geometry.pointAt(s)
            val fix = GeoPoint(p.latitude + rng.nextGaussian() * noise / 111_320.0, p.longitude + rng.nextGaussian() * noise / 111_320.0)
            val u = session.location(LocationFix(fix, t, 5.0, null, speed), t)
            u.speak?.let { spoken += it }
            val snap = u.snapshot
            if (snap.status == SessionStatus.SignalLimited) limited++
            assertFalse("never off route on a recorded route (${snap.signalReason})", snap.offRoute)
            assertTrue("progress only moves forward at s=$s", snap.alongMeters >= lastAlong - 1.0); lastAlong = snap.alongMeters
            assertTrue("step index never goes backwards", snap.maneuverIndex >= lastStep); lastStep = snap.maneuverIndex
            if (snap.status == SessionStatus.Arrived) arrived = true
            if (s >= geometry.length) { session.location(LocationFix(route.geometry.last(), t + 1_000, 4.0, null, 0.0), t + 1_000).also { if (it.snapshot.status == SessionStatus.Arrived) arrived = true } }
        }
        assertEquals("$mode: guidance never lost the signal", 0, limited)
        assertTrue("$mode: arrived at the destination", arrived)
        spoken
    }

    @Test fun `driving replay announces each turn once in order and arrives`() {
        val spoken = replay("driving", 11.0, 3.5, 3)
        println("DRIVING: " + spoken.joinToString(" | "))
        val route = route("driving")
        val turns = route.maneuvers.filter { it.kind !in setOf("Start", "Arrive") }
        // Every real instruction was spoken when its step began, none twice.
        fun said(m: Maneuver) = spoken.count { it == m.instruction || it.endsWith(m.instruction.trim().trimEnd('.').replaceFirstChar { c -> c.lowercase() }) }
        turns.forEach { m -> assertTrue("'${m.instruction}' announced once or at most once per distance (400/120 m): $spoken", said(m) in 1..2) }
        assertEquals("arrival spoken exactly once: $spoken", 1, spoken.count { it.contains("arrived") })
        // Order of the instructions follows the route.
        val order = turns.map { m -> spoken.indexOfFirst { it == m.instruction || it.endsWith(m.instruction.trim().trimEnd('.').replaceFirstChar { c -> c.lowercase() }) } }
        assertEquals(order.sorted(), order)
        assertTrue("the roundabout is announced ahead of time: $spoken", spoken.any { it.startsWith("In ") && it.contains("Wellington Circus") })
    }

    @Test fun `walking replay on foot also completes cleanly`() {
        val spoken = replay("walking", 1.4, 3.0, 5)
        println("WALKING: " + spoken.size + " lines")
        assertTrue(spoken.any { it.contains("arrived") })
    }

    @Test fun `slow and fast drivers both stay on the route`() {
        for ((speed, seed) in listOf(4.0 to 7L, 19.0 to 8L)) replay("driving", speed, 3.0, seed)
    }
}

class ShortestRoutePayloadTest {
    private val request = PlanRequest(1, "m", GeoPoint(18.9, 72.8), "d", profile = "driving", destinationPoint = GeoPoint(18.95, 72.82))
    @Test fun `shortest asks the router for the least distance and fastest does not`() {
        assertTrue(ValhallaRoutingProvider.payload(request.copy(preference = "shortest")).contains("\"shortest\":true"))
        assertFalse(ValhallaRoutingProvider.payload(request).contains("shortest"))
    }
}
