package com.rohanc.navgate.ui.journey

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Arrangement
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

    suspend fun load(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        synchronized(cache) { cache.get(url)?.let { return it } }
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
        synchronized(cache) { if (bitmap != null) cache.put(url, bitmap) }
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

/** A stable frame keeps Directions in place while the network finishes (or fails). No motion is
 * needed for this frequent loading transition, including when reduced motion is enabled. */
@Composable
fun PlacePhoto(url: String, modifier: Modifier = Modifier, loader: suspend (String) -> ImageBitmap? = { PlacePhotos.load(it)?.asImageBitmap() }) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    val state by produceState<PhotoState>(PhotoState.Loading, url, attempt) {
        value = PhotoState.Loading
        value = loader(url)?.let { PhotoState.Ready(it) } ?: PhotoState.Unavailable
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).testTag("placePhotoFrame"), contentAlignment = Alignment.Center) {
            when (val photo = state) {
                PhotoState.Loading -> Text("Loading photo…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                PhotoState.Unavailable -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Photo unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { attempt++ }) { Text("Retry photo") }
                }
                is PhotoState.Ready -> Image(photo.bitmap, "Photo of the place", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        // Measure the same credit at every state, including large-font wrapping, without announcing
        // or showing attribution for a missing image. A blank one-line string would still shift on load.
        Text("Photo: Wikimedia Commons or OpenStreetMap contributors",
            modifier = if (state is PhotoState.Ready) Modifier else Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.labelSmall,
            color = if (state is PhotoState.Ready) MaterialTheme.colorScheme.onSurfaceVariant else Color.Transparent)
    }
}

private sealed interface PhotoState {
    data object Loading : PhotoState
    data object Unavailable : PhotoState
    data class Ready(val bitmap: ImageBitmap) : PhotoState
}
