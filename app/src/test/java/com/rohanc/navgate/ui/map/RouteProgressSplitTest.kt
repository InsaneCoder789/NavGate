package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import org.junit.Assert.*
import org.junit.Test

class RouteProgressSplitTest {
    private val line = RouteGeometry((0..10).map { GeoPoint(19.0 + it * 0.001, 72.8) })   // ~1.1 km north
    private fun coords(json: String) = Regex("\\[(-?[0-9.]+),(-?[0-9.]+)]").findAll(json.substringAfter("coordinates")).map { it.groupValues[2].toDouble() }.toList()

    @Test fun `the line is cut at the vehicle with the cut point on both sides`() {
        val (done, ahead) = MapRouteGeoJson.progressSplit(line, line.length / 2)
        val d = coords(done); val a = coords(ahead)
        assertEquals(19.0, d.first(), 1e-9); assertEquals(19.005, d.last(), 1e-6)
        assertEquals(19.005, a.first(), 1e-6); assertEquals(19.010, a.last(), 1e-9)
        assertEquals(d.last(), a.first(), 1e-9)
    }

    @Test fun `at the start nothing is behind and at the end nothing is ahead`() {
        val start = MapRouteGeoJson.progressSplit(line, 0.0)
        assertTrue(start.first.contains("\"features\":[]")); assertTrue(coords(start.second).size >= 2)
        val end = MapRouteGeoJson.progressSplit(line, line.length)
        assertTrue(end.second.contains("\"features\":[]")); assertTrue(coords(end.first).size >= 2)
    }

    @Test fun `no route or a one point route draws nothing and out of range progress is clamped`() {
        assertEquals(Pair("""{"type":"FeatureCollection","features":[]}""", """{"type":"FeatureCollection","features":[]}"""), MapRouteGeoJson.progressSplit(null, 5.0))
        val clamped = MapRouteGeoJson.progressSplit(line, 1e9)
        assertTrue(clamped.second.contains("\"features\":[]"))
        assertTrue(coords(MapRouteGeoJson.progressSplit(line, -50.0).second).size == 11)
    }
}
