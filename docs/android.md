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

Leaving the app while a video plays shows it picture-in-picture by default;
Settings, Beim Verlassen switches to audio only or to stopping. Playback runs
in a media session, so it continues in the background with lock-screen and
headset controls.

"Diese Stelle teilen" makes the web player's `?at=` link to the exact second,
with a preview and 1 s / 5 s nudges.

## Downloads

Every edition within the retention period downloads by itself, by default on
Wi-Fi only and in 720p: when the app opens and hourly in the background.
Downloaded editions play without network and are deleted once they are older
than the retention period (48 hours by default). Single editions can be
downloaded or deleted from the list or the gear sheet.

## Settings

| Setting | Default | |
| --- | --- | --- |
| Laufende Ausgabe ab Beginn | on | Play the edition on air from its start, else the newest finished one (Tasker and app start) |
| Danach die nächste Ausgabe abspielen | off | Continue with the next edition, or stop after one |
| Beim Verlassen des Players | Bild-im-Bild | Picture-in-picture, audio only, or stop |
| Automatisch herunterladen | Nur im WLAN | Off, Wi-Fi only, or always |
| Download-Qualität | 720p | 1080p, 720p or 540p |
| Downloads behalten | 2 Tage | 1 day to 1 week |
| Server | `https://tagesschau.brkn.lol` | The tagesschau server to use |

## Tasker

Two ways to play "the latest tagesschau":

- The plugin action **tagesschau abspielen** (Plugin, tagesschau). Options:
  play the edition on air from its start, and audio only for it.
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
