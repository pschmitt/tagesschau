package dev.pschmitt.tagesschau

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Optional notifications (own channel): an edition goes on air, or a new one is published. Checks
 * the server every 15 minutes (WorkManager's shortest period); tapping plays that edition.
 */
object EditionWatch {
    const val CHANNEL = "editions"
    private const val WORK = "edition-watch"

    fun createChannel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.editions_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!Settings(context).notifyNew) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<EditionWatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun notify(context: Context, id: Int, title: String, text: String, editionId: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open =
            PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PLAY_ID, editionId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val n =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tagesschau)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

class EditionWatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Settings(ctx).notifyNew) return Result.success()
        val listing =
            runCatching { Api(Settings(ctx).serverUrl).listing() }
                .getOrElse {
                    return Result.retry()
                }
        val prefs = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
        val first = !prefs.contains("edition")
        val live = listing.live
        if (live != null && prefs.getString("live", null) != live.id) {
            if (!first) {
                EditionWatch.notify(
                    ctx,
                    100,
                    ctx.getString(R.string.notify_live_title, live.time),
                    ctx.getString(R.string.notify_live_text),
                    live.id,
                )
            }
            prefs.edit { putString("live", live.id) }
        }
        val newest = listing.editions.firstOrNull()
        if (newest != null && prefs.getString("edition", null) != newest.id) {
            if (!first) {
                EditionWatch.notify(
                    ctx,
                    101,
                    ctx.getString(R.string.notify_new_title, newest.time),
                    newest.day,
                    newest.id,
                )
            }
            prefs.edit { putString("edition", newest.id) }
        }
        return Result.success()
    }
}
