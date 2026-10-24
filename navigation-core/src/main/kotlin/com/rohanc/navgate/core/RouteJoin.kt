package com.rohanc.navgate.core

import java.util.UUID

/**
 * Chains single-leg routes (stop 1 to 2, 2 to 3, ...) into one trip that the session can follow like any other route.
 * Each stop but the last becomes a "Stop N" cue; only the final point is an arrival.
 */
object RouteJoin {
    /** [stopTitles] names every stop after the first, so it has one entry per leg. Returns null when the legs do not form a trip. */
    fun join(legs: List<CampusRoute>, stopTitles: List<String>, requestId: Long = 0): CampusRoute? {
        if (legs.isEmpty() || stopTitles.size != legs.size) return null
        if (legs.map { it.profile }.distinct().size != 1 || legs.any { it.geometry.size < 2 }) return null
        if (legs.size == 1) return legs.single()
        val geometry = ArrayList<GeoPoint>(); val times = ArrayList<Double>(); val maneuvers = ArrayList<Maneuver>()
        var distance = 0.0; var seconds = 0.0
        legs.forEachIndexed { index, leg ->
            val last = index == legs.lastIndex
            // The next leg starts where this one ended; keep one copy of that point.
            val dropFirst = geometry.isNotEmpty() && geometry.last().distanceTo(leg.geometry.first()) < 1.0
            val points = if (dropFirst) leg.geometry.drop(1) else leg.geometry
            val legTimes = if (leg.shapeElapsedSeconds.size == leg.geometry.size) (if (dropFirst) leg.shapeElapsedSeconds.drop(1) else leg.shapeElapsedSeconds) else emptyList()
            geometry += points
            legTimes.forEach { times += seconds + it }
            // Leg maneuvers: skip the later legs' "Start" (the stop cue sits at the same spot) and turn each middle arrival into a stop cue.
            leg.maneuvers.forEachIndexed { m, maneuver ->
                val isArrival = m == leg.maneuvers.lastIndex
                when {
                    index > 0 && m == 0 && maneuver.kind == "Start" -> Unit
                    isArrival && !last -> maneuvers += Maneuver("Stop ${index + 2} of ${legs.size + 1}: ${stopTitles[index]}", maneuver.target, distance + leg.distanceMeters, "Straight")
                    else -> maneuvers += maneuver.copy(alongMeters = distance + maneuver.alongMeters)
                }
            }
            distance += leg.distanceMeters; seconds += leg.estimatedSeconds
        }
        val first = legs.first(); val final = legs.last()
        return first.copy(
            id = UUID.randomUUID().toString(), requestId = requestId, geometry = geometry, distanceMeters = distance, estimatedSeconds = seconds,
            maneuvers = maneuvers, shapeElapsedSeconds = if (times.size == geometry.size) times else emptyList(),
            destinationPlaceId = final.destinationPlaceId, destinationPoint = final.destinationPoint, destinationTitle = final.destinationTitle,
            warnings = legs.flatMap { it.warnings }.distinct(), label = null,
        )
    }
}
