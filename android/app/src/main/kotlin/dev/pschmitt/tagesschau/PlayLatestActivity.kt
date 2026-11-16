package dev.pschmitt.tagesschau

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * Invisible: starts "play the latest tagesschau" and stays until playback runs (at most 10 s), so
 * Android lets the playback service go into the foreground. Tasker can start it directly with Send
 * Intent (target Activity, action [PlaybackService.ACTION_PLAY_LATEST]); the Tasker plugin starts
 * it too. Extras: [PlaybackService.EXTRA_PREFER_LIVE], [PlaybackService.EXTRA_AUDIO_ONLY].
 */
class PlayLatestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    private var future: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        val preferLive =
            intent.getBooleanExtra(PlaybackService.EXTRA_PREFER_LIVE, settings.preferLive)
        val audioOnly = intent.getBooleanExtra(PlaybackService.EXTRA_AUDIO_ONLY, true)
        val resume = intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, true)
        startService(
            PlaybackService.playLatestIntent(this, preferLive, audioOnly, resume)
                .putExtra(MediaVolume.EXTRA, intent.getIntExtra(MediaVolume.EXTRA, -1))
                .putExtra(
                    PlaybackService.EXTRA_CHANNEL,
                    intent.getBooleanExtra(PlaybackService.EXTRA_CHANNEL, false),
                )
        )
        val f =
            MediaController.Builder(
                    this,
                    SessionToken(this, ComponentName(this, PlaybackService::class.java)),
                )
                .buildAsync()
        future = f
        f.addListener(
            {
                val c = runCatching { f.get() }.getOrNull() ?: return@addListener finish()
                if (c.isPlaying) return@addListener finish()
                c.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) finish()
                        }
                    }
                )
            },
            ContextCompat.getMainExecutor(this),
        )
        handler.postDelayed({ finish() }, 10_000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
