package dev.pschmitt.tagesschau

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TagesschauApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    Downloads.CHANNEL_ID,
                    getString(R.string.downloads_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        Downloads.scheduleRetention(this)
        Downloads.scheduleAutoDownload(this)
        EditionWatch.createChannel(this)
        BluetoothAutoPlay.createChannel(this)
        EditionWatch.schedule(this)
        dev.pschmitt.tagesschau.update.Updater.createChannel(this)
        dev.pschmitt.tagesschau.update.Updater.schedule(this)
    }
}
