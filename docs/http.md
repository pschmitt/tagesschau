# HTTP interface

| Path | Returns |
| --- | --- |
| `/` | The web player to browsers (`Accept: text/html`), the feed to everything else. |
| `/feed.xml`, `/feed` | RSS feed of the editions, newest first. |
| `/player` | The web player. |
| `/episodes.json` | Editions and the edition on air, for the player. |
| `/live` | URL of a time-shifted HLS stream (video and audio), or 404. |
| `/live/audio` | The same as an audio-only stream, for phones with the screen off. |
| `/download/<id>` | The edition's MP4 as a file download (`?q=webxxl\|webxl\|webm\|webs`). |
| `/sw.js` | The web player's service worker (notifications). |
| `/cover.jpg`, `/logo.jpg` | The configured artwork, or 404. |

## Live stream

Das Erste's live HLS keeps two hours, every segment dated with
`EXT-X-PROGRAM-DATE-TIME`. `/live` looks up the edition from the ARD programme
guide and returns a session URL. The session's playlists replay the live
stream from the edition's start, delayed by however late the session began,
so any player that starts at the live edge starts at the edition's first
second. The playlist ends a minute after the scheduled end.

`/live` answers 404 when no edition started within `liveMinutes`.

Playlists and JSON answers carry `Access-Control-Allow-Origin: *`, so Cast
receivers can load the live sessions.

## Web player

- Controls as on YouTube: timeline above previous, play/pause, next, volume
  (slider on hover, arrow keys) and the time; seeking, download, cast, share,
  speed and fullscreen on the right.
- Casting through Google Cast (Chrome): the cast button hands the edition and
  position to the TV.
- The bell notifies while the page is open when an edition goes on air or a
  new one is published.

## Player links

- `?at=<unix seconds>` opens the edition with that moment and starts there
  (the player's share dialog creates these).
- `?ton=1` (or `?sound=1`) never starts muted: when the browser blocks
  autoplay with sound, the player waits for a tap.
