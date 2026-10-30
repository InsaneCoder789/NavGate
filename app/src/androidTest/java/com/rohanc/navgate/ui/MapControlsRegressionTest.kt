package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Map
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.components.*
import com.rohanc.navgate.ui.journey.AdaptiveModeSwitch
import com.rohanc.navgate.ui.theme.NavGateTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MapControlsRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shortViewportKeepsWholeButtonsAndOverflowActionsReachable() {
        var selected = -1
        var room by mutableFloatStateOf(52f)
        val actions = (0..4).map { i -> MapControlAction("Map action $i", Icons.Rounded.Map) { selected=i } }
        compose.setContent { NavGateTheme(false) { Box(Modifier.fillMaxSize()) { MapControlRail(actions,room) } } }
        for (height in listOf(52f,112f,172f)) {
            room=height;compose.waitForIdle()
            val rail=compose.onNodeWithTag("mapControlRail").fetchSemanticsNode().boundsInRoot
            val density=compose.density.density
            assertTrue("Clipped rail at $height",rail.height <= height*density+1)
            compose.onNodeWithContentDescription("More map controls").assertIsDisplayed().performClick()
            compose.onNodeWithText("Map action 4").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(4,selected) }
        }
    }
    @Test fun shortModeViewportStillOffersEveryTravelView() {
        var selected="3D"
        compose.setContent { NavGateTheme(false) { Box(Modifier.height(80.dp).width(100.dp)) { AdaptiveModeSwitch("3D",{selected=it},true) } } }
        compose.onNodeWithText("3D").assertIsDisplayed().performClick()
        compose.onNodeWithText("VR navigation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("VR",selected) }
    }
}
