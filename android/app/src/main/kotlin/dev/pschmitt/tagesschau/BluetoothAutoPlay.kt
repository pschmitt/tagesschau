package dev.pschmitt.tagesschau

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts the latest tagesschau (audio, the edition on air from its start when wanted) when one of
 * the chosen paired Bluetooth devices connects, e.g. the car or headphones. Like the Tasker action
 * it starts [PlayLatestActivity], which needs the "display over other apps" permission while
 * another app is in front or the screen is off.
 */
class BluetoothAutoPlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        @Suppress("DEPRECATION")
        val device =
            if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val settings = Settings(context)
        val address = device?.address ?: return
        if (address !in settings.bluetoothDevices) return
        // A device that drops and reconnects should not restart playback each time.
        val prefs = context.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0) < 60_000) return
        prefs.edit().putLong("last", now).apply()
        val start =
            Intent(context, PlayLatestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(PlaybackService.EXTRA_PREFER_LIVE, settings.preferLive)
                .putExtra(PlaybackService.EXTRA_AUDIO_ONLY, true)
        runCatching { context.startActivity(start) }
            .onFailure { Log.w("BluetoothAutoPlay", "could not start playback", it) }
    }
}
