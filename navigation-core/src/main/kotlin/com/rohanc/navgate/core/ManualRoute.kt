package com.rohanc.navgate.core

import kotlinx.serialization.Serializable

@Serializable
data class RoutePin(val id: String, val label: String, val point: GeoPoint) {
    init {
        require(id.isNotBlank() && id.length<=80)
        require(label.isNotBlank() && label.length<=120)
        require(point.valid) { "Pin coordinates must be finite WGS84 coordinates" }
    }
}

/** An ordered user-authored itinerary. Joining pins is not proof of a traversable road/path. */
@Serializable
data class ManualRoute(val id: String, val name: String, val mode: String, val pins: List<RoutePin>) {
    init {
        require(id.isNotBlank() && id.length<=80)
        require(name.isNotBlank() && name.length<=120)
        require(mode in setOf("walking","driving"))
        require(pins.size in 2..100) { "A route requires 2–100 labelled pins" }
        require(pins.map{it.id}.distinct().size==pins.size) { "Pin IDs must be unique" }
        require(pins.zipWithNext().all{(a,b)->a.point.distanceTo(b.point)>=1}) { "Consecutive pins must be at least one metre apart" }
    }
    val straightLineMeters: Double get() = pins.zipWithNext().sumOf{(a,b)->a.point.distanceTo(b.point)}
}
