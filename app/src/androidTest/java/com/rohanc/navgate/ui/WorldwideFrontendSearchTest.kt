package com.rohanc.navgate.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.*
import com.rohanc.navgate.data.search.*
import com.rohanc.navgate.ui.state.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldwideFrontendSearchTest {
    @get:Rule val compose=createComposeRule()
    @Test fun searchButtonShowsWorldwideResultAndSelectionReachesPlaceDetails() {
        val worldPlace=CampusPlace("osm:w:5013364","Eiffel Tower","Paris, France",city="Paris",point=GeoPoint(48.8584,2.2945))
        val local=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=emptyList<CampusPlace>()
            override suspend fun place(regionId:String,id:String):CampusPlace?=null
        }
        val regions=object:RegionStore {
            override suspend fun active(regionId:String):RegionPack?=null
            override suspend fun version(regionId:String,version:String):RegionPack?=null
            override suspend fun activate(pack:RegionPack,sha256:String)=Unit
        }
        val clock=object:BackendClock {override fun elapsedMillis()=1000L;override fun epochMillis()=1_800_000_000_000L}
        val data=WorldwideSearchData(local,WorldwidePlaceSearch{listOf(worldPlace)},0)
        val store=ViewModelStore()
        lateinit var vm:NavGateViewModel
        compose.runOnUiThread{
            vm=NavGateViewModel({scope->NavigationBackend(scope,data,regions,clock,routingProvider=com.rohanc.navgate.testing.FixtureRouting())},InMemoryUserPlacesStore(),InMemoryAppPreferencesStore(onboardingSeen=true,storedCityMode=CityMode.Kiit),clock)
            store.put("worldwide-search",vm)
        }
        try {
            compose.setContent{NavGateFrontend(injectedViewModel=vm,renderMap=false,trackLocation=false)}
            compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded}
            compose.onNodeWithText("Where to?").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Eiffel Tower")
            compose.waitUntil(10_000){vm.uiState.value.places.any{it.id==worldPlace.id}}
            compose.onNode(hasText("Eiffel Tower",substring=false) and !hasSetTextAction()).performClick()
            compose.waitUntil(5_000){vm.uiState.value.focusedPlace?.id==worldPlace.id}
            assertEquals(48.8584,vm.uiState.value.focusedPlace!!.latitude!!,0.00001)
            compose.onNodeWithText("Paris, France",substring=false).assertExists()
            compose.onNodeWithContentDescription("Save").performClick()
            compose.waitUntil(5_000){vm.uiState.value.savedPlaces.any{it.id==worldPlace.id}}
        } finally {compose.runOnUiThread{store.clear()}}
    }
}
