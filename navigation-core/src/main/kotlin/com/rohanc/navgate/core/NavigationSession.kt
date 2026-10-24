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
data class LocationFix(val point: GeoPoint, val elapsedMillis: Long, val accuracyMeters: Double, val heading: Double? = null, val speedMps: Double? = null)

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
    /** Position projected onto the route while on it, and the road direction there: where the vehicle is drawn. */
    val snappedLocation: GeoPoint? = null,
    val routeBearing: Double? = null,
    /** Speed along the route and the fix it was measured at, so a screen can glide the vehicle between fixes without jumping. */
    val speedAlongMps: Double = 0.0,
    val fixElapsedMillis: Long = 0,
    /** False once the vehicle has left the planned road; screens then stop snapping it to the route. */
    val lockedToRoute: Boolean = true,
    val backupRoutes: Int = 0,
)

@Serializable
data class SessionCheckpoint(val snapshot: SessionSnapshot)

data class RerouteRequest(val sessionId: String, val requestId: Long, val request: PlanRequest, val pinnedVersion: String?, val originFixElapsedMillis: Long = 0)

data class SessionUpdate(val snapshot: SessionSnapshot, val speak: String? = null, val reroute: RerouteRequest? = null)

data class GuidancePolicy(
    val maxFixAgeMillis: Long = 8_000,
    val maxAccuracyMeters: Double = 25.0,
    /** Once a trip is running, city canyons and tunnels exits are tolerated up to this accuracy instead of the stricter start limit. */
    val maxActiveAccuracyMeters: Double = 45.0,
    val offRouteMeters: Double = 30.0,
    val deviationSamples: Int = 3,
    val arrivalMeters: Double = 10.0,
    val drivingArrivalMeters: Double = 25.0,
    val arrivalSamples: Int = 2,
    val rerouteCooldownMillis: Long = 12_000,
    val maxWalkingMetersPerSecond: Double = 4.0,
    val maxDrivingMetersPerSecond: Double = 70.0,
    /** A fix that is only briefly poor or odd is ignored for this long instead of interrupting guidance. */
    val coastMillis: Long = 6_000,
    /** With no fix at all, guidance keeps advancing along the route at the last speed for this long before it admits it has lost you. */
    val deadReckonMillis: Long = 25_000,
) {
    init {
        require(maxFixAgeMillis>0 && maxAccuracyMeters.isFinite() && maxAccuracyMeters>0 && maxActiveAccuracyMeters>=maxAccuracyMeters)
        require(offRouteMeters.isFinite() && offRouteMeters>0 && arrivalMeters.isFinite() && arrivalMeters>0 && drivingArrivalMeters.isFinite() && drivingArrivalMeters>=arrivalMeters)
        require(deviationSamples>=2 && arrivalSamples>=2 && rerouteCooldownMillis>=0)
        require(maxWalkingMetersPerSecond.isFinite() && maxWalkingMetersPerSecond>0)
        require(maxDrivingMetersPerSecond.isFinite() && maxDrivingMetersPerSecond>0 && coastMillis>=0)
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
    private var tracker: RouteTracker? = null
    private var trackedRouteId: String? = null
    private var jumpCandidate: LocationFix? = null
    private var backups: List<CampusRoute> = emptyList()
    private var aheadStreak = 0
    private var lastLateralMeters = 0.0
    private var lastLateralAtMillis = 0L
    private var ambiguousStreak = 0
    private var announcedStep = -1
    private var announcedLevel = 0

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
        // No new fix for a moment (a tunnel, a canyon): keep the trip moving along the route at the last speed instead of freezing it.
        val route = state.route; val tr = tracker; val age = latestFix?.let { nowElapsedMillis - it.elapsedMillis }
        if (state.status == SessionStatus.Navigating && route != null && tr != null && age != null && age > 1_500 && age <= policy.deadReckonMillis && state.lockedToRoute && !state.offRoute) {
            val p = tr.predict(nowElapsedMillis)
            val progress = max(state.alongMeters, route.shapeToRouteMeters(p.alongMeters))
            // It never declares arrival without a real fix: stop just short of the end.
            val capped = min(progress, max(0.0, route.distanceMeters - policy.drivingArrivalMeters - 5.0))
            val step = route.maneuvers.indexOfFirst { it.alongMeters > capped + 2 }.let { if (it < 0) route.maneuvers.lastIndex.coerceAtLeast(0) else it }
            val maneuver = route.maneuvers.getOrNull(step)
            val next = state.copy(alongMeters = capped, maneuverIndex = step, remainingMeters = max(0.0, route.distanceMeters - capped), remainingSeconds = route.remainingSecondsAt(capped),
                distanceToManeuver = max(0.0, (maneuver?.alongMeters ?: route.distanceMeters) - capped), instruction = maneuver?.instruction ?: state.instruction,
                snappedLocation = p.point, routeBearing = p.bearingDegrees ?: state.routeBearing, speedAlongMps = p.speedMps, fixElapsedMillis = nowElapsedMillis,
                signalReason = "No GPS signal: following the route")
            val spoken = if (step != state.maneuverIndex) onNewStep(next) else headsUp(next)
            return@withLock publish(next, spoken)
        }
        val reason = qualityReason(latestFix, nowElapsedMillis, state.status in ACTIVE)
        if (state.status in ACTIVE && reason != null) {
            deviationCount = 0; arrivalCount = 0
            publish(state.copy(status = SessionStatus.SignalLimited, signalReason = reason))
        } else SessionUpdate(state)
    }

    suspend fun location(fix: LocationFix, nowElapsedMillis: Long): SessionUpdate = mutex.withLock {
        val current = mutable.value
        // Out-of-order samples do not refresh freshness or accrue arrival/deviation evidence.
        if (latestFix != null && fix.elapsedMillis <= latestFix!!.elapsedMillis) return@withLock SessionUpdate(current)
        val issue = qualityReason(fix, nowElapsedMillis, current.status in ACTIVE)
        if (issue != null) {
            // A single poor fix in the middle of a good trip is ignored: the tracker keeps the vehicle moving on the route.
            val recentlyGood = lastAccepted?.let { nowElapsedMillis - it.elapsedMillis <= policy.coastMillis } == true
            if (current.status in ACTIVE && recentlyGood && issue == "Location accuracy is insufficient") return@withLock SessionUpdate(current)
            arrivalCount = 0; deviationCount = 0
            return@withLock if (current.status in ACTIVE) publish(current.copy(status = SessionStatus.SignalLimited, signalReason = issue)) else publish(current.copy(signalReason = issue))
        }
        latestFix = fix
        if (current.status !in ACTIVE) return@withLock publish(current.copy(location = fix.point, accuracyMeters = fix.accuracyMeters, signalReason = null))
        val route = current.route ?: return@withLock SessionUpdate(current)
        val previous = lastAccepted
        val elapsed = previous?.let { (fix.elapsedMillis - it.elapsedMillis) / 1000.0 } ?: 0.0
        // Elapsed time is not capped: after a signal outage the user may legitimately be far from the last accepted fix.
        // A car cannot suddenly move at 70 m/s: the allowance follows the speed it has actually been doing.
        val speedAllowance = if (route.profile == "driving") min(policy.maxDrivingMetersPerSecond, max(25.0, 2.0 * current.speedAlongMps + 8.0)) else policy.maxWalkingMetersPerSecond
        val movementBudget = (elapsed.coerceAtLeast(0.0) * speedAllowance + 8.0)
        var relocated = false
        if (previous != null && previous.point.distanceTo(fix.point) > movementBudget + min(previous.accuracyMeters + fix.accuracyMeters, 20.0)) {
            arrivalCount = 0; deviationCount = 0
            val earlier = jumpCandidate
            jumpCandidate = fix
            val sinceGood = previous?.let { fix.elapsedMillis - it.elapsedMillis } ?: Long.MAX_VALUE
            // Within a few seconds of a good fix, an impossible jump is a glitch: ignore it quietly, guidance carries on along the route.
            if (sinceGood <= policy.coastMillis) return@withLock SessionUpdate(current)
            // After a longer gap, two fixes in a row that agree with each other are a real relocation (tunnel exit), not a glitch.
            if (earlier == null || earlier.point.distanceTo(fix.point) > 25.0 + fix.accuracyMeters) {
                return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Implausible location jump; waiting for a stable fix"))
            }
            jumpCandidate = null; tracker?.reset(); relocated = true
        } else jumpCandidate = null
        val windowed = projectPath(fix.point, route.geometry,
            minAlong = route.routeToShapeMeters(max(0.0, current.alongMeters - BACK_WINDOW_METERS)), maxAlong = if (relocated) Double.POSITIVE_INFINITY else route.routeToShapeMeters(current.alongMeters) + movementBudget)
        val fullProjection = projectPath(fix.point, route.geometry)
        // On the route but well behind where the trip had got to: heading back the way it came.
        val behind = windowed == null && fullProjection != null && fullProjection.distanceMeters <= max(policy.offRouteMeters, fix.accuracyMeters * 1.5) &&
            fullProjection.alongMeters < route.routeToShapeMeters(current.alongMeters) - BACK_WINDOW_METERS && (fix.speedMps ?: 5.0) > 2.0
        var projection = windowed ?: if (behind) fullProjection else null
        // On the route but further along than the trip had got to (for example after the app was suspended, or progress lagged):
        // two such fixes in a row are real progress, so jump the trip forward instead of reporting "ambiguous" for ever.
        if (projection == null && fullProjection != null && fullProjection.distanceMeters <= max(policy.offRouteMeters, fix.accuracyMeters * 1.5)) {
            aheadStreak++
            if (aheadStreak >= 2) { projection = fullProjection; tracker?.reset(); aheadStreak = 0 }
        } else if (projection != null) aheadStreak = 0
        if (projection == null || (projection.distanceMeters > policy.offRouteMeters && (fullProjection?.distanceMeters ?: Double.POSITIVE_INFINITY) < 10)) {
            arrivalCount = 0; deviationCount = 0
            // One or two odd readings are ignored; only a persistent mismatch is reported.
            if (++ambiguousStreak < 3 && current.status == SessionStatus.Navigating) return@withLock SessionUpdate(current)
            return@withLock publish(current.copy(status = SessionStatus.SignalLimited, signalReason = "Route progress is ambiguous; replan from a confirmed position"))
        }
        ambiguousStreak = 0
        // A car cannot slide sideways 100 m in a second. A fix that lands far off the road, faster than any vehicle could get there,
        // is a GPS glitch (multipath, a bad network fix), not a driver leaving the route: ignore it and let the road decide.
        val sinceLateral = ((fix.elapsedMillis - lastLateralAtMillis).coerceAtLeast(0L)) / 1000.0
        val lateralAllowance = lastLateralMeters + max(fix.speedMps ?: 5.0, 3.0) * 1.5 * sinceLateral + 25.0
        if (lastLateralAtMillis != 0L && projection.distanceMeters > lateralAllowance && current.status == SessionStatus.Navigating && !relocated) {
            deviationCount = 0
            return@withLock SessionUpdate(current)
        }
        lastLateralMeters = projection.distanceMeters; lastLateralAtMillis = fix.elapsedMillis
        lastAccepted = fix
        // Driving the wrong way along the route is a deviation too, even though the car is still on the road.
        val trackedEarly = trackerFor(route).update(fix.point, fix.accuracyMeters, fix.speedMps, fix.elapsedMillis, courseOf(fix))
        val offRoute = projection.distanceMeters > max(policy.offRouteMeters, fix.accuracyMeters * 1.5) || trackedEarly.reversing || behind
        deviationCount = if (offRoute) deviationCount + 1 else 0
        // The route-locked tracker smooths progress: GPS sideways noise and brief glitches cannot move it forward or back.
        val tracked = trackedEarly
        val trackedAlong = route.shapeToRouteMeters(tracked.alongMeters)
        // No ratchet: the tracker never runs backwards on its own, and a ratchet would leave one early overshoot in place for the rest of the trip.
        val progress = if (offRoute) current.alongMeters else if (tracked.locked) trackedAlong else max(current.alongMeters, route.shapeToRouteMeters(projection.alongMeters))
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
        // Off the planned road but on a backup route computed earlier: switch at once, no network round trip.
        if (!arrived && canReroute) {
            backupAt(fix)?.let { (backup, along) ->
                backups = emptyList(); tracker = null; trackedRouteId = null
                val moved = max(0.0, along)
                val nextStep = backup.maneuvers.indexOfFirst { it.alongMeters > moved + 2 }.let { if (it < 0) backup.maneuvers.lastIndex.coerceAtLeast(0) else it }
                val man = backup.maneuvers.getOrNull(nextStep)
                deviationCount = 0; arrivalCount = 0; lastAccepted = fix
                val switched = trackerFor(backup).update(fix.point, fix.accuracyMeters, fix.speedMps, fix.elapsedMillis, courseOf(fix))
                return@withLock publish(current.copy(route = backup, status = SessionStatus.Navigating, alongMeters = moved, maneuverIndex = nextStep,
                    remainingMeters = (backup.distanceMeters - moved).coerceAtLeast(0.0), remainingSeconds = backup.remainingSecondsAt(moved), offRoute = false, signalReason = null,
                    location = fix.point, accuracyMeters = fix.accuracyMeters, instruction = man?.instruction ?: "Follow the route",
                    distanceToManeuver = ((man?.alongMeters ?: backup.distanceMeters) - moved).coerceAtLeast(0.0),
                    snappedLocation = switched.point, routeBearing = switched.bearingDegrees, speedAlongMps = switched.speedMps, fixElapsedMillis = fix.elapsedMillis, lockedToRoute = true, backupRoutes = 0), "Route updated")
            }
        }
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
            signalReason = if (offRoute) "Route deviation detected" else null, offRoute = offRoute,
            snappedLocation = if (offRoute) null else tracked.point,
            routeBearing = if (offRoute) null else tracked.bearingDegrees ?: route.geometry.getOrNull(projection.segment)?.let { a -> route.geometry.getOrNull(projection.segment + 1)?.let { b -> a.bearingTo(b) } },
            speedAlongMps = if (offRoute) 0.0 else tracked.speedMps, fixElapsedMillis = fix.elapsedMillis, lockedToRoute = !offRoute && tracked.locked, backupRoutes = backups.size)
        if (arrived) pendingReroute = null
        val spoken = if (arrived) next.instruction else if (step != current.maneuverIndex) onNewStep(next) else headsUp(next)
        publish(next, spoken, reroute)
    }

    /**
     * The vehicle has passed a turn and the next one is now ahead. Say it only if it is close enough to matter, in the same
     * "In 300 metres, ..." form as the heads-up (so it is never said twice); leaving a roundabout is said as it happens;
     * arrival is only ever said when it really happens.
     */
    private fun onNewStep(s: SessionSnapshot): String? {
        val maneuver = s.route?.maneuvers?.getOrNull(s.maneuverIndex) ?: return null
        announcedStep = s.maneuverIndex; announcedLevel = 0
        return when (maneuver.kind) {
            "Arrive" -> null
            "RoundaboutExit" -> { announcedLevel = levelFor(s); maneuver.instruction }
            else -> headsUp(s)
        }
    }

    /** How many of the "get ready" distances the vehicle is already inside for the next turn (0, 1 or 2). */
    private fun levelFor(s: SessionSnapshot): Int {
        val tiers = if (s.route?.profile == "driving") listOf(400.0, 120.0) else listOf(60.0, 20.0)
        return tiers.count { s.distanceToManeuver <= it }
    }

    /** "In 400 metres, turn right onto ...": spoken once at each distance, never twice for the same turn. */
    private fun headsUp(s: SessionSnapshot): String? {
        if (s.status != SessionStatus.Navigating || s.offRoute) return null
        val maneuver = s.route?.maneuvers?.getOrNull(s.maneuverIndex) ?: return null
        if (maneuver.kind == "Arrive" || maneuver.kind == "Start" || maneuver.kind == "RoundaboutExit") return null
        if (announcedStep != s.maneuverIndex) { announcedStep = s.maneuverIndex; announcedLevel = 0 }
        val level = levelFor(s)
        if (level <= announcedLevel) return null
        announcedLevel = level
        val metres = s.distanceToManeuver
        val rounded = if (metres >= 200) (Math.round(metres / 50.0) * 50).toInt() else (Math.round(metres / 10.0) * 10).toInt()
        val text = maneuver.instruction.trim().trimEnd('.')
        return "In $rounded metres, ${text.replaceFirstChar { it.lowercase() }}"
    }

    suspend fun replace(request: RerouteRequest, result: RouteResult, nowElapsedMillis: Long? = null): SessionUpdate = mutex.withLock {
        val current = mutable.value
        if (pendingReroute != request || current.sessionId != request.sessionId || current.status !in ACTIVE) return@withLock SessionUpdate(current)
        pendingReroute = null
        if (result is RouteResult.Unavailable) return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = result.detail))
        val route = (result as RouteResult.Ready).route
        if ((!route.external && route.graphVersion != request.pinnedVersion) || route.external != current.route?.external || route.profile != request.request.profile || route.destinationPoint != request.request.destinationPoint || route.regionId != request.request.regionId || route.destinationPlaceId != request.request.destinationPlaceId || route.entranceId!=request.request.entranceId || route.requestId != request.requestId || RouteIntegrity.shapeErrors(route).isNotEmpty()) {
            return@withLock publish(current.copy(status = SessionStatus.GuidanceUnavailable, signalReason = "Replacement route does not match the current trip"))
        }
        val fix=latestFix
        val signal=qualityReason(fix,nowElapsedMillis ?: fix?.elapsedMillis ?: 0,true)
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
        deviationCount = 0; arrivalCount = 0; backups = emptyList()
        lastAccepted = fix
        publish(current.copy(route = route, backupRoutes = 0, status = SessionStatus.Navigating, alongMeters = progress, maneuverIndex = step,
            remainingMeters = (route.distanceMeters - progress).coerceAtLeast(0.0), remainingSeconds = route.remainingSecondsAt(progress), offRoute = false, signalReason = null,
            location = fix.point, accuracyMeters = fix.accuracyMeters,
            instruction = maneuver.instruction,
            distanceToManeuver = (maneuver.alongMeters - progress).coerceAtLeast(0.0)), "Route updated")
    }

    /** The tracker for [route]; created once per route and discarded when the route changes. */
    private fun courseOf(fix: LocationFix): Double? = fix.heading?.takeIf { (fix.speedMps ?: 0.0) >= COURSE_MIN_SPEED_MPS }

    private fun trackerFor(route: CampusRoute): RouteTracker {
        if (trackedRouteId != route.id || tracker == null) { tracker = RouteTracker(RouteGeometry(route.geometry), route.profile != "driving"); trackedRouteId = route.id }
        return tracker!!
    }

    /**
     * Alternative routes to the same destination, computed ahead of time while the connection was good. If the vehicle leaves
     * the planned road onto one of them, guidance switches over immediately instead of waiting for a new route from the network.
     */
    suspend fun setBackups(routes: List<CampusRoute>): SessionUpdate = mutex.withLock {
        val current = mutable.value
        val route = current.route
        backups = if (route == null || current.status !in ACTIVE) emptyList() else routes.filter { candidate ->
            candidate.id != route.id && candidate.profile == route.profile && candidate.source == route.source && candidate.provider == route.provider &&
                candidate.regionId == route.regionId && candidate.destinationPlaceId == route.destinationPlaceId &&
                (candidate.destinationPoint == null || route.destinationPoint == null || candidate.destinationPoint.distanceTo(route.destinationPoint) < 5.0) &&
                RouteIntegrity.shapeErrors(candidate).isEmpty()
        }.take(3)
        publish(current.copy(backupRoutes = backups.size))
    }

    /** A backup route the vehicle is on right now, with how far along it is (route metres), or null. */
    private fun backupAt(fix: LocationFix): Pair<CampusRoute, Double>? =
        backups.mapNotNull { candidate ->
            val hit = projectPath(fix.point, candidate.geometry) ?: return@mapNotNull null
            if (hit.distanceMeters > max(20.0, fix.accuracyMeters * 1.2) || hit.alongMeters > candidate.geometry.lengthMeters() - 10) return@mapNotNull null
            Triple(candidate, candidate.shapeToRouteMeters(hit.alongMeters), hit.distanceMeters)
        }.minByOrNull { it.third }?.let { it.first to it.second }

    private fun resetTracking() {
        tracker = null; trackedRouteId = null; jumpCandidate = null; backups = emptyList(); announcedStep = -1; announcedLevel = 0; aheadStreak = 0; ambiguousStreak = 0; lastLateralMeters = 0.0; lastLateralAtMillis = 0L
        lastAccepted = null; deviationCount = 0; arrivalCount = 0; pendingReroute = null; lastRerouteAt = null
    }

    private fun qualityReason(fix: LocationFix?, now: Long, active: Boolean = false): String? = when {
        fix == null -> "Waiting for a fresh location"
        !fix.point.valid -> "Invalid location coordinate"
        !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0 || fix.accuracyMeters > (if (active) policy.maxActiveAccuracyMeters else policy.maxAccuracyMeters) -> "Location accuracy is insufficient"
        fix.elapsedMillis < 0 || now < fix.elapsedMillis || now - fix.elapsedMillis > policy.maxFixAgeMillis -> "Location fix is stale"
        else -> null
    }

    private fun publish(snapshot: SessionSnapshot, speak: String? = null, reroute: RerouteRequest? = null): SessionUpdate {
        mutable.value = snapshot
        return SessionUpdate(snapshot, speak, reroute)
    }

    companion object {
        /** How far behind the trip's progress a fix may still be matched to the route. The tracker predicts ahead, so a car that stops or turns off is behind its own prediction. */
        /** GPS heading is only trustworthy while moving; slower than this it is noise. */
        const val COURSE_MIN_SPEED_MPS = 3.0
        const val BACK_WINDOW_METERS = 60.0
        val ACTIVE = setOf(SessionStatus.Navigating, SessionStatus.SignalLimited, SessionStatus.Rerouting, SessionStatus.GuidanceUnavailable)
    }
}
