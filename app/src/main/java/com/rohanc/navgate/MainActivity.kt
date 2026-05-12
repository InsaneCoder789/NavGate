package com.rohanc.navgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.rohanc.navgate.ui.NavGateApp
import com.rohanc.navgate.ui.theme.NavGateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            NavGateTheme {
                NavGateApp()
            }
        }
    }
}
