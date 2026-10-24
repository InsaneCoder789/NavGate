package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

/** Real-world driving glitches replayed against the navigation session. */
class SessionScenarioTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    private fun road(id: String, vararg pts: GeoPoint): CampusRoute {
        val geometry = pts.toList(); val length = geometry.lengthMeters()
        val times = DoubleArray(geometry.size).also { for (i in 1 until geometry.size) it[i] = it[i - 1] + geometry[i - 1].distanceTo(geometry[i]) / 12.0 }
        return CampusRoute(id, 1, "x", "v", "dest", "", geometry, emptyList(), length, times.last(),
            listOf(Maneuver("Head out", geometry.first(), 0.0, "Start"), Maneuver("Arrive", geometry.last(), length, "Arrive")), "", source = "valhalla", profile = "driving",
            provider = "p", fetchedAtEpochMillis = 1, destinationPoint = geometry.last(), shapeElapsedSeconds = times.toList())
    }

    private val primary = road("primary", at(0.0, 0.0), at(0.0, 2000.0))
    private val backup = road("backup", at(0.0, 0.0), at(0.0, 500.0), at(1500.0, 500.0), at(1500.0, 2000.0), at(0.0, 2000.0))

    private suspend fun started(route: CampusRoute = primary): NavigationSession {
        val s = NavigationSession(); s.preview(route)
        s.location(LocationFix(route.geometry.first(), 100, 4.0, null, 10.0), 100); s.start(100)
        return s
    }
    private fun fix(p: GeoPoint, t: Long, acc: Double = 4.0, speed: Double = 12.0) = LocationFix(p, t, acc, null, speed)

    @Test fun `one poor accuracy fix mid trip does not interrupt guidance`() = runTest {
        val s = started()
        s.location(fix(at(0.0, 60.0), 5_100), 5_100)
        val update = s.location(fix(at(0.0, 72.0), 6_100, acc = 80.0), 6_100)
        assertEquals(SessionStatus.Navigating, update.snapshot.status)
        val next = s.location(fix(at(0.0, 84.0), 7_100), 7_100)
        assertEquals(SessionStatus.Navigating, next.snapshot.status)
        assertTrue(next.snapshot.alongMeters > 75)
    }

    @Test fun `sideways gps noise never moves progress backwards`() = runTest {
        val s = started(); var last = 0.0
        val noise = listOf(7.0, -8.0, 5.0, -6.0, 9.0, -9.0, 4.0, -7.0)
        for (i in 1..40) {
            val t = 100L + i * 1_000
            val snap = s.location(fix(at(noise[i % noise.size], 12.0 * i), t), t).snapshot
            assertTrue("step $i", snap.alongMeters >= last - 1e-6); last = snap.alongMeters
            assertTrue(snap.lockedToRoute)
        }
        assertEquals(480.0, last, 20.0)
    }

    @Test fun `leaving onto a precomputed backup route switches without the network`() = runTest {
        val s = started()
        s.setBackups(listOf(backup))
        assertEquals(1, s.state.value.backupRoutes)
        var t = 100L; var snap = s.state.value
        for (i in 1..40) { t += 1_000; snap = s.location(fix(at(0.0, 12.0 * i), t), t).snapshot }              // up to 480 m
        var reroute: RerouteRequest? = null
        for (i in 1..30) { t += 1_000; val u = s.location(fix(at(12.0 * i, 500.0), t), t); snap = u.snapshot; if (u.reroute != null) reroute = u.reroute; if (snap.route?.id == "backup") break }
        assertEquals("backup", snap.route?.id)
        assertNull("no network reroute was needed", reroute)
        assertEquals(SessionStatus.Navigating, snap.status)
        assertFalse(snap.offRoute)
    }

    @Test fun `backups for a different destination or mode are rejected`() = runTest {
        val s = started()
        s.setBackups(listOf(backup.copy(destinationPlaceId = "elsewhere"), backup.copy(id = "walk", profile = "walking"), primary))
        assertEquals(0, s.state.value.backupRoutes)
    }

    @Test fun `two agreeing fixes after a long gap relock instead of freezing guidance`() = runTest {
        val s = started()
        for (i in 1..10) { val t = 100L + i * 1_000; s.location(fix(at(0.0, 12.0 * i), t), t) }
        // Tunnel: no fixes for 8 s while the car covers 100 m, then the fix is 700 m on (a bad first reading), then confirmed.
        val first = s.location(fix(at(0.0, 900.0), 18_100), 18_100).snapshot
        assertEquals(SessionStatus.SignalLimited, first.status)
        val second = s.location(fix(at(0.0, 912.0), 19_100), 19_100).snapshot
        assertEquals(SessionStatus.Navigating, second.status)
        assertTrue(second.alongMeters > 850)
    }
}

class SessionVoiceAndDirectionTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    private fun road(): CampusRoute {
        val geometry = listOf(at(0.0, 0.0), at(0.0, 1000.0), at(800.0, 1000.0)); val length = geometry.lengthMeters()
        val times = DoubleArray(3).also { it[1] = 1000.0 / 12; it[2] = length / 12 }
        return CampusRoute("r", 1, "x", "v", "dest", "", geometry, emptyList(), length, times.last(),
            listOf(Maneuver("Head north", geometry[0], 0.0, "Start"), Maneuver("Turn right onto Khatau Road.", geometry[1], 1000.0, "Right"), Maneuver("Arrive", geometry[2], length, "Arrive")),
            "", source = "valhalla", profile = "driving", provider = "p", fetchedAtEpochMillis = 1, destinationPoint = geometry.last(), shapeElapsedSeconds = times.toList())
    }
    private fun fix(p: GeoPoint, t: Long, speed: Double = 12.0) = LocationFix(p, t, 4.0, null, speed)

    @Test fun `turns are announced once at each distance`() = runTest {
        val s = NavigationSession(); val route = road(); s.preview(route)
        s.location(fix(route.geometry.first(), 100), 100); s.start(100)
        val spoken = mutableListOf<String>()
        for (i in 1..80) { val t = 100L + i * 1_000; s.location(fix(at(0.0, 12.0 * i), t), t).speak?.let { spoken += it } }
        assertEquals(spoken.toString(), 2, spoken.size)
        assertTrue(spoken[0], spoken[0].startsWith("In 4") && spoken[0].contains("turn right onto Khatau Road"))
        assertTrue(spoken[1], spoken[1].startsWith("In 1"))
    }

    @Test fun `driving the wrong way along the route triggers a reroute`() = runTest {
        val s = NavigationSession(); val route = road(); s.preview(route)
        s.location(fix(route.geometry.first(), 100), 100); s.start(100)
        var t = 100L
        for (i in 1..30) { t += 1_000; s.location(fix(at(0.0, 12.0 * i), t), t) }       // 360 m along
        var reroute: RerouteRequest? = null
        for (i in 1..25) { t += 1_000; val u = s.location(fix(at(0.0, 360.0 - 12.0 * i), t), t); if (u.reroute != null) reroute = u.reroute }
        assertNotNull("U-turned onto the same road: guidance must notice", reroute)
    }
}

/** Longer stories: wrong turn, reroute, recovery, arrival and a second trip. */
class SessionStoryTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    private fun road(id: String, requestId: Long, vararg pts: GeoPoint): CampusRoute {
        val g = pts.toList(); val len = g.lengthMeters()
        val times = DoubleArray(g.size).also { for (i in 1 until g.size) it[i] = it[i - 1] + g[i - 1].distanceTo(g[i]) / 12.0 }
        return CampusRoute(id, requestId, "x", "v", "dest", "", g, emptyList(), len, times.last(),
            listOf(Maneuver("Head north", g.first(), 0.0, "Start"), Maneuver("Arrive", g.last(), len, "Arrive")), "", source = "valhalla", profile = "driving",
            provider = "p", fetchedAtEpochMillis = 1, destinationPoint = g.last(), shapeElapsedSeconds = times.toList())
    }
    private fun fix(p: GeoPoint, t: Long, speed: Double = 12.0) = LocationFix(p, t, 4.0, null, speed)

    @Test fun `a wrong turn is detected, rerouted and guidance finishes the trip`() = runTest {
        val primary = road("primary", 1, at(0.0, 0.0), at(0.0, 2000.0))
        val session = NavigationSession(); session.preview(primary)
        session.location(fix(primary.geometry.first(), 100), 100); session.start(100)
        var t = 100L; var request: RerouteRequest? = null
        for (i in 1..40) { t += 1_000; session.location(fix(at(0.0, 12.0 * i), t), t) }                 // 480 m along
        // The driver turns east onto a side road.
        for (i in 1..12) { t += 1_000; val u = session.location(fix(at(12.0 * i, 480.0), t), t); if (u.reroute != null && request == null) { request = u.reroute; break } }
        assertNotNull("leaving the road must request a reroute", request)
        assertEquals(SessionStatus.Rerouting, session.state.value.status)
        // The router answers with a road from where the driver was when asked.
        val from = request!!.request.origin
        val replacement = road("replacement", request.requestId, from, primary.geometry.last())
        val adopted = session.replace(request, RouteResult.Ready(replacement), t)
        assertEquals(adopted.snapshot.signalReason, SessionStatus.Navigating, adopted.snapshot.status)
        assertEquals("replacement", adopted.snapshot.route?.id)
        // Drive the new road to the end.
        var last = adopted.snapshot; var s = 0.0
        val geo = RouteGeometry(replacement.geometry)
        while (last.status != SessionStatus.Arrived && s < geo.length + 40) { t += 1_000; s += 12.0; last = session.location(fix(geo.pointAt(minOf(s, geo.length)), t), t).snapshot }
        assertEquals(SessionStatus.Arrived, last.status)
    }

    @Test fun `a failed reroute recovers by itself when the driver rejoins the road`() = runTest {
        val primary = road("primary", 1, at(0.0, 0.0), at(0.0, 2000.0))
        val session = NavigationSession(); session.preview(primary)
        session.location(fix(primary.geometry.first(), 100), 100); session.start(100)
        var t = 100L; var request: RerouteRequest? = null
        for (i in 1..20) { t += 1_000; session.location(fix(at(0.0, 12.0 * i), t), t) }
        for (i in 1..8) { t += 1_000; val u = session.location(fix(at(14.0 * i, 240.0), t), t); if (u.reroute != null && request == null) { request = u.reroute; break } }
        assertNotNull(request)
        val failed = session.replace(request!!, RouteResult.Unavailable(RouteFailure.NoConnection, "offline"), t)
        assertEquals(SessionStatus.GuidanceUnavailable, failed.snapshot.status)
        // The driver comes back to the road (still no network): guidance resumes on the original route.
        var snap = failed.snapshot
        for (i in 1..6) { t += 1_000; snap = session.location(fix(at(0.0, 240.0 + 12.0 * i), t), t).snapshot }
        assertEquals(snap.signalReason, SessionStatus.Navigating, snap.status)
        assertEquals("primary", snap.route?.id)
        assertFalse(snap.offRoute)
    }

    @Test fun `a new trip can start straight after arriving`() = runTest {
        val first = road("first", 1, at(0.0, 0.0), at(0.0, 200.0))
        val session = NavigationSession(); session.preview(first)
        session.location(fix(first.geometry.first(), 100), 100); session.start(100)
        var t = 100L; var s = 0.0; val g = RouteGeometry(first.geometry); var snap = session.state.value
        while (snap.status != SessionStatus.Arrived && s < 260) { t += 1_000; s += 12.0; snap = session.location(fix(g.pointAt(minOf(s, g.length)), t), t).snapshot }
        assertEquals(SessionStatus.Arrived, snap.status)
        val second = road("second", 2, at(0.0, 200.0), at(300.0, 200.0))
        session.preview(second)
        t += 1_000; session.location(fix(second.geometry.first(), t), t)
        val started = session.start(t).snapshot
        assertEquals(started.signalReason, SessionStatus.Navigating, started.status)
        assertEquals("second", started.route?.id)
        assertEquals(0.0, started.alongMeters, 1.0)
    }
}
