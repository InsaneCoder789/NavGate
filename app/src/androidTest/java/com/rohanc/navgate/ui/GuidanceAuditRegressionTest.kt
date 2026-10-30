package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.ui.ar.*
import com.rohanc.navgate.ui.journey.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidanceAuditRegressionTest {
    @get:Rule val compose = createComposeRule()
    private fun actions() = JourneyActions(
            back = {},
            openSearch = {},
            query = {},
            submitSearch = {},
            choose = {},
            directions = {},
            share = {},
            toggleSaved = {},
            openSettings = {},
            openTab = {},
            openMyRoutes = {},
            clearRecents = {},
            useCurrentLocation = {},
            chooseOnMap = {},
            swap = {},
            clearPlan = {},
            setProfile = {},
            replan = {},
            start = {},
            requestStop = {},
            requestLocation = {},
            openAppSettings = {},
            showSteps = {},
            openCamera = {},
            setViewMode = {},
            saveParking = {},
            chooseRoute = {},
            practiceAr = {},
            exportArDiagnostics = {},
            toggleVoice = {},
            finishArrival = {},
            openOfflineData = {},
            openPlace = {},
            planAgain = {},
            clearHistory = {},
            setPrefs = {},
            setCity = {},
            reviewSetup = {},
            closeCamera = {},
            requestCamera = {},
            dismissOnboarding = {},
            cancelPick = {},
            openCategory = {},
            closeResults = {},
            call = {},
            openWebsite = {},
            email = {}
    )

    @Test fun trackingTitleWrapsWithoutLosingWordsAtLargeText() {
        var scale by mutableFloatStateOf(1.15f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.width(240.dp).height(700.dp)) { StatusCard("Getting your bearings") }
            }
        }
        for (s in listOf(1f, 1.15f, 1.5f, 2f)) {
            scale = s; compose.waitForIdle()
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Getting your bearings").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            // Compose can retain a paragraph wider than its compact measured text box.
            // Check actual glyph-line bounds and all characters, allowing integer-pixel rounding.
            assertFalse("tracking heading clipped vertically at $s", layout.didOverflowHeight)
            assertTrue("tracking heading clipped horizontally at $s", (0 until layout.lineCount).all { layout.getLineRight(it) <= layout.size.width + 1f && layout.getLineLeft(it) >= -1f })
            assertEquals("Getting your bearings".length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    @Test fun liveRetainsStopMapAndVoiceActionsAndTheyWork() {
        var stopped = false; var map = false; var voice = false
        val a = actions().copy(requestStop = { stopped = true }, closeCamera = { map = true }, toggleVoice = { voice = true })
        compose.setContent {
            Box(Modifier.fillMaxSize()) { LiveGuidanceControls(false, AlignmentStatus(AlignmentLevel.Recover, "Need heading lock", 0.0), a) }
        }
        compose.onNodeWithContentDescription("Stop navigation").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Return to map").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Turn on spoken cues").assertIsDisplayed().performClick()
        assertTrue(stopped); assertTrue(map); assertTrue(voice)
    }
    @Test fun navigationModeRailClearsGpsBannerAndTripFooterAtLargeText() {
        var scale by mutableFloatStateOf(1.15f)
        val route = com.rohanc.navgate.core.CampusRoute("test", 1, "test", "v1", "destination", "entrance",
            listOf(com.rohanc.navgate.core.GeoPoint(20.0, 85.0), com.rohanc.navgate.core.GeoPoint(20.001, 85.0)),
            emptyList(), 110.0, 90.0, emptyList(), "2026-10-30")
        val session = com.rohanc.navgate.core.SessionSnapshot(sessionId = "layout", route = route,
            status = com.rohanc.navgate.core.SessionStatus.SignalLimited, remainingMeters = 110.0, remainingSeconds = 90.0,
            instruction = "Turn left onto the very long street name", distanceToManeuver = 81.0)
        val ui = com.rohanc.navgate.ui.state.NavGateUiState(preferencesLoaded = true, showOnboarding = false)
        val data = JourneyData("Navigation", "to", ui, session, emptyList(), emptyList(), true, false, null,
            emptyList(), true, null, SettingsModel(com.rohanc.navgate.data.FrontendPreferences(),
                com.rohanc.navgate.ui.state.CityMode.PanIndia, true, VoiceAvailability.OfflineReady, true),
            "2D", true, AlignmentStatus(AlignmentLevel.Aligned, "Facing route", 0.0), true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.width(320.dp).height(780.dp)) { JourneyUi(data, { _, _ -> }, actions()) {} }
            }
        }
        for (s in listOf(1.15f, 2f)) {
            scale = s; compose.waitForIdle()
            compose.onNodeWithTag("stateBanner").performScrollTo()
            val header = compose.onNodeWithTag("navigationHeader", true).fetchSemanticsNode().boundsInRoot
            val modes = compose.onNodeWithTag("navigationModeViewport", true).fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithTag("navigationFooter", true).fetchSemanticsNode().boundsInRoot
            val banner = compose.onNodeWithTag("stateBanner", true).fetchSemanticsNode().boundsInRoot
            assertTrue("mode controls overlap header at $s", modes.top >= header.bottom)
            assertTrue("mode controls overlap GPS banner at $s", modes.top >= banner.bottom)
            assertTrue("mode controls overlap footer at $s", modes.bottom <= footer.top)
        }
    }

    @Test fun liveRecoveryStatusDoesNotCoverTurnBubbleOrTripCard() {
        var scale by mutableFloatStateOf(1.15f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.width(320.dp).height(780.dp)) {
                    LiveCameraLayer(LiveBubble("Turn left onto a long street", "128 m", "Left", 0.5f), "6:18 pm", "13 min", "1.1 km",
                        { m, _ -> Box(m) }, rawStatusMessage = "Need heading lock")
                }
            }
        }
        for (s in listOf(1.15f, 2f)) {
            scale = s; compose.waitForIdle()
            // The status card appears once the message has stood for a moment, so a flickering heading cannot make it blink.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("arStatus", true).fetchSemanticsNodes().isNotEmpty() }
            val status = compose.onNodeWithTag("arStatus", true).fetchSemanticsNode().boundsInRoot
            val bubble = compose.onNodeWithTag("liveBubble", true).fetchSemanticsNode().boundsInRoot
            val trip = compose.onNodeWithTag("arTripCard", true).fetchSemanticsNode().boundsInRoot
            assertTrue("status overlaps Live turn at $s", status.bottom <= bubble.top)
            assertTrue("Live turn overlaps trip card at $s", bubble.bottom <= trip.top)
        }
    }

    @Test fun arrivalRestoresFeedbackAndTogglesWithoutDroppingExistingTags() {
        var chosen by mutableStateOf(setOf("Well lit"))
        val saved = mutableListOf<Set<String>>()
        val a = actions().copy(saveFeedback = { id, tags ->
            assertEquals("trip-a", id); chosen = tags; saved += tags
        })
        compose.setContent {
            Box(Modifier.fillMaxSize()) { ArrivalUi(null, "Destination", false, null, { _, _ -> }, a, "trip-a", chosen) }
        }
        fun weight(text: String): androidx.compose.ui.text.font.FontWeight? {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().layoutInput.style.fontWeight
        }
        compose.onNodeWithText("Well lit").assertIsDisplayed()
        assertEquals(androidx.compose.ui.text.font.FontWeight.ExtraBold, weight("Well lit"))
        compose.onNodeWithText("Easy to follow").performClick()
        compose.onNodeWithText("Well lit").performClick()
        compose.runOnIdle {
            assertEquals(listOf(setOf("Well lit", "Easy to follow"), setOf("Easy to follow")), saved)
            assertEquals(setOf("Easy to follow"), chosen)
        }
        assertEquals(androidx.compose.ui.text.font.FontWeight.ExtraBold, weight("Easy to follow"))
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, weight("Well lit"))
    }


    @Test fun turnDistanceRetainsNumberAndUnitAtLargeText() {
        var distance by mutableStateOf("81 m")
        var scale by mutableFloatStateOf(1.15f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                Box(Modifier.width(320.dp).height(700.dp)) {
                    TurnCard(androidx.compose.material.icons.Icons.Rounded.TurnLeft, distance, "Turn left", androidx.compose.material.icons.Icons.Rounded.TurnRight)
                }
            }
        }
        for (s in listOf(1.15f, 2f)) for (text in listOf("81 m", "999 m", "1.1 km", "100 km")) {
            scale = s; distance = text; compose.waitForIdle()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("turnDistance").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("Distance clipped vertically at $s: $text", layout.didOverflowHeight)
            assertTrue("Distance clipped horizontally at $s: $text", (0 until layout.lineCount).all { layout.getLineRight(it) <= layout.size.width + 1f && layout.getLineLeft(it) >= -1f })
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    @Test fun mutedDrivingHintAgreesWithVoiceControl() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NavigationUi(com.rohanc.navgate.core.SessionSnapshot(instruction="Continue",distanceToManeuver=100.0),emptyList(),false,true,null,{_,_->},actions())
            }
        }
        compose.onNodeWithText("Mount your phone safely. Spoken directions are off.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Turn on spoken directions").assertIsDisplayed()
    }

    @Test fun arIntroExplainsConditionalOcclusionWithoutGuaranteeingIt() {
        compose.setContent { ArIntroOverlay("AR", {}, {}, reduceMotion=true) }
        compose.onNodeWithText("Next").performClick();compose.waitForIdle()
        compose.onNodeWithText("Next").performClick();compose.waitForIdle()
        compose.onNodeWithText("On supported phones, depth can soften the path behind nearby objects. It may be unavailable or inaccurate.").assertIsDisplayed()
    }

    @Test fun delayedSearchResultsRevealTheBestMatchInsteadOfKeepingFooterAnchored() {
        var results by mutableStateOf(emptyList<com.rohanc.navgate.model.PlaceSearchResult>())
        var loading by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.width(320.dp).height(700.dp)) {
                SearchPage("Kitchen", "place", results, emptyList(), loading, null, true, null, actions())
            }
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        compose.runOnIdle {
            results=(0..25).map { com.rohanc.navgate.model.PlaceSearchResult("qa-$it", "Result $it", "Test address", null, null, com.rohanc.navgate.model.PlaceType.Landmark) }
            loading=false
        }
        compose.waitForIdle()
        compose.onNodeWithText("Result 0").assertIsDisplayed()
    }
}
