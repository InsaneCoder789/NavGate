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
)
