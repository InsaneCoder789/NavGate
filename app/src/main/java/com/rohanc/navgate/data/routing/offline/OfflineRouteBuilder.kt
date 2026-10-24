package com.rohanc.navgate.data.routing.offline

import com.rohanc.navgate.core.CampusRoute
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.core.PlanRequest
import com.rohanc.navgate.core.RouteGeometry
import com.rohanc.navgate.core.TurnExtractor
import java.util.UUID

/** Turns a bare offline road line into a route the guidance engine can follow, with turns found from the line's own shape. */
object OfflineRouteBuilder {
    const val GRAPH_VERSION = "brouter-1.7.10"

    fun build(request: PlanRequest, track: OfflineTrack, nowMillis: Long): CampusRoute {
        val destination = requireNotNull(request.destinationPoint)
        val geometry = RouteGeometry(track.points)
        val length = geometry.length
        require(track.points.size >= 2 && length > 0)
        val seconds = track.seconds.takeIf { it.isFinite() && it > 0 } ?: (length / if (request.profile == "driving") 11.0 else 1.3)
        val maneuvers = TurnExtractor.extract(track.points, request.profile, track.streetNames, request.destinationTitle)
        // Time at each point, in proportion to distance along the line: BRouter's own totals, spread evenly.
        val elapsed = track.points.indices.map { if (length <= 0) 0.0 else geometry.cumulative[it] / length * seconds }
        val warnings = buildList {
            add("Offline route · BRouter, © OpenStreetMap contributors. Estimated time; live traffic and closures are not included.")
            val startOffset = track.points.first().distanceTo(request.origin)
            if (startOffset > 20) add("The mapped route starts ${startOffset.toInt()} m from your start. Move to that point before beginning guidance.")
            if (track.points.last().distanceTo(destination) > 20) add("Arrival is at the mapped road/path endpoint near the destination pin, not a verified building entrance.")
        }
        return CampusRoute(id = UUID.randomUUID().toString(), requestId = request.requestId, regionId = request.regionId, graphVersion = GRAPH_VERSION,
            destinationPlaceId = request.destinationPlaceId, entranceId = "", geometry = track.points, edgeIds = emptyList(), distanceMeters = length,
            estimatedSeconds = seconds, maneuvers = maneuvers, reviewedAt = "", source = "brouter", profile = request.profile, provider = "brouter-offline",
            fetchedAtEpochMillis = nowMillis, destinationPoint = destination, destinationTitle = request.destinationTitle, shapeElapsedSeconds = elapsed, warnings = warnings)
    }
}
