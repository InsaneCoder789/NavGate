package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Online search is global regardless of which offline region is selected. Routing remains a separate contract. */
class WorldwideSearchData(private val local:BackendData,private val worldwide:WorldwidePlaceSearch,private val debounceMillis:Long=600):BackendData {
    override suspend fun initialize()=local.initialize()
    override suspend fun place(regionId:String,id:String)=local.place(regionId,id)
    override suspend fun search(query:String,regionId:String)=searchPage(query,regionId).places
    override suspend fun searchPage(query:String,regionId:String):PlaceSearchPage {
        val normalized=query.trim().take(256)
        val installed=try{local.search(normalized,regionId)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}
        if(normalized.length<3)return PlaceSearchPage(installed,if(normalized.isBlank())null else "Type at least 3 characters to search worldwide")
        delay(debounceMillis)
        return try {
            val matches=worldwide.search(normalized)
            // Provider ranking comes first; the selected region never limits worldwide matches.
            PlaceSearchPage((matches+installed).distinctBy{it.id},"Worldwide results · © OpenStreetMap contributors · Photon")
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){PlaceSearchPage(installed,"Worldwide search is unavailable. Check your connection and retry; only installed local matches are shown.")}
    }
}
