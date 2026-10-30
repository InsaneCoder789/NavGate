package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.ui.journey.SpeedGaugeFace
import com.rohanc.navgate.ui.journey.TripDock
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Components are drawn the way they will be met: narrow and wide screens, small and very large text, empty and extreme values. Nothing may overflow
 * its parent, touch an edge or sit on top of its neighbour.
 */
@RunWith(AndroidJUnit4::class)
class ComponentLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val widths = listOf(320, 360, 411)
    private val fontScales = listOf(0.85f, 1.0f, 1.15f, 1.5f, 2.0f)

    private var width by mutableIntStateOf(360)
    private var fontScale by mutableFloatStateOf(1f)
    private var speed by mutableDoubleStateOf(0.0)
    private var noSignal by mutableStateOf(false)
    private var arrive by mutableStateOf("08:05"); private var left by mutableStateOf("1 min"); private var distance by mutableStateOf("9 m")
    private var mode by mutableIntStateOf(0)   // 0 gauge, 1 footer, 2 list row, 3 buttons, 4 button row
    private var title by mutableStateOf("Shri Ganesh Mandir"); private var distanceText by mutableStateOf("180 m")

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(2.75f, fontScale)) {
            Box(Modifier.width(width.dp)) {
                when (mode) {
                    0 -> SpeedGaugeFace(speed, {}, unknown = noSignal)
                    1 -> TripDock(null, arrive, left, distance, {}, {}, Modifier.fillMaxWidth())
                    2 -> com.rohanc.navgate.ui.journey.ResultRow(com.rohanc.navgate.model.PlaceSearchResult("p", title, "Hospital · 340 Valmiki Chowk, Kandivali East, Mumbai, Maharashtra 400101", 19.2, 72.87, com.rohanc.navgate.model.PlaceType.Food, category = "hospital"),
                        com.rohanc.navgate.model.Coordinate(19.2001, 72.8701), {})
                    4 -> com.rohanc.navgate.ui.journey.ButtonRow(Modifier.fillMaxWidth()) { item ->
                        com.rohanc.navgate.ui.journey.NgPrimaryButton("Directions", Icons.Rounded.Navigation, {}, item(1.5f).testTag("b1"))
                        com.rohanc.navgate.ui.journey.NgTonalButton("Saved", Icons.Rounded.Share, {}, item(1f).testTag("b2"))
                        com.rohanc.navgate.ui.journey.NgTonalButton("Call", Icons.Rounded.Share, {}, item(1f).testTag("b3"))
                    }
                    else -> androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                        com.rohanc.navgate.ui.journey.NgPrimaryButton(title, Icons.Rounded.Navigation, {}, Modifier.fillMaxWidth().testTag("primary"))
                        com.rohanc.navgate.ui.journey.NgTonalButton(title, Icons.Rounded.Share, {}, Modifier.fillMaxWidth().testTag("tonal"))
                    }
                }
            }
        }
    }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun speedFigureSitsRightAboveTheKmhTag() {
        mode = 0; show()
        for (scale in fontScales) for (value in listOf(0.0, 9.0, 99.0, 100.0, 250.0)) {
            compose.runOnIdle { fontScale = scale; speed = value }; compose.waitForIdle()
            val ring = node("gauge"); val figure = node("gauge-speed"); val unit = node("gauge-unit")
            val d = 2.75f
            val gap = (unit.top - figure.bottom) / d
            assertTrue("speed $value scale $scale: the tag is $gap dp below the figure", gap in -3f..5f)
            assertTrue("speed $value scale $scale: figure is wider than the ring allows", figure.width / d <= 92f * 0.72f)
            assertTrue("speed $value scale $scale: figure and tag are not inside the ring", figure.left > ring.left + 14 * d && unit.right < ring.right - 14 * d && unit.bottom < ring.bottom - 10 * d)
            assertTrue("speed $value scale $scale: figure starts too high (${figure.top - ring.top} of ${ring.height})", figure.top > ring.top + ring.height * 0.28f)
        }
    }

    @Test fun footerFitsEveryWidthAndTextSize() {
        mode = 1; show()
        for (w in widths) for (scale in fontScales) for ((a0, l0, m0) in listOf(Triple("12:59 PM", "1 hr 59 min", "999.9 km"), Triple("08:05", "1 min", "9 m"), Triple("23:59", "12 hr 5 min", "1,250 km"))) {
            compose.runOnIdle { width = w; fontScale = scale; arrive = a0; left = l0; distance = m0 }; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val stop = compose.onNodeWithContentDescription("Stop navigation").fetchSemanticsNode().boundsInRoot
            val d = 2.75f
            assertTrue("$w dp, scale $scale: the End button touches the footer edge ($stop in $root)", stop.left >= root.left + 8 * d && stop.right <= root.right - 8 * d)
            // The new footer: the time left is the headline, arrival and distance share the line under it, then End and Steps.
            val head = compose.onNodeWithText(l0).fetchSemanticsNode().boundsInRoot
            val sub = compose.onNodeWithText("Arrive $a0", substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$w dp, scale $scale, $a0/$l0/$m0: the headline runs into the line under it ($head $sub)", head.bottom <= sub.top + 2 * d)
            assertTrue("$w dp, scale $scale, $a0/$l0/$m0: a figure touches the screen edge", head.left >= root.left + 8 * d && head.right <= root.right - 8 * d && sub.left >= root.left + 8 * d && sub.right <= root.right - 8 * d)
            val steps = compose.onNodeWithContentDescription("Steps").fetchSemanticsNode().boundsInRoot
            assertTrue("$w dp, scale $scale: the buttons overlap each other", stop.right <= steps.left)
            assertTrue("$w dp, scale $scale, $a0/$l0/$m0: the figures touch the buttons (${sub.bottom} vs ${stop.top})", sub.bottom + 6 * d <= stop.top)
        }
    }

    @Test fun listRowsHoldLongNamesAtEveryTextSize() {
        mode = 2; show()
        for (w in widths) for (scale in fontScales) for (name in listOf("Shri Ganesh Mandir", "Sri Venkateswara Swamy Devasthanam and Charitable Trust Hospital Annex Building")) {
            compose.runOnIdle { width = w; fontScale = scale; title = name }; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val badge = node("place-badge"); val d = 2.75f
            val text = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$w dp scale $scale: icon touches the left edge", badge.left >= root.left + 12 * d)
            assertTrue("$w dp scale $scale: the name runs into the icon", text.left >= badge.right + 8 * d)
            assertTrue("$w dp scale $scale: the name reaches the right edge (${text.right} of ${root.right})", text.right <= root.right - 12 * d)
        }
    }

    @Test fun buttonsKeepLongLabelsInside() {
        mode = 3; show()
        for (w in widths) for (scale in fontScales) for (label in listOf("Start driving", "Preview walking route to the destination now")) {
            compose.runOnIdle { width = w; fontScale = scale; title = label }; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            for (tag in listOf("primary", "tonal")) {
                val b = node(tag)
                assertTrue("$w dp scale $scale $tag: button is wider than the screen", b.left >= root.left && b.right <= root.right)
            }
            val text = compose.onAllNodesWithText(label, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
            assertTrue("$w dp scale $scale: the label leaves the button", text.left >= node("primary").left && text.right <= node("primary").right)
        }
    }

    @Test fun buttonRowsStackAtLargeTextInsteadOfSqueezingLabels() {
        mode = 4; show()
        for (w in widths) for (scale in fontScales) {
            compose.runOnIdle { width = w; fontScale = scale }; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val b = listOf("b1", "b2", "b3").map { node(it) }
            for ((k, r) in b.withIndex()) assertTrue("$w dp scale $scale: button ${k + 1} leaves the screen ($r in $root)", r.left >= root.left - 1 && r.right <= root.right + 1)
            for (x in 0 until 3) for (y in x + 1 until 3) {
                val overlap = b[x].left < b[y].right - 1 && b[y].left < b[x].right - 1 && b[x].top < b[y].bottom - 1 && b[y].top < b[x].bottom - 1
                assertTrue("$w dp scale $scale: buttons ${x + 1} and ${y + 1} overlap", !overlap)
            }
            if (scale > 1.3f) assertTrue("$w dp scale $scale: the buttons should be stacked", b[0].left == b[1].left && b[1].left == b[2].left)
            else assertTrue("$w dp scale $scale: the buttons should sit side by side", b[0].top == b[1].top && b[1].top == b[2].top)
        }
    }

    @Test fun withoutSignalTheGaugeShowsADashInsideTheRing() {
        mode = 0; show()
        for (scale in fontScales) {
            compose.runOnIdle { fontScale = scale; speed = 87.0; noSignal = true }; compose.waitForIdle()
            compose.onNodeWithText("—", useUnmergedTree = true).assertExists()
            val ring = node("gauge"); val figure = node("gauge-speed"); val d = 2.75f
            assertTrue("scale $scale: the dash leaves the ring", figure.left > ring.left + 14 * d && figure.right < ring.right - 14 * d)
        }
        compose.runOnIdle { noSignal = false }
    }
}
