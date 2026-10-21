package com.rohanc.navgate.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rohanc.navgate.core.BackendSnapshot
import com.rohanc.navgate.core.SessionSnapshot
import com.rohanc.navgate.core.SessionStatus
import com.rohanc.navgate.ui.ar.GuidanceSpeech
import com.rohanc.navgate.ui.ar.VoiceAvailability
import com.rohanc.navgate.ui.state.NavGateUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Device Compose runtime with isolated flows and platform adapters; no real trips, GPS or user-data writes. */
class GuidanceRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Notifications : GuidanceNotifications {
        val instructions = CopyOnWriteArrayList<String>()
        val stops = AtomicInteger()
        override fun show(title: String, instruction: String) { instructions += instruction }
        override fun stop() { stops.incrementAndGet() }
    }

    private class Speech : GuidanceSpeech {
        override val availability = VoiceAvailability.OfflineReady
        val spoken = CopyOnWriteArrayList<String>()
        val enabledStates = CopyOnWriteArrayList<Boolean>()
        override fun update(enabled: Boolean, utteranceKey: String, message: String?) {
            enabledStates += enabled
            if (enabled && message != null) spoken += "$utteranceKey:$message"
        }
    }

    @Test fun stoppedScreenStillTicksAndDeliversTurnAndArrivalEffects() {
        val owner = Owner()
        val notifications = Notifications()
        val ticks = AtomicInteger()
        val speech = Speech()
        val spoken = speech.spoken
        val backend = MutableStateFlow(BackendSnapshot(session = SessionSnapshot(sessionId = "trip-1", status = SessionStatus.Navigating, instruction = "Head north", distanceToManeuver = 200.0)))
        val ui = MutableStateFlow(NavGateUiState(voiceAnnouncement = "Head north", voiceSequence = 1))
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                GuidanceRuntime(backend, ui, true, { ticks.incrementAndGet() }, notifications, speech)
            }
        }
        compose.waitUntil(5_000) { ticks.get() > 0 && notifications.instructions.contains("Head north") }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val before = ticks.get()
        // Wall time, not Compose's animation clock: this exercises the real coroutine ticker on Android.
        compose.waitUntil(4_000) { ticks.get() > before }
        compose.runOnUiThread {
            backend.value = backend.value.copy(session = backend.value.session.copy(instruction = "Turn left", distanceToManeuver = 40.0))
            ui.value = ui.value.copy(voiceAnnouncement = "Turn left", voiceSequence = 2)
        }
        compose.waitUntil(5_000) { notifications.instructions.contains("Turn left") && spoken.contains("2:Turn left") }
        compose.runOnUiThread { backend.value = backend.value.copy(session = backend.value.session.copy(status = SessionStatus.Arrived, instruction = "You have arrived")) }
        compose.waitUntil(5_000) { notifications.stops.get() == 1 && spoken.contains("arrival:trip-1:You have arrived") }
        val beforeDelayedAnnouncement = spoken.size
        compose.runOnUiThread { ui.value = ui.value.copy(voiceSequence = 3, voiceAnnouncement = "You have arrived") }
        // The speech controller receives the same stable arrival key despite the later UI event.
        compose.waitUntil(5_000) { spoken.size > beforeDelayedAnnouncement }
        assertEquals("arrival:trip-1:You have arrived", spoken.last())
        val stoppedAt = ticks.get()
        Thread.sleep(1_200)
        assertEquals("Ticker stops with the trip", stoppedAt, ticks.get())
        assertEquals(Lifecycle.State.CREATED, owner.registry.currentState)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test fun previewDoesNotStartServiceOrTickAndMutedGuidanceDoesNotEnableSpeech() {
        val backend = MutableStateFlow(BackendSnapshot(session = SessionSnapshot(status = SessionStatus.Preview)))
        val ui = MutableStateFlow(NavGateUiState())
        val notifications = Notifications()
        val ticks = AtomicInteger()
        val speech = Speech()
        val enabledStates = speech.enabledStates
        compose.setContent {
            GuidanceRuntime(backend, ui, true, { ticks.incrementAndGet() }, notifications, speech)
        }
        compose.waitForIdle()
        assertTrue(notifications.instructions.isEmpty())
        assertEquals(0, ticks.get())
        compose.runOnUiThread {
            ui.value = ui.value.copy(frontendPreferences = ui.value.frontendPreferences.copy(voiceEnabled = false))
            backend.value = backend.value.copy(session = backend.value.session.copy(status = SessionStatus.Navigating))
        }
        compose.waitUntil(5_000) { notifications.instructions.isNotEmpty() }
        assertTrue(enabledStates.none { it })
    }

    @Test fun realNotificationUpdatesAndServiceStopsWithActivityInBackground() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val backend = MutableStateFlow(BackendSnapshot(session = SessionSnapshot(status = SessionStatus.Navigating,
            instruction = "Synthetic test turn", distanceToManeuver = 200.0)))
        val ui = MutableStateFlow(NavGateUiState())
        val speech = Speech()
        fun text() = manager.activeNotifications.firstOrNull { it.id == 4201 }?.notification?.extras
            ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        compose.setContent { GuidanceRuntime(backend, ui, true, {}, speech = speech) }
        try {
            compose.waitUntil(5_000) { text() == "Synthetic test turn" }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.runOnUiThread { backend.value = backend.value.copy(session = backend.value.session.copy(
                instruction = "Synthetic background turn", distanceToManeuver = 30.0)) }
            compose.waitUntil(5_000) { text() == "Synthetic background turn" }
            compose.runOnUiThread { backend.value = backend.value.copy(session = backend.value.session.copy(status = SessionStatus.Arrived)) }
            compose.waitUntil(5_000) { text() == null }
            @Suppress("DEPRECATION")
            val services = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            compose.waitUntil(5_000) { services.getRunningServices(Int.MAX_VALUE).none {
                it.service.className == "com.rohanc.navgate.nav.NavigationService"
            } }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            com.rohanc.navgate.nav.NavigationService.stop(context)
        }
    }
}
