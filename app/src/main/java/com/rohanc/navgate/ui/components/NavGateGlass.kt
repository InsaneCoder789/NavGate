package com.rohanc.navgate.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

val LocalSolidGlass = staticCompositionLocalOf { false }

/** Material 3 tinted glass. Text stays sharp; no claim of unsupported map-backdrop blur. */
@Composable
fun NavGateGlass(modifier: Modifier=Modifier, guidance:Boolean=false, content:@Composable ColumnScope.()->Unit) {
    NavGlassSurface(modifier,shape=RoundedCornerShape(if(guidance)24.dp else 32.dp),dense=true,shadowElevation=if(guidance)8.dp else 6.dp) {
        Column(Modifier.padding(horizontal=20.dp,vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
