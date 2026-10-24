package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp

/** One line feature from a vector tile, in longitude/latitude, with its text attributes. */
class MvtLine(val points: List<GeoPoint>, val tags: Map<String, String>)

/**
 * Just enough of the Mapbox Vector Tile format (a protobuf) to read named roads: layers, features, tag tables and line geometry.
 * Everything else in the tile is skipped. Malformed input yields fewer features, never an exception.
 */
object MvtDecoder {
    private class Buf(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long { var r = 0L; var s = 0; while (pos < end && s < 64) { val x = b[pos++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 }; throw IllegalStateException("varint") }
        fun bytes(): Buf { val n = varint().toInt(); require(n >= 0 && pos + n <= end); return Buf(b, pos, pos + n).also { pos += n } }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> pos += 8; 2 -> { val n = varint().toInt(); require(n >= 0); pos += n }; 5 -> pos += 4; else -> throw IllegalStateException("wire") } }
    }

    /** Line features of the layer called [layerName] in an uncompressed tile at [z]/[x]/[y]. */
    fun lines(tile: ByteArray, layerName: String, z: Int, x: Int, y: Int): List<MvtLine> = try {
        val out = ArrayList<MvtLine>(); val root = Buf(tile)
        while (root.more()) {
            val key = root.varint(); val field = (key shr 3).toInt(); val wire = (key and 7).toInt()
            if (field == 3 && wire == 2) layer(root.bytes(), layerName, z, x, y, out) else root.skip(wire)
        }
        out
    } catch (_: Exception) { emptyList() }

    private fun layer(l: Buf, wanted: String, z: Int, x: Int, y: Int, out: MutableList<MvtLine>) {
        var name = ""; var extent = 4096; val keys = ArrayList<String>(); val values = ArrayList<String>(); val features = ArrayList<Buf>()
        while (l.more()) {
            val key = l.varint(); val field = (key shr 3).toInt(); val wire = (key and 7).toInt()
            when {
                field == 1 && wire == 2 -> name = String(l.bytes().let { it.b.copyOfRange(it.pos, it.end) }, Charsets.UTF_8)
                field == 2 && wire == 2 -> features += l.bytes()
                field == 3 && wire == 2 -> keys += String(l.bytes().let { it.b.copyOfRange(it.pos, it.end) }, Charsets.UTF_8)
                field == 4 && wire == 2 -> values += value(l.bytes())
                field == 5 && wire == 0 -> extent = l.varint().toInt()
                else -> l.skip(wire)
            }
        }
        if (name != wanted) return
        for (f in features) feature(f, keys, values, extent, z, x, y)?.let { out += it }
    }

    private fun value(v: Buf): String {
        while (v.more()) {
            val key = v.varint(); val field = (key shr 3).toInt(); val wire = (key and 7).toInt()
            when {
                field == 1 && wire == 2 -> return String(v.bytes().let { it.b.copyOfRange(it.pos, it.end) }, Charsets.UTF_8)
                field in 2..7 && wire == 0 -> return v.varint().toString()
                field == 3 && wire == 1 -> { val bits = java.nio.ByteBuffer.wrap(v.b, v.pos, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long; v.pos += 8; return java.lang.Double.longBitsToDouble(bits).toString() }
                field == 2 && wire == 5 -> { v.pos += 4; return "" }
                else -> v.skip(wire)
            }
        }
        return ""
    }

    private fun feature(f: Buf, keys: List<String>, values: List<String>, extent: Int, z: Int, x: Int, y: Int): MvtLine? {
        var type = 0; val tags = LinkedHashMap<String, String>(); var geometry: Buf? = null
        while (f.more()) {
            val key = f.varint(); val field = (key shr 3).toInt(); val wire = (key and 7).toInt()
            when {
                field == 2 && wire == 2 -> { val t = f.bytes(); val ids = ArrayList<Int>(); while (t.more()) ids += t.varint().toInt()
                    for (i in 0 until ids.size - 1 step 2) { val k = keys.getOrNull(ids[i]); val v = values.getOrNull(ids[i + 1]); if (k != null && v != null) tags[k] = v } }
                field == 3 && wire == 0 -> type = f.varint().toInt()
                field == 4 && wire == 2 -> geometry = f.bytes()
                else -> f.skip(wire)
            }
        }
        if (type != 2 || geometry == null) return null
        // Geometry is a stream of MoveTo / LineTo commands with zig-zag deltas; a line feature may hold several lines, joined here as one.
        val pts = ArrayList<GeoPoint>(); var cx = 0; var cy = 0
        while (geometry.more()) {
            val cmd = geometry.varint().toInt(); val id = cmd and 7; val count = cmd shr 3
            if (id == 7) continue
            repeat(count) {
                val dx = geometry.varint().toInt(); val dy = geometry.varint().toInt()
                cx += (dx ushr 1) xor -(dx and 1); cy += (dy ushr 1) xor -(dy and 1)
                if (id == 1 && pts.isNotEmpty()) pts += NaNPoint
                pts += toLonLat(cx, cy, extent, z, x, y)
            }
        }
        return MvtLine(pts, tags)
    }

    /** Marks a break between separate lines inside one feature. */
    val NaNPoint = GeoPoint(Double.NaN, Double.NaN)

    private fun toLonLat(px: Int, py: Int, extent: Int, z: Int, x: Int, y: Int): GeoPoint {
        val n = (1 shl z).toDouble()
        val lon = (x + px.toDouble() / extent) / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * (y + py.toDouble() / extent) / n))))
        return GeoPoint(lat, lon)
    }
}
