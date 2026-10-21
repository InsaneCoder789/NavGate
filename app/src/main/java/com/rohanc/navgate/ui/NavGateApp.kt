package com.rohanc.navgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

@Composable
fun NavGateApp() {
    // The app initializes beneath the brand layer; reopening from Recents or rotating does not replay it.
    var showingLaunch by rememberSaveable { mutableStateOf(true) }
    Box(Modifier.fillMaxSize()) {
        NavGateFrontend()
        if (showingLaunch) BrandLaunch { showingLaunch = false }
    }
}
