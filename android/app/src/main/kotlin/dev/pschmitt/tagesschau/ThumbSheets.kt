package dev.pschmitt.tagesschau

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The server's scrubbing thumbnails of an edition: JPEG sheets of [columns] x [rows] frames, one
 * frame every [interval] seconds, row by row.
 */
data class Thumbs(
    val interval: Int,
    val columns: Int,
    val rows: Int,
    val sheets: List<String>,
)

/** Thumbnail sheets by edition id (from the last listing) and the decoded sheets in memory. */
object ThumbSheets {
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Thumbs>()

    // About 1.6 MB per decoded sheet (RGB 565); a 15-minute edition has ten.
    private val cache =
        object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
        }

    fun register(editions: List<Edition>) {
        editions.forEach { e -> e.thumbs?.let { byId[e.id] = it } }
    }

    fun of(id: String?): Thumbs? = id?.let { byId[it] }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? =
        cache.get(url)
            ?: withContext(Dispatchers.IO) {
                runCatching {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 15_000
                        try {
                            val opts =
                                BitmapFactory.Options().apply {
                                    inPreferredConfig = Bitmap.Config.RGB_565
                                }
                            conn.inputStream
                                .use { BitmapFactory.decodeStream(it, null, opts) }
                                ?.asImageBitmap()
                        } finally {
                            conn.disconnect()
                        }
                    }
                    .getOrNull()
                    ?.also { cache.put(url, it) }
            }
}

/**
 * The tile for media position [ms] from [thumbs]. All sheets of the edition load when it first
 * shows; [onFailed] is called when a sheet cannot be loaded (offline), so the caller can fall back.
 */
@Composable
fun SheetThumb(thumbs: Thumbs, ms: Long, onFailed: () -> Unit, modifier: Modifier) {
    val loaded = remember(thumbs) { mutableStateMapOf<String, ImageBitmap>() }
    val per = thumbs.columns * thumbs.rows
    val i = (ms / 1000 / thumbs.interval).toInt().coerceIn(0, thumbs.sheets.size * per - 1)
    val url = thumbs.sheets[i / per]
    LaunchedEffect(thumbs) {
        // The sheet under the finger first, then the others.
        val order = listOf(url) + thumbs.sheets.filter { it != url }
        order.forEach { u ->
            launch {
                val img = ThumbSheets.load(u)
                if (img != null) loaded[u] = img else if (u == url) onFailed()
            }
        }
    }
    val sheet = loaded[url] ?: ThumbSheets.cached(url)
    Canvas(modifier) {
        if (sheet == null) return@Canvas
        val w = sheet.width / thumbs.columns
        val h = sheet.height / thumbs.rows
        val n = i % per
        drawImage(
            sheet,
            srcOffset = IntOffset(n % thumbs.columns * w, n / thumbs.columns * h),
            srcSize = IntSize(w, h),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
