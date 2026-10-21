package com.rohanc.navgate.data.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndiaPlaceIndexTest {
    @Test fun nearbyCategorySearchReadsRealIndexAndRejectsStaleBias() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val near = IndiaPlaceIndex(context) { SearchLocation(com.rohanc.navgate.core.GeoPoint(19.07,72.88),0,10.0) }
        val hospitals = near.search("hospitals")
        assertTrue("No nearby hospital source records", hospitals.any { PlaceSearchRanking.category(it.category)=="health" })
        assertTrue(hospitals.all { it.point?.valid==true })
        val stale = IndiaPlaceIndex(context) { SearchLocation(com.rohanc.navgate.core.GeoPoint(19.07,72.88),600_001,10.0) }
        assertEquals(IndiaPlaceIndex(context).search("Mumbai"),stale.search("Mumbai"))
    }

    @Test fun nationalAssetSearchUsesRealCoordinatesAcrossIndia() = runBlocking {
        val index = IndiaPlaceIndex(ApplicationProvider.getApplicationContext())
        for ((name,latitude,longitude) in listOf(
            Triple("Mumbai",19.07,72.88), Triple("Chennai",13.08,80.27),
            Triple("Delhi",28.65,77.23), Triple("Guwahati",26.18,91.75),
            Triple("Srinagar",34.09,74.83), Triple("Port Blair",11.67,92.74)
        )) {
            val results = index.search(name)
            assertTrue("Missing national feature $name", results.any {
                val p=it.point
                p!=null && kotlin.math.abs(p.latitude-latitude)<0.5 && kotlin.math.abs(p.longitude-longitude)<0.5
            })
            assertTrue(results.all { it.id.startsWith("geonames:") && !it.reviewed && it.sourceUrl!=null })
        }
        assertTrue("Mumbai Hindi alias missing",index.search("मुंबई").any { it.title=="Mumbai" })
        assertTrue("Delhi Hindi alias missing",index.search("दिल्ली").any {
            val point=it.point
            point!=null && kotlin.math.abs(point.latitude-28.65)<0.5 && kotlin.math.abs(point.longitude-77.23)<0.5
        })
        assertTrue(index.search("Bombay").any{it.title=="Mumbai"})
        assertTrue(index.search("Gateway of India").any{it.title=="Gateway of India"})
        assertTrue(index.search("Marine Dri").any{it.title=="Marine Drive, Mumbai"})
        assertTrue(index.search("\" OR * --").size<=60)
        assertTrue(index.search(" ").isEmpty())
    }
}
