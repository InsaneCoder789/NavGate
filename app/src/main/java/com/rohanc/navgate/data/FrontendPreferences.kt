package com.rohanc.navgate.data

data class FrontendPreferences(
    val appearance: String = "dark",
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val voiceEnabled: Boolean = true,
)
