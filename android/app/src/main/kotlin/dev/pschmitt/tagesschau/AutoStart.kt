package dev.pschmitt.tagesschau

import android.content.Context
import android.content.Intent

/**
 * Starting playback on its own (Bluetooth auto-play, the Tasker action): what to play and how, as
 * an intent for [PlayLatestActivity] (sound only) or [MainActivity] (video, picture-in-picture).
 */
object AutoStart {
    data class Options(
        val target: PlayTarget = PlayTarget.TAGESSCHAU,
        /** The edition on air from its start (else the newest finished one). */
        val preferLive: Boolean = true,
        val mode: BluetoothMode = BluetoothMode.AUDIO,
        /** Continue the latest edition where it was left. */
        val resume: Boolean = true,
        /** Media volume in percent, or -1 to leave it alone. */
        val volume: Int = -1,
    )

    fun intent(context: Context, o: Options): Intent =
        when (o.mode) {
                BluetoothMode.AUDIO ->
                    Intent(context, PlayLatestActivity::class.java)
                        .putExtra(PlaybackService.EXTRA_AUDIO_ONLY, true)
                BluetoothMode.VIDEO,
                BluetoothMode.PIP ->
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_PLAY_LATEST, true)
                        .putExtra(MainActivity.EXTRA_PIP, o.mode == BluetoothMode.PIP)
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(PlaybackService.EXTRA_PREFER_LIVE, o.preferLive)
            .putExtra(PlaybackService.EXTRA_RESUME, o.resume)
            .putExtra(PlaybackService.EXTRA_CHANNEL, o.target == PlayTarget.CHANNEL)
            .putExtra(MediaVolume.EXTRA, o.volume)
}
