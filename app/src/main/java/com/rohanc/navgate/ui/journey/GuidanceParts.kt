package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohanc.navgate.core.SignalGrade
import com.rohanc.navgate.core.SignalState
import com.rohanc.navgate.ui.theme.NavDisplay
import com.rohanc.navgate.ui.theme.NavTokens

/**
 * The next manoeuvre of 2D and 3D guidance: a blue tile with the turn, the distance as the headline (Sora), the road under it, and a "then" chip for the turn after.
 * Light is the porcelain gradient, dark the clear glass.
 */
@Composable
fun TurnCard(icon: ImageVector, distance: String, instruction: String, thenIcon: ImageVector?, modifier: Modifier = Modifier) {
    GlassPanel(modifier.fillMaxWidth().testTag("turnCard"), shape = RoundedCornerShape(28.dp), weight = GlassWeight.Thick, minOpacity = 0.8f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(19.dp)).background(NavTokens.pulse()), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(36.dp), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(distance, color = MaterialTheme.colorScheme.onSurface, fontFamily = NavDisplay, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, softWrap = true, modifier = Modifier.testTag("turnDistance"))
                Text(instruction, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (thenIcon != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("THEN", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.ExtraBold)
                Box(Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) { Icon(thenIcon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

/** One word for how good the position is: a coloured dot and a label ("GPS strong", "No GPS, 7 s"). */
@Composable
fun SignalChip(signal: SignalState, modifier: Modifier = Modifier) {
    val dark = NavTokens.isDark()
    val amber = if (dark) Color(0xFFFBBF24) else Color(0xFFB26A00)
    val (dot, label) = when (signal.grade) {
        SignalGrade.Strong -> NavTokens.go() to "GPS strong"
        SignalGrade.Fair -> amber to "GPS fair"
        SignalGrade.Weak -> NavTokens.coral() to "GPS weak"
        SignalGrade.Lost -> NavTokens.coral() to (signal.secondsSinceFix?.let { "No GPS, $it s" } ?: "No GPS")
        SignalGrade.Searching -> MaterialTheme.colorScheme.secondary to "Searching for GPS"
    }
    GlassPanel(modifier.testTag("signalChip"), shape = CircleShape, weight = GlassWeight.Thin, minOpacity = 0.8f) {
        Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
        }
    }
}

/** Shown while the phone is warm and the app has stepped its frame rate down, so a slightly less smooth map has a reason. */
@Composable
fun CoolModeChip(modifier: Modifier = Modifier) {
    val cool by com.rohanc.navgate.DisplayRate.coolMode.collectAsState()
    if (!cool) return
    GlassPanel(modifier.testTag("coolChip").semantics { contentDescription = "Cool mode: the phone is warm, so the map is drawn a little less often" }, shape = CircleShape, weight = GlassWeight.Thin, minOpacity = 0.8f) {
        Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Thermostat, null, Modifier.size(16.dp), tint = NavTokens.coral())
            Text("Cool mode", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
        }
    }
}

/** One row of status over the map: an icon or a spinner, a headline and one line of what happens next, with an optional action at the end. */
@Composable
fun StateBanner(title: String, modifier: Modifier = Modifier, detail: String? = null, icon: ImageVector? = null, spinner: Boolean = false, action: (@Composable () -> Unit)? = null) {
    GlassPanel(modifier.fillMaxWidth().testTag("stateBanner"), shape = RoundedCornerShape(20.dp), weight = GlassWeight.Regular, shadow = false, minOpacity = 0.8f) {
        Row(Modifier.padding(start = 14.dp, end = if (action != null) 6.dp else 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (spinner) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                if (detail != null) Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            action?.invoke()
        }
    }
}
