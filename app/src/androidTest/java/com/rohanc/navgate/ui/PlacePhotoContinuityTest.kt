package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.journey.PlacePhoto
import com.rohanc.navgate.ui.theme.NavGateTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlacePhotoContinuityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun photoFailureRetryAndSuccessNeverMoveDirections() {
        val first = CompletableDeferred<ImageBitmap?>()
        var calls = 0
        compose.setContent { NavGateTheme(false) {
            Column(Modifier.width(320.dp)) {
                PlacePhoto("https://test.invalid/photo", loader = { if (++calls == 1) first.await() else ImageBitmap(16,16) })
                Button({}, Modifier.testTag("directionsBelowPhoto")) { Text("Directions") }
            }
        } }
        compose.onNodeWithText("Loading photo…").assertIsDisplayed()
        val before=compose.onNodeWithTag("directionsBelowPhoto").fetchSemanticsNode().boundsInRoot
        first.complete(null)
        compose.waitForIdle()
        compose.onNodeWithText("Retry photo").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Photo of the place").assertIsDisplayed()
        val after=compose.onNodeWithTag("directionsBelowPhoto").fetchSemanticsNode().boundsInRoot
        assertEquals(before,after)
        compose.runOnIdle { assertEquals(2,calls) }
    }
}
