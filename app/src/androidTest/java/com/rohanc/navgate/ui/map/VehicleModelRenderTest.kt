package com.rohanc.navgate.ui.map

import android.app.ActivityManager
import android.graphics.Bitmap
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real GLB / native GPU acceptance. No map provider, location injection, user preferences or database writes. */
@RunWith(AndroidJUnit4::class)
class VehicleModelRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val status = AtomicReference(VehicleRenderStatus.Loading)
    private var model by mutableStateOf(VehicleModel.Sedan)
    private var paint by mutableStateOf(VehicleColor.White.paint)
    private var heading by mutableFloatStateOf(148f)
    private var tilt by mutableFloatStateOf(38f)
    private var shown by mutableStateOf(true)
    private var length by mutableFloatStateOf(164f)

    private fun content() {
        val caps = compose.activity.getSystemService(ActivityManager::class.java).deviceConfigurationInfo
        assumeTrue("Native marker requires OpenGL ES 3", caps.reqGlEsVersion >= 0x30000)
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Color(0xFFE9EEF4)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    Text("NavGate · Native vehicle preview", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.size(240.dp).background(Color(0xFFD9E2EB))) {
                        if (shown) VehicleModelView(model, paint, heading, tilt, length.dp, Modifier.fillMaxSize(), centreYFraction = .62f, onStatus = { status.set(it) })
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("${model.label} · ${heading.toInt()}° · tilt ${tilt.toInt()}°")
                    Text("GLB + Filament · transparent background")
                }
            }
        }
    }

    private fun texture(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) texture(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun bitmap(): Bitmap? {
        var result: Bitmap? = null
        compose.runOnUiThread { result = texture(compose.activity.window.decorView)?.bitmap }
        return result
    }
    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun awaitFrame(): Bitmap {
        var rendered: Bitmap? = null
        compose.waitUntil(20_000) {
            if (status.get() != VehicleRenderStatus.Ready) false else {
                rendered = bitmap()
                rendered?.let { pixels(it).count { p -> (p ushr 24) > 64 } > 1_000 } == true
            }
        }
        return requireNotNull(rendered)
    }
    private fun save(name: String, bitmap: Bitmap) {
        val root = File(compose.activity.getExternalFilesDir(null), "vehicle-render-validation").apply { mkdirs() }
        File(root, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "NAVGATE_VEHICLE_SAVED ${File(root, "$name.png")}\n") })
    }

    @Test fun allSixAssetsRenderWithTransparencyAndFitAtMapHeadings() {
        content()
        for (choice in VehicleModel.entries) {
            compose.runOnUiThread {
                status.set(VehicleRenderStatus.Loading)
                model = choice
                paint = when (choice) {
                    VehicleModel.Arrow -> VehicleColor.Sky
                    VehicleModel.Sedan -> VehicleColor.White
                    VehicleModel.Pickup -> VehicleColor.Yellow
                    VehicleModel.Suv -> VehicleColor.Slate
                    VehicleModel.Hatch -> VehicleColor.Teal
                    VehicleModel.Coupe -> VehicleColor.Red
                }.paint
                heading = 148f; tilt = 38f; length = 164f
            }
            val image = awaitFrame()
            save("${choice.key}-studio", image)
            val p = pixels(image)
            android.util.Log.i("VehicleValidation", "${choice.key} alpha=" + p.asIterable().groupingBy { it ushr 24 }.eachCount())
            val opaque = p.count { (it ushr 24) > 64 }
            assertTrue("${choice.key} has a transparent background", opaque < p.size * .7)
            val border = 4
            for (y in 0 until image.height) for (x in 0 until image.width) {
                if (x < border || y < border || x >= image.width - border || y >= image.height - border) {
                    assertEquals("${choice.key} clipped at $x,$y", 0, p[y * image.width + x] ushr 24)
                }
            }
            save("${choice.key}-studio", image)
            for (angle in listOf(0f, 90f, 180f, 270f)) {
                compose.runOnUiThread { heading = angle; tilt = if (angle == 0f) 0f else 58f; length = 96f }
                compose.waitForIdle()
                Thread.sleep(120) // Allow the TextureView consumer to present the queued native frames.
                awaitFrame()
            }
            save("${choice.key}-map", awaitFrame())
        }
    }

    @Test fun allEightPaintsChangeBodyWithoutChangingTheSilhouette() {
        content()
        val first = awaitFrame()
        save("sedan-white", first)
        val baseline = pixels(first)
        var previous = baseline
        for (color in VehicleColor.entries.drop(1)) {
            compose.runOnUiThread { paint = color.paint }
            var current: Bitmap? = null
            compose.waitUntil(10_000) {
                current = bitmap()
                current?.let { b -> pixels(b).zip(previous.asIterable()).count { (a, c) -> a != c } > 500 } == true
            }
            val now = pixels(requireNotNull(current))
            save("sedan-${color.key}", requireNotNull(current))
            val changedAlpha = now.indices.count { (now[it] ushr 24) != (baseline[it] ushr 24) }
            val changedMask = now.indices.count { ((now[it] ushr 24) > 64) != ((baseline[it] ushr 24) > 64) }
            android.util.Log.i("VehicleValidation", "${color.key}: alphaChanges=$changedAlpha maskChanges=$changedMask of ${now.size}")
            assertTrue("Paint must not move the model: ${color.key}; alpha=$changedAlpha mask=$changedMask", changedAlpha < now.size * .01)
            save("sedan-${color.key}", requireNotNull(current))
            previous = now
        }
    }

    @Test fun rendererSurvivesStopResumeAndFullViewDisposal() {
        content(); awaitFrame()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitFrame()
        repeat(3) {
            compose.runOnUiThread { shown = false }
            compose.waitForIdle()
            compose.runOnUiThread { status.set(VehicleRenderStatus.Loading); shown = true; model = VehicleModel.entries[it] }
            awaitFrame()
        }
    }
}
