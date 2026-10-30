package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.SignalGrade
import com.rohanc.navgate.core.SignalState
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.journey.*
import com.rohanc.navgate.ui.state.Suggestion
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The NavGate Next components (turn card, signal chip, status banner, smart row, feedback chips) at every width and text size: nothing leaves the screen or overlaps. */
@RunWith(AndroidJUnit4::class)
class NextUiLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val widths = listOf(320, 360, 411)
    private val scales = listOf(0.85f, 1.0f, 1.3f, 1.5f, 2.0f)
    private val d = 2.75f
    private var width by mutableIntStateOf(360); private var scale by mutableFloatStateOf(1f)
    private var title by mutableStateOf("Walk south on IT Park Road toward the Kalinga Institute of Industrial Technology main gate")
    private var distance by mutableStateOf("1.1 km"); private var grade by mutableStateOf(SignalGrade.Strong)
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun inside(name: String, r: Rect, root: Rect) = assertTrue("$name $r leaves the screen $root", r.left >= root.left + 8 * d - 1 && r.right <= root.right - 8 * d + 1 && r.top >= root.top && r.bottom <= root.bottom)

    @Test fun turnCardSignalChipAndBannerFitEverywhere() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(d, scale)) { Box(Modifier.width(width.dp).height(900.dp)) { Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TurnCard(Icons.Rounded.TurnRight, distance, title, Icons.Rounded.TurnLeft)
            SignalChip(SignalState(grade, 12.0, 7))
            StateBanner("Searching for GPS", detail = "The map moves again when the signal returns.", spinner = true)
        } } } }
        for (w in widths) for (s in scales) for (dist in listOf("9 m", "120 m", "1.1 km", "999.9 km")) for (g in SignalGrade.entries) {
            width = w; scale = s; distance = dist; grade = g; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot; val tag = "[$w dp, x$s, $dist, $g]"
            val card = bounds("turnCard"); inside("turn card $tag", card, root); inside("signal chip $tag", bounds("signalChip"), root); inside("banner $tag", bounds("stateBanner"), root)
            assertTrue("the chip sits on the card $tag", bounds("signalChip").top >= card.bottom - 1)
            assertTrue("the banner sits on the chip $tag", bounds("stateBanner").top >= bounds("signalChip").bottom - 1)
        }
    }

    @Test fun smartRowAndFeedbackChipsFitEverywhere() {
        var chosen by mutableStateOf(setOf("Well lit"))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(d, scale)) { Box(Modifier.width(width.dp).height(900.dp)) { Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartRow(Suggestion("kp5", title, TravelProfile.Walking, 880.0, 3), {})
            FeedbackChips(chosen, onToggle = { chosen = if (it in chosen) chosen - it else chosen + it })
        } } } }
        for (w in widths) for (s in scales) for (name in listOf("KP-5", "Sri Venkateswara Swamy Devasthanam and Charitable Trust Hospital Annex Building")) {
            width = w; scale = s; title = name; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot; val tag = "[$w dp, x$s, \"${name.take(12)}\"]"
            val row = bounds("smartRow"); inside("smart row $tag", row, root); inside("feedback chips $tag", bounds("feedbackChips"), root)
            assertTrue("the chips sit on the row $tag", bounds("feedbackChips").top >= row.bottom - 1)
        }
    }

    @Test fun routeRowsFitEverywhere() {
        fun route(name: String, n: Int) = com.rohanc.navgate.core.ManualRoute("r", name, "walking", (0 until n).map { com.rohanc.navgate.core.RoutePin("p$it", "Pin $it", com.rohanc.navgate.core.GeoPoint(20.35 + it * 0.001, 85.82 + (it % 2) * 0.001)) })
        var current by mutableStateOf(route("Hostel to library", 4))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(d, scale)) { Box(Modifier.width(width.dp).height(900.dp)) { Column(Modifier.padding(horizontal = 12.dp).testTag("routeRowHost")) { RouteRow(current, {}, {}, {}) } } } }
        for (w in widths) for (s in scales) for (r in listOf(route("Hostel to library", 4), route("The long way round the whole campus past every hostel, canteen and sports ground", 40))) {
            width = w; scale = s; current = r; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot; val tag = "[$w dp, x$s, ${r.pins.size} pins]"
            inside("route row $tag", bounds("routeRowHost"), root)
            val go = compose.onNodeWithContentDescription("Navigate ${r.name}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val del = compose.onNodeWithContentDescription("Delete ${r.name}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            inside("Go $tag", go, root); inside("Delete $tag", del, root)
            assertTrue("Go and Delete overlap $tag", go.right <= del.left + 1)
        }
    }

    @Test fun dialogButtonsNeverCutTheirLabels() {
        var dismiss by mutableStateOf("Keep going")
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(d, scale)) { Box(Modifier.width(width.dp).height(900.dp)) { NgDialogCard("Stop this trip?", "You can plan another route after stopping.", "Stop", {}, {}, dismiss, true, null, true) } } }
        for (w in widths) for (s in scales) for (label in listOf("Keep going", "Keep navigating", "Cancel")) {
            width = w; scale = s; dismiss = label; compose.waitForIdle()
            for (text in listOf("Stop", label)) {
                val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes()
                assertTrue("\"$text\" not found at $w dp, x$s", nodes.isNotEmpty())
                for (node in nodes) {
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                    val wanted = results.first().multiParagraph.maxIntrinsicWidth
                    assertTrue("\"$text\" is cut off at $w dp, x$s: needs $wanted px, has ${node.size.width}", node.size.width + 1 >= wanted)
                }
            }
        }
    }
}
