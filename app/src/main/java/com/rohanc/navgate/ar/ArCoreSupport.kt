package com.rohanc.navgate.ar

import android.app.Activity
import com.google.ar.core.ArCoreApk

class ArCoreSupport {
    fun checkAvailability(
        activity: Activity,
        onStateChanged: (ArAvailabilityState) -> Unit,
    ) {
        onStateChanged(ArAvailabilityState.Checking)

        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        val state = when {
            availability.isTransient() -> ArAvailabilityState.Checking
            availability.isSupported() && availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArAvailabilityState.ApkTooOld
            availability.isSupported() && availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailabilityState.NotInstalled
            availability.isSupported() -> ArAvailabilityState.Supported
            else -> ArAvailabilityState.Unsupported
        }
        onStateChanged(state)
    }
}
