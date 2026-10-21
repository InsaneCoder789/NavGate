package com.rohanc.navgate.ui.location

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tells you the next turn through touch, so you can follow a route with the phone in a pocket, a mount or held up in AR.
 * Left is two quick taps, right is one long pulse, a U-turn is three taps, anything else is one tap.
 * [stage] 1 is the heads-up before the turn, 2 is "now" and is stronger.
 */
object TurnHaptics {
    fun cue(context: Context, kind: String?, stage: Int) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vibrator.hasVibrator()) return
            val strong = stage >= 2
            val tap = if (strong) 90L else 55L
            val pattern = when (kind) {
                "Left", "SlightLeft", "SharpLeft", "KeepLeft" -> longArrayOf(0, tap, 90, tap)
                "Right", "SlightRight", "SharpRight", "KeepRight" -> longArrayOf(0, if (strong) 420L else 260L)
                "UTurn" -> longArrayOf(0, tap, 80, tap, 80, tap)
                else -> longArrayOf(0, tap)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
