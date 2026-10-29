package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.model.tidy
import com.rohanc.navgate.ui.state.Suggestion
import com.rohanc.navgate.ui.theme.NavTokens

/** "Leaving now?": where you usually go at this time, with the way and the time it takes, and a Go button. Hidden when there is no habit to go on. */
@Composable
fun SmartRow(suggestion: Suggestion, onGo: () -> Unit, modifier: Modifier = Modifier) {
    val minutes = kotlin.math.ceil(suggestion.etaSeconds / 60.0).toInt().coerceAtLeast(1)
    val way = if (suggestion.mode == TravelProfile.Driving) "drive" else "walk"
    GlassPanel(modifier.fillMaxWidth().testTag("smartRow"), shape = RoundedCornerShape(24.dp), weight = GlassWeight.Regular, minOpacity = 0.8f, onClick = onGo) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(NavTokens.pulse()), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Flag, null, Modifier.size(24.dp), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("LEAVING NOW?", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.ExtraBold)
                Text("${suggestion.title.tidy()} · $minutes min $way", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("You go here around now", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(NavTokens.pulse()).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Text("Go", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
