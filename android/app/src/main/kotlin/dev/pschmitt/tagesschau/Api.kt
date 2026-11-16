package dev.pschmitt.tagesschau

import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
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
) {
    val time: String
        get() = date.atZoneSameInstant(BERLIN).format(TIME)

    val day: String
        get() = date.atZoneSameInstant(BERLIN).format(DAY)

    val title: String
        get() = "tagesschau $time Uhr"

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
        private val DAY = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)
    }
}

/** Editions as the server lists them: the edition on air (if any) first, then newest first. */
data class Listing(val live: Edition?, val editions: List<Edition>) {
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
            Listing(live, editions)
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
