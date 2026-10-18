package com.rohanc.navgate.core

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun distanceTo(other: GeoPoint): Double {
        val lat = Math.toRadians(other.latitude - latitude)
        val lon = Math.toRadians(other.longitude - longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val lon = Math.toRadians(other.longitude - longitude)
        return (Math.toDegrees(atan2(sin(lon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon))) + 360) % 360
    }
}

/** Local tangent-plane projection, for bounded campus graphs, not planet-scale routing. */
data class SegmentProjection(val point: GeoPoint, val fraction: Double, val distanceMeters: Double)

fun project(point: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
    val sx = 111_195.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    val sy = 111_195.0
    val dx = (b.longitude - a.longitude) * sx
    val dy = (b.latitude - a.latitude) * sy
    val px = (point.longitude - a.longitude) * sx
    val py = (point.latitude - a.latitude) * sy
    val squaredLength = dx * dx + dy * dy
    val fraction = if (squaredLength < 1e-12) 0.0 else ((px * dx + py * dy) / squaredLength).coerceIn(0.0, 1.0)
    val snapped = GeoPoint(a.latitude + fraction * (b.latitude - a.latitude), a.longitude + fraction * (b.longitude - a.longitude))
    return SegmentProjection(snapped, fraction, point.distanceTo(snapped))
}

@Serializable
data class Coverage(val boundary: List<GeoPoint>) {
    fun contains(point: GeoPoint): Boolean {
        if (!point.valid || boundary.size < 3) return false
        var inside = false
        for (i in boundary.indices) {
            val a = boundary[i]
            val b = boundary[(i + 1) % boundary.size]
            if (project(point, a, b).distanceMeters < 0.05) return true
            if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
                point.longitude < (b.longitude - a.longitude) * (point.latitude - a.latitude) / (b.latitude - a.latitude) + a.longitude
            ) inside = !inside
        }
        return inside
    }
}

fun List<GeoPoint>.lengthMeters(): Double = zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

data class PathProjection(val point: GeoPoint, val distanceMeters: Double, val alongMeters: Double, val segment: Int)

fun projectPath(point: GeoPoint, path: List<GeoPoint>, minAlong: Double = 0.0, maxAlong: Double = Double.POSITIVE_INFINITY): PathProjection? {
    var along = 0.0
    var best: PathProjection? = null
    for (i in 0 until path.lastIndex) {
        val a = path[i]
        val b = path[i + 1]
        val length = a.distanceTo(b)
        val projection = project(point, a, b)
        val progress = along + length * projection.fraction
        if (progress in minAlong..maxAlong && (best == null || projection.distanceMeters < best.distanceMeters)) {
            best = PathProjection(projection.point, projection.distanceMeters, progress, i)
        }
        along += length
    }
    return best
}
