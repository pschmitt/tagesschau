@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch

/**
 * The app's one screen, laid out like the web player: the picture on top, the playhead as a
 * broadcast-time clock below it, then the editions by day. The player itself lives in the
 * [PlaybackService]; this screen drives it through a [MediaController]. Fullscreen in landscape,
 * picture-in-picture (or audio only, or stop: a setting) when the app is left.
 */
class MainActivity : ComponentActivity() {
    lateinit var playerView: PlayerView
    private var future: ListenableFuture<MediaController>? = null
    var controller by mutableStateOf<MediaController?>(null)
        private set

    var fullscreen by mutableStateOf(false)
    var inPip by mutableStateOf(false)
        private set

    var sharing by mutableStateOf(false)

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
        if (intent?.getBooleanExtra(EXTRA_INSTALL_UPDATE, false) == true) installUpdate()
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

    fun play(editions: List<Edition>, index: Int) {
        val c = controller ?: return
        val e = editions[index]
        if (e.channel) {
            c.setMediaItem(Playlist.item(this, e))
            c.prepare()
            c.seekToDefaultPosition()
            c.play()
            return
        }
        val resume = if (e.live) 0 else Positions.resumeAt(this, e.id)
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

    private fun pipParams(): PictureInPictureParams {
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
        playerView.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) playerView.hideController()
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
            c.setMediaItems(l.all.map { Playlist.item(activity, it) }, 0, 0)
            c.prepare()
        }
    }
    val currentId = st.mediaId
    val airTime = st.air
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
                        Icon(
                            painterResource(R.drawable.ic_tagesschau),
                            null,
                            tint = Color.White,
                            modifier =
                                Modifier.size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF185596)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("tagesschau", fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.width(6.dp))
                        Text("alle Ausgaben", color = Palette.Mist, fontSize = 16.sp)
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
            Box(Modifier.background(Palette.Deep)) { Transport(activity, st) }
            if (activity.sharing) ShareSheet(activity, st, st.url) { activity.sharing = false }
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = ::refresh,
                modifier = Modifier.weight(1f),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "clock") {
                        Clock(
                            airTime,
                            if (currentId == Edition.CHANNEL_ID) Edition.channel()
                            else editions.firstOrNull { it.id == currentId },
                        )
                    }
                    item(key = Edition.CHANNEL_ID) {
                        ChannelRow(playing = currentId == Edition.CHANNEL_ID) {
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
                                saved = Positions.get(activity, e.id),
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

/** The playhead as broadcast time, large, with the date below (as on the web). */
@Composable
private fun Clock(airTime: OffsetDateTime?, edition: Edition?) {
    val t = (airTime ?: edition?.date)?.atZoneSameInstant(Edition.BERLIN)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
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
                    append(t?.format(SECONDS) ?: "")
                }
            },
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-2).sp,
            lineHeight = 64.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(edition?.day ?: "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (edition?.live == true) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.on_air),
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
            today -> "Heute"
            today.minusDays(1) -> "Gestern"
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
    val watched = seen >= 0.95f
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
            modifier = Modifier.padding(start = 12.dp).width(100.dp),
        )
        Column(Modifier.weight(1f)) {
            if (e.live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Signal))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.live_from_start), fontSize = 15.sp)
                }
            } else {
                Text(
                    when {
                        playing -> stringResource(R.string.playing)
                        download?.state == Download.STATE_COMPLETED ->
                            stringResource(R.string.downloaded)
                        download?.state == Download.STATE_DOWNLOADING ->
                            stringResource(
                                R.string.downloading,
                                download.percentDownloaded.toInt().coerceAtLeast(0),
                            )
                        download?.state == Download.STATE_QUEUED -> stringResource(R.string.queued)
                        download?.state == Download.STATE_FAILED ->
                            stringResource(R.string.download_failed)
                        watched -> stringResource(R.string.watched)
                        seen > 0f && saved != null ->
                            stringResource(
                                R.string.resume_at,
                                e.date
                                    .plusNanos(saved.first * 1_000_000)
                                    .atZoneSameInstant(Edition.BERLIN)
                                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                            )
                        else -> "tagesschau"
                    },
                    color =
                        when {
                            playing -> Palette.Ink
                            download?.state == Download.STATE_COMPLETED -> Palette.Signal
                            else -> Palette.Mist
                        },
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                Download.STATE_COMPLETED ->
                    IconButton(onClick = onRemove) {
                        Icon(
                            painterResource(R.drawable.ic_delete),
                            stringResource(R.string.delete_download),
                            tint = Palette.Mist,
                        )
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
private fun ChannelRow(playing: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (playing) Palette.Panel else Palette.Deep)
            .clickable(onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Das Erste live", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                stringResource(if (playing) R.string.playing else R.string.channel_live),
                color = Palette.Mist,
                fontSize = 14.sp,
            )
        }
    }
}
