package com.rohanc.navgate.ui.journey

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.model.asMetersLabel
import com.rohanc.navgate.ui.ar.AlignmentStatus
import com.rohanc.navgate.ui.state.NavGateUiState
import kotlinx.coroutines.flow.StateFlow


/** All state the screens read, in one place. */
class JourneyData(
    val screen: String, val searchFor: String, val ui: NavGateUiState, val session: SessionSnapshot, val steps: List<RouteStep>, val routeWarnings: List<String>,
    val permission: Boolean, val voiceOn: Boolean, val areaName: String?, val searchResults: List<PlaceSearchResult>,
    val tripActive: Boolean, val motion: StateFlow<MotionEstimate?>?, val settings: SettingsModel,
    val viewMode: String, val hasCameraPermission: Boolean, val alignment: AlignmentStatus, val cuesAvailable: Boolean,
    val online: Boolean = true, val tripSummary: String? = null, val locationOn: Boolean = true,
)

/** [custom] draws the screens that own live app state (the manual route editor); everything else is drawn here. */
@Composable
fun BoxScope.JourneyUi(d: JourneyData, reportInsets: (Float, Float) -> Unit, a: JourneyActions, custom: @Composable BoxScope.() -> Unit) {
    val ui = d.ui
    var sheetDp by remember(d.screen) { mutableFloatStateOf(0f) }
    var headerDp by remember(d.screen) { mutableFloatStateOf(0f) }
    val onInsets: (Float, Float) -> Unit = { top, bottom -> headerDp = top; sheetDp = bottom; reportInsets(top, bottom) }
    if (!ui.preferencesLoaded) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return }
    val userLocation = ui.displayLocation ?: ui.snapshot.userLocation
    if (ui.showOnboarding && d.screen == "Explore") { Onboarding(d.permission, a); return }
    when (d.screen) {
        "Explore" -> ExploreHome(d.areaName, onInsets, a, ui.cityMode == com.rohanc.navgate.ui.state.CityMode.Kiit, userLocation, ui.routeHistory, reduceMotion = ui.frontendPreferences.reduceMotion)
        "Results" -> ResultsScreen(ui, userLocation, onInsets, a)
        "Search" -> SearchPage(ui.searchQuery, d.searchFor, d.searchResults, ui.recentPlaces, ui.isLoadingPlaces, ui.searchNotice.takeIf { d.searchFor != "saved" }, d.permission, userLocation, a)
        "Place" -> ui.focusedPlace?.let { place -> PlaceUi(place, ui.savedPlaces.any { it.id == place.id }, userLocation, d.tripActive, onInsets, a) }
        "Directions", "Route" -> DirectionsUi(ui, d.steps, d.routeWarnings, d.permission, onInsets, a)
        "Navigation" -> NavigationUi(d.session, d.steps, d.voiceOn, (d.session.route?.profile ?: ui.travelProfile.name.lowercase()) == "driving", d.motion, onInsets, a, d.online, locationIssue = when { !d.permission -> "Location permission was turned off."; !d.locationOn -> "Location is turned off on this phone."; else -> null }, signal = ui.signal)
        "Arrival" -> ArrivalUi(ui.selectedDestination, ui.selectedDestination?.title ?: d.session.route?.destinationTitle ?: "Destination", ui.travelProfile == TravelProfile.Driving, d.tripSummary, onInsets, a, tripId = d.session.sessionId, initialFeedback = d.session.sessionId?.let { ui.tripFeedback[it] }.orEmpty())
        "Saved" -> SavedScreen(ui, userLocation, a)
        "Recents" -> RecentsScreen(ui, userLocation, a)
        "Settings" -> SettingsScreen(d.settings, a)
        "Offline" -> OfflineScreen(ui.regionStatus, d.tripActive, a)
        "Steps" -> StepsScreen(d.steps, d.session.maneuverIndex, ui.selectedOrigin?.title ?: "Your location", a)
        "Live" -> LiveGuidanceControls(d.voiceOn, d.alignment, a)
        "Camera" -> CameraScreen(d.hasCameraPermission, d.cuesAvailable, d.alignment, d.session.instruction, d.session.distanceToManeuver.asMetersLabel(), d.voiceOn, d.session.route?.maneuvers?.getOrNull(d.session.maneuverIndex)?.kind, a,
            nextKind = d.session.route?.maneuvers?.getOrNull(d.session.maneuverIndex + 1)?.kind?.takeIf { it != "Arrive" || d.session.maneuverIndex + 1 < (d.session.route?.maneuvers?.size ?: 0) },
            tripEta = d.session.remainingSeconds.takeIf { it > 0 }?.let { java.time.LocalTime.now().plusSeconds(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")) },
            tripLeft = d.session.remainingSeconds.let { if (it >= 3600) "%d h %d min".format((it / 3600).toInt(), ((it % 3600) / 60).toInt()) else "${(it / 60).toInt().coerceAtLeast(1)} min" },
            tripDistance = d.session.remainingMeters.asMetersLabel(), onInsets = onInsets)
        "MapPick" -> PickPrompt(if (d.searchFor == "from") "Tap the map to choose a start" else "Tap the map to choose a destination", a)
        "ManualPin" -> PickPrompt("Tap the map to drop a pin", a)
        "Setup" -> Onboarding(d.permission, a)
        else -> custom()
    }
    val routed = d.session.route != null
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    if (routed && d.screen in setOf("Directions", "Route") && sheetDp in 1f..(screenDp * 0.5f)) {
        ViewModeSwitch(d.viewMode, a.setViewMode, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = sheetDp.dp + 12.dp), arAvailable = d.session.route?.profile == "walking")
    }
    // While driving the vertical switch sits mid-left, clear of the car, the banner and the map buttons on the right.
    if (routed && d.screen == "Camera" && d.hasCameraPermission) NgModeButton("VR", Icons.Rounded.Videocam, { a.setViewMode("VR") }, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = (headerDp + 8f).dp))
    if (routed && d.screen == "Live" && d.hasCameraPermission) NgModeButton("AR", Icons.Rounded.ViewInAr, { a.setViewMode("AR") }, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 56.dp))
    if (routed && d.screen == "Navigation") {
        // Controls belong to the free map viewport, never to the centre of the full screen.
        // A short viewport scrolls the rail rather than covering a GPS warning or the trip footer.
        Column(Modifier.align(Alignment.TopStart).padding(top = (headerDp + 8f).dp, bottom = (sheetDp + 8f).dp, start = 12.dp)
            .fillMaxHeight().testTag("navigationModeViewport")) {
            AdaptiveModeSwitch(d.viewMode, a.setViewMode, arAvailable = d.session.route?.profile == "walking")
        }
    }
}
