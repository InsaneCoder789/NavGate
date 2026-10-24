package com.rohanc.navgate.ui.theme

import androidx.compose.ui.graphics.Color

// Palette: blue accents on a deep blue-slate night. No purple or magenta anywhere.
val NavBackground = Color(0xFF0B1220)
val NavSurface = Color(0xFF121B2E)
val NavSurfaceHigh = Color(0xFF1D2940)
val NavPrimary = Color(0xFF5B9BFF)           // blue
val NavPrimaryContainer = Color(0xFF1E3A66)
val NavOnPrimary = Color(0xFF05163A)

val NavSecondary = Color(0xFF7FB2FF)         // blue
val NavSecondaryContainer = Color(0xFF1E3A66)

val NavTertiary = Color(0xFFFFC857)          // amber
val NavOnSurface = Color(0xFFEEF3FA)
val NavOnSurfaceVariant = Color(0xFFC3CEDF)

// Glassmorphism Specials
val GlassWhite = Color(0x1AFFFFFF)
val GlassBlack = Color(0x4D000000)
val GlassStroke = Color(0x33FFFFFF)
val GlassBlur = 16.0 // Reference for blur radius

// Navigation Specifics
val NavGuidance = Color(0xFF16305C)          // the turn banner: deep blue
val NavHighConfidence = Color(0xFF7FB2FF)
val NavMediumConfidence = Color(0xFFFFC857)
val NavLowConfidence = Color(0xFFFF6B6B)

val NavMapGlow = Color(0xFF1A73E8)
val NavRouteDark = Color(0xFF8AB4F8)         // the map keeps its blue: route line on dark maps
val NavRouteLight = Color(0xFF3B47E8)        // route line on light maps
val NavMapPosition = Color(0xFF63D8F2)       // your position on the map
val NavMapAccent = Color(0xFF38BDF8)         // your own itinerary and the turn you picked: sky blue, lighter than the route line
