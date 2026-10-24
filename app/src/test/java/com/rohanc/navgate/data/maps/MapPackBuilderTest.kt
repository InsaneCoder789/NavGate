package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MapPackBuilderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    @After fun stop() { server.shutdown() }

    private fun tileBody(path: String) = "tile:$path".repeat(5).toByteArray()
    private fun serve(missing: (String) -> Boolean = { false }, broken: (String) -> Boolean = { false }, plain: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                if (path == "/planet") return MockResponse().setBody("""{"tiles":["http://${server.hostName}:${server.port}/t/{z}/{x}/{y}.pbf"],"version":"9","attribution":"OSM","vector_layers":[{"id":"water"}]}""")
                val key = path.removePrefix("/t/").removeSuffix(".pbf")
                if (missing(key)) return MockResponse().setResponseCode(404)
                if (broken(key)) return MockResponse().setResponseCode(500)
                val raw = tileBody(key)
                return if (plain) MockResponse().setBody(Buffer().write(raw)) else MockResponse().setBody(Buffer().write(Pmtiles.gzip(raw))).setHeader("Content-Encoding", "gzip")
            }
        }
        server.start()
    }
    private fun builder() = MapPackBuilder(tileJsonUrl = server.url("/planet").toString())
    private val area = MapArea.around("Test", GeoPoint(19.07, 72.88), 3.0)
    private fun build(b: MapPackBuilder = builder(), a: MapArea = area): File { val out = File(tmp.root, "out.pmtiles"); runBlocking { b.build(a, out, File(tmp.root, "scratch")) {} }; return out }

    @Test fun `every tile of the area is fetched and stored once`() {
        serve()
        val b = builder(); val out = build(b)
        val r = Pmtiles.Reader(out)
        assertEquals(0, r.header.minZoom); assertEquals(14, r.header.maxZoom)
        val tiles = b.tilesFor(area)
        assertEquals(tiles.size.toLong(), r.header.addressedTiles)
        for ((z, x, y) in tiles.shuffled().take(80)) {
            val stored = r.tile(z, x, y)!!
            assertTrue(Pmtiles.isGzip(stored)); assertArrayEquals(tileBody("$z/$x/$y"), Pmtiles.gunzip(stored))
        }
        val meta = Json.parseToJsonElement(r.metadata()).jsonObject
        assertEquals("OSM", meta["attribution"]!!.jsonPrimitive.content); assertEquals(1, meta["vector_layers"]!!.jsonArray.size)
    }

    @Test fun `a server that sends plain bytes still produces a gzip archive`() {
        serve(plain = true)
        val r = Pmtiles.Reader(build())
        val (z, x, y) = builder().tilesFor(area)[3]
        assertArrayEquals(tileBody("$z/$x/$y"), Pmtiles.gunzip(r.tile(z, x, y)!!))
    }

    @Test fun `empty ocean tiles that answer 404 are skipped`() {
        serve(missing = { it.startsWith("14/") })
        val r = Pmtiles.Reader(build())
        assertEquals(13, r.header.maxZoom)
    }

    @Test fun `a server that keeps failing stops the download with a clear message`() {
        serve(broken = { it.startsWith("1") })
        val failure = runCatching { build() }.exceptionOrNull()
        assertTrue(failure is IOException); assertTrue(failure!!.message!!.contains("stopped answering"))
    }

    @Test fun `an area that is too large is refused before any download`() {
        serve()
        val huge = MapArea("World", -10.0, -10.0, 10.0, 10.0)
        val failure = runCatching { build(a = huge) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("too large")); assertEquals(0, server.requestCount)
    }

    @Test fun `tile counts match the web mercator grid`() {
        assertEquals(1, builder().tilesFor(MapArea("p", 72.8, 19.0, 72.81, 19.01), maxZoom = 0).size)
        val z1 = builder().tilesFor(MapArea("w", -179.9, -80.0, 179.9, 80.0), maxZoom = 1).count { it.first == 1 }
        assertEquals(4, z1)
    }
}
