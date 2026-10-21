package com.rohanc.navgate.ui.journey

import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.TravelProfile

/** Everything the journey screens can ask the app to do. Screens never reach into the view model or navigation state directly. */
data class JourneyActions(
    val back: () -> Unit,
    val openSearch: (String) -> Unit,
    val query: (String) -> Unit,
    val submitSearch: () -> Unit,
    val choose: (PlaceSearchResult) -> Unit,
    val directions: (PlaceSearchResult) -> Unit,
    val directionsWithEntrance: (PlaceSearchResult, String) -> Unit,
    val share: (PlaceSearchResult) -> Unit,
    val toggleSaved: (PlaceSearchResult) -> Unit,
    val openSettings: () -> Unit,
    val openTab: (String) -> Unit,
    val openMyRoutes: () -> Unit,
    val clearRecents: () -> Unit,
    val useCurrentLocation: () -> Unit,
    val chooseOnMap: (String) -> Unit,
    val swap: () -> Unit,
    val clearPlan: () -> Unit,
    val setProfile: (TravelProfile) -> Unit,
    val replan: () -> Unit,
    val start: () -> Unit,
    val requestStop: () -> Unit,
    val requestLocation: () -> Unit,
    val openAppSettings: () -> Unit,
    val showSteps: () -> Unit,
    val openCamera: () -> Unit,
    val toggleVoice: () -> Unit,
    val finishArrival: () -> Unit,
    val openOfflineData: () -> Unit,
)
