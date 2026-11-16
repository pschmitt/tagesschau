@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.time.OffsetDateTime

/**
 * Full-screen video player. It drives the [PlaybackService]'s player through a [MediaController],
 * so playback goes on (as audio, with lock-screen controls) when the screen is left. Leaving it
 * while video plays switches to picture-in-picture.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var view: PlayerView
    private var future: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = future?.takeIf { it.isDone }?.let { runCatching { it.get() }.getOrNull() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view =
            PlayerView(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setShowNextButton(true)
                setShowPreviousButton(true)
                controllerShowTimeoutMs = 2500
                keepScreenOn = true
            }
        setContentView(view)
        installDoubleTapSeek()
        updatePipParams()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({ onConnected(f.get()) }, ContextCompat.getMainExecutor(this))
    }

    private fun onConnected(c: MediaController) {
        view.player = c
        c.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = updatePipParams()
            }
        )
        val editions = editionsFrom(intent) ?: return
        intent.removeExtra(EXTRA_IDS) // a recreated activity (rotation, PiP) keeps playing as is
        val index = intent.getIntExtra(EXTRA_INDEX, 0)
        c.setMediaItems(editions.map { Playlist.item(this, it) }, index, 0)
        c.prepare()
        c.play()
    }

    override fun onStop() {
        // Left without picture-in-picture: AUDIO keeps playing in the service, STOP pauses.
        val leaving = !isChangingConfigurations && !isInPictureInPictureModeCompat()
        if (leaving && Settings(this).leaveMode == LeaveMode.STOP) controller?.pause()
        view.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        super.onStop()
    }

    // --- Picture-in-picture ---

    private fun isInPictureInPictureModeCompat(): Boolean = isInPictureInPictureMode

    private fun pipParams(): PictureInPictureParams {
        val hint = android.graphics.Rect().also { view.getGlobalVisibleRect(it) }
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

    private fun updatePipParams() {
        setPictureInPictureParams(pipParams())
    }

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
        view.useController = !isInPictureInPictureMode
        if (isInPictureInPictureMode) view.hideController()
    }

    // --- Double tap: left or right third jumps 20 s; further taps keep adding ---

    @SuppressLint("ClickableViewAccessibility")
    private fun installDoubleTapSeek() {
        var seekUntil = 0L
        var seekSide = 0
        var total = 0
        val detector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean = tap(e, true)

                    override fun onSingleTapUp(e: MotionEvent): Boolean = tap(e, false)

                    private fun tap(e: MotionEvent, double: Boolean): Boolean {
                        val c = controller ?: return false
                        val side =
                            when {
                                e.x < view.width / 3f -> -1
                                e.x > view.width * 2f / 3f -> 1
                                else -> 0
                            }
                        val now = System.currentTimeMillis()
                        val inSeekMode = side != 0 && side == seekSide && now < seekUntil
                        if (side == 0 || !(double || inSeekMode)) return false
                        if (side != seekSide || now >= seekUntil) total = 0
                        seekSide = side
                        seekUntil = now + 700
                        total += 20
                        c.seekTo((c.currentPosition + side * 20_000L).coerceAtLeast(0))
                        Toast.makeText(
                                this@PlayerActivity,
                                (if (side < 0) "−" else "+") + "$total s",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                        return true
                    }
                },
            )
        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_DATES = "dates"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_INDEX = "index"

        fun intent(context: Context, editions: List<Edition>, index: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_IDS, editions.map { it.id }.toTypedArray())
                .putExtra(EXTRA_DATES, editions.map { it.date.toString() }.toTypedArray())
                .putExtra(EXTRA_URLS, editions.map { it.url }.toTypedArray())
                .putExtra(EXTRA_LIVE, editions.map { it.live }.toBooleanArray())
                .putExtra(EXTRA_INDEX, index)

        private fun editionsFrom(intent: Intent): List<Edition>? {
            val ids = intent.getStringArrayExtra(EXTRA_IDS) ?: return null
            val dates = intent.getStringArrayExtra(EXTRA_DATES) ?: return null
            val urls = intent.getStringArrayExtra(EXTRA_URLS) ?: return null
            val live = intent.getBooleanArrayExtra(EXTRA_LIVE) ?: BooleanArray(ids.size)
            return ids.indices.map { i ->
                Edition(ids[i], OffsetDateTime.parse(dates[i]), urls[i], live[i])
            }
        }
    }
}
