package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.ui.theme.NavDisplay
import com.rohanc.navgate.ui.theme.NavTokens

/**
 * The floating window shown when the app is left during a trip: the map fills it and this adds only the next turn on top and the trip figures below.
 * Nothing here is tappable: the window is too small for touch targets, and a tap brings the full app back.
 */
@Composable
fun BoxScope.PipHud(icon: ImageVector, distance: String, instruction: String, trip: String, onInsets: (topDp: Float, bottomDp: Float) -> Unit = { _, _ -> }) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var bottomPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The map's free area is what lies between the two cards: the arrow is centred there.
    androidx.compose.runtime.LaunchedEffect(topPx, bottomPx) { with(density) { onInsets(topPx.toDp().value, bottomPx.toDp().value) } }
    GlassPanel(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(6.dp).onSizeChanged { topPx = it.height + with(density) { 12.dp.roundToPx() } }.testTag("pipTurn"), shape = RoundedCornerShape(16.dp), weight = GlassWeight.Thick, minOpacity = 0.85f) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(NavTokens.pulse()), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = Color.White) }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(distance, fontFamily = NavDisplay, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(instruction, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (trip.isNotBlank()) GlassPanel(Modifier.align(Alignment.BottomCenter).padding(6.dp).onSizeChanged { bottomPx = it.height + with(density) { 12.dp.roundToPx() } }.testTag("pipTrip"), shape = RoundedCornerShape(14.dp), weight = GlassWeight.Regular, minOpacity = 0.85f) {
        // The window is about 170 dp wide: "1:14 pm · 1.1 km" fits where "Arrive 1:14 pm · 1.1 km" was cut.
        Text(trip.removePrefix("Arrive "), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
    }
}
