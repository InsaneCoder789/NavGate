package com.rohanc.navgate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalSolidGlass = staticCompositionLocalOf { false }

/** Material 3 tinted glass. Text stays sharp; no claim of unsupported map-backdrop blur. */
@Composable
fun NavGateGlass(modifier: Modifier=Modifier, guidance:Boolean=false, content:@Composable ColumnScope.()->Unit) {
    val surface=MaterialTheme.colorScheme.surface
    val solid=LocalSolidGlass.current
    Surface(modifier,shape=RoundedCornerShape(28.dp),color=surface.copy(alpha=if(solid)1f else if(guidance).97f else .91f),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.onSurface.copy(alpha=.12f)),shadowElevation=if(guidance)8.dp else 12.dp) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha=if(solid)0f else .035f),Color.Transparent))).padding(20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
