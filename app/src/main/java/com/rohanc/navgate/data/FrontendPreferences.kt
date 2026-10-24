package com.rohanc.navgate.data

data class FrontendPreferences(
    val appearance: String = "system",
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val voiceEnabled: Boolean = true,
    val buildings3d: Boolean = true,
    /** 0 = almost clear glass, 1 = solid panels. */
    val glassOpacity: Float = 0.15f,
    /** AR smoothness: "auto", "saver" (30 fps), "smooth" (60 fps) or "max" (60 fps camera, overlay at the screen's refresh rate). */
    val arFps: String = "auto",
    /** Hide the AR road line behind people, vehicles and animals the camera sees. */
    val arObjects: Boolean = true,
    /** Car routes: steer around toll roads and motorways where a reasonable alternative exists. */
    /** Opt-in: post the technical crash report automatically on the next launch. Off by default. */
    val sendCrashReports: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
)
