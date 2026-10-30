package com.rohanc.navgate.ui.journey

import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import org.junit.Assert.*
import org.junit.Test

class SearchPresentationRegressionTest {
    private fun place(id: String, lat: Double? = 18.92188, category: String? = "monument", subtitle: String = "Monument · Mumbai") =
        PlaceSearchResult(id, "Gateway of India", subtitle, lat, 72.83468, PlaceType.Landmark, category = category)

    @Test fun `nearby GeoNames and OSM monument representations show once`() {
        val names = place("geonames:1", category = null, subtitle = "Monument · India")
        val osm = place("osm:2", lat = 18.92197)
        assertEquals(listOf(names), distinctSearchPlaces(listOf(names, osm)))
    }

    @Test fun `distant monuments and neighbouring businesses are retained`() {
        val near = place("osm:1")
        val distant = place("osm:2", lat = 19.0)
        assertEquals(2, distinctSearchPlaces(listOf(near, distant)).size)
        val restaurantA = place("osm:3", category = "restaurant", subtitle = "Restaurant")
        val restaurantB = place("osm:4", category = "restaurant", subtitle = "Restaurant")
        assertEquals(2, distinctSearchPlaces(listOf(restaurantA, restaurantB)).size)
    }

    @Test fun `unknown coordinates and categories are not guessed into duplicates`() {
        assertEquals(2, distinctSearchPlaces(listOf(place("a", lat = null), place("b", lat = null))).size)
        assertEquals(2, distinctSearchPlaces(listOf(place("a", category = null, subtitle = "India"), place("b", category = null, subtitle = "India"))).size)
    }

    @Test fun `unrelated brand tag is not presented as monument identity`() {
        val facts = mapOf("Brand" to "Gateway Of India Bali", "Opened" to "1924", "Wheelchair access" to "Yes")
        assertEquals(facts - "Brand", displayPlaceFacts(place("osm:1").copy(facts = facts)))
        assertEquals(facts, displayPlaceFacts(place("osm:2", category = "restaurant").copy(facts = facts)))
    }
}
