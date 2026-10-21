package com.rohanc.navgate.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaceSearchPage(val places:List<CampusPlace>,val notice:String?=null)

interface BackendData {
    suspend fun initialize()
    suspend fun search(query: String, regionId: String): List<CampusPlace>
    suspend fun searchPage(query:String,regionId:String):PlaceSearchPage = PlaceSearchPage(search(query,regionId))
    suspend fun place(regionId: String, id: String): CampusPlace?
}

/** Persist checkpoint and trip summary together; previews and raw GPS tracks are not durable trips. */
interface SessionStore {
    suspend fun loadCheckpoint(): SessionCheckpoint?
    suspend fun persist(snapshot: SessionSnapshot, updatedAtEpochMillis: Long)
}

object NoSessionStore : SessionStore {
    override suspend fun loadCheckpoint(): SessionCheckpoint? = null
    override suspend fun persist(snapshot: SessionSnapshot, updatedAtEpochMillis: Long) = Unit
}

interface BackendClock {
    fun elapsedMillis(): Long
    fun epochMillis(): Long
}

data class BackendSnapshot(
    val initializing: Boolean = true,
    val searching: Boolean = false,
    val planning: Boolean = false,
    val query: String = "",
    val searchNotice:String?=null,
    val regionId: String = "kiit",
    val places: List<CampusPlace> = emptyList(),
    val session: SessionSnapshot = SessionSnapshot(),
    val routeFailure: RouteResult.Unavailable? = null,
    val destinationAvailability: Map<String,DestinationAvailability> = emptyMap(),
    val storageError: String? = null,
    val durableRevision: Long = 0,
    /** Other ways to the same destination, loaded in the background while the preview is open (includes the previewed route). */
    val routeChoices: List<CampusRoute> = emptyList(),
)

/**
 * Public integration boundary for Compose, Stitch-generated screens, or headless tests.
 * Commands are ordered by one actor. Slow search/planning runs in children and returns generation-tagged events.
 */
class NavigationBackend(
    parentScope: CoroutineScope,
    private val data: BackendData,
    private val regions: RegionStore,
    private val clock: BackendClock,
    private val store: SessionStore = NoSessionStore,
    private val session: NavigationSession = NavigationSession(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val routingProvider: RoutingProvider = RoutePlanner(regions),
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val events = Channel<Event>(Channel.BUFFERED)
    private val mutable = MutableStateFlow(BackendSnapshot())
    val state: StateFlow<BackendSnapshot> = mutable.asStateFlow()
    private val speech = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val announcements: SharedFlow<String> = speech.asSharedFlow()
    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var rerouteJob: Job? = null
    private var searchGeneration = 0L
    private var planGeneration = 0L
    private var pendingPlan: PlanRequest? = null
    private var lastCheckpointAt: Long? = null
    private var lastPersistedStatus: SessionStatus? = null
    private var backupJob: Job? = null
    private var backupRouteId: String? = null
    private var lastBackupAt = 0L

    init {
        scope.launch {
            initialize()
            for (event in events) {
                try { handle(event) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutable.value = mutable.value.copy(initializing = false, planning = false, searching = false,
                        storageError = "Local navigation operation failed. Retry after checking installed data and storage.")
                }
            }
        }
    }

    suspend fun search(query: String, regionId: String = "kiit") = events.send(Event.Search(query.take(256), regionId))
    suspend fun plan(request: PlanRequest) = events.send(Event.Plan(request))
    suspend fun start() = dispatch(Event.Start)
    suspend fun stop() = dispatch(Event.Stop)
    suspend fun clear() = dispatch(Event.Clear)
    suspend fun chooseRoute(routeId: String) = dispatch(Event.ChooseRoute(routeId))
    suspend fun location(fix: LocationFix) = events.send(Event.Location(fix))
    suspend fun tick() = events.send(Event.Tick)
    suspend fun presentation(value: Presentation) = events.send(Event.PresentationChange(value))
    suspend fun retryInitialization() = events.send(Event.Initialize)
    suspend fun regionStatus(regionId: String): RegionStatus = withContext(io) { regionStatus(regionId,regions.active(regionId)) }
    fun close() { job.cancel(); events.close() }

    private suspend fun dispatch(event: Event) {
        val done = CompletableDeferred<Unit>()
        events.send(event)
        events.send(Event.Barrier(done))
        done.await()
    }

    private suspend fun initialize() {
        mutable.value = mutable.value.copy(initializing = true, storageError = null)
        try {
            withContext(io) { data.initialize() }
            val checkpoint = try { withContext(io) { store.loadCheckpoint() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(storageError = "Saved trip could not be read; plan a new trip")
                null
            }
            if (checkpoint != null) {
                val route = checkpoint.snapshot.route
                val pack = route?.let { withContext(io) { regions.version(it.regionId,it.graphVersion) } }
                session.restore(checkpoint, if (route?.external == true) RouteIntegrity.externalCheckpointErrors(checkpoint).isEmpty() else pack != null && RouteIntegrity.checkpointErrors(checkpoint,pack).isEmpty())
            }
            mutable.value = mutable.value.copy(initializing = false, session = session.state.value)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(initializing = false, storageError = "Unable to initialize local data. Retry initialization.")
        }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            Event.Initialize -> if (session.state.value.status !in NavigationSession.ACTIVE) initialize()
            is Event.Barrier -> event.done.complete(Unit)
            is Event.Search -> {
                searchJob?.cancel(); val generation = ++searchGeneration
                mutable.value = mutable.value.copy(searching = true, query = event.query, regionId = event.regionId, searchNotice=null)
                searchJob = scope.launch {
                    try {
                        val (page,availability) = withContext(io) {
                            val page=data.searchPage(event.query,event.regionId)
                            val places=page.places
                            val all=destinationAvailability(regions.active(event.regionId))
                            page to places.mapNotNull{place -> all[place.id]?.let{place.id to it}}.toMap()
                        }
                        events.send(Event.SearchResult(generation,page.places,null,availability,page.notice))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { events.send(Event.SearchResult(generation, emptyList(),"Place search is unavailable. Check your connection or installed data.")) }
                }
            }
            is Event.SearchResult -> if (event.generation == searchGeneration) {
                mutable.value = mutable.value.copy(searching = false, places = event.places, destinationAvailability = event.availability, storageError = event.error, searchNotice=event.notice)
            }
            is Event.Plan -> {
                if (session.state.value.status in NavigationSession.ACTIVE) {
                    mutable.value = mutable.value.copy(routeFailure = RouteResult.Unavailable(RouteFailure.UncertainOrigin,"Stop the current trip before changing its endpoints"))
                    return
                }
                planJob?.cancel(); rerouteJob?.cancel(); val generation = ++planGeneration
                session.clear()
                mutable.value = mutable.value.copy(planning = true, session = session.state.value, routeFailure = null, routeChoices = emptyList())
                pendingPlan = event.request.copy(requestId = generation)
                planJob = scope.launch {
                    val result = try { withContext(compute) { routingProvider.plan(event.request.copy(requestId = generation)) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { RouteResult.Unavailable(RouteFailure.InvalidGraph,"Unable to load or calculate the installed route") }
                    events.send(Event.PlanResult(generation,result))
                }
            }
            is Event.PlanResult -> if (event.generation == planGeneration) {
                val requested = pendingPlan
                val candidate = event.result
                val result = if (candidate is RouteResult.Ready && (requested == null ||
                    candidate.route.requestId != requested.requestId || candidate.route.profile != requested.profile ||
                    (requested.routingSource != null && candidate.route.source != requested.routingSource) ||
                    candidate.route.regionId != requested.regionId || candidate.route.destinationPlaceId != requested.destinationPlaceId ||
                    (candidate.route.external && candidate.route.destinationPoint != requested.destinationPoint) ||
                    RouteIntegrity.shapeErrors(candidate.route).isNotEmpty())) {
                    RouteResult.Unavailable(RouteFailure.InvalidGraph, "Routing provider returned an invalid or mismatched route")
                } else candidate
                pendingPlan = null
                if (result is RouteResult.Ready) {
                    session.preview(result.route)
                    mutable.value = mutable.value.copy(routeChoices = listOf(result.route))
                    loadChoices(result.route, requested)
                }
                mutable.value = mutable.value.copy(planning = false, session = session.state.value,
                    routeFailure = result as? RouteResult.Unavailable)
            }
            Event.Start -> consume(session.start(clock.elapsedMillis()))
            Event.Stop -> {
                invalidatePlans()
                consume(session.stop())
            }
            Event.Clear -> {
                invalidatePlans()
                if (session.state.value.status in NavigationSession.ACTIVE || session.state.value.status == SessionStatus.ResumeAvailable) consume(session.stop())
                consume(session.clear())
                mutable.value = mutable.value.copy(routeFailure = null, routeChoices = emptyList())
            }
            is Event.Location -> consume(session.location(event.fix,clock.elapsedMillis()))
            Event.Tick -> consume(session.tick(clock.elapsedMillis()))
            is Event.PresentationChange -> consume(session.presentation(event.value))
            is Event.RerouteResult -> consume(session.replace(event.request,event.result,clock.elapsedMillis()))
            is Event.PreviewChoices -> if (session.state.value.route?.id == event.forRouteId && session.state.value.status == SessionStatus.Preview) {
                mutable.value = mutable.value.copy(routeChoices = event.routes)
            }
            is Event.ChooseRoute -> {
                val chosen = mutable.value.routeChoices.firstOrNull { it.id == event.routeId }
                if (chosen != null && session.state.value.status == SessionStatus.Preview) { session.preview(chosen); mutable.value = mutable.value.copy(session = session.state.value) }
            }
            is Event.Backups -> if (session.state.value.route?.id == event.routeId) consume(session.setBackups(event.routes))
        }
    }

    /** While the preview is open, fetch the fastest, shortest and alternative routes so the driver can pick, and so a reroute has them ready. */
    private fun loadChoices(route: CampusRoute, requested: PlanRequest?) {
        val destination = route.destinationPoint ?: return
        val request = PlanRequest(0, route.regionId, route.geometry.first(), route.destinationPlaceId, route.entranceId, route.profile, destination, route.destinationTitle, route.source)
        if (!route.external || requested == null) return
        scope.launch {
            val routes = try { withContext(compute) { routingProvider.alternatives(request) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (routes.isNotEmpty()) events.send(Event.PreviewChoices(route.id, RouteChoices.labelled(listOf(route) + routes.filterNot { RouteChoices.sameRoute(it, route) })))
        }
    }

    private fun invalidatePlans() {
        planGeneration++; pendingPlan = null; planJob?.cancel(); rerouteJob?.cancel()
        mutable.value = mutable.value.copy(planning = false)
    }

    /**
     * As soon as a trip is running and a position is known, fetch ready-made backup routes in the background (again every ~90 s,
     * and whenever the route changes). If the driver then leaves the road onto one of them, guidance switches over instantly.
     */
    private fun refreshBackupsIfDue(snapshot: SessionSnapshot) {
        val route = snapshot.route ?: return
        if (snapshot.status != SessionStatus.Navigating || route.destinationPoint == null) return
        val origin = snapshot.snappedLocation ?: snapshot.location ?: return
        val now = clock.elapsedMillis()
        if ((backupRouteId == route.id && now - lastBackupAt < 90_000) || backupJob?.isActive == true) return
        backupRouteId = route.id; lastBackupAt = now
        val request = PlanRequest(0, route.regionId, origin, route.destinationPlaceId, route.entranceId, route.profile, route.destinationPoint, route.destinationTitle, route.source)
        backupJob = scope.launch {
            val routes = try { withContext(compute) { routingProvider.alternatives(request) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (routes.isNotEmpty()) events.send(Event.Backups(route.id, routes))
        }
    }

    private suspend fun consume(update: SessionUpdate) {
        mutable.value = mutable.value.copy(session = update.snapshot)
        refreshBackupsIfDue(update.snapshot)
        update.speak?.let { speech.tryEmit(it) }
        val now = clock.elapsedMillis()
        val status = update.snapshot.status
        val persistable = status in NavigationSession.ACTIVE || status in setOf(SessionStatus.Arrived,SessionStatus.Stopped)
        if (persistable && update.snapshot.sessionId != null &&
            (status != lastPersistedStatus || lastCheckpointAt == null || now-lastCheckpointAt!! >= 2_000)
        ) {
            try {
                withContext(io) { store.persist(update.snapshot,clock.epochMillis()) }
                lastCheckpointAt = now; lastPersistedStatus = status
                mutable.value = mutable.value.copy(durableRevision = mutable.value.durableRevision + 1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(storageError = "Trip state could not be saved; navigation remains active")
            }
        }
        update.reroute?.let { request ->
            rerouteJob = scope.launch {
                val result = try { withContext(compute) { routingProvider.plan(request.request,request.pinnedVersion) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { RouteResult.Unavailable(RouteFailure.InvalidGraph,"Replacement graph could not be loaded") }
                events.send(Event.RerouteResult(request,result))
            }
        }
    }

    private sealed interface Event {
        data class Barrier(val done: CompletableDeferred<Unit>) : Event
        data object Initialize : Event
        data class Search(val query: String, val regionId: String) : Event
        data class SearchResult(val generation: Long, val places: List<CampusPlace>, val error: String?, val availability:Map<String,DestinationAvailability> = emptyMap(),val notice:String?=null) : Event
        data class Plan(val request: PlanRequest) : Event
        data class PlanResult(val generation: Long, val result: RouteResult) : Event
        data object Start : Event
        data object Stop : Event
        data object Clear : Event
        data class Location(val fix: LocationFix) : Event
        data object Tick : Event
        data class PresentationChange(val value: Presentation) : Event
        data class RerouteResult(val request: RerouteRequest, val result: RouteResult) : Event
        data class Backups(val routeId: String, val routes: List<CampusRoute>) : Event
        data class PreviewChoices(val forRouteId: String, val routes: List<CampusRoute>) : Event
        data class ChooseRoute(val routeId: String) : Event
    }
}
