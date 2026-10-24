package com.rohanc.navgate.ui.map

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.FrontendPreferences
import com.rohanc.navgate.model.Coordinate
import com.rohanc.navgate.model.TravelProfile
import com.rohanc.navgate.navigation.NavigationSnapshot
import com.rohanc.navgate.ui.NativeMap
import com.rohanc.navgate.ui.journey.VehiclePickerSheet
import com.rohanc.navgate.ui.state.NavGateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual picker and MapLibre composition with a fixed public-location visual fixture; no GPS provider or trip is started. */
@RunWith(AndroidJUnit4::class)
class VehicleMapIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun textures(view: View): List<TextureView> = when (view) {
        is TextureView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textures(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun marker(): Bitmap? {
        var result: Bitmap? = null
        compose.runOnUiThread {
            // The picker lives in a bottom-sheet dialog window, so look through every window of the app, not only the activity's.
            val roots = runCatching {
                val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
                val field = global!!.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") (field.get(global) as List<View>)
            }.getOrNull().orEmpty().ifEmpty { listOf(compose.activity.window.decorView) }
            result = roots.flatMap { textures(it) }.firstOrNull { it.tag == "navgate.vehicle.renderer" }?.bitmap
        }
        return result
    }
    private fun hasGeometry(bitmap: Bitmap?): Boolean {
        if (bitmap == null) return false
        val p = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(p, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return p.count { it ushr 24 > 64 } > 500
    }
    private fun save(name: String) {
        val root = File(compose.activity.getExternalFilesDir(null), "vehicle-render-validation").apply { mkdirs() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // A bottom-sheet dialog window reports no package of its own; any other app in front is still refused.
        val front = automation.rootInActiveWindow?.packageName?.toString()
        assertTrue("Only capture this app's test window", front == null || front == compose.activity.packageName)
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pickerSwipesAndRecoloursTheDownloadedModels() {
        var prefs by mutableStateOf(FrontendPreferences(vehicleModel = "sedan", vehicleColor = "white"))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFE9EEF4))) {
                    VehiclePickerSheet(prefs, { prefs = it }, {})
                }
            }
        }
        compose.waitUntil(15_000) { hasGeometry(marker()) }
        compose.onNodeWithContentDescription("Lagoon teal").performClick()
        compose.runOnIdle { assertEquals("teal", prefs.vehicleColor) }
        compose.onNodeWithContentDescription("Sedan").performTouchInput { swipeLeft(durationMillis = 450) }
        compose.waitUntil(8_000) { prefs.vehicleModel == "pickup" }
        compose.waitUntil(10_000) { hasGeometry(marker()) }
        compose.waitForIdle(); Thread.sleep(180)
        save("picker-native")
    }

    @Test fun vehicleAndWalkingArrowComposeAboveTheRealMap() {
        val here = GeoPoint(18.9430, 72.8236)
        val motion = MutableStateFlow<MotionEstimate?>(MotionEstimate(here, 8.0, 0.0, 0.0, 0.0, 5.0, CalibrationState.Good, false, false, SystemClock.elapsedRealtime()))
        var profile by mutableStateOf(TravelProfile.Driving)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    val ui = NavGateUiState(
                        travelProfile = profile,
                        currentLocationUsable = true,
                        displayLocation = Coordinate(here.latitude, here.longitude),
                        frontendPreferences = FrontendPreferences(vehicleModel = "sedan", vehicleColor = "white", buildings3d = profile == TravelProfile.Driving),
                        snapshot = NavigationSnapshot(isNavigating = true, userLocation = Coordinate(here.latitude, here.longitude)),
                    )
                    NativeMap(ui, dark = false, permission = true, active = true, sessionId = "vehicle-visual-fixture", accuracyMeters = 5.0, inspectPoint = null,
                        onFeedback = {}, onOpenLayers = {}, onPlace = {}, motion = motion, showControls = false)
                    Column(Modifier.padding(top = 44.dp, start = 20.dp).background(Color.White.copy(alpha = .85f)).padding(12.dp)) {
                        Text("NavGate · ${if (profile == TravelProfile.Driving) "Sedan" else "3D arrow"}")
                        Text("Visual fixture · no GPS or trip started")
                    }
                }
            }
        }
        compose.waitUntil(25_000) { compose.mainClock.advanceTimeBy(100); hasGeometry(marker()) }
        // Let both independently rendered surfaces settle on the final follow-camera projection.
        repeat(12) { compose.mainClock.advanceTimeBy(100); Thread.sleep(80) }
        assertTrue(hasGeometry(marker()))
        save("map-sedan-native")
        compose.runOnUiThread { profile = TravelProfile.Walking }
        repeat(12) { compose.mainClock.advanceTimeBy(100); Thread.sleep(80) }
        assertTrue(hasGeometry(marker()))
        save("map-arrow-native")
    }
}
