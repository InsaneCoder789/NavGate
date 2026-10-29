package com.rohanc.navgate.ui.state

import com.rohanc.navgate.model.RouteHistoryEntry
import com.rohanc.navgate.model.TravelProfile
import java.time.Instant
import java.time.ZoneId

/** "Leaving now?": the place and way you usually go at this time of day, worked out on the phone from the trips already in Recents. */
data class Suggestion(val destinationId: String, val title: String, val mode: TravelProfile, val etaSeconds: Double, val tripsAtThisTime: Int)

/**
 * The destination reached most often within an hour of the current time of day, by the same way of travel. At least two arrivals on different days are needed,
 * so a single trip never turns into a habit. Stopped and interrupted trips do not count.
 */
fun suggestFor(history: List<RouteHistoryEntry>, nowEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Suggestion? {
    fun minuteOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone).let { it.hour * 60 + it.minute }
    fun day(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
    val now = minuteOfDay(nowEpochMillis)
    fun nearNow(entry: RouteHistoryEntry): Boolean { val d = kotlin.math.abs(minuteOfDay(entry.recordedAtEpochMillis) - now); return minOf(d, 1440 - d) <= 60 }
    val groups = history.filter { it.status == "Arrived" && nearNow(it) }.groupBy { it.destinationId to it.travelMode }
    val best = groups.filterValues { trips -> trips.map { day(it.recordedAtEpochMillis) }.distinct().size >= 2 }
        .maxWithOrNull(compareBy({ (_, trips) -> trips.map { day(it.recordedAtEpochMillis) }.distinct().size }, { (_, trips) -> trips.maxOf { it.recordedAtEpochMillis } })) ?: return null
    val latest = best.value.maxBy { it.recordedAtEpochMillis }
    return Suggestion(best.key.first, latest.destinationTitle, best.key.second, latest.etaSeconds, best.value.size)
}
