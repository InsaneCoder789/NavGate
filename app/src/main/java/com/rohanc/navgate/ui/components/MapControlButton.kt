package com.rohanc.navgate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.journey.LocalGlassOpacity

/** Dense glass over a moving map: do not sample the haze source that contains this control itself. */
@Composable
fun MapControlButton(onClick:()->Unit,enabled:Boolean=true,content:@Composable ()->Unit) {
    val dark=MaterialTheme.colorScheme.surface.luminance()<0.5f
    val alpha=if(LocalSolidGlass.current)1f else maxOf(0.92f,LocalGlassOpacity.current.coerceIn(0f,1f))
    Surface(shape=CircleShape, color=(if(dark)Color(0xFF172338) else Color(0xFFFAFCFF)).copy(alpha=alpha),
        contentColor=MaterialTheme.colorScheme.onSurface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=if(dark)0.16f else 0.08f)),shadowElevation=2.dp) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(52.dp),content=content)
    }
}
