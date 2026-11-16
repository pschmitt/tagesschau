package dev.pschmitt.tagesschau

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** Sets the media volume (Bluetooth auto-play's fixed start volume). */
object MediaVolume {
    /** Extra carrying the volume in percent; absent or -1 leaves the volume alone. */
    const val EXTRA = "volume_percent"

    fun apply(context: Context, percent: Int) {
        if (percent < 0) return
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = (max * percent / 100f).roundToInt().coerceIn(0, max)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
    }
}
