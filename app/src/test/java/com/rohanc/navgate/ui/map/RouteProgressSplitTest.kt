package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.offset
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

class RouteWindowTest {
    private val origin = com.rohanc.navgate.core.GeoPoint(19.0, 72.8)
    // A 20 km route with a point every 10 m: what a long drive looks like.
    private val long = com.rohanc.navgate.core.RouteGeometry((0..2000).map { origin.offset(it * 10.0, 0.0) })
    private fun count(json: String) = Regex("\\[[-0-9.]+,[-0-9.]+\\]").findAll(json).count()

    @Test fun `only the stretch around the vehicle is built, however long the trip`() {
        val (done, ahead) = MapRouteGeoJson.progressSplit(long, 10_000.0, 120.0, 700.0)
        assertTrue("behind: ${count(done)} points", count(done) in 10..16)
        assertTrue("ahead: ${count(ahead)} points", count(ahead) in 68..74)
        val full = MapRouteGeoJson.progressSplit(long, 10_000.0)
        // The road to go is drawn to its end, with the stretches beyond 1.5 km thinned to a point every 20 m.
        assertTrue("ahead of the vehicle: ${count(full.second)} points", count(full.second) in 300..600)
    }

    @Test fun `the window joins the vehicle without a gap and stops at the end of the route`() {
        val (done, ahead) = MapRouteGeoJson.progressSplit(long, 10_000.0, 120.0, 700.0)
        fun pts(j: String) = Regex("\\[([-0-9.]+),([-0-9.]+)\\]").findAll(j).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertEquals(pts(done).last(), pts(ahead).first())
        val near = MapRouteGeoJson.progressSplit(long, 19_900.0, 120.0, 700.0).second
        assertEquals(long.points.last().latitude, pts(near).last().second, 1e-6)
    }

    @Test fun `the line ahead sits in the lane and returns to the centre of the road`() {
        val (_, ahead) = MapRouteGeoJson.progressSplit(long, 10_000.0, 120.0, 700.0, laneOffsetMeters = 1.6)
        val p = Regex("\\[([-0-9.]+),([-0-9.]+)\\]").findAll(ahead).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        fun metresRight(q: Pair<Double, Double>) = (q.first - origin.longitude) * 111_320.0 * Math.cos(Math.toRadians(origin.latitude))   // the road runs north, so right of travel is east
        assertEquals(1.6, metresRight(p[1]), 0.1)
        assertEquals(0.0, metresRight(p.last()), 0.1)
    }
}
