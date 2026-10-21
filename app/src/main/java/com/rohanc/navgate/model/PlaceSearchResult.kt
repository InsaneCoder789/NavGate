package com.rohanc.navgate.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaceSearchResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double?,
    val longitude: Double?,
    val type: PlaceType,
    val city: String? = null,
    val campusLabel: String? = null,
    val category: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val hours: String? = null,
    val detail: String? = null,
    val sourceUrl: String? = null,
) {
    val coordinate: Coordinate?
        get() = if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) Coordinate(latitude, longitude) else null
}

@Serializable
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
