package com.rohanc.navgate.model

enum class TravelProfile {
    Walking,
}

enum class ManeuverType {
    Start,
    Straight,
    SlightLeft,
    Left,
    SlightRight,
    Right,
    Arrive,
}

data class RouteRequest(
    val origin: Coordinate,
    val destination: Coordinate,
    val profile: TravelProfile = TravelProfile.Walking,
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val maneuverType: ManeuverType,
) {
    val target: Coordinate = Coordinate(targetLatitude, targetLongitude)
}

data class RouteResponse(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val pathCoordinates: List<Coordinate>,
    val steps: List<RouteStep>,
)
