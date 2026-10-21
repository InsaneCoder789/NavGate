package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorldwideSearchDataTest {
    private val localPlace=CampusPlace("campus","Campus library",city="Bhubaneswar")
    private val global=CampusPlace("osm:n:1","New York",city="New York",point=GeoPoint(40.7,-74.0))
    private val local=object:BackendData {
        override suspend fun initialize()=Unit
        override suspend fun search(query:String,regionId:String)=listOf(localPlace)
        override suspend fun place(regionId:String,id:String)=localPlace.takeIf{it.id==id}
    }
    @Test fun `worldwide search works regardless of the selected offline region`()=runTest {
        var calls=0
        val data=WorldwideSearchData(local,WorldwidePlaceSearch{calls++;listOf(global)},0)
        for(region in listOf("kiit","mumbai","uninstalled")){
            val page=data.searchPage("New York",region)
            assertEquals(global,page.places.first());assertTrue(page.notice!!.contains("Worldwide"))
        }
        assertEquals(3,calls)
    }
    @Test fun `offline results are explicitly labelled and short queries make no network request`()=runTest {
        var calls=0
        val data=WorldwideSearchData(local,WorldwidePlaceSearch{calls++;throw IOException("offline")},0)
        assertEquals(listOf(localPlace),data.searchPage("NY","kiit").places);assertEquals(0,calls)
        val offline=data.searchPage("New York","kiit")
        assertEquals(listOf(localPlace),offline.places);assertTrue(offline.notice!!.contains("unavailable"));assertEquals(1,calls)
    }
    @Test fun `cancellation is propagated instead of being reported as an offline result`()=runTest {
        val data=WorldwideSearchData(local,WorldwidePlaceSearch{throw CancellationException()},0)
        try{data.searchPage("Paris","kiit");fail("Cancellation must propagate")}catch(_:CancellationException){}
    }
    @Test fun `national matches remain available offline for any region`()=runTest {
        val india=CampusPlace("geonames:1275339","Mumbai",city="Mumbai",aliases=listOf("Bombay"),point=GeoPoint(19.07283,72.88261))
        val data=WorldwideSearchData(local,WorldwidePlaceSearch{throw IOException("offline")},0,WorldwidePlaceSearch{listOf(india)})
        for(region in listOf("kiit","mumbai","uninstalled")) {
            val page=data.searchPage("Bombay",region)
            assertEquals(india,page.places.first())
            assertTrue(page.notice!!.contains("GeoNames"))
        }
    }
    @Test fun `local proximity never changes provider query and stale fixes do not reorder results`()=runTest {
        val far=CampusPlace("osm:n:1","Central",city="Delhi",point=GeoPoint(28.6,77.2),category="hospital")
        val near=CampusPlace("osm:n:2","Central",city="Mumbai",point=GeoPoint(19.07,72.88),category="hospital")
        var requested=""
        var fix:SearchLocation?=null
        val provider=WorldwidePlaceSearch { requested=it;listOf(far,near) }
        val data=WorldwideSearchData(local,provider,0,location={fix})
        assertEquals(far,data.searchPage("Central","kiit").places.first())
        fix=SearchLocation(GeoPoint(19.07,72.88),0,10.0)
        assertEquals(near,data.searchPage("Central","kiit").places.first())
        assertEquals("Central",requested)
        fix=SearchLocation(GeoPoint(19.07,72.88),30_001,10.0)
        assertEquals(far,data.searchPage("Central","kiit").places.first())
    }
    @Test fun `missing national data is visible while online results still work`()=runTest {
        val data=WorldwideSearchData(local,WorldwidePlaceSearch { listOf(global) },0,
            WorldwidePlaceSearch { throw IOException("bad local database") })
        val page=data.searchPage("New York","kiit")
        assertEquals(global,page.places.first())
        assertTrue(page.notice!!.contains("installed search data is unavailable"))
    }

}
