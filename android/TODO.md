# TODO

## TSA-1: First version of the Android app

- [x] List of editions (server `/episodes.json`), grouped by day, edition on air first
- [x] Media3 playback service with media session (background, lock screen, headset)
- [x] Player with picture-in-picture, leave setting (PiP / audio only / stop), double-tap seek
- [x] Downloads with offline playback and a retention period (default 48 h)
- [x] Tasker plugin action and `PlayLatestActivity` for Send Intent
- [x] Self-update from the `latest` release, silent through Shizuku
- [ ] Verified on a device: playback, PiP, downloads offline, Tasker with the screen off,
      self-update through Shizuku
- [ ] Automatic download of new editions on Wi-Fi (optional)
- [ ] Resume position per edition (as in the web player)

Status: in progress (2026-11-16)

## TSA-2: Bluetooth auto-play with the screen off

- [x] Screen off or locked: start playback from `PlaybackService` as a foreground service
      (Bluetooth broadcast, exact alarm and notification action allow it), the activity only as
      a fallback
- [x] Video modes: play the video stream without a picture, open the player on unlock
      (`ACTION_USER_PRESENT`) without a track change, so the sound does not stop
- [x] `WAKE_MODE_NETWORK` so streaming goes on with the screen off
- [ ] Verified on a device: car connect with the screen off, sound, then unlock to fullscreen

Status: in progress (2026-11-20)

## TSA-3: Media notification and audio routing for Bluetooth auto-play

- [x] `addSession` in `PlaybackService`: playback started from an intent (Bluetooth, Tasker)
      had no connected controller, so Media3 posted no media notification and the system's
      media controls did not list the app
- [x] Bluetooth auto-play moves the sound to the device that connected: `MediaRouter2`
      transfer to its system route (Android 14+, BLUETOOTH_CONNECT + BLUETOOTH_SCAN), with
      Shizuku as a fallback (`IBluetooth.setActiveDevice` as the shell user); retried while
      A2DP connects
- [ ] Verified on a device: media controls after a Bluetooth start, routing with two devices

Status: in progress (2026-11-20)

## TSA-4: Stop and disconnect at the end of a Bluetooth auto-play

- [x] "Am Ende stoppen": stop the player (no media items) when the auto-played edition ends
- [x] "Am Ende trennen": disconnect the device (`IBluetooth.disconnectAllEnabledProfiles` as
      the shell user through Shizuku); a reconnect within 30 minutes does not auto-play
- [x] Only if at least 30 s were left to play once playback ran (resume near the end); off by
      default
- [x] "Am Ende trennen" greyed out (shown off, choice kept) while Shizuku is not ready
- [ ] Verified on a device (P11): car, edition runs to its end

Status: in progress (2026-11-27)
