package com.rohanc.navgate.ui.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the camera guidance screen, so every card shares one spacing scale, one corner radius family and one glass recipe.
 * The surfaces are deliberately light: the camera view is the content, the cards only carry numbers and one instruction.
 */
internal object ArTokens {
    val space1: Dp = 4.dp; val space2: Dp = 8.dp; val space3: Dp = 12.dp; val space4: Dp = 16.dp; val space5: Dp = 24.dp
    val radiusCard: Dp = 28.dp; val radiusChip: Dp = 18.dp
    val glassTop = ArNavy.copy(alpha = 0.70f); val glassBottom = ArNavy.copy(alpha = 0.52f)
    val onGlass = Color.White; val onGlassMuted = Color.White.copy(alpha = 0.72f); val hairline = Color.White.copy(alpha = 0.16f)
}

/** The shared frosted surface: soft shadow, a gentle top-to-bottom tint and a hairline edge that catches the light at the top. */
@Composable
internal fun ArCard(modifier: Modifier = Modifier, radius: Dp = ArTokens.radiusCard, body: Float = 0f, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f)).clip(shape)
        .background(Brush.verticalGradient(listOf(ArTokens.glassTop, ArTokens.glassBottom)))
        .then(if (body > 0f) Modifier.background(ArNavy.copy(alpha = body)) else Modifier)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f))), shape), content = content)
}

/** The next manoeuvre: a coral badge, the distance as the headline, the road as the sub-line and the turn after it as a small chip. */
@Composable
internal fun ArTurnCard(kind: String?, distance: String, instruction: String, thenKind: String?, modifier: Modifier = Modifier) {
    ArCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(ArTokens.space3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ArTokens.space3)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF6FAEFF), ArBlue))), contentAlignment = Alignment.Center) {
                Icon(turnIcon(kind), null, Modifier.size(34.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(distance, color = ArTokens.onGlass, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                Text(instruction, color = ArTokens.onGlassMuted, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (thenKind != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("THEN", color = ArTokens.onGlassMuted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(turnIcon(thenKind), null, Modifier.size(22.dp), tint = ArTokens.onGlass)
                }
            }
        }
    }
}

/** Three figures that matter on a walk: when you arrive, how long is left and how far. */
@Composable
internal fun ArTripCard(eta: String, remaining: String, distance: String, modifier: Modifier = Modifier) {
    ArCard(modifier.fillMaxWidth(), radius = 24.dp) {
        Row(Modifier.padding(vertical = ArTokens.space3, horizontal = ArTokens.space4), verticalAlignment = Alignment.CenterVertically) {
            Metric("ARRIVE", eta, Modifier.weight(1f)); Divider(); Metric("LEFT", remaining, Modifier.weight(1f)); Divider(); Metric("DISTANCE", distance, Modifier.weight(1f))
        }
    }
}

@Composable private fun Divider() = Box(Modifier.width(1.dp).height(28.dp).background(ArTokens.hairline))

@Composable private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = ArTokens.onGlassMuted, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = ArTokens.onGlass, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A round glass control; [primary] is the single coral action on the screen. */
@Composable
internal fun ArControl(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, primary: Boolean = false) {
    val size = if (primary) 68.dp else 52.dp
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(size).then(
        if (primary) Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(ArBlue)
        else Modifier.shadow(10.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(ArTokens.glassTop, ArTokens.glassBottom))).border(1.dp, ArTokens.hairline, CircleShape))) {
        Icon(icon, description, Modifier.size(if (primary) 30.dp else 24.dp), tint = Color.White)
    }
}
