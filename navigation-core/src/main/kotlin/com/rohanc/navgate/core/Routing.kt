package com.rohanc.navgate.core

import kotlinx.serialization.Serializable

@Serializable
enum class RouteFailure { MissingRegion, MissingGraph, UnsupportedProfile, InvalidCoordinate, MissingEntrance, OutsideCoverage, UncertainOrigin, NoConnection, InvalidGraph }

sealed interface RouteResult {
    data class Ready(val route: CampusRoute) : RouteResult
    data class Unavailable(val reason: RouteFailure, val detail: String) : RouteResult
}

@Serializable
data class PlanRequest(
    val requestId: Long,
    val regionId: String,
    val origin: GeoPoint,
    val destinationPlaceId: String,
    val entranceId: String? = null,
    val profile: String = "walking",
    val destinationPoint: GeoPoint? = null,
    val destinationTitle: String? = null,
    val routingSource: String? = null,
    /** "fastest" (least time) or "shortest" (least distance). */
    val preference: String = "fastest",
)

@Serializable
data class Maneuver(
    val instruction: String,
    val target: GeoPoint,
    val alongMeters: Double,
    val kind: String,
    /** Roundabouts: which exit to take, and how the route turns overall between the road you enter on and the road you leave on (positive = right). */
    val exitNumber: Int? = null,
    val bearingChange: Double? = null,
)

@Serializable
data class CampusRoute(
    val id: String,
    val requestId: Long,
    val regionId: String,
    val graphVersion: String,
    val destinationPlaceId: String,
    val entranceId: String,
    val geometry: List<GeoPoint>,
    val edgeIds: List<String>,
    val distanceMeters: Double,
    val estimatedSeconds: Double,
    val maneuvers: List<Maneuver>,
    val reviewedAt: String,
    val source: String = "local_campus_astar",
    val profile: String = "walking",
    val provider: String? = null,
    val fetchedAtEpochMillis: Long? = null,
    val destinationPoint: GeoPoint? = null,
    val destinationTitle: String? = null,
    /** Provider cumulative elapsed time at every geometry vertex, in seconds. */
    val shapeElapsedSeconds: List<Double> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** "Fastest", "Shortest" or "Alternative" when several routes were offered. */
    val label: String? = null,
    val fromCache: Boolean = false,
) {
    val external: Boolean get() = source == "valhalla" || source == "brouter"
    fun shapeToRouteMeters(meters: Double): Double =
        if (geometry.lengthMeters() > 0) meters / geometry.lengthMeters() * distanceMeters else 0.0
    fun routeToShapeMeters(meters: Double): Double =
        if (distanceMeters > 0) meters / distanceMeters * geometry.lengthMeters() else 0.0
    fun remainingSecondsAt(progress: Double): Double {
        if (distanceMeters <= 0) return 0.0
        if (shapeElapsedSeconds.size != geometry.size) return estimatedSeconds * (1 - progress / distanceMeters).coerceIn(0.0, 1.0)
        val target = routeToShapeMeters(progress)
        var along = 0.0
        geometry.zipWithNext().forEachIndexed { i, (a, b) ->
            val length = a.distanceTo(b)
            if (target <= along + length || i == geometry.lastIndex - 1) {
                val fraction = if (length > 0) ((target - along) / length).coerceIn(0.0, 1.0) else 1.0
                val elapsed = shapeElapsedSeconds[i] + fraction * (shapeElapsedSeconds[i + 1] - shapeElapsedSeconds[i])
                return (estimatedSeconds - elapsed).coerceAtLeast(0.0)
            }
            along += length
        }
        return 0.0
    }
}

interface RoutingProvider {
    suspend fun plan(request: PlanRequest, pinnedVersion: String? = null): RouteResult

    /** Other good ways to the same destination (and a fresh best route), used as ready-made backups if the driver leaves the planned road. */
    suspend fun alternatives(request: PlanRequest): List<CampusRoute> = emptyList()
}
