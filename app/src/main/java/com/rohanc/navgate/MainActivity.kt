package com.rohanc.navgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.rohanc.navgate.ui.NavGateApp
import com.rohanc.navgate.ui.theme.NavGateTheme

class MainActivity : ComponentActivity() {
    private val powerSaveChanged = object : android.content.BroadcastReceiver() { override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) = DisplayRate.apply(this@MainActivity) }
    override fun onStart() { super.onStart(); androidx.core.content.ContextCompat.registerReceiver(this, powerSaveChanged, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); DisplayRate.apply(this); if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).addThermalStatusListener(mainExecutor, thermalChanged) } }
    private val thermalChanged = android.os.PowerManager.OnThermalStatusChangedListener { DisplayRate.apply(this) }
    override fun onStop() { super.onStop(); runCatching { unregisterReceiver(powerSaveChanged) }; if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).removeThermalStatusListener(thermalChanged) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        DisplayRate.apply(this)
        setContent {
            NavGateTheme {
                NavGateApp()
            }
        }
    }
}
