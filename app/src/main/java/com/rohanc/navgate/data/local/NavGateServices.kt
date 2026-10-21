package com.rohanc.navgate.data.local

import android.content.Context
import com.rohanc.navgate.data.routing.HybridRoutingProvider
import com.rohanc.navgate.data.routing.ValhallaRoutingProvider
import com.rohanc.navgate.data.search.SearchLocation
import com.rohanc.navgate.core.LocationFix
import com.rohanc.navgate.data.search.IndiaPlaceIndex
import com.rohanc.navgate.data.search.PhotonPlaceSearch
import com.rohanc.navgate.data.search.WorldwideSearchData
import android.os.SystemClock
import com.rohanc.navgate.core.BackendClock
import com.rohanc.navgate.core.NavigationBackend
import com.rohanc.navgate.core.RegionPackInstaller
import com.rohanc.navgate.core.SignedPackImport
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
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
    val roadRouting = ValhallaRoutingProvider(cache=RoomRoadRouteCache(database))
    val routing = HybridRoutingProvider(regions,roadRouting)
    val checkpoints = RoomSessionStore(database,regions)
    // Only a key shipped by the app publisher is trusted. Imported files cannot choose their own trust root.
    private val updateKey = runCatching {
        context.assets.open("regions/trusted-update-key.der").use { input ->
            val bytes=input.readBytes()
            require(bytes.size<=4096)
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
        }
    }.getOrNull()
    val packImports = SignedPackImport(RegionPackInstaller(regions,updateKey))
    val regionDocuments = RegionDocumentImporter(packImports,context.contentResolver)
    fun backend(scope: CoroutineScope): NavigationBackend = NavigationBackend(scope,searchData,regions,AndroidBackendClock,checkpoints,routingProvider=routing)

    companion object {
        @Volatile private var instance: NavGateServices? = null
        fun get(context: Context): NavGateServices = instance ?: synchronized(this) {
            instance ?: NavGateServices(context.applicationContext).also { instance=it }
        }
    }
}
