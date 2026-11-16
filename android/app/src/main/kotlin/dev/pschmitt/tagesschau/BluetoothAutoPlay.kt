package dev.pschmitt.tagesschau

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

/**
 * Starts the latest tagesschau when one of the chosen paired Bluetooth devices connects (car,
 * headphones): sound only, video in the app, or video in picture-in-picture. With a delay, a
 * notification counts down and offers "cancel" and "play now"; either way a notification says that
 * playback started. Starting an activity from the background needs "display over other apps".
 */
object BluetoothAutoPlay {
    const val CHANNEL = "autoplay"
    private const val NOTIFICATION = 200
    const val ACTION_START = "dev.pschmitt.tagesschau.action.BT_START"
    const val ACTION_CANCEL = "dev.pschmitt.tagesschau.action.BT_CANCEL"
    const val EXTRA_NAME = "device_name"

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
    fun trigger(context: Context, deviceName: String) {
        val delay = Settings(context).bluetoothDelaySeconds
        if (delay <= 0) {
            start(context, deviceName)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delay * 1000L
        val pi = action(context, ACTION_START, deviceName)
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
                    action(context, ACTION_CANCEL, deviceName),
                )
                .addAction(
                    0,
                    context.getString(R.string.autoplay_now),
                    action(context, ACTION_START, deviceName),
                )
                .build(),
        )
    }

    /** Starts playback in the chosen mode and says so in a notification. */
    fun start(context: Context, deviceName: String) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(action(context, ACTION_START, deviceName))
        val settings = Settings(context)
        val intent =
            when (settings.bluetoothMode) {
                BluetoothMode.AUDIO ->
                    Intent(context, PlayLatestActivity::class.java)
                        .putExtra(PlaybackService.EXTRA_PREFER_LIVE, settings.preferLive)
                        .putExtra(PlaybackService.EXTRA_AUDIO_ONLY, true)
                        .putExtra(PlaybackService.EXTRA_RESUME, settings.bluetoothResume)
                BluetoothMode.VIDEO,
                BluetoothMode.PIP ->
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_PLAY_LATEST, true)
                        .putExtra(
                            MainActivity.EXTRA_PIP,
                            settings.bluetoothMode == BluetoothMode.PIP,
                        )
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w("BluetoothAutoPlay", "could not start playback", it) }
        notify(
            context,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tagesschau)
                .setContentTitle(context.getString(R.string.autoplay_started))
                .setContentText(context.getString(R.string.autoplay_connected, deviceName))
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .build(),
        )
    }

    fun cancel(context: Context, deviceName: String) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(action(context, ACTION_START, deviceName))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    private fun action(context: Context, action: String, deviceName: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, BluetoothAutoPlayActionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_NAME, deviceName),
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
        BluetoothAutoPlay.trigger(context, name)
    }
}

/** "Play now" / "cancel" from the countdown notification, and the delayed start (not exported). */
class BluetoothAutoPlayActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(BluetoothAutoPlay.EXTRA_NAME).orEmpty()
        when (intent.action) {
            BluetoothAutoPlay.ACTION_START -> BluetoothAutoPlay.start(context, name)
            BluetoothAutoPlay.ACTION_CANCEL -> BluetoothAutoPlay.cancel(context, name)
        }
    }
}
