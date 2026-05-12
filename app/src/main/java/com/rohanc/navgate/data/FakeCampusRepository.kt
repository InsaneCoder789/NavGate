package com.rohanc.navgate.data

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import kotlinx.coroutines.delay

class FakeCampusRepository : NavigationRepository {
    private val places =
        listOf(
            PlaceSearchResult("gate-1", "North Gate", "Main arrival gate", 20.349884, 85.807529, PlaceType.Gate),
            PlaceSearchResult("lib-1", "Central Library", "Quiet study hub", 20.350080, 85.808021, PlaceType.Academic),
            PlaceSearchResult("sport-1", "Sports Complex", "Indoor courts and arena", 20.350412, 85.808665, PlaceType.Sports),
            PlaceSearchResult("hostel-1", "Maple Residence", "Student housing block", 20.349210, 85.808990, PlaceType.Residential),
            PlaceSearchResult("cafe-1", "Terrace Cafe", "Coffee and quick meals", 20.349560, 85.808330, PlaceType.Food),
            PlaceSearchResult("bus-1", "Transit Plaza", "Shuttle and bus pickup zone", 20.350905, 85.807775, PlaceType.Transit),
        )

    override suspend fun searchPlaces(query: String): List<PlaceSearchResult> {
        delay(100)
        if (query.isBlank()) return places
        val token = query.trim().lowercase()
        return places.filter {
            it.title.lowercase().contains(token) || it.subtitle.lowercase().contains(token)
        }
    }

    override suspend fun fetchRoute(request: RouteRequest): RouteResponse {
        delay(180)
        val midpoint = interpolatedMidpoint(request.origin, request.destination)
        val totalDistance = request.origin.distanceTo(request.destination)
        val midpointDistance = request.origin.distanceTo(midpoint)
        val finalLegDistance = midpoint.distanceTo(request.destination)
        val durationSeconds = totalDistance / 1.35

        return RouteResponse(
            distanceMeters = totalDistance,
            durationSeconds = durationSeconds,
            pathCoordinates = listOf(request.origin, midpoint, request.destination),
            steps =
                listOf(
                    RouteStep(
                        instruction = "Head toward the campus spine",
                        distanceMeters = midpointDistance,
                        targetLatitude = midpoint.latitude,
                        targetLongitude = midpoint.longitude,
                        maneuverType = ManeuverType.Straight,
                    ),
                    RouteStep(
                        instruction = "Continue to your destination",
                        distanceMeters = finalLegDistance,
                        targetLatitude = request.destination.latitude,
                        targetLongitude = request.destination.longitude,
                        maneuverType = ManeuverType.Arrive,
                    ),
                ),
        )
    }

    fun allPlaces(): List<PlaceSearchResult> = places

    private fun interpolatedMidpoint(start: Coordinate, end: Coordinate): Coordinate =
        Coordinate(
            latitude = (start.latitude + end.latitude) / 2.0 + 0.00008,
            longitude = (start.longitude + end.longitude) / 2.0 - 0.00005,
        )
}
