@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.pschmitt.tagesschau

import android.app.Notification
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Offline editions: one cache for downloads, read by playback too, so a downloaded edition plays
 * without network. Each download carries its edition (id, air time) as JSON in its request data.
 */
object Downloads {
    private const val CHANNEL = "downloads"
    private var manager: DownloadManager? = null
    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null

    @Synchronized
    private fun database(context: Context): StandaloneDatabaseProvider =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    @Synchronized
    fun cache(context: Context): SimpleCache =
        cache
            ?: SimpleCache(
                    File(context.applicationContext.filesDir, "downloads"),
                    NoOpCacheEvictor(),
                    database(context),
                )
                .also { cache = it }

    private fun http() =
        DefaultHttpDataSource.Factory()
            .setUserAgent("tagesschau-android/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)

    /** Plays from the download cache when an edition is there, else from the network. */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(http())
            .setCacheWriteDataSinkFactory(null)

    @Synchronized
    fun manager(context: Context): DownloadManager =
        manager
            ?: DownloadManager(
                    context.applicationContext,
                    database(context),
                    cache(context),
                    http(),
                    Executors.newFixedThreadPool(2),
                )
                .apply { maxParallelDownloads = 2 }
                .also { manager = it }

    fun start(context: Context, e: Edition) {
        val data = JSONObject().put("id", e.id).put("date", e.date.toString()).put("url", e.url)
        val request =
            DownloadRequest.Builder(e.id, e.url.toUri())
                .setData(data.toString().toByteArray())
                .build()
        DownloadService.sendAddDownload(
            context,
            TagesschauDownloadService::class.java,
            request,
            false,
        )
    }

    fun remove(context: Context, id: String) =
        DownloadService.sendRemoveDownload(
            context,
            TagesschauDownloadService::class.java,
            id,
            false,
        )

    fun edition(download: Download): Edition? =
        runCatching {
                val j = JSONObject(String(download.request.data))
                Edition(
                    j.getString("id"),
                    OffsetDateTime.parse(j.getString("date")),
                    j.getString("url"),
                )
            }
            .getOrNull()

    /** All downloads, by edition id. Reads the index, so it works from any thread. */
    fun all(context: Context): Map<String, Download> {
        val out = mutableMapOf<String, Download>()
        manager(context).downloadIndex.getDownloads().use { c ->
            while (c.moveToNext()) out[c.download.request.id] = c.download
        }
        return out
    }

    /** Completed downloads as editions, newest first, for offline use. */
    fun downloaded(context: Context): List<Edition> =
        all(context)
            .values
            .filter { it.state == Download.STATE_COMPLETED }
            .mapNotNull(::edition)
            .sortedByDescending { it.date }

    /**
     * Deletes downloads of editions older than the retention period. Talks to the download manager
     * directly (no service start, so it works in the background); call it on the main thread.
     */
    fun prune(context: Context) {
        val cutoff = OffsetDateTime.now().minusHours(Settings(context).retentionHours.toLong())
        val manager = manager(context)
        all(context).values.forEach { d ->
            val e = edition(d)
            if (e == null || e.date.isBefore(cutoff)) manager.removeDownload(d.request.id)
        }
    }

    fun scheduleRetention(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("retention", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun notification(
        context: Context,
        downloads: List<Download>,
        notMet: Int,
    ): Notification =
        DownloadNotificationHelper(context, CHANNEL)
            .buildProgressNotification(
                context,
                R.drawable.ic_tagesschau,
                null,
                null,
                downloads,
                notMet,
            )

    const val CHANNEL_ID = CHANNEL
}

class TagesschauDownloadService :
    DownloadService(
        1,
        DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
        Downloads.CHANNEL_ID,
        R.string.downloads_channel,
        0,
    ) {
    override fun getDownloadManager(): DownloadManager = Downloads.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: @Requirements.RequirementFlags Int,
    ): Notification = Downloads.notification(this, downloads, notMetRequirements)
}

/** Deletes old downloads every few hours, see [Downloads.prune]. */
class RetentionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) { Downloads.prune(applicationContext) }
        return Result.success()
    }
}
