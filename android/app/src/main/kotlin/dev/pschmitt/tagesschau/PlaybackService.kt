@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
                // Pause when headphones or a Bluetooth device disconnect, unless set to continue.
                .setHandleAudioBecomingNoisy(!Settings(this).continueOnDisconnect)
                .setSeekBackIncrementMs(SEEK_MS)
                .setSeekForwardIncrementMs(SEEK_MS)
                // Keep streaming with the screen off (CPU and Wi-Fi stay awake while playing).
                .setWakeMode(C.WAKE_MODE_NETWORK)
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
        session =
            MediaSession.Builder(this, sessionPlayer).setSessionActivity(open).build().also {
                // Media3 posts the media notification (and the system's media controls) only for
                // sessions added to the service, which otherwise happens when a controller
                // connects. Playback started from an intent (Bluetooth, Tasker) has none.
                addSession(it)
            }
        // Stop after the current edition unless "continue with the next one" is on.
        val settings = Settings(this)
        localPlayer = player
        player.pauseAtEndOfMediaItems = !settings.autoNext
        player.trackSelectionParameters =
            Subtitles.params(player.trackSelectionParameters, settings.subtitles)
        prefsListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Settings.KEY_AUTO_NEXT)
                    player.pauseAtEndOfMediaItems = !settings.autoNext
                if (key == Settings.KEY_SUBTITLES)
                    player.trackSelectionParameters =
                        Subtitles.params(player.trackSelectionParameters, settings.subtitles)
                if (key == Settings.KEY_CONTINUE_ON_DISCONNECT)
                    player.setHandleAudioBecomingNoisy(!settings.continueOnDisconnect)
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
            val resume = intent.getBooleanExtra(EXTRA_RESUME, true)
            val channel = intent.getBooleanExtra(EXTRA_CHANNEL, false)
            val volume = intent.getIntExtra(MediaVolume.EXTRA, -1)
            // Started as a foreground service (Bluetooth auto-play with the screen off): be in the
            // foreground at once, the edition list comes from the network first. The session's
            // own notification takes over the same id once playback runs.
            val foreground = intent.getBooleanExtra(EXTRA_FOREGROUND, false)
            if (foreground) startForegroundPlaceholder()
            if (intent.getBooleanExtra(EXTRA_VIDEO_ON_UNLOCK, false)) {
                watchUnlock(
                    pip = intent.getBooleanExtra(MainActivity.EXTRA_PIP, false),
                    fullscreen = intent.getBooleanExtra(MainActivity.EXTRA_FULLSCREEN, false),
                )
            }
            scope.launch {
                val playing = playLatest(preferLive, audioOnly, resume, channel)
                if (foreground && !playing) {
                    ServiceCompat.stopForeground(
                        this@PlaybackService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                }
                // Android restores the device's own volume once its audio route is active, which
                // can come a moment after the connection: set it at the start and again a bit
                // later.
                MediaVolume.apply(this@PlaybackService, volume)
                kotlinx.coroutines.delay(2500)
                MediaVolume.apply(this@PlaybackService, volume)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startForegroundPlaceholder() {
        BluetoothAutoPlay.createChannel(this)
        val n =
            NotificationCompat.Builder(this, BluetoothAutoPlay.CHANNEL)
                .setSmallIcon(R.drawable.ic_tagesschau)
                .setContentTitle(getString(R.string.autoplay_started))
                .setContentIntent(BluetoothAutoPlay.openPlayer(this))
                .setSilent(true)
                .build()
        runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
            .onFailure { android.util.Log.w("PlaybackService", "no foreground start", it) }
    }

    private var unlockReceiver: BroadcastReceiver? = null

    /**
     * Video auto-play that started while the phone was locked (video stream, no picture yet): open
     * the player (fullscreen or picture-in-picture, as set) once the phone is unlocked and still
     * playing. The player view takes over the running stream, so the sound does not stop.
     */
    private fun watchUnlock(pip: Boolean, fullscreen: Boolean) {
        unwatchUnlock()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unwatchUnlock()
                    val player = session?.player ?: return
                    if (!player.playWhenReady || player.mediaItemCount == 0) return
                    runCatching {
                            startActivity(
                                Intent(context, MainActivity::class.java)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                                    )
                                    .putExtra(MainActivity.EXTRA_SHOW_VIDEO, true)
                                    .putExtra(MainActivity.EXTRA_PIP, pip)
                                    .putExtra(MainActivity.EXTRA_FULLSCREEN, fullscreen)
                            )
                        }
                        .onFailure {
                            android.util.Log.w("PlaybackService", "could not show the video", it)
                        }
                }
            }
        unlockReceiver = receiver
        // Exported: SystemUI sends USER_PRESENT under its own uid, which a not-exported receiver
        // never gets. It is a protected broadcast, so no other app can send it.
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun unwatchUnlock() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
    }

    private suspend fun playLatest(
        preferLive: Boolean,
        audioOnly: Boolean,
        resumeLatest: Boolean,
        channel: Boolean = false,
    ): Boolean {
        val items = Playlist.latest(this, preferLive, audioOnly, channel)
        val player = session?.player ?: return false
        if (items.isEmpty()) return false
        // Whatever an earlier auto-play muted or switched off: sound on, picture as asked.
        player.volume = 1f
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
        // Continue the latest edition where it was left, if wanted (Bluetooth: a setting).
        val date = items[0].mediaMetadata.extras?.getString(Playlist.EXTRA_DATE)
        val resume = if (resumeLatest && date != null) Positions.resumeAt(this, date) else 0L
        // Das Erste live starts at its live edge (the default position), an edition at `resume`.
        val channel = items[0].mediaId == Edition.CHANNEL_ID
        player.setMediaItems(items, 0, if (channel) androidx.media3.common.C.TIME_UNSET else resume)
        player.prepare()
        player.play()
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        unwatchUnlock()
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
        const val EXTRA_RESUME = "resume"
        /** Play Das Erste live instead of the latest tagesschau. */
        const val EXTRA_CHANNEL = "channel"
        /** Started with startForegroundService: go into the foreground right away. */
        const val EXTRA_FOREGROUND = "foreground"
        /** Locked for now; open the video player (MainActivity extras) once unlocked. */
        const val EXTRA_VIDEO_ON_UNLOCK = "video_on_unlock"
        /** Media3's default notification id, so its notification replaces the placeholder. */
        private const val NOTIFICATION_ID =
            androidx.media3.session.DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
        const val SEEK_MS = 20_000L

        fun playLatestIntent(
            context: Context,
            preferLive: Boolean,
            audioOnly: Boolean,
            resume: Boolean = true,
        ) =
            Intent(context, PlaybackService::class.java)
                .setAction(ACTION_PLAY_LATEST)
                .putExtra(EXTRA_PREFER_LIVE, preferLive)
                .putExtra(EXTRA_AUDIO_ONLY, audioOnly)
                .putExtra(EXTRA_RESUME, resume)
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
