package com.rohanc.navgate.ui.state

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.AppPreferencesStore
import com.rohanc.navgate.data.FrontendPreferences
import com.rohanc.navgate.data.UserPlacesStore
import com.rohanc.navgate.data.local.AndroidBackendClock
import com.rohanc.navgate.data.local.NavGateServices
import com.rohanc.navgate.data.local.toAppPlace
import com.rohanc.navgate.data.local.toAppRoute
import com.rohanc.navgate.model.*
import com.rohanc.navgate.navigation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Existing shell vocabulary is retained solely as an adapter for the forthcoming Stitch UI.
enum class AppTab { Explore, Go, Saved, Recents }
enum class CityMode(val label: String, val backendCity: String?, val regionId: String) {
    Mumbai("Mumbai", "Mumbai", "mumbai"),
    KiitBeta("KIIT Beta", "Bhubaneswar", "kiit"),
}

class NavGateViewModel(
    backendFactory: (CoroutineScope) -> NavigationBackend,
    private val userPlacesStore: UserPlacesStore,
    private val appPreferencesStore: AppPreferencesStore,
    private val clock: BackendClock = AndroidBackendClock,
) : ViewModel() {
    private val backend = backendFactory(viewModelScope)
    /** Primary frontend integration contract. New Stitch screens can consume this directly. */
    val backendState: StateFlow<BackendSnapshot> = backend.state
    private val mutable = MutableStateFlow(NavGateUiState())
    val uiState: StateFlow<NavGateUiState> = mutable.asStateFlow()
    private var heading: Double? = null
    private var latestLocationSample: LocationFix? = null
    private val guidancePolicy = GuidancePolicy()
    private var requestId = 0L
    private var lastDurableRevision = 0L
    private var backendWasInitializing = true
    private val preferenceWrites = Mutex()
    private var persistedFrontendPreferences = FrontendPreferences()

    init {
        viewModelScope.launch {
            backend.state.collect { state ->
                if (backendWasInitializing && !state.initializing && mutable.value.preferencesLoaded) refreshRegionStatus()
                backendWasInitializing = state.initializing
                val route = state.session.route
                mutable.update { current ->
                    val preview = if (state.session.status == SessionStatus.Preview && route != null && current.selectedOrigin != null && current.selectedDestination != null) {
                        RoutePreview.from(current.selectedOrigin,current.selectedDestination,route.toAppRoute())
                    } else null
                    current.copy(
                        places = state.places.map { it.toAppPlace() }, searchNotice=state.searchNotice, isLoadingPlaces = state.searching || state.initializing,
                        isLoadingRoute = state.planning, preview = preview, isPreviewVisible = preview != null,
                        snapshot = state.session.toLegacySnapshot(heading),
                        statusMessage = state.storageError ?: state.routeFailure?.detail ?: state.session.signalReason,
                        routeFailure = state.routeFailure?.reason,
                        canResume = state.session.status == SessionStatus.ResumeAvailable,
                        currentLocationUsable = freshCurrentLocation() != null,
                        selectedOrigin = if (current.selectedOrigin?.id == LIVE_LOCATION_ID && state.session.signalReason == null) state.session.location?.let(::livePlace) ?: current.selectedOrigin else current.selectedOrigin,
                    )
                }
                if (state.durableRevision != lastDurableRevision) {
                    lastDurableRevision = state.durableRevision
                    refreshUserPlaces()
                }
            }
        }
        viewModelScope.launch {
            backend.announcements.collect { message -> mutable.update { it.copy(voiceAnnouncement=message,voiceSequence=it.voiceSequence+1) } }
        }
        viewModelScope.launch {
            try {
                val city = appPreferencesStore.cityMode()
                val onboarding = appPreferencesStore.isOnboardingSeen()
                val beta = appPreferencesStore.kiitBetaAccess()
                val frontend = appPreferencesStore.frontendPreferences()
                persistedFrontendPreferences = frontend
                mutable.update { it.copy(cityMode=city,showOnboarding=!onboarding,kiitBetaAccess=beta,frontendPreferences=frontend,preferencesLoaded=true) }
                backend.search("",city.regionId)
                refreshRegionStatus()
                refreshUserPlaces()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(statusMessage="Local preferences or user data could not be loaded",preferencesLoaded=true) } }
        }
    }

    fun updateSearch(query: String) {
        mutable.update { it.copy(searchQuery=query,activeTab=AppTab.Explore) }
        viewModelScope.launch { backend.search(query,mutable.value.cityMode.regionId) }
    }

    fun setCityMode(mode: CityMode) {
        if (!canEditTrip()) return
        mutable.update { it.copy(cityMode=mode,selectedOrigin=null,selectedDestination=null,focusedPlace=null,selectedEntranceId=null,preview=null,isPreviewVisible=false,regionStatus=null) }
        viewModelScope.launch {
            backend.clear()
            backend.search(mutable.value.searchQuery,mode.regionId)
            storeOperation { appPreferencesStore.setCityMode(mode) }
            refreshRegionStatus()
        }
    }

    fun setKiitBetaAccess(enabled: Boolean) {
        mutable.update { it.copy(kiitBetaAccess=enabled) }
        viewModelScope.launch { storeOperation { appPreferencesStore.setKiitBetaAccess(enabled) } }
    }

    fun dismissOnboarding() {
        mutable.update { it.copy(showOnboarding=false) }
        viewModelScope.launch { storeOperation { appPreferencesStore.markOnboardingSeen() } }
    }

    fun selectOrigin(place: PlaceSearchResult) {
        if (!canEditTrip()) return
        if (place.coordinate == null) { mutable.update { it.copy(statusMessage="This place has no reviewed coordinates; choose another start") }; return }
        mutable.update { it.copy(selectedOrigin=place,focusedPlace=null,activeTab=AppTab.Go,searchQuery="") }
        recordRecent(place)
        recomputePreview()
    }

    fun startHere(place:PlaceSearchResult) {
        if(!canEditTrip())return
        if(place.coordinate==null){mutable.update{it.copy(statusMessage="This place has no reviewed starting coordinate")};return}
        mutable.update{it.copy(selectedOrigin=place,selectedDestination=null,selectedEntranceId=null,focusedPlace=null,preview=null,isPreviewVisible=false,statusMessage="Choose a destination")}
        recordRecent(place)
        viewModelScope.launch{backend.clear()}
    }

    fun selectDestination(place: PlaceSearchResult) {
        if (!canEditTrip()) return
        val live = freshCurrentLocation()
        mutable.update { it.copy(selectedOrigin=it.selectedOrigin ?: live?.let(::livePlace),selectedDestination=place,
            selectedEntranceId=null,focusedPlace=null,activeTab=AppTab.Go,searchQuery="") }
        recordRecent(place)
        recomputePreview()
    }

    fun selectEntrance(id: String?) {
        if (!canEditTrip()) return
        mutable.update { it.copy(selectedEntranceId=id) }
        recomputePreview()
    }

    fun setTravelProfile(profile: TravelProfile) {
        if (!canEditTrip()) return
        mutable.update { it.copy(travelProfile=profile) }
        recomputePreview()
    }

    private fun recomputePreview() {
        val state = mutable.value
        val origin = state.selectedOrigin?.coordinate
        val destination = state.selectedDestination
        val request = if (origin != null && destination != null) PlanRequest(++requestId,state.cityMode.regionId,
            GeoPoint(origin.latitude,origin.longitude),destination.id,state.selectedEntranceId,state.travelProfile.name.lowercase()) else null
        viewModelScope.launch {
            if (request == null) {
                backend.clear()
                mutable.update { it.copy(statusMessage=if (destination == null) "Choose a destination" else "Choose a start or enable accurate location") }
            } else backend.plan(request)
        }
    }

    fun startNavigation() {
        if (mutable.value.startPending) return
        mutable.update { it.copy(startPending=true) }
        viewModelScope.launch {
            try { backend.start() }
            finally { mutable.update { it.copy(startPending=false) } }
        }
    }

    fun stopNavigation() {
        if (mutable.value.stopPending) return
        mutable.update { it.copy(stopPending=true) }
        viewModelScope.launch {
            try { backend.stop(); mutable.update { it.copy(voiceAnnouncement=null) } }
            finally { mutable.update { it.copy(stopPending=false) } }
        }
    }

    fun switchMode(mode: PresentationMode) {
        viewModelScope.launch { backend.presentation(if(mode==PresentationMode.MAP) Presentation.Map else Presentation.CameraAssist) }
    }

    fun onLocationFix(fix: LocationFix) {
        heading=fix.heading?.takeIf { it.isFinite() }
        if (latestLocationSample?.let { fix.elapsedMillis > it.elapsedMillis } != false) latestLocationSample=fix
        viewModelScope.launch { backend.location(fix) }
    }

    fun onLocationSample(location: Coordinate, heading: Double?, lastFixAgeMillis: Long, accuracyMeters: Double = Double.POSITIVE_INFINITY) {
        onLocationFix(LocationFix(GeoPoint(location.latitude,location.longitude),clock.elapsedMillis()-lastFixAgeMillis,accuracyMeters,heading))
    }

    fun checkSignal() {
        // Idle/Preview ticks do not publish backend snapshots, but their last GPS fix still ages.
        mutable.update { it.copy(currentLocationUsable=freshCurrentLocation()!=null) }
        viewModelScope.launch { backend.tick() }
    }
    fun retryInitialization() { viewModelScope.launch { backend.retryInitialization() } }
    fun clearRouteHistory() { viewModelScope.launch { storeOperation { userPlacesStore.clearRouteHistory();refreshUserPlaces() } } }
    fun clearRecents() { viewModelScope.launch { storeOperation { userPlacesStore.clearRecents();refreshUserPlaces() } } }
    fun updateFrontendPreferences(value: FrontendPreferences) {
        mutable.update { it.copy(frontendPreferences=value) }
        viewModelScope.launch { preferenceWrites.withLock {
            try { appPreferencesStore.setFrontendPreferences(value);persistedFrontendPreferences=value }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update {
                it.copy(frontendPreferences=if(it.frontendPreferences==value) persistedFrontendPreferences else it.frontendPreferences,
                    statusMessage="Appearance settings could not be saved; please try again")
            } }
        } }
    }
    fun useCurrentOrigin(): Boolean {
        if (!canEditTrip()) return false
        val session=backend.state.value.session
        val fix=freshCurrentLocation()
        if(fix==null || session.signalReason!=null) { mutable.update { it.copy(statusMessage="Waiting for a fresh accurate location; choose a start manually") };return false }
        selectOrigin(livePlace(fix))
        return true
    }
    private fun freshCurrentLocation(): GeoPoint? {
        val sample=latestLocationSample ?: return null
        val session=backend.state.value.session
        val age=clock.elapsedMillis()-sample.elapsedMillis
        return session.location?.takeIf {
            session.signalReason==null && it==sample.point && sample.point.valid &&
                sample.accuracyMeters.isFinite() && sample.accuracyMeters>0 &&
                sample.accuracyMeters<=guidancePolicy.maxAccuracyMeters && age in 0..guidancePolicy.maxFixAgeMillis
        }
    }
    fun swapEndpoints() {
        if(!canEditTrip())return
        val state=mutable.value
        val from=state.selectedOrigin;val to=state.selectedDestination
        if(from==null || to?.coordinate==null || from.id==LIVE_LOCATION_ID) {
            mutable.update { it.copy(statusMessage="Choose two campus places to swap endpoints") };return
        }
        mutable.update { it.copy(selectedOrigin=to,selectedDestination=from,selectedEntranceId=null) };recomputePreview()
    }
    fun clearPlan() { if(!canEditTrip())return;viewModelScope.launch {
        backend.clear();mutable.update { it.copy(selectedOrigin=null,selectedDestination=null,focusedPlace=null,preview=null,isPreviewVisible=false,statusMessage=null) }
    } }
    fun finishArrival(keepDestinationContext:Boolean=false,destinationContext:PlaceSearchResult?=null) { val destination=mutable.value.selectedDestination ?: destinationContext;viewModelScope.launch { backend.clear();mutable.update { it.copy(selectedOrigin=null,selectedDestination=null,focusedPlace=if(keepDestinationContext)destination else null,activeTab=AppTab.Explore) } } }
    fun refreshRegionStatus() { val id=mutable.value.cityMode.regionId;viewModelScope.launch {
        backend.state.first { !it.initializing }
        storeOperation { val status=backend.regionStatus(id);mutable.update { if(it.cityMode.regionId==id)it.copy(regionStatus=status) else it } }
    } }
    fun replan() { if(canEditTrip()) recomputePreview() }

    fun setCurrentLocationAsOrigin(location: Coordinate) = selectOrigin(livePlace(GeoPoint(location.latitude,location.longitude)))
    fun focusPlace(place: PlaceSearchResult) { mutable.update { it.copy(focusedPlace=place,activeTab=AppTab.Explore) } }
    fun dismissFocusedPlace() { mutable.update { it.copy(focusedPlace=null) } }
    fun searchSaved(query: String): List<PlaceSearchResult> {
        val term=query.trim()
        return mutable.value.savedPlaces.filter { place ->
            term.isEmpty() || listOfNotNull(place.title,place.subtitle,place.category,place.campusLabel).any { it.contains(term,ignoreCase=true) }
        }
    }
    fun selectTab(tab: AppTab) { mutable.update { it.copy(activeTab=tab,searchQuery=if(tab==AppTab.Explore) it.searchQuery else "",focusedPlace=null) } }
    fun toggleSaved(place: PlaceSearchResult) {
        viewModelScope.launch { storeOperation { val saved=userPlacesStore.toggleSaved(place); mutable.update { it.copy(savedPlaces=saved) } } }
    }
    private fun recordRecent(place: PlaceSearchResult) {
        viewModelScope.launch { storeOperation { val recent=userPlacesStore.recordRecent(place); mutable.update { it.copy(recentPlaces=recent) } } }
    }
    private suspend fun refreshUserPlaces() {
        storeOperation {
            val saved=userPlacesStore.savedPlaces(); val recents=userPlacesStore.recentPlaces(); val history=userPlacesStore.routeHistory()
            mutable.update { it.copy(savedPlaces=saved,recentPlaces=recents,routeHistory=history) }
        }
    }
    private suspend fun storeOperation(operation: suspend () -> Unit) {
        try { operation() }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { mutable.update { it.copy(statusMessage="Local data could not be saved or loaded; existing records were retained") } }
    }
    private fun canEditTrip(): Boolean {
        if (mutable.value.startPending || mutable.value.stopPending || backend.state.value.session.status in NavigationSession.ACTIVE) {
            mutable.update { it.copy(statusMessage="Stop the current trip before changing its region or destination") }
            return false
        }
        return true
    }
    override fun onCleared() { backend.close(); super.onCleared() }

    companion object {
        private const val LIVE_LOCATION_ID="live-location"
        fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val services=NavGateServices.get(application)
                NavGateViewModel(services::backend,services.users,services.preferences)
            }
        }
        private fun livePlace(point: GeoPoint)=PlaceSearchResult(LIVE_LOCATION_ID,"Your location","Last accepted GPS position",point.latitude,point.longitude,PlaceType.Transit)
    }
}

private fun SessionSnapshot.toLegacySnapshot(heading: Double?): NavigationSnapshot {
    val appRoute=route?.toAppRoute()
    val accepted=status in setOf(SessionStatus.Navigating,SessionStatus.Rerouting,SessionStatus.Arrived) && !offRoute
    val confidence=SignalConfidence(if(accepted) GuidanceConfidence.Medium else GuidanceConfidence.Low,
        signalReason ?: if(accepted) "GPS accepted; field accuracy is unmeasured" else "Waiting for usable location")
    return NavigationSnapshot(origin=route?.geometry?.firstOrNull()?.let { Coordinate(it.latitude,it.longitude) },
        destination=route?.geometry?.lastOrNull()?.let { Coordinate(it.latitude,it.longitude) },route=appRoute,
        currentStepIndex=maneuverIndex,currentInstruction=instruction,nextInstructionHint=route?.maneuvers?.getOrNull(maneuverIndex+1)?.instruction,
        currentManeuverType=appRoute?.steps?.getOrNull(maneuverIndex)?.maneuverType ?: ManeuverType.Start,
        distanceToNextStep=distanceToManeuver,etaSeconds=remainingSeconds,isOffRoute=offRoute,isRerouting=status==SessionStatus.Rerouting,
        locationConfidence=confidence,headingConfidence=SignalConfidence(GuidanceConfidence.Low,"World anchoring is not available"),guidanceConfidence=confidence,
        presentationMode=if(presentation==Presentation.Map) PresentationMode.MAP else PresentationMode.AR_ASSIST,
        isNavigating=status in NavigationSession.ACTIVE,isArrived=status==SessionStatus.Arrived,
        userLocation=location?.let { Coordinate(it.latitude,it.longitude) },userHeading=heading)
}

data class NavGateUiState(
    val searchNotice:String?=null,
    val searchQuery:String="",val places:List<PlaceSearchResult> = emptyList(),val isLoadingPlaces:Boolean=false,
    val focusedPlace:PlaceSearchResult?=null,val selectedOrigin:PlaceSearchResult?=null,val selectedDestination:PlaceSearchResult?=null,
    val preview:RoutePreview?=null,val snapshot:NavigationSnapshot=NavigationSnapshot(),val isLoadingRoute:Boolean=false,
    val isPreviewVisible:Boolean=false,val activeTab:AppTab=AppTab.Explore,val travelProfile:TravelProfile=TravelProfile.Walking,
    val savedPlaces:List<PlaceSearchResult> = emptyList(),val recentPlaces:List<PlaceSearchResult> = emptyList(),
    val routeHistory:List<RouteHistoryEntry> = emptyList(),val cityMode:CityMode=CityMode.KiitBeta,val kiitBetaAccess:Boolean=false,
    val showOnboarding:Boolean=true,val selectedEntranceId:String?=null,val statusMessage:String?=null,
    val routeFailure:RouteFailure?=null,val canResume:Boolean=false,val startPending:Boolean=false,
    val voiceAnnouncement:String?=null,val voiceSequence:Long=0,
    val frontendPreferences:FrontendPreferences=FrontendPreferences(),val regionStatus:RegionStatus?=null,
    val preferencesLoaded:Boolean=false,val stopPending:Boolean=false,val currentLocationUsable:Boolean=false,
)

data class RoutePreview(val originTitle:String,val destinationTitle:String,val distanceLabel:String,val etaLabel:String,val firstInstruction:String) {
    companion object {
        fun from(origin:PlaceSearchResult,destination:PlaceSearchResult,route:RouteResponse)=RoutePreview(origin.title,destination.title,
            route.distanceMeters.asDistanceLabel(),route.durationSeconds.asEtaLabel(),route.steps.firstOrNull()?.instruction ?: "Route ready")
    }
}
internal fun Double.asDistanceLabel():String=if(this>=1000) "%.1f km".format(this/1000) else "${kotlin.math.round(this).toInt()} m"
internal fun Double.asEtaLabel():String { val minutes=kotlin.math.ceil(this/60).toInt().coerceAtLeast(1);return if(minutes<60) "$minutes min" else "${minutes/60} hr ${minutes%60} min" }
