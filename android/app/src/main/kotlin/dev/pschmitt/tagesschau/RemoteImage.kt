package dev.pschmitt.tagesschau

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A small image from the network (programme thumbnails), loaded once and kept in memory. */
private object RemoteImages {
    val cache = android.util.LruCache<String, ImageBitmap>(16)

    suspend fun load(url: String): ImageBitmap? =
        cache.get(url)
            ?: withContext(Dispatchers.IO) {
                runCatching {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 15_000
                        try {
                            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                        } finally {
                            conn.disconnect()
                        }
                    }
                    .getOrNull()
                    ?.also { cache.put(url, it) }
            }
}

@Composable
fun rememberRemoteImage(url: String): ImageBitmap? {
    val image by produceState(RemoteImages.cache.get(url), url) { value = RemoteImages.load(url) }
    return image
}
