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

data class RerouteRequest(val sessionId: String, val requestId: Long, val request: PlanRequest, val pinnedVersion: String)

data class SessionUpdate(val snapshot: SessionSnapshot, val speak: String? = null, val reroute: RerouteRequest? = null)

data class GuidancePolicy(
    val maxFixAgeMillis: Long = 8_000,
    val maxAccuracyMeters: Double = 25.0,
    val offRouteMeters: Double = 30.0,
    val deviationSamples: Int = 3,
    val arrivalMeters: Double = 10.0,
    val arrivalSamples: Int = 2,
    val rerouteCooldownMillis: Long = 12_000,
    val maxWalkingMetersPerSecond: Double = 4.0,
)

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
        if (near > 20.0) return@withLock publish(current.copy(signalReason = "Move to the planned start or replan from your location"))
        if (resumed && abs((expected?.alongMeters ?: 0.0) - current.alongMeters) > 40.0) {
            return@withLock publish(current.copy(signalReason = "Position changed since the checkpoint; replan before resuming"))
        }
        resetTracking()
        lastAccepted = fix
        publish(current.copy(sessionId = current.sessionId ?: UUID.randomUUID().toString(), status = SessionStatus.Navigating,
            location = fix.point, accuracyMeters = fix.accuracyMeters, signalReason = null), current.instruction)
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
        if (saved.route == null || !graphAvailable || saved.status !in ACTIVE) return@withLock publish(SessionSnapshot(signalReason = "Saved trip cannot be resumed"))
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
        val movementBudget = (elapsed.coerceIn(0.0, 30.0) * policy.maxWalkingMetersPerSecond + 8.0)
        if (previous != null && previous.point.distanceTo(fix.point) > movementBudget + min(previous.accuracyMeters + fix.accuracyMeters, 20.0)) {
            arrivalCount = 0; deviationCount = 0
            return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Implausible location jump; waiting for a stable fix"))
        }
        val projection = projectPath(fix.point, route.geometry,
            minAlong = max(0.0, current.alongMeters - 15), maxAlong = current.alongMeters + movementBudget)
        val fullProjection = projectPath(fix.point, route.geometry)
        if (projection == null || (projection.distanceMeters > policy.offRouteMeters && (fullProjection?.distanceMeters ?: Double.POSITIVE_INFINITY) < 10)) {
            arrivalCount = 0; deviationCount = 0
            return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Route progress is ambiguous; replan from a confirmed position"))
        }
        lastAccepted = fix
        val offRoute = projection.distanceMeters > max(policy.offRouteMeters, fix.accuracyMeters * 1.5)
        deviationCount = if (offRoute) deviationCount + 1 else 0
        val progress = if (offRoute) current.alongMeters else max(current.alongMeters, projection.alongMeters)
        val remaining = max(0.0, route.distanceMeters - progress)
        val arrivalRadius = min(policy.arrivalMeters, max(4.0, fix.accuracyMeters))
        val atDestination = !offRoute && remaining <= policy.arrivalMeters && fix.point.distanceTo(route.geometry.last()) <= arrivalRadius
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
                PlanRequest(rerouteCounter, route.regionId, fix.point, route.destinationPlaceId, route.entranceId), route.graphVersion)
            pendingReroute = reroute
            lastRerouteAt = nowElapsedMillis
        }
        val nextStatus = when { arrived -> SessionStatus.Arrived; pendingReroute != null -> SessionStatus.Rerouting; current.status == SessionStatus.GuidanceUnavailable && offRoute -> SessionStatus.GuidanceUnavailable; else -> SessionStatus.Navigating }
        val next = current.copy(status = nextStatus, location = fix.point, accuracyMeters = fix.accuracyMeters,
            alongMeters = if (arrived) route.distanceMeters else progress, maneuverIndex = step,
            remainingMeters = if (arrived) 0.0 else remaining, remainingSeconds = if (arrived) 0.0 else remaining / CampusRoutingProvider.WALKING_SPEED,
            distanceToManeuver = if (arrived) 0.0 else max(0.0, (maneuver?.alongMeters ?: route.distanceMeters) - progress),
            instruction = if (arrived) "You have arrived at the selected entrance" else maneuver?.instruction ?: "Follow the mapped path",
            signalReason = if (offRoute) "Route deviation detected" else null, offRoute = offRoute)
        if (arrived) pendingReroute = null
        publish(next, if (arrived || step != current.maneuverIndex) next.instruction else null, reroute)
    }

    suspend fun replace(request: RerouteRequest, result: RouteResult): SessionUpdate = mutex.withLock {
        val current = mutable.value
        if (pendingReroute != request || current.sessionId != request.sessionId || current.status !in ACTIVE) return@withLock SessionUpdate(current)
        pendingReroute = null
        if (result is RouteResult.Unavailable) return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = result.detail))
        val route = (result as RouteResult.Ready).route
        if (route.graphVersion != request.pinnedVersion || route.regionId != request.request.regionId || route.destinationPlaceId != request.request.destinationPlaceId || route.requestId != request.requestId) {
            return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = "Replacement route does not match the current trip"))
        }
        deviationCount = 0; arrivalCount = 0
        publish(current.copy(route = route, status = SessionStatus.Navigating, alongMeters = 0.0, maneuverIndex = 0,
            remainingMeters = route.distanceMeters, remainingSeconds = route.estimatedSeconds, offRoute = false, signalReason = null,
            instruction = route.maneuvers.firstOrNull()?.instruction ?: "Route updated",
            distanceToManeuver = route.maneuvers.firstOrNull()?.alongMeters ?: 0.0), "Route updated")
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
