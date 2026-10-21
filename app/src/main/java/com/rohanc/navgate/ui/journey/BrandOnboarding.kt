package com.rohanc.navgate.ui.journey

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.rohanc.navgate.R
import com.rohanc.navgate.ui.components.LocalSolidGlass

private val BrandInk = Color(0xFF08111F)
private val BrandBlue = Color(0xFF397CFF)
private val BrandCyan = Color(0xFF73DEED)
private val BrandWhite = Color(0xFFF2F6FA)

/** Presentation only: permission and completion still use the app's existing actions. */
@Composable
fun BoxScope.Onboarding(hasLocation: Boolean, a: JourneyActions) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }
    val artwork = when (page) {
        0 -> R.drawable.navgate_onboarding_discover
        1 -> R.drawable.navgate_onboarding_routes
        else -> R.drawable.navgate_onboarding_navigate
    }
    val title = when (page) {
        0 -> "Find your place"
        1 -> "Choose your route"
        else -> "Navigate with confidence"
    }
    val description = when (page) {
        0 -> "Search places across India and beyond."
        1 -> "Plan a walk or a drive before you go."
        else -> "Enable location for guidance while the app is open."
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(BrandInk)) {
        val panelMaxHeight = maxHeight * 0.68f
        Image(painterResource(artwork), contentDescription = null, contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to BrandInk.copy(alpha = 0.65f), 0.18f to Color.Transparent,
            0.48f to Color.Transparent, 0.78f to BrandInk.copy(alpha = 0.8f), 1f to BrandInk)))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.navgate_brand_mark), null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(8.dp))
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append("Nav")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = BrandBlue)); append("Gate"); pop()
                    }, color = BrandWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                }
                if (page < 2) TextButton(onClick = a.dismissOnboarding,
                    colors = ButtonDefaults.textButtonColors(contentColor = BrandWhite)) { Text("Skip") }
                else IconButton(onClick = { page-- }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous welcome screen", tint = BrandWhite) }
            }
            Spacer(Modifier.weight(1f))
            Surface(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = BrandInk.copy(alpha = if (LocalSolidGlass.current) 1f else 0.94f), contentColor = BrandWhite,
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandCyan.copy(alpha = 0.22f))) {
                Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(12.dp))
                    Text(description, color = BrandWhite.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = {
                        if (page < 2) page++ else if (hasLocation) a.dismissOnboarding() else a.requestLocation()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2667E8), contentColor = Color.White)) {
                        Text(if (page < 2) "Continue" else if (hasLocation) "Get started" else "Enable location",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (page == 2) TextButton(onClick = a.dismissOnboarding, modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = BrandCyan)) { Text("Explore first") }
                    else Spacer(Modifier.height(16.dp))
                    Row(Modifier.padding(vertical = 12.dp).semantics { stateDescription = "Welcome screen ${page + 1} of 3" },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { index ->
                            Box(Modifier.size(width = if (index == page) 24.dp else 8.dp, height = 8.dp)
                                .background(if (index == page) BrandCyan else BrandWhite.copy(alpha = 0.25f), CircleShape))
                        }
                    }
                }
            }
        }
    }
}
