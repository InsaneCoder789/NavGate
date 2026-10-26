package com.rohanc.navgate.data.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Mumbai places index shipped in the APK, opened the way the app opens it, on a real phone. */
@RunWith(AndroidJUnit4::class)
class MumbaiPlacesTest {
    private val index = PoiIndex(ApplicationProvider.getApplicationContext(), "mumbai_pois", "Mumbai")
    private val colaba = GeoPoint(18.9220, 72.8347)
    private val bandra = GeoPoint(19.0596, 72.8295)
    private val andheri = GeoPoint(19.1136, 72.8697)

    @Test fun coversMumbaiAndNotOdisha() {
        assertTrue(index.bundled)
        for (p in listOf(colaba, bandra, andheri, GeoPoint(19.2183, 72.9781) /* Thane */)) assertTrue("$p", index.covers(p))
        assertTrue(!index.covers(GeoPoint(20.2961, 85.8245)))
    }

    @Test fun famousPlacesAreFoundByNameWithTheirDetails() = runBlocking {
        for ((query, expected) in listOf("Leopold" to "leopold", "Gateway of India" to "gateway", "Haji Ali" to "haji ali", "Siddhivinayak" to "siddhivinayak", "Taj Mahal Palace" to "taj"))
            assertTrue("$query: ${index.search(query, colaba, 20).map { it.title }}", index.search(query, colaba, 20).any { it.title.lowercase().contains(expected) })
        val leopold = index.search("Leopold", colaba, 10).first { it.title.lowercase().contains("leopold") }
        assertTrue(leopold.point!!.distanceTo(colaba) < 1_500)
    }

    @Test fun everyDayPlacesAreNearInEveryPartOfTheCity() = runBlocking {
        for (area in listOf(colaba, bandra, andheri)) {
            val found = index.nearbyAll(area, 40)
            assertEquals("$area", 40, found.size)
            val distances = found.map { it.point!!.distanceTo(area) }
            assertEquals(distances.sorted(), distances)
            assertTrue(found.all { it.city == "Mumbai" })
            for (category in listOf(PoiCategory.Food, PoiCategory.Health, PoiCategory.Pharmacy, PoiCategory.Bank, PoiCategory.Atm, PoiCategory.Fuel)) assertTrue("$area $category", index.nearby(category, area).size >= 8)
        }
    }

    @Test fun placesCarryTheirPhoneWebsiteAddressAndMore() = runBlocking {
        val clinics = index.search("clinic", bandra, 60)
        assertTrue(clinics.count { it.phone != null } >= 10); assertTrue(clinics.count { it.address != null } >= 20)
        assertTrue(index.search("Starbucks", bandra, 20).any { it.website != null })
        assertTrue(index.nearbyAll(bandra, 40).any { it.source != null && it.confidence != null })
    }

    @Test fun aLandmarkIsFoundByNameFromAnywhereInTheCity() = runBlocking {
        val kandivali = GeoPoint(19.2050, 72.8650)
        val found = index.search("Gateway of India", kandivali, 40)
        android.util.Log.i("MUMBAIQ", found.joinToString(" | ") { "${it.title} @${it.point!!.distanceTo(kandivali).toInt()}m ${it.source}" })
        assertTrue(found.any { it.title == "Gateway of India" && it.category == "monument" })
    }

    @Test fun emergencyListHasAHospitalAPoliceStationAndAFireStation() = runBlocking {
        val kandivali = GeoPoint(19.1971, 72.8727)
        val found = index.emergency(kandivali, 6)
        android.util.Log.i("MUMBAIQ", found.joinToString(" | ") { "${it.title} [${it.category}] ${it.point!!.distanceTo(kandivali).toInt()} m" })
        assertTrue("hospital: ${found.map { it.category }}", found.any { it.category == "hospital" })
        assertTrue("police: ${found.map { it.category }}", found.any { it.category.contains("police") })
        assertTrue("fire: ${found.map { it.category }}", found.any { it.category.contains("fire") })
    }

    @Test fun emergencyIsReachableThroughTheAppsOwnSearchData() = runBlocking {
        val services = com.rohanc.navgate.data.local.NavGateServices.get(ApplicationProvider.getApplicationContext())
        val found = services.searchData.emergencyNear(GeoPoint(19.1971, 72.8727))
        android.util.Log.i("MUMBAIQ", "services emergency: ${found.map { it.title }}")
        assertTrue("through the services: $found", found.isNotEmpty())
    }
}
