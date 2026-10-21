package com.rohanc.navgate.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.rohanc.navgate.core.BackendSnapshot
import com.rohanc.navgate.core.NavigationSession
import com.rohanc.navgate.core.SessionStatus
import com.rohanc.navgate.nav.NavigationService
import com.rohanc.navgate.ui.ar.GuidanceSpeech
import com.rohanc.navgate.ui.ar.VoiceAvailability
import com.rohanc.navgate.ui.ar.rememberOfflineSpeech
import com.rohanc.navgate.ui.state.NavGateUiState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal interface GuidanceNotifications {
    fun show(title: String, instruction: String)
    fun stop()
}

/** Trip operations collect flows directly: a stopped Activity also pauses Compose's frame clock. */
@Composable
internal fun GuidanceRuntime(
    backendState: StateFlow<BackendSnapshot>,
    uiState: StateFlow<NavGateUiState>,
    permission: Boolean,
    onSignalTick: () -> Unit,
    notifications: GuidanceNotifications? = null,
    speech: GuidanceSpeech? = null,
): VoiceAvailability {
    val context = LocalContext.current
    val platformNotifications = remember(context) { object : GuidanceNotifications {
        override fun show(title: String, instruction: String) = NavigationService.show(context, title, instruction)
        override fun stop() = NavigationService.stop(context)
    } }
    // Tests replace platform effects, keeping user trips, speech and notifications isolated.
    val speaker = if (speech == null) rememberOfflineSpeech() else speech
    val sink = notifications ?: platformNotifications
    val tick by rememberUpdatedState(onSignalTick)
    LaunchedEffect(backendState, uiState, permission, sink, speaker) {
        runGuidanceEffects(backendState, uiState, permission, { tick() }, sink, speaker)
    }
    return speaker.availability
}

internal suspend fun runGuidanceEffects(
    backendState: StateFlow<BackendSnapshot>,
    uiState: StateFlow<NavGateUiState>,
    permission: Boolean,
    onSignalTick: () -> Unit,
    notifications: GuidanceNotifications,
    speech: GuidanceSpeech,
): Nothing = coroutineScope {
    launch {
        backendState.map { it.session.status in NavigationSession.ACTIVE }.distinctUntilChanged().collectLatest { active ->
            if (active) while (true) { onSignalTick(); delay(1_000) }
        }
    }
    var showing = false
    var lastNotification: Pair<String, Int>? = null
    try {
        combine(backendState, uiState) { backend, ui -> backend.session to ui }.collect { (session, ui) ->
            val active = session.status in NavigationSession.ACTIVE
            if (active) {
                val key = session.instruction to (session.distanceToManeuver / 25).toInt()
                if (!showing || key != lastNotification) {
                    val distance = session.distanceToManeuver.toInt()
                    val label = if (distance >= 1_000) "%.1f km".format(distance / 1000.0) else "$distance m"
                    notifications.show("In $label", session.instruction)
                    showing = true
                    lastNotification = key
                }
            } else if (showing) {
                notifications.stop()
                showing = false
                lastNotification = null
            }
            // Arrival and its instruction are atomic in the session; the UI announcement can arrive later.
            val arrived = session.status == SessionStatus.Arrived && session.sessionId != null
            speech.update(ui.frontendPreferences.voiceEnabled && (active || arrived) && permission,
                if (arrived) "arrival:${session.sessionId}" else ui.voiceSequence.toString(),
                if (arrived) session.instruction else ui.voiceAnnouncement)
        }
        error("StateFlow collection unexpectedly completed")
    } finally {
        if (showing) notifications.stop()
        speech.update(false, "", null)
    }
}
