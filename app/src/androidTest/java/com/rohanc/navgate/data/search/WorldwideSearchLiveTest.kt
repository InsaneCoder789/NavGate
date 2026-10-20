package com.rohanc.navgate.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.local.toAppPlace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: three bounded calls to the real provider, no account or production writes. */
@RunWith(AndroidJUnit4::class)
class WorldwideSearchLiveTest {
    @Test fun realAndroidHttpSearchFindsMultipleContinentsWithoutCampusBounds()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWorldwideSearch")=="true")
        val local=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=emptyList<CampusPlace>()
            override suspend fun place(regionId:String,id:String):CampusPlace?=null
        }
        val search=WorldwideSearchData(local,PhotonPlaceSearch(),0)
        for((query,expected,latRange,lonRange) in listOf(
            SearchCase("Eiffel Tower Paris","Eiffel",48.0..49.0,2.0..3.0),
            SearchCase("Sydney Opera House","Opera",-34.5..-33.0,150.0..152.0),
            SearchCase("New York","New York",40.0..42.0,-75.0..-72.0),
        )) {
            val page=search.searchPage(query,"kiit")
            assertTrue("Provider unavailable or empty for $query: ${page.notice}",page.places.isNotEmpty())
            val place=page.places.firstOrNull{it.title.contains(expected,true) && it.point?.let{point -> point.latitude in latRange && point.longitude in lonRange}==true}
            assertNotNull("Expected worldwide result for $query",place)
            assertNotNull(place!!.toAppPlace().coordinate)
            assertTrue(place.id.startsWith("osm:"));assertTrue(page.notice!!.contains("Worldwide"))
        }
    }
    private data class SearchCase(val query:String,val expected:String,val latRange:ClosedFloatingPointRange<Double>,val lonRange:ClosedFloatingPointRange<Double>)
}
