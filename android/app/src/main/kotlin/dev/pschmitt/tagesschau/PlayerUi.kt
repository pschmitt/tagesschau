package dev.pschmitt.tagesschau

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
) {
    /** Broadcast time of a media position: the edition's air time plus the offset. */
    fun airAt(ms: Long): OffsetDateTime? = start?.plusNanos(ms * 1_000_000)

    val air: OffsetDateTime?
        get() = airAt(position)

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
                        if (c.isPlaying && id != null && !id.startsWith("live-")) {
                            Positions.save(context, id, pos, dur)
                        }
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
                        )
                    }
                delay(200)
            }
        }
    return state
}

private fun parseDate(s: String) = runCatching { OffsetDateTime.parse(s) }.getOrNull()

/** Remembered playback position per edition, like the web player's. */
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
 * The picture with the web player's behaviour: tap to play or pause (or to bring back hidden
 * controls), double tap on the left or right third to jump 20 s with every further tap adding 20 s,
 * a big play button while paused and a short play/pause sign on toggling. With [overlay] the
 * controls sit on the picture and hide while playing; otherwise they go below it.
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
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val flash = remember { Animatable(0f) }
    var flashPlay by remember { mutableStateOf(true) }
    var skipSide by remember { mutableIntStateOf(0) }
    var skipTotal by remember { mutableIntStateOf(0) }
    val skipAlpha = remember { Animatable(0f) }

    // Controls on the picture hide 2.5 s after the last interaction while playing.
    LaunchedEffect(overlay, st.playing, interaction, controlsVisible) {
        if (overlay && st.playing && controlsVisible) {
            delay(2500)
            controlsVisible = false
        }
        if (!st.playing) controlsVisible = true
    }

    fun toggle() {
        val cc = c ?: return
        flashPlay = !cc.isPlaying
        if (cc.isPlaying) cc.pause() else cc.play()
        scope.launch {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(650))
        }
    }

    // Tap handling as on the web: single taps wait briefly on the sides to tell double taps apart.
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
            delay(300)
            skipAlpha.animateTo(0f, tween(400))
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { activity.playerView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().pointerInput(overlay) {
                awaitEachGesture {
                    awaitFirstDown()
                    val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                    interaction++
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
                            val wasHidden = overlay && !controlsVisible
                            pending = scope.launch {
                                if (side != 0) delay(300)
                                if (wasHidden) controlsVisible = true else toggle()
                            }
                        }
                    }
                }
            }
        )
        // Big play button while paused (or before the first start).
        if (!st.playing && !activity.inPip && st.loaded) {
            Box(
                Modifier.align(Alignment.Center)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Palette.Deep.copy(alpha = 0.72f))
                    .clickable { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_ctl_play),
                    stringResource(R.string.play),
                    tint = Palette.Ink,
                    modifier = Modifier.size(34.dp).offset(x = 3.dp),
                )
            }
        }
        // Play/pause sign on toggling.
        if (flash.value > 0f) {
            Box(
                Modifier.align(Alignment.Center)
                    .size(76.dp)
                    .scale(0.85f + (1 - flash.value) * 0.45f)
                    .alpha(flash.value)
                    .clip(CircleShape)
                    .background(Palette.Deep.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(
                        if (flashPlay) R.drawable.ic_ctl_play else R.drawable.ic_ctl_pause
                    ),
                    null,
                    tint = Palette.Ink,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        // "+40 s" on the side of a double tap.
        if (skipAlpha.value > 0f && skipSide != 0) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(0.38f)
                    .align(if (skipSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .alpha(skipAlpha.value)
                    .background(
                        Brush.horizontalGradient(
                            if (skipSide < 0)
                                listOf(Palette.Deep.copy(alpha = 0.65f), Color.Transparent)
                            else listOf(Color.Transparent, Palette.Deep.copy(alpha = 0.65f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (if (skipSide < 0) "−" else "+") + "$skipTotal s",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Palette.Ink,
                )
            }
        }
        if (overlay && !activity.inPip) {
            AnimatedVisibility(
                controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Palette.Deep.copy(alpha = 0.7f),
                                    Palette.Deep.copy(alpha = 0.92f),
                                )
                            )
                        )
                        .padding(top = 36.dp)
                ) {
                    Transport(activity, st, onInteract = { interaction++ })
                }
            }
        }
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
                shareMoment(activity, st)
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
            val h = if (dragFrac >= 0) 6.dp.toPx() else 4.dp.toPx()
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
            drawCircle(Palette.Deep, 10.dp.toPx(), Offset(size.width * frac, y))
            drawCircle(Palette.Signal, 7.dp.toPx(), Offset(size.width * frac, y))
        }
        if (dragFrac >= 0 && span > 0) {
            val t = st.airAt((dragFrac * span).toLong())?.hms() ?: ""
            Text(
                t,
                color = Palette.Deep,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier =
                    Modifier.offset {
                            IntOffset(
                                (dragFrac * widthPx).roundToInt() - 32.dp.roundToPx(),
                                -26.dp.roundToPx(),
                            )
                        }
                        .background(Palette.Ink, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                        .width(52.dp),
            )
        }
    }
}

/** Shares a link to this moment, the web player's ?at= link. */
private fun shareMoment(activity: MainActivity, st: PlayState) {
    val at = st.air ?: return
    val url = "${Settings(activity).serverUrl}/?at=${at.toEpochSecond()}"
    val title = st.start?.let { "tagesschau ${it.hm()} Uhr" } ?: "tagesschau"
    activity.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, "$title: $url"),
            activity.getString(R.string.share),
        )
    )
}
