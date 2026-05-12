package com.rohanc.navgate.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohanc.navgate.data.BackendNavigationRepository
import com.rohanc.navgate.data.NavigationRepository
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.PlaceSearchResult
import com.rohanc.navgate.model.RouteRequest
import com.rohanc.navgate.navigation.NavigationEngine
import com.rohanc.navgate.navigation.NavigationSnapshot
import com.rohanc.navgate.navigation.PresentationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NavGateViewModel(
    private val repository: NavigationRepository = BackendNavigationRepository(),
    private val engine: NavigationEngine = NavigationEngine(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(NavGateUiState())
    val uiState: StateFlow<NavGateUiState> = _uiState.asStateFlow()

    init {
        refreshPlaces()
    }

    fun updateSearch(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        refreshPlaces(query)
    }

    fun selectOrigin(place: PlaceSearchResult) {
        _uiState.update {
            it.copy(
                selectedOrigin = place,
                preview = null,
                snapshot = it.snapshot.copy(origin = place.coordinate),
            )
        }
        recomputePreviewIfReady()
    }

    fun selectDestination(place: PlaceSearchResult) {
        _uiState.update {
            it.copy(
                selectedDestination = place,
                preview = null,
                snapshot = it.snapshot.copy(destination = place.coordinate),
            )
        }
        recomputePreviewIfReady()
    }

    fun startNavigation() {
        val snapshot = engine.startNavigation()
        _uiState.update {
            it.copy(
                snapshot = snapshot,
                isPreviewVisible = false,
            )
        }
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
        _uiState.update { it.copy(snapshot = liveSnapshot) }

        val destination = state.selectedDestination?.coordinate ?: return
        if (!liveSnapshot.isNavigating) return
        if (!engine.shouldReroute(location)) return

        engine.markRerouting(true)
        _uiState.update { it.copy(snapshot = it.snapshot.copy(isRerouting = true)) }
        viewModelScope.launch {
            val route = repository.fetchRoute(RouteRequest(origin = location, destination = destination))
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
            it.copy(snapshot = it.snapshot.copy(userLocation = location))
        }
    }

    private fun recomputePreviewIfReady() {
        val origin = _uiState.value.selectedOrigin ?: return
        val destination = _uiState.value.selectedDestination ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoute = true) }
            val route = repository.fetchRoute(RouteRequest(origin.coordinate, destination.coordinate))
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
            val places = repository.searchPlaces(query)
            _uiState.update { it.copy(places = places) }
        }
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
