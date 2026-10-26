package com.rohanc.navgate.data.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Odisha places index shipped in the APK, opened the way the app opens it, on a real phone. */
@RunWith(AndroidJUnit4::class)
class OdishaPlacesTest {
    private val index = PoiIndex(ApplicationProvider.getApplicationContext(), "odisha_pois", "Odisha")
    private val bhubaneswar = GeoPoint(20.2961, 85.8245)
    private val cuttack = GeoPoint(20.4625, 85.8828)
    private val puri = GeoPoint(19.8135, 85.8312)

    @Test fun theIndexIsInTheApkAndCoversTheState() {
        assertTrue(index.bundled)
        for (city in listOf(bhubaneswar, cuttack, puri, GeoPoint(21.5, 83.9) /* Sambalpur side */, GeoPoint(19.3, 84.8) /* Berhampur */)) assertTrue("$city", index.covers(city))
        assertTrue(!index.covers(GeoPoint(19.07, 72.88)))                       // Mumbai belongs to the other index
    }

    @Test fun famousPlacesAreFoundByNameOffline() = runBlocking {
        for ((query, expected) in listOf("Lingaraj" to "lingaraj", "Nandankanan" to "nandankanan", "Konark" to "konark", "Jagannath" to "jagannath", "Capital Hospital" to "capital"))
            assertTrue("$query: ${index.search(query, bhubaneswar, 20).map { it.title }}", index.search(query, bhubaneswar, 20).any { it.title.lowercase().contains(expected) })
    }

    @Test fun nearestFirstAndAllTheCategoriesAnswerInTheCities() = runBlocking {
        for (city in listOf(bhubaneswar, cuttack, puri)) for (category in listOf(PoiCategory.Food, PoiCategory.Health, PoiCategory.Fuel, PoiCategory.Lodging, PoiCategory.Education, PoiCategory.Bank)) {
            val found = index.nearby(category, city)
            assertTrue("$city $category: ${found.size}", found.size >= 5)
            val distances = found.map { it.point!!.distanceTo(city) }
            assertEquals(distances.sorted(), distances)
            assertTrue(found.all { it.city == "Odisha" })
        }
    }

    @Test fun kiitAndItsNeighbourhoodHaveARealDirectory() = runBlocking {
        val kiit = GeoPoint(20.3527, 85.8200)
        assertTrue(index.search("KIIT", kiit, 40).size >= 10)
        assertTrue(index.nearby(PoiCategory.Food, kiit, 60).size >= 30)
    }

    @Test fun placesCarryTheirPhoneWebsiteHoursAndMore() = runBlocking {
        val around = index.nearbyAll(bhubaneswar, 40)
        assertTrue(around.size >= 30)
        assertTrue(around.none { it.title.isBlank() || it.point == null })
        val pizza = index.search("Pizza Hut", bhubaneswar, 10).first { it.phone != null && it.website != null }
        assertTrue(pizza.address!!.contains("Bhubaneswar", true)); assertTrue(pizza.facts.containsKey("Cuisine") || pizza.facts.containsKey("Brand"))
        val everything = index.search("hospital", bhubaneswar, 40)
        assertTrue(everything.any { it.phone != null } && everything.any { it.email != null || it.website != null })
        assertTrue(index.search("Primary", bhubaneswar, 200).plus(index.search("Junction", bhubaneswar, 200)).any { it.photo != null })
        assertTrue(around.none { it.category in listOf("business", "locality", "parking", "toilets") })
    }
}
