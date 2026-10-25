package com.rohanc.navgate.ui.map

/**
 * How often the map and the vehicle marker are redrawn while guiding. Every redraw moves the camera, relays out the marker and asks the
 * GPU for a frame, which is where a navigation app spends its battery, so the rate follows what the user can actually see: full rate
 * only while driving on a charged phone, less on foot or in the AR/VR views (which already run the camera), a trickle when standing still,
 * and the least in battery saver or when the phone is warm.
 */
object RenderBudget {
    const val DRIVING_MS = 16L        // 60 Hz
    const val WALKING_MS = 33L        // 30 Hz
    const val CAMERA_VIEW_MS = 33L    // 30 Hz: ARCore and the camera are already busy
    const val WARM_MS = 50L           // 20 Hz
    const val POWER_SAVE_MS = 66L     // 15 Hz
    const val STANDING_MS = 200L      // 5 Hz

    fun intervalMillis(powerSave: Boolean, warm: Boolean, speedMps: Double, driving: Boolean, cameraView: Boolean): Long {
        val base = when {
            cameraView -> CAMERA_VIEW_MS
            driving -> DRIVING_MS
            else -> WALKING_MS
        }
        val limited = when {
            powerSave -> maxOf(base, POWER_SAVE_MS)
            warm -> maxOf(base, WARM_MS)
            else -> base
        }
        return if (speedMps < 0.4) maxOf(limited, STANDING_MS) else limited
    }
}
