package com.rohanc.navgate.data.search

import com.rohanc.navgate.data.search.PlaceSearchRanking.normalize

/**
 * Things people look for near them. [slug] matches the category column of the offline place index; [query] is the canonical
 * search word the buttons send; [osmTag] is the OpenStreetMap tag used for the online fallback outside the indexed region.
 */
enum class PoiCategory(val slug: String, val label: String, val query: String, val osmTag: String, private val words: List<String>) {
    Food("food", "Restaurants", "restaurants", "amenity:restaurant", listOf("restaurant", "restaurants", "food", "cafe", "cafes", "coffee", "eat", "dining", "eateries")),
    Fuel("fuel", "Petrol", "petrol pump", "amenity:fuel", listOf("petrol", "petrol pump", "petrol pumps", "petrol station", "fuel", "gas station", "pump")),
    Health("health", "Hospitals", "hospitals", "amenity:hospital", listOf("hospital", "hospitals", "clinic", "clinics", "doctor", "doctors", "medical")),
    Pharmacy("pharmacy", "Pharmacies", "pharmacy", "amenity:pharmacy", listOf("pharmacy", "pharmacies", "chemist", "medical store", "medicine")),
    Lodging("lodging", "Hotels", "hotels", "tourism:hotel", listOf("hotel", "hotels", "stay", "hostel", "hostels", "lodge")),
    Atm("atm", "ATMs", "atm", "amenity:atm", listOf("atm", "atms", "cash")),
    Bank("bank", "Banks", "banks", "amenity:bank", listOf("bank", "banks")),
    Grocery("grocery", "Groceries", "groceries", "shop:supermarket", listOf("grocery", "groceries", "supermarket", "supermarkets", "kirana")),
    Shopping("shopping", "Malls", "malls", "shop:mall", listOf("mall", "malls", "shopping", "market", "markets")),
    Education("education", "Schools", "schools", "amenity:school", listOf("school", "schools", "college", "colleges", "university", "universities")),
    Transit("transit", "Stations", "stations", "railway:station", listOf("station", "stations", "railway", "railway station", "metro", "bus station", "train")),
    Worship("worship", "Temples", "temples", "amenity:place_of_worship", listOf("temple", "temples", "mosque", "mosques", "church", "churches", "worship")),
    Park("park", "Parks", "parks", "leisure:park", listOf("park", "parks", "garden", "gardens", "beach", "beaches")),
    Fitness("fitness", "Gyms", "gyms", "leisure:fitness_centre", listOf("gym", "gyms", "fitness")),
    Attraction("attraction", "Things to do", "things to do", "tourism:attraction", listOf("attraction", "attractions", "sightseeing", "museum", "museums", "things to do", "tourist")),
    Entertainment("entertainment", "Cinemas", "cinemas", "amenity:cinema", listOf("cinema", "cinemas", "movie", "movies", "theatre", "theatres")),
    Ev("ev", "EV charging", "ev charging", "amenity:charging_station", listOf("ev", "ev charging", "charging", "charging station", "charging stations")),
    Services("services", "Police & post", "police station", "amenity:police", listOf("police", "police station", "post office")),
    Toilets("toilets", "Toilets", "toilets", "amenity:toilets", listOf("toilet", "toilets", "restroom", "restrooms")),
    Parking("parking", "Parking", "parking", "amenity:parking", listOf("parking"));

    companion object {
        /** The category a whole query asks for ("petrol pump", "Hospitals"), or null for an ordinary place name. */
        fun fromQuery(query: String): PoiCategory? {
            val q = normalize(query.trim())
            return entries.firstOrNull { c -> c.words.any { it == q } }
        }
        fun fromSlug(slug: String): PoiCategory? = entries.firstOrNull { it.slug == slug }
    }
}
