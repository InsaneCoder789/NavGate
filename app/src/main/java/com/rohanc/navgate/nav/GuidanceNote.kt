package com.rohanc.navgate.nav

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the next turn looks like away from the app: the notification card, its lock-screen face and the short chip in the status bar.
 * Pure, so the wording is tested without a device.
 */
data class GuidanceNote(
    /** "In 120 m", or "Now" at the turn. */
    val title: String,
    /** The instruction: "Turn left onto KIIT Road". */
    val instruction: String,
    /** Two or three characters' worth for the status-bar chip: "120 m". */
    val chip: String,
    /** "Arrive 1:14 pm · 1.1 km", empty when the route has no figures yet. */
    val trip: String,
    /** Metres of the route covered and its whole length: the live notification draws them as a progress bar with a moving arrow. 0 when unknown. */
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
) {
    companion object {
        fun distanceLabel(meters: Double): String {
            val m = meters.coerceAtLeast(0.0)
            return when {
                m >= 10_000 -> "%.0f km".format(Locale.US, m / 1000.0)
                m >= 1_000 -> "%.1f km".format(Locale.US, m / 1000.0)
                m >= 100 -> "${(Math.round(m / 10.0) * 10).toInt()} m"
                else -> "${Math.round(m / 5.0).toInt() * 5} m"
            }
        }

        fun of(instruction: String, distanceToTurn: Double, remainingSeconds: Double, remainingMeters: Double, now: LocalTime = LocalTime.now(), routeMeters: Double = 0.0): GuidanceNote {
            val chip = distanceLabel(distanceToTurn)
            val atTurn = distanceToTurn < 15
            val arrive = remainingSeconds.takeIf { it > 0 }?.let { now.plusSeconds(it.toLong()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US) }
            val trip = listOfNotNull(arrive?.let { "Arrive $it" }, remainingMeters.takeIf { it > 0 }?.let { distanceLabel(it) }).joinToString(" · ")
            val total = routeMeters.toInt().coerceAtLeast(0)
            val done = if (total > 0) (routeMeters - remainingMeters).toInt().coerceIn(0, total) else 0
            return GuidanceNote(if (atTurn) "Now" else "In $chip", instruction, if (atTurn) "Now" else chip, trip, done, total)
        }
    }
}

/** Whether the app should shrink to a floating window when it is left: only while a trip runs and the screen is a map one. */
fun pipEligible(tripActive: Boolean, screen: String): Boolean = tripActive && screen == "Navigation"
