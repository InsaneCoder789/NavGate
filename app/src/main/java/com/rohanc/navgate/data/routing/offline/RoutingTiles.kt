package com.rohanc.navgate.data.routing.offline

import com.rohanc.navgate.core.GeoPoint
import kotlin.math.abs
import kotlin.math.floor

/** BRouter routing data comes in 5 by 5 degree tiles named after their south-west corner, e.g. E70_N15 covers Mumbai. */
object RoutingTiles {
    const val SIZE_DEGREES = 5

    fun nameFor(latitude: Double, longitude: Double): String {
        val lon = floor(longitude / SIZE_DEGREES).toInt() * SIZE_DEGREES
        val lat = floor(latitude / SIZE_DEGREES).toInt() * SIZE_DEGREES
        return (if (lon < 0) "W${-lon}" else "E$lon") + "_" + (if (lat < 0) "S${-lat}" else "N$lat")
    }

    fun nameFor(point: GeoPoint) = nameFor(point.latitude, point.longitude)

    /** Every tile a trip between these points might read: the tiles around both ends, plus those between when far apart. */
    fun covering(points: List<GeoPoint>, marginMeters: Double = 30_000.0): List<String> {
        if (points.isEmpty()) return emptyList()
        val marginLat = marginMeters / 111_320.0
        val names = LinkedHashSet<String>()
        val minLat = points.minOf { it.latitude } - marginLat; val maxLat = points.maxOf { it.latitude } + marginLat
        val cosLat = Math.cos(Math.toRadians(points.map { abs(it.latitude) }.max().coerceAtMost(85.0))).coerceAtLeast(0.1)
        val marginLon = marginLat / cosLat
        val minLon = points.minOf { it.longitude } - marginLon; val maxLon = points.maxOf { it.longitude } + marginLon
        var lat = floor(minLat / SIZE_DEGREES) * SIZE_DEGREES
        while (lat <= maxLat) {
            var lon = floor(minLon / SIZE_DEGREES) * SIZE_DEGREES
            while (lon <= maxLon) { names += nameFor(lat + 0.001, lon + 0.001); lon += SIZE_DEGREES }
            lat += SIZE_DEGREES
        }
        return names.toList()
    }

    private val pattern = Regex("^([EW])(\\d{1,3})_([NS])(\\d{1,2})$")
    fun isValid(name: String): Boolean {
        val m = pattern.matchEntire(name) ?: return false
        val lon = m.groupValues[2].toInt(); val lat = m.groupValues[4].toInt()
        return lon % SIZE_DEGREES == 0 && lat % SIZE_DEGREES == 0 && lon <= 180 && lat <= 90
    }

    /** South-west corner and centre of a tile, for showing which area it covers. */
    fun centerOf(name: String): GeoPoint? {
        val m = pattern.matchEntire(name) ?: return null
        val lon = m.groupValues[2].toInt() * (if (m.groupValues[1] == "W") -1 else 1) + SIZE_DEGREES / 2.0
        val lat = m.groupValues[4].toInt() * (if (m.groupValues[3] == "S") -1 else 1) + SIZE_DEGREES / 2.0
        return GeoPoint(lat, lon)
    }
}
