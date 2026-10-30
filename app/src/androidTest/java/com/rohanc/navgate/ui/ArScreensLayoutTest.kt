package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.ui.ar.*
import com.rohanc.navgate.ui.journey.LiveBubble
import com.rohanc.navgate.ui.journey.LiveCameraLayer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every AR and Live (VR) overlay drawn at the screens it will meet: narrow and wide, small to very large text, the turn label at every spot across the picture,
 * long street names, the road pointer on either edge, every status card. Nothing may leave the screen, touch its edge or sit over its neighbour.
 */
@RunWith(AndroidJUnit4::class)
class ArScreensLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val widths = listOf(320, 360, 411)
    private val fontScales = listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f)
    private val density = 2.75f
    private val margin get() = 8 * density          // nothing closer to the edge than 8 dp

    private fun rootBounds() = compose.onRoot().fetchSemanticsNode().boundsInRoot
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun all(tag: String): List<Rect> = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
    private fun inside(name: String, r: Rect, root: Rect) = assertTrue("$name $r leaves the screen $root (margin ${margin}px)", r.left >= root.left + margin - 1 && r.right <= root.right - margin + 1 && r.top >= root.top && r.bottom <= root.bottom)

    private var width by mutableIntStateOf(360); private var scale by mutableFloatStateOf(1f)
    private var spot by mutableFloatStateOf(0.5f); private var title by mutableStateOf("Left")
    private var angle by mutableFloatStateOf(80f); private var message by mutableStateOf("Aligning the world"); private var beaconX by mutableFloatStateOf(0.5f); private var beaconY by mutableFloatStateOf(0.4f)
    private var instruction by mutableStateOf("Left")

    private fun host(content: @Composable BoxScope.() -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(density, scale)) { Box(Modifier.width(width.dp).height(780.dp)) { content() } }
    }

    @Test fun liveBubbleStaysInsideAtEverySpotAndTextSize() {
        host { LiveCameraLayer(LiveBubble(title, "128 m", "Left", spot), "6:18 pm", "13 min", "1.1 km", { m, _ -> Box(m) }) }
        for (w in widths) for (s in fontScales) for (x in listOf(0f, 0.14f, 0.5f, 0.86f, 1f)) for (name in listOf("Left", "Dr. Shyama Prasad Mukherjee Road Extension", "Take the 3rd exit")) {
            width = w; scale = s; spot = x; title = name; compose.waitForIdle()
            val root = rootBounds(); val b = bounds("liveBubble"); val body = bounds("liveBubbleBody"); val icon = bounds("liveBubbleIcon")
            inside("bubble [$w dp, x$s, spot $x, \"$name\"]", b, root); inside("bubble body", body, root)
            assertTrue("icon leaves its bubble: $icon in $body", icon.left >= body.left && icon.right <= body.right && icon.top >= body.top && icon.bottom <= body.bottom)
            val trip = bounds("arTripCard"); inside("trip card", trip, root)
            assertTrue("the bubble overlaps the trip card at $w dp x$s", b.bottom <= trip.top)
        }
    }

    @Test fun roadPointerStatusAndBeaconStayOnScreen() {
        val statuses = listOf("Aligning the world", "Lining up with the road", "Too dark for AR", "Moving too fast", "Point the camera at the road", "Getting your bearings", "The camera is in use by another app")
        host { ArTextLayer(ArHud(beaconX, beaconY, message, false, false, 30, 60, roadAngleDeg = angle), "Left", null, 120.0, ArFpsMode.Auto) }
        for (w in widths) for (s in fontScales) for (a in listOf(-80f, 80f)) for (msg in statuses) for (turn in listOf(0.02f to 0.1f, 0.5f to 0.4f, 0.98f to 0.9f)) {
            width = w; scale = s; angle = a; message = msg; beaconX = turn.first; beaconY = turn.second; compose.waitForIdle()
            val root = rootBounds(); val tag = "[$w dp, x$s, road $a, \"$msg\", beacon $turn]"
            inside("road pointer $tag", bounds("roadPointer"), root); inside("status card $tag", bounds("arStatus"), root); inside("turn beacon $tag", bounds("turnBeacon"), root)
            assertTrue("the road pointer sits on the status card $tag", !bounds("arStatus").overlaps(bounds("roadPointer")))
        }
    }

    @Test fun directionCardTripCardAndControlsFitEveryScreen() {
        host {
            Column(Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth()) { ArTurnCard("Left", "1.1 km", instruction, "Right") }
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ArTripCard("6:18 pm", "13 min", "1.1 km", Modifier.testTag("arTripCard"))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArControl(Icons.Rounded.Close, "Stop navigation", {}); ArControl(Icons.Rounded.Map, "Return to map", {}, primary = true); ArControl(Icons.Rounded.VolumeUp, "Mute spoken cues", {})
                }
            }
        }
        for (w in widths) for (s in fontScales) for (text in listOf("Left", "Walk south on IT Park Road toward the Kalinga Institute of Industrial Technology main gate")) {
            width = w; scale = s; instruction = text; compose.waitForIdle()
            val root = rootBounds(); val tag = "[$w dp, x$s]"
            inside("direction card $tag", bounds("arTurnCard"), root); inside("trip card $tag", bounds("arTripCard"), root)
            val controls = all("arControl"); controls.forEachIndexed { i, c -> inside("control $i $tag", c, root); controls.drop(i + 1).forEach { assertTrue("controls overlap $tag", !c.overlaps(it)) } }
            assertTrue("the direction card runs into the trip card $tag", bounds("arTurnCard").bottom <= bounds("arTripCard").top)
            assertTrue("the trip card sits on the controls $tag", bounds("arTripCard").bottom <= controls.minOf { it.top })
        }
    }
}
