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
        // Casting: the CastPlayer wraps the local player and moves playback to a Chromecast when
        // one connects (Default Media Receiver). Without Google Play Services it stays local.
        val sessionPlayer: androidx.media3.common.Player =
            runCatching {
                    androidx.media3.cast.CastPlayer.Builder(this)
                        .setLocalPlayer(player)
                        .setRemotePlayer(
                            androidx.media3.cast.RemoteCastPlayer.Builder(this)
                                .setMediaItemConverter(RemoteUrlConverter())
                                .build()
                        )
                        .build()
                }
                .getOrElse { player }
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(open).build()
        // Stop after the current edition unless "continue with the next one" is on.
        val settings = Settings(this)
        localPlayer = player
        player.pauseAtEndOfMediaItems = !settings.autoNext
        prefsListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Settings.KEY_AUTO_NEXT)
                    player.pauseAtEndOfMediaItems = !settings.autoNext
            }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private var localPlayer: ExoPlayer? = null

    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? =
        null

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
        runCatching { localPlayer?.release() }
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

/**
 * Hands the Chromecast the edition's web URL, also for downloaded editions (their local file is not
 * reachable from the TV).
 */
private class RemoteUrlConverter : androidx.media3.cast.MediaItemConverter {
    private val default = androidx.media3.cast.DefaultMediaItemConverter()

    override fun toMediaQueueItem(
        mediaItem: androidx.media3.common.MediaItem
    ): com.google.android.gms.cast.MediaQueueItem {
        val uri = mediaItem.localConfiguration?.uri
        val remote = mediaItem.mediaMetadata.extras?.getString(Playlist.EXTRA_REMOTE)
        val item =
            if (uri != null && uri.scheme?.startsWith("http") != true && remote != null) {
                mediaItem
                    .buildUpon()
                    .setUri(remote)
                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                    .build()
            } else mediaItem
        return default.toMediaQueueItem(item)
    }

    override fun toMediaItem(
        mediaQueueItem: com.google.android.gms.cast.MediaQueueItem
    ): androidx.media3.common.MediaItem = default.toMediaItem(mediaQueueItem)
}
