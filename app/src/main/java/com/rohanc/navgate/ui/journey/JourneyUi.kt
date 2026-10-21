package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import com.rohanc.navgate.core.DestinationAvailability
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteStep
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.ui.state.NavGateUiState

val JourneyScreens = setOf("Explore", "Search", "Results", "Place", "Directions", "Route", "Navigation", "Arrival")

/** Where the map needs to leave room: the top overlay and the bottom sheet, in dp. Reported by whichever screen is showing. */
@Composable
fun BoxScope.JourneyUi(screen: String, searchFor: String, ui: NavGateUiState, session: SessionSnapshot, steps: List<RouteStep>, routeWarnings: List<String>,
    permission: Boolean, voiceOn: Boolean, areaName: String?, searchResults: List<PlaceSearchResult>, availability: DestinationAvailability?,
    entranceLabels: Map<String, String>, tripActive: Boolean, onInsets: (Float, Float) -> Unit, a: JourneyActions) {
    when (screen) {
        "Explore" -> ExploreHome(areaName, onInsets, a)
        "Search", "Results" -> SearchPage(ui.searchQuery, searchFor, searchResults, ui.recentPlaces, ui.isLoadingPlaces, ui.searchNotice.takeIf { searchFor != "saved" }, permission, ui.displayLocation ?: ui.snapshot.userLocation, a)
        "Place" -> ui.focusedPlace?.let { place ->
            PlaceUi(place, ui.savedPlaces.any { it.id == place.id }, ui.displayLocation ?: ui.snapshot.userLocation, availability, entranceLabels, tripActive, onInsets, a)
        }
        "Directions", "Route" -> DirectionsUi(ui, steps, routeWarnings, permission, onInsets, a)
        "Navigation" -> NavigationUi(session, steps, voiceOn, onInsets, a)
        "Arrival" -> ArrivalUi(ui.selectedDestination?.title ?: session.route?.destinationTitle ?: "Destination", ui.travelProfile == TravelProfile.Driving, onInsets, a)
    }
}
