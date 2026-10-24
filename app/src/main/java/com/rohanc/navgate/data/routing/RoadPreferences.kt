package com.rohanc.navgate.data.routing

/** What the driver wants the road router to avoid. Read when a request is built; set from Settings. */
object RoadPreferences {
    @Volatile var avoidTolls = false
    @Volatile var avoidHighways = false
}
