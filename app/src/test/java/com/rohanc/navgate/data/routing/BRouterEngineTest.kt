package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.data.routing.offline.BRouterEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Runs the real engine against a downloaded routing tile. Skipped unless NAVGATE_BROUTER_TILES points at a folder holding E70_N15.rd5. */
class BRouterEngineTest {
    private val tiles = System.getenv("NAVGATE_BROUTER_TILES")?.let { File(it) }
    private val profiles = File("src/main/assets/brouter/profiles2")
    private fun engine(): BRouterEngine { assumeTrue("no routing tiles", tiles?.let { File(it, "E70_N15.rd5").isFile } == true); return BRouterEngine(tiles!!, profiles) }

    private val nariman = GeoPoint(18.9256, 72.8242); private val bandra = GeoPoint(19.0596, 72.8295)

    @Test fun `a real Mumbai drive routes offline with a sensible length and time`() = runBlocking {
        val t = engine().route(nariman, bandra, "driving")
        val length = RouteGeometry(t.points).length
        assertTrue("length $length", length in 14_000.0..30_000.0)
        assertTrue("seconds ${t.seconds}", t.seconds in 600.0..4_000.0)
        assertTrue(t.points.first().distanceTo(nariman) < 300 && t.points.last().distanceTo(bandra) < 300)
    }

    @Test fun `walking uses the walking profile and avoiding tolls still finds a route`() = runBlocking {
        val e = engine()
        val walk = e.route(GeoPoint(18.9440, 72.8230), GeoPoint(18.9600, 72.8100), "walking")
        assertTrue(RouteGeometry(walk.points).length in 1_500.0..6_000.0)
        val car = e.route(nariman, bandra, "driving", avoidTolls = true, avoidHighways = true)
        assertTrue(car.points.size > 10)
    }

    @Test fun `a point in the sea fails with a clear message`() = runBlocking {
        val e = engine()
        val failure = runCatching { e.route(GeoPoint(18.0, 70.5), bandra, "driving") }.exceptionOrNull()
        assertNotNull(failure); assertTrue(failure!!.message!!.isNotBlank())
    }
}
