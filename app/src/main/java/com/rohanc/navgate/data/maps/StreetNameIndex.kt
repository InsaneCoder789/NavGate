package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Road names for a line of points, read from the named-road layer of a downloaded map pack. This is what lets an offline route
 * say "Turn right onto Marine Drive": the routing data itself carries no names, but the map the user already saved does.
 */
class StreetNameIndex(private val reader: Pmtiles.Reader) {
    private class Segment(val a: GeoPoint, val b: GeoPoint, val name: String, val bearing: Double)

    /** One name (or null) per point. Names must lie within [maxMeters] of the point and run roughly the same way as the route. */
    fun namesAlong(points: List<GeoPoint>, maxMeters: Double = 22.0): List<String?> {
        if (points.size < 2) return List(points.size) { null }
        val segments = load(points)
        if (segments.isEmpty()) return List(points.size) { null }
        val cell = 0.0004
        val grid = HashMap<Long, MutableList<Segment>>()
        fun key(r: Int, c: Int) = r.toLong() shl 32 or (c.toLong() and 0xffffffffL)
        for (s in segments) {
            val r0 = floor(minOf(s.a.latitude, s.b.latitude) / cell).toInt(); val r1 = floor(maxOf(s.a.latitude, s.b.latitude) / cell).toInt()
            val c0 = floor(minOf(s.a.longitude, s.b.longitude) / cell).toInt(); val c1 = floor(maxOf(s.a.longitude, s.b.longitude) / cell).toInt()
            if ((r1 - r0 + 1).toLong() * (c1 - c0 + 1) > 400) continue
            for (r in r0..r1) for (c in c0..c1) grid.getOrPut(key(r, c)) { ArrayList(2) } += s
        }
        val raw = points.mapIndexed { i, p ->
            val ahead = points[minOf(points.lastIndex, i + 1)]; val behind = points[maxOf(0, i - 1)]
            val heading = behind.bearingTo(ahead)
            val r = floor(p.latitude / cell).toInt(); val c = floor(p.longitude / cell).toInt()
            var best: Segment? = null; var bestDistance = maxMeters
            for (dr in -1..1) for (dc in -1..1) for (s in grid[key(r + dr, c + dc)].orEmpty()) {
                val d = distanceToSegment(p, s.a, s.b)
                if (d < bestDistance && alongTheSameWay(heading, s.bearing)) { best = s; bestDistance = d }
            }
            best?.name
        }
        return fillShortGaps(raw)
    }

    private fun alongTheSameWay(a: Double, b: Double): Boolean { val d = abs(((a - b + 540.0) % 360.0) - 180.0); return d < 40.0 || d > 140.0 }

    /** A name missing for a few points between two points with the same name is almost certainly the same road. */
    private fun fillShortGaps(names: List<String?>): List<String?> {
        val out = names.toMutableList(); var i = 0
        while (i < out.size) {
            if (out[i] != null) { i++; continue }
            var j = i; while (j < out.size && out[j] == null) j++
            val before = out.getOrNull(i - 1); val after = out.getOrNull(j)
            if (before != null && before == after && j - i <= 6) for (k in i until j) out[k] = before
            i = j
        }
        return out
    }

    private fun load(points: List<GeoPoint>): List<Segment> {
        val z = ZOOM; val n = 1 shl z
        fun tx(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        fun ty(lat: Double) = floor((1.0 - asinh(tan(Math.toRadians(lat.coerceIn(-85.0, 85.0)))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val margin = 0.0005
        val x0 = tx(points.minOf { it.longitude } - margin); val x1 = tx(points.maxOf { it.longitude } + margin)
        val y0 = ty(points.maxOf { it.latitude } + margin); val y1 = ty(points.minOf { it.latitude } - margin)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 1200) return emptyList()
        val out = ArrayList<Segment>()
        for (x in x0..x1) for (y in y0..y1) {
            val stored = reader.tile(z, x, y) ?: continue
            val bytes = if (Pmtiles.isGzip(stored)) Pmtiles.gunzip(stored) else stored
            for (line in MvtDecoder.lines(bytes, "transportation_name", z, x, y)) {
                val name = nameOf(line.tags) ?: continue
                var previous: GeoPoint? = null
                for (p in line.points) {
                    if (p.latitude.isNaN()) { previous = null; continue }
                    previous?.let { if (it.distanceTo(p) > 0.5) out += Segment(it, p, name, it.bearingTo(p)) }
                    previous = p
                }
            }
        }
        return out
    }

    companion object {
        const val ZOOM = 14
        /** English or Latin-script name first so spoken directions are readable; the local name or road number otherwise. */
        fun nameOf(tags: Map<String, String>): String? =
            listOf("name_en", "name:latin", "name", "ref").firstNotNullOfOrNull { tags[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }

        internal fun distanceToSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
            val k = cos(Math.toRadians(p.latitude)) * 111_320.0
            fun x(g: GeoPoint) = (g.longitude - p.longitude) * k
            fun y(g: GeoPoint) = (g.latitude - p.latitude) * 111_320.0
            val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy).div(len2).coerceIn(0.0, 1.0)
            return hypot(ax + t * dx, ay + t * dy)
        }
    }
}
