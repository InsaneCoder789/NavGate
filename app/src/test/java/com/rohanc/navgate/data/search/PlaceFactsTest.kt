package com.rohanc.navgate.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFactsTest {
    @Test fun `tags become labelled facts in a stable order`() {
        val facts = PlaceFacts.from("Domino's", "pizza;italian", "yes", """{"takeaway":"yes","delivery":"no","internet_access":"wlan","payment:upi":"yes","operator":"Jubilant FoodWorks","diet:vegetarian":"yes"}""")
        assertEquals(listOf("Brand", "Operator", "Cuisine", "Vegetarian", "Takeaway", "Delivery", "Wi-Fi", "UPI accepted", "Wheelchair access"), facts.keys.toList())
        assertEquals("Pizza, Italian", facts["Cuisine"]); assertEquals("Yes", facts["Wi-Fi"]); assertEquals("No", facts["Delivery"])
    }
    @Test fun `nothing known gives nothing and a broken extra field is ignored`() {
        assertTrue(PlaceFacts.from("", "", "", "").isEmpty())
        assertEquals(mapOf("Cuisine" to "Indian"), PlaceFacts.from("", "indian", "", "{not json"))
    }
    @Test fun `unknown tags are not shown`() {
        assertTrue(PlaceFacts.from("", "", "", """{"survey:date":"2021","fixme":"check"}""").isEmpty())
    }
}
