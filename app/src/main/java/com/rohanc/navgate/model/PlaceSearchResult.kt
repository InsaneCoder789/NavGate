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
    val address: String? = null,
    val email: String? = null,
    val social: String? = null,
    val photo: String? = null,
    val source: String? = null,
    val confidence: Double? = null,
    val facts: Map<String, String> = emptyMap(),
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

/** A line of text as a person should read it: literal "\\n" and real line breaks become spaces, runs of spaces collapse. Data from the places sources sometimes carries them. */
fun String.tidy(): String = replace("\\r\\n", " ").replace("\\n", " ").replace("\\r", " ").replace(Regex("\\s+"), " ").trim()
