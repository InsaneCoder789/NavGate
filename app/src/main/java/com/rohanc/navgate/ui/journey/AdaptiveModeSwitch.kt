package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/** A short map viewport gets a menu, never a sliced-off AR/VR touch target. */
@Composable
internal fun AdaptiveModeSwitch(selected: String, onSelect: (String) -> Unit, arAvailable: Boolean, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.testTag("navigationModeSwitch")) {
        val modes = if(arAvailable) listOf("2D", "3D", "AR", "VR") else listOf("2D", "3D")
        if(maxHeight >= (modes.size * 58 + 8).dp) ViewModeSwitch(selected, onSelect, vertical = true, arAvailable = arAvailable)
        else {
            NgTonalButton(selected, null, { expanded = true }, Modifier.heightIn(min = 52.dp).widthIn(min = 64.dp).semantics { contentDescription = "Change navigation view: $selected" })
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                modes.forEach { mode -> DropdownMenuItem(text = { Text("$mode navigation") }, onClick = { expanded=false;onSelect(mode) }) }
            }
        }
    }
}
