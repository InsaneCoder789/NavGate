package com.rohanc.navgate.ui.state

import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.InMemoryAppPreferencesStore
import com.rohanc.navgate.data.InMemoryUserPlacesStore
import com.rohanc.navgate.data.local.toAppPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher

/** Synthetic topology confined to test sources. */
internal class BackendTestHarness(private val dispatcher:CoroutineDispatcher) : BackendClock,RegionStore,BackendData {
    var time=1_000L
    override fun elapsedMillis()=time
    override fun epochMillis()=1_800_000_000_000L+time
    val a=GeoPoint(20.0,85.0)
    val b=GeoPoint(20.0,85.001)
    val places=listOf(CampusPlace("a","Test Start",city="Bhubaneswar",point=a),CampusPlace("b","Test Destination",city="Bhubaneswar",point=b),CampusPlace("missing","Unlocated Place",city="Bhubaneswar"))
    var installed=RegionPack(regionId="kiit",version="test-v1",city="Bhubaneswar",
        coverage=Coverage(listOf(GeoPoint(19.99,84.99),GeoPoint(19.99,85.01),GeoPoint(20.01,85.01),GeoPoint(20.01,84.99))),
        source="Synthetic integration fixture",attribution="Test fixture",license="CC0-1.0",generatedAt="2026-10-18",reviewedAt="2026-10-18",
        nodes=listOf(GraphNode("a",a),GraphNode("b",b)),
        edges=listOf(GraphEdge("ab","a","b",listOf(a,b),Access.Allowed,true)),places=places,
        entrances=listOf(Entrance("entry","b","b","Test entry",true)))
    lateinit var backend:NavigationBackend
    val users=InMemoryUserPlacesStore()
    var initializeData: suspend () -> Unit = {}
    override suspend fun initialize()=initializeData()
    override suspend fun active(regionId:String)=installed.takeIf{it.regionId==regionId}
    override suspend fun version(regionId:String,version:String)=active(regionId)?.takeIf{it.version==version}
    override suspend fun activate(pack:RegionPack,sha256:String){installed=pack}
    override suspend fun search(query:String,regionId:String)=if(regionId=="kiit") places.filter{it.title.contains(query,true)} else emptyList()
    override suspend fun place(regionId:String,id:String)=places.find{it.id==id}
    fun viewModel(preferences: com.rohanc.navgate.data.AppPreferencesStore = InMemoryAppPreferencesStore(storedCityMode=CityMode.KiitBeta)):NavGateViewModel=NavGateViewModel(
        backendFactory={scope:CoroutineScope -> NavigationBackend(scope,this,this,this,compute=dispatcher,io=dispatcher,routingProvider=FixtureRouting()).also{backend=it}},
        userPlacesStore=users,appPreferencesStore=preferences,clock=this)
    fun appPlace(index:Int)=places[index].toAppPlace()
}
