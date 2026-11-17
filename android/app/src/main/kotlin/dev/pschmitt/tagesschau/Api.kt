package dev.pschmitt.tagesschau

import android.content.Context
import android.text.format.DateFormat
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One tagesschau edition: published (an MP4) or on air (a time-shifted HLS stream). */
data class Edition(
    val id: String,
    val date: OffsetDateTime,
    val url: String,
    val live: Boolean = false,
    val end: OffsetDateTime? = null,
    /** The Das Erste live channel itself (real time), not an edition. */
    val channel: Boolean = false,
    /** WebVTT URL when the edition has subtitles (live streams carry their own). */
    val subtitles: String? = null,
) {
    val time: String
        get() = date.atZoneSameInstant(BERLIN).format(TIME)

    /** The day in the app's current locale, e.g. "Montag, 16. November". */
    val day: String
        get() = formatDay(date.atZoneSameInstant(BERLIN))

    /** Localized title, e.g. "tagesschau 20:00 Uhr" (German), "tagesschau 20h00" (French). */
    fun title(context: Context): String {
        if (channel) return context.getString(R.string.channel_title)
        val at = date.atZoneSameInstant(BERLIN)
        return context.getString(
            R.string.edition_title,
            "%02d".format(at.hour),
            "%02d".format(at.minute),
        )
    }

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        const val CHANNEL_ID = "daserste-live"
        private const val CHANNEL_URL =
            "https://daserste-live.ard-mcdn.de/daserste/live/hls/de/master.m3u8"

        /** Das Erste's live stream, as it airs. */
        fun channel() =
            Edition(CHANNEL_ID, OffsetDateTime.now(), CHANNEL_URL, live = true, channel = true)

        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        /** Weekday, day and month in the default locale's order and language (no year). */
        fun formatDay(at: ZonedDateTime): String {
            val locale = Locale.getDefault()
            val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
            return DateTimeFormatter.ofPattern(pattern, locale).format(at)
        }
    }
}

/** Editions as the server lists them: the edition on air (if any) first, then newest first. */
/** One programme on Das Erste (from the ARD programme guide, via the server). */
data class Programme(
    val title: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val image: String?,
)

data class Listing(
    val live: Edition?,
    val editions: List<Edition>,
    /** What Das Erste shows now and next, if the server knows. */
    val onAir: List<Programme> = emptyList(),
) {
    /** The programme running at [at] (now by default). */
    fun programmeAt(at: OffsetDateTime = OffsetDateTime.now()): Programme? = onAir.firstOrNull {
        !it.start.isAfter(at) && it.end.isAfter(at)
    }

    val all: List<Edition>
        get() = listOfNotNull(live) + editions
}

class Api(private val baseUrl: String) {
    suspend fun listing(): Listing =
        withContext(Dispatchers.IO) {
            val json = JSONObject(get("$baseUrl/episodes.json"))
            val list = json.getJSONArray("editions")
            val editions =
                (0 until list.length()).map { i ->
                    val e = list.getJSONObject(i)
                    Edition(
                        id = e.getString("id"),
                        date = OffsetDateTime.parse(e.getString("date")),
                        url = e.getString("url"),
                        subtitles = e.optString("subtitles").takeIf { it.isNotEmpty() },
                    )
                }
            val live =
                json.optJSONObject("live")?.let { l ->
                    Edition(
                        id = "live-" + l.getString("date"),
                        date = OffsetDateTime.parse(l.getString("date")),
                        url = l.getString("url"),
                        live = true,
                        end =
                            l.optString("end")
                                .takeIf { it.isNotEmpty() }
                                ?.let(OffsetDateTime::parse),
                    )
                }
            val onAir =
                json.optJSONObject("channel")?.let { c ->
                    listOf("now", "next").mapNotNull { k ->
                        c.optJSONObject(k)?.let { p ->
                            runCatching {
                                    Programme(
                                        title = p.getString("title"),
                                        start = OffsetDateTime.parse(p.getString("start")),
                                        end = OffsetDateTime.parse(p.getString("end")),
                                        image = p.optString("image").takeIf { it.isNotEmpty() },
                                    )
                                }
                                .getOrNull()
                        }
                    }
                } ?: emptyList()
            Listing(live, editions, onAir)
        }

    /** URL of the audio-only time-shifted stream of the edition on air, or null. */
    suspend fun liveAudio(): String? =
        withContext(Dispatchers.IO) {
            runCatching { get("$baseUrl/live/audio").trim().takeIf { it.startsWith("http") } }
                .getOrNull()
        }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "tagesschau-android/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode != 200) error("$url: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
