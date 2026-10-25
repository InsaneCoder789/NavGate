package com.rohanc.navgate.model

import kotlinx.serialization.Serializable

@Serializable
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
    UTurn,
    Roundabout,
    RoundaboutExit,
    SharpLeft,
    SharpRight,
    KeepLeft,
    KeepRight,
    Merge,
    Arrive,
}


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

@Serializable
data class RouteHistoryEntry(
    val destinationId: String,
    val destinationTitle: String,
    val travelMode: TravelProfile,
    val recordedAtEpochMillis: Long,
    val etaSeconds: Double,
    val tripId: String = "",
    val status: String = "Legacy",
    val regionId: String? = null,
    val graphVersion: String? = null,
    val estimatedDistanceMeters: Double? = null,
    val finishedAtEpochMillis: Long? = null,
)
