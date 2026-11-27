package dev.pschmitt.tagesschau

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * What happens when an edition that Bluetooth auto-play started comes to its end: stop the player
 * and/or disconnect the device that started it (both settings, off by default). Only when at least
 * [MIN_LEFT_MS] were left to play once playback ran: an edition that "resume" picks up a few
 * seconds before its end should not drop the car's connection right away. Das Erste live and the
 * edition on air have no end, so nothing happens there.
 */
object BluetoothSession {
    private const val TAG = "BluetoothSession"
    const val MIN_LEFT_MS = 30_000L
    /** A started auto-play has this long to get going before it no longer counts. */
    private const val PENDING_MS = 3 * 60_000L
    /** After a disconnect at the end, the device's reconnect does not start playback again. */
    const val QUIET_MS = 30 * 60_000L
    private const val PREFS = "bluetooth"
    private const val KEY_QUIET = "quiet_until_"

    private data class Device(val address: String, val name: String, val at: Long)

    @Volatile private var pending: Device? = null
    @Volatile private var armed: Device? = null

    /** Bluetooth auto-play starts playback for [address]. */
    fun started(address: String, name: String) {
        if (address.isEmpty()) return
        armed = null
        pending = Device(address, name, SystemClock.elapsedRealtime())
    }

    /** No auto-play reconnect for [address] right after the end disconnected it. */
    fun quiet(context: Context, address: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_QUIET + address, 0) >
            System.currentTimeMillis()

    /**
     * Watches the service's player. [stop] stops the player (no media items, so the notification
     * and the car's media display go away).
     */
    fun listener(context: Context, player: Player, stop: () -> Unit): Player.Listener =
        object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) ready(player)
                if (state == Player.STATE_ENDED) end(context, stop)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // "Continue with the next one" off: the player pauses at the end of the edition.
                if (
                    !playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
                )
                    end(context, stop)
            }

            override fun onMediaItemTransition(
                item: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                // Something else was chosen: no longer the auto-play's edition.
                if (
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                            pending == null)
                )
                    armed = null
            }
        }

    private fun ready(player: Player) {
        val device = pending ?: return
        pending = null
        if (SystemClock.elapsedRealtime() - device.at > PENDING_MS) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || player.isCurrentMediaItemDynamic) {
            Log.i(TAG, "no end to wait for (live)")
            return
        }
        val left = duration - player.currentPosition
        if (left >= MIN_LEFT_MS) armed = device
        Log.i(TAG, "${left / 1000} s left, end actions ${if (armed != null) "on" else "off"}")
    }

    private fun end(context: Context, stop: () -> Unit) {
        val device = armed ?: return
        armed = null
        val settings = Settings(context)
        if (settings.bluetoothStopAtEnd) {
            Log.i(TAG, "edition over: stopping the player")
            stop()
        }
        if (settings.bluetoothDisconnectAtEnd) {
            Log.i(TAG, "edition over: disconnecting ${device.address}")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong(KEY_QUIET + device.address, System.currentTimeMillis() + QUIET_MS)
            }
            AudioRoute.disconnect(context, device.address)
        }
    }
}
