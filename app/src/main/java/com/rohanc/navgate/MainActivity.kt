package com.rohanc.navgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.rohanc.navgate.ui.NavGateApp
import com.rohanc.navgate.ui.theme.NavGateTheme

class MainActivity : ComponentActivity() {
    private val powerSaveChanged = object : android.content.BroadcastReceiver() { override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) = DisplayRate.apply(this@MainActivity) }
    override fun onStart() { super.onStart(); androidx.core.content.ContextCompat.registerReceiver(this, powerSaveChanged, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED); DisplayRate.apply(this); if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).addThermalStatusListener(mainExecutor, thermalChanged) } }
    private val thermalChanged = android.os.PowerManager.OnThermalStatusChangedListener { DisplayRate.apply(this) }
    override fun onStop() { super.onStop(); runCatching { unregisterReceiver(powerSaveChanged) }; if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).removeThermalStatusListener(thermalChanged) } }

    /** Leaving the app during a trip shrinks it to a floating map instead of hiding the route (Android 12+ does it on its own once the params say so). */
    private fun pipParams(enabled: Boolean) = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(3, 4))
        .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(enabled) }.build()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT in 26..30 && com.rohanc.navgate.nav.NavigationEvents.pipEligible.value) runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.rohanc.navgate.nav.NavigationEvents.setInPictureInPicture(isInPictureInPictureMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { com.rohanc.navgate.nav.NavigationEvents.pipEligible.collect { runCatching { setPictureInPictureParams(pipParams(it)) } } }
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
