package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class TurnExtractorTest {
    private val o = GeoPoint(19.0, 72.8)
    private fun at(north: Double, east: Double) = GeoPoint(o.latitude + north / 111_320.0, o.longitude + east / (111_320.0 * cos(Math.toRadians(o.latitude))))
    private fun line(vararg p: Pair<Double, Double>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        p.toList().zipWithNext().forEach { (a, b) -> val n = maxOf(1, (Math.hypot(b.first - a.first, b.second - a.second) / 5).toInt()); for (i in 0 until n) out += at(a.first + (b.first - a.first) * i / n, a.second + (b.second - a.second) * i / n) }
        out += at(p.last().first, p.last().second); return out
    }

    @Test fun `a straight road has only start and arrival`() {
        val m = TurnExtractor.extract(line(0.0 to 0.0, 500.0 to 0.0), "driving")
        assertEquals(listOf("Start", "Arrive"), m.map { it.kind })
        assertEquals("Head north", m.first().instruction)
    }

    @Test fun `a right angle to the east is one right turn at the corner`() {
        val m = TurnExtractor.extract(line(0.0 to 0.0, 200.0 to 0.0, 200.0 to 300.0), "driving", destinationLabel = "Dock")
        assertEquals(listOf("Start", "Right", "Arrive"), m.map { it.kind })
        assertEquals(200.0, m[1].alongMeters, 8.0)
        assertTrue(m[1].bearingChange!! in 80.0..100.0)
        assertEquals("You have arrived at Dock.", m.last().instruction)
    }

    @Test fun `a turn to the west is a left turn`() {
        val m = TurnExtractor.extract(line(0.0 to 0.0, 200.0 to 0.0, 200.0 to -300.0), "walking")
        assertEquals("Left", m[1].kind)
    }

    @Test fun `a bend spread over several vertices is a single turn`() {
        val curve = line(0.0 to 0.0, 150.0 to 0.0, 170.0 to 8.0, 182.0 to 22.0, 190.0 to 40.0, 192.0 to 200.0)
        val m = TurnExtractor.extract(curve, "driving")
        assertEquals(1, m.count { it.kind == "Right" || it.kind == "SlightRight" })
        assertEquals(3, m.size)
    }

    @Test fun `street names are used when known`() {
        val shape = line(0.0 to 0.0, 200.0 to 0.0, 200.0 to 300.0)
        val names = shape.map { p -> if (p.latitude > o.latitude + 190.0 / 111_320.0 && p.longitude > o.longitude + 0.00001) "Marine Drive" else "Link Road" }
        val m = TurnExtractor.extract(shape, "driving", names)
        assertEquals("Head north on Link Road", m.first().instruction)
        assertEquals("Turn right onto Marine Drive", m[1].instruction)
    }

    @Test fun `maneuvers stay in order and inside the route`() {
        val m = TurnExtractor.extract(line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0, 0.0 to 200.0), "walking")
        assertTrue(m.zipWithNext().all { (a, b) -> a.alongMeters <= b.alongMeters })
        assertEquals(RouteGeometry(line(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0, 0.0 to 200.0)).length, m.last().alongMeters, 0.01)
        assertEquals(listOf("Start", "Right", "Right", "Left", "Arrive"), m.map { it.kind })
    }
}
