package com.rohanc.navgate.ui.ar

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point on the ground in ARCore world space: east-ish [x] and south-ish [z] metres (y is up and set separately). */
class GroundPoint(val x: Double, val z: Double)

/** Triangle data for the AR layer: interleaved x, y, z, u, v floats; two triangles per ribbon segment. */
class ArMeshData(val vertices: FloatArray, val vertexCount: Int)

/**
 * Builds the geometry drawn on the road. Everything is flat on the ground plane: a ribbon along the path whose [u] runs
 * -1 (left edge) to +1 (right edge) and whose [v] is metres travelled along the path, which the shader uses for fading and
 * the flowing chevrons; and a bent turn arrow.
 */
object ArMesh {
    private const val STRIDE = 5

    /** A triangle strip expressed as plain triangles (so several meshes can be concatenated). */
    fun ribbon(path: List<GroundPoint>, y: Double, halfWidth: Double): ArMeshData {
        if (path.size < 2) return ArMeshData(FloatArray(0), 0)
        val verts = FloatArray((path.size - 1) * 6 * STRIDE)
        var o = 0
        var dist = 0.0
        fun put(x: Double, z: Double, u: Double, v: Double) {
            verts[o++] = x.toFloat(); verts[o++] = y.toFloat(); verts[o++] = z.toFloat(); verts[o++] = u.toFloat(); verts[o++] = v.toFloat()
        }
        // Side offsets per point from the average direction of its two segments, so corners stay joined without gaps.
        val left = ArrayList<DoubleArray>(path.size); val right = ArrayList<DoubleArray>(path.size)
        for (i in path.indices) {
            val a = path[maxOf(0, i - 1)]; val b = path[minOf(path.lastIndex, i + 1)]
            val dx = b.x - a.x; val dz = b.z - a.z; val n = hypot(dx, dz).takeIf { it > 1e-6 } ?: 1.0
            val nx = -dz / n * halfWidth; val nz = dx / n * halfWidth
            left.add(doubleArrayOf(path[i].x + nx, path[i].z + nz)); right.add(doubleArrayOf(path[i].x - nx, path[i].z - nz))
        }
        for (i in 0 until path.size - 1) {
            val d0 = dist; val d1 = dist + hypot(path[i + 1].x - path[i].x, path[i + 1].z - path[i].z); dist = d1
            put(left[i][0], left[i][1], -1.0, d0); put(right[i][0], right[i][1], 1.0, d0); put(left[i + 1][0], left[i + 1][1], -1.0, d1)
            put(right[i][0], right[i][1], 1.0, d0); put(right[i + 1][0], right[i + 1][1], 1.0, d1); put(left[i + 1][0], left[i + 1][1], -1.0, d1)
        }
        return ArMeshData(verts, o / STRIDE)
    }

    /**
     * A flat arrow that runs in along the road you are on, bends at [turn], and points out along the road you will take.
     * [incoming]/[outgoing] are unit directions in the ground plane. [scale] widens it (used for the dark outline).
     */
    fun turnArrow(turn: GroundPoint, incoming: GroundPoint, outgoing: GroundPoint, y: Double, scale: Double = 1.0): ArMeshData {
        val w = 0.45 * scale
        val tail = GroundPoint(turn.x - incoming.x * 7.0, turn.z - incoming.z * 7.0)
        val neck = GroundPoint(turn.x + outgoing.x * 3.0, turn.z + outgoing.z * 3.0)
        val tip = GroundPoint(turn.x + outgoing.x * 5.6, turn.z + outgoing.z * 5.6)
        val verts = ArrayList<Float>()
        fun vert(x: Double, z: Double) { verts += x.toFloat(); verts += y.toFloat(); verts += z.toFloat(); verts += 0f; verts += 0f }
        fun quad(a: GroundPoint, b: GroundPoint, halfWidth: Double) {
            val dx = b.x - a.x; val dz = b.z - a.z; val n = hypot(dx, dz).takeIf { it > 1e-6 } ?: return
            val nx = -dz / n * halfWidth; val nz = dx / n * halfWidth
            vert(a.x + nx, a.z + nz); vert(a.x - nx, a.z - nz); vert(b.x + nx, b.z + nz)
            vert(a.x - nx, a.z - nz); vert(b.x - nx, b.z - nz); vert(b.x + nx, b.z + nz)
        }
        quad(tail, turn, w); quad(turn, neck, w)
        // Arrow head across the outgoing direction.
        val hx = -outgoing.z * 1.45 * scale; val hz = outgoing.x * 1.45 * scale
        vert(tip.x, tip.z); vert(neck.x + hx, neck.z + hz); vert(neck.x - hx, neck.z - hz)
        // Round the elbow with a small disc so the bend has no notch.
        val steps = 10
        for (s in 0 until steps) {
            val a0 = 2 * PI * s / steps; val a1 = 2 * PI * (s + 1) / steps
            vert(turn.x, turn.z); vert(turn.x + cos(a0) * w, turn.z + sin(a0) * w); vert(turn.x + cos(a1) * w, turn.z + sin(a1) * w)
        }
        val arr = verts.toFloatArray()
        return ArMeshData(arr, arr.size / STRIDE)
    }

    /** A flat ring, used to mark the destination. */
    fun ring(centre: GroundPoint, y: Double, radius: Double, width: Double): ArMeshData {
        val steps = 36
        val verts = FloatArray(steps * 6 * STRIDE); var o = 0
        fun put(x: Double, z: Double) { verts[o++] = x.toFloat(); verts[o++] = y.toFloat(); verts[o++] = z.toFloat(); verts[o++] = 0f; verts[o++] = 0f }
        for (s in 0 until steps) {
            val a0 = 2 * PI * s / steps; val a1 = 2 * PI * (s + 1) / steps
            val ox0 = cos(a0) * (radius + width / 2); val oz0 = sin(a0) * (radius + width / 2); val ix0 = cos(a0) * (radius - width / 2); val iz0 = sin(a0) * (radius - width / 2)
            val ox1 = cos(a1) * (radius + width / 2); val oz1 = sin(a1) * (radius + width / 2); val ix1 = cos(a1) * (radius - width / 2); val iz1 = sin(a1) * (radius - width / 2)
            put(centre.x + ox0, centre.z + oz0); put(centre.x + ix0, centre.z + iz0); put(centre.x + ox1, centre.z + oz1)
            put(centre.x + ix0, centre.z + iz0); put(centre.x + ix1, centre.z + iz1); put(centre.x + ox1, centre.z + oz1)
        }
        return ArMeshData(verts, o / STRIDE)
    }
}

/** Quaternion helpers (x, y, z, w) used to predict the camera pose between ARCore frames. */
object ArMath {
    fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    fun conjugate(q: DoubleArray) = doubleArrayOf(-q[0], -q[1], -q[2], q[3])

    /** [previous] turned by [k] times the step that takes it to [latest]: k = 1 gives [latest], k > 1 extrapolates the same turn. */
    fun extrapolate(previous: DoubleArray, latest: DoubleArray, k: Double): DoubleArray {
        var delta = multiply(latest, conjugate(previous))
        if (delta[3] < 0) delta = doubleArrayOf(-delta[0], -delta[1], -delta[2], -delta[3])      // shortest way round
        val w = delta[3].coerceIn(-1.0, 1.0)
        val angle = 2 * kotlin.math.acos(w)
        val s = kotlin.math.sqrt(1 - w * w)
        if (s < 1e-6 || angle < 1e-9) return latest
        val scaled = angle * k
        val axis = doubleArrayOf(delta[0] / s, delta[1] / s, delta[2] / s)
        val step = doubleArrayOf(axis[0] * sin(scaled / 2), axis[1] * sin(scaled / 2), axis[2] * sin(scaled / 2), cos(scaled / 2))
        val out = multiply(step, previous)      // previous turned by k steps: k = 1 lands on latest, k = 2 carries on as far again
        val n = kotlin.math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3])
        return doubleArrayOf(out[0] / n, out[1] / n, out[2] / n, out[3] / n)
    }
}
