package dev.pschmitt.tagesschau

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Starts the latest tagesschau when one of the chosen paired Bluetooth devices connects (car,
 * headphones): sound only, video (optionally fullscreen), or video in picture-in-picture. With a
 * delay, a notification counts down and offers "cancel" and "play now"; either way a notification
 * says that playback started. Starting an activity from the background needs "display over other
 * apps"; with the screen off or locked the playback service starts it, and the picture follows on
 * unlock.
 */
object BluetoothAutoPlay {
    const val CHANNEL = "autoplay"
    private const val NOTIFICATION = 200
    const val ACTION_START = "dev.pschmitt.tagesschau.action.BT_START"
    const val ACTION_CANCEL = "dev.pschmitt.tagesschau.action.BT_CANCEL"
    const val EXTRA_NAME = "device_name"
    const val EXTRA_ADDRESS = "device_address"

    fun createChannel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.autoplay_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
    }

    /** A chosen device connected: start now, or count down first. */
    fun trigger(context: Context, deviceName: String, address: String) {
        val delay = Settings(context).bluetoothDelaySeconds
        if (delay <= 0) {
            start(context, deviceName, address)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delay * 1000L
        val pi = action(context, ACTION_START, deviceName, address)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tagesschau)
                .setContentTitle(context.getString(R.string.autoplay_countdown_title))
                .setContentText(context.getString(R.string.autoplay_connected, deviceName))
                .setWhen(System.currentTimeMillis() + delay * 1000L)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setTimeoutAfter(delay * 1000L + 2000)
                .addAction(
                    0,
                    context.getString(R.string.cancel),
                    action(context, ACTION_CANCEL, deviceName, address),
                )
                .addAction(
                    0,
                    context.getString(R.string.autoplay_now),
                    action(context, ACTION_START, deviceName, address),
                )
                .build(),
        )
    }

    /**
     * Starts playback in the chosen mode and says so in a notification. The sound goes to the
     * device that connected, also when another Bluetooth device is still connected ([AudioRoute]).
     */
    fun start(context: Context, deviceName: String, address: String) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(action(context, ACTION_START, deviceName, address))
        if (address.isNotEmpty()) AudioRoute.preferBluetooth(context, address, deviceName)
        val settings = Settings(context)
        val intent =
            AutoStart.intent(
                context,
                AutoStart.Options(
                    target = settings.bluetoothTarget,
                    preferLive = settings.preferLive,
                    mode = settings.bluetoothMode,
                    fullscreen = settings.bluetoothFullscreen,
                    resume = settings.bluetoothResume,
                    volume = settings.bluetoothVolume,
                ),
            )
        // Screen off or locked: an activity would only come up after unlocking, so start playback
        // straight from the playback service (the Bluetooth broadcast, the exact alarm and the
        // notification action may start a foreground service from the background).
        val started = !screenUsable(context) && startService(context, intent)
        if (!started) {
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w("BluetoothAutoPlay", "could not start playback", it) }
        }
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tagesschau)
                .setContentTitle(context.getString(R.string.autoplay_started))
                .setContentText(context.getString(R.string.autoplay_connected, deviceName))
                .setContentIntent(openPlayer(context))
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build(),
        )
    }

    /**
     * Tapping a "starting" notification: the player as the Bluetooth mode wants it (fullscreen or
     * picture-in-picture); the running playback goes on.
     */
    fun openPlayer(context: Context): PendingIntent {
        val settings = Settings(context)
        val mode = settings.bluetoothMode
        return PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_VIDEO, mode != BluetoothMode.AUDIO)
                .putExtra(MainActivity.EXTRA_PIP, mode == BluetoothMode.PIP)
                .putExtra(
                    MainActivity.EXTRA_FULLSCREEN,
                    mode == BluetoothMode.VIDEO && settings.bluetoothFullscreen,
                ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun screenUsable(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /**
     * Starts playback as a foreground service; false if Android does not allow it right now. Video
     * modes play the video stream without a picture, so the player view can take it over on unlock
     * without a break in the sound.
     */
    private fun startService(context: Context, activityIntent: Intent): Boolean {
        val video = activityIntent.getBooleanExtra(MainActivity.EXTRA_PLAY_LATEST, false)
        return runCatching {
                ContextCompat.startForegroundService(
                    context,
                    PlaybackService.playLatestIntent(
                            context,
                            activityIntent.getBooleanExtra(PlaybackService.EXTRA_PREFER_LIVE, true),
                            audioOnly = !video,
                            resume =
                                activityIntent.getBooleanExtra(PlaybackService.EXTRA_RESUME, true),
                        )
                        .putExtra(
                            PlaybackService.EXTRA_CHANNEL,
                            activityIntent.getBooleanExtra(PlaybackService.EXTRA_CHANNEL, false),
                        )
                        .putExtra(
                            MediaVolume.EXTRA,
                            activityIntent.getIntExtra(MediaVolume.EXTRA, -1),
                        )
                        .putExtra(PlaybackService.EXTRA_FOREGROUND, true)
                        // Video or picture-in-picture mode: show the picture once unlocked.
                        .putExtra(PlaybackService.EXTRA_VIDEO_ON_UNLOCK, video)
                        .putExtra(
                            MainActivity.EXTRA_PIP,
                            activityIntent.getBooleanExtra(MainActivity.EXTRA_PIP, false),
                        )
                        .putExtra(
                            MainActivity.EXTRA_FULLSCREEN,
                            activityIntent.getBooleanExtra(MainActivity.EXTRA_FULLSCREEN, false),
                        ),
                )
            }
            .onFailure {
                Log.w("BluetoothAutoPlay", "no foreground start, trying the activity", it)
            }
            .isSuccess
    }

    fun cancel(context: Context, deviceName: String, address: String) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(action(context, ACTION_START, deviceName, address))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    private fun action(
        context: Context,
        action: String,
        deviceName: String,
        address: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, BluetoothAutoPlayActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NAME, deviceName)
                .putExtra(EXTRA_ADDRESS, address),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notify(context: Context, n: android.app.Notification) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION, n) }
    }
}

/** The system's "Bluetooth device connected" broadcast. */
class BluetoothAutoPlayReceiver : BroadcastReceiver() {
    @android.annotation.SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        @Suppress("DEPRECATION")
        val device =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = device?.address ?: return
        if (address !in Settings(context).bluetoothDevices) return
        // A device that drops and reconnects should not restart playback each time.
        val prefs = context.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0) < 60_000) return
        prefs.edit { putLong("last", now) }
        val name = runCatching { device.name }.getOrNull() ?: address
        BluetoothAutoPlay.trigger(context, name, address)
    }
}

/** "Play now" / "cancel" from the countdown notification, and the delayed start (not exported). */
class BluetoothAutoPlayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(BluetoothAutoPlay.EXTRA_NAME).orEmpty()
        val address = intent.getStringExtra(BluetoothAutoPlay.EXTRA_ADDRESS).orEmpty()
        when (intent.action) {
            BluetoothAutoPlay.ACTION_START -> BluetoothAutoPlay.start(context, name, address)
            BluetoothAutoPlay.ACTION_CANCEL -> BluetoothAutoPlay.cancel(context, name, address)
        }
    }
}
