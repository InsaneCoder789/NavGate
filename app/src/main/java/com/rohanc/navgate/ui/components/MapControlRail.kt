package com.rohanc.navgate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

internal data class MapControlAction(val label: String, val icon: ImageVector? = null, val text: String? = null,
    val enabled: Boolean = true, val rotation: Float = 0f, val onClick: () -> Unit)

/** Show complete touch targets only. Overflow stays reachable instead of clipping a circular button. */
@Composable
internal fun MapControlRail(actions: List<MapControlAction>, availableHeight: Float, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val capacity = ((availableHeight + 8f) / 60f).toInt().coerceAtLeast(1)
    val overflow = actions.size > capacity
    val visible = if (overflow) actions.take((capacity - 1).coerceAtLeast(0)) else actions
    val remaining = actions.drop(visible.size)
    Column(modifier.testTag("mapControlRail"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { action -> MapControlButton(action.onClick, action.enabled) {
            if (action.icon != null) Icon(action.icon, action.label, Modifier.graphicsLayer { rotationZ = action.rotation })
            else Text(action.text.orEmpty(), modifier = Modifier.semantics { contentDescription = action.label }, style = MaterialTheme.typography.titleMedium)
        } }
        if (overflow) Box {
            MapControlButton({ expanded = true }) { Icon(Icons.Rounded.MoreVert, "More map controls") }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                remaining.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled,
                    onClick = { expanded = false; action.onClick() },
                    leadingIcon = { if(action.icon != null) Icon(action.icon, null) else Text(action.text.orEmpty()) }) }
            }
        }
    }
}
