package com.rohanc.navgate.navigation

import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.RouteResponse
import com.rohanc.navgate.model.RouteStep
import kotlin.math.abs

class NavigationEngine {
    private var route: RouteResponse? = null
    private var origin: Coordinate? = null
    private var destination: Coordinate? = null
    private var stepIndex: Int = 0
    private var isNavigating = false
    private var isArrived = false
    private var isRerouting = false
    private var presentationMode = PresentationMode.MAP
    private var arAvailability: ArAvailabilityState = ArAvailabilityState.Checking
    private var shouldSpeakInstruction = false

    fun selectRoute(origin: Coordinate, destination: Coordinate, route: RouteResponse): NavigationSnapshot {
        this.origin = origin
        this.destination = destination
        this.route = route
        this.stepIndex = 0
        this.isNavigating = false
        this.isArrived = false
        this.isRerouting = false
        this.presentationMode = PresentationMode.MAP
        this.shouldSpeakInstruction = false
        return buildSnapshot(userLocation = origin, userHeading = null, lastFixAgeMillis = 0)
    }

    fun setPresentationMode(mode: PresentationMode) {
        presentationMode = mode
    }

    fun setArAvailability(state: ArAvailabilityState) {
        arAvailability = state
    }

    fun startNavigation(): NavigationSnapshot {
        isNavigating = route != null
        isArrived = false
        shouldSpeakInstruction = isNavigating
        return buildSnapshot(userLocation = origin, userHeading = null, lastFixAgeMillis = 0)
    }

    fun stopNavigation(): NavigationSnapshot {
        isNavigating = false
        isArrived = false
        return buildSnapshot(userLocation = origin, userHeading = null, lastFixAgeMillis = 0)
    }

    fun markRerouting(value: Boolean) {
        isRerouting = value
    }


    fun replaceActiveRoute(
        origin: Coordinate,
        destination: Coordinate,
        route: RouteResponse,
        userLocation: Coordinate,
        userHeading: Double?,
        lastFixAgeMillis: Long,
    ): NavigationSnapshot {
        this.origin = origin
        this.destination = destination
        this.route = route
        this.stepIndex = 0
        this.isNavigating = true
        this.isArrived = false
        this.isRerouting = false
        this.shouldSpeakInstruction = true
        return buildSnapshot(userLocation, userHeading, lastFixAgeMillis)
    }

    fun updateUserProgress(
        userLocation: Coordinate,
        userHeading: Double?,
        lastFixAgeMillis: Long,
    ): NavigationSnapshot {
        val currentRoute = route
        if (currentRoute != null && isNavigating) {
            val currentStep = currentRoute.steps.getOrNull(stepIndex)
            if (currentStep != null) {
                val distanceToStep = userLocation.distanceTo(currentStep.target)
                if (distanceToStep < STEP_REACHED_DISTANCE_METERS) {
                    if (stepIndex < currentRoute.steps.lastIndex) {
                        stepIndex += 1
                        shouldSpeakInstruction = true
                    } else {
                        isNavigating = false
                        isArrived = true
                        shouldSpeakInstruction = true
                    }
                }
            }
        }
        return buildSnapshot(userLocation, userHeading, lastFixAgeMillis)
    }

    fun shouldReroute(userLocation: Coordinate): Boolean {
        val currentPath = route?.pathCoordinates.orEmpty()
        if (currentPath.isEmpty()) return false
        val nearestDistance = currentPath.minOf { point -> point.distanceTo(userLocation) }
        return nearestDistance > OFF_ROUTE_DISTANCE_METERS
    }

    fun headingDelta(userHeading: Double?, userLocation: Coordinate? = null): Double? {
        val step = route?.steps?.getOrNull(stepIndex) ?: return null
        val currentPosition = userLocation ?: this.origin ?: return null
        val currentHeading = userHeading ?: return null
        val targetBearing = currentPosition.bearingTo(step.target)
        val raw = targetBearing - currentHeading
        return when {
            raw > 180 -> raw - 360
            raw < -180 -> raw + 360
            else -> raw
        }
    }

    private fun buildSnapshot(
        userLocation: Coordinate?,
        userHeading: Double?,
        lastFixAgeMillis: Long,
    ): NavigationSnapshot {
        val currentRoute = route
        val currentStep = currentRoute?.steps?.getOrNull(stepIndex)
        val distanceToStep =
            if (isArrived) {
                0.0
            } else if (currentStep != null && userLocation != null) {
                userLocation.distanceTo(currentStep.target)
            } else {
                currentStep?.distanceMeters ?: 0.0
            }

        val locationConfidence = classifyLocationConfidence(lastFixAgeMillis)
        val headingConfidence = classifyHeadingConfidence(userHeading)
        val isOffRoute = userLocation?.let(::shouldReroute) ?: false
        val guidanceConfidence =
            classifyGuidanceConfidence(
                route = currentRoute,
                location = locationConfidence,
                heading = headingConfidence,
                isOffRoute = isOffRoute,
                arAvailability = arAvailability,
                isArrived = isArrived,
            )

        return NavigationSnapshot(
            origin = origin,
            destination = destination,
            route = currentRoute,
            currentStepIndex = stepIndex,
            currentInstruction =
                when {
                    isArrived -> "You have arrived at your destination"
                    currentStep != null -> currentStep.instruction
                    else -> "Choose a route to begin"
                },
            distanceToNextStep = distanceToStep,
            etaSeconds = if (isArrived) 0.0 else remainingEta(currentRoute, stepIndex, distanceToStep),
            isOffRoute = isOffRoute,
            isRerouting = isRerouting,
            locationConfidence = locationConfidence,
            headingConfidence = headingConfidence,
            guidanceConfidence = guidanceConfidence,
            arAvailability = arAvailability,
            presentationMode = presentationMode,
            isNavigating = isNavigating,
            isArrived = isArrived,
            userLocation = userLocation,
            userHeading = userHeading,
            shouldSpeakInstruction = consumeSpokenFlag(),
        )
    }

    private fun remainingEta(route: RouteResponse?, stepIndex: Int, currentLegDistance: Double): Double {
        if (route == null) return 0.0
        val remainingPlanned = route.steps.drop(stepIndex + 1).sumOf(RouteStep::distanceMeters)
        return (remainingPlanned + currentLegDistance) / metersPerSecondForRoute(route)
    }

    private fun classifyLocationConfidence(lastFixAgeMillis: Long): SignalConfidence = when {
        lastFixAgeMillis <= 3_000 -> SignalConfidence(GuidanceConfidence.High, "Live GPS is fresh")
        lastFixAgeMillis <= 8_000 -> SignalConfidence(GuidanceConfidence.Medium, "GPS is slightly delayed")
        else -> SignalConfidence(GuidanceConfidence.Low, "GPS update is stale")
    }

    private fun classifyHeadingConfidence(userHeading: Double?): SignalConfidence = when {
        userHeading == null -> SignalConfidence(GuidanceConfidence.Low, "Compass unavailable")
        userHeading.isNaN() -> SignalConfidence(GuidanceConfidence.Low, "Compass unstable")
        else -> SignalConfidence(GuidanceConfidence.High, "Heading locked")
    }

    private fun classifyGuidanceConfidence(
        route: RouteResponse?,
        location: SignalConfidence,
        heading: SignalConfidence,
        isOffRoute: Boolean,
        arAvailability: ArAvailabilityState,
        isArrived: Boolean,
    ): SignalConfidence {
        if (route == null) return SignalConfidence(GuidanceConfidence.Low, "No active route")
        if (isArrived) return SignalConfidence(GuidanceConfidence.High, "Destination reached")
        if (isOffRoute) return SignalConfidence(GuidanceConfidence.Low, "Route deviation detected")
        if (location.label == GuidanceConfidence.Low) {
            return SignalConfidence(GuidanceConfidence.Low, "Weak location signal")
        }
        if (arAvailability != ArAvailabilityState.Supported || heading.label == GuidanceConfidence.Low) {
            return SignalConfidence(GuidanceConfidence.Medium, "Map guidance is stronger than AR right now")
        }
        return SignalConfidence(GuidanceConfidence.High, "Route and heading are aligned")
    }

    companion object {
        private const val STEP_REACHED_DISTANCE_METERS = 28.0
        private const val OFF_ROUTE_DISTANCE_METERS = 45.0
    }

    private fun metersPerSecondForRoute(route: RouteResponse): Double =
        if (route.travelMode.equals("driving", ignoreCase = true)) 8.0 else 1.35

    private fun consumeSpokenFlag(): Boolean {
        val current = shouldSpeakInstruction
        shouldSpeakInstruction = false
        return current
    }
}
