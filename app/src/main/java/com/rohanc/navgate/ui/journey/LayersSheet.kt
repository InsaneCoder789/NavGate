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
    GmSheet(Modifier.align(Alignment.BottomCenter)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Map details", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                GmRoundButton(Icons.Rounded.Close, "Close map details", onDismiss)
            }
            Text("Map style", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.selectableGroup().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    FilterChip(prefs.appearance == key, { onChange(prefs.copy(appearance = key)) }, { Text(label) }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("3D buildings", style = MaterialTheme.typography.bodyLarge)
                    Text("Show building heights when the map is tilted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(prefs.buildings3d, { onChange(prefs.copy(buildings3d = it)) })
            }
        }
    }
}
