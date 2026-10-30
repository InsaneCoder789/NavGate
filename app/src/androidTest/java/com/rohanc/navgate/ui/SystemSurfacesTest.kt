package com.rohanc.navgate.ui

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.async
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.rohanc.navgate.MainActivity
import com.rohanc.navgate.nav.GuidanceNote
import com.rohanc.navgate.nav.NavigationEvents
import com.rohanc.navgate.nav.NavigationService
import com.rohanc.navgate.ui.journey.PipHud
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The trip away from the app: the notification card, its lock-screen and status-bar faces, the Exit action, and the floating window. */
@RunWith(AndroidJUnit4::class)
class SystemSurfacesTest {
    @get:Rule val compose = createComposeRule()

    @org.junit.Before fun allowNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun posted(timeoutMs: Long = 8_000): Notification? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            manager.activeNotifications.firstOrNull { it.id == 4201 }?.let { return it.notification }
            Thread.sleep(150)
        }
        return null
    }

    private fun gone(timeoutMs: Long = 6_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (manager.activeNotifications.none { it.id == 4201 }) return true; Thread.sleep(150) }
        return false
    }

    @Test fun notificationCardShowsTheTurnTheTripAndAnExitButton() {
        // The service may only start while the app is in front, exactly as in a real trip.
        ActivityScenario.launch(MainActivity::class.java).use {
            NavigationService.show(context, GuidanceNote.of("Turn left onto KIIT Road", 118.0, 600.0, 1_100.0, java.time.LocalTime.of(13, 4), routeMeters = 1_500.0))
            val n = posted()
            assertNotNull("the navigation notification was not posted", n)
            n!!
            assertEquals("In 120 m", n.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Turn left onto KIIT Road", n.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Arrive 1:14 pm · 1.1 km", n.extras.getString(Notification.EXTRA_SUB_TEXT))
            assertEquals("Exit navigation", n.actions.single().title.toString())
            assertEquals(Notification.CATEGORY_NAVIGATION, n.category)
            assertTrue("ongoing, so it cannot be swiped away mid-trip", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("the turn must show on the lock screen", Notification.VISIBILITY_PUBLIC, n.visibility)
            // Android 16 reads these two extras to promote it to the status-bar chip.
            assertTrue(n.extras.getBoolean(NavigationService.EXTRA_REQUEST_PROMOTED))
            assertEquals("120 m", n.extras.getString(NavigationService.EXTRA_SHORT_CRITICAL_TEXT))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals("the live view draws the route as progress", "android.app.Notification\$ProgressStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
                assertEquals(400, n.extras.getInt(Notification.EXTRA_PROGRESS))
                assertTrue("and the system accepted the promotion", n.flags and 0x00040000 != 0 || n.hasPromotableCharacteristics())
            }
            NavigationService.stop(context)
            assertTrue("the notification stays after the trip ended", gone())
        }
    }

    @Test fun updatingTheCardReplacesTheText() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NavigationService.show(context, GuidanceNote("In 300 m", "Continue straight", "300 m", ""))
            assertEquals("In 300 m", posted()!!.extras.getString(Notification.EXTRA_TITLE))
            NavigationService.show(context, GuidanceNote("In 100 m", "Turn right", "100 m", ""))
            val end = System.currentTimeMillis() + 6_000
            var title: String? = null
            while (System.currentTimeMillis() < end && title != "In 100 m") { title = manager.activeNotifications.firstOrNull { it.id == 4201 }?.notification?.extras?.getString(Notification.EXTRA_TITLE); Thread.sleep(150) }
            assertEquals("In 100 m", title)
            assertEquals("one card, not a stack of them", 1, manager.activeNotifications.count { it.id == 4201 })
            NavigationService.stop(context)
        }
    }

    @Test fun exitNavigationInTheCardEndsTheTrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NavigationService.show(context, GuidanceNote("In 100 m", "Turn right", "100 m", ""))
            val n = posted()!!
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            val request = scope.async { withTimeout(8_000) { NavigationEvents.exitRequests.first() } }
            Thread.sleep(300)
            n.actions.single().actionIntent.send()
            runBlocking { request.await() }
            assertTrue("the card goes away with the trip", gone())
        }
    }

    @Test fun floatingWindowKeepsTheTurnAndTheTripReadable() {
        var width by mutableStateOf(240); var height by mutableStateOf(320); var scale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                Box(Modifier.width(width.dp).height(height.dp)) { PipHud(Icons.Rounded.TurnLeft, "120 m", "Turn left onto Sri Venkateswara Swamy Devasthanam Road", "Arrive 1:14 pm · 1.1 km") }
            }
        }
        for ((w, h) in listOf(180 to 240, 240 to 320, 320 to 240)) for (s in listOf(0.85f, 1f, 1.3f, 2f)) {
            width = w; height = h; scale = s; compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val turn = compose.onNodeWithTag("pipTurn").fetchSemanticsNode().boundsInRoot
            val trip = compose.onNodeWithTag("pipTrip").fetchSemanticsNode().boundsInRoot
            val tag = "[$w x $h dp, x$s]"
            val tripText = compose.onNodeWithTag("pipTrip").fetchSemanticsNode()
            if (w >= 240 && s <= 1f) assertTrue("the trip figures must not be cut $tag", tripText.size.width > 0 && compose.onNodeWithText("1:14 pm \u00B7 1.1 km").fetchSemanticsNode().size.width > 0)
            assertTrue("turn card leaves the window $tag $turn in $root", turn.left >= root.left - 1 && turn.right <= root.right + 1 && turn.top >= root.top - 1)
            assertTrue("trip bar leaves the window $tag $trip in $root", trip.left >= root.left - 1 && trip.right <= root.right + 1 && trip.bottom <= root.bottom + 1)
            assertTrue("the turn card and the trip bar overlap $tag", turn.bottom <= trip.top + 1)
        }
    }

    @Test fun theAppShrinksToAFloatingWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var entered = false
            scenario.onActivity { entered = it.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(3, 4)).build()) }
            assertTrue("the activity must support picture-in-picture", entered)
            val end = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < end && !NavigationEvents.inPictureInPicture.value) Thread.sleep(100)
            assertTrue("the shared state must follow the window", NavigationEvents.inPictureInPicture.value)
            scenario.onActivity { assertTrue(it.isInPictureInPictureMode) }
        }
        NavigationEvents.setInPictureInPicture(false)
    }
}
