# tagesschau Android app

See `.just/android-app-ci/AGENTS-shared.md` for the fleet-wide conventions (task tracking, dev
environment, CI as the lint authority, physical test devices) and read it alongside this file.
The repository root's `AGENTS.md` covers the server; this directory is the Android app only.

## Project shape

A Kotlin/Jetpack Compose and Media3 app for the tagesschau server in this repository
(`../src/server.py`). Package `dev.pschmitt.tagesschau`, debug application id
`dev.pschmitt.tagesschau.debug`, GPL-3.0.

- `Api.kt` reads the server's `/episodes.json` and `/live/audio`.
- `PlaybackService.kt` owns the ExoPlayer in a `MediaSessionService`; the UI and Tasker talk to it.
- `Downloads.kt`: Media3 downloads into one cache that playback reads as well; `RetentionWorker`
  deletes editions older than the retention setting.
- `PlayerActivity.kt`: full-screen player, picture-in-picture, double-tap seeking.
- `PlayLatestActivity.kt` and `TaskerPlugin.kt`: "play the latest tagesschau" for Tasker.
- `update/`: self-update from the rolling `latest` prerelease, silent through Shizuku (ported
  from stricknani).

## Build and validation

- This project's `TODO.md` prefix is `TSA-N`. Read it before starting and keep it current.
- Never run Gradle on this workstation. Use the `just` recipes (they sync to the build host) or
  an ad-hoc `rworker`; pass `-Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/37.0.0/aapt2"`
  on a worker.
- Release and CI debug builds are signed with the key in the rbw entry named in the `justfile`
  (also the `CI_KEYSTORE_*` repository secrets). Self-updates need that one key: never replace it.
- The rolling `latest` release is rebuilt on every push to `main` that touches `android/`.

## Conventions

- User-facing text is German.
- The repository ships no ARD logos; the launcher icon is the app's own.
- No private hostnames, device names or addresses in code or docs beyond what the shared recipes
  already need; use placeholders.
- Strip trailing whitespace; LF line endings.
