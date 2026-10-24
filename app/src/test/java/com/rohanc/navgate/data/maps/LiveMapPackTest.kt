package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Builds a real pack from the live tile server. Skipped unless NAVGATE_LIVE_PACK_DIR is set (it makes a few hundred requests). */
class LiveMapPackTest {
    @Test fun `a real Mumbai pack builds and reads back`() {
        val dir = System.getenv("NAVGATE_LIVE_PACK_DIR")?.let { File(it) }
        assumeTrue("live test not enabled", dir != null)
        dir!!.mkdirs()
        val out = File(dir, "mumbai-test.pmtiles"); var last = MapPackBuilder.Progress(0, 0)
        val started = System.currentTimeMillis()
        runBlocking { MapPackBuilder().build(MapArea.around("Mumbai test", GeoPoint(19.0760, 72.8777), 8.0), out, File(dir, "tmp")) { last = it } }
        println("LIVE pack ${out.length() / 1024} KiB, ${last.total} tiles, ${(System.currentTimeMillis() - started) / 1000} s")
        val r = Pmtiles.Reader(out)
        assertTrue(r.header.addressedTiles > 100)
        val (z, x, y) = MapPackBuilder().tilesFor(MapArea.around("Mumbai test", GeoPoint(19.0760, 72.8777), 8.0)).last { it.first == 14 }
        val tile = r.tile(z, x, y)
        assertNotNull("tile $z/$x/$y", tile); assertTrue(Pmtiles.isGzip(tile!!))
    }
}
