package com.rohanc.navgate.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A compact map dock. Selection uses the same screen contract as the backend-connected shell. */
@Composable
fun NavGateDock(selected:String,onSelect:(String)->Unit,modifier:Modifier=Modifier) {
    val colors=MaterialTheme.colorScheme
    Surface(modifier.widthIn(max=560.dp).fillMaxWidth(),shape=RoundedCornerShape(30.dp),
        color=colors.surface.copy(alpha=if(LocalSolidGlass.current)1f else .98f),
        border=BorderStroke(1.dp,colors.onSurface.copy(alpha=.09f)),shadowElevation=10.dp) {
        Row(Modifier.selectableGroup().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            listOf("Explore" to Icons.Rounded.Explore,"Directions" to Icons.Rounded.DirectionsWalk,
                "Saved" to Icons.Rounded.BookmarkBorder,"Recents" to Icons.Rounded.History).forEach { (screen,icon)->
                val chosen=screen==selected
                Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp))
                    .background(if(chosen)colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected=chosen,role=Role.Tab,onClick={onSelect(screen)})
                    .heightIn(min=62.dp).padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    val tint=if(chosen)colors.onPrimaryContainer else colors.onSurfaceVariant
                    Icon(if(chosen&&screen=="Saved")Icons.Rounded.Bookmark else icon,null,Modifier.size(23.dp),tint=tint)
                    Text(if(screen=="Directions")"Go" else screen,style=MaterialTheme.typography.labelMedium,
                        fontWeight=if(chosen)FontWeight.SemiBold else FontWeight.Medium,color=tint,maxLines=1)
                }
            }
        }
    }
}

@Composable
fun MapControlButton(onClick:()->Unit,enabled:Boolean=true,content:@Composable ()->Unit) {
    NavGlassSurface(shape=androidx.compose.foundation.shape.CircleShape,shadowElevation=3.dp) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(52.dp),content=content)
    }
}

/** Drag the grip or activate it with touch/accessibility to resize the content panel. */
@Composable
fun SheetGrip(expanded:Boolean,onExpandedChange:(Boolean)->Unit) {
    var drag by remember {mutableFloatStateOf(0f)}
    val threshold=with(LocalDensity.current){24.dp.toPx()}
    Box(Modifier.fillMaxWidth().height(28.dp)
        .semantics {contentDescription=if(expanded)"Collapse panel" else "Expand panel"}
        .clickable(role=Role.Button,onClick={onExpandedChange(!expanded)})
        .draggable(rememberDraggableState{drag+=it},Orientation.Vertical,
            onDragStarted={drag=0f},onDragStopped={if(drag < -threshold)onExpandedChange(true)else if(drag > threshold)onExpandedChange(false)}),
        contentAlignment=Alignment.Center) {
        Box(Modifier.width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.22f),RoundedCornerShape(2.dp)))
    }
}
