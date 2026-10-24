package com.rohanc.navgate.data.maps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** PMTiles version 3: a single-file map archive that MapLibre reads directly (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md). */
object Pmtiles {
    const val HEADER_BYTES = 127
    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TYPE_MVT = 1
    private const val ROOT_LIMIT = 16_384 - HEADER_BYTES

    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    data class Header(
        val rootOffset: Long, val rootLength: Long, val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long, val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean, val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int, val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double,
        val centerZoom: Int, val centerLon: Double, val centerLat: Double,
    )

    /** Position of a tile along the Hilbert curve over all zoom levels: the order PMTiles stores them in. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "tile $z/$x/$y is outside the grid" }
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        var tx = x.toLong(); var ty = y.toLong(); var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }

    internal fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    private class VarReader(val bytes: ByteArray) {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                if (pos >= bytes.size || shift > 63) throw IOException("Corrupt directory")
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
    }

    fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(data) } }.toByteArray()
    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    fun isGzip(data: ByteArray) = data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()

    fun serializeDirectory(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        varint(out, entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(out, e.tileId - last); last = e.tileId }
        for (e in entries) varint(out, e.runLength.toLong())
        for (e in entries) varint(out, e.length.toLong())
        entries.forEachIndexed { i, e ->
            if (i > 0 && e.offset == entries[i - 1].offset + entries[i - 1].length) varint(out, 0) else varint(out, e.offset + 1)
        }
        return gzip(out.toByteArray())
    }

    fun parseDirectory(compressed: ByteArray, compression: Int): List<Entry> {
        val r = VarReader(if (compression == COMPRESSION_GZIP) gunzip(compressed) else compressed)
        val n = r.varint(); if (n < 0 || n > 5_000_000) throw IOException("Corrupt directory")
        val ids = LongArray(n.toInt()); var last = 0L
        for (i in ids.indices) { last += r.varint(); ids[i] = last }
        val runs = IntArray(ids.size) { r.varint().toInt() }
        val lengths = IntArray(ids.size) { r.varint().toInt() }
        val offsets = LongArray(ids.size)
        for (i in ids.indices) {
            val v = r.varint()
            offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
        }
        return ids.indices.map { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    fun parseHeader(b: ByteArray): Header {
        if (b.size < HEADER_BYTES || String(b, 0, 7, Charsets.US_ASCII) != "PMTiles") throw IOException("Not a PMTiles file")
        if (b[7].toInt() != 3) throw IOException("Only PMTiles version 3 is supported")
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun e7(at: Int) = buf.getInt(at) / 10_000_000.0
        return Header(buf.getLong(8), buf.getLong(16), buf.getLong(24), buf.getLong(32), buf.getLong(40), buf.getLong(48), buf.getLong(56), buf.getLong(64),
            buf.getLong(72), buf.getLong(80), buf.getLong(88), b[96].toInt() == 1, b[97].toInt() and 0xff, b[98].toInt() and 0xff, b[99].toInt() and 0xff,
            b[100].toInt() and 0xff, b[101].toInt() and 0xff, e7(102), e7(106), e7(110), e7(114), b[118].toInt() and 0xff, e7(119), e7(123))
    }

    private fun writeHeader(h: Header): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PMTiles".toByteArray(Charsets.US_ASCII)); buf.put(3)
        buf.putLong(h.rootOffset).putLong(h.rootLength).putLong(h.metadataOffset).putLong(h.metadataLength)
        buf.putLong(h.leafOffset).putLong(h.leafLength).putLong(h.dataOffset).putLong(h.dataLength)
        buf.putLong(h.addressedTiles).putLong(h.tileEntries).putLong(h.tileContents)
        buf.put(if (h.clustered) 1 else 0).put(h.internalCompression.toByte()).put(h.tileCompression.toByte()).put(h.tileType.toByte())
        buf.put(h.minZoom.toByte()).put(h.maxZoom.toByte())
        buf.putInt((h.minLon * 1e7).toInt()).putInt((h.minLat * 1e7).toInt()).putInt((h.maxLon * 1e7).toInt()).putInt((h.maxLat * 1e7).toInt())
        buf.put(h.centerZoom.toByte()).putInt((h.centerLon * 1e7).toInt()).putInt((h.centerLat * 1e7).toInt())
        return buf.array()
    }

    /**
     * Collects tiles (in any order) and writes one archive. Tile bytes go to a scratch file as they arrive, so a large area never sits in memory.
     * Identical consecutive tiles share storage. Tiles are stored exactly as given; [tileCompression] says how they are compressed.
     */
    class Writer(private val scratch: File, private val tileCompression: Int = COMPRESSION_GZIP, private val tileType: Int = TYPE_MVT) : AutoCloseable {
        private class Pending(val tileId: Long, val offset: Long, val length: Int)
        private val pending = ArrayList<Pending>()
        private val data = RandomAccessFile(scratch, "rw").also { it.setLength(0) }
        private var dataLength = 0L
        private var minZ = 255; private var maxZ = 0

        @Synchronized fun add(z: Int, x: Int, y: Int, bytes: ByteArray) {
            pending += Pending(tileId(z, x, y), dataLength, bytes.size)
            data.seek(dataLength); data.write(bytes); dataLength += bytes.size
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }

        val count: Int @Synchronized get() = pending.size

        fun finish(target: File, metadataJson: String, minLon: Double, minLat: Double, maxLon: Double, maxLat: Double, centerZoom: Int = maxOf(minZ, 0).coerceAtLeast((minZ + maxZ) / 2)) {
            require(pending.isNotEmpty()) { "No tiles to write" }
            pending.sortBy { it.tileId }
            // Merge consecutive identical tiles into one run so they are stored once.
            val entries = ArrayList<Entry>(); val kept = ArrayList<Pending>()
            var contents = 0L; var addressed = 0L
            val sameAsPrev = { cur: Pending, prev: Pending -> cur.tileId == prev.tileId + 1 && cur.length == prev.length && sameBytes(cur, prev) }
            var i = 0
            while (i < pending.size) {
                var j = i + 1
                while (j < pending.size && sameAsPrev(pending[j], pending[j - 1]) && sameBytes(pending[j], pending[i])) j++
                kept += pending[i]; contents++
                addressed += (j - i)
                entries += Entry(pending[i].tileId, 0, pending[i].length, j - i)
                i = j
            }
            // Rewrite the kept tiles back to back, in tile order, and give each entry its final offset.
            val ordered = File(scratch.parentFile, scratch.name + ".ordered")
            var offset = 0L
            RandomAccessFile(ordered, "rw").use { out ->
                out.setLength(0)
                val buf = ByteArray(1 shl 16)
                kept.forEachIndexed { k, p ->
                    entries[k] = entries[k].copy(offset = offset)
                    data.seek(p.offset); var left = p.length
                    while (left > 0) { val n = data.read(buf, 0, minOf(buf.size, left)); if (n < 0) throw IOException("Scratch file ended early"); out.write(buf, 0, n); left -= n }
                    offset += p.length
                }
            }
            val metadata = gzip(metadataJson.toByteArray(Charsets.UTF_8))
            val (root, leaves) = layoutDirectories(entries)
            val rootOffset = HEADER_BYTES.toLong(); val metaOffset = rootOffset + root.size; val leafOffset = metaOffset + metadata.size
            val dataOffset = leafOffset + leaves.size
            val header = Header(rootOffset, root.size.toLong(), metaOffset, metadata.size.toLong(), leafOffset, leaves.size.toLong(), dataOffset, offset,
                addressed, entries.size.toLong(), contents, true, COMPRESSION_GZIP, tileCompression, tileType, minZ, maxZ, minLon, minLat, maxLon, maxLat,
                centerZoom, (minLon + maxLon) / 2, (minLat + maxLat) / 2)
            val part = File(target.parentFile, target.name + ".part")
            part.outputStream().buffered().use { out ->
                out.write(writeHeader(header)); out.write(root); out.write(metadata); out.write(leaves)
                ordered.inputStream().use { it.copyTo(out) }
            }
            ordered.delete()
            if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        }

        private fun sameBytes(a: Pending, b: Pending): Boolean {
            if (a.length != b.length) return false
            val x = ByteArray(a.length); val y = ByteArray(b.length)
            data.seek(a.offset); data.readFully(x); data.seek(b.offset); data.readFully(y)
            return x.contentEquals(y)
        }

        override fun close() { runCatching { data.close() }; scratch.delete() }

        /** A root directory that fits in the first 16 KiB, with leaf directories behind it when the tile count needs them. */
        private fun layoutDirectories(entries: List<Entry>): Pair<ByteArray, ByteArray> {
            val whole = serializeDirectory(entries)
            if (whole.size <= ROOT_LIMIT) return whole to ByteArray(0)
            var leafSize = 4096
            while (true) {
                val leaves = ByteArrayOutputStream(); val rootEntries = ArrayList<Entry>()
                entries.chunked(leafSize).forEach { chunk ->
                    val bytes = serializeDirectory(chunk)
                    rootEntries += Entry(chunk.first().tileId, leaves.size().toLong(), bytes.size, 0)
                    leaves.write(bytes)
                }
                val root = serializeDirectory(rootEntries)
                if (root.size <= ROOT_LIMIT) return root to leaves.toByteArray()
                leafSize *= 2
            }
        }
    }

    /** Reads tiles back out of an archive on disk. Used to check imported packs and in tests. */
    class Reader(private val file: File) {
        val header: Header = RandomAccessFile(file, "r").use { f ->
            val b = ByteArray(HEADER_BYTES); f.readFully(b); parseHeader(b)
        }

        private fun read(offset: Long, length: Long): ByteArray {
            require(length in 0..64L * 1024 * 1024) { "Section too large" }
            return RandomAccessFile(file, "r").use { f -> f.seek(offset); ByteArray(length.toInt()).also { f.readFully(it) } }
        }

        fun metadata(): String = read(header.metadataOffset, header.metadataLength).let { if (header.internalCompression == COMPRESSION_GZIP) gunzip(it) else it }.toString(Charsets.UTF_8)

        /** The stored (still compressed) tile bytes, or null when the archive has no such tile. */
        fun tile(z: Int, x: Int, y: Int): ByteArray? {
            val id = tileId(z, x, y)
            var dirOffset = header.rootOffset; var dirLength = header.rootLength; var base = 0L
            repeat(4) {
                val entries = parseDirectory(read(dirOffset, dirLength), header.internalCompression)
                var lo = 0; var hi = entries.size - 1; var found: Entry? = null
                while (lo <= hi) { val mid = (lo + hi) ushr 1; if (entries[mid].tileId <= id) { found = entries[mid]; lo = mid + 1 } else hi = mid - 1 }
                val e = found ?: return null
                if (e.runLength == 0) { dirOffset = header.leafOffset + e.offset; dirLength = e.length.toLong(); base = 0 }
                else return if (id < e.tileId + e.runLength) read(header.dataOffset + e.offset, e.length.toLong()) else null
            }
            return null
        }
    }
}
