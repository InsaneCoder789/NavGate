package com.rohanc.navgate.ui.journey

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.data.FrontendPreferences
import com.rohanc.navgate.ui.map.VehicleModelView
import com.rohanc.navgate.ui.map.VehicleColor
import com.rohanc.navgate.ui.map.VehicleModel
import kotlin.math.abs

/** One settings row that shows the current vehicle and opens the picker. */
@Composable
fun VehicleRow(prefs: FrontendPreferences, onClick: () -> Unit) {
    val model = VehicleModel.of(prefs.vehicleModel); val color = VehicleColor.of(prefs.vehicleColor)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).heightIn(min = 72.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        VehicleModelView(model, color.paint, 148f, 38f, (84f * model.length / 7f).dp, Modifier.size(84.dp, 60.dp), centreYFraction = .68f)
        Column(Modifier.weight(1f)) {
            Text("Your vehicle", style = MaterialTheme.typography.bodyLarge)
            Text("${model.label} · ${color.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Your ride, your way": swipe through the vehicles, then pick one of eight paints. Every change is saved as it is made. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclePickerSheet(prefs: FrontendPreferences, onChange: (FrontendPreferences) -> Unit, onDismiss: () -> Unit) {
    val models = VehicleModel.entries
    val color = VehicleColor.of(prefs.vehicleColor)
    val pager = rememberPagerState(initialPage = models.indexOf(VehicleModel.of(prefs.vehicleModel)).coerceAtLeast(0)) { models.size }
    val latest by rememberUpdatedState(prefs)
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { page -> if (models[page].key != latest.vehicleModel) onChange(latest.copy(vehicleModel = models[page].key)) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Choose your vehicle", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
            }
            // The middle scrolls, so large text or a small screen never pushes the Done button off the sheet.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val pageWidth = 220.dp
            val padding = ((LocalConfiguration.current.screenWidthDp.dp - pageWidth) / 2).coerceAtLeast(0.dp)
            HorizontalPager(pager, Modifier.fillMaxWidth().height(230.dp), contentPadding = PaddingValues(horizontal = padding), pageSize = PageSize.Fixed(pageWidth), verticalAlignment = Alignment.CenterVertically) { page ->
                val offset = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                val scale = 1f - 0.28f * offset
                val platform = MaterialTheme.colorScheme.primary
                Box(Modifier.size(pageWidth, 200.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1f - 0.5f * offset }.semantics { contentDescription = models[page].label }) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height * 0.68f)
                        drawOval(platform.copy(alpha = 0.18f), Offset(c.x - 98.dp.toPx(), c.y - 22.dp.toPx()), Size(196.dp.toPx(), 52.dp.toPx()))
                        drawOval(platform, Offset(c.x - 98.dp.toPx(), c.y - 22.dp.toPx()), Size(196.dp.toPx(), 52.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                    }
                    VehicleModelView(models[page], color.paint, 148f, 36f, (196f * models[page].length / 6.1f).dp, Modifier.fillMaxSize(), centreYFraction = .68f)
                }
            }
            Text(models[pager.currentPage].label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))
            Text("Colour", style = MaterialTheme.typography.titleMedium)
            Text(color.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().heightIn(max = 190.dp).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), userScrollEnabled = false) {
                items(VehicleColor.entries.toList()) { c ->
                    val chosen = c == color
                    Box(Modifier.aspectRatio(1f).semantics { contentDescription = c.label }.clip(CircleShape).clickable { onChange(latest.copy(vehicleColor = c.key)) }
                        .border(if (chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)), CircleShape).padding(5.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(c.paint), contentAlignment = Alignment.Center) {
                            if (chosen) Icon(Icons.Rounded.Check, null, tint = if (c.paint.luminance() > 0.55f) Color.Black else Color.White)
                        }
                    }
                }
            }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDismiss, Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(54.dp), shape = CircleShape) { Text("Done", fontWeight = FontWeight.SemiBold) }
        }
    }
}
