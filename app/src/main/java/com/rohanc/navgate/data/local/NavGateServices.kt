package com.rohanc.navgate.data.local

import android.content.Context
import com.rohanc.navgate.data.routing.ValhallaRoutingProvider
import com.rohanc.navgate.data.search.SearchLocation
import com.rohanc.navgate.core.LocationFix
import com.rohanc.navgate.data.search.IndiaPlaceIndex
import com.rohanc.navgate.data.search.PhotonPlaceSearch
import com.rohanc.navgate.data.search.WorldwideSearchData
import android.os.SystemClock
import com.rohanc.navgate.core.BackendClock
import com.rohanc.navgate.core.NavigationBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AndroidBackendClock : BackendClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun epochMillis(): Long = System.currentTimeMillis()
}

/** Constructor-injected application services; one Room database and one settings DataStore per process. */
class NavGateServices private constructor(context: Context) {
    val database = NavGateDatabase.get(context)
    val regions = RoomRegionStore(database)
    val users = RoomUserPlacesStore(context,database)
    val manualRoutes = ManualRouteStore(database)
    val preferences = DataStoreAppPreferencesStore(context)
    val repository = LocalNavigationRepository(context,regions,users)
    @Volatile private var latestSearchFix:LocationFix?=null
    fun updateSearchLocation(fix:LocationFix){if(latestSearchFix?.let{fix.elapsedMillis>it.elapsedMillis}!=false)latestSearchFix=fix}
    private val searchLocation:()->SearchLocation?={latestSearchFix?.let{SearchLocation(it.point,AndroidBackendClock.elapsedMillis()-it.elapsedMillis,it.accuracyMeters)}}
    private val indiaIndex = IndiaPlaceIndex(context,location=searchLocation)
    private val poiIndex = com.rohanc.navgate.data.search.PoiIndex(context)
    val searchData = WorldwideSearchData(repository,PhotonPlaceSearch(),india=indiaIndex,location=searchLocation,pois=poiIndex)
    init { CoroutineScope(SupervisorJob()+Dispatchers.IO).launch { runCatching { indiaIndex.warmUp() };runCatching { poiIndex.warmUp() } } }
    private val onlineRouting = ValhallaRoutingProvider(cache=RoomRoadRouteCache(database))
    val mapPacks = com.rohanc.navgate.data.maps.MapPackStore(context)
    val routingData = com.rohanc.navgate.data.routing.offline.RoutingDataStore(context)
    /** Online routing first; the phone's own BRouter engine when the network or the server fails and the area's tiles are installed. */
    val roadRouting: com.rohanc.navgate.core.RoutingProvider = com.rohanc.navgate.data.routing.offline.FailoverRoutingProvider(onlineRouting,com.rohanc.navgate.data.routing.offline.OfflineRoutingProvider.of(routingData) { points -> mapPacks.streetNamesFor(points) })
    private val roadTiles = com.rohanc.navgate.data.maps.RoadTileSource(pack = { z, x, y -> mapPacks.packTile(z, x, y) }, cacheDir = java.io.File(context.cacheDir, "road-tiles"))
    private val roundabouts = com.rohanc.navgate.data.maps.RoundaboutFinder { z, x, y -> roadTiles.tile(z, x, y) }
    /** Every route, from either router, with its roundabouts drawn and spoken the way they are on the ground. */
    val routing: com.rohanc.navgate.core.RoutingProvider = com.rohanc.navgate.core.RoundaboutRoutingProvider(roadRouting) { roundabouts.near(it) }
    val checkpoints = RoomSessionStore(database,regions)
    fun backend(scope: CoroutineScope): NavigationBackend = NavigationBackend(scope,searchData,regions,AndroidBackendClock,checkpoints,routingProvider=routing)

    companion object {
        @Volatile private var instance: NavGateServices? = null
        fun get(context: Context): NavGateServices = instance ?: synchronized(this) {
            instance ?: NavGateServices(context.applicationContext).also { instance=it }
        }
    }
}
