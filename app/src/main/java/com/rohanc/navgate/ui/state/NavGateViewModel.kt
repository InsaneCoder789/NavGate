package com.rohanc.navgate.ui.state

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rohanc.navgate.data.BackendNavigationRepository
import com.rohanc.navgate.data.AppPreferencesStore
import com.rohanc.navgate.data.SharedPrefsAppPreferencesStore
import com.rohanc.navgate.data.NavigationRepository
import com.rohanc.navgate.data.SharedPrefsUserPlacesStore
import com.rohanc.navgate.data.UserPlacesStore
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.PlaceType
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.navigation.NavigationEngine
import com.rohanc.navgate.navigation.NavigationSnapshot
import com.rohanc.navgate.navigation.PresentationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab {
    Explore,
    Go,
    Saved,
    Recents,
}

enum class CityMode(
    val label: String,
    val backendCity: String?,
) {
    Mumbai("Mumbai", "Mumbai"),
    KiitBeta("KIIT Beta", "Bhubaneswar"),
}

class NavGateViewModel(
    private val repository: NavigationRepository = BackendNavigationRepository(),
    private val engine: NavigationEngine = NavigationEngine(),
    private val userPlacesStore: UserPlacesStore,
    private val appPreferencesStore: AppPreferencesStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NavGateUiState())
    val uiState: StateFlow<NavGateUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
        refreshPlaces()
        loadPersistedPlaces()
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query, activeTab = AppTab.Explore) }
        refreshPlaces(query)
    }

    fun setCityMode(mode: CityMode) {
        _uiState.update {
            it.copy(
                cityMode = mode,
                selectedOrigin = if (mode == CityMode.Mumbai && it.selectedOrigin?.campusLabel != null) null else it.selectedOrigin,
                selectedDestination = if (mode == CityMode.Mumbai && it.selectedDestination?.campusLabel != null) null else it.selectedDestination,
                preview = null,
                isPreviewVisible = false,
            )
        }
        viewModelScope.launch { appPreferencesStore.setCityMode(mode) }
        refreshPlaces(_uiState.value.searchQuery)
    }

    fun setKiitBetaAccess(enabled: Boolean) {
        _uiState.update { it.copy(kiitBetaAccess = enabled) }
        viewModelScope.launch { appPreferencesStore.setKiitBetaAccess(enabled) }
    }

    fun dismissOnboarding() {
        _uiState.update { it.copy(showOnboarding = false) }
        viewModelScope.launch { appPreferencesStore.markOnboardingSeen() }
    }

    fun selectOrigin(place: PlaceSearchResult) {
        _uiState.update {
            it.copy(
                selectedOrigin = place,
                preview = null,
                snapshot = it.snapshot.copy(origin = place.coordinate),
                activeTab = AppTab.Go,
                searchQuery = place.title,
            )
        }
        recordRecent(place)
        recomputePreviewIfReady()
    }

    fun selectDestination(place: PlaceSearchResult) {
        val inferredOrigin = _uiState.value.selectedOrigin ?: _uiState.value.snapshot.userLocation?.let(::liveLocationPlace)
        _uiState.update {
            it.copy(
                selectedOrigin = inferredOrigin ?: it.selectedOrigin,
                selectedDestination = place,
                preview = null,
                snapshot =
                    it.snapshot.copy(
                        origin = (inferredOrigin ?: it.selectedOrigin)?.coordinate,
                        destination = place.coordinate,
                    ),
                activeTab = AppTab.Go,
                searchQuery = place.title,
            )
        }
        recordRecent(place)
        recomputePreviewIfReady()
    }

    fun startNavigation() {
        val snapshot = engine.startNavigation()
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                isPreviewVisible = false,
                activeTab = AppTab.Go,
            )
        }
        _uiState.value.selectedDestination?.let(::recordRecent)
        recordRouteHistory(_uiState.value.selectedDestination, snapshot.etaSeconds)
    }

    fun switchMode(mode: PresentationMode) {
        engine.setPresentationMode(mode)
        _uiState.update {
            it.copy(snapshot = it.snapshot.copy(presentationMode = mode))
        }
    }

    fun updateProgress(lastFixAgeMillis: Long = 0, heading: Double? = null) {
        val location = _uiState.value.snapshot.userLocation
            ?: _uiState.value.selectedOrigin?.coordinate
            ?: return
        val snapshot = engine.updateUserProgress(location, heading, lastFixAgeMillis)
        _uiState.update { it.copy(snapshot = snapshot) }
    }

    fun onLocationSample(location: Coordinate, heading: Double?, lastFixAgeMillis: Long) {
        val state = _uiState.value
        val liveSnapshot = engine.updateUserProgress(location, heading, lastFixAgeMillis)
        _uiState.update { current ->
            current.copy(
                snapshot = liveSnapshot,
                selectedOrigin = if (current.selectedOrigin == null || current.selectedOrigin.id == LIVE_LOCATION_ID) liveLocationPlace(location) else current.selectedOrigin,
            )
        }
        if (!state.snapshot.isArrived && liveSnapshot.isArrived) {
            recordRouteHistory(state.selectedDestination, 0.0)
        }

        val destination = state.selectedDestination?.coordinate ?: return
        if (!liveSnapshot.isNavigating) return
        if (!engine.shouldReroute(location)) return

        engine.markRerouting(true)
        _uiState.update { it.copy(snapshot = it.snapshot.copy(isRerouting = true)) }
        viewModelScope.launch {
            val route = repository.fetchRoute(routeRequest(origin = location, destination = destination, destinationPlace = state.selectedDestination))
            val rerouted = engine.replaceActiveRoute(location, destination, route, location, heading, lastFixAgeMillis)
            _uiState.update {
                it.copy(
                    snapshot = rerouted,
                    preview = it.preview?.copy(
                        distanceLabel = route.distanceMeters.asDistanceLabel(),
                        etaLabel = route.durationSeconds.asEtaLabel(),
                        firstInstruction = route.steps.firstOrNull()?.instruction ?: "Route updated",
                    ),
                )
            }
        }
    }

    fun setCurrentLocationAsOrigin(location: Coordinate) {
        _uiState.update {
            val existingOrigin = it.selectedOrigin
            val liveOrigin = if (existingOrigin == null || existingOrigin.id == LIVE_LOCATION_ID) liveLocationPlace(location) else existingOrigin
            it.copy(
                selectedOrigin = liveOrigin,
                snapshot = it.snapshot.copy(userLocation = location, origin = liveOrigin.coordinate),
            )
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setTravelProfile(profile: TravelProfile) {
        _uiState.update { it.copy(travelProfile = profile) }
        recomputePreviewIfReady()
    }

    fun toggleSaved(place: PlaceSearchResult) {
        viewModelScope.launch {
            val saved = userPlacesStore.toggleSaved(place)
            _uiState.update { it.copy(savedPlaces = saved, activeTab = if (saved.any { p -> p.id == place.id }) AppTab.Saved else it.activeTab) }
        }
    }

    private fun recomputePreviewIfReady() {
        val state = _uiState.value
        val origin = state.selectedOrigin ?: return
        val destination = state.selectedDestination ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoute = true) }
            val route = repository.fetchRoute(routeRequest(origin.coordinate, destination.coordinate, destination))
            val snapshot = engine.selectRoute(origin.coordinate, destination.coordinate, route)
            _uiState.update {
                it.copy(
                    isLoadingRoute = false,
                    preview = RoutePreview.from(origin, destination, route),
                    snapshot = snapshot,
                    isPreviewVisible = true,
                )
            }
        }
    }

    private fun refreshPlaces(query: String = _uiState.value.searchQuery) {
        viewModelScope.launch {
            val places = repository.searchPlaces(query, _uiState.value.cityMode.backendCity)
            _uiState.update { it.copy(places = places) }
        }
    }

    private fun loadPersistedPlaces() {
        viewModelScope.launch {
            val saved = userPlacesStore.savedPlaces()
            val recents = userPlacesStore.recentPlaces()
            val history = userPlacesStore.routeHistory()
            _uiState.update { it.copy(savedPlaces = saved, recentPlaces = recents, routeHistory = history) }
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            val onboardingSeen = appPreferencesStore.isOnboardingSeen()
            val cityMode = appPreferencesStore.cityMode()
            val kiitBeta = appPreferencesStore.kiitBetaAccess()
            _uiState.update {
                it.copy(
                    showOnboarding = !onboardingSeen,
                    cityMode = cityMode,
                    kiitBetaAccess = kiitBeta,
                )
            }
            refreshPlaces(_uiState.value.searchQuery)
        }
    }

    private fun recordRecent(place: PlaceSearchResult) {
        viewModelScope.launch {
            val recents = userPlacesStore.recordRecent(place)
            _uiState.update { it.copy(recentPlaces = recents) }
        }
    }

    private fun recordRouteHistory(destination: PlaceSearchResult?, etaSeconds: Double) {
        destination ?: return
        viewModelScope.launch {
            val history =
                userPlacesStore.recordRouteHistory(
                    RouteHistoryEntry(
                        destinationId = destination.id,
                        destinationTitle = destination.title,
                        travelMode = _uiState.value.travelProfile,
                        recordedAtEpochMillis = System.currentTimeMillis(),
                        etaSeconds = etaSeconds,
                    ),
                )
            _uiState.update { it.copy(routeHistory = history) }
        }
    }

    private fun routeRequest(origin: Coordinate, destination: Coordinate, destinationPlace: PlaceSearchResult?) =
        RouteRequest(
            origin = origin,
            destination = destination,
            profile = _uiState.value.travelProfile,
            destinationPlaceId = destinationPlace?.id,
            cityHint = destinationPlace?.city ?: _uiState.value.cityMode.backendCity,
        )

    companion object {
        private const val LIVE_LOCATION_ID = "live-location"

        fun factory(application: Application): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    NavGateViewModel(
                        repository = BackendNavigationRepository(application = application),
                        engine = NavigationEngine(),
                        userPlacesStore = SharedPrefsUserPlacesStore(application),
                        appPreferencesStore = SharedPrefsAppPreferencesStore(application),
                    )
                }
            }

        private fun liveLocationPlace(location: Coordinate): PlaceSearchResult =
            PlaceSearchResult(
                id = LIVE_LOCATION_ID,
                title = "Live location",
                subtitle = "Current GPS position",
                latitude = location.latitude,
                longitude = location.longitude,
                type = PlaceType.Transit,
                city = "Current city",
                category = "Live",
            )
    }
}

data class NavGateUiState(
    val searchQuery: String = "",
    val places: List<PlaceSearchResult> = emptyList(),
    val selectedOrigin: PlaceSearchResult? = null,
    val selectedDestination: PlaceSearchResult? = null,
    val preview: RoutePreview? = null,
    val snapshot: NavigationSnapshot = NavigationSnapshot(),
    val isLoadingRoute: Boolean = false,
    val isPreviewVisible: Boolean = false,
    val activeTab: AppTab = AppTab.Explore,
    val travelProfile: TravelProfile = TravelProfile.Walking,
    val savedPlaces: List<PlaceSearchResult> = emptyList(),
    val recentPlaces: List<PlaceSearchResult> = emptyList(),
    val routeHistory: List<RouteHistoryEntry> = emptyList(),
    val cityMode: CityMode = CityMode.Mumbai,
    val kiitBetaAccess: Boolean = false,
    val showOnboarding: Boolean = true,
)

data class RoutePreview(
    val originTitle: String,
    val destinationTitle: String,
    val distanceLabel: String,
    val etaLabel: String,
    val firstInstruction: String,
) {
    companion object {
        fun from(
            origin: PlaceSearchResult,
            destination: PlaceSearchResult,
            route: com.rohanc.navgate.model.RouteResponse,
        ): RoutePreview =
            RoutePreview(
                originTitle = origin.title,
                destinationTitle = destination.title,
                distanceLabel = route.distanceMeters.asDistanceLabel(),
                etaLabel = route.durationSeconds.asEtaLabel(),
                firstInstruction = route.steps.firstOrNull()?.instruction ?: "Route ready",
            )
    }
}

internal fun Double.asDistanceLabel(): String =
    if (this >= 1000) {
        "%.1f km".format(this / 1000.0)
    } else {
        "${kotlin.math.round(this).toInt()} m"
    }

internal fun Double.asEtaLabel(): String {
    val minutes = kotlin.math.ceil(this / 60.0).toInt().coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} hr ${minutes % 60} min"
}
