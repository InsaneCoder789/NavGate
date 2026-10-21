package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.CampusPlace
import com.rohanc.navgate.core.GeoPoint
import java.text.Normalizer
import java.util.Locale

/** Only local ranking consumes this point; it is never included in a provider request. */
data class SearchLocation(val point: GeoPoint, val ageMillis: Long, val accuracyMeters: Double) {
    fun usable(): GeoPoint? = point.takeIf { it.valid && ageMillis in 0..30_000 && accuracyMeters.isFinite() && accuracyMeters > 0.0 && accuracyMeters <= 100.0 }
}

internal object PlaceSearchRanking {
    fun normalize(value: String): String {
        // Latin accents are optional spelling variants; Indic marks encode actual vowels.
        val folded = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("([\\p{IsLatin}])\\p{M}+"), "$1")
        return Normalizer.normalize(folded, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
    }

    fun category(value: String): String = when (value.uppercase(Locale.ROOT)) {
        "HSP", "HSPC", "CLIN", "HOSPITAL", "CLINIC", "DOCTORS" -> "health"
        "SCH", "SCHC", "SCHN", "UNIV", "SCHOOL", "COLLEGE", "UNIVERSITY" -> "education"
        "RSTN", "RSTP", "RAILWAY", "STATION", "TRAIN_STATION" -> "rail"
        "AIRP", "AIRF", "AIRPORT", "AERODROME" -> "airport"
        "HTL", "HOTEL", "HOSTEL", "GUEST_HOUSE" -> "lodging"
        "REST", "RESTAURANT", "CAFE", "FAST_FOOD", "FOOD" -> "food"
        "TMPL", "MSQE", "CH", "TEMPLE", "MOSQUE", "PLACE_OF_WORSHIP" -> "worship"
        "PPL", "PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLC", "PPLX", "CITY", "TOWN", "VILLAGE", "SUBURB" -> "settlement"
        else -> normalize(value)
    }
    fun requestedCategory(query: String): String? = when(normalize(query)) {
        "hospital", "hospitals", "clinic", "clinics" -> "health"
        "school", "schools", "college", "colleges", "university", "universities" -> "education"
        "restaurant", "restaurants", "cafe", "cafes", "food" -> "food"
        "hotel", "hotels" -> "lodging"
        "airport", "airports" -> "airport"
        "temple", "temples", "mosque", "mosques" -> "worship"
        else -> null
    }
    fun featureCodes(category: String): List<String> = when(category) {
        "health" -> listOf("HSP", "HSPC", "CLIN")
        "education" -> listOf("SCH", "SCHC", "SCHN", "UNIV")
        "food" -> listOf("REST")
        "lodging" -> listOf("HTL")
        "airport" -> listOf("AIRP", "AIRF")
        "worship" -> listOf("TMPL", "MSQE", "CH")
        else -> emptyList()
    }
    private fun quality(query: String, place: CampusPlace): Int {
        val q = normalize(query)
        val title = normalize(place.title)
        return when {
            requestedCategory(query)?.let { it == category(place.category) } == true -> 0
            title == q -> 0
            place.aliases.any { normalize(it) == q } -> 1
            title.startsWith(q) -> 2
            place.aliases.any { normalize(it).startsWith(q) } -> 3
            q.split(' ').all { token -> title.split(' ').any { it.startsWith(token) } } -> 4
            else -> 5
        }
    }
    fun merge(query: String, places: List<CampusPlace>, bias: GeoPoint?): List<CampusPlace> {
        // Reviewed entrances/IDs win duplicate representation, but text quality wins ranking.
        val sourceOrder = places.withIndex().associate { it.value.id to it.index }
        val representatives = places.distinctBy { it.id }.sortedWith(compareByDescending<CampusPlace> { it.reviewed })
        val unique = mutableListOf<CampusPlace>()
        for (candidate in representatives) {
            val duplicate = unique.any { existing ->
                existing.id.substringBefore(':') != candidate.id.substringBefore(':') && !(existing.reviewed && candidate.reviewed) && normalize(existing.title) == normalize(candidate.title) &&
                    category(existing.category).isNotBlank() && category(existing.category) == category(candidate.category) &&
                    existing.point?.let { a -> candidate.point?.let { b -> a.distanceTo(b) <= 60.0 } } == true
            }
            if (!duplicate) unique += candidate
        }
        return unique.sortedWith(compareBy<CampusPlace> { quality(query, it) }
            .thenBy { place -> bias?.let { origin -> place.point?.let { origin.distanceTo(it).coerceAtMost(2_000_000.0) } } ?: 2_000_000.0 }
            .thenByDescending { it.reviewed }.thenBy { if (it.id.startsWith("osm:")) 0 else 1 }
            .thenBy { sourceOrder[it.id] ?: Int.MAX_VALUE }.thenBy { normalize(it.title) }.thenBy { it.id }).take(60)
    }
}
