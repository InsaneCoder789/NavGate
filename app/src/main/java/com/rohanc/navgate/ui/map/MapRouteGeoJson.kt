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
    fun progressSplit(line: com.rohanc.navgate.core.RouteGeometry?, alongMeters: Double): Pair<String, String> {
        val empty = """{"type":"FeatureCollection","features":[]}"""
        if (line == null || line.points.size < 2) return empty to empty
        val along = alongMeters.coerceIn(0.0, line.length)
        val here = line.pointAt(along)
        val done = ArrayList<com.rohanc.navgate.core.GeoPoint>(); val ahead = ArrayList<com.rohanc.navgate.core.GeoPoint>()
        done += line.points.first(); ahead += here
        line.points.forEachIndexed { i, p -> if (line.cumulative[i] < along) done += p else if (line.cumulative[i] > along) ahead += p }
        done += here
        fun json(points: List<com.rohanc.navgate.core.GeoPoint>) =
            if (points.size < 2 || points.zipWithNext().all { (a, b) -> a.distanceTo(b) < 0.01 }) empty
            else featureCollectionJson(points.joinToString(prefix = "[", postfix = "]") { "[${it.longitude},${it.latitude}]" }, "LineString")
        return json(if (along <= 0.0) emptyList() else done) to json(ahead)
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
