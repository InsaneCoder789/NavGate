package com.rohanc.navgate.ui.ar

import android.os.Build

/**
 * A short, private record of how AR behaved, for field testing and bug reports. It keeps numbers only (tracking state, frame rates,
 * confidence, phone height, whether the ground and road were found): no places, routes or coordinates, and it leaves the phone only
 * when the user chooses to share it.
 */
object ArDiagnostics {
    private class Entry(val atMillis: Long, val tracking: String, val aligned: Boolean, val ground: Boolean, val confidence: Double, val cameraFps: Int, val overlayFps: Int, val phoneHeight: Double, val objects: Int)
    private val entries = ArrayDeque<Entry>()
    private var started = 0L

    @Synchronized fun record(now: Long, hud: ArHud) {
        if (started == 0L) started = now
        if (entries.isNotEmpty() && now - entries.last().atMillis < 1_000) return
        entries.addLast(Entry(now - started, hud.tracking ?: "ok", hud.aligned, hud.groundFound, hud.confidence, hud.cameraFps, hud.overlayFps, hud.phoneHeight, hud.objects))
        while (entries.size > 600) entries.removeFirst()          // the last ten minutes
    }

    @Synchronized fun clear() { entries.clear(); started = 0L }

    @Synchronized fun export(appVersion: String): String = buildString {
        appendLine("NavGate AR diagnostics (numbers only: no places, routes or coordinates)")
        appendLine("App $appVersion · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Samples: ${entries.size}")
        if (entries.isNotEmpty()) {
            val tracking = entries.count { it.tracking == "ok" } * 100 / entries.size
            val aligned = entries.count { it.aligned } * 100 / entries.size
            appendLine("Tracking OK ${tracking}% · aligned ${aligned}% · mean confidence ${"%.2f".format(entries.map { it.confidence }.average())}")
            appendLine("Camera fps ${entries.map { it.cameraFps }.average().toInt()} · overlay fps ${entries.map { it.overlayFps }.average().toInt()}")
            appendLine("Phone height above ground ${"%.2f".format(entries.filter { it.ground }.map { it.phoneHeight }.average().takeIf { !it.isNaN() } ?: 0.0)} m (ground found ${entries.count { it.ground } * 100 / entries.size}% of the time)")
        }
        appendLine(); appendLine("seconds, tracking, aligned, ground, confidence, cameraFps, overlayFps, phoneHeight, objects")
        entries.forEach { appendLine("${it.atMillis / 1000}, ${it.tracking.replace(',', ';')}, ${it.aligned}, ${it.ground}, ${"%.2f".format(it.confidence)}, ${it.cameraFps}, ${it.overlayFps}, ${"%.2f".format(it.phoneHeight)}, ${it.objects}") }
    }
}
