package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.data.search.MapPin
import com.rohanc.navgate.data.search.PoiSource
import kotlin.math.cos
import kotlin.math.floor

/**
 * The places around the part of the map on screen. The map is cut into small cells that are each read from the offline index once and kept,
 * so panning back over a street costs nothing and a pan reads only the cells that are new. Nothing is read while zoomed far out or while guiding.
 */
class PlacePinLoader(private val source: PoiSource?) {
    private val cells = object : LinkedHashMap<Long, List<MapPin>>(64, 0.75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<MapPin>>) = size > 96 }

    suspend fun around(south: Double, west: Double, north: Double, east: Double): List<MapPin> {
        val index = source ?: return emptyList()
        val rows = floor(north / STEP).toInt() - floor(south / STEP).toInt() + 1; val columns = floor(east / STEP).toInt() - floor(west / STEP).toInt() + 1
        if (rows * columns > MAX_CELLS) return emptyList()
        val found = ArrayList<MapPin>()
        for (r in floor(south / STEP).toInt()..floor(north / STEP).toInt()) for (c in floor(west / STEP).toInt()..floor(east / STEP).toInt()) {
            val key = (r.toLong() shl 32) xor (c.toLong() and 0xffffffffL)
            val cached = synchronized(cells) { cells[key] }
            found += cached ?: index.pins(r * STEP, c * STEP, (r + 1) * STEP, (c + 1) * STEP, CELL_LIMIT).also { synchronized(cells) { cells[key] = it } }
        }
        return found.filter { it.latitude in south..north && it.longitude in west..east }
    }

    companion object {
        const val STEP = 0.01
        const val CELL_LIMIT = 90
        const val MAX_CELLS = 30
        const val MIN_ZOOM = 14.6
        const val MAX_PINS = 320

        /** The box a map view covers, a little generous toward the horizon when tilted. */
        fun box(latitude: Double, longitude: Double, metersPerDp: Double, widthDp: Float, heightDp: Float): DoubleArray {
            val dLat = heightDp * metersPerDp * 0.75 / 111_320.0; val dLon = widthDp * metersPerDp * 0.6 / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.2))
            return doubleArrayOf(latitude - dLat, longitude - dLon, latitude + dLat, longitude + dLon)
        }

        /** KIIT campus places win over an index entry of the same name within 40 m, so a place never shows twice. */
        fun merge(indexed: List<MapPin>, campus: List<MapPin>): List<MapPin> {
            if (campus.isEmpty()) return indexed
            val kept = indexed.filterNot { p -> campus.any { c -> c.name.equals(p.name, true) && kotlin.math.abs(c.latitude - p.latitude) < 0.0004 && kotlin.math.abs(c.longitude - p.longitude) < 0.0004 } }
            return campus + kept
        }

        fun pinOf(place: CampusPlace): MapPin? = place.point?.let { MapPin(place.id, place.title, place.category, it.latitude, it.longitude, place, com.rohanc.navgate.data.search.PinKind.of("", place.category)) }

        fun geoJson(pins: List<MapPin>): String {
            val out = StringBuilder("""{"type":"FeatureCollection","features":[""")
            pins.take(MAX_PINS).forEachIndexed { i, p ->
                if (i > 0) out.append(',')
                out.append("""{"type":"Feature","properties":{"kind":""").append(quote(p.kind.slug)).append(""","name":""").append(quote(p.name)).append("""},"geometry":{"type":"Point","coordinates":[""").append(p.longitude).append(',').append(p.latitude).append("]}}")
            }
            return out.append("]}").toString()
        }
        private fun quote(text: String) = buildString { append('"'); for (ch in text) when { ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch < ' ' -> append(' '); else -> append(ch) }; append('"') }
    }
}
