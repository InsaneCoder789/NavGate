package com.rohanc.navgate.ui.journey

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.rohanc.navgate.core.DestinationAvailability
import com.rohanc.navgate.core.MotionEstimate
import com.rohanc.navgate.core.RegionStatus
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.model.asMetersLabel
import com.rohanc.navgate.ui.ar.AlignmentStatus
import com.rohanc.navgate.ui.state.NavGateUiState
import kotlinx.coroutines.flow.StateFlow

/** Every screen the app can show. There is no second layout: all of them are drawn by [JourneyUi]. */
val JourneyScreens = setOf("Explore", "Search", "Results", "Place", "Directions", "Route", "Navigation", "Arrival", "Saved", "Recents", "Settings", "Offline",
    "Steps", "Camera", "My routes", "ManualPin", "MapPick", "Setup")

/** All state the screens read, in one place. */
class JourneyData(
    val screen: String, val searchFor: String, val ui: NavGateUiState, val session: SessionSnapshot, val steps: List<RouteStep>, val routeWarnings: List<String>,
    val permission: Boolean, val voiceOn: Boolean, val areaName: String?, val searchResults: List<PlaceSearchResult>, val availability: DestinationAvailability?,
    val entranceLabels: Map<String, String>, val tripActive: Boolean, val motion: StateFlow<MotionEstimate?>?, val settings: SettingsModel,
    val viewMode: String, val updatesEnabled: Boolean, val importing: Boolean, val importVerifying: Boolean, val hasCameraPermission: Boolean, val alignment: AlignmentStatus, val cuesAvailable: Boolean,
    val online: Boolean = true, val tripSummary: String? = null, val traffic: com.rohanc.navgate.data.traffic.TrafficReport? = null,
)

/** [custom] draws the screens that own live app state (the manual route editor); everything else is drawn here. */
@Composable
fun BoxScope.JourneyUi(d: JourneyData, reportInsets: (Float, Float) -> Unit, a: JourneyActions, custom: @Composable BoxScope.() -> Unit) {
    val ui = d.ui
    var sheetDp by remember { mutableFloatStateOf(0f) }
    val onInsets: (Float, Float) -> Unit = { top, bottom -> sheetDp = bottom; reportInsets(top, bottom) }
    if (!ui.preferencesLoaded) { CircularProgressIndicator(Modifier.align(Alignment.Center)); return }
    val userLocation = ui.displayLocation ?: ui.snapshot.userLocation
    if (ui.showOnboarding && d.screen == "Explore") { Onboarding(d.permission, a); return }
    when (d.screen) {
        "Explore" -> ExploreHome(d.areaName, onInsets, a)
        "Results" -> ResultsScreen(ui, userLocation, onInsets, a)
        "Search" -> SearchPage(ui.searchQuery, d.searchFor, d.searchResults, ui.recentPlaces, ui.isLoadingPlaces, ui.searchNotice.takeIf { d.searchFor != "saved" }, d.permission, userLocation, a)
        "Place" -> ui.focusedPlace?.let { place -> PlaceUi(place, ui.savedPlaces.any { it.id == place.id }, userLocation, d.availability, d.entranceLabels, d.tripActive, onInsets, a) }
        "Directions", "Route" -> DirectionsUi(ui, d.steps, d.routeWarnings, d.permission, onInsets, a, d.traffic)
        "Navigation" -> NavigationUi(d.session, d.steps, d.voiceOn, (d.session.route?.profile ?: ui.travelProfile.name.lowercase()) == "driving", d.motion, onInsets, a, d.online, d.traffic)
        "Arrival" -> ArrivalUi(ui.selectedDestination?.title ?: d.session.route?.destinationTitle ?: "Destination", ui.travelProfile == TravelProfile.Driving, d.tripSummary, onInsets, a)
        "Saved" -> SavedScreen(ui, userLocation, a)
        "Recents" -> RecentsScreen(ui, userLocation, a)
        "Settings" -> SettingsScreen(d.settings, a)
        "Offline" -> OfflineScreen(ui.regionStatus, d.updatesEnabled, d.tripActive, d.importing, d.importVerifying, a)
        "Steps" -> StepsScreen(d.steps, d.session.maneuverIndex, ui.selectedOrigin?.title ?: "Your location", a)
        "Camera" -> CameraScreen(d.hasCameraPermission, d.cuesAvailable, d.alignment, d.session.instruction, d.session.distanceToManeuver.asMetersLabel(), d.voiceOn, d.session.route?.maneuvers?.getOrNull(d.session.maneuverIndex)?.kind, a)
        "MapPick" -> PickPrompt(if (d.searchFor == "from") "Tap the map to choose a start" else "Tap the map to choose a destination", a)
        "ManualPin" -> PickPrompt("Tap the map to drop a pin", a)
        "Setup" -> Onboarding(d.permission, a)
        else -> custom()
    }
    val routed = d.session.route != null
    val screenDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    if (routed && d.screen in setOf("Directions", "Route") && sheetDp in 1f..(screenDp * 0.5f)) {
        ViewModeSwitch(d.viewMode, a.setViewMode, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = sheetDp.dp + 12.dp))
    }
    // While driving the vertical switch sits mid-left, clear of the car, the banner and the map buttons on the right.
    if (routed && d.screen == "Navigation") ViewModeSwitch(d.viewMode, a.setViewMode, Modifier.align(Alignment.CenterStart).offset(y = 6.dp).padding(start = 12.dp), vertical = true)
    if (routed && d.screen == "Camera" && d.hasCameraPermission) ViewModeSwitch(d.viewMode, a.setViewMode, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), vertical = true)
}
