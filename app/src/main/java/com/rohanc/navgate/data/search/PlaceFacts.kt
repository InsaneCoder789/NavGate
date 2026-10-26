package com.rohanc.navgate.data.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns the raw tags a place carries (cuisine, wheelchair, takeaway, payment:upi ...) into labelled facts a person can read. */
object PlaceFacts {
    private val labels = linkedMapOf(
        "brand" to "Brand", "operator" to "Operator", "cuisine" to "Cuisine", "diet:vegetarian" to "Vegetarian", "diet:vegan" to "Vegan", "diet:halal" to "Halal", "takeaway" to "Takeaway", "delivery" to "Delivery",
        "outdoor_seating" to "Outdoor seating", "air_conditioning" to "Air conditioning", "reservation" to "Reservations", "drive_through" to "Drive-through", "internet_access" to "Wi-Fi", "payment:upi" to "UPI accepted",
        "payment:cards" to "Cards accepted", "wheelchair" to "Wheelchair access", "smoking" to "Smoking", "fee" to "Entry fee", "toilets" to "Toilets", "emergency" to "Emergency care", "healthcare:speciality" to "Speciality",
        "beds" to "Beds", "stars" to "Stars", "rooms" to "Rooms", "capacity" to "Capacity", "religion" to "Religion", "denomination" to "Denomination", "start_date" to "Opened", "level" to "Floor", "ref" to "Reference",
        "short_name" to "Short name", "old_name" to "Also called", "alt_name" to "Also called", "description" to "About", "more_phones" to "Other phones", "more_links" to "Other links")

    /** Facts in a stable, readable order. Yes/no values read as "Yes"/"No"; anything with a semicolon list reads as a comma list. */
    fun from(brand: String, cuisine: String, wheelchair: String, extraJson: String): Map<String, String> {
        val raw = linkedMapOf<String, String>()
        if (brand.isNotBlank()) raw["brand"] = brand
        if (cuisine.isNotBlank()) raw["cuisine"] = cuisine
        if (wheelchair.isNotBlank()) raw["wheelchair"] = wheelchair
        if (extraJson.isNotBlank()) runCatching { (Json.parseToJsonElement(extraJson) as JsonObject).forEach { (k, v) -> raw.putIfAbsent(k, v.jsonPrimitive.content) } }
        val out = linkedMapOf<String, String>()
        for ((key, label) in labels) raw[key]?.let { value -> pretty(value).takeIf { it.isNotBlank() }?.let { out.putIfAbsent(label, it) } }
        return out
    }

    private fun pretty(value: String): String = when (value.lowercase()) {
        "yes", "true", "1" -> "Yes"; "no", "false", "0" -> "No"; "limited" -> "Limited"; "wlan", "wifi" -> "Yes"; "free" -> "Free"; "customers" -> "For customers"
        else -> value.replace(';', ',').replace('_', ' ').split(',').joinToString(", ") { it.trim().replaceFirstChar { c -> c.uppercase() } }
    }
}
