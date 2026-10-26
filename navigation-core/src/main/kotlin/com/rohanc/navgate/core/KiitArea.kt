package com.rohanc.navgate.core

/**
 * "On or near the KIIT campuses": within 4.5 km of the middle of the campuses (Patia, Chandrasekharpur, Infocity and the KIIT roads), which lies inside Bhubaneswar; the city centre is about 6 km away and outside.
 * The KIIT region lists the campus directory in Nearby only when the user is here; anywhere else it behaves like PAN INDIA.
 */
object KiitArea {
    val center = GeoPoint(20.3500, 85.8180)
    const val RADIUS_METERS = 4_500.0

    fun contains(point: GeoPoint?): Boolean = point != null && point.valid && point.distanceTo(center) <= RADIUS_METERS
}
