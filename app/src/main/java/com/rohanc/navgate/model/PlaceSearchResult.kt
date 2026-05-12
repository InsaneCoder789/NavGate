package com.rohanc.navgate.model

data class PlaceSearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val type: PlaceType,
    val city: String? = null,
    val campusLabel: String? = null,
    val category: String? = null,
) {
    val coordinate: Coordinate = Coordinate(latitude, longitude)
}

enum class PlaceType {
    Gate,
    Academic,
    Sports,
    Residential,
    Food,
    Transit,
    Commercial,
    Hospitality,
    Medical,
    Landmark,
}
