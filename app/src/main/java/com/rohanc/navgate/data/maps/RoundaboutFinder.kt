package com.rohanc.navgate.data.maps

import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.RoundaboutRing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.tan

/**
 * Finds roundabouts along a route in the road layer of the map tiles the app already draws: a roundabout is a small closed loop of road,
 * and the roads that meet it are its exits. Tiles come from the saved map pack when there is one, from the tile server otherwise.
 */
class RoundaboutFinder(private val tile: suspend (z: Int, x: Int, y: Int) -> ByteArray?) {
    private class Loop(val points: List<GeoPoint>, val center: GeoPoint, val radius: Double, val members: List<MvtLine> = emptyList())

    suspend fun near(route: List<GeoPoint>): List<RoundaboutRing> {
        if (route.size < 2) return emptyList()
        val n = 1 shl ZOOM
        fun tx(lon: Double) = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        fun ty(lat: Double) = floor((1.0 - asinh(tan(Math.toRadians(lat.coerceIn(-85.0, 85.0)))) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        val tiles = LinkedHashSet<Pair<Int, Int>>()
        // Only the tiles the route actually crosses (and their neighbours, since a ring can sit on a tile edge).
        for (p in route.filterIndexed { i, _ -> i % 4 == 0 } + route.last()) {
            val x = tx(p.longitude); val y = ty(p.latitude)
            for (dx in -1..1) for (dy in -1..1) tiles += (x + dx) to (y + dy)
            if (tiles.size > MAX_TILES) return emptyList()
        }
        val lines = ArrayList<MvtLine>()
        for ((x, y) in tiles) {
            val bytes = tile(ZOOM, x, y) ?: continue
            lines += MvtDecoder.lines(if (Pmtiles.isGzip(bytes)) Pmtiles.gunzip(bytes) else bytes, "transportation", ZOOM, x, y)
        }
        return rings(lines, route)
    }

    internal fun rings(lines: List<MvtLine>, route: List<GeoPoint>): List<RoundaboutRing> {
        val loops = (lines.mapNotNull { loop(it.points) } + chainedLoops(lines)).filter { l -> route.any { it.distanceTo(l.center) <= l.radius + NEAR_ROUTE_METERS } }
            .fold(ArrayList<Loop>()) { kept, l -> if (kept.none { it.center.distanceTo(l.center) < 5.0 }) kept += l; kept }
        return loops.map { l ->
            val arms = ArrayList<Double>()
            for (line in lines) {
                if (line.points === l.points || l.members.any { it === line }) continue
                val touching = line.points.any { !it.latitude.isNaN() && abs(it.distanceTo(l.center) - l.radius) <= ARM_REACH_METERS }
                if (!touching) continue
                // The bearing the road leaves in: where it is about 25 m out, or its far end when it is shorter than that.
                val far = line.points.filter { !it.latitude.isNaN() && it.distanceTo(l.center) in l.radius..(l.radius + 40.0) }.maxByOrNull { it.distanceTo(l.center) } ?: continue
                val bearing = l.center.bearingTo(far)
                if (arms.none { angleBetween(it, bearing) < 22.0 }) arms += bearing
            }
            RoundaboutRing(l.center, l.radius, arms)
        }
    }

    /**
     * Rings that the map stores in pieces (a roundabout cut at every junction, or at a tile edge): follow pieces that meet end to end,
     * turning the same way all the time, until the chain comes back to where it started.
     */
    private fun chainedLoops(lines: List<MvtLine>): List<Loop> {
        val pieces = lines.filter { l -> l.points.size >= 2 && l.points.none { it.latitude.isNaN() } && l.points.first().distanceTo(l.points.last()) > 3.0 && length(l.points) in 3.0..200.0 }
        if (pieces.size < 2) return emptyList()
        val found = ArrayList<Loop>()
        fun ends(p: MvtLine) = listOf(p.points.first(), p.points.last())
        for ((i, start) in pieces.withIndex()) for (startAtFirst in listOf(true, false)) {
            val chain = arrayListOf(if (startAtFirst) start.points else start.points.reversed())
            val used = hashSetOf(i)
            fun walk(): Boolean {
                val tail = chain.last().last()
                if (chain.size >= 2 && tail.distanceTo(chain.first().first()) <= JOIN_METERS) return true
                if (chain.size >= MAX_PIECES) return false
                for ((j, next) in pieces.withIndex()) {
                    if (j in used) continue
                    val forward = ends(next)[0].distanceTo(tail) <= JOIN_METERS; val backward = ends(next)[1].distanceTo(tail) <= JOIN_METERS
                    if (!forward && !backward) continue
                    val points = if (forward) next.points else next.points.reversed()
                    if (!turnsSameWay(chain.last(), points) || length(chain.flatten()) + length(points) > MAX_PERIMETER) continue
                    chain += points; used += j
                    if (walk()) return true
                    chain.removeAt(chain.lastIndex); used -= j
                }
                return false
            }
            if (walk()) loop(chain.flatten().let { it + it.first() })?.let { found += Loop(it.points, it.center, it.radius, used.map { k -> pieces[k] }) }
        }
        return found
    }

    private fun length(points: List<GeoPoint>) = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

    /** True when the bend from [a] into [b] is gentle and to the same side as the bends inside them, as along a circle. */
    private fun turnsSameWay(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
        val before = a[a.size - 2].bearingTo(a.last()); val after = b.first().bearingTo(b[1])
        val turn = ((after - before + 540.0) % 360.0) - 180.0
        return abs(turn) < 70.0
    }

    private fun loop(points: List<GeoPoint>): Loop? {
        if (points.size < 6 || points.any { it.latitude.isNaN() }) return null
        if (points.first().distanceTo(points.last()) > 3.0) return null
        val perimeter = points.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
        if (perimeter !in MIN_PERIMETER..MAX_PERIMETER) return null
        val body = points.dropLast(1)
        val c = GeoPoint(body.sumOf { it.latitude } / body.size, body.sumOf { it.longitude } / body.size)
        val radii = body.map { it.distanceTo(c) }; val mean = radii.average()
        // A circle, not a block of buildings or a long thin loop.
        if (mean < 4.0 || radii.max() / max(radii.min(), 0.1) > 1.8) return null
        return Loop(points, c, mean)
    }

    private fun angleBetween(a: Double, b: Double) = abs(((a - b + 540.0) % 360.0) - 180.0)

    companion object {
        const val ZOOM = 14
        private const val MAX_TILES = 60
        private const val JOIN_METERS = 2.5
        private const val MAX_PIECES = 8
        private const val NEAR_ROUTE_METERS = 30.0
        private const val ARM_REACH_METERS = 14.0
        private const val MIN_PERIMETER = 28.0
        private const val MAX_PERIMETER = 320.0
    }
}
