package com.rohanc.navgate.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class RouteChoicesTest {
    private val origin = GeoPoint(19.0, 72.85)
    private fun at(e: Double, n: Double) = GeoPoint(origin.latitude + n / 111_320.0, origin.longitude + e / (111_320.0 * cos(Math.toRadians(origin.latitude))))
    private fun road(id: String, seconds: Double, vararg pts: GeoPoint): CampusRoute {
        val g = pts.toList(); val len = g.lengthMeters()
        val times = DoubleArray(g.size).also { for (i in 1 until g.size) it[i] = seconds * (g.take(i + 1).lengthMeters() / len) }
        return CampusRoute(id, 1, "x", "v", "d", "", g, emptyList(), len, seconds, listOf(Maneuver("go", g.first(), 0.0, "Start"), Maneuver("Arrive", g.last(), len, "Arrive")), "",
            source = "valhalla", profile = "driving", provider = "p", fetchedAtEpochMillis = 1, destinationPoint = g.last(), shapeElapsedSeconds = times.toList())
    }
    private val direct = road("direct", 500.0, at(0.0, 0.0), at(0.0, 3000.0))
    private val highway = road("highway", 380.0, at(0.0, 0.0), at(2500.0, 0.0), at(2500.0, 3000.0), at(0.0, 3000.0))   // longer but faster
    private val sameAsDirect = road("copy", 505.0, at(1.0, 0.0), at(1.0, 3000.0))

    @Test fun `identical roads are recognised and different ones are not`() {
        assertTrue(RouteChoices.sameRoute(direct, sameAsDirect))
        assertFalse(RouteChoices.sameRoute(direct, highway))
    }

    @Test fun `fastest and shortest are labelled separately`() {
        val labelled = RouteChoices.labelled(listOf(direct, highway)).associate { it.id to it.label }
        assertEquals("Shortest", labelled["direct"])
        assertEquals("Fastest", labelled["highway"])
    }

    @Test fun `when one route is both fastest and shortest it is fastest and the rest are alternatives`() {
        val slowLong = road("slow", 900.0, at(0.0, 0.0), at(-2500.0, 0.0), at(-2500.0, 3000.0), at(0.0, 3000.0))
        val labels = RouteChoices.labelled(listOf(direct.copy(estimatedSeconds = 300.0), slowLong)).associate { it.id to it.label }
        assertEquals("Fastest", labels["direct"]); assertEquals("Alternative", labels["slow"])
    }
}
