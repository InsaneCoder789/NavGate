package com.rohanc.navgate.ui.ar

import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.navigation.NavigationSnapshot
import kotlin.math.abs

enum class AlignmentLevel {
    Aligned,
    Adjust,
    Recover,
}

data class AlignmentStatus(
    val level: AlignmentLevel,
    val label: String,
    val deltaDegrees: Double,
)

fun alignmentStatus(snapshot: NavigationSnapshot, heading: Double?): AlignmentStatus {
    val target = snapshot.route?.steps?.getOrNull(snapshot.currentStepIndex)?.target
    val current = snapshot.userLocation ?: snapshot.origin
    if (heading == null || target == null || current == null) {
        return AlignmentStatus(AlignmentLevel.Recover, "Need heading lock", 0.0)
    }

    val targetBearing = current.bearingTo(target)
    val delta = normalizeDelta(targetBearing - heading)
    val level = when {
        abs(delta) <= 12 -> AlignmentLevel.Aligned
        abs(delta) <= 45 -> AlignmentLevel.Adjust
        else -> AlignmentLevel.Recover
    }
    val label = when (level) {
        AlignmentLevel.Aligned -> "Facing the route"
        AlignmentLevel.Adjust -> if (delta > 0) "Turn slightly right" else "Turn slightly left"
        AlignmentLevel.Recover -> if (delta > 0) "Rotate right to realign" else "Rotate left to realign"
    }
    return AlignmentStatus(level, label, delta)
}

private fun normalizeDelta(value: Double): Double = when {
    value > 180 -> value - 360
    value < -180 -> value + 360
    else -> value
}
