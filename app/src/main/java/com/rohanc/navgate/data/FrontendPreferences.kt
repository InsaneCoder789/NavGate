package com.rohanc.navgate.data

data class FrontendPreferences(
    val appearance: String = "system",
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val voiceEnabled: Boolean = true,
)
