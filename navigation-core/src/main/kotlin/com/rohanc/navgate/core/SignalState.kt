package com.rohanc.navgate.core

enum class SignalGrade { Searching, Strong, Fair, Weak, Lost }

/** How good the position is right now, in one word the screens can show: from the last fix's accuracy and age, and how many impossible fixes were thrown away. */
data class SignalState(val grade: SignalGrade = SignalGrade.Searching, val accuracyMeters: Double? = null, val secondsSinceFix: Int? = null, val rejectedJumps: Int = 0) {
    companion object {
        const val STRONG_METERS = 8.0
        const val FAIR_METERS = 20.0
        /** While guiding, this long without a fix is a lost signal; the car carries on along the route and the screens say so. */
        const val LOST_AFTER_SECONDS = 3.0
        const val STALE_AFTER_SECONDS = 10.0

        fun from(accuracyMeters: Double?, secondsSinceFix: Double?, navigating: Boolean, rejectedJumps: Int = 0): SignalState {
            val seconds = secondsSinceFix?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)
            val accuracy = accuracyMeters?.takeIf { it.isFinite() }
            val grade = when {
                seconds == null -> SignalGrade.Searching
                navigating && seconds > LOST_AFTER_SECONDS -> SignalGrade.Lost
                seconds > STALE_AFTER_SECONDS -> SignalGrade.Lost
                accuracy == null -> SignalGrade.Weak
                accuracy <= STRONG_METERS -> SignalGrade.Strong
                accuracy <= FAIR_METERS -> SignalGrade.Fair
                else -> SignalGrade.Weak
            }
            return SignalState(grade, accuracy, seconds?.toInt(), rejectedJumps)
        }
    }
}
