package dev.pschmitt.tagesschau

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/** MediaItems for editions, with the metadata the notification and lock screen show. */
object Playlist {
    const val EXTRA_DATE = "date"
    const val EXTRA_END = "end"
    const val EXTRA_URL = "url"

    fun item(context: Context, e: Edition, streamUrl: String = e.url): MediaItem {
        // A downloaded edition plays its downloaded file (possibly another quality).
        val url =
            if (e.live) streamUrl
            else
                Downloads.completedUri(context, e.id)
                    ?: streamUrl.replace(
                        Regex("""\.web[a-z]+\.h264\.mp4$"""),
                        ".${Settings(context).playbackQuality}.h264.mp4",
                    )
        val server = Settings(context).serverUrl
        val metadata =
            MediaMetadata.Builder()
                .setTitle(e.title)
                .setArtist(
                    when {
                        e.channel -> "Fernsehprogramm in Echtzeit"
                        e.live -> "${e.day}, läuft gerade"
                        else -> e.day
                    }
                )
                .setAlbumTitle("tagesschau")
                .setArtworkUri("$server/cover.jpg".toUri())
                .setExtras(
                    Bundle().apply {
                        if (!e.channel) putString(EXTRA_DATE, e.date.toString())
                        e.end?.let { putString(EXTRA_END, it.toString()) }
                        putString(EXTRA_URL, url)
                    }
                )
                .build()
        return MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(url)
            .setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else null)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * What "play the latest tagesschau" means: the edition on air from its start when there is one
     * (audio only if [audioOnlyLive]), otherwise the newest published edition; older ones follow.
     * Falls back to downloaded editions when the server cannot be reached.
     */
    suspend fun latest(
        context: Context,
        preferLive: Boolean,
        audioOnlyLive: Boolean,
    ): List<MediaItem> {
        val api = Api(Settings(context).serverUrl)
        val listing = runCatching { api.listing() }.getOrNull()
        if (listing == null) {
            return Downloads.downloaded(context).map { item(context, it) }
        }
        val items = mutableListOf<MediaItem>()
        val live = listing.live
        if (preferLive && live != null) {
            val url = if (audioOnlyLive) api.liveAudio() ?: live.url else live.url
            items += item(context, live, url)
        }
        items += listing.editions.map { item(context, it) }
        return items
    }
}
