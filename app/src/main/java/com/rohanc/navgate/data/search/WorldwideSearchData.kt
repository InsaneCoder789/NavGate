package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Online search is global regardless of which offline region is selected. Routing remains a separate contract. */
class WorldwideSearchData(private val local:BackendData,private val worldwide:WorldwidePlaceSearch,private val debounceMillis:Long=600,private val india:WorldwidePlaceSearch?=null,private val location:()->SearchLocation?={null}):BackendData {
    override suspend fun initialize()=local.initialize()
    override suspend fun place(regionId:String,id:String)=local.place(regionId,id)
    override suspend fun search(query:String,regionId:String)=searchPage(query,regionId).places
    override suspend fun searchPage(query:String,regionId:String):PlaceSearchPage {
        val normalized=query.trim().take(256)
        var nationalUnavailable=false
        var regionalUnavailable=false
        val bias=location()?.usable()
        val national=try{india?.search(normalized).orEmpty()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){nationalUnavailable=true;emptyList()}
        val regional=try{local.search(normalized,regionId)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){regionalUnavailable=true;emptyList()}
        val installed=PlaceSearchRanking.merge(normalized,regional+national,bias)
        val localNotice=if(nationalUnavailable || regionalUnavailable) " · Some installed search data is unavailable" else ""
        val attribution=if(national.isNotEmpty()) " · India offline: GeoNames, CC BY 4.0" else ""
        if(normalized.length<3)return PlaceSearchPage(installed,if(normalized.isBlank())null else "Type at least 3 characters to search worldwide")
        delay(debounceMillis)
        return try {
            val matches=worldwide.search(normalized)
            // Text relevance precedes local proximity; the selected region never bounds global matches.
            PlaceSearchPage(PlaceSearchRanking.merge(normalized,regional+national+matches,bias),"Worldwide results · © OpenStreetMap contributors · Photon"+attribution+localNotice)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){PlaceSearchPage(installed,"Worldwide search is unavailable. Check your connection and retry; only installed local matches are shown."+attribution+localNotice)}
    }
}
