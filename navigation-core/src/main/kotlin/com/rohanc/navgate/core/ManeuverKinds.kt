package com.rohanc.navgate.core

import kotlin.math.abs

/**
 * Turn kinds, shared by every screen, voice line and AR marker. A provider says what kind of manoeuvre it thinks this is;
 * the route's own geometry has the final say on which way and how sharply it turns, so a wrong-way label can never reach the driver.
 */
object ManeuverKinds {
    const val START = "Start"; const val ARRIVE = "Arrive"; const val STRAIGHT = "Straight"
    const val SLIGHT_LEFT = "SlightLeft"; const val LEFT = "Left"; const val SHARP_LEFT = "SharpLeft"
    const val SLIGHT_RIGHT = "SlightRight"; const val RIGHT = "Right"; const val SHARP_RIGHT = "SharpRight"
    const val UTURN = "UTurn"; const val ROUNDABOUT = "Roundabout"; const val ROUNDABOUT_EXIT = "RoundaboutExit"
    const val MERGE = "Merge"; const val KEEP_LEFT = "KeepLeft"; const val KEEP_RIGHT = "KeepRight"

    /** Signed turn in degrees at [index] of [shape]: positive is a right turn. Measured over ~12 m either side so a single odd vertex does not matter. */
    fun turnAngle(shape: List<GeoPoint>, index: Int, spanMeters: Double = 12.0): Double? {
        if (index <= 0 || index >= shape.lastIndex) return null
        var before = index; while (before > 0 && shape[before].distanceTo(shape[index]) < spanMeters) before--
        var after = index; while (after < shape.lastIndex && shape[after].distanceTo(shape[index]) < spanMeters) after++
        if (before == index || after == index) return null
        val change = shape[index].bearingTo(shape[after]) - shape[before].bearingTo(shape[index])
        return ((change + 540.0) % 360.0) - 180.0
    }

    /** How sharply and which way a turn of [angle] degrees goes. */
    fun fromAngle(angle: Double): String {
        val a = abs(angle); val right = angle >= 0
        return when {
            a < 15 -> STRAIGHT
            a < 45 -> if (right) SLIGHT_RIGHT else SLIGHT_LEFT
            a < 120 -> if (right) RIGHT else LEFT
            a < 165 -> if (right) SHARP_RIGHT else SHARP_LEFT
            else -> UTURN
        }
    }

    private fun side(kind: String): Int = when (kind) {
        SLIGHT_LEFT, LEFT, SHARP_LEFT, KEEP_LEFT -> -1
        SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, KEEP_RIGHT -> 1
        else -> 0
    }

    /**
     * Valhalla manoeuvre type to a kind. [angle] is the measured turn at that point, when known.
     * Ordinary turns (types 9-16) take their sharpness and side from the geometry; keeps, merges and ramps keep the provider's meaning
     * but are flipped if the geometry clearly goes the other way.
     */
    fun forValhalla(type: Int, angle: Double?): String {
        val base = when (type) {
            in 1..3 -> START; in 4..6 -> ARRIVE
            9 -> SLIGHT_RIGHT; 10 -> RIGHT; 11 -> SHARP_RIGHT; 12 -> UTURN; 13 -> UTURN
            14 -> SHARP_LEFT; 15 -> LEFT; 16 -> SLIGHT_LEFT
            18, 20, 23 -> KEEP_RIGHT; 19, 21, 24 -> KEEP_LEFT
            25, 37, 38 -> MERGE
            26 -> ROUNDABOUT; 27 -> ROUNDABOUT_EXIT
            else -> STRAIGHT   // name change, continue, ramp straight, stay straight, ferry
        }
        if (angle == null) return base
        return when (type) {
            // A turn the provider calls a turn is never reported as straight: nearly straight becomes a slight bear in its direction.
            9, 10, 11, 14, 15, 16 -> if (abs(angle) < 15) (if (side(base) > 0) SLIGHT_RIGHT else SLIGHT_LEFT) else fromAngle(angle)
            12, 13 -> UTURN
            18, 20, 23, 19, 21, 24 -> if (abs(angle) > 25 && side(base) != 0 && side(base) * angle < 0) (if (side(base) > 0) KEEP_LEFT else KEEP_RIGHT) else base
            else -> base
        }
    }

    /** "1st", "2nd", "3rd", "4th" for a roundabout exit. */
    fun ordinal(n: Int): String = when {
        n % 100 in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"; n % 10 == 2 -> "${n}nd"; n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

    /** Where the vehicle ends up relative to the road it entered a roundabout on: straight on, left, right or back. */
    fun roundaboutExitDirection(change: Double): String = when (fromAngle(change)) {
        STRAIGHT -> "straight on"
        SLIGHT_LEFT, LEFT -> "left"; SHARP_LEFT -> "sharp left"
        SLIGHT_RIGHT, RIGHT -> "right"; SHARP_RIGHT -> "sharp right"
        else -> "back the way you came"
    }
}
