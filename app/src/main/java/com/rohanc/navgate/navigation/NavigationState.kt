package com.rohanc.navgate.navigation

import com.rohanc.navgate.ar.ArAvailabilityState
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.ManeuverType
import com.rohanc.navgate.model.RouteResponse


enum class PresentationMode {
    MAP,
    AR_ASSIST,
}

enum class GuidanceConfidence {
    High,
    Medium,
    Low,
}

data class SignalConfidence(
    val label: GuidanceConfidence,
    val reason: String,
)

data class NavigationSnapshot(
    val origin: Coordinate? = null,
    val destination: Coordinate? = null,
    val route: RouteResponse? = null,
    val currentStepIndex: Int = 0,
    val currentInstruction: String = "Select a route",
    val nextInstructionHint: String? = null,
    val currentManeuverType: ManeuverType = ManeuverType.Start,
    val distanceToNextStep: Double = 0.0,
    val etaSeconds: Double = 0.0,
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val locationConfidence: SignalConfidence = SignalConfidence(GuidanceConfidence.Low, "Waiting for live GPS"),
    val headingConfidence: SignalConfidence = SignalConfidence(GuidanceConfidence.Low, "Need heading lock"),
    val guidanceConfidence: SignalConfidence = SignalConfidence(GuidanceConfidence.Low, "No active route"),
    val arAvailability: ArAvailabilityState = ArAvailabilityState.Checking,
    val presentationMode: PresentationMode = PresentationMode.MAP,
    val isNavigating: Boolean = false,
    val isArrived: Boolean = false,
    val userLocation: Coordinate? = null,
    val userHeading: Double? = null,
    val shouldSpeakInstruction: Boolean = false,
) {
    val canStartNavigation: Boolean = route != null && !isNavigating
}
