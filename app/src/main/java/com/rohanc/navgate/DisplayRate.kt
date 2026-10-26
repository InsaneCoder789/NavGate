package com.rohanc.navgate

import android.app.Activity
import android.content.Context
import android.os.PowerManager

/** Asks for the display's top refresh rate for the whole app (120 Hz where the phone has it), and 60 Hz in battery saver or when the phone is getting hot. */
object DisplayRate {
    fun apply(activity: Activity) {
        val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val display = (if (android.os.Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay) ?: return
        val current = display.mode
        // Only modes at the screen's current resolution: switching resolution would flash the screen.
        val sameSize = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val warm = android.os.Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val wanted = if (power.isPowerSaveMode || warm) sameSize.filter { it.refreshRate <= 61f }.maxByOrNull { it.refreshRate } else sameSize.maxByOrNull { it.refreshRate }
        val attributes = activity.window.attributes
        if (wanted != null && attributes.preferredDisplayModeId != wanted.modeId) {
            attributes.preferredDisplayModeId = wanted.modeId
            attributes.preferredRefreshRate = wanted.refreshRate
            activity.window.attributes = attributes
        }
    }
}
