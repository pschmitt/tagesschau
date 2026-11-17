# HTTP interface

| Path | Returns |
| --- | --- |
| `/` | The web player to browsers (`Accept: text/html`), the feed to everything else. |
| `/feed.xml`, `/feed` | RSS feed of the editions, newest first. |
| `/player` | The web player. |
| `/episodes.json` | Editions and the edition on air, for the player. |
| `/live` | Browsers: the player on Das Erste live. Others: the URL of Das Erste's live HLS stream. |
| `/live/edition` | URL of a time-shifted HLS stream (video and audio) of the edition on air or aired within `liveMinutes`, or 404. |
| `/live/audio` | The same as an audio-only stream, for phones with the screen off. |
| `/subtitles/<id>.vtt` | The edition's WebVTT subtitles from the ARD Mediathek, or 404. |
| `/thumbs/<id>/<n>.jpg` | Sheet `n` of the edition's scrubbing thumbnails, or 404. |
| `/latest`, `/latest.mp4` | Redirect to the newest edition's MP4 (`?q=webxxl\|webxl\|webm\|webs`), for OBS or scripts. |
| `/download/<id>` | The edition's MP4 as a file download (`?q=webxxl\|webxl\|webm\|webs`). |
| `/sw.js` | The web player's service worker (notifications). |
| `/cover.jpg`, `/logo.jpg` | The configured artwork, or 404. |

## Live stream

Das Erste's live HLS keeps two hours, every segment dated with
`EXT-X-PROGRAM-DATE-TIME`. `/live` looks up the edition from the ARD programme
guide and `/live/edition` returns a session URL. The session's playlists replay the live
stream from the edition's start, delayed by however late the session began,
so any player that starts at the live edge starts at the edition's first
second. The playlist ends a minute after the scheduled end.

`/live/edition` answers 404 when no edition started within `liveMinutes`.
After the edition's scheduled end the players label the session a replay.

## Subtitles

tagesschau.de's edition JSON has no subtitles; the ARD Mediathek item of the
same video does. Its id is the base64 of `crid://tagesschau.de/<externalId>`
plus a suffix, so the server finds it from the edition's `externalId`.
`episodes.json` lists `subtitles` for each edition that has them. The live
session playlists offer Das Erste's subtitle rendition, not selected by
default.

## Scrubbing thumbnails

Unless `thumbnails.enable` is off, the server runs ffmpeg once per edition on
its lowest-quality MP4, decoding key frames only (a couple of seconds), and
keeps the result in the service's cache directory until the edition leaves
the list. `episodes.json` lists `thumbs` for each edition whose sheets are
ready:

```json
{"interval": 4, "width": 240, "height": 135, "columns": 5, "rows": 5,
 "sheets": [".../thumbs/ts-12345/1.jpg", "..."]}
```

Frame `i` shows second `i * interval`; it is tile `i % (columns * rows)` of
sheet `i / (columns * rows)`, row by row. Players without `thumbs` (live
editions, sheets not made yet, the option off) seek a hidden low-quality
video instead.

Playlists and JSON answers carry `Access-Control-Allow-Origin: *`, so Cast
receivers can load the live sessions.

## Web player

`?embed=1` shows only the video, filling the window, with no controls; it
always starts from the beginning. With `?subs=1` it shows subtitles. Use it as
an OBS browser source, for example `/?embed=1&subs=1` (newest edition) or
`/live?embed=1&subs=1` (Das Erste live).

- Controls as on YouTube: timeline above play/pause, volume
  (slider on hover, arrow keys) and the time; seeking, download, cast, share,
  settings (subtitles, speed, clock format and font; C toggles subtitles) and
  fullscreen on the right. The big clock below is off or shows minutes, seconds
  (default) or milliseconds.
- A double tap or double-click on the left or right third of the picture
  jumps 20 s back or ahead; further quick taps add 20 s each.
- Live streams (Das Erste live, an edition on air) show a LIVE button: red
  at the live edge, grey when behind; a click returns to what airs now.
- Casting through Google Cast (Chrome): the cast button hands the edition and
  position to the TV.
- The bell notifies while the page is open when an edition goes on air or a
  new one is published.

## Player links

- `?at=<unix seconds>` opens the edition with that moment and starts there
  (the player's share dialog creates these).
- `?lang=de|en|fr` sets the language and remembers it. Without it the page
  uses the remembered choice, else the first of the browser's preferred
  languages it has, else English. The footer links to each language.
- `?untertitel=1` (or `?subs=1`) shows subtitles for this visit, `=0` hides
  them. Without it the CC button's last state applies (off at first).
- `?ton=1` (or `?sound=1`) never starts muted: when the browser blocks
  autoplay with sound, the player waits for a tap.
