package com.rohanc.navgate.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.*
import com.rohanc.navgate.data.local.*
import com.rohanc.navgate.data.routing.*
import com.rohanc.navgate.ui.state.*
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in (screenshots=true): renders each journey screen with the live map and real routes, and saves PNGs for visual review. */
@RunWith(AndroidJUnit4::class)
class ArRenderSmokeTest {
    @get:Rule val compose=createComposeRule()
    @org.junit.Before fun grantCamera(){InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ApplicationProvider.getApplicationContext<Context>().packageName,android.Manifest.permission.CAMERA)}
    @org.junit.Before fun grantLocation(){InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
        ApplicationProvider.getApplicationContext<Context>().packageName,android.Manifest.permission.ACCESS_FINE_LOCATION)}

    private fun shell(command:String):String {
        val out=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(out).use{String(it.readBytes())}
    }

    /** Opens real ARCore on a planned route and checks it starts, draws, and reports no shader or session errors. */
    @Test fun arStartsDrawsAndReportsItsFrameRate(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("ar")=="true")
        shell("logcat -c")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        val stores=ViewModelStore()
        lateinit var vm:NavGateViewModel
        val start=CampusPlace("start","Colaba Causeway",city="Mumbai",point=GeoPoint(18.9151,72.8230),subtitle="Colaba, Mumbai, Maharashtra, India",category="road")
        val end=CampusPlace("marine","Marine Drive",city="Mumbai",point=GeoPoint(18.9440,72.8236),subtitle="C Ward, Mumbai, Maharashtra, India",category="road")
        val data=object:BackendData {
            override suspend fun initialize()=Unit
            override suspend fun search(query:String,regionId:String)=listOf(start,end).filter{query.isBlank()||it.title.contains(query,true)}
            override suspend fun place(regionId:String,id:String)=listOf(start,end).find{it.id==id}
        }
        try {
            compose.runOnUiThread{
                val regions=RoomRegionStore(db)
                val road=ValhallaRoutingProvider(cache=RoomRoadRouteCache(db))
                val routing=road
                vm=NavGateViewModel({scope->NavigationBackend(scope,data,regions,AndroidBackendClock,RoomSessionStore(db,regions),routingProvider=routing)},InMemoryUserPlacesStore(),
                    InMemoryAppPreferencesStore(onboardingSeen=true,storedCityMode=CityMode.Kiit,initialFrontend=com.rohanc.navgate.data.FrontendPreferences(arIntroSeen=true,vrIntroSeen=true)),estimator=road)
                stores.put("ar",vm)
            }
            compose.setContent{
                @Suppress("ContextCastToActivity") val activity=androidx.compose.ui.platform.LocalContext.current as android.app.Activity
                androidx.compose.runtime.SideEffect{activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                NavGateFrontend(vm,renderMap=true,trackLocation=false,showMotion=true)
            }
            compose.waitUntil(10_000){vm.uiState.value.preferencesLoaded&&!vm.backendState.value.initializing}
            compose.runOnUiThread{vm.onLocationFix(LocationFix(start.point!!,AndroidBackendClock.elapsedMillis(),6.0))}
            compose.onNodeWithText("Search here").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("Marine")
            compose.waitUntil(8_000){vm.uiState.value.places.any{it.id=="marine"}}
            compose.onAllNodes(hasText("Marine Drive",substring=true) and !hasSetTextAction())[0].performClick()
            compose.waitUntil(8_000){vm.uiState.value.focusedPlace?.id=="marine"}
            compose.onNodeWithText("Directions").performClick()
            compose.waitUntil(40_000){vm.backendState.value.session.route!=null||vm.backendState.value.routeFailure!=null}
            assertNull(vm.backendState.value.routeFailure)
            // AR is for walking routes.
            compose.onNodeWithContentDescription("Walking").performClick()
            compose.waitUntil(40_000){vm.backendState.value.session.route?.profile=="walking"&&!vm.backendState.value.planning}
            // Stand on the start of the route, then open AR navigation.
            compose.runOnUiThread{vm.onLocationFix(LocationFix(vm.backendState.value.session.route!!.geometry.first(),AndroidBackendClock.elapsedMillis(),4.0))}
            compose.waitUntil(5_000){vm.uiState.value.currentLocationUsable}
            compose.waitForIdle()
            compose.onNodeWithContentDescription("AR navigation").performClick()
            // The AR layer redraws every frame, so Compose is never "idle" from here on: wait with plain sleeps.
            // ARCore needs a few seconds to start the camera and find the ground; keep the position fresh meanwhile.
            repeat(45){ i ->
                if(i==20)shell("screencap -p /sdcard/Download/navgate-ar-mid.png")
                Thread.sleep(700)
                compose.mainClock.advanceTimeBy(300)
                compose.runOnUiThread{vm.onLocationFix(LocationFix(vm.backendState.value.session.route!!.geometry.first(),AndroidBackendClock.elapsedMillis(),4.0))}
            }
            shell("screencap -p /sdcard/Download/navgate-ar.png")
            val log=shell("logcat -d -s NavGateAR:V AndroidRuntime:E")
            android.util.Log.i("ARSMOKE",log)
            assertFalse("no shader or session failure: $log",log.contains("failed")||log.contains("FATAL"))
            assertTrue("AR session started: $log",log.contains("AR session:"))
        }finally{compose.runOnUiThread{stores.clear()};db.close()}
    }
}
