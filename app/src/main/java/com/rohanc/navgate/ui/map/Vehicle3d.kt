package com.rohanc.navgate.ui.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The vehicles a driver can pick. [length] is the real length in metres, which sets how big it is drawn against the map. */
enum class VehicleModel(val key: String, val label: String, val length: Float) {
    Arrow("arrow", "Arrow", 4.0f), Sedan("sedan", "Sedan", 4.6f), Pickup("pickup", "Trail truck", 5.0f),
    Suv("suv", "SUV", 4.7f), Hatch("hatch", "Hatchback", 3.9f), Coupe("coupe", "Coupe", 4.5f);
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Sedan }
}

/** The eight paint colours. */
enum class VehicleColor(val key: String, val label: String, val paint: Color) {
    White("white", "Glacier white", Color(0xFFF4F6FA)), Black("black", "Onyx black", Color(0xFF16171A)), Slate("slate", "Slate grey", Color(0xFF7C95A3)),
    Red("red", "Signal red", Color(0xFFF2495A)), Sky("sky", "Sky blue", Color(0xFF1E9BFF)), Yellow("yellow", "Sunrise yellow", Color(0xFFFFD21F)),
    Teal("teal", "Lagoon teal", Color(0xFF0E8A98)), Cobalt("cobalt", "Cobalt blue", Color(0xFF2F5BFF));
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == (if (key == "violet") "cobalt" else key) } ?: White }
}

private class Face(val pts: List<FloatArray>, val color: Color)

/**
 * Small low-poly 3D models drawn on a canvas: boxes, tapered boxes and wheel prisms are rotated to the vehicle's heading, tipped to the
 * camera's pitch, depth-sorted, culled and shaded face by face. Cheap enough to redraw every display frame, and it turns smoothly
 * because it is real geometry, not a set of sprites.
 */
object Vehicle3d {
    // x: right, y: forward, z: up, in metres, origin at the centre of the footprint on the ground.
    private fun frustum(cx: Float, cy: Float, z0: Float, z1: Float, w0: Float, l0: Float, w1: Float, l1: Float, shiftY: Float, color: Color, topColor: Color = color): List<Face> {
        fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
        val b = listOf(v(cx - w0 / 2, cy - l0 / 2, z0), v(cx + w0 / 2, cy - l0 / 2, z0), v(cx + w0 / 2, cy + l0 / 2, z0), v(cx - w0 / 2, cy + l0 / 2, z0))
        val t = listOf(v(cx - w1 / 2, cy + shiftY - l1 / 2, z1), v(cx + w1 / 2, cy + shiftY - l1 / 2, z1), v(cx + w1 / 2, cy + shiftY + l1 / 2, z1), v(cx - w1 / 2, cy + shiftY + l1 / 2, z1))
        return listOf(Face(listOf(b[3], b[2], b[1], b[0]), color), Face(listOf(t[0], t[1], t[2], t[3]), topColor),
            Face(listOf(b[0], b[1], t[1], t[0]), color), Face(listOf(b[1], b[2], t[2], t[1]), color), Face(listOf(b[2], b[3], t[3], t[2]), color), Face(listOf(b[3], b[0], t[0], t[3]), color))
    }
    private fun box(cx: Float, cy: Float, z0: Float, z1: Float, w: Float, l: Float, color: Color, top: Color = color) = frustum(cx, cy, z0, z1, w, l, w, l, 0f, color, top)

    /** A wheel: a sixteen-sided prism whose axis runs across the vehicle, centred [cz] above the ground. */
    private fun wheel(cx: Float, cy: Float, r: Float, width: Float, color: Color, cz: Float = r): List<Face> {
        val n = 16; val faces = ArrayList<Face>()
        fun ring(x: Float) = (0 until n).map { i -> val a = i * 2 * Math.PI.toFloat() / n; floatArrayOf(x, cy + r * cos(a), cz + r * sin(a)) }
        val l = ring(cx - width / 2); val rr = ring(cx + width / 2)
        for (i in 0 until n) { val j = (i + 1) % n; faces += Face(listOf(l[i], l[j], rr[j], rr[i]), color) }
        faces += Face(rr, color); faces += Face(rr.reversed(), color); faces += Face(l, color); faces += Face(l.reversed(), color)
        return faces
    }

    /** A rounded cross-section at [y]: a superellipse [hw] half-width wide between heights [z0] and [z1]; larger [n] is squarer. */
    private fun ring(y: Float, hw: Float, z0: Float, z1: Float, n: Float, seg: Int = 18): List<FloatArray> {
        val zc = (z0 + z1) / 2; val hh = (z1 - z0) / 2
        fun sp(v: Double) = (Math.signum(v) * Math.pow(Math.abs(v), 2.0 / n)).toFloat()
        return (0 until seg).map { i -> val t = i * 2 * Math.PI / seg; floatArrayOf(hw * sp(cos(t)), y, zc + hh * sp(sin(t))) }
    }

    /** Skins a row of cross-sections into smooth bodywork. Faces on the upper arc take [top], the rest [side]. Ends are closed. */
    private fun loft(sections: List<List<FloatArray>>, side: Color, top: Color): List<Face> {
        val faces = ArrayList<Face>(); val seg = sections[0].size
        for (k in 0 until sections.size - 1) {
            val a = sections[k]; val b = sections[k + 1]
            for (i in 0 until seg) {
                val j = (i + 1) % seg
                val upper = sin((i + 0.5) * 2 * Math.PI / seg) > 0.62
                faces += Face(listOf(a[i], b[i], b[j], a[j]), if (upper) top else side)
            }
        }
        for (cap in listOf(sections.first(), sections.last())) { faces += Face(cap, top); faces += Face(cap.reversed(), top) }
        return faces
    }

    private fun body(rows: List<FloatArray>, n: Float, side: Color, top: Color = side) =
        loft(rows.map { ring(it[0], it[1], it[2], it[3], n) }, side, top)   // each row: y, half-width, bottom, top

    private fun shade(c: Color, f: Float) = Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), c.alpha)

    private fun lights(f: MutableList<Face>, m: VehicleModel, nose: Float, tail: Float, hw: Float, z: Float) {
        for (sx in listOf(-1f, 1f)) {
            f += box(sx * hw * 0.68f, nose, z, z + 0.18f, 0.44f, 0.07f, Color(0xFFFFF6D6))     // headlights
            f += box(sx * hw * 0.72f, tail, z + 0.04f, z + 0.2f, 0.4f, 0.07f, Color(0xFFE8303C))   // tail lights
        }
        f += box(0f, nose, z - 0.22f, z - 0.02f, hw * 0.8f, 0.08f, Color(0xFF23252A))            // grille
    }

    private fun model(m: VehicleModel, paint: Color): List<Face> {
        val glass = Color(0xFF3B556E); val tyre = Color(0xFF17181C); val rim = Color(0xFFCDD2DA); val dark = Color(0xFF23252A)
        val f = ArrayList<Face>()
        fun wheels(x: Float, front: Float, rear: Float, r: Float) {
            for (cx in listOf(-x, x)) for (cy in listOf(front, rear)) {
                f += wheel(cx, cy, r, 0.30f, tyre)
                f += wheel(cx + (if (cx < 0) -0.16f else 0.16f), cy, r * 0.62f, 0.04f, rim)
            }
        }
        when (m) {
            VehicleModel.Sedan -> {
                f += body(listOf(r(-2.30f, .66f, .42f, .86f), r(-2.15f, .82f, .32f, .96f), r(-1.5f, .92f, .30f, 1.0f), r(-.3f, .94f, .30f, 1.02f), r(.8f, .93f, .30f, .99f), r(1.6f, .90f, .30f, .90f), r(2.1f, .84f, .32f, .78f), r(2.30f, .72f, .40f, .66f)), 3.4f, paint)
                f += body(listOf(r(-1.25f, .70f, .98f, 1.16f), r(-.95f, .80f, .99f, 1.40f), r(-.25f, .80f, 1.0f, 1.52f), r(.5f, .78f, 1.0f, 1.50f), r(1.0f, .74f, 1.0f, 1.30f), r(1.4f, .66f, .98f, 1.04f)), 2.6f, glass, paint)
                lights(f, m, 2.31f, -2.31f, .94f, .56f); wheels(.88f, 1.42f, -1.42f, .37f)
            }
            VehicleModel.Coupe -> {
                f += body(listOf(r(-2.25f, .70f, .38f, .80f), r(-2.1f, .86f, .28f, .92f), r(-1.3f, .94f, .26f, .94f), r(0f, .95f, .26f, .96f), r(1.2f, .92f, .26f, .90f), r(1.9f, .86f, .28f, .76f), r(2.25f, .72f, .36f, .62f)), 3.4f, paint)
                f += body(listOf(r(-1.5f, .72f, .94f, 1.10f), r(-1.1f, .80f, .95f, 1.30f), r(-.4f, .80f, .96f, 1.36f), r(.3f, .78f, .96f, 1.32f), r(.8f, .72f, .96f, 1.12f), r(1.1f, .66f, .94f, .98f)), 2.6f, glass, paint)
                lights(f, m, 2.26f, -2.26f, .94f, .52f); wheels(.9f, 1.4f, -1.4f, .37f)
            }
            VehicleModel.Hatch -> {
                f += body(listOf(r(-1.95f, .78f, .38f, .92f), r(-1.8f, .86f, .30f, .98f), r(-1.0f, .90f, .30f, 1.02f), r(0f, .91f, .30f, 1.02f), r(1.0f, .89f, .30f, .94f), r(1.6f, .83f, .32f, .80f), r(1.95f, .72f, .38f, .68f)), 3.4f, paint)
                f += body(listOf(r(-1.55f, .72f, 1.0f, 1.30f), r(-1.25f, .80f, 1.0f, 1.58f), r(-.3f, .81f, 1.0f, 1.62f), r(.4f, .78f, 1.0f, 1.58f), r(.85f, .72f, 1.0f, 1.32f), r(1.2f, .64f, 1.0f, 1.06f)), 2.6f, glass, paint)
                lights(f, m, 1.96f, -1.96f, .90f, .58f); wheels(.86f, 1.2f, -1.2f, .35f)
            }
            VehicleModel.Suv -> {
                f += body(listOf(r(-2.35f, .80f, .46f, 1.06f), r(-2.2f, .92f, .38f, 1.16f), r(-1.4f, .98f, .36f, 1.20f), r(0f, .99f, .36f, 1.22f), r(1.2f, .97f, .36f, 1.16f), r(1.9f, .92f, .38f, 1.02f), r(2.35f, .80f, .46f, .82f)), 3.6f, paint)
                f += body(listOf(r(-2.1f, .82f, 1.16f, 1.60f), r(-1.9f, .88f, 1.18f, 1.88f), r(-.4f, .89f, 1.2f, 1.94f), r(.8f, .86f, 1.2f, 1.88f), r(1.3f, .80f, 1.18f, 1.60f), r(1.6f, .72f, 1.14f, 1.30f)), 3.0f, glass, paint)
                lights(f, m, 2.36f, -2.36f, .98f, .66f); wheels(.95f, 1.5f, -1.45f, .44f)
            }
            VehicleModel.Pickup -> {
                f += body(listOf(r(0.3f, .98f, .50f, 1.18f), r(1.2f, .98f, .46f, 1.12f), r(1.9f, .94f, .46f, 1.00f), r(2.5f, .84f, .50f, .86f)), 3.6f, paint)       // hood and front
                f += body(listOf(r(-0.55f, .88f, 1.12f, 1.66f), r(-.1f, .92f, 1.14f, 1.90f), r(.5f, .90f, 1.14f, 1.92f), r(1.0f, .84f, 1.12f, 1.62f), r(1.25f, .78f, 1.1f, 1.30f)), 3.0f, glass, paint) // cab
                f += body(listOf(r(-0.6f, .98f, .50f, 1.12f), r(-0.2f, .98f, .46f, 1.18f)), 3.6f, paint)
                f += box(0f, -1.65f, .52f, .70f, 1.9f, 1.8f, shade(paint, 0.7f))                                                                 // bed floor
                f += box(-.93f, -1.65f, .70f, 1.2f, .10f, 1.8f, paint); f += box(.93f, -1.65f, .70f, 1.2f, .10f, 1.8f, paint); f += box(0f, -2.52f, .70f, 1.2f, 1.9f, .10f, paint)
                f += box(0f, .05f, 1.93f, 2.02f, 1.7f, .16f, dark)                                                                               // roof light bar
                for (x in listOf(-.55f, -.18f, .18f, .55f)) f += box(x, .05f, 2.0f, 2.12f, .22f, .12f, Color(0xFFFFF3C4))
                lights(f, m, 2.51f, -2.55f, .94f, .58f); wheels(.98f, 1.6f, -1.6f, .46f)
            }
            VehicleModel.Arrow -> {
                f += frustum(0f, 0f, 0.05f, 0.40f, 2.0f, 3.2f, 0.4f, 0.6f, 1.2f, paint)
                return f
            }
        }
        return f
    }

    private fun r(y: Float, hw: Float, z0: Float, z1: Float) = floatArrayOf(y, hw, z0, z1)

    /**
     * Draws [vehicle] centred at [center]. [yawDegrees] turns it on the ground (0 = pointing up the screen), [pitchDegrees] is how far the
     * camera is tipped from straight down, and [pxPerMeter] sets the size. This is the stand-in shown while a .glb model loads or when it cannot.
     */
    fun DrawScope.draw(vehicle: VehicleModel, paint: Color, center: Offset, yawDegrees: Float, pitchDegrees: Float, pxPerMeter: Float) {
        val yaw = Math.toRadians(yawDegrees.toDouble()); val t = Math.toRadians(pitchDegrees.toDouble().coerceIn(0.0, 75.0))
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat(); val ct = cos(t).toFloat(); val st = sin(t).toFloat()
        val half = vehicle.length * pxPerMeter / 2
        drawOval(Color(0x44000000), Offset(center.x - half * 0.62f, center.y - half * 0.62f * ct * 0.9f + half * 0.05f), Size(half * 1.24f, half * 1.24f * max(ct, 0.45f)))
        val light = floatArrayOf(-0.45f, -0.35f, 0.82f)
        data class Drawn(val depth: Float, val path: Path, val color: Color)
        val drawn = ArrayList<Drawn>()
        for (face in model(vehicle, paint)) {
            val ps = face.pts.map { p ->
                val x = p[0] * cy + p[1] * sy; val y = -p[0] * sy + p[1] * cy; val z = p[2]
                floatArrayOf(x, y, z, y * st - z * ct)
            }
            val a = face.pts[0]; val b = face.pts[1]; val c = face.pts[2]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]; val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-6f); nx /= len; ny /= len; nz /= len
            val rx = nx * cy + ny * sy; val ry = -nx * sy + ny * cy
            if (-(ry * st) + nz * ct <= 0.0001f) continue
            val lit = 0.50f + 0.58f * max(0f, rx * light[0] + ry * light[1] + nz * light[2])
            val path = Path().apply {
                ps.forEachIndexed { i, p -> val px = center.x + p[0] * pxPerMeter; val py = center.y - (p[1] * ct + p[2] * st) * pxPerMeter; if (i == 0) moveTo(px, py) else lineTo(px, py) }
                close()
            }
            drawn += Drawn(ps.map { it[3] }.average().toFloat(), path, shade(face.color, lit))
        }
        for (d in drawn.sortedByDescending { it.depth }) { drawPath(d.path, d.color); drawPath(d.path, d.color, style = Stroke(1.2f)) }
    }
}
