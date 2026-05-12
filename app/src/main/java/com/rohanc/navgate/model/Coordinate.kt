package com.rohanc.navgate.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val EarthRadiusMeters = 6_371_000.0

data class Coordinate(
    val latitude: Double,
    val longitude: Double,
) {
    fun distanceTo(other: Coordinate): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val startLat = Math.toRadians(latitude)
        val endLat = Math.toRadians(other.latitude)

        val a =
            sin(dLat / 2).pow(2) +
                cos(startLat) * cos(endLat) * sin(dLon / 2).pow(2)

        return EarthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearingTo(other: Coordinate): Double {
        val startLat = Math.toRadians(latitude)
        val endLat = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)

        val y = sin(dLon) * cos(endLat)
        val x =
            cos(startLat) * sin(endLat) -
                sin(startLat) * cos(endLat) * cos(dLon)

        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

fun Double.asMetersLabel(): String =
    if (this >= 1000) {
        "%.1f km".format(this / 1000.0)
    } else {
        "${roundToInt()} m"
    }

fun Double.asEtaLabel(): String {
    val totalMinutes = (this / 60.0).roundToInt().coerceAtLeast(1)
    return if (totalMinutes < 60) {
        "$totalMinutes min"
    } else {
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
    }
}
