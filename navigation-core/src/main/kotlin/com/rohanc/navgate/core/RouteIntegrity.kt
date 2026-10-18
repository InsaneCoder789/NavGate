package com.rohanc.navgate.core

import kotlin.math.abs

/** Validates durable routes as graph paths, not just drawable polylines. */
object RouteIntegrity {
    fun shapeErrors(route: CampusRoute): List<String> = buildList {
        if (route.id.isBlank() || route.regionId.isBlank() || route.graphVersion.isBlank()) add("Missing route identity")
        if (route.geometry.size !in 2..200_000 || route.geometry.any { !it.valid }) add("Invalid route geometry")
        if (!route.distanceMeters.isFinite() || route.distanceMeters < 0 || !route.estimatedSeconds.isFinite() || route.estimatedSeconds < 0) add("Invalid route costs")
        if (route.geometry.all { it.valid } && abs(route.geometry.lengthMeters()-route.distanceMeters)>0.05) add("Geometry and route distance disagree")
        if (route.maneuvers.isEmpty() || route.maneuvers.any { !it.target.valid || !it.alongMeters.isFinite() || it.alongMeters !in 0.0..route.distanceMeters || it.instruction.isBlank() }) add("Invalid maneuvers")
        if (route.maneuvers.zipWithNext().any { (a,b) -> a.alongMeters>b.alongMeters }) add("Maneuvers are out of order")
        if (route.maneuvers.lastOrNull()?.kind!="Arrive") add("Missing final arrival maneuver")
    }

    fun checkpointErrors(checkpoint: SessionCheckpoint, pack: RegionPack): List<String> = buildList {
        val saved=checkpoint.snapshot
        val route=saved.route
        if (route==null) { add("Missing checkpoint route");return@buildList }
        addAll(shapeErrors(route))
        if (!PackValidator.validate(pack).valid) add("Invalid pinned graph")
        if (route.regionId!=pack.regionId || route.graphVersion!=pack.version || route.reviewedAt!=pack.reviewedAt || route.source!="local_campus_astar") add("Route does not match pinned graph metadata")
        if (saved.sessionId.isNullOrBlank() || saved.status !in NavigationSession.ACTIVE) add("Invalid checkpoint session")
        if (!saved.alongMeters.isFinite() || saved.alongMeters !in 0.0..route.distanceMeters || !saved.remainingMeters.isFinite() || abs(saved.remainingMeters-(route.distanceMeters-saved.alongMeters))>0.05) add("Invalid checkpoint progress")
        if (!saved.remainingSeconds.isFinite() || saved.remainingSeconds<0 || saved.maneuverIndex !in route.maneuvers.indices) add("Invalid checkpoint maneuver or duration")
        val entrance=pack.entrances.find { it.id==route.entranceId && it.placeId==route.destinationPlaceId && it.reviewed }
        val target=entrance?.let { entry -> pack.nodes.find { it.id==entry.nodeId }?.point }
        if (target==null || route.geometry.lastOrNull()?.distanceTo(target)?.let { it>0.1 }!=false) add("Route does not finish at its reviewed entrance")
        val edges=route.edgeIds.mapNotNull { id -> pack.edges.find { it.id==id && it.reviewed && it.access==Access.Allowed } }
        if (edges.size!=route.edgeIds.size || edges.zipWithNext().any { (a,b) -> a.to!=b.from }) add("Checkpoint contains disconnected or forbidden edges")
        if (edges.isEmpty()) {
            if (route.distanceMeters!=0.0 || route.geometry.any { it!=target }) add("Nonzero route without graph edges")
        } else if (route.geometry.isNotEmpty() && route.geometry.first().valid) {
            val projection=projectPath(route.geometry.first(),edges.first().geometry)
            if (projection==null || projection.distanceMeters>0.05) add("Checkpoint starts outside its first edge")
            else {
                val expected=(listOf(projection.point)+edges.first().geometry.drop(projection.segment+1)+edges.drop(1).flatMap { it.geometry })
                    .fold(mutableListOf<GeoPoint>()) { result,point -> if (result.lastOrNull()?.distanceTo(point)?.let { it>0.001 }!=false) result+=point;result }
                val actual=route.geometry.fold(mutableListOf<GeoPoint>()) { result,point -> if (result.lastOrNull()?.distanceTo(point)?.let { it>0.001 }!=false) result+=point;result }
                if (expected.size!=actual.size || expected.zip(actual).any { (a,b) -> a.distanceTo(b)>0.05 }) add("Checkpoint geometry does not follow its recorded edges")
            }
        }
    }
}
