@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.content.Context
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.session.MediaController
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the controls show, read from the controller a few times a second. */
data class PlayState(
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val buffered: Long = 0,
    val mediaId: String? = null,
    val start: OffsetDateTime? = null,
    val end: OffsetDateTime? = null,
    val live: Boolean = false,
    val speed: Float = 1f,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val loaded: Boolean = false,
    val url: String? = null,
    val muted: Boolean = false,
    val remote: Boolean = false,
    val videoOff: Boolean = false,
    /** The edition has a text track, and whether text tracks are switched on. */
    val hasSubs: Boolean = false,
    val subsOn: Boolean = false,
) {
    /** Broadcast time of a media position: the edition's air time plus the offset. */
    fun airAt(ms: Long): OffsetDateTime? = start?.plusNanos(ms * 1_000_000)

    /** Das Erste's live stream: the clock shows the current time. */
    val channel: Boolean
        get() = mediaId == Edition.CHANNEL_ID

    val air: OffsetDateTime?
        get() = if (channel) OffsetDateTime.now() else airAt(position)

    /** The timeline in media ms: fixed to the scheduled end for live, else the file's length. */
    val span: Long
        get() =
            when {
                live && start != null && end != null ->
                    java.time.Duration.between(start, end).toMillis().coerceAtLeast(1)
                duration > 0 -> duration
                else -> 0
            }
}

@Composable
fun rememberPlayState(context: Context, c: MediaController?): PlayState {
    val state by
        produceState(PlayState(), c) {
            while (true) {
                value =
                    if (c == null) PlayState()
                    else {
                        val extras = c.currentMediaItem?.mediaMetadata?.extras
                        val pos = c.currentPosition
                        val id = c.currentMediaItem?.mediaId
                        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: 0
                        val date = extras?.getString(Playlist.EXTRA_DATE)
                        if (c.isPlaying && date != null) Positions.save(context, date, pos, dur)
                        PlayState(
                            playing = c.isPlaying,
                            position = pos,
                            duration = dur,
                            buffered = c.bufferedPosition,
                            mediaId = id,
                            start = extras?.getString(Playlist.EXTRA_DATE)?.let(::parseDate),
                            end = extras?.getString(Playlist.EXTRA_END)?.let(::parseDate),
                            live = id?.startsWith("live-") == true,
                            speed = c.playbackParameters.speed,
                            hasPrevious = c.hasPreviousMediaItem(),
                            hasNext = c.hasNextMediaItem(),
                            loaded = c.mediaItemCount > 0,
                            url = extras?.getString(Playlist.EXTRA_URL),
                            muted = c.volume == 0f,
                            remote =
                                c.deviceInfo.playbackType ==
                                    androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE,
                            videoOff =
                                C.TRACK_TYPE_VIDEO in c.trackSelectionParameters.disabledTrackTypes,
                            hasSubs = c.currentTracks.containsType(C.TRACK_TYPE_TEXT),
                            subsOn =
                                C.TRACK_TYPE_TEXT !in c.trackSelectionParameters.disabledTrackTypes,
                        )
                    }
                delay(200)
            }
        }
    return state
}

private fun parseDate(s: String) = runCatching { OffsetDateTime.parse(s) }.getOrNull()

/**
 * Remembered playback position per edition, like the web player's. Keyed by the edition's air time,
 * so a position from the live stream carries over to the published file.
 */
object Positions {
    private fun prefs(context: Context) =
        context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun save(context: Context, id: String, position: Long, duration: Long) {
        if (position < 1000) return
        prefs(context).edit { putString(id, "$position,$duration") }
    }

    /** (position, duration) in ms, or null. */
    fun get(context: Context, id: String): Pair<Long, Long>? =
        prefs(context).getString(id, null)?.split(",")?.let {
            runCatching { it[0].toLong() to it[1].toLong() }.getOrNull()
        }

    /** Where to start an edition: the remembered position unless it was (almost) finished. */
    fun resumeAt(context: Context, id: String): Long {
        val (pos, dur) = get(context, id) ?: return 0
        return if (pos > 5000 && (dur <= 0 || pos.toDouble() / dur < 0.95)) pos else 0
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun OffsetDateTime.hm() = atZoneSameInstant(Edition.BERLIN).format(HM)

private fun OffsetDateTime.hms() = atZoneSameInstant(Edition.BERLIN).format(HMS)

private val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)

/**
 * The picture with YouTube's mobile player behaviour, in the web player's colours: controls on the
 * picture (hidden until a tap, hidden again 3 s into playback), previous / play-pause / next in the
 * middle, share and speed top right, broadcast time bottom left, fullscreen bottom right, the
 * timeline along the bottom edge (a thin progress line while the controls are hidden). Double taps
 * on the sides jump 20 s and every further tap adds 20 s; swipe up for fullscreen, down to leave
 * it.
 */
@Composable
fun VideoPlayer(
    activity: MainActivity,
    st: PlayState,
    overlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = activity.controller
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var skipSide by remember { mutableIntStateOf(0) }
    var skipTotal by remember { mutableIntStateOf(0) }
    val skipAlpha = remember { Animatable(0f) }
    val density = LocalDensity.current

    // Hidden again 3 s after the last interaction while playing; always shown while paused.
    LaunchedEffect(st.playing, interaction, controlsVisible) {
        if (st.playing && controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }
    LaunchedEffect(st.playing, st.loaded) { if (!st.playing && st.loaded) controlsVisible = true }

    var lastTap by remember { mutableLongStateOf(0L) }
    var lastSide by remember { mutableIntStateOf(0) }
    var seekUntil by remember { mutableLongStateOf(0L) }
    var seekSide by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Job?>(null) }

    fun skip(side: Int) {
        val cc = c ?: return
        pending?.cancel()
        if (seekSide != side) skipTotal = 0
        seekSide = side
        seekUntil = System.currentTimeMillis() + 700
        skipTotal += 20
        skipSide = side
        cc.seekTo((cc.currentPosition + side * 20_000L).coerceAtLeast(0))
        scope.launch {
            skipAlpha.snapTo(1f)
            delay(450)
            skipAlpha.animateTo(0f, tween(400))
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { activity.playerView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        // Gestures: taps (show/hide, double-tap seeking) and vertical swipes (fullscreen).
        var dragY by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragY = 0f },
                        onDragEnd = {
                            val limit = with(density) { 60.dp.toPx() }
                            if (dragY < -limit && !activity.fullscreen)
                                activity.setFullscreenMode(true)
                            if (dragY > limit && activity.fullscreen)
                                activity.setFullscreenMode(false)
                        },
                    ) { _, dy ->
                        dragY += dy
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val w = size.width
                        val x = up.position.x
                        val side =
                            when {
                                x < w / 3f -> -1
                                x > w * 2f / 3f -> 1
                                else -> 0
                            }
                        val now = System.currentTimeMillis()
                        when {
                            side != 0 && side == seekSide && now < seekUntil -> skip(side)
                            side != 0 && side == lastSide && now - lastTap < 300 -> {
                                lastTap = 0
                                skip(side)
                            }
                            else -> {
                                seekSide = 0
                                lastTap = now
                                lastSide = side
                                pending?.cancel()
                                pending = scope.launch {
                                    if (side != 0) delay(300)
                                    controlsVisible = !controlsVisible
                                    interaction++
                                }
                            }
                        }
                    }
                }
        )
        // Casting: the picture plays on the TV.
        if (st.remote) {
            Box(
                Modifier.fillMaxSize().background(Palette.Deep.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.casting_on),
                    color = Palette.Ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
            }
        }
        // Auto-play started without sound or picture: one tap turns it on.
        if ((st.muted || st.videoOff) && !activity.inPip) {
            Box(
                Modifier.align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Signal)
                    .clickable { activity.setMedia(video = true, sound = true) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(if (st.muted) R.string.unmute else R.string.video_on),
                    color = Palette.Deep,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
        // Double tap: a half circle on that side with the seconds jumped.
        if (skipAlpha.value > 0f && skipSide != 0) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val r = maxHeight * 1.1f
                Box(
                    Modifier.size(r)
                        .offset(
                            x = if (skipSide < 0) -r / 2 else maxWidth - r / 2,
                            y = (maxHeight - r) / 2,
                        )
                        .alpha(skipAlpha.value)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                )
                Column(
                    Modifier.align(if (skipSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 28.dp)
                        .alpha(skipAlpha.value),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (skipSide < 0) "◀◀◀" else "▶▶▶", color = Color.White, fontSize = 14.sp)
                    Text(
                        "$skipTotal Sekunden",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (!activity.inPip) {
            AnimatedVisibility(
                controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                    // Top right: the player settings (YouTube's gear), as a bottom sheet.
                    Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        // Google Cast (Media3's button, chooser and controller dialogs).
                        if (st.hasSubs && !st.remote) {
                            Ctl(
                                if (st.subsOn) R.drawable.ic_cc_on else R.drawable.ic_cc,
                                if (st.subsOn) R.string.subtitles_off else R.string.subtitles_on,
                            ) {
                                interaction++
                                c?.let {
                                    it.trackSelectionParameters =
                                        Subtitles.params(it.trackSelectionParameters, !st.subsOn)
                                }
                            }
                        }
                        androidx.media3.cast.MediaRouteButton()
                        Ctl(R.drawable.ic_settings, R.string.player_settings) {
                            interaction++
                            activity.playerSettings = true
                        }
                    }
                    // Centre: previous, play/pause, next.
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        RoundCtl(R.drawable.ic_ctl_prev, R.string.newer, 56, 30, st.hasPrevious) {
                            interaction++
                            c?.seekToPreviousMediaItem()
                        }
                        RoundCtl(
                            if (st.playing) R.drawable.ic_ctl_pause else R.drawable.ic_ctl_play,
                            if (st.playing) R.string.pause else R.string.play,
                            76,
                            42,
                        ) {
                            interaction++
                            c?.let { if (it.isPlaying) it.pause() else it.play() }
                        }
                        RoundCtl(R.drawable.ic_ctl_next, R.string.older, 56, 30, st.hasNext) {
                            interaction++
                            c?.seekToNextMediaItem()
                        }
                    }
                    // Bottom: broadcast time, fullscreen, and the timeline along the edge.
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                buildString {
                                    append(st.air?.hms() ?: "--:--:--")
                                    if (!st.channel && st.span > 0) {
                                        st.airAt(st.span)?.let { append(" / ").append(it.hm()) }
                                    }
                                },
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Ctl(
                                if (activity.fullscreen) R.drawable.ic_ctl_full_exit
                                else R.drawable.ic_ctl_full,
                                R.string.fullscreen,
                            ) {
                                interaction++
                                activity.setFullscreenMode(!activity.fullscreen)
                            }
                        }
                        Timeline(st, Modifier.fillMaxWidth().padding(horizontal = 6.dp)) { ms ->
                            interaction++
                            c?.seekTo(ms)
                        }
                    }
                }
            }
            // Controls hidden: only a thin progress line on the bottom edge (YouTube's red line).
            if (!controlsVisible && st.span > 0) {
                val frac = (st.position.toFloat() / st.span).coerceIn(0f, 1f)
                Box(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth(frac)
                        .height(2.dp)
                        .background(Palette.Signal)
                )
            }
        }
    }
}

/** A round button on the picture (YouTube's centre controls). */
@Composable
private fun RoundCtl(
    icon: Int,
    label: Int,
    size: Int,
    iconSize: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            stringResource(label),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** The web player's transport: timeline in broadcast time on top, buttons below. */
@Composable
fun Transport(activity: MainActivity, st: PlayState, onInteract: () -> Unit = {}) {
    val c = activity.controller
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.start?.hm() ?: "--:--", style = EdgeStyle)
            Timeline(st, Modifier.weight(1f).padding(horizontal = 10.dp)) { ms ->
                onInteract()
                c?.seekTo(ms)
            }
            Text(
                st.start?.let { s -> if (st.span > 0) st.airAt(st.span)?.hm() else null }
                    ?: "--:--",
                style = EdgeStyle,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ctl(R.drawable.ic_ctl_prev, R.string.newer, enabled = st.hasPrevious) {
                onInteract()
                c?.seekToPreviousMediaItem()
            }
            Ctl(
                if (st.playing) R.drawable.ic_ctl_pause else R.drawable.ic_ctl_play,
                if (st.playing) R.string.pause else R.string.play,
                size = 30,
            ) {
                onInteract()
                c?.let { if (it.isPlaying) it.pause() else it.play() }
            }
            Ctl(R.drawable.ic_ctl_next, R.string.older, enabled = st.hasNext) {
                onInteract()
                c?.seekToNextMediaItem()
            }
            Spacer(Modifier.weight(1f))
            SeekCtl(R.drawable.ic_ctl_back, "10", R.string.back10) {
                onInteract()
                c?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) }
            }
            SeekCtl(R.drawable.ic_ctl_fwd, "30", R.string.fwd30) {
                onInteract()
                c?.let { it.seekTo(it.currentPosition + 30_000) }
            }
            Ctl(R.drawable.ic_share, R.string.share) {
                onInteract()
                activity.sharing = true
            }
            Box(
                Modifier.height(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        onInteract()
                        c?.let {
                            val next =
                                SPEEDS[
                                    (SPEEDS.indexOf(it.playbackParameters.speed) + 1) % SPEEDS.size]
                            it.setPlaybackSpeed(next)
                        }
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    st.speed.toString().removeSuffix(".0").replace('.', ',') + "×",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Palette.Ink,
                )
            }
            Ctl(
                if (activity.fullscreen) R.drawable.ic_ctl_full_exit else R.drawable.ic_ctl_full,
                R.string.fullscreen,
            ) {
                onInteract()
                activity.setFullscreenMode(!activity.fullscreen)
            }
        }
    }
}

private val EdgeStyle =
    androidx.compose.ui.text.TextStyle(
        color = Palette.Mist,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )

@Composable
private fun Ctl(
    icon: Int,
    label: Int,
    enabled: Boolean = true,
    size: Int = 24,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            stringResource(label),
            tint = if (enabled) Palette.Ink else Palette.Ink.copy(alpha = 0.35f),
            modifier = Modifier.size(size.dp),
        )
    }
}

@Composable
private fun SeekCtl(icon: Int, seconds: String, label: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            stringResource(label),
            tint = Palette.Ink,
            modifier = Modifier.size(26.dp),
        )
        Text(
            seconds,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.Ink,
            modifier = Modifier.offset(y = 2.dp),
        )
    }
}

/** Timeline in broadcast time: rail, buffer, orange fill and knob; tap or drag to seek. */
@Composable
private fun Timeline(st: PlayState, modifier: Modifier, onSeek: (Long) -> Unit) {
    var dragFrac by remember { mutableFloatStateOf(-1f) }
    val span = st.span
    val frac =
        if (dragFrac >= 0) dragFrac
        else if (span > 0) (st.position.toFloat() / span).coerceIn(0f, 1f) else 0f
    val bufFrac = if (span > 0) (st.buffered.toFloat() / span).coerceIn(0f, 1f) else 0f
    val density = LocalDensity.current
    BoxWithConstraints(modifier.height(36.dp)) {
        val widthPx = with(density) { maxWidth.toPx() }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(span) {
                    detectTapGestures { o ->
                        if (span > 0) onSeek(((o.x / size.width).coerceIn(0f, 1f) * span).toLong())
                    }
                }
                .pointerInput(span) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (span > 0 && dragFrac >= 0) onSeek((dragFrac * span).toLong())
                            dragFrac = -1f
                        },
                        onDragCancel = { dragFrac = -1f },
                    ) { change, _ ->
                        dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
        ) {
            val y = size.height / 2
            val h = if (dragFrac >= 0) 5.dp.toPx() else 3.dp.toPx()
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(
                Palette.Mist.copy(alpha = 0.22f),
                Offset(0f, y - h / 2),
                Size(size.width, h),
                r,
            )
            drawRoundRect(
                Palette.Mist.copy(alpha = 0.38f),
                Offset(0f, y - h / 2),
                Size(size.width * bufFrac, h),
                r,
            )
            drawRoundRect(Palette.Signal, Offset(0f, y - h / 2), Size(size.width * frac, h), r)
            drawCircle(
                Palette.Signal,
                (if (dragFrac >= 0) 8.dp else 6.dp).toPx(),
                Offset(size.width * frac, y),
            )
        }
        if (dragFrac >= 0 && span > 0) {
            val ms = (dragFrac * span).toLong()
            val t = st.airAt(ms)?.hms() ?: ""
            val thumbUrl = if (st.live) null else st.url
            val boxW = if (thumbUrl != null) 136.dp else 64.dp
            val boxWpx = with(density) { boxW.toPx() }
            val x =
                (dragFrac * widthPx - boxWpx / 2).coerceIn(-boxWpx / 3, widthPx - boxWpx * 2 / 3)
            Column(
                Modifier.offset {
                        IntOffset(
                            x.roundToInt(),
                            -(if (thumbUrl != null) 104.dp else 26.dp).roundToPx(),
                        )
                    }
                    .width(boxW)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Palette.Deep)
            ) {
                // Scrubbing thumbnail (as on the web): the low-quality file, seeking along.
                if (thumbUrl != null)
                    ScrubThumb(thumbUrl, ms, Modifier.fillMaxWidth().height(76.dp))
                Text(
                    t,
                    color = Palette.Deep,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier =
                        Modifier.fillMaxWidth().background(Palette.Ink).padding(vertical = 1.dp),
                )
            }
        }
    }
}

/** A muted player on ARD's lowest-quality file that seeks to the nearest key frame. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ScrubThumb(url: String, ms: Long, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val low = url.replace(Regex("""\.web[a-z]+\.h264\.mp4$"""), ".webs.h264.mp4")
    val player =
        remember(low) {
            androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                volume = 0f
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                setMediaItem(androidx.media3.common.MediaItem.fromUri(low))
                prepare()
            }
        }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(ms) { player.seekTo(ms) }
    AndroidView(
        factory = {
            androidx.media3.ui.PlayerView(it).apply {
                useController = false
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = modifier,
    )
}

/**
 * YouTube's player settings sheet: speed, stream quality, continue with the next edition, share
 * this moment, download (or delete) the edition playing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(activity: MainActivity, st: PlayState, onDismiss: () -> Unit) {
    val c = activity.controller
    val settings = remember { Settings(activity) }
    var autoNext by remember { mutableStateOf(settings.autoNext) }
    var quality by remember { mutableStateOf(settings.playbackQuality) }
    val itemColors =
        androidx.compose.material3.ListItemDefaults.colors(containerColor = Palette.Deep)
    val editionId = st.mediaId?.takeIf { !st.live && !st.channel }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Palette.Deep,
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.speed)) },
                supportingContent = {
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                            androidx.compose.material3.FilterChip(
                                selected = st.speed == v,
                                onClick = { c?.setPlaybackSpeed(v) },
                                label = {
                                    Text(v.toString().removeSuffix(".0").replace('.', ',') + "×")
                                },
                            )
                        }
                    }
                },
                colors = itemColors,
            )
            if (editionId != null) {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(stringResource(R.string.quality)) },
                    supportingContent = {
                        androidx.compose.material3.SingleChoiceSegmentedButtonRow(
                            Modifier.padding(top = 6.dp)
                        ) {
                            val qs =
                                listOf("webxxl" to "1080p", "webxl" to "720p", "webm" to "540p")
                            qs.forEachIndexed { i, (q, label) ->
                                SegmentedButton(
                                    selected = quality == q,
                                    onClick = {
                                        quality = q
                                        settings.playbackQuality = q
                                        switchQuality(activity, q)
                                    },
                                    shape =
                                        androidx.compose.material3.SegmentedButtonDefaults
                                            .itemShape(i, qs.size),
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    },
                    colors = itemColors,
                )
            }
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.auto_next)) },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        autoNext,
                        {
                            autoNext = it
                            settings.autoNext = it
                        },
                    )
                },
                colors = itemColors,
            )
            if (st.start != null) {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(stringResource(R.string.share)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_share), null) },
                    modifier =
                        Modifier.clickable {
                            onDismiss()
                            activity.sharing = true
                        },
                    colors = itemColors,
                )
            }
            if (editionId != null) {
                val downloaded = Downloads.completedUri(activity, editionId) != null
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                if (downloaded) R.string.delete_download else R.string.download
                            )
                        )
                    },
                    leadingContent = {
                        Icon(
                            painterResource(
                                if (downloaded) R.drawable.ic_delete else R.drawable.ic_download
                            ),
                            null,
                        )
                    },
                    modifier =
                        Modifier.clickable {
                            if (downloaded) Downloads.remove(activity, editionId)
                            else
                                st.start?.let { date ->
                                    st.url?.let { url ->
                                        Downloads.start(activity, Edition(editionId, date, url))
                                    }
                                }
                            onDismiss()
                        },
                    colors = itemColors,
                )
            }
        }
    }
}

/** Swaps the playing edition for another quality of the same file, keeping the position. */
private fun switchQuality(activity: MainActivity, quality: String) {
    val c = activity.controller ?: return
    val item = c.currentMediaItem ?: return
    val url = item.mediaMetadata.extras?.getString(Playlist.EXTRA_URL) ?: return
    if (!url.startsWith("http")) return // a downloaded file stays as it is
    val newUrl = url.replace(Regex("""\.web[a-z]+\.h264\.mp4$"""), ".$quality.h264.mp4")
    if (newUrl == url) return
    val extras =
        android.os.Bundle(item.mediaMetadata.extras).apply { putString(Playlist.EXTRA_URL, newUrl) }
    val newItem =
        item
            .buildUpon()
            .setUri(newUrl)
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    val pos = c.currentPosition
    val playing = c.isPlaying
    val index = c.currentMediaItemIndex
    c.replaceMediaItem(index, newItem)
    c.seekTo(index, pos)
    if (playing) c.play()
}
