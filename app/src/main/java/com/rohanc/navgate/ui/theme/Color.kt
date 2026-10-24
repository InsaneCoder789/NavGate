package com.rohanc.navgate.ui.theme

import androidx.compose.ui.graphics.Color

// Palette: warm coral and violet on a deep plum night. Nothing blue or green, so the app reads as itself.
val NavBackground = Color(0xFF0F0B14)
val NavSurface = Color(0xFF1A1422)
val NavSurfaceHigh = Color(0xFF2A2233)
val NavPrimary = Color(0xFFFF8A65)           // coral
val NavPrimaryContainer = Color(0xFF5C2A1A)
val NavOnPrimary = Color(0xFF3A1206)

val NavSecondary = Color(0xFFC6A9FF)         // violet
val NavSecondaryContainer = Color(0xFF3B2A66)

val NavTertiary = Color(0xFFFFC857)          // amber
val NavOnSurface = Color(0xFFF4EEF7)
val NavOnSurfaceVariant = Color(0xFFCFC4D8)

// Glassmorphism Specials
val GlassWhite = Color(0x1AFFFFFF)
val GlassBlack = Color(0x4D000000)
val GlassStroke = Color(0x33FFFFFF)
val GlassBlur = 16.0 // Reference for blur radius

// Navigation Specifics
val NavGuidance = Color(0xFF34204A)          // the turn banner: deep violet
val NavHighConfidence = Color(0xFFC6A9FF)
val NavMediumConfidence = Color(0xFFFFC857)
val NavLowConfidence = Color(0xFFFF6B6B)

val NavMapGlow = Color(0xFF1A73E8)
val NavRouteDark = Color(0xFF8AB4F8)         // the map keeps its blue: route line on dark maps
val NavRouteLight = Color(0xFF3B47E8)        // route line on light maps
val NavMapPosition = Color(0xFF63D8F2)       // your position on the map
val NavMapAccent = Color(0xFFD16BFF)         // your own itinerary and the turn you picked: magenta, clear of orange
