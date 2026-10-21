package com.rohanc.navgate.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.local.*
import com.rohanc.navgate.ui.theme.NavGateTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManualRouteEditorTest {
    @get:Rule val compose=createComposeRule()
    @Test fun createNamedPinsAcrossPickerSaveReopenRenameReorderDelete() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NavGateDatabase::class.java).build()
        val store=ManualRouteStore(db)
        try {
            compose.setContent {
                var picking by remember{mutableStateOf(false)}
                var pins by remember{mutableStateOf<List<RoutePin>>(emptyList())}
                var point by remember{mutableStateOf<GeoPoint?>(null)}
                val holder=rememberSaveableStateHolder()
                NavGateTheme(dark=true) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if(picking)Button(onClick={point=GeoPoint(18.92188,72.83468);picking=false}){Text("Select test map coordinate")}
                        else holder.SaveableStateProvider("editor"){
                            ManualRoutePanel(store,pins,{pins=it},point,{picking=true},{point=null},{})
                        }
                    }
                }
            }
            fun field(label:String,value:String){compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo().performTextReplacement(value)}
            fun click(text:String){compose.onNodeWithText(text).performScrollTo().performClick()}
            field("Route name","Mumbai waterfront")
            field("Pin label","Gateway of India")
            click("Pin location on map")
            click("Select test map coordinate")
            compose.onNode(hasText("Route name") and hasSetTextAction()).assertTextContains("Mumbai waterfront")
            compose.onNode(hasText("Pin label") and hasSetTextAction()).assertTextContains("Gateway of India")
            click("Add labelled pin")
            field("Label for pin 1","Gateway label draft")
            click("Pin location on map")
            click("Select test map coordinate")
            compose.onNode(hasText("Label for pin 1") and hasSetTextAction()).assertTextContains("Gateway label draft")
            click("Apply label 1")
            field("Pin label","Marine Drive · मुंबई")
            field("Latitude","18.9341");field("Longitude","72.82365")
            click("Add labelled pin")
            click("Save manual route")
            compose.waitUntil(10_000){runBlocking{store.list().size==1}}
            assertEquals(2,runBlocking{store.list().single().pins.size})
            click("New manual route")
            click("Open Mumbai waterfront · 2 pins · walking")
            click("Move up 2")
            field("Label for pin 1","Sunset stop")
            click("Apply label 1")
            click("Driving itinerary")
            click("Save manual route")
            compose.waitUntil(10_000){runBlocking{store.list().single().mode=="driving"}}
            val saved=runBlocking{store.list().single()}
            assertEquals("Sunset stop",saved.pins.first().label)
            assertEquals(18.9341,saved.pins.first().point.latitude,0.000001)
            assertTrue(saved.straightLineMeters in 1750.0..1850.0)
            click("Delete Mumbai waterfront")
            compose.waitUntil(10_000){runBlocking{store.list().isEmpty()}}
        } finally {db.close()}
    }
}
