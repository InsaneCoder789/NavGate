package com.rohanc.navgate.ui.journey

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Navy = Color(0xFF0B1426); private val Blue = Color(0xFF3D8BFF); private val Ice = Color(0xFFA9CCFF)

private class IntroPage(val title: String, val body: String, val before: String? = null, val now: String? = null, val steps: List<String> = emptyList())

private fun pagesFor(mode: String): List<IntroPage> = if (mode == "VR") listOf(
    IntroPage("The road and the map, together", "Live view puts the camera above and your map below, so one glance shows both what is ahead and where you are on the route.",
        before = "You look down at the map, look up, and lose your place at every junction.", now = "The street you need hangs in the picture, right where it lies ahead."),
    IntroPage("How it works", "Hold your phone upright and walk as usual.", steps = listOf("The camera shows the real street.", "A blue tag floats toward the next street, moving as you turn.", "The map curves up from the bottom, with you on the route.")),
    IntroPage("Good to know", "Live view is for walking routes and uses the camera, so it works best in daylight.", steps = listOf("Switch to the map or to AR at any time.", "Keep watching where you walk: the screen is guidance only.", "Spoken directions keep going even when you look away.")),
) else listOf(
    IntroPage("A path drawn on the road", "AR lays your route on the real ground in front of you, so you just follow the blue path.",
        before = "A line on a flat map has to be matched to the street by eye.", now = "The path sits on the road and runs ahead of you to the turn."),
    IntroPage("How it works", "Hold your phone up and sweep it gently.", steps = listOf("The camera finds the ground under your feet.", "A bold blue path is placed on the road and a ring marks where you stand.", "Follow it; the arrow and distance show the next turn.")),
    IntroPage("Good to know", "AR needs light and a surface with some texture, like pavement.", steps = listOf("In the dark or on blank surfaces it asks you to use the map.", "It is guidance only: it does not see obstacles.", "On supported phones, depth can soften the path behind nearby objects. It may be unavailable or inaccurate.")),
)

/** First-time walkthrough of AR or of Live view: what changes, how it works and what to expect, with a picture for each step. */
@Composable
fun ArIntroOverlay(mode: String, onStart: () -> Unit, onClose: () -> Unit, reduceMotion: Boolean = false) {
    val pages = remember(mode) { pagesFor(mode) }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val name = if (mode == "VR") "Live view" else "AR"
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12203A), Navy)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text("NEW", Modifier.clip(CircleShape).background(Blue).padding(horizontal = 10.dp, vertical = 3.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                TextButton(onClick = onStart) { Text("Skip", color = Ice) }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = pages[index]
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IntroPicture(mode, index, Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 320.dp).aspectRatio(1.18f))
                    Spacer(Modifier.height(18.dp))
                    Text(page.title, color = Color.White, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(page.body, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp, lineHeight = 22.sp)
                    if (page.before != null && page.now != null) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Compare("BEFORE", page.before, Color.White.copy(alpha = 0.08f), Ice, Modifier.weight(1f))
                            Compare("NOW", page.now, Blue.copy(alpha = 0.28f), Color.White, Modifier.weight(1f))
                        }
                    }
                    if (page.steps.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        page.steps.forEachIndexed { i, s ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                Text(s, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages.size) { i -> Box(Modifier.height(8.dp).width(if (i == pager.currentPage) 22.dp else 8.dp).clip(CircleShape).background(if (i == pager.currentPage) Blue else Color.White.copy(alpha = 0.25f))) }
                }
                val last = pager.currentPage == pages.lastIndex
                Button(onClick = { if (last) onStart() else scope.launch { if(reduceMotion) pager.scrollToPage(pager.currentPage + 1) else pager.animateScrollToPage(pager.currentPage + 1) } }, Modifier.height(52.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White)) {
                    Text(if (last) "Start $name" else "Next", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun Compare(label: String, text: String, bg: Color, accent: Color, modifier: Modifier) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = accent, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 17.sp)
    }
}

/** A real screenshot if one was dropped in `assets/onboarding/{ar|vr}_{1..3}.png`; otherwise a drawn stand-in of that screen. */
@Composable
private fun IntroPicture(mode: String, index: Int, modifier: Modifier) {
    val context = LocalContext.current
    val file = "onboarding/${mode.lowercase()}_${index + 1}.png"
    val bitmap = remember(file) { runCatching { context.assets.open(file).use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    Box(modifier.clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(24.dp)).background(Color(0xFF0E1A30)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Canvas(Modifier.fillMaxSize().padding(16.dp)) { drawMockPhone(mode, index) }
    }
}

private fun DrawScope.drawMockPhone(mode: String, step: Int) {
    val w = size.height * 0.52f; val h = size.height; val left = (size.width - w) / 2
    val frame = RoundedCornerShape(0.dp)
    drawRoundRect(Color(0xFF1B2740), Offset(left, 0f), Size(w, h), CornerRadius(34f, 34f))
    val sx = left + 8f; val sy = 8f; val sw = w - 16f; val sh = h - 16f
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7FA6D6), Color(0xFFCBD8E6)), sy, sy + sh * 0.5f), Offset(sx, sy), Size(sw, sh), CornerRadius(28f, 28f))
    fun road(top: Float, bottom: Float, topHalf: Float, bottomHalf: Float, color: Color) = Path().apply {
        val cx = sx + sw / 2; moveTo(cx - topHalf, top); lineTo(cx + topHalf, top); lineTo(cx + bottomHalf, bottom); lineTo(cx - bottomHalf, bottom); close()
    }.also { drawPath(it, color) }
    if (mode == "VR") {
        val dome = sy + sh * 0.58f
        road(sy + sh * 0.30f, dome + 40f, sw * 0.04f, sw * 0.42f, Color(0xFF59616E))
        // The map, curving up from the bottom.
        drawOval(Color(0xFF101C33), Offset(sx - sw * 0.5f, dome), Size(sw * 2f, sh))
        drawArc(Color.White, 180f, 180f, false, Offset(sx - sw * 0.5f, dome), Size(sw * 2f, sh), style = Stroke(4f))
        drawLine(Blue, Offset(sx + sw / 2, dome + 20f), Offset(sx + sw / 2, sy + sh - 30f), 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 14f)))
        drawCircle(Blue, 12f, Offset(sx + sw / 2, sy + sh - 60f))
        drawRoundRect(Blue, Offset(sx + sw * 0.28f, sy + sh * 0.18f), Size(sw * 0.46f, sh * 0.09f), CornerRadius(22f, 22f))
        drawRoundRect(Color.White.copy(alpha = 0.9f), Offset(sx + sw * 0.33f, sy + sh * 0.205f), Size(sw * 0.26f, 8f), CornerRadius(4f, 4f))
    } else {
        road(sy + sh * 0.30f, sy + sh, sw * 0.03f, sw * 0.5f, Color(0xFF59616E))
        // The blue path on the road, with chevrons.
        Path().apply { val cx = sx + sw / 2; moveTo(cx - sw * 0.015f, sy + sh * 0.32f); lineTo(cx + sw * 0.015f, sy + sh * 0.32f); lineTo(cx + sw * 0.17f, sy + sh * 0.95f); lineTo(cx - sw * 0.17f, sy + sh * 0.95f); close() }.also { drawPath(it, Blue.copy(alpha = 0.9f)) }
        for (i in 0..3) { val y = sy + sh * (0.45f + i * 0.14f); val half = sw * (0.03f + i * 0.035f); val c = sx + sw / 2
            drawLine(Color.White, Offset(c - half, y + half * 0.5f), Offset(c, y), 5f); drawLine(Color.White, Offset(c + half, y + half * 0.5f), Offset(c, y), 5f) }
        if (step == 1) drawOval(Ice.copy(alpha = 0.7f), Offset(sx + sw * 0.34f, sy + sh * 0.9f), Size(sw * 0.32f, sh * 0.05f), style = Stroke(5f))
        drawRoundRect(Color(0xFF0B1426).copy(alpha = 0.85f), Offset(sx + sw * 0.07f, sy + sh * 0.05f), Size(sw * 0.86f, sh * 0.1f), CornerRadius(26f, 26f))
        drawCircle(Blue, sh * 0.032f, Offset(sx + sw * 0.17f, sy + sh * 0.1f))
        drawRoundRect(Color.White, Offset(sx + sw * 0.30f, sy + sh * 0.085f), Size(sw * 0.3f, 10f), CornerRadius(5f, 5f))
        drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(sx + sw * 0.30f, sy + sh * 0.115f), Size(sw * 0.45f, 8f), CornerRadius(4f, 4f))
    }
}
