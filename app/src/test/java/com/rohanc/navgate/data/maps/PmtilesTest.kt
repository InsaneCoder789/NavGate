package com.rohanc.navgate.data.maps

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

class PmtilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `tile ids follow the spec's Hilbert order`() {
        assertEquals(0L, Pmtiles.tileId(0, 0, 0))
        assertEquals(1L, Pmtiles.tileId(1, 0, 0)); assertEquals(2L, Pmtiles.tileId(1, 0, 1))
        assertEquals(3L, Pmtiles.tileId(1, 1, 1)); assertEquals(4L, Pmtiles.tileId(1, 1, 0))
        assertEquals(5L, Pmtiles.tileId(2, 0, 0))
        val ids = (0 until 8).flatMap { x -> (0 until 8).map { y -> Pmtiles.tileId(3, x, y) } }
        assertEquals(64, ids.toSet().size); assertEquals(21L, ids.min()); assertEquals(84L, ids.max())
    }

    @Test fun `neighbouring tiles are close on the curve`() {
        val a = Pmtiles.tileId(12, 2000, 1500); val b = Pmtiles.tileId(12, 2001, 1500)
        assertTrue(Math.abs(a - b) < 100_000)
    }

    @Test fun `a directory survives a write and read`() {
        val entries = listOf(Pmtiles.Entry(1, 0, 100, 1), Pmtiles.Entry(4, 100, 50, 3), Pmtiles.Entry(9, 500, 20, 1), Pmtiles.Entry(10, 520, 7, 1))
        assertEquals(entries, Pmtiles.parseDirectory(Pmtiles.serializeDirectory(entries), Pmtiles.COMPRESSION_GZIP))
    }

    private fun tileBytes(z: Int, x: Int, y: Int) = Pmtiles.gzip("tile-$z-$x-$y".repeat(3).toByteArray())

    @Test fun `a small archive round-trips every tile and its header`() {
        val target = tmp.newFile("small.pmtiles")
        Pmtiles.Writer(tmp.newFile("scratch1")).use { w ->
            for (z in 0..4) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) w.add(z, x, y, tileBytes(z, x, y))
            w.finish(target, """{"name":"test","vector_layers":[]}""", 72.0, 18.0, 73.5, 19.5)
        }
        val r = Pmtiles.Reader(target)
        assertEquals(0, r.header.minZoom); assertEquals(4, r.header.maxZoom); assertEquals(1, r.header.tileType)
        assertEquals(72.0, r.header.minLon, 1e-6); assertEquals(19.5, r.header.maxLat, 1e-6)
        assertTrue(r.metadata().contains("\"name\":\"test\""))
        for (z in 0..4) for (x in 0 until (1 shl z)) for (y in 0 until (1 shl z)) assertArrayEquals("$z/$x/$y", tileBytes(z, x, y), r.tile(z, x, y))
        assertNull(r.tile(5, 0, 0))
    }

    @Test fun `thousands of tiles use leaf directories and still read back`() {
        val rng = Random(11); val target = tmp.newFile("big.pmtiles"); val expected = HashMap<Triple<Int, Int, Int>, ByteArray>()
        Pmtiles.Writer(tmp.newFile("scratch2")).use { w ->
            val list = ArrayList<Triple<Int, Int, Int>>()
            for (x in 0 until 150) for (y in 0 until 150) list += Triple(14, 9000 + x, 6000 + y)
            for (z in 0..13) for (x in 0 until minOf(1 shl z, 40)) for (y in 0 until minOf(1 shl z, 40)) list += Triple(z, x, y)
            list.shuffle(rng)
            for ((z, x, y) in list) { val b = ByteArray(5 + rng.nextInt(60)).also { rng.nextBytes(it) }; expected[Triple(z, x, y)] = b; w.add(z, x, y, b) }
            w.finish(target, "{}", 72.0, 18.0, 73.0, 19.0)
        }
        val r = Pmtiles.Reader(target)
        assertTrue("leaf directories used", r.header.leafLength > 0)
        assertTrue("root fits the first 16 KiB", r.header.rootOffset + r.header.rootLength <= 16_384)
        expected.entries.shuffled(rng).take(600).forEach { (k, v) -> assertArrayEquals(k.toString(), v, r.tile(k.first, k.second, k.third)) }
        assertNull(r.tile(14, 8000, 6000))
    }

    @Test fun `identical neighbouring tiles are stored once`() {
        val target = tmp.newFile("runs.pmtiles"); val sea = Pmtiles.gzip(ByteArray(300))
        Pmtiles.Writer(tmp.newFile("scratch3")).use { w ->
            for (x in 0 until 16) w.add(4, x, 0, sea)
            w.finish(target, "{}", 0.0, 0.0, 1.0, 1.0)
        }
        val r = Pmtiles.Reader(target)
        assertTrue("stored ${r.header.dataLength} bytes", r.header.dataLength < sea.size * 16)
        assertEquals(16L, r.header.addressedTiles)
        for (x in 0 until 16) assertArrayEquals(sea, r.tile(4, x, 0))
    }

    @Test fun `files that are not version 3 archives are refused`() {
        val f = tmp.newFile("bad.pmtiles"); f.writeBytes(ByteArray(200))
        assertThrows(java.io.IOException::class.java) { Pmtiles.Reader(f) }
        val g = tmp.newFile("short.pmtiles"); g.writeBytes("PMTiles".toByteArray())
        assertThrows(Exception::class.java) { Pmtiles.Reader(g) }
    }
}
