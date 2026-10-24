package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** Builds the small slice of the Mapbox Vector Tile format that [MvtDecoder] reads. */
internal object MvtBuilder {
    private fun ByteArrayOutputStream.varint(v: Long) = Pmtiles.varint(this, v)
    private fun ByteArrayOutputStream.tag(field: Int, wire: Int) = varint((field shl 3 or wire).toLong())
    private fun ByteArrayOutputStream.bytes(field: Int, data: ByteArray) { tag(field, 2); varint(data.size.toLong()); write(data) }
    private fun zig(v: Int) = ((v shl 1) xor (v shr 31)).toLong() and 0xffffffffL

    /** [lines]: each is a list of tile-pixel points (0..4096) with its tags. */
    fun tile(layer: String, lines: List<Pair<List<Pair<Int, Int>>, Map<String, String>>>): ByteArray {
        val keys = lines.flatMap { it.second.keys }.distinct(); val values = lines.flatMap { it.second.values }.distinct()
        val l = ByteArrayOutputStream()
        l.bytes(1, layer.toByteArray())
        for ((pts, tags) in lines) {
            val f = ByteArrayOutputStream()
            val t = ByteArrayOutputStream(); tags.forEach { (k, v) -> t.varint(keys.indexOf(k).toLong()); t.varint(values.indexOf(v).toLong()) }
            f.bytes(2, t.toByteArray())
            f.tag(3, 0); f.varint(2)
            val g = ByteArrayOutputStream(); var cx = 0; var cy = 0
            pts.forEachIndexed { i, (x, y) ->
                if (i == 0) g.varint(1L or (1L shl 3)) else if (i == 1) g.varint(2L or ((pts.size - 1).toLong() shl 3))
                g.varint(zig(x - cx)); g.varint(zig(y - cy)); cx = x; cy = y
            }
            f.bytes(4, g.toByteArray()); l.bytes(2, f.toByteArray())
        }
        keys.forEach { l.bytes(3, it.toByteArray()) }
        values.forEach { v -> val vb = ByteArrayOutputStream(); vb.bytes(1, v.toByteArray()); l.bytes(4, vb.toByteArray()) }
        l.tag(5, 0); l.varint(4096)
        return ByteArrayOutputStream().also { it.bytes(3, l.toByteArray()) }.toByteArray()
    }
}

class StreetNameIndexTest {
    @get:Rule val tmp = TemporaryFolder()
    private val z = 14; private val tx = 11_508; private val ty = 7_181
    private fun lat(py: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * (ty + py) / (1 shl z))))) 
    private fun lon(px: Double) = (tx + px) / (1 shl z) * 360.0 - 180.0

    private fun archive(vararg lines: Pair<List<Pair<Int, Int>>, Map<String, String>>): Pmtiles.Reader {
        val target = tmp.newFile("names.pmtiles")
        Pmtiles.Writer(tmp.newFile("scratch")).use { w ->
            w.add(z, tx, ty, Pmtiles.gzip(MvtBuilder.tile("transportation_name", lines.toList())))
            w.finish(target, "{}", lon(0.0), lat(1.0), lon(1.0), lat(0.0))
        }
        return Pmtiles.Reader(target)
    }

    @Test fun `the decoder reads a named line and places it on the map`() {
        val bytes = MvtBuilder.tile("transportation_name", listOf(listOf(0 to 2048, 4096 to 2048) to mapOf("name" to "Marine Drive", "class" to "primary")))
        val lines = MvtDecoder.lines(bytes, "transportation_name", z, tx, ty)
        assertEquals(1, lines.size); assertEquals("Marine Drive", lines[0].tags["name"])
        assertEquals(lat(0.5), lines[0].points[0].latitude, 1e-6); assertEquals(lon(0.0), lines[0].points[0].longitude, 1e-6); assertEquals(lon(1.0), lines[0].points[1].longitude, 1e-6)
        assertTrue(MvtDecoder.lines(bytes, "water", z, tx, ty).isEmpty())
        assertTrue(MvtDecoder.lines(byteArrayOf(1, 2, 3), "transportation_name", z, tx, ty).isEmpty())
    }

    @Test fun `route points along a named road get its name and a crossing road does not`() {
        val index = StreetNameIndex(archive(listOf(0 to 2048, 4096 to 2048) to mapOf("name" to "Marine Drive"), listOf(2048 to 0, 2048 to 4096) to mapOf("name" to "Link Road", "name_en" to "Link Rd")))
        val east = (1..9).map { GeoPoint(lat(0.5), lon(it / 10.0)) }
        val names = index.namesAlong(east)
        assertTrue(names.count { it == "Marine Drive" } >= 7)
        val north = (1..9).map { GeoPoint(lat(1 - it / 10.0), lon(0.25)) }   // runs north-south along x = 0.25, far from the crossing road
        assertTrue(index.namesAlong(north).all { it == null })
    }

    @Test fun `english names win and unnamed roads fall back to their number`() {
        assertEquals("Link Rd", StreetNameIndex.nameOf(mapOf("name" to "लिंक रोड", "name_en" to "Link Rd")))
        assertEquals("NH48", StreetNameIndex.nameOf(mapOf("ref" to "NH48")))
        assertNull(StreetNameIndex.nameOf(mapOf("class" to "primary")))
    }

    @Test fun `a short gap between two stretches of one road is filled`() {
        val index = StreetNameIndex(archive(listOf(0 to 2048, 1800 to 2048) to mapOf("name" to "Marine Drive"), listOf(2000 to 2048, 4096 to 2048) to mapOf("name" to "Marine Drive")))
        val names = index.namesAlong((1..9).map { GeoPoint(lat(0.5), lon(it / 10.0)) })
        assertTrue(names.all { it == "Marine Drive" })
    }

    @Test fun `a route outside the saved tiles gets no names`() {
        val index = StreetNameIndex(archive(listOf(0 to 2048, 4096 to 2048) to mapOf("name" to "Marine Drive")))
        assertTrue(index.namesAlong(listOf(GeoPoint(10.0, 20.0), GeoPoint(10.001, 20.001))).all { it == null })
    }
}
