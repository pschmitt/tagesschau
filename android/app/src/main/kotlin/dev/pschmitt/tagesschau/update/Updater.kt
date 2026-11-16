package dev.pschmitt.tagesschau.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pschmitt.tagesschau.BuildConfig
import dev.pschmitt.tagesschau.MainActivity
import dev.pschmitt.tagesschau.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer build of the rolling `latest` prerelease. */
data class Update(val commit: String, val apkUrl: String, val sha256: String?)

/**
 * Self-update from the rolling `latest` prerelease of github.com/pschmitt/tagesschau (published by
 * the release workflow on every Android change on main). Same approach as stricknani: the commit
 * built into the APK is compared with the release's commit through GitHub's compare API; the APK
 * must match SHA256SUMS, this package and this app's signing key, and installs through Shizuku
 * (silent) or the system installer (one confirmation).
 */
object Updater {
    private const val TAG = "Updater"
    private const val API = "https://api.github.com/repos/pschmitt/tagesschau"
    private const val TRUSTED_PATH = "/pschmitt/tagesschau/releases/download/"
    const val CHANNEL = "updates"

    private val variant = if (BuildConfig.DEBUG) "debug" else "release"
    val installedCommit: String = BuildConfig.BUILD_COMMIT

    /** The newer `latest` build, or null when this one is current (or unknown to GitHub). */
    suspend fun check(): Update? =
        withContext(Dispatchers.IO) {
            val release = JSONObject(get("$API/releases/tags/latest"))
            val tagSha = JSONObject(get("$API/commits/latest")).getString("sha")
            if (
                installedCommit.length < 7 ||
                    tagSha.startsWith(installedCommit) ||
                    installedCommit.startsWith(tagSha)
            ) {
                return@withContext null
            }
            val status =
                runCatching {
                        JSONObject(get("$API/compare/$installedCommit...$tagSha"))
                            .getString("status")
                    }
                    .getOrNull()
            if (status != "ahead") return@withContext null
            val assets = release.getJSONArray("assets")
            var apk: String? = null
            var sums: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                val url = a.getString("browser_download_url")
                if (name.endsWith("-app-$variant.apk")) apk = url
                if (name == "SHA256SUMS") sums = url
            }
            val apkUrl = apk ?: return@withContext null
            require(trusted(apkUrl)) { "untrusted asset URL" }
            val sha = sums?.let { u ->
                get(u)
                    .lineSequence()
                    .mapNotNull { SHA_LINE.matchEntire(it.trim()) }
                    .firstOrNull { apkUrl.endsWith("/" + it.groupValues[2]) }
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            }
            Update(tagSha, apkUrl, sha)
        }

    /** Downloads, verifies and installs [update]. Returns false when the APK was refused. */
    suspend fun install(context: Context, update: Update): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "update.apk")
            download(update.apkUrl, file)
            val sha = sha256(file)
            if (update.sha256 == null || sha != update.sha256) {
                Log.w(TAG, "checksum mismatch or missing: $sha vs ${update.sha256}")
                return@withContext false
            }
            if (!sameAppAndKey(context, file)) {
                Log.w(TAG, "APK is for another package or signed with another key")
                return@withContext false
            }
            val sender = statusSender(context)
            val shizuku = ShizukuInstaller.get(context)
            shizuku.refresh()
            if (shizuku.state.value == ShizukuState.READY) {
                try {
                    shizuku.install(file, sender)
                    return@withContext true
                } catch (e: Throwable) {
                    Log.w(TAG, "Shizuku install failed, falling back to the system installer", e)
                }
            }
            installWithPackageInstaller(context, file, sender)
            true
        }

    private fun installWithPackageInstaller(context: Context, file: File, sender: IntentSender) {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            session.commit(sender)
        }
    }

    private fun statusSender(context: Context): IntentSender =
        PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            .intentSender

    private fun sameAppAndKey(context: Context, file: File): Boolean {
        val pm = context.packageManager
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = signers(archive)
        return archive.packageName == context.packageName &&
            a.isNotEmpty() &&
            a == signers(installed)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners
            else info.signatures
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun trusted(url: String): Boolean {
        val u = URL(url)
        return u.protocol == "https" && u.host == "github.com" && u.path.startsWith(TRUSTED_PATH)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "tagesschau-android")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("$url: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Follows redirects by hand, so every hop stays on HTTPS and on GitHub's hosts. */
    private fun download(url: String, dest: File) {
        var current = URL(url)
        repeat(5) {
            require(
                current.protocol == "https" &&
                    (current.host == "github.com" ||
                        current.host.endsWith(".githubusercontent.com"))
            ) {
                "untrusted download host ${current.host}"
            }
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "tagesschau-android")
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        current = URL(current, conn.getHeaderField("Location"))
                        return@repeat
                    }
                    200 -> {
                        conn.inputStream.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        return
                    }
                    else -> error("download: HTTP ${conn.responseCode}")
                }
            } finally {
                conn.disconnect()
            }
        }
        error("download: too many redirects")
    }

    private fun sha256(file: File): String =
        file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val SHA_LINE = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")

    fun schedule(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
                )
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("self-update", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun createChannel(context: Context) {
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.updates_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
    }

    fun notify(context: Context, title: String, text: String, intent: Intent? = null) {
        val open =
            PendingIntent.getActivity(
                context,
                1,
                intent ?: Intent(context, MainActivity::class.java),
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
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(42, n) }
    }
}

/**
 * Checks twice a day. With Shizuku ready the update installs silently; otherwise a notification
 * offers it (tapping it opens the app, which installs after one system confirmation).
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val update =
            runCatching { Updater.check() }
                .getOrElse {
                    return Result.retry()
                } ?: return Result.success()
        val shizuku = ShizukuInstaller.get(applicationContext)
        shizuku.refresh()
        if (shizuku.state.value == ShizukuState.READY) {
            runCatching { Updater.install(applicationContext, update) }
        } else {
            Updater.notify(
                applicationContext,
                applicationContext.getString(R.string.update_available),
                applicationContext.getString(R.string.update_available_text, update.commit.take(7)),
                Intent(applicationContext, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, true),
            )
        }
        return Result.success()
    }
}

/** Result of an install session: confirmation needed (system installer) or failure. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    runCatching { context.startActivity(it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.notify(context, context.getString(R.string.update_failed), message)
            }
        }
    }
}

/** Tells the user the new version runs (the app is killed while it is replaced). */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Updater.notify(
                context,
                context.getString(R.string.update_installed),
                context.getString(R.string.update_installed_text, BuildConfig.BUILD_COMMIT.take(7)),
            )
        }
    }
}
