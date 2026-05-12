package com.rohanc.navgate.ui.map

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteResponse

object MapRouteGeoJson {
    fun lineString(route: RouteResponse?): String =
        featureCollectionJson(
            geometry = route?.pathCoordinates.orEmpty().toLineStringCoordinates(),
            geometryType = "LineString",
        )

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
