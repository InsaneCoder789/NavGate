package com.rohanc.navgate.data

data class FrontendPreferences(
    val appearance: String = "system",
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val voiceEnabled: Boolean = true,
    val buildings3d: Boolean = true,
    /** 0 = almost clear glass, 1 = solid panels. */
    val glassOpacity: Float = 0.15f,
    val trafficLayer: Boolean = false,
)
