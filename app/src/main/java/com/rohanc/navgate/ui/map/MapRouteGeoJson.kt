package com.rohanc.navgate.ui.map

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteResponse

object MapRouteGeoJson {
    fun lineString(route: RouteResponse?): String =
        featureCollectionJson(
            geometry = route?.pathCoordinates.orEmpty().toLineStringCoordinates(),
            geometryType = "LineString",
        )

    /**
     * The route cut at the vehicle: the part already driven, and the part still to go. Both are LineString collections, empty when
     * there is nothing on that side. [alongMeters] is distance along the drawn line.
     */
    fun progressSplit(line: com.rohanc.navgate.core.RouteGeometry?, alongMeters: Double, behindMeters: Double = Double.POSITIVE_INFINITY,
        aheadMeters: Double = Double.POSITIVE_INFINITY, laneOffsetMeters: Double = 0.0): Pair<String, String> {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        if (line == null || line.points.size < 2) return empty to empty
        val along = alongMeters.coerceIn(0.0, line.length)
        // Only the stretch the screen can show is built (a little behind, a few hundred metres ahead), found by binary search, so the cost
        // of each update does not grow with the length of the trip. The rest of the route is not sent to the map at all.
        val from = (along - behindMeters).coerceAtLeast(0.0); val to = (along + aheadMeters).coerceAtMost(line.length)
        fun firstAfter(s: Double): Int { val i = java.util.Arrays.binarySearch(line.cumulative, s); return if (i >= 0) i + 1 else -i - 1 }
        val here = line.pointAt(along)
        val done = ArrayList<com.rohanc.navgate.core.GeoPoint>(); val ahead = ArrayList<com.rohanc.navgate.core.GeoPoint>()
        done += line.pointAt(from)
        for (i in firstAfter(from) until firstAfter(along)) if (line.cumulative[i] < along) done += line.points[i]
        done += here
        ahead += here
        for (i in firstAfter(along) until line.points.size) { if (line.cumulative[i] >= to) break; ahead += line.points[i] }
        line.pointAt(to).let { end -> if (ahead.last().distanceTo(end) > 0.01) ahead += end }
        // The vehicle drives in its own lane, so the line ahead does too: it holds the lane for 80 m and returns to the road's centre.
        val drawnAhead = if (kotlin.math.abs(laneOffsetMeters) >= 0.2) com.rohanc.navgate.core.LaneTracker.shift(ahead, laneOffsetMeters, holdMeters = 80.0, fadeMeters = 60.0) else ahead
        fun json(points: List<com.rohanc.navgate.core.GeoPoint>) =
            if (points.size < 2 || points.zipWithNext().all { (a, b) -> a.distanceTo(b) < 0.01 }) empty
            else featureCollectionJson(points.joinToString(prefix = "[", postfix = "]") { "[${it.longitude},${it.latitude}]" }, "LineString")
        return json(if (along <= 0.0) emptyList() else done) to json(drawnAhead)
    }

    fun endpoints(origin: Coordinate?, destination: Coordinate?): String {
        val features = buildList {
            origin?.let { add(pointFeature(it, "origin")) }
            destination?.let { add(pointFeature(it, "destination")) }
        }
        return """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
    }

    private fun featureCollectionJson(geometry: String, geometryType: String): String =
        if (geometry.isBlank()) {
            """{"type":"FeatureCollection","features":[]}"""
        } else {
            """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","properties":{},"geometry":{"type":"$geometryType","coordinates":$geometry}}
            ]}
            """.trimIndent().replace("\n", "")
        }

    private fun List<Coordinate>.toLineStringCoordinates(): String =
        if (isEmpty()) "" else joinToString(prefix = "[", postfix = "]") { "[${it.longitude},${it.latitude}]" }

    private fun pointFeature(coordinate: Coordinate, kind: String): String =
        """{"type":"Feature","properties":{"kind":"$kind"},"geometry":{"type":"Point","coordinates":[${coordinate.longitude},${coordinate.latitude}]}}"""
}
