# Android app

A native player for this server: the editions of the last days, the edition
on air from its first second, Das Erste live, downloads for offline viewing,
a Tasker action and self-updates. Package `dev.pschmitt.tagesschau`; the
sources are in [`android/`](../android/).

## Install

Install `tagesschau-android-latest-app-release.apk` from the
[`latest` release](https://github.com/pschmitt/tagesschau/releases/tag/latest)
(the web player's "Als Podcast abonnieren" dialog links it too), or add
`https://github.com/pschmitt/tagesschau` to Obtainium with prereleases
allowed. After that the app updates itself (see [Self-update](#self-update)).

The app talks to a tagesschau server; it uses `https://tagesschau.brkn.lol` until
another one is set under Settings, Server.

## Player

The screen follows the web player: the picture on top, the playhead as a
broadcast-time clock below it, then Das Erste live and the editions by day.
The edition playing is marked in orange; a thin line under each edition shows
how much of it was seen, with "weiter ab" or "gesehen".

The controls work like YouTube's mobile player:

| Gesture | Effect |
| --- | --- |
| Tap the picture | Show or hide the controls (they hide 3 s into playback) |
| Double tap left or right third | Jump 20 s back or ahead; every further tap adds 20 s |
| Swipe up / down on the picture | Fullscreen on / off (turning the phone works too) |
| Drag the timeline | Seek, with a thumbnail and the broadcast time |
| Gear | Speed, stream quality, continue with the next edition, share, download |
| Cast button | Play on a Chromecast or Google TV |
| CC button | Subtitles on / off (shown when the edition or stream has them) |

Leaving the app while a video plays shows it picture-in-picture by default;
Settings, Beim Verlassen switches to audio only or to stopping. Playback runs
in a media session, so it continues in the background with lock-screen and
headset controls.

"Diese Stelle teilen" makes the web player's `?at=` link to the exact second,
with a preview and 1 s / 5 s nudges.

## Casting

The cast button on the picture (next to the gear) sends playback to a
Chromecast or Google TV through Google Cast's Default Media Receiver; the
controls then drive the TV. Downloaded editions go to the TV as their web URL.
Casting needs Google Play Services on the phone.

## Notifications

Settings, Benachrichtigungen, Neue Ausgaben melden posts to the channel "Neue
Ausgaben" when an edition goes on air and when a new one is published (checked
every 15 minutes). Tapping a notification plays that edition.

## Bluetooth

Settings, Bluetooth, Bei Verbindung abspielen picks paired devices (car,
headphones, speakers). When one of them connects, "Was abspielen" decides what
starts: the latest edition (the one on air from its start if that setting is
on, else the newest one) or Das Erste live at its live edge.
The mode decides how: "Nur Ton" (audio in the background), "Video" (the app
opens and plays) or "Bild-im-Bild" (straight into picture-in-picture). A
notification says that playback started. With a delay (0 s by default, up to
30 s) the notification counts down first and offers "Abbrechen" and "Jetzt
abspielen".

"Lautstärke beim Start festlegen" (off by default) sets the media volume to a
chosen level when Bluetooth auto-play starts, and again a moment later, after
Android has restored the device's own volume.

"Bei Trennung weiterspielen" (on by default) keeps playback going on the
phone's speaker when a Bluetooth device or headphones disconnect; off pauses it. Like the Tasker action, this needs "Über anderen Apps einblenden".

## Downloads

Every edition within the retention period downloads by itself, by default on
Wi-Fi only and in 720p: when the app opens and hourly in the background.
Downloaded editions play without network and are deleted once they are older
than the retention period (48 hours by default). Single editions can be
downloaded or deleted from the list or the gear sheet. Subtitles are saved
with each download, so they work offline too.

## Settings

| Setting | Default | |
| --- | --- | --- |
| Sprache (Erweitert) | System | German, English or French; the system language by default (English when it is none of them). On Android 13+ also in the system's per-app language settings |
| Beim Öffnen | Neueste tagesschau | What the app shows when it starts: the latest tagesschau or Das Erste live (at its live edge) |
| Beim Öffnen abspielen | Nur Bild (stumm) | Start the newest edition when the app opens: picture and sound, picture only (with a "Ton einschalten" button), sound only, or nothing |
| Laufende Ausgabe ab Beginn | on | Play the edition on air from its start, else the newest finished one (Tasker and app start) |
| Uhrzeit unter dem Player | 20:00:06 | The big clock: off, minutes, seconds or milliseconds |
| Untertitel | off | Subtitles by default, where an edition has them; the CC button in the player switches them for the session |
| Danach die nächste Ausgabe abspielen | off | Continue with the next edition, or stop after one |
| Beim Verlassen des Players | Bild-im-Bild | Picture-in-picture, audio only, or stop |
| Automatisch herunterladen | Nur im WLAN | Off, Wi-Fi only, or always |
| Nur beim Laden | off | Start automatic downloads only while the phone charges |
| Download-Qualität | 720p | 1080p, 720p or 540p |
| Downloads behalten | 2 Tage | 1 day to 1 week |
| Server | `https://tagesschau.brkn.lol` | The tagesschau server to use |

## Tasker

Two ways to play "the latest tagesschau":

- The plugin action **tagesschau abspielen** (Plugin, tagesschau). Each action
  has its own options, the same as Bluetooth auto-play: what to play (latest
  tagesschau or Das Erste live), how (sound only, video, picture-in-picture),
  the edition on air from its start, resume, and a start volume.
- **Send Intent** with action `dev.pschmitt.tagesschau.action.PLAY_LATEST`,
  target Activity. Optional boolean extras `prefer_live` and `audio_only`.

Both start an invisible activity that starts playback and closes. To do that
while another app is in front or the screen is off, Android needs the app's
"Über anderen Apps einblenden" permission (Settings, Tasker).

## Self-update

Every change to `android/` on `main` rebuilds the rolling `latest` prerelease.
The app checks it twice a day (and on Settings, Nach Updates suchen): when the
release's commit is ahead of the installed one, it downloads the APK and checks
it against `SHA256SUMS`, the package name and the signing key.

- With [Shizuku](https://shizuku.rikka.app/) running and access granted
  (Settings, Updates, Shizuku), updates install silently.
- Without it, a notification offers the update, and Android asks once to
  confirm the install.

## Building

Builds run in the Android flake's dev shell on a remote host, never on the
workstation:

```sh
cd android
just build            # debug APK
just build release    # release APK, signed with the key from rbw
just check            # unit tests and Android Lint
```

Release builds (here and in CI) are signed with one persistent key: in rbw as
"tagesschau Android CI Signing Keystore" and in the repository secrets
`CI_KEYSTORE_*`. Self-updates only work between builds signed with it.
