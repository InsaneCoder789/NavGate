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
}
