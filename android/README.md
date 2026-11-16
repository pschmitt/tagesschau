# tagesschau for Android

A native player for the [tagesschau server](../README.md): the editions of the last two days, the
edition on air from its start, and downloads for offline listening.

See [docs/android.md](../docs/android.md) for the player, downloads, settings,
Tasker and self-updates.

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
