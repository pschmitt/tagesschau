@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the player, so playback goes on in the background, with lock-screen and headset controls
 * through the media session. Downloaded editions play from the download cache.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(Downloads.dataSourceFactory(this))
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(SEEK_MS)
                .setSeekForwardIncrementMs(SEEK_MS)
                .build()
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAY_LATEST) {
            val preferLive = intent.getBooleanExtra(EXTRA_PREFER_LIVE, true)
            val audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, true)
            scope.launch { playLatest(preferLive, audioOnly) }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun playLatest(preferLive: Boolean, audioOnly: Boolean) {
        val items = Playlist.latest(this, preferLive, audioOnly)
        val player = session?.player ?: return
        if (items.isEmpty()) return
        player.setMediaItems(items, 0, 0)
        player.prepare()
        player.play()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_LATEST = "dev.pschmitt.tagesschau.action.PLAY_LATEST"
        const val EXTRA_PREFER_LIVE = "prefer_live"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        const val SEEK_MS = 20_000L

        fun playLatestIntent(context: Context, preferLive: Boolean, audioOnly: Boolean) =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_LATEST)
                .putExtra(EXTRA_PREFER_LIVE, preferLive)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
    }
}
