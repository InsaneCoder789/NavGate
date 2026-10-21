package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.core.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

class ArMeshTest {
    private val straight = (0..10).map { GroundPoint(0.0, -it * 2.0) }   // 20 m straight ahead (-z is forward)

    @Test fun `ribbon has two triangles per segment and runs the full width and length`() {
        val mesh = ArMesh.ribbon(straight, -1.2, 0.8)
        assertEquals(10 * 6, mesh.vertexCount)
        val xs = (0 until mesh.vertexCount).map { mesh.vertices[it * 5] }
        assertEquals(-0.8f, xs.min(), 1e-4f); assertEquals(0.8f, xs.max(), 1e-4f)
        val vs = (0 until mesh.vertexCount).map { mesh.vertices[it * 5 + 4] }
        assertEquals(0f, vs.min(), 1e-4f); assertEquals(20f, vs.max(), 1e-3f)       // v is metres along the road
        assertTrue((0 until mesh.vertexCount).all { abs(mesh.vertices[it * 5 + 1] + 1.2f) < 1e-4f })   // flat on the ground
    }

    @Test fun `ribbon edges stay joined round a corner`() {
        val corner = listOf(GroundPoint(0.0, 0.0), GroundPoint(0.0, -10.0), GroundPoint(10.0, -10.0))
        val mesh = ArMesh.ribbon(corner, 0.0, 0.8)
        assertEquals(12, mesh.vertexCount)
        // The shared vertex pair at the corner is identical in both segments: no gap or overlap.
        fun v(i: Int) = Pair(mesh.vertices[i * 5], mesh.vertices[i * 5 + 2])
        assertEquals(v(5), v(6)); assertEquals(v(4), v(7))
    }

    @Test fun `turn arrow bends at the turn and points down the new road`() {
        val turn = GroundPoint(0.0, -20.0)
        val arrow = ArMesh.turnArrow(turn, GroundPoint(0.0, -1.0), GroundPoint(1.0, 0.0), -1.0)      // going forward, then turning to +x
        assertTrue(arrow.vertexCount > 0 && arrow.vertexCount % 3 == 0)
        val maxX = (0 until arrow.vertexCount).maxOf { arrow.vertices[it * 5] }
        assertTrue("tip reaches about 5.6 m out: $maxX", maxX in 5.4f..5.8f)
        val tailZ = (0 until arrow.vertexCount).maxOf { arrow.vertices[it * 5 + 2] }
        assertTrue("tail starts 7 m before the turn: $tailZ", tailZ in -13.2f..-12.8f)
    }

    @Test fun `quaternion extrapolation continues a steady turn`() {
        fun about(angleDeg: Double): DoubleArray { val h = Math.toRadians(angleDeg) / 2; return doubleArrayOf(0.0, Math.sin(h), 0.0, Math.cos(h)) }
        val q = ArMath.extrapolate(about(10.0), about(20.0), 2.0)       // 10 degrees per step, two steps on from the first
        val expected = about(30.0)
        assertEquals(expected[1], q[1], 1e-6); assertEquals(expected[3], q[3], 1e-6)
        val same = ArMath.extrapolate(about(10.0), about(20.0), 1.0)
        assertEquals(about(20.0)[1], same[1], 1e-6)
    }

    @Test fun `aligner puts a point north of the camera straight ahead once the compass says the camera faces north`() {
        val aligner = ArGeoAligner()
        val here = GeoPoint(19.0, 72.85)
        // ARCore camera at the origin looking along -z; the compass says that is north.
        val reading = ArCameraReading(doubleArrayOf(0.0, 1.3, 0.0), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), 1_000)
        aligner.update(reading, 0.0, here, 3.0, null, 0.0)
        assertTrue(aligner.aligned)
        val tenNorth = GeoPoint(here.latitude + 10.0 / 111_320.0, here.longitude)
        val tenEast = GeoPoint(here.latitude, here.longitude + 10.0 / (111_320.0 * cos(Math.toRadians(here.latitude))))
        val n = aligner.toGround(tenNorth)!!; val e = aligner.toGround(tenEast)!!
        assertEquals(0.0, n.x, 0.05); assertEquals(-10.0, n.z, 0.05)        // north is forward (-z)
        assertEquals(10.0, e.x, 0.05); assertEquals(0.0, e.z, 0.05)          // east is to the right (+x)
    }

    @Test fun `the road's own heading realigns the world while moving`() {
        val aligner = ArGeoAligner()
        val start = GeoPoint(19.0, 72.85)
        // The compass wrongly says the camera faces east; the road runs north and ARCore sees the phone move forward (-z).
        for (i in 0..8) {
            val moved = GeoPoint(start.latitude + 3.0 * i / 111_320.0, start.longitude)
            val reading = ArCameraReading(doubleArrayOf(0.0, 1.3, -3.0 * i), doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), 1_000L + i * 1_000L)
            aligner.update(reading, 90.0, moved, 3.0, 0.0, 3.0)
        }
        val yaw = aligner.yawDegrees!!
        assertTrue("yaw $yaw pulled from 90 toward 0 by the road", yaw < 75.0)
    }
}
