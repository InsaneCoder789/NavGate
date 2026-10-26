package com.rohanc.navgate.ui.map

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.rohanc.navgate.data.search.PinKind

/**
 * The look of each kind of place on the map, in the manner of the base map's own hospital marker: a small light disc with a thin coloured rim and the
 * kind's glyph in colour. Blues and teals for everyday places; red, orange and navy are kept for the emergency services.
 */
object MapIcons {
    fun icon(kind: PinKind): ImageVector = when (kind) {
        PinKind.Hospital -> Icons.Rounded.LocalHospital
        PinKind.Clinic -> Icons.Rounded.MedicalServices
        PinKind.Pharmacy -> Icons.Rounded.LocalPharmacy
        PinKind.Police -> Icons.Rounded.LocalPolice
        PinKind.Fire -> Icons.Rounded.LocalFireDepartment
        PinKind.Restaurant -> Icons.Rounded.Restaurant
        PinKind.Cafe -> Icons.Rounded.LocalCafe
        PinKind.Bakery -> Icons.Rounded.BakeryDining
        PinKind.Sweets -> Icons.Rounded.Icecream
        PinKind.Bar -> Icons.Rounded.LocalBar
        PinKind.Bank -> Icons.Rounded.AccountBalance
        PinKind.Atm -> Icons.Rounded.LocalAtm
        PinKind.Shop -> Icons.Rounded.ShoppingBag
        PinKind.Grocery -> Icons.Rounded.LocalGroceryStore
        PinKind.Mall -> Icons.Rounded.LocalMall
        PinKind.Electronics -> Icons.Rounded.Devices
        PinKind.Jewelry -> Icons.Rounded.Diamond
        PinKind.Fuel -> Icons.Rounded.LocalGasStation
        PinKind.Charging -> Icons.Rounded.EvStation
        PinKind.Parking -> Icons.Rounded.LocalParking
        PinKind.Transit -> Icons.Rounded.DirectionsTransit
        PinKind.Airport -> Icons.Rounded.Flight
        PinKind.AutoService -> Icons.Rounded.CarRepair
        PinKind.Travel -> Icons.Rounded.Luggage
        PinKind.Toilets -> Icons.Rounded.Wc
        PinKind.Hotel -> Icons.Rounded.Hotel
        PinKind.School -> Icons.Rounded.School
        PinKind.College -> Icons.Rounded.AccountBalance
        PinKind.Coaching -> Icons.Rounded.MenuBook
        PinKind.Library -> Icons.Rounded.LocalLibrary
        PinKind.Temple -> Icons.Rounded.TempleHindu
        PinKind.Mosque -> Icons.Rounded.Mosque
        PinKind.Church -> Icons.Rounded.Church
        PinKind.Worship -> Icons.Rounded.TempleBuddhist
        PinKind.Park -> Icons.Rounded.Park
        PinKind.Museum -> Icons.Rounded.Museum
        PinKind.Landmark -> Icons.Rounded.Attractions
        PinKind.Fitness -> Icons.Rounded.FitnessCenter
        PinKind.Sports -> Icons.Rounded.SportsSoccer
        PinKind.Entertainment -> Icons.Rounded.Theaters
        PinKind.Salon -> Icons.Rounded.ContentCut
        PinKind.Services -> Icons.Rounded.HomeRepairService
        PinKind.Government -> Icons.Rounded.Gavel
        PinKind.Office -> Icons.Rounded.Business
        PinKind.RealEstate -> Icons.Rounded.HomeWork
        PinKind.Other -> Icons.Rounded.Place
    }

    fun color(kind: PinKind): Color = when (kind) {
        PinKind.Hospital, PinKind.Pharmacy -> Color(0xFFD93025)
        PinKind.Clinic -> Color(0xFF2563EB)
        PinKind.Police -> Color(0xFF1E3A8A)
        PinKind.Fire -> Color(0xFFEA580C)
        PinKind.Restaurant, PinKind.Cafe, PinKind.Bakery, PinKind.Sweets, PinKind.Bar -> Color(0xFFE08A00)
        PinKind.Bank, PinKind.Atm, PinKind.Government -> Color(0xFF0369A1)
        PinKind.Shop, PinKind.Grocery, PinKind.Mall, PinKind.Electronics, PinKind.Jewelry -> Color(0xFF2563EB)
        PinKind.Fuel, PinKind.Charging, PinKind.Parking, PinKind.Transit, PinKind.Airport, PinKind.AutoService, PinKind.Travel, PinKind.Toilets -> Color(0xFF0E7490)
        PinKind.Hotel -> Color(0xFF3B82F6)
        PinKind.School, PinKind.College, PinKind.Coaching, PinKind.Library -> Color(0xFF1D4ED8)
        PinKind.Temple, PinKind.Mosque, PinKind.Church, PinKind.Worship -> Color(0xFFB45309)
        PinKind.Park -> Color(0xFF0F766E)
        PinKind.Museum, PinKind.Landmark, PinKind.Entertainment -> Color(0xFF0891B2)
        PinKind.Fitness, PinKind.Sports -> Color(0xFF0284C7)
        PinKind.Salon, PinKind.Services, PinKind.Office, PinKind.RealEstate -> Color(0xFF475569)
        PinKind.Other -> Color(0xFF64748B)
    }
}

/** A small light disc, a thin rim in the kind's colour and the glyph in that colour (a dark disc on dark maps). */
private class BadgePainter(private val icon: Painter, private val color: Color, private val disc: Color, private val px: Float) : Painter() {
    override val intrinsicSize = Size(px, px)
    override fun DrawScope.onDraw() {
        val r = size.minDimension / 2f
        drawCircle(disc, r)
        drawCircle(color.copy(alpha = 0.85f), r - size.minDimension * 0.035f, style = Stroke(size.minDimension * 0.07f))
        val s = size.minDimension * 0.56f
        translate((size.width - s) / 2f, (size.height - s) / 2f) { with(icon) { draw(Size(s, s), colorFilter = ColorFilter.tint(color)) } }
    }
}

@Composable
fun rememberBadgePainter(kind: PinKind, dark: Boolean): Painter {
    val icon = rememberVectorPainter(MapIcons.icon(kind))
    return remember(kind, icon, dark) { BadgePainter(icon, if (dark) lift(MapIcons.color(kind)) else MapIcons.color(kind), if (dark) Color(0xFF16213A) else Color.White, 96f) }
}

/** On a dark map the deep colours are lifted a little so the glyph stays easy to read. */
private fun lift(c: Color) = Color(minOf(1f, c.red + (1f - c.red) * 0.35f), minOf(1f, c.green + (1f - c.green) * 0.35f), minOf(1f, c.blue + (1f - c.blue) * 0.35f), c.alpha)

private class TintedPainter(private val icon: Painter, private val color: Color) : Painter() {
    override val intrinsicSize = Size(96f, 96f)
    override fun DrawScope.onDraw() { with(icon) { draw(size, colorFilter = ColorFilter.tint(color)) } }
}

/** The destination marker: a red drop pin. */
@Composable
fun rememberDropPinPainter(): Painter {
    val icon = rememberVectorPainter(Icons.Filled.Place)
    return remember(icon) { TintedPainter(icon, Color(0xFFD93025)) }
}
