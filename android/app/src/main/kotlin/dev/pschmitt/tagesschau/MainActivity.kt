@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import dev.pschmitt.tagesschau.update.Updater
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's one screen, laid out like the web player: the picture on top, the playhead as a
 * broadcast-time clock below it, then the editions by day. The player itself lives in the
 * [PlaybackService]; this screen drives it through a [MediaController]. Fullscreen in landscape,
 * picture-in-picture (or audio only, or stop: a setting) when the app is left.
 */
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    lateinit var playerView: PlayerView
    private var future: ListenableFuture<MediaController>? = null
    var controller by mutableStateOf<MediaController?>(null)
        private set

    var fullscreen by mutableStateOf(false)
    var inPip by mutableStateOf(false)
        private set

    var sharing by mutableStateOf(false)
    var playerSettings by mutableStateOf(false)

    /** An edition to start, from a notification tap. */
    var pendingPlayId by mutableStateOf<String?>(null)

    /**
     * Start the latest edition (Bluetooth auto-play), optionally straight into picture-in-picture.
     */
    var pendingLatest by mutableStateOf(false)
    var pendingPip = false
    var pendingFullscreen = false
    var pendingVolume = -1
    // What a play-latest request asks for (Bluetooth or Tasker); defaults: the Bluetooth settings.
    var pendingChannel = false
    var pendingPreferLive = true
    var pendingResume = true

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PLAY_ID)?.let { pendingPlayId = it }
        if (intent.getBooleanExtra(EXTRA_PLAY_LATEST, false)) {
            pendingPip = intent.getBooleanExtra(EXTRA_PIP, false)
            pendingFullscreen = intent.getBooleanExtra(EXTRA_FULLSCREEN, false)
            pendingVolume = intent.getIntExtra(MediaVolume.EXTRA, -1)
            val settings = Settings(this)
            pendingChannel =
                intent.getBooleanExtra(
                    PlaybackService.EXTRA_CHANNEL,
                    settings.bluetoothTarget == PlayTarget.CHANNEL,
                )
            pendingPreferLive =
                intent.getBooleanExtra(PlaybackService.EXTRA_PREFER_LIVE, settings.preferLive)
            pendingResume =
                intent.getBooleanExtra(PlaybackService.EXTRA_RESUME, settings.bluetoothResume)
            pendingLatest = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Bluetooth auto-play in PIP mode: enter picture-in-picture once playback runs. */
    var pipWhenPlaying = false

    /**
     * Enters picture-in-picture, retrying: Android refuses (returns false) while the activity is
     * still in its opening transition, which is exactly when Bluetooth auto-play asks for it.
     */
    fun enterPipNow(attempt: Int = 0) {
        if (isInPictureInPictureMode) return
        val ok =
            runCatching { enterPictureInPictureMode(pipParams()) }
                .onFailure { android.util.Log.w("MainActivity", "picture-in-picture failed", it) }
                .getOrDefault(false)
        android.util.Log.i("MainActivity", "picture-in-picture attempt $attempt: $ok")
        if (!ok && attempt < 6) window.decorView.postDelayed({ enterPipNow(attempt + 1) }, 500)
    }

    private fun maybeEnterPip() {
        if (!pipWhenPlaying || controller?.isPlaying != true) return
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        pipWhenPlaying = false
        window.decorView.post { enterPipNow() }
    }

    override fun onResume() {
        super.onResume()
        maybeEnterPip()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        playerView =
            PlayerView(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // The web player's controls are drawn in Compose (PlayerUi.kt).
                useController = false
            }
        setContent { TagesschauTheme { MainScreen(this) } }
        // Back leaves the player and stops playback (Home keeps playing, in picture-in-picture).
        // Registered first, so Compose's handlers (fullscreen, sheets) still come before it.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    controller?.pause()
                    pipWhenPlaying = false
                    // No auto-enter on the way out.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        setPictureInPictureParams(
                            PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                        )
                    moveTaskToBack(true)
                }
            },
        )
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) installUpdate()
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val f =
            MediaController.Builder(
                    this,
                    SessionToken(this, ComponentName(this, PlaybackService::class.java)),
                )
                .buildAsync()
        future = f
        f.addListener(
            {
                val c = runCatching { f.get() }.getOrNull() ?: return@addListener
                controller = c
                playerView.player = c
                c.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePipParams()
                            playerView.keepScreenOn = isPlaying
                            maybeEnterPip()
                        }
                    }
                )
                updatePipParams()
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    override fun onStop() {
        // Left without picture-in-picture: AUDIO keeps playing in the service, STOP pauses.
        val leaving = !isChangingConfigurations && !isInPictureInPictureMode
        if (leaving && Settings(this).leaveMode == LeaveMode.STOP) controller?.pause()
        playerView.player = null
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    /** Picture and sound on or off (auto-play); choosing an edition turns both back on. */
    fun setMedia(video: Boolean, sound: Boolean) {
        val c = controller ?: return
        c.trackSelectionParameters =
            c.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !video)
                .build()
        c.volume = if (sound) 1f else 0f
    }

    fun play(editions: List<Edition>, index: Int, resumeLatest: Boolean = true) {
        val c = controller ?: return
        setMedia(video = true, sound = true)
        val e = editions[index]
        if (e.channel) {
            c.setMediaItem(Playlist.item(this, e))
            c.prepare()
            c.seekToDefaultPosition()
            c.play()
            return
        }
        val resume = if (resumeLatest) Positions.resumeAt(this, e.date.toString()) else 0L
        c.setMediaItems(editions.map { Playlist.item(this, it) }, index, resume)
        c.prepare()
        c.play()
    }

    fun setFullscreenMode(on: Boolean) {
        fullscreen = on
        requestedOrientation =
            if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Hides the system bars while the picture fills the screen. */
    fun immersive(on: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (on) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // --- Picture-in-picture ---

    fun pipParams(): PictureInPictureParams {
        val hint = Rect().also { playerView.getGlobalVisibleRect(it) }
        val b =
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setSourceRectHint(hint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(
                Settings(this).leaveMode == LeaveMode.PIP && controller?.isPlaying == true
            )
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    fun updatePipParams() = setPictureInPictureParams(pipParams())

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (auto-enter); older versions are asked here.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                Settings(this).leaveMode == LeaveMode.PIP &&
                controller?.isPlaying == true
        ) {
            enterPictureInPictureMode(pipParams())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // The controls are Compose (PlayerUi.kt); Media3's own stay off, also after PiP.
        playerView.useController = false
    }

    // --- Self-update ---

    fun installUpdate() {
        lifecycleScope.launch {
            val update = runCatching { Updater.check() }.getOrNull()
            if (update == null) {
                toast(getString(R.string.update_none))
                return@launch
            }
            toast(getString(R.string.update_installing))
            if (!runCatching { Updater.install(this@MainActivity, update) }.getOrDefault(false)) {
                toast(getString(R.string.update_failed))
            }
        }
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_INSTALL_UPDATE = "install_update"
        const val EXTRA_PLAY_ID = "play_id"
        const val EXTRA_PLAY_LATEST = "play_latest"
        const val EXTRA_PIP = "pip"
        const val EXTRA_FULLSCREEN = "fullscreen"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: MainActivity) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val bigPicture = activity.inPip || activity.fullscreen || landscape
    LaunchedEffect(bigPicture) { activity.immersive(bigPicture && !activity.inPip) }

    val st = rememberPlayState(activity, activity.controller)
    if (bigPicture) {
        if (activity.fullscreen) BackHandler { activity.setFullscreenMode(false) }
        VideoPlayer(activity, st, overlay = true, modifier = Modifier.fillMaxSize())
        if (activity.sharing) ShareSheet(activity, st, st.url) { activity.sharing = false }
        if (activity.playerSettings)
            PlayerSettingsSheet(activity, st) { activity.playerSettings = false }
        return
    }

    val settings = remember { Settings(activity) }
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val downloads = remember { mutableStateMapOf<String, Download>() }
    val controller = activity.controller

    fun refresh() {
        loading = true
        scope.launch {
            runCatching { Api(settings.serverUrl).listing() }
                .onSuccess {
                    listing = it
                    if (Downloads.autoAllowed(activity))
                        Downloads.autoAdd(activity, it.editions, viaService = true)
                    runCatching { Downloads.resumePending(activity) }
                    runCatching { Downloads.prune(activity) }
                    error = null
                }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // Show the newest edition ready to play when nothing is loaded yet.
    LaunchedEffect(controller, listing) {
        val c = controller ?: return@LaunchedEffect
        val l = listing ?: return@LaunchedEffect
        if (c.mediaItemCount == 0 && l.all.isNotEmpty()) {
            // Das Erste live, or the edition on air (if wanted), else the newest one; then the
            // auto-play setting.
            if (settings.playTarget == PlayTarget.CHANNEL) {
                c.setMediaItem(Playlist.item(activity, Edition.channel()))
                c.prepare()
                c.seekToDefaultPosition()
            } else {
                val start = if (settings.preferLive || l.live == null) 0 else 1
                c.setMediaItems(l.all.map { Playlist.item(activity, it) }, start, 0)
                c.prepare()
            }
            when (settings.autoPlay) {
                AutoPlay.VIDEO_SOUND -> activity.setMedia(video = true, sound = true)
                AutoPlay.VIDEO_ONLY -> activity.setMedia(video = true, sound = false)
                AutoPlay.SOUND_ONLY -> activity.setMedia(video = false, sound = true)
                AutoPlay.NONE -> Unit
            }
            if (settings.autoPlay != AutoPlay.NONE) c.play()
        }
    }
    // Bluetooth auto-play with video: the latest edition, then picture-in-picture if asked.
    LaunchedEffect(controller, listing, activity.pendingLatest) {
        if (!activity.pendingLatest) return@LaunchedEffect
        val l = listing ?: return@LaunchedEffect
        if (controller == null || l.all.isEmpty()) return@LaunchedEffect
        activity.pendingLatest = false
        if (activity.pendingChannel) {
            activity.play(listOf(Edition.channel()), 0)
        } else {
            val start = if (activity.pendingPreferLive || l.live == null) 0 else 1
            activity.play(
                l.all,
                start.coerceAtMost(l.all.size - 1),
                resumeLatest = activity.pendingResume,
            )
        }
        // No delay here: resetting pendingLatest restarts (cancels) this effect. The activity
        // enters picture-in-picture as soon as playback runs (see onIsPlayingChanged).
        if (activity.pendingPip) activity.pipWhenPlaying = true
        if (activity.pendingFullscreen) {
            activity.pendingFullscreen = false
            activity.setFullscreenMode(true)
        }
        // Fixed start volume, set again after the device's audio route has taken over.
        val volume = activity.pendingVolume
        if (volume >= 0) {
            MediaVolume.apply(activity, volume)
            activity.lifecycleScope.launch {
                delay(2500)
                MediaVolume.apply(activity, volume)
            }
        }
    }
    // A notification asked for an edition: play it once the list is there.
    LaunchedEffect(controller, listing, activity.pendingPlayId) {
        val id = activity.pendingPlayId ?: return@LaunchedEffect
        val l = listing ?: return@LaunchedEffect
        if (controller == null) return@LaunchedEffect
        val i = l.all.indexOfFirst { it.id == id }
        if (i >= 0) activity.play(l.all, i)
        activity.pendingPlayId = null
    }
    val currentId = st.mediaId
    DisposableEffect(Unit) {
        val manager = Downloads.manager(activity)
        downloads.putAll(Downloads.all(activity))
        val listener =
            object : DownloadManager.Listener {
                override fun onDownloadChanged(m: DownloadManager, d: Download, e: Exception?) {
                    downloads[d.request.id] = d
                }

                override fun onDownloadRemoved(m: DownloadManager, d: Download) {
                    downloads.remove(d.request.id)
                }
            }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }
    // Media3 reports state changes, not progress: while downloads run, read their progress twice a
    // second so the circles fill smoothly.
    val running = downloads.values.any { it.state == Download.STATE_DOWNLOADING }
    LaunchedEffect(running) {
        val manager = Downloads.manager(activity)
        while (running) {
            // A fresh copy each time: Media3 updates the same object in place, which Compose
            // would not notice.
            for (d in manager.currentDownloads) {
                val progress =
                    androidx.media3.exoplayer.offline.DownloadProgress().apply {
                        bytesDownloaded = d.bytesDownloaded
                        percentDownloaded = d.percentDownloaded
                    }
                downloads[d.request.id] =
                    Download(
                        d.request,
                        d.state,
                        d.startTimeMs,
                        d.updateTimeMs,
                        d.contentLength,
                        d.stopReason,
                        d.failureReason,
                        progress,
                    )
            }
            kotlinx.coroutines.delay(500)
        }
    }

    // Online: the server's list. Offline: whatever is downloaded.
    val editions: List<Edition> =
        listing?.all
            ?: downloads.values
                .filter { it.state == Download.STATE_COMPLETED }
                .mapNotNull(Downloads::edition)
                .sortedByDescending { it.date }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The app icon (rounded, with its own corners).
                        androidx.compose.foundation.Image(
                            painterResource(R.drawable.ic_header),
                            null,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("tagesschau", fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.all_editions),
                            color = Palette.Mist,
                            fontSize = 16.sp,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            activity.startActivity(Intent(activity, SettingsActivity::class.java))
                        }
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            stringResource(R.string.settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Studio),
            )
        },
        containerColor = Palette.Studio,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            VideoPlayer(
                activity,
                st,
                overlay = false,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            if (activity.sharing) ShareSheet(activity, st, st.url) { activity.sharing = false }
            if (activity.playerSettings)
                PlayerSettingsSheet(activity, st) { activity.playerSettings = false }
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = ::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "clock") {
                        Clock(
                            activity,
                            st,
                            if (currentId == Edition.CHANNEL_ID) Edition.channel()
                            else editions.firstOrNull { it.id == currentId },
                        )
                    }
                    item(key = Edition.CHANNEL_ID) {
                        ChannelRow(
                            playing = currentId == Edition.CHANNEL_ID,
                            listing = listing,
                        ) {
                            val ch = Edition.channel()
                            activity.play(listOf(ch), 0)
                        }
                    }
                    if (error != null) {
                        item(key = "error") {
                            Text(
                                stringResource(R.string.load_failed, error.orEmpty()),
                                color = Palette.Mist,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    var lastDay: String? = null
                    editions.forEachIndexed { index, e ->
                        if (e.day != lastDay) {
                            lastDay = e.day
                            item(key = "day-${e.day}") { DayHeader(e) }
                        }
                        item(key = e.id) {
                            EditionRow(
                                e,
                                downloads[e.id],
                                playing = e.id == currentId,
                                saved = Positions.get(activity, e.date.toString()),
                                onPlay = { activity.play(editions, index) },
                                onDownload = { Downloads.start(activity, e) },
                                onRemove = { Downloads.remove(activity, e.id) },
                            )
                        }
                    }
                    if (editions.isEmpty() && !loading && error == null) {
                        item {
                            Text(
                                stringResource(R.string.empty),
                                color = Palette.Mist,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val SECONDS = DateTimeFormatter.ofPattern(":ss")
private val MILLIS = DateTimeFormatter.ofPattern(",SSS")

/**
 * The playhead as broadcast time, large, with milliseconds and the date below (as on the web).
 * Redrawn every frame from the controller's position; the play state only updates every 200 ms.
 */
@Composable
private fun Clock(activity: MainActivity, st: PlayState, edition: Edition?) {
    val state by rememberUpdatedState(st)
    var airTime by remember { mutableStateOf(st.air) }
    // The format follows the setting, also when it changes while this screen is open.
    val settings = remember { Settings(activity) }
    var format by remember { mutableStateOf(settings.clockFormat) }
    DisposableEffect(Unit) {
        val l =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Settings.KEY_CLOCK) format = settings.clockFormat
            }
        settings.prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis {
                val s = state
                val c = activity.controller
                airTime =
                    when {
                        s.channel && c != null -> s.channelAt(c.currentPosition)
                        c != null && s.start != null -> s.airAt(c.currentPosition)
                        else -> s.air
                    }
            }
        }
    }
    val t = (airTime ?: edition?.date)?.atZoneSameInstant(Edition.BERLIN)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        // "Aus": only the date line below.
        if (format != ClockFormat.OFF)
            Text(
                buildAnnotatedString {
                    append(t?.format(CLOCK) ?: "--:--")
                    withStyle(
                        SpanStyle(
                            fontSize = 26.sp,
                            color = Palette.Mist,
                            fontWeight = FontWeight.SemiBold,
                        )
                    ) {
                        if (format != ClockFormat.MINUTES) append(t?.format(SECONDS) ?: "")
                    }
                    withStyle(
                        SpanStyle(
                            fontSize = 18.sp,
                            color = Palette.Mist.copy(alpha = 0.8f),
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        )
                    ) {
                        if (airTime != null && format == ClockFormat.MILLIS)
                            append(t?.format(MILLIS) ?: "")
                    }
                },
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = LocalTimeFont.current,
                letterSpacing = (-1).sp,
                lineHeight = 64.sp,
            )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(edition?.day ?: "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (edition?.live == true) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (edition.airing) R.string.on_air else R.string.replay),
                    color = Palette.Signal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun DayHeader(e: Edition) {
    val today = java.time.LocalDate.now(Edition.BERLIN)
    val date = e.date.atZoneSameInstant(Edition.BERLIN).toLocalDate()
    val label =
        when (date) {
            today -> stringResource(R.string.today)
            today.minusDays(1) -> stringResource(R.string.yesterday)
            else -> e.day
        }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp)) {
        Text(label, color = Palette.Mist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        HorizontalDivider(Modifier.padding(top = 6.dp), color = Palette.Mist.copy(alpha = 0.22f))
    }
}

@Composable
private fun EditionRow(
    e: Edition,
    download: Download?,
    playing: Boolean,
    saved: Pair<Long, Long>?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    // How much of the edition was seen, as a thin line along the row's bottom (as on the web).
    val seen =
        saved?.let { (pos, dur) -> if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f }
            ?: 0f
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (playing) Palette.Panel else Color.Transparent)
            .drawBehind {
                if (seen > 0f && !e.live) {
                    drawRect(
                        if (playing) Palette.Signal else Palette.Mist.copy(alpha = 0.6f),
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width * seen, 2.dp.toPx()),
                    )
                }
            }
            .clickable(onClick = onPlay),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(3.dp)
                .height(56.dp)
                .background(if (playing) Palette.Signal else Color.Transparent)
        )
        Text(
            e.time,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = LocalTimeFont.current,
            modifier = Modifier.padding(start = 12.dp).width(100.dp),
        )
        Column(Modifier.weight(1f)) {
            if (e.live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.airing) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Signal))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        stringResource(
                            if (e.airing) R.string.live_from_start else R.string.replay_from_start
                        ),
                        fontSize = 15.sp,
                    )
                }
            } else {
                // No status words (the bar, the seen-line and the button say it): an offline mark
                // when downloaded, and a word only when a download failed.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (download?.state == Download.STATE_FAILED) {
                        Text(
                            stringResource(R.string.download_failed),
                            color = Palette.Mist,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (!e.live) {
            when (download?.state) {
                Download.STATE_DOWNLOADING,
                Download.STATE_QUEUED ->
                    IconButton(onClick = onRemove) {
                        CircularProgressIndicator(
                            progress = { (download.percentDownloaded / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                Download.STATE_COMPLETED -> {
                    // Downloaded: the orange offline mark; a tap asks before deleting it.
                    var confirm by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirm = true }) {
                        Icon(
                            painterResource(R.drawable.ic_offline),
                            stringResource(R.string.delete_download),
                            tint = Palette.Signal,
                        )
                    }
                    if (confirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text(stringResource(R.string.delete_download)) },
                            text = {
                                Text(
                                    stringResource(
                                        R.string.delete_download_text,
                                        e.title(LocalContext.current),
                                    )
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        confirm = false
                                        onRemove()
                                    }
                                ) {
                                    Text(stringResource(R.string.delete))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { confirm = false }
                                ) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
                else ->
                    IconButton(onClick = onDownload) {
                        Icon(
                            painterResource(R.drawable.ic_download),
                            stringResource(R.string.download),
                            tint = Palette.Ink,
                        )
                    }
            }
        }
    }
}

/** Das Erste live, above the editions. */
@Composable
private fun ChannelRow(playing: Boolean, listing: Listing?, onPlay: () -> Unit) {
    // What runs now; checked every half minute, so the row follows the programme.
    val now by
        produceState(OffsetDateTime.now()) {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                value = OffsetDateTime.now()
            }
        }
    val prog = listing?.programmeAt(now)
    val thumb = prog?.image?.let { rememberRemoteImage(it) }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (playing) Palette.Panel else Palette.Deep)
            .clickable(onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (prog != null) {
            // Thumbnail of the programme, the live dot on it.
            Box(
                Modifier.width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Palette.Panel)
            ) {
                thumb?.let {
                    androidx.compose.foundation.Image(
                        it,
                        null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier.padding(5.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                )
            }
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.channel_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            if (prog != null) {
                Text(
                    prog.title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.until,
                        prog.end
                            .atZoneSameInstant(Edition.BERLIN)
                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                    ),
                    color = Palette.Mist,
                    fontSize = 14.sp,
                )
            } else {
                Text(
                    stringResource(if (playing) R.string.playing else R.string.channel_live),
                    color = Palette.Mist,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
