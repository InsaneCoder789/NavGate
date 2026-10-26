package com.rohanc.navgate.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinKindTest {
    @Test fun `a hospital gets the hospital icon and a restaurant the restaurant icon`() {
        assertEquals(PinKind.Hospital, PinKind.of("health", "hospital")); assertEquals(PinKind.Restaurant, PinKind.of("food", "restaurant"))
        assertEquals(PinKind.Clinic, PinKind.of("health", "dental clinic")); assertEquals(PinKind.Fuel, PinKind.of("fuel", "gas station")); assertEquals(PinKind.Pharmacy, PinKind.of("pharmacy", "pharmacy"))
    }
    @Test fun `police and fire stations are found whatever their category`() {
        assertEquals(PinKind.Police, PinKind.of("services", "police station")); assertEquals(PinKind.Fire, PinKind.of("government", "fire_station"))
    }
    @Test fun `every emergency subtype maps to an emergency kind`() {
        for (s in PinKind.EMERGENCY_SUBTYPES) assertTrue(s, PinKind.of("health", s) in listOf(PinKind.Hospital, PinKind.Police, PinKind.Fire))
    }
    @Test fun `unknown things fall back to the plain pin`() = assertEquals(PinKind.Other, PinKind.of("mystery", "thing"))
    @Test fun `an office is an office`() = assertEquals(PinKind.Office, PinKind.of("business", "consulting"))
    @Test fun `every kind has a distinct slug`() = assertEquals(PinKind.entries.size, PinKind.entries.map { it.slug }.toSet().size)
}

class PinKindTaxonomyTest {
    @Test fun `subtypes of the shipped indexes land on the right kind`() {
        val expected = mapOf(
            "food" to "cafe" to PinKind.Cafe, "food" to "bakery" to PinKind.Bakery, "food" to "indian restaurant" to PinKind.Restaurant, "food" to "bar" to PinKind.Bar,
            "food" to "ice cream shop" to PinKind.Sweets, "shopping" to "jewelry store" to PinKind.Jewelry, "shopping" to "mobile phone store" to PinKind.Electronics,
            "shopping" to "shopping mall" to PinKind.Mall, "shopping" to "clothing store" to PinKind.Shop, "shopping" to "auto dealer" to PinKind.AutoService,
            "fitness" to "gym" to PinKind.Fitness, "fitness" to "cricket ground" to PinKind.Sports, "entertainment" to "movie theater" to PinKind.Entertainment,
            "attraction" to "historic site" to PinKind.Landmark, "attraction" to "museum" to PinKind.Museum, "education" to "tutoring service" to PinKind.Coaching,
            "education" to "college university" to PinKind.College, "education" to "preschool" to PinKind.School, "services" to "beauty salon" to PinKind.Salon,
            "services" to "government office" to PinKind.Government, "business" to "real estate agent" to PinKind.RealEstate, "transit" to "train station" to PinKind.Transit,
            "transit" to "airport" to PinKind.Airport, "transit" to "tire shop" to PinKind.AutoService, "transit" to "travel agent" to PinKind.Travel,
            "worship" to "mosque" to PinKind.Mosque, "worship" to "hindu temple" to PinKind.Temple, "worship" to "church" to PinKind.Church, "lodging" to "resort" to PinKind.Hotel,
            "parking" to "parking" to PinKind.Parking, "toilets" to "toilets" to PinKind.Toilets, "ev" to "charging station" to PinKind.Charging, "atm" to "atm" to PinKind.Atm,
        )
        for ((key, kind) in expected) assertEquals("${key.first}/${key.second}", kind, PinKind.of(key.first, key.second))
    }
    @Test fun `a barber is a salon, not a bar`() = assertEquals(PinKind.Salon, PinKind.of("services", "barber shop"))
    @Test fun `a petrol station is fuel even though it is a station`() = assertEquals(PinKind.Fuel, PinKind.of("fuel", "gas station"))
}
