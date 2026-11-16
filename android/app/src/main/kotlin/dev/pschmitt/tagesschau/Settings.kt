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

/**
 * How a Bluetooth connection plays: sound only, video in the app, or video in picture-in-picture.
 */
enum class BluetoothMode {
    AUDIO,
    VIDEO,
    PIP,
}

/** The big clock under the player: off, 20:00, 20:00:06 or 20:00:06,123. */
enum class ClockFormat {
    OFF,
    MINUTES,
    SECONDS,
    MILLIS,
}

/** What starts on its own (app start, Bluetooth): the latest tagesschau or Das Erste live. */
enum class PlayTarget {
    TAGESSCHAU,
    CHANNEL,
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
    /** Subtitles on by default (the player's CC button switches them for the session). */
    var clockFormat: ClockFormat
        get() =
            runCatching { ClockFormat.valueOf(prefs.getString(KEY_CLOCK, null) ?: "") }
                .getOrDefault(ClockFormat.SECONDS)
        set(value) = prefs.edit { putString(KEY_CLOCK, value.name) }

    var subtitles: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES, false)
        set(value) = prefs.edit { putBoolean(KEY_SUBTITLES, value) }

    var autoNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NEXT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_NEXT, value) }

    /** ARD's file variant for streaming published editions (webxxl is 1080p). */
    var playbackQuality: String
        get() = prefs.getString(KEY_PLAY_QUALITY, null) ?: "webxxl"
        set(value) = prefs.edit { putString(KEY_PLAY_QUALITY, value) }

    var playTarget: PlayTarget
        get() =
            runCatching { PlayTarget.valueOf(prefs.getString(KEY_PLAY_TARGET, null) ?: "") }
                .getOrDefault(PlayTarget.TAGESSCHAU)
        set(value) = prefs.edit { putString(KEY_PLAY_TARGET, value.name) }

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

    var bluetoothMode: BluetoothMode
        get() =
            runCatching { BluetoothMode.valueOf(prefs.getString(KEY_BT_MODE, null) ?: "") }
                .getOrDefault(BluetoothMode.AUDIO)
        set(value) = prefs.edit { putString(KEY_BT_MODE, value.name) }

    /** Media volume (percent) for Bluetooth auto-play, or -1 to leave the volume alone. */
    var bluetoothVolume: Int
        get() = prefs.getInt(KEY_BT_VOLUME, -1)
        set(value) = prefs.edit { putInt(KEY_BT_VOLUME, value.coerceIn(-1, 100)) }

    /** The slider's value while the fixed volume is off. */
    var bluetoothVolumeLevel: Int
        get() = prefs.getInt(KEY_BT_VOLUME_LEVEL, 50)
        set(value) = prefs.edit { putInt(KEY_BT_VOLUME_LEVEL, value.coerceIn(0, 100)) }

    /** Keep playing (on the phone's speaker) when a Bluetooth device or headphones disconnect. */
    var continueOnDisconnect: Boolean
        get() = prefs.getBoolean(KEY_CONTINUE_ON_DISCONNECT, true)
        set(value) = prefs.edit { putBoolean(KEY_CONTINUE_ON_DISCONNECT, value) }

    /** Bluetooth auto-play continues the latest edition where it was left, if started before. */
    /** What a Bluetooth connection starts. */
    var bluetoothTarget: PlayTarget
        get() =
            runCatching { PlayTarget.valueOf(prefs.getString(KEY_BT_TARGET, null) ?: "") }
                .getOrDefault(PlayTarget.TAGESSCHAU)
        set(value) = prefs.edit { putString(KEY_BT_TARGET, value.name) }

    var bluetoothResume: Boolean
        get() = prefs.getBoolean(KEY_BT_RESUME, true)
        set(value) = prefs.edit { putBoolean(KEY_BT_RESUME, value) }

    /** Seconds between a Bluetooth connection and playback (cancellable from a notification). */
    var bluetoothDelaySeconds: Int
        get() = prefs.getInt(KEY_BT_DELAY, 0)
        set(value) = prefs.edit { putInt(KEY_BT_DELAY, value.coerceIn(0, 300)) }

    var autoDownload: AutoDownload
        get() =
            runCatching { AutoDownload.valueOf(prefs.getString(KEY_AUTO, null) ?: "") }
                .getOrDefault(AutoDownload.WIFI)
        set(value) = prefs.edit { putString(KEY_AUTO, value.name) }

    /** Automatic downloads only while the phone is charging. */
    var autoDownloadCharging: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHARGING, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHARGING, value) }

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
        private const val KEY_BT_MODE = "bluetooth_mode"
        private const val KEY_BT_DELAY = "bluetooth_delay"
        private const val KEY_BT_RESUME = "bluetooth_resume"
        const val KEY_CONTINUE_ON_DISCONNECT = "continue_on_disconnect"
        private const val KEY_BT_VOLUME = "bluetooth_volume"
        private const val KEY_BT_VOLUME_LEVEL = "bluetooth_volume_level"
        private const val KEY_AUTOPLAY = "auto_play"
        const val KEY_AUTO_NEXT = "auto_next"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_CLOCK = "clock_format"
        private const val KEY_PLAY_TARGET = "play_target"
        private const val KEY_BT_TARGET = "bluetooth_play_target"
        private const val KEY_AUTO_CHARGING = "auto_download_charging"
        private const val KEY_QUALITY = "download_quality"
        private const val KEY_PLAY_QUALITY = "playback_quality"
    }
}
