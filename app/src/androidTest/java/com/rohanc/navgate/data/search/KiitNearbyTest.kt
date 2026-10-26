package com.rohanc.navgate.data.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.GeoPoint
import com.rohanc.navgate.data.local.NavGateServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The KIIT directory behind Nearby, from the catalogue installed in the app. */
@RunWith(AndroidJUnit4::class)
class KiitNearbyTest {
    private val repository = NavGateServices.get(ApplicationProvider.getApplicationContext()).repository
    private val campus6 = GeoPoint(20.3527, 85.8200)

    @Test fun theWholeDirectoryIsThereNearestFirst() = runBlocking {
        val places = repository.regionPlacesNear("kiit", campus6, 500)
        assertTrue("${places.size} places with a position", places.size >= 240)
        val distances = places.map { it.point!!.distanceTo(campus6) }
        assertEquals(distances.sorted(), distances)
        assertTrue(places.none { it.title.contains("http") || it.subtitle.contains("google", true) })
    }

    @Test fun hostelsCafeteriasSportsAndMedicalAreAllIncluded() = runBlocking {
        val categories = repository.regionPlacesNear("kiit", campus6, 500).map { it.category }.toSet()
        for (needed in listOf("Boys Hostel", "Girls Hostel", "Cafeteria", "Sports", "KIMS", "KISS", "Guest House", "KIIT")) assertTrue("$needed in $categories", needed in categories)
    }

    @Test fun unassignedPlacesAreKeptAndCampusOnesKnowTheirCampus() = runBlocking {
        val places = repository.regionPlacesNear("kiit", campus6, 500)
        assertTrue(places.any { it.campus == null }); assertTrue(places.any { it.campus == "Campus 3" } && places.any { it.campus == "Campus 25" })
    }
}
