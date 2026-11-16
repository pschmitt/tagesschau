package dev.pschmitt.tagesschau

import android.content.Context
import androidx.core.content.edit

/** Leaving the player: picture-in-picture (default), keep the audio playing, or stop. */
enum class LeaveMode {
    PIP,
    AUDIO,
    STOP,
}

/** User settings, kept in SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

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

    companion object {
        const val DEFAULT_SERVER = "https://tagesschau.brkn.lol"
        const val DEFAULT_RETENTION_HOURS = 48
        private const val KEY_SERVER = "server_url"
        private const val KEY_RETENTION = "retention_hours"
        private const val KEY_PREFER_LIVE = "prefer_live"
        private const val KEY_LEAVE = "leave_mode"
    }
}
