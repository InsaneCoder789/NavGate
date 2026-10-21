package com.rohanc.navgate.core

import java.time.DayOfWeek
import java.time.LocalDateTime

/** Whether a place is open now, from an OpenStreetMap `opening_hours` value. */
data class OpenStatus(val open: Boolean, val label: String)

/**
 * Reads the common subset of OSM opening hours: "24/7", day lists and ranges ("Mo-Fr", "Sa,Su"), one or more time ranges per day
 * (including past midnight), "off", and ";"-separated rules where later rules override earlier ones. Anything fancier
 * (public holidays, sunrise, weeks) returns null so the caller can show the raw text instead of guessing.
 */
object OpeningHours {
    private val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun status(spec: String?, now: LocalDateTime): OpenStatus? {
        val week = parse(spec) ?: return null
        val today = now.dayOfWeek.index()
        val minute = now.hour * 60 + now.minute
        fun open(day: Int): List<IntRange> = week[day]
        // Hours that started yesterday and run past midnight also cover the early minutes of today.
        val covering = open(today).firstOrNull { minute >= it.first && minute < it.last } ?: open((today + 6) % 7).firstOrNull { it.last > 1440 && minute + 1440 < it.last }?.let { (it.first - 1440)..(it.last - 1440) }
        if (covering != null) {
            val end = covering.last
            return if (week.all { r -> r.any { it.first == 0 && it.last >= 1440 } && r.size == 1 }) OpenStatus(true, "Open 24 hours")
            else OpenStatus(true, "Open · Closes ${clock(end)}")
        }
        for (offset in 0..7) {
            val day = (today + offset) % 7
            val next = open(day).filter { offset > 0 || it.first > minute }.minByOrNull { it.first } ?: continue
            val when_ = if (offset == 0) "" else if (offset == 1) " tomorrow" else " ${dayNames[day]}"
            return OpenStatus(false, "Closed · Opens ${clock(next.first)}$when_")
        }
        return OpenStatus(false, "Closed")
    }

    internal fun parse(spec: String?): List<List<IntRange>>? {
        val text = spec?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text == "24/7") return List(7) { listOf(0..1440) }
        if (listOf("PH", "SH", "sunrise", "sunset", "week", "easter", "[", "\"", "+", "<").any { text.contains(it) }) return null
        val week = MutableList(7) { emptyList<IntRange>() }
        for (rule in text.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val firstSpace = rule.indexOfFirst { it == ' ' }
            val dayPart = if (firstSpace < 0 && rule.first().isLetter()) rule else if (rule.first().isLetter()) rule.substring(0, firstSpace) else ""
            val timePart = if (dayPart.isEmpty()) rule else if (firstSpace < 0) "" else rule.substring(firstSpace + 1).trim()
            val selected = if (dayPart.isEmpty()) (0..6).toList() else parseDays(dayPart) ?: return null
            val ranges = if (timePart.equals("off", true) || timePart.equals("closed", true)) emptyList() else parseTimes(timePart) ?: return null
            for (d in selected) week[d] = ranges
        }
        return week
    }

    private fun parseDays(text: String): List<Int>? {
        val result = mutableListOf<Int>()
        for (part in text.split(',')) {
            val bounds = part.trim().split('-')
            val from = days.indexOf(bounds[0]).takeIf { it >= 0 } ?: return null
            val to = if (bounds.size == 2) days.indexOf(bounds[1]).takeIf { it >= 0 } ?: return null else from
            var d = from
            while (true) { result += d; if (d == to) break; d = (d + 1) % 7; if (result.size > 14) return null }
        }
        return result
    }

    private fun parseTimes(text: String): List<IntRange>? {
        if (text.isBlank()) return null
        val ranges = mutableListOf<IntRange>()
        for (part in text.split(',')) {
            val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(part) ?: return null
            val start = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
            var end = match.groupValues[3].toInt() * 60 + match.groupValues[4].toInt()
            if (start > 1440 || end > 1440 + 600) return null
            if (end <= start) end += 1440
            ranges += start..end
        }
        return ranges
    }

    private fun clock(minute: Int): String { val m = minute % 1440; return "%02d:%02d".format(m / 60, m % 60) }
    private fun DayOfWeek.index() = value - 1
}
