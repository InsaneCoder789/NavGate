package com.rohanc.navgate.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rohanc.navgate.core.*
import com.rohanc.navgate.data.local.*
import com.rohanc.navgate.ui.theme.NavGateTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable DB only: a new editor session must never overwrite the route that was previously edited. */
@RunWith(AndroidJUnit4::class)
class ManualEditorSessionRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newSessionClearsOldIdentityAndSavingPreservesThePreviousRoute() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NavGateDatabase::class.java).build()
        val store = ManualRouteStore(db)
        fun pins(prefix: String) = listOf(RoutePin("${prefix}1", "Start", GeoPoint(19.0, 72.0)), RoutePin("${prefix}2", "Finish", GeoPoint(19.001, 72.001)))
        val a = ManualRoute("existing-route", "Original route", "driving", pins("a"))
        runBlocking { store.save(a) }
        var session by mutableIntStateOf(0)
        var initial by mutableStateOf<ManualRoute?>(a)
        var selectedPins by mutableStateOf(a.pins)
        try {
            compose.setContent {
                NavGateTheme(dark = false) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ManualRoutePanel(store, selectedPins, { selectedPins = it }, null, {}, {}, {}, initialRoute = initial,
                            onInitialRouteShown = { initial = null }, editorSession = session)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNode(hasText("Route name") and hasSetTextAction()).assertTextContains("Original route")
            compose.runOnIdle { session++; selectedPins = pins("b") }
            compose.waitForIdle()
            val field = compose.onNode(hasText("Route name") and hasSetTextAction())
            field.performScrollTo()
            assertEquals("", field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            field.performTextReplacement("New route")
            compose.onNodeWithText("Save manual route").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { store.list().size == 2 } }
            val saved = runBlocking { store.list() }
            assertEquals(a, saved.single { it.id == "existing-route" })
            assertNotEquals("existing-route", saved.single { it.name == "New route" }.id)
            assertEquals("walking", saved.single { it.name == "New route" }.mode)
        } finally { db.close() }
    }
}
