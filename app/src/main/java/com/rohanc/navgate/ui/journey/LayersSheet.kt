package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.data.FrontendPreferences

/** "Map details": the map's look (follow the phone, always light, always dark) and 3D buildings. */
@Composable
fun BoxScope.LayersSheet(prefs: FrontendPreferences, onChange: (FrontendPreferences) -> Unit, onDismiss: () -> Unit) {
    NgSheet(Modifier.align(Alignment.BottomCenter)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Map details", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                NgRoundButton(Icons.Rounded.Close, "Close map details", onDismiss)
            }
            Text("STYLE", style = MaterialTheme.typography.labelSmall, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedRow(listOf("system" to "Auto", "light" to "Light", "dark" to "Dark"), prefs.appearance) { onChange(prefs.copy(appearance = it)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("3D buildings", style = MaterialTheme.typography.bodyLarge)
                    Text("Show building heights when the map is tilted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(prefs.buildings3d, { onChange(prefs.copy(buildings3d = it)) }, colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = MaterialTheme.colorScheme.primary))
            }
        }
    }
}
