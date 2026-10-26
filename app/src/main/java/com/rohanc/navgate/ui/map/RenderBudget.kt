package com.rohanc.navgate.ui.map

/**
 * How often the map and the vehicle marker are redrawn while guiding. Every redraw moves the camera, relays out the marker and asks the
 * GPU for a frame, which is where a navigation app spends its battery, so the rate follows what the user can actually see: full rate
 * only while driving on a charged phone, less on foot or in the AR/VR views (which already run the camera), a trickle when standing still,
 * and the least in battery saver or when the phone is warm.
 */
object RenderBudget {
    // 0 = every display frame (the display runs at its top rate, 120 Hz on phones that have it; 60 Hz in battery saver, see DisplayRate).
    const val DRIVING_MS = 0L
    const val WALKING_MS = 0L
    const val CAMERA_VIEW_MS = 0L
    const val WARM_MS = 16L           // 60 Hz when the phone is warm (thermal status moderate)
    const val HOT_MS = 22L            // 45 Hz when it is hot (severe): the phone is about to throttle itself, so ease off first
    const val CRITICAL_MS = 33L       // 30 Hz when it is critical: cooling matters more than smoothness for a while
    const val POWER_SAVE_MS = 16L     // 60 Hz in battery saver: smooth, never the old 15 Hz slideshow
    const val STANDING_MS = 33L       // 30 Hz while standing, so the zoom-in when guidance starts is not a 5 Hz slideshow

    /** [thermalStatus] is Android's: 0 none, 1 light, 2 moderate, 3 severe, 4 critical or worse. */
    fun intervalMillis(powerSave: Boolean, thermalStatus: Int, speedMps: Double, driving: Boolean, cameraView: Boolean): Long {
        val warm = thermalStatus >= 2
        val base = when {
            cameraView -> CAMERA_VIEW_MS
            driving -> DRIVING_MS
            else -> WALKING_MS
        }
        val limited = when {
            powerSave -> maxOf(base, POWER_SAVE_MS)
            thermalStatus >= 4 -> maxOf(base, CRITICAL_MS)
            thermalStatus == 3 -> maxOf(base, HOT_MS)
            warm -> maxOf(base, WARM_MS)
            else -> base
        }
        return if (speedMps < 0.4) maxOf(limited, STANDING_MS) else limited
    }
}
