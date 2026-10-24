package com.rohanc.navgate.core

import kotlin.math.abs
import kotlin.math.max

/** Validates durable routes as graph paths, not just drawable polylines. */
object RouteIntegrity {
    fun shapeErrors(route: CampusRoute): List<String> = buildList {
        if (route.id.isBlank() || route.regionId.isBlank() || route.graphVersion.isBlank()) add("Missing route identity")
        if (route.geometry.size !in 2..200_000 || route.geometry.any { !it.valid }) add("Invalid route geometry")
        if (!route.distanceMeters.isFinite() || route.distanceMeters < 0 || !route.estimatedSeconds.isFinite() || route.estimatedSeconds < 0) add("Invalid route costs")
        if (route.profile !in setOf("walking", "driving")) add("Unsupported route profile")
        if (route.source !in setOf("valhalla", "brouter")) add("Unknown route source")
        if (!route.external && route.profile != "walking") add("Campus graph only supports walking")
        val tolerance = if (route.external) max(30.0, route.distanceMeters * 0.05) else 0.05
        if (route.geometry.all { it.valid } && abs(route.geometry.lengthMeters()-route.distanceMeters)>tolerance) add("Geometry and route distance disagree")
        if (route.external) {
            if (route.provider.isNullOrBlank() || (route.fetchedAtEpochMillis ?: 0) <= 0) add("Missing provider provenance")
            if (route.destinationPlaceId.isBlank() || route.destinationPoint?.valid != true) add("Missing provider destination")
            if (route.destinationPoint?.valid == true && route.geometry.lastOrNull()?.distanceTo(route.destinationPoint)?.let { it > 150 } != false) add("Route misses destination")
            val times = route.shapeElapsedSeconds
            if (times.size != route.geometry.size || times.any { !it.isFinite() || it < 0 } || times.zipWithNext().any { (a,b) -> a>b } || times.firstOrNull() != 0.0 || times.lastOrNull()?.let { abs(it-route.estimatedSeconds)>0.1 } != false) add("Invalid provider shape timing")
            if (route.distanceMeters > 0 && route.estimatedSeconds <= 0) add("Missing provider travel time")
        }
        if (route.maneuvers.isEmpty() || route.maneuvers.any { !it.target.valid || !it.alongMeters.isFinite() || it.alongMeters !in 0.0..route.distanceMeters || it.instruction.isBlank() }) add("Invalid maneuvers")
        if (route.maneuvers.zipWithNext().any { (a,b) -> a.alongMeters>b.alongMeters }) add("Maneuvers are out of order")
        if (route.maneuvers.lastOrNull()?.kind!="Arrive") add("Missing final arrival maneuver")
        route.maneuvers.lastOrNull()?.let { arrival ->
            if (abs(arrival.alongMeters-route.distanceMeters)>0.1 || route.geometry.lastOrNull()?.distanceTo(arrival.target)?.let { it>1 } != false) add("Arrival does not finish route")
        }
    }

    fun externalCheckpointErrors(checkpoint: SessionCheckpoint): List<String> = buildList {
        val saved = checkpoint.snapshot
        val route = saved.route
        if (route == null || !route.external) { add("Missing external route"); return@buildList }
        addAll(shapeErrors(route))
        addAll(progressErrors(saved, route))
    }

    private fun progressErrors(saved: SessionSnapshot, route: CampusRoute): List<String> = buildList {
        if (saved.sessionId.isNullOrBlank() || saved.status !in NavigationSession.ACTIVE) add("Invalid checkpoint session")
        if (!saved.alongMeters.isFinite() || saved.alongMeters !in 0.0..route.distanceMeters || !saved.remainingMeters.isFinite() || abs(saved.remainingMeters-(route.distanceMeters-saved.alongMeters))>0.05) add("Invalid checkpoint progress")
        if (!saved.remainingSeconds.isFinite() || saved.remainingSeconds<0 || abs(saved.remainingSeconds-route.remainingSecondsAt(saved.alongMeters))>0.1 || saved.maneuverIndex !in route.maneuvers.indices) add("Invalid checkpoint maneuver or duration")
    }
}
