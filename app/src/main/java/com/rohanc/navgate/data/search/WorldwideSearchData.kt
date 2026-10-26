package com.rohanc.navgate.data.search

import com.rohanc.navgate.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Online search is global regardless of which offline region is selected. Routing remains a separate contract. */
class WorldwideSearchData(private val local:BackendData,private val worldwide:WorldwidePlaceSearch,private val debounceMillis:Long=600,private val india:WorldwidePlaceSearch?=null,private val location:()->SearchLocation?={null},private val pois:PoiSource?=null):BackendData {
    /** Nearest places of a category: offline index first, the online geocoder outside the indexed region. */
    private suspend fun categoryPage(category:PoiCategory,near:GeoPoint?):PlaceSearchPage {
        if(near==null)return PlaceSearchPage(emptyList(),"Turn on location to see ${category.label.lowercase()} near you.")
        val offline=if(pois?.covers(near)==true)try{pois.nearby(category,near)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}else emptyList()
        if(offline.isNotEmpty())return PlaceSearchPage(offline,"${category.label} near you · © OpenStreetMap contributors")
        val online=worldwide as? NearbyPlaceSearch
        if(online==null)return PlaceSearchPage(emptyList(),"No ${category.label.lowercase()} found nearby.")
        return try {
            val found=online.nearby(category.osmTag,category.query,near.latitude,near.longitude)
            PlaceSearchPage(PlaceSearchRanking.merge(category.query,found,near),"${category.label} near you · © OpenStreetMap contributors · Photon")
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){PlaceSearchPage(emptyList(),"Could not load ${category.label.lowercase()}. Check your connection and try again.")}
    }
    /** What is nearest to [near], of every kind: the offline index when it covers the position, otherwise the nearest restaurants from the online geocoder. */
    /** The nearest hospital, police station and fire station: shown first in Nearby, since they matter most when something goes wrong. */
    suspend fun emergencyNear(near:GeoPoint):List<com.rohanc.navgate.core.CampusPlace> =
        try{pois?.takeIf{it.covers(near)}?.emergency(near,6).orEmpty()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}

    suspend fun nearbyPage(near:GeoPoint?):PlaceSearchPage {
        if(near==null)return PlaceSearchPage(emptyList(),"Turn on location to see what is around you.")
        val offline=if(pois?.covers(near)==true)try{pois.nearbyAll(near,40)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}else emptyList()
        if(offline.isNotEmpty())return PlaceSearchPage(offline,"Nearby · © OpenStreetMap contributors · Overture Maps")
        return categoryPage(PoiCategory.Food,near)
    }
    override suspend fun initialize()=local.initialize()
    override suspend fun place(regionId:String,id:String)=local.place(regionId,id)
    override suspend fun search(query:String,regionId:String)=searchPage(query,regionId).places
    private val cache=SearchCache<PlaceSearchPage>(capacity=24,lifetimeMillis=90_000)

    /** The same question asked again within a minute and a half (the Nearby sheet, a retyped query) is answered from memory, not the network. */
    override suspend fun searchPage(query:String,regionId:String):PlaceSearchPage {
        val where=location()?.usable()
        val key="${query.trim().lowercase()}|$regionId|${where?.let{"${Math.round(it.latitude*200)},${Math.round(it.longitude*200)}"}}"
        cache.get(key)?.let{return it}
        return searchUncached(query,regionId).also{if(it.places.isNotEmpty())cache.put(key,it)}
    }

    private suspend fun searchUncached(query:String,regionId:String):PlaceSearchPage {
        val normalized=query.trim().take(256)
        var nationalUnavailable=false
        var regionalUnavailable=false
        val bias=location()?.usable()
        // "restaurants", "petrol pump", "hospitals" ...: list the nearest ones instead of matching names.
        PoiCategory.fromQuery(normalized)?.let{category->return categoryPage(category,bias)}
        val poiMatches=try{pois?.search(normalized,bias).orEmpty()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){emptyList()}
        val national=try{india?.search(normalized).orEmpty()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){nationalUnavailable=true;emptyList()}
        val regional=try{local.search(normalized,regionId)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){regionalUnavailable=true;emptyList()}
        val installed=PlaceSearchRanking.merge(normalized,regional+national+poiMatches,bias)
        val localNotice=if(nationalUnavailable || regionalUnavailable) " · Some installed search data is unavailable" else ""
        val attribution=if(national.isNotEmpty()) " · India offline: GeoNames, CC BY 4.0" else ""
        if(normalized.length<3)return PlaceSearchPage(installed,if(normalized.isBlank())null else "Type at least 3 characters to search worldwide")
        delay(debounceMillis)
        return try {
            val matches=worldwide.search(normalized)
            // Text relevance precedes local proximity; the selected region never bounds global matches.
            PlaceSearchPage(PlaceSearchRanking.merge(normalized,regional+national+poiMatches+matches,bias),"Worldwide results · © OpenStreetMap contributors · Photon"+attribution+localNotice)
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){PlaceSearchPage(installed,"Worldwide search is unavailable. Check your connection and retry; only installed local matches are shown."+attribution+localNotice)}
    }
}

/** A few recent answers, each good for [lifetimeMillis]; the oldest is dropped first. */
class SearchCache<T : Any>(private val capacity: Int, private val lifetimeMillis: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = object : LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, T>>?) = size > capacity
    }
    @Synchronized fun get(key: String): T? = entries[key]?.takeIf { clock() - it.first <= lifetimeMillis }?.second
    @Synchronized fun put(key: String, value: T) { entries[key] = clock() to value }
}
