package com.rohanc.navgate.ui.journey

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Photos of places (where the data has one: OpenStreetMap `image` and Wikimedia Commons). Fetched when the place is opened, kept in memory, and simply absent if offline or unavailable. */
object PlacePhotos {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    private val failed = HashSet<String>()

    suspend fun load(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        synchronized(cache) { cache.get(url)?.let { return it }; if (url in failed) return null }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 8_000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "NavGate/1.0 (place photos)") }
                try {
                    if (connection.responseCode != 200) return@runCatching null
                    val bytes = connection.inputStream.use { stream -> stream.readAtMost(MAX_BYTES) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
                    var sample = 1; while (bounds.outWidth / sample > 1400) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                } finally { connection.disconnect() }
            }.getOrNull()
        }
        synchronized(cache) { if (bitmap != null) cache.put(url, bitmap) else failed += url }
        return bitmap
    }

    private const val MAX_BYTES = 6 * 1024 * 1024

    /** At most [limit] bytes of the stream (a photo is never allowed to fill memory). */
    internal fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (total < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - total)); if (n < 0) break; out.write(buffer, 0, n); total += n }
        return out.toByteArray()
    }
}

@Composable
fun PlacePhoto(url: String, modifier: Modifier = Modifier) {
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, url) { value = PlacePhotos.load(url)?.asImageBitmap() }
    bitmap?.let { Image(it, "Photo of the place", modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop) }
}
