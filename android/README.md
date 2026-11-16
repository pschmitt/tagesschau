# tagesschau for Android

A native player for the [tagesschau server](../README.md): the editions of the last two days, the
edition on air from its start, and downloads for offline listening.

## Features

- **Player**: Media3 with background playback, lock-screen and headset controls,
  picture-in-picture when leaving the player (or audio only, or stop: a setting), and double taps
  on the picture's sides to jump 20 s.
- **Live from the start**: the edition on air plays from its first second, through the server's
  time-shifted stream.
- **Downloads**: per edition, played from the cache when offline; editions older than the
  retention period (48 hours by default) are deleted.
- **Tasker**: a plugin action "tagesschau abspielen", and `PlayLatestActivity` for Send Intent
  (action `dev.pschmitt.tagesschau.action.PLAY_LATEST`, target Activity).
- **Self-update**: from the rolling `latest` release; silent with
  [Shizuku](https://shizuku.rikka.app/), otherwise after one confirmation.

## Install

Download `tagesschau-android-latest-app-release.apk` from the
[`latest` release](https://github.com/pschmitt/tagesschau/releases/tag/latest), or add the
repository to Obtainium. The app then updates itself.

## Build

```sh
cd android
just build            # debug APK, built on the remote build host
just build release    # release APK, signed with the key from rbw
```

## License

[GPL-3.0-or-later](../LICENSE).
