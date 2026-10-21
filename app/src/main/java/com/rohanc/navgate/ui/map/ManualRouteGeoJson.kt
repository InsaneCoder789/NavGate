package com.rohanc.navgate.ui.map

import com.rohanc.navgate.core.RoutePin
import kotlinx.serialization.json.*

internal object ManualRouteGeoJson {
    fun points(pins: List<RoutePin>): String = buildJsonObject {
        put("type","FeatureCollection")
        putJsonArray("features") { pins.forEachIndexed { index,pin ->
            add(buildJsonObject {
                put("type","Feature")
                putJsonObject("properties") { put("label","${index+1}. ${pin.label}") }
                putJsonObject("geometry") { put("type","Point");putJsonArray("coordinates"){add(pin.point.longitude);add(pin.point.latitude)} }
            })
        } }
    }.toString()
}
