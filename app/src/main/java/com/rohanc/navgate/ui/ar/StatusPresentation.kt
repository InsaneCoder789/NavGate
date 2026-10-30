package com.rohanc.navgate.ui.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.CameraAlt
import kotlinx.coroutines.delay

/** What the status card says for a tracking or alignment message: a headline, one line of what to do, and the picture that fits (a spinner while the app is simply working). */
internal data class StatusView(val title: String, val hint: String, val icon: ImageVector, val spinner: Boolean)

internal fun statusView(message: String): StatusView = when {
    message.startsWith("Aligning") -> StatusView("Finding the road", "Hold the phone up and look along it", Icons.Rounded.Explore, true)
    message.startsWith("Lining") -> StatusView("Lining up with the road", "Walk a few steps forward", Icons.Rounded.Explore, true)
    message.startsWith("Getting your bearings") -> StatusView("Getting your bearings", "Move the phone slowly", Icons.Rounded.Explore, true)
    message.startsWith("Too dark") -> StatusView("Too dark for AR", "Add light, or use the map", Icons.Rounded.LightMode, false)
    message.startsWith("Moving too fast") -> StatusView("Hold steady", "Move the phone more slowly", Icons.Rounded.CameraAlt, false)
    message.startsWith("Point the camera") -> StatusView("Not enough to see", "Point at the road and its surroundings", Icons.Rounded.CameraAlt, false)
    message.startsWith("The camera is in use") -> StatusView("Camera busy", "Close the other app using it", Icons.Rounded.CameraAlt, false)
    message.startsWith("Use map guidance") -> StatusView("Location is weak", "Follow the map until it recovers", Icons.Rounded.GpsOff, false)
    message.startsWith("Need heading") -> StatusView("Finding your direction", "Move the phone slowly", Icons.Rounded.Explore, true)
    message.startsWith("Near maneuver") -> StatusView("Turn coming up", "Follow the written instruction", Icons.Rounded.TurnRight, false)
    message.startsWith("Turn slightly right") -> StatusView("Turn slightly right", "to face the route", Icons.Rounded.TurnRight, false)
    message.startsWith("Turn slightly left") -> StatusView("Turn slightly left", "to face the route", Icons.Rounded.TurnLeft, false)
    message.startsWith("Rotate right") -> StatusView("Rotate right", "to face the route", Icons.Rounded.RotateRight, false)
    message.startsWith("Rotate left") -> StatusView("Rotate left", "to face the route", Icons.Rounded.RotateLeft, false)
    else -> StatusView(message, "", Icons.Rounded.Info, false)
}

/**
 * Keeps a status from flickering: a message must stand for [showAfterMs] before it appears, then stays at least [holdMs], and a gap shorter than [clearAfterMs] does not
 * remove it. A walker's heading wobbling around a threshold would otherwise make the card blink on and off, which reads as the app failing.
 */
internal class StableStatus(private val showAfterMs: Long = 800, private val holdMs: Long = 1500, private val clearAfterMs: Long = 800, private val switchAfterMs: Long = 300) {
    private var candidate: String? = null; private var candidateSince = 0L
    private var shown: String? = null; private var shownSince = 0L

    fun update(nowMs: Long, raw: String?): String? {
        if (raw != candidate) { candidate = raw; candidateSince = nowMs }
        val stable = nowMs - candidateSince
        val current = shown
        when {
            candidate == current -> {}
            current == null -> if (candidate != null && stable >= showAfterMs) { shown = candidate; shownSince = nowMs }
            nowMs - shownSince < holdMs -> {}
            candidate == null -> if (stable >= clearAfterMs) shown = null
            else -> if (stable >= switchAfterMs) { shown = candidate; shownSince = nowMs }
        }
        return shown
    }
}

/** [raw] as the card should show it: steady, not blinking. */
@Composable
internal fun rememberStableStatus(raw: String?): String? {
    val stable = remember { StableStatus() }
    var shown by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(raw) {
        // Re-evaluates while the message is changing; the loop ends once what is shown matches what is asked for.
        while (true) {
            shown = stable.update(android.os.SystemClock.elapsedRealtime(), raw)
            if (shown == raw) break
            delay(150)
        }
    }
    return shown
}
