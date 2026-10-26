package com.rohanc.navgate.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The round frosted button used for the map controls (recenter, layers, lane, fit route). */
@Composable
fun MapControlButton(onClick:()->Unit,enabled:Boolean=true,content:@Composable ()->Unit) {
    NavGlassSurface(shape=androidx.compose.foundation.shape.CircleShape,shadowElevation=3.dp,dense=true) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(52.dp),content=content)
    }
}
