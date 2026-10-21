package com.rohanc.navgate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A 5 km drive with everything that goes wrong in a city: GPS noise, multipath bursts, a 100 m network-style offset,
 * a tunnel, a stop at a light and accuracy spikes. The whole pipeline (fusion -> session -> route tracker) runs at 10 Hz
 * and the position a driver would see is checked frame by frame.
 */
class DriveSimulationTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(east: Double, north: Double) = GeoPoint(origin.latitude + north / 111_320.0, origin.longitude + east / (111_320.0 * cos(Math.toRadians(origin.latitude))))

    private fun road(): CampusRoute {
        val geometry = listOf(at(0.0, 0.0), at(0.0, 2000.0), at(1500.0, 2000.0), at(1500.0, 3500.0)); val length = geometry.lengthMeters()
        val times = DoubleArray(geometry.size).also { for (i in 1 until geometry.size) it[i] = it[i - 1] + geometry[i - 1].distanceTo(geometry[i]) / 12.0 }
        return CampusRoute("sim", 1, "x", "v", "dest", "", geometry, emptyList(), length, times.last(),
            listOf(Maneuver("Head north", geometry[0], 0.0, "Start"), Maneuver("Turn right", geometry[1], 2000.0, "Right"), Maneuver("Turn left", geometry[2], 3500.0, "Left"), Maneuver("Arrive", geometry.last(), length, "Arrive")),
            "", source = "valhalla", profile = "driving", provider = "p", fetchedAtEpochMillis = 1, destinationPoint = geometry.last(), shapeElapsedSeconds = times.toList())
    }

    @Test fun `displayed position stays on the road and never jumps across a messy drive`() = runTest {
        val route = road(); val geometry = RouteGeometry(route.geometry)
        val session = NavigationSession(); val rng = Random(11)
        session.preview(route); session.location(LocationFix(route.geometry.first(), 100, 4.0, null, 0.0), 100); session.start(100)
        val fusion = MotionFusion()
        var truthS = 0.0; var shown = 0.0; var prevShown = -1.0
        var maxFrameJump = 0.0; var maxAlongError = 0.0; var signalLimitedFrames = 0; var frames = 0; var lastGpsAt = -1L
        val stopWindow = 150..170; val tunnel = 220..235
        for (ms in 0..300_000 step 100) {
            val sec = ms / 1000
            val speed = when { sec in stopWindow -> 0.0; sec < 5 -> 3.0 * sec; else -> 12.0 }
            truthS += speed * 0.1
            val truePoint = geometry.pointAt(truthS)
            val t = 1_000L + ms
            if (ms % 1000 == 0 && sec !in tunnel) {
                var dx = rng.nextGaussian() * 3.5; var dy = rng.nextGaussian() * 3.5; var acc = 5.0
                if (sec in 60..62) { dx += 90.0; dy -= 60.0; acc = 8.0 }          // multipath burst while claiming good accuracy
                if (sec in 100..104) { dx += 80.0; acc = 70.0 }                   // network-style fix, honestly reported as poor
                if (sec == 120) acc = 95.0                                         // accuracy spike
                val point = GeoPoint(truePoint.latitude + dy / 111_320.0, truePoint.longitude + dx / (111_320.0 * cos(Math.toRadians(truePoint.latitude))))
                val sample = GpsSample(point, acc, maxOf(0.0, speed + rng.nextGaussian() * 0.3), if (speed > 3) 0.0 else null, t)
                fusion.onGps(sample); lastGpsAt = t
                // Raw GPS drives guidance, exactly as the app does now. In the tunnel there is no fix at all and the session dead-reckons along the route.
                val update = session.location(LocationFix(point, t, acc, null, sample.speedMps), t)
                if (update.snapshot.status == SessionStatus.SignalLimited) signalLimitedFrames++
            }
            session.tick(t)
            val snap = session.state.value
            val along = route.routeToShapeMeters(snap.alongMeters)
            // The marker glides from the last fix at the tracked speed.
            val extrapolated = along + snap.speedAlongMps * ((t - snap.fixElapsedMillis) / 1000.0).coerceIn(0.0, 8.0)
            shown = if (prevShown < 0 || abs(extrapolated - shown) > 80) extrapolated else shown + (extrapolated - shown) * 0.5
            frames++
            if (prevShown >= 0) maxFrameJump = maxOf(maxFrameJump, abs(shown - prevShown))
            prevShown = shown
            if (sec > 10) maxAlongError = maxOf(maxAlongError, abs(shown - truthS))
        }
        // A car at 12 m/s moves 1.2 m per frame; the one real correction is ~7 m when GPS returns after 16 s in a tunnel.
        assertTrue("biggest single-frame jump $maxFrameJump m", maxFrameJump < 10.0)
        assertTrue("largest distance-along-route error $maxAlongError m", maxAlongError < 12.0)
        assertEquals("guidance never dropped into signal-limited", 0, signalLimitedFrames)
        assertNotEquals(SessionStatus.Idle, session.state.value.status)
    }
}
