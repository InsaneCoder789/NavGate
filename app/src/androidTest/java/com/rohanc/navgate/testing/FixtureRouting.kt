package com.rohanc.navgate.testing

import com.rohanc.navgate.core.*

/** A routing provider for tests: a straight road-style route from the start to the destination point, with a start and an arrival. */
class FixtureRouting(private val failure: RouteResult.Unavailable? = null) : RoutingProvider {
    override suspend fun plan(request: PlanRequest, pinnedVersion: String?): RouteResult {
        failure?.let { return it }
        val to = request.destinationPoint ?: return RouteResult.Unavailable(RouteFailure.InvalidCoordinate, "No destination point")
        val points = listOf(request.origin, to)
        val length = request.origin.distanceTo(to).coerceAtLeast(1.0)
        val seconds = length / if (request.profile == "driving") 10.0 else 1.35
        return RouteResult.Ready(CampusRoute("fixture-${request.requestId}", request.requestId, request.regionId, "fixture-v1", request.destinationPlaceId, "", points, emptyList(),
            length, seconds, listOf(Maneuver("Head to the destination", request.origin, 0.0, "Start"), Maneuver("You have arrived", to, length, "Arrive")), "",
            source = "valhalla", profile = request.profile, provider = "fixture", fetchedAtEpochMillis = 1L, destinationPoint = to, destinationTitle = request.destinationTitle,
            shapeElapsedSeconds = listOf(0.0, seconds)))
    }
}
