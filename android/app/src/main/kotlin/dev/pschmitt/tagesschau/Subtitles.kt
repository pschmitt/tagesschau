package dev.pschmitt.tagesschau

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Subtitles: the server's WebVTT per edition, kept next to downloads for offline playback. */
object Subtitles {
    fun url(context: Context, id: String) = "${Settings(context).serverUrl}/subtitles/$id.vtt"

    fun dir(context: Context) = File(context.filesDir, "subtitles")

    fun file(context: Context, id: String) = File(dir(context), "$id.vtt")

    /** Saves an edition's subtitles in the background; editions without any are left alone. */
    fun save(context: Context, id: String) {
        val app = context.applicationContext
        val target = file(app, id)
        if (target.exists()) return
        Thread {
                runCatching {
                    val conn = URL(url(app, id)).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    try {
                        if (conn.responseCode != 200) return@runCatching
                        target.parentFile?.mkdirs()
                        val part = File(target.path + ".part")
                        conn.inputStream.use { input ->
                            part.outputStream().use { input.copyTo(it) }
                        }
                        part.renameTo(target)
                    } finally {
                        conn.disconnect()
                    }
                }
            }
            .start()
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }

    /** Text tracks on (German preferred, so the live stream's optional track is picked) or off. */
    fun params(base: TrackSelectionParameters, on: Boolean): TrackSelectionParameters =
        base
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage("de")
            .build()
}
