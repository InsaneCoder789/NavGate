package com.rohanc.navgate.core

/** GPX 1.1 export, readable by any maps or hiking app. Names are XML-escaped; coordinates keep 6 decimals (about 11 cm). */
object Gpx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun num(v: Double) = "%.6f".format(java.util.Locale.US, v)

    fun write(name: String, stops: List<Pair<String, GeoPoint>>, track: List<GeoPoint> = emptyList()): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="NavGate" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <metadata><name>${esc(name)}</name></metadata>")
        stops.forEach { (label, p) -> appendLine("""  <wpt lat="${num(p.latitude)}" lon="${num(p.longitude)}"><name>${esc(label)}</name></wpt>""") }
        if (stops.size >= 2) {
            appendLine("  <rte><name>${esc(name)}</name>")
            stops.forEach { (label, p) -> appendLine("""    <rtept lat="${num(p.latitude)}" lon="${num(p.longitude)}"><name>${esc(label)}</name></rtept>""") }
            appendLine("  </rte>")
        }
        if (track.size >= 2) {
            appendLine("  <trk><name>${esc(name)}</name><trkseg>")
            track.forEach { appendLine("""    <trkpt lat="${num(it.latitude)}" lon="${num(it.longitude)}"/>""") }
            appendLine("  </trkseg></trk>")
        }
        appendLine("</gpx>")
    }

    fun write(route: ManualRoute) = write(route.name, route.pins.map { it.label to it.point })
}
