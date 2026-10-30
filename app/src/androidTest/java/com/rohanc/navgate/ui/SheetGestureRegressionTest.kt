package com.rohanc.navgate.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rohanc.navgate.ui.journey.NgSheet
import com.rohanc.navgate.ui.theme.NavGateTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SheetGestureRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun handleSupportsDirectionalDragAndAccessibleClick() {
        var expanded = false
        compose.setContent {
            NavGateTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    NgSheet(Modifier.align(Alignment.BottomCenter).height(300.dp), onGrabber = { expanded = !expanded }, onGrabberDrag = { expanded = it }) { Text("Route steps") }
                }
            }
        }
        val handle = compose.onNodeWithTag("sheetGrabber")
        handle.performTouchInput { swipe(center, center.copy(y = center.y - 200f), 300) }
        compose.runOnIdle { assertTrue(expanded) }
        handle.performTouchInput { swipe(center, center.copy(y = center.y + 200f), 300) }
        compose.runOnIdle { assertFalse(expanded) }
        handle.performClick()
        compose.runOnIdle { assertTrue(expanded) }
    }
}
