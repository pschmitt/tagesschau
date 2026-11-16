package dev.pschmitt.tagesschau

import android.content.Context
import androidx.core.content.edit

/** Leaving the player: picture-in-picture (default), keep the audio playing, or stop. */
enum class LeaveMode {
    PIP,
    AUDIO,
    STOP,
}

/** Automatic downloads of new editions: off, on Wi-Fi (unmetered networks) or always. */
enum class AutoDownload {
    OFF,
    WIFI,
    ALWAYS,
}

/** What starts when the app opens and nothing plays yet. */
enum class AutoPlay {
    VIDEO_SOUND,
    VIDEO_ONLY,
    SOUND_ONLY,
    NONE,
}

/** User settings, kept in SharedPreferences. */
class Settings(context: Context) {
    val prefs: android.content.SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Base URL of the tagesschau server (github.com/pschmitt/tagesschau). */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.trimEnd('/') ?: DEFAULT_SERVER
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    /** Downloads of editions older than this are deleted. */
    var retentionHours: Int
        get() = prefs.getInt(KEY_RETENTION, DEFAULT_RETENTION_HOURS)
        set(value) = prefs.edit { putInt(KEY_RETENTION, value.coerceIn(1, 24 * 14)) }

    /** Play the edition on air from its start, when there is one. */
    var preferLive: Boolean
        get() = prefs.getBoolean(KEY_PREFER_LIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_PREFER_LIVE, value) }

    /** What happens to video playback when the player is left (home button, other app). */
    var leaveMode: LeaveMode
        get() =
            runCatching { LeaveMode.valueOf(prefs.getString(KEY_LEAVE, null) ?: "") }
                .getOrDefault(LeaveMode.PIP)
        set(value) = prefs.edit { putString(KEY_LEAVE, value.name) }

    /** Continue with the next (older) edition when one ends. */
    var autoNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_NEXT, value) }

    /** ARD's file variant for streaming published editions (webxxl is 1080p). */
    var playbackQuality: String
        get() = prefs.getString(KEY_PLAY_QUALITY, null) ?: "webxxl"
        set(value) = prefs.edit { putString(KEY_PLAY_QUALITY, value) }

    var autoPlay: AutoPlay
        get() =
            runCatching { AutoPlay.valueOf(prefs.getString(KEY_AUTOPLAY, null) ?: "") }
                .getOrDefault(AutoPlay.VIDEO_ONLY)
        set(value) = prefs.edit { putString(KEY_AUTOPLAY, value.name) }

    /** Notify when an edition goes on air or a new one is published. */
    var notifyNew: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY, value) }

    /** Addresses of paired Bluetooth devices whose connection starts playback. */
    var bluetoothDevices: Set<String>
        get() = prefs.getStringSet(KEY_BT, emptySet()).orEmpty()
        set(value) = prefs.edit { putStringSet(KEY_BT, value) }

    var autoDownload: AutoDownload
        get() =
            runCatching { AutoDownload.valueOf(prefs.getString(KEY_AUTO, null) ?: "") }
                .getOrDefault(AutoDownload.WIFI)
        set(value) = prefs.edit { putString(KEY_AUTO, value.name) }

    /** ARD's file variant for downloads: webxxl (1080p), webxl (720p) or webm (540p). */
    var downloadQuality: String
        get() = prefs.getString(KEY_QUALITY, null) ?: "webxl"
        set(value) = prefs.edit { putString(KEY_QUALITY, value) }

    companion object {
        const val DEFAULT_SERVER = "https://tagesschau.brkn.lol"
        const val DEFAULT_RETENTION_HOURS = 48
        private const val KEY_SERVER = "server_url"
        private const val KEY_RETENTION = "retention_hours"
        private const val KEY_PREFER_LIVE = "prefer_live"
        private const val KEY_LEAVE = "leave_mode"
        private const val KEY_AUTO = "auto_download"
        private const val KEY_NOTIFY = "notify_new"
        private const val KEY_BT = "bluetooth_devices"
        private const val KEY_AUTOPLAY = "auto_play"
        const val KEY_AUTO_NEXT = "auto_next"
        private const val KEY_QUALITY = "download_quality"
        private const val KEY_PLAY_QUALITY = "playback_quality"
    }
}
