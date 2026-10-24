package com.rohanc.navgate.data.routing

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.maps.Pmtiles
import com.rohanc.navgate.data.maps.StreetNameIndex
import com.rohanc.navgate.data.routing.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** BRouter geometry plus road names from a real saved map pack. Needs NAVGATE_BROUTER_TILES and NAVGATE_LIVE_PACK_DIR (with mumbai-test.pmtiles). */
class OfflineStreetNamesTest {
    @Test fun `a real Bandra to Andheri drive is described with street names from the map pack`() = runBlocking {
        val tiles = System.getenv("NAVGATE_BROUTER_TILES")?.let(::File); val pack = System.getenv("NAVGATE_LIVE_PACK_DIR")?.let { File(it, "mumbai-test.pmtiles") }
        assumeTrue("needs routing tiles and a pack", tiles?.let { File(it, "E70_N15.rd5").isFile } == true && pack?.isFile == true)
        val track = BRouterEngine(tiles!!, File("src/main/assets/brouter/profiles2")).route(GeoPoint(19.0596, 72.8295), GeoPoint(19.1136, 72.8697), "driving")
        val names = StreetNameIndex(Pmtiles.Reader(pack!!)).namesAlong(track.points)
        val covered = names.count { it != null }.toDouble() / names.size
        println("NAMES covered ${(covered * 100).toInt()}% distinct=${names.filterNotNull().distinct().take(8)}")
        assertTrue("road names found for most of the route ($covered)", covered > 0.6)
        val request = PlanRequest(1, "mumbai", track.points.first(), "d", profile = "driving", destinationPoint = track.points.last(), destinationTitle = "Andheri")
        val route = OfflineRouteBuilder.build(request, OfflineTrack(track.points, track.seconds, names), 1L)
        route.maneuvers.forEach { println("NAMES ${it.alongMeters.toInt()} m: ${it.instruction}") }
        assertTrue("some turns name their road", route.maneuvers.count { it.instruction.contains(" onto ") } >= 2)
        assertTrue(route.maneuvers.first().instruction.startsWith("Head "))
    }
}
