package com.rohanc.navgate.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.*

@Serializable
enum class SessionStatus { Idle, Preview, Navigating, SignalLimited, Rerouting, GuidanceUnavailable, Arrived, Stopped, ResumeAvailable }

@Serializable
enum class Presentation { Map, CameraAssist }

@Serializable
data class LocationFix(val point: GeoPoint, val elapsedMillis: Long, val accuracyMeters: Double, val heading: Double? = null)

@Serializable
data class SessionSnapshot(
    val sessionId: String? = null,
    val route: CampusRoute? = null,
    val status: SessionStatus = SessionStatus.Idle,
    val presentation: Presentation = Presentation.Map,
    val location: GeoPoint? = null,
    val accuracyMeters: Double? = null,
    val alongMeters: Double = 0.0,
    val maneuverIndex: Int = 0,
    val remainingMeters: Double = 0.0,
    val remainingSeconds: Double = 0.0,
    val distanceToManeuver: Double = 0.0,
    val instruction: String = "Choose a destination",
    val signalReason: String? = null,
    val offRoute: Boolean = false,
)

@Serializable
data class SessionCheckpoint(val snapshot: SessionSnapshot)

data class RerouteRequest(val sessionId: String, val requestId: Long, val request: PlanRequest, val pinnedVersion: String?, val originFixElapsedMillis: Long = 0)

data class SessionUpdate(val snapshot: SessionSnapshot, val speak: String? = null, val reroute: RerouteRequest? = null)

data class GuidancePolicy(
    val maxFixAgeMillis: Long = 8_000,
    val maxAccuracyMeters: Double = 25.0,
    val offRouteMeters: Double = 30.0,
    val deviationSamples: Int = 3,
    val arrivalMeters: Double = 10.0,
    val drivingArrivalMeters: Double = 25.0,
    val arrivalSamples: Int = 2,
    val rerouteCooldownMillis: Long = 12_000,
    val maxWalkingMetersPerSecond: Double = 4.0,
    val maxDrivingMetersPerSecond: Double = 70.0,
) {
    init {
        require(maxFixAgeMillis>0 && maxAccuracyMeters.isFinite() && maxAccuracyMeters>0)
        require(offRouteMeters.isFinite() && offRouteMeters>0 && arrivalMeters.isFinite() && arrivalMeters>0 && drivingArrivalMeters.isFinite() && drivingArrivalMeters>=arrivalMeters)
        require(deviationSamples>=2 && arrivalSamples>=2 && rerouteCooldownMillis>=0)
        require(maxWalkingMetersPerSecond.isFinite() && maxWalkingMetersPerSecond>0)
        require(maxDrivingMetersPerSecond.isFinite() && maxDrivingMetersPerSecond>0)
    }
}

/** All state transitions are serialized here. Views consume snapshots and never advance a trip. */
class NavigationSession(private val policy: GuidancePolicy = GuidancePolicy()) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(SessionSnapshot())
    val state: StateFlow<SessionSnapshot> = mutable.asStateFlow()
    private var latestFix: LocationFix? = null
    private var lastAccepted: LocationFix? = null
    private var deviationCount = 0
    private var arrivalCount = 0
    private var lastRerouteAt: Long? = null
    private var rerouteCounter = 0L
    private var pendingReroute: RerouteRequest? = null

    suspend fun preview(route: CampusRoute): SessionUpdate = mutex.withLock {
        require(RouteIntegrity.shapeErrors(route).isEmpty()) { "Cannot preview an invalid route" }
        resetTracking()
        publish(SessionSnapshot(route = route, status = SessionStatus.Preview, location = latestFix?.point,
            remainingMeters = route.distanceMeters, remainingSeconds = route.estimatedSeconds,
            instruction = route.maneuvers.firstOrNull()?.instruction ?: "Route ready", distanceToManeuver = route.maneuvers.firstOrNull()?.alongMeters ?: 0.0))
    }

    suspend fun clear(): SessionUpdate = mutex.withLock {
        resetTracking()
        publish(SessionSnapshot(location = latestFix?.point))
    }

    suspend fun start(nowElapsedMillis: Long): SessionUpdate = mutex.withLock {
        val current = mutable.value
        if (current.status !in setOf(SessionStatus.Preview, SessionStatus.ResumeAvailable)) return@withLock SessionUpdate(current)
        val route = current.route ?: return@withLock SessionUpdate(current)
        val fix = latestFix
        val issue = qualityReason(fix, nowElapsedMillis)
        if (issue != null) return@withLock publish(current.copy(signalReason = issue))
        requireNotNull(fix)
        val resumed = current.status == SessionStatus.ResumeAvailable
        val expected = if (resumed) projectPath(fix.point, route.geometry) else null
        val near = if (resumed) expected?.distanceMeters ?: Double.POSITIVE_INFINITY else fix.point.distanceTo(route.geometry.first())
        // A road router snaps the start pin to the nearest mapped road, which can be tens of metres from where a phone reports you.
        val startTolerance = if (route.external) 60.0 else 20.0
        if (near > startTolerance) return@withLock publish(current.copy(signalReason = "Move to the planned start or replan from your location"))
        if (resumed && abs(route.shapeToRouteMeters(expected?.alongMeters ?: 0.0) - current.alongMeters) > 40.0) {
            return@withLock publish(current.copy(signalReason = "Position changed since the checkpoint; replan before resuming"))
        }
        resetTracking()
        lastAccepted = fix
        // Open on the first real turn, not on the "head southwest" start instruction at 0 m.
        val upcoming = route.maneuvers.indexOfFirst { it.alongMeters > current.alongMeters + 2 }.let { if (it < 0) route.maneuvers.lastIndex.coerceAtLeast(0) else it }
        val firstTurn = route.maneuvers.getOrNull(upcoming)
        publish(current.copy(sessionId = current.sessionId ?: UUID.randomUUID().toString(), status = SessionStatus.Navigating,
            location = fix.point, accuracyMeters = fix.accuracyMeters, signalReason = null, maneuverIndex = upcoming,
            instruction = firstTurn?.instruction ?: current.instruction,
            distanceToManeuver = ((firstTurn?.alongMeters ?: route.distanceMeters) - current.alongMeters).coerceAtLeast(0.0)), current.instruction)
    }

    suspend fun stop(): SessionUpdate = mutex.withLock {
        resetTracking()
        publish(mutable.value.copy(status = SessionStatus.Stopped, offRoute = false, signalReason = null))
    }

    suspend fun presentation(value: Presentation): SessionUpdate = mutex.withLock { publish(mutable.value.copy(presentation = value)) }

    suspend fun restore(checkpoint: SessionCheckpoint, graphAvailable: Boolean): SessionUpdate = mutex.withLock {
        resetTracking()
        latestFix = null // persisted positions never count as a live fix, including after device reboot
        val saved = checkpoint.snapshot
        if (saved.route == null || !graphAvailable || saved.status !in ACTIVE || RouteIntegrity.shapeErrors(saved.route).isNotEmpty()) return@withLock publish(SessionSnapshot(signalReason = "Saved trip cannot be resumed; plan a new trip"))
        publish(saved.copy(status = SessionStatus.ResumeAvailable, presentation = Presentation.Map, location = null,
            signalReason = "Acquire a fresh location and confirm resume", offRoute = false))
    }

    suspend fun tick(nowElapsedMillis: Long): SessionUpdate = mutex.withLock {
        val state = mutable.value
        val reason = qualityReason(latestFix, nowElapsedMillis)
        if (state.status in ACTIVE && reason != null) {
            deviationCount = 0; arrivalCount = 0
            publish(state.copy(status = SessionStatus.SignalLimited, signalReason = reason))
        } else SessionUpdate(state)
    }

    suspend fun location(fix: LocationFix, nowElapsedMillis: Long): SessionUpdate = mutex.withLock {
        val current = mutable.value
        // Out-of-order samples do not refresh freshness or accrue arrival/deviation evidence.
        if (latestFix != null && fix.elapsedMillis <= latestFix!!.elapsedMillis) return@withLock SessionUpdate(current)
        val issue = qualityReason(fix, nowElapsedMillis)
        if (issue != null) {
            arrivalCount = 0; deviationCount = 0
            return@withLock if (current.status in ACTIVE) publish(current.copy(status = SessionStatus.SignalLimited, signalReason = issue)) else publish(current.copy(signalReason = issue))
        }
        latestFix = fix
        if (current.status !in ACTIVE) return@withLock publish(current.copy(location = fix.point, accuracyMeters = fix.accuracyMeters, signalReason = null))
        val route = current.route ?: return@withLock SessionUpdate(current)
        val previous = lastAccepted
        val elapsed = previous?.let { (fix.elapsedMillis - it.elapsedMillis) / 1000.0 } ?: 0.0
        // Elapsed time is not capped: after a signal outage the user may legitimately be far from the last accepted fix.
        val movementBudget = (elapsed.coerceAtLeast(0.0) * (if (route.profile == "driving") policy.maxDrivingMetersPerSecond else policy.maxWalkingMetersPerSecond) + 8.0)
        if (previous != null && previous.point.distanceTo(fix.point) > movementBudget + min(previous.accuracyMeters + fix.accuracyMeters, 20.0)) {
            arrivalCount = 0; deviationCount = 0
            return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Implausible location jump; waiting for a stable fix"))
        }
        val projection = projectPath(fix.point, route.geometry,
            minAlong = route.routeToShapeMeters(max(0.0, current.alongMeters - 15)), maxAlong = route.routeToShapeMeters(current.alongMeters) + movementBudget)
        val fullProjection = projectPath(fix.point, route.geometry)
        if (projection == null || (projection.distanceMeters > policy.offRouteMeters && (fullProjection?.distanceMeters ?: Double.POSITIVE_INFINITY) < 10)) {
            arrivalCount = 0; deviationCount = 0
            return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Route progress is ambiguous; replan from a confirmed position"))
        }
        lastAccepted = fix
        val offRoute = projection.distanceMeters > max(policy.offRouteMeters, fix.accuracyMeters * 1.5)
        deviationCount = if (offRoute) deviationCount + 1 else 0
        val progress = if (offRoute) current.alongMeters else max(current.alongMeters, route.shapeToRouteMeters(projection.alongMeters))
        val remaining = max(0.0, route.distanceMeters - progress)
        // A moving car can cross a 4-10 m walking radius between two fixes, so driving uses a wider fixed zone.
        val driving = route.profile == "driving"
        val arrivalLimit = if (driving) policy.drivingArrivalMeters else policy.arrivalMeters
        val arrivalRadius = if (driving) arrivalLimit else min(arrivalLimit, max(4.0, fix.accuracyMeters))
        val atDestination = !offRoute && remaining <= arrivalLimit && fix.point.distanceTo(route.geometry.last()) <= arrivalRadius
        arrivalCount = if (atDestination) arrivalCount + 1 else 0
        val arrived = arrivalCount >= policy.arrivalSamples
        val step = route.maneuvers.indexOfFirst { it.alongMeters > progress + 2 }.let { if (it < 0) route.maneuvers.lastIndex.coerceAtLeast(0) else it }
        val maneuver = route.maneuvers.getOrNull(step)
        var reroute: RerouteRequest? = null
        val canReroute = deviationCount >= policy.deviationSamples && pendingReroute == null &&
            (lastRerouteAt == null || nowElapsedMillis - lastRerouteAt!! >= policy.rerouteCooldownMillis)
        if (!arrived && canReroute) {
            rerouteCounter++
            reroute = RerouteRequest(requireNotNull(current.sessionId), rerouteCounter,
                PlanRequest(rerouteCounter, route.regionId, fix.point, route.destinationPlaceId, route.entranceId, route.profile, route.destinationPoint, route.destinationTitle, route.source), if (route.external) null else route.graphVersion, fix.elapsedMillis)
            pendingReroute = reroute
            lastRerouteAt = nowElapsedMillis
        }
        val nextStatus = when { arrived -> SessionStatus.Arrived; pendingReroute != null -> SessionStatus.Rerouting; current.status == SessionStatus.GuidanceUnavailable && offRoute -> SessionStatus.GuidanceUnavailable; else -> SessionStatus.Navigating }
        val next = current.copy(status = nextStatus, location = fix.point, accuracyMeters = fix.accuracyMeters,
            alongMeters = if (arrived) route.distanceMeters else progress, maneuverIndex = step,
            remainingMeters = if (arrived) 0.0 else remaining, remainingSeconds = if (arrived) 0.0 else route.remainingSecondsAt(progress),
            distanceToManeuver = if (arrived) 0.0 else max(0.0, (maneuver?.alongMeters ?: route.distanceMeters) - progress),
            instruction = if (arrived) if (route.external) "You have arrived at your destination" else "You have arrived at the selected entrance" else maneuver?.instruction ?: "Follow the mapped path",
            signalReason = if (offRoute) "Route deviation detected" else null, offRoute = offRoute)
        if (arrived) pendingReroute = null
        publish(next, if (arrived || step != current.maneuverIndex) next.instruction else null, reroute)
    }

    suspend fun replace(request: RerouteRequest, result: RouteResult, nowElapsedMillis: Long? = null): SessionUpdate = mutex.withLock {
        val current = mutable.value
        if (pendingReroute != request || current.sessionId != request.sessionId || current.status !in ACTIVE) return@withLock SessionUpdate(current)
        pendingReroute = null
        if (result is RouteResult.Unavailable) return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = result.detail))
        val route = (result as RouteResult.Ready).route
        if ((!route.external && route.graphVersion != request.pinnedVersion) || route.source != current.route?.source || route.provider != current.route?.provider || route.profile != request.request.profile || route.destinationPoint != request.request.destinationPoint || route.regionId != request.request.regionId || route.destinationPlaceId != request.request.destinationPlaceId || route.entranceId!=request.request.entranceId || route.requestId != request.requestId || RouteIntegrity.shapeErrors(route).isNotEmpty()) {
            return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = "Replacement route does not match the current trip"))
        }
        val fix=latestFix
        val signal=qualityReason(fix,nowElapsedMillis ?: fix?.elapsedMillis ?: 0)
        if (signal!=null) return@withLock publish(current.copy(status=SessionStatus.SignalLimited,signalReason=signal))
        requireNotNull(fix)
        val now = nowElapsedMillis ?: fix.elapsedMillis
        val latency = now - request.originFixElapsedMillis
        val traveledSeconds = (fix.elapsedMillis - request.originFixElapsedMillis) / 1000.0
        val originSnap = request.request.origin.distanceTo(route.geometry.first())
        val speedLimit = if (route.profile == "driving") policy.maxDrivingMetersPerSecond else policy.maxWalkingMetersPerSecond
        val movementBudget = traveledSeconds.coerceAtLeast(0.0) * speedLimit + 8.0 + min(fix.accuracyMeters, 10.0)
        val projected = projectPath(fix.point, route.geometry)
        // A route is calculated from the requesting fix. A moving driver need not still be at that
        // point when the response arrives, but cannot jump to a later loop or use an old response.
        if (latency !in 0L..30_000L || traveledSeconds < 0 ||
            originSnap > (if (route.external) 100.0 else 20.0) ||
            request.request.origin.distanceTo(fix.point) > movementBudget || projected == null ||
            projected.distanceMeters > max(policy.offRouteMeters, fix.accuracyMeters * 1.5) ||
            projected.alongMeters > movementBudget + originSnap) {
            return@withLock publish(current.copy(status=SessionStatus.GuidanceUnavailable,signalReason="Position changed beyond the replacement route; replan from a confirmed position"))
        }
        val progress = route.shapeToRouteMeters(projected.alongMeters).coerceIn(0.0, route.distanceMeters)
        val step = route.maneuvers.indexOfFirst { it.alongMeters > progress + 2 }.let { if (it < 0) route.maneuvers.lastIndex else it }
        val maneuver = route.maneuvers[step]
        deviationCount = 0; arrivalCount = 0
        lastAccepted = fix
        publish(current.copy(route = route, status = SessionStatus.Navigating, alongMeters = progress, maneuverIndex = step,
            remainingMeters = (route.distanceMeters - progress).coerceAtLeast(0.0), remainingSeconds = route.remainingSecondsAt(progress), offRoute = false, signalReason = null,
            location = fix.point, accuracyMeters = fix.accuracyMeters,
            instruction = maneuver.instruction,
            distanceToManeuver = (maneuver.alongMeters - progress).coerceAtLeast(0.0)), "Route updated")
    }

    private fun resetTracking() {
        lastAccepted = null; deviationCount = 0; arrivalCount = 0; pendingReroute = null; lastRerouteAt = null
    }

    private fun qualityReason(fix: LocationFix?, now: Long): String? = when {
        fix == null -> "Waiting for a fresh location"
        !fix.point.valid -> "Invalid location coordinate"
        !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0 || fix.accuracyMeters > policy.maxAccuracyMeters -> "Location accuracy is insufficient"
        fix.elapsedMillis < 0 || now < fix.elapsedMillis || now - fix.elapsedMillis > policy.maxFixAgeMillis -> "Location fix is stale"
        else -> null
    }

    private fun publish(snapshot: SessionSnapshot, speak: String? = null, reroute: RerouteRequest? = null): SessionUpdate {
        mutable.value = snapshot
        return SessionUpdate(snapshot, speak, reroute)
    }

    companion object {
        val ACTIVE = setOf(SessionStatus.Navigating, SessionStatus.SignalLimited, SessionStatus.Rerouting, SessionStatus.GuidanceUnavailable)
    }
}
