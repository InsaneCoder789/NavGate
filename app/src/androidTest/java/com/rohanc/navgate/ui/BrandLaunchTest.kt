package com.rohanc.navgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BrandLaunchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun launchCompletesOnceAndDoesNotLoop() {
        compose.mainClock.autoAdvance = false
        val finished = AtomicInteger()
        compose.setContent { BrandLaunchAnimation(false) { finished.incrementAndGet() } }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(0, finished.get()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, finished.get()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(1, finished.get()) }
    }

    @Test fun reducedMotionSkipsTheDecorativeDelay() {
        val finished = AtomicInteger()
        compose.setContent { BrandLaunchAnimation(true) { finished.incrementAndGet() } }
        compose.runOnIdle { assertEquals(1, finished.get()) }
    }

    @Test fun inspectMistFramesAndAdaptiveIcon() {
        var frame by mutableFloatStateOf(0.15f)
        var icon by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF08111F)), contentAlignment = Alignment.Center) {
                if (icon) AndroidView(factory = { context ->
                    android.widget.ImageView(context).apply {
                        setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                }, modifier = Modifier.size(160.dp))
                else BrandLaunchFrame(frame)
            }
        }
        listOf("forming" to 0.20f, "formed" to 0.48f, "dissolving" to 0.80f).forEach { (name, value) ->
            compose.runOnIdle { frame = value }
            screenshot(name)
        }
        compose.runOnIdle { icon = true }
        screenshot("adaptive-icon")
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("brandingScreenshots") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "splash-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
