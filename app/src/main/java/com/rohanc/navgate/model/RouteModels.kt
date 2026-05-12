package com.rohanc.navgate.model

enum class TravelProfile {
    Walking,
    Driving,
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
    val destinationPlaceId: String? = null,
    val cityHint: String? = null,
)

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val maneuverType: ManeuverType,
    val durationSeconds: Double = 0.0,
    val bearingStart: Double = 0.0,
    val bearingEnd: Double = 0.0,
    val streetName: String = "",
) {
    val target: Coordinate = Coordinate(targetLatitude, targetLongitude)
}

data class RouteResponse(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val pathCoordinates: List<Coordinate>,
    val steps: List<RouteStep>,
    val travelMode: String = "walking",
    val routeSource: String = "backend",
    val routeConfidence: String = "medium",
    val supportsAr: Boolean = true,
    val warnings: List<String> = emptyList(),
)
