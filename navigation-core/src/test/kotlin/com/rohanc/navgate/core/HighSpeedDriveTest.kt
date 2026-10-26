package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Guidance at motorway speeds: the fixes arrive far apart on the road, and every one of them is real. */
class HighSpeedDriveTest {
    private fun straightRoute(lengthMeters: Double): CampusRoute {
        val a = GeoPoint(19.0, 72.80); val points = (0..(lengthMeters / 50).toInt()).map { a.offset(it * 50.0, 90.0) }
        val total = points.lengthMeters(); var run = 0.0
        val along = points.mapIndexed { i, p -> if (i > 0) run += points[i - 1].distanceTo(p); run }
        return CampusRoute("hs", 1, "m", "v", "dest", "", points, emptyList(), total, total / 25.0,
            listOf(Maneuver("Head east", points.first(), 0.0, "Start"), Maneuver("You have arrived", points.last(), total, "Arrive")),
            "", source = "valhalla", profile = "driving", provider = "t", fetchedAtEpochMillis = 1, destinationPoint = points.last(), destinationTitle = "dest",
            shapeElapsedSeconds = along.map { it / 25.0 })
    }

    /** Drives the route at [mps] with a fix every [intervalMillis]; returns where guidance thinks the vehicle is at the end. */
    private suspend fun drive(mps: Double, seconds: Int, intervalMillis: Long = 200, startAt: Double = 0.0, jitterMillis: Long = 0, reportSpeed: Boolean = true): Pair<SessionSnapshot, Double> {
        val route = straightRoute(30_000.0); val session = NavigationSession()
        session.preview(route)
        var t = 1_000L; var along = startAt
        session.location(LocationFix(route.geometry.first().offset(along, 90.0), t, 4.0), t)
        session.start(t)
        repeat((seconds * 1000 / intervalMillis).toInt()) { i ->
            val step = intervalMillis + if (i % 3 == 0) jitterMillis else -jitterMillis   // fixes batched or late, as phones deliver them
            t += step; along += mps * step / 1000.0
            session.location(LocationFix(route.geometry.first().offset(along, 90.0), t, 4.0, speedMps = mps.takeIf { reportSpeed }), t)
        }
        return session.state.value to along
    }

    @Test fun `guidance keeps up at 100 km per hour`() = runTest { val (s, truth) = drive(27.8, 30); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 40.0) }
    @Test fun `guidance keeps up at 180 km per hour`() = runTest { val (s, truth) = drive(50.0, 30); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 60.0) }
    @Test fun `guidance keeps up at 250 km per hour`() = runTest { val (s, truth) = drive(69.4, 30); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 90.0) }
    @Test fun `guidance keeps up at 250 km per hour with fixes arriving unevenly`() = runTest { val (s, truth) = drive(69.4, 30, jitterMillis = 120); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 120.0) }
    @Test fun `a trip that starts mid-route at motorway speed is followed too`() = runTest { val (s, truth) = drive(33.0, 20, startAt = 4_000.0); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 60.0) }
    @Test fun `guidance keeps up at 250 km per hour when fixes carry no speed`() = runTest { val (s, truth) = drive(69.4, 30, reportSpeed = false); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 120.0) }
    @Test fun `guidance keeps up at 100 km per hour when fixes carry no speed`() = runTest { val (s, truth) = drive(27.8, 30, reportSpeed = false); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 60.0) }
    @Test fun `guidance keeps up at 250 km per hour with no speed and uneven delivery`() = runTest { val (s, truth) = drive(69.4, 30, jitterMillis = 150, reportSpeed = false); assertTrue("along ${s.alongMeters} of $truth", s.alongMeters > truth - 150.0) }
}
