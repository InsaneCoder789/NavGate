package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.model.asMetersLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Each test is one error seen in the 14:47 walk to KP-5 (docs/FOOTAGE_2026-10-29_KP5_WALK.md), so it cannot come back unnoticed.
 */
class FootageRegressionTest {
    private val here = GeoPoint(19.0, 72.85)
    private fun north(m: Double) = GeoPoint(here.latitude + m / 111_320.0, here.longitude)
    private fun east(p: GeoPoint, m: Double) = GeoPoint(p.latitude, p.longitude + m / (111_320.0 * cos(Math.toRadians(p.latitude))))

    private fun alignedWorld(compass: Double = 0.0): ArWorld {
        val world = ArWorld(); val reading = ArCameraReading(doubleArrayOf(0.0, 1.3, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), 1_000)
        world.aligner.update(reading, compass, here, 3.0, null, 0.0)
        assertTrue(world.aligner.aligned)
        return world
    }

    private val road = (0..40).map { north(it * 5.0) }          // 200 m north

    private fun angleBetween(ax: Double, az: Double, bx: Double, bz: Double) = Math.toDegrees(abs(atan2(ax * bz - az * bx, ax * bx + az * bz)))

    @Test fun `distance labels are never negative (the card said -1 m and -23 m)`() {
        assertEquals("0 m", (-1.4).asMetersLabel()); assertEquals("0 m", (-23.0).asMetersLabel()); assertEquals("0 m", Double.NaN.asMetersLabel())
        assertEquals("68 m", 68.2.asMetersLabel()); assertEquals("1.1 km", 1_100.0.asMetersLabel())
    }

    @Test fun `the ground is never above the phone, so the road line cannot hang in the sky`() {
        val ground = GroundEstimator()
        val first = ground.groundY(10.0, 1.4)
        assertEquals(8.6, first, 1e-9)
        // The phone's height drifts down 1.5 m (stairs, drift, a lowered arm): the ground estimate must follow it, not stay above the lens.
        for (cameraY in listOf(8.0, 7.5, 6.0, 12.0, 10.0)) {
            val g = ground.groundY(cameraY, 1.4)
            assertTrue("ground $g for the phone at $cameraY", g <= cameraY - 0.6 + 1e-9 && g >= cameraY - 2.8 - 1e-9)
        }
    }

    private fun first(m: ArMeshData) = GroundPoint(m.vertices[0].toDouble(), m.vertices[2].toDouble())
    private val facingNorth = GroundPoint(0.0, -1.0)

    @Test fun `no arrow is drawn out in front of the phone, only the line and the turn`() {
        val scene = alignedWorld().buildScene(road, 150.0, null, 0.0, GroundPoint(0.0, 0.0), facingNorth)!!
        assertTrue("without a turn there is nothing but the line", scene.arrow == null && scene.arrowOutline == null && scene.turnPoint == null)
    }

    @Test fun `the track starts at the feet, a little behind the phone, whichever way it faces`() {
        for (facing in listOf(facingNorth, GroundPoint(1.0, 0.0), GroundPoint(0.6, -0.8))) {
            val camera = GroundPoint(2.0, 5.0)
            val scene = alignedWorld().buildScene(road, 150.0, null, 0.0, camera, facing)!!
            val start = first(scene.track!!)
            val feet = feetPoint(camera, facing)
            // The track is a ribbon: its first two vertices are the left and right edge, 0.30 m either side of the centre.
            val mid = GroundPoint((scene.track!!.vertices[0] + scene.track!!.vertices[5]) / 2.0, (scene.track!!.vertices[2] + scene.track!!.vertices[7]) / 2.0)
            assertTrue("the track starts ${hypot(mid.x - feet.x, mid.z - feet.z)} m from the feet", hypot(mid.x - feet.x, mid.z - feet.z) <= 0.05)
            assertTrue("the feet are ${hypot(camera.x - feet.x, camera.z - feet.z)} m behind the phone", abs(hypot(camera.x - feet.x, camera.z - feet.z) - 0.3) < 1e-6)
            assertNotNull(start)
        }
    }

    @Test fun `with the phone looking straight down the feet are under it`() {
        val camera = GroundPoint(1.0, 1.0)
        assertEquals(camera, feetPoint(camera, GroundPoint(0.0, 0.0)))
        assertEquals(camera, feetPoint(camera, null))
    }

    @Test fun `a walker on the pavement keeps the road line on the road, and a badly placed world brings it to the feet`() {
        // The road line gives up 20% of the gap on a pavement (up to 4.5 m), everything beyond 9 m.
        assertEquals(0.2, ribbonHold(3.0), 1e-9); assertEquals(0.2, ribbonHold(4.5), 1e-9)
        assertEquals(1.0, ribbonHold(9.0), 1e-9); assertEquals(1.0, ribbonHold(20.0), 1e-9)
        assertTrue(ribbonHold(6.0) > 0.2 && ribbonHold(6.0) < 1.0)
        val world = alignedWorld()
        val pavement = world.buildScene(road, 150.0, null, 0.0, GroundPoint(-3.0, 0.0), facingNorth)!!
        assertTrue("a pavement walker's road line starts ${abs(first(pavement.ribbon).x)} m off the road", abs(first(pavement.ribbon).x + 3.0 * 0.2) < 1.0 + 1e-6 || abs(first(pavement.ribbon).x) < 3.0)
        val far = world.buildScene(road, 150.0, null, 0.0, GroundPoint(-12.0, 0.0), facingNorth)!!
        val gap = hypot(first(far.ribbon).x - (-12.0), first(far.ribbon).z - 0.0)
        assertTrue("with the world 12 m off, the road line still starts $gap m from the walker", gap <= 2.0)
    }

    @Test fun `the road line never reaches behind the walker`() {
        val camera = GroundPoint(0.0, 0.0)
        val scene = alignedWorld().buildScene(road, 150.0, null, 0.0, camera, facingNorth)!!
        for (m in listOfNotNull(scene.ribbon, scene.track)) for (i in 0 until m.vertexCount) assertTrue("a vertex lies ${m.vertices[i * 5 + 2]} m behind the walker", m.vertices[i * 5 + 2] <= 1.4f + 1.0f)
    }

    @Test fun `the road line starts near the walker and has no gaps`() {
        val world = alignedWorld(); val scene = world.buildScene(road, 150.0, "Turn left", 0.0, GroundPoint(2.0, 0.0), facingNorth)!!
        val r = scene.ribbon; val v = r.vertices
        assertTrue("the line starts ${hypot(v[0] - 2.0, v[2].toDouble())} m from the phone", hypot(v[0] - 2.0, v[2].toDouble()) <= 2.5)
        // Quad i ends where quad i+1 starts: no seam.
        val segs = r.vertexCount / 6
        for (i in 0 until segs - 1) {
            val endLeft = (i * 6 + 2) * 5; val nextLeft = ((i + 1) * 6) * 5
            assertTrue("gap at segment $i", hypot((v[endLeft] - v[nextLeft]).toDouble(), (v[endLeft + 2] - v[nextLeft + 2]).toDouble()) < 1e-3)
        }
    }

    @Test fun `the line never lies across the way (a cross-line) however the phone is turned`() {
        for (compass in listOf(0.0, 45.0, 90.0, -90.0, 170.0)) {
            val world = alignedWorld(compass); val scene = world.buildScene(road, 150.0, null, 0.0, GroundPoint(0.0, 0.0))!!
            val v = scene.ribbon.vertices; val n = scene.ribbon.vertexCount
            // The line's overall direction (first to last vertex) against the road's (first to last point), both in the world's ground frame.
            val a = world.aligner.toGround(road.first())!!; val b = world.aligner.toGround(road.last())!!
            val off = angleBetween(v[(n - 1) * 5].toDouble() - v[0], v[(n - 1) * 5 + 2].toDouble() - v[2], b.x - a.x, b.z - a.z)
            assertTrue("compass $compass: the line runs $off degrees off the road", off <= 5.0)
        }
    }

    private fun walk(world: ArWorld, dx: Double, dz: Double, camera: DoubleArray, seconds: Double, startMs: Long = 2_000L) {
        val geometry = com.rohanc.navgate.core.RouteGeometry(road); var ms = startMs; var x = 0.0; var z = 0.0; var t = 0.0
        while (t < seconds) {
            val reading = ArCameraReading(doubleArrayOf(x, 1.4, z), camera, doubleArrayOf(-camera[2], 0.0, camera[0]), doubleArrayOf(0.0, 1.0, 0.0), ms)
            world.align(reading, 0.0, 0.0, geometry, 20.0, 1.4, here, 4.0, 0.0); x += dx * 1.4 / 30; z += dz * 1.4 / 30; t += 1.0 / 30; ms += 33
        }
    }

    @Test fun `the map follows the way the walker goes even with the camera facing the other way`() {
        // The camera faces north (-z) but the walker is going south (+z): the map must turn to the walking direction, not the camera's.
        val world = alignedWorld(); walk(world, 0.0, 1.0, doubleArrayOf(0.0, 0.0, -1.0), 3.0)
        val camera = world.cameraHeadingDegrees(0.0, -1.0)!!; val travel = world.travelHeadingDegrees()!!
        assertTrue("travel $travel against camera $camera", angleBetweenDeg(travel, camera) in 170.0..180.0)
    }

    @Test fun `a standing walker has no travel heading, so the map follows the camera`() {
        val world = alignedWorld(); walk(world, 0.0, 0.0, doubleArrayOf(0.0, 0.0, -1.0), 3.0)
        assertTrue(world.travelHeadingDegrees() == null)
    }

    private fun angleBetweenDeg(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0).let { 180.0 - it + 0.0 }.let { 180.0 - it }
}
