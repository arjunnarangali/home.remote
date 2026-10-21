# Engineering Review & Completion Tracker

**Updated:** 2026-10-21  
**Overall status:** Implementation and static checks pass; final physical TV interaction checks remain.  
**Reference:** `/home/shahabas/services/tvremote/www/index.html` + `server.py`  
**Android target:** API 36; home Wi-Fi; `http://192.168.1.9:6969`

## Step completion

| Step | Status | Completion evidence / remaining work |
|---|---|---|
| 1. Audit source UI and server API | Complete | Read the remote UI, routes, dock metadata and voice-sidecar contract. Added a focused `/launch` correction to the Lenovo service and kept a rollback copy. |
| 2. Separate UI, state, API and recorder responsibilities | Complete | `MainActivity.kt`, `RemoteControls.kt`, `RemoteDialogs.kt`, `RemoteViewModel.kt`, `RemoteApi.kt`, and `PcmAudioRecorder.kt`. |
| 3. Match screen and controls | Complete in code | Dock icons/colors, persisted theme, connection/reconnect, buttons, keyboard, wallpaper dialog, and responsive layout implemented. |
| 4. Match service contracts | Complete | Android uses typed requests/errors; Lenovo `/launch` supports Leanback apps, encoded passthrough IDs and checked ADB/ActivityManager results. |
| 5. Implement Ask Rocky | Complete in code; hardware test pending | Runtime permission, 16 kHz mono WAV capture, hold/tap behavior, timeout/cancel/background cleanup, `/voice` response display. |
| 6. Build, unit test and lint | Complete | `testDebugUnitTest`, `assembleDebug`, and `lintDebug` pass. Lint reports 0 errors; remaining warnings are documented compatibility pins. |
| 7. Install and test on hardware | Partial | Latest APK installed/launched on the I2405i phone with no runtime exception. An instrumented artwork test passes on both the I2405i (Android 16) and emulator (Android 17). Remote-control, voice and wallpaper actions still need manual TV verification. |
| 8. Documentation | Complete | `plan.md`, `README.md`, `status.md`, and this tracker describe architecture, build steps, trade-offs and remaining checks. |

## Review findings fixed

- Moved polling out of unconditional ViewModel startup and tied it to Activity `STARTED`, avoiding background network polling.
- Propagated coroutine cancellation instead of reporting cancellation as a network failure.
- Added command-specific HTTP read timeouts so ADB reconnect and voice inference are not cut off by the ordinary API timeout.
- Added explicit runtime microphone permission verification at recorder creation to handle revoked permission safely.
- Serialized recorder start/stop/cancel, made recording state thread-safe, and ensured recorder release after reader completion.
- Stop active recording when the Activity backgrounds; prevent duplicate cancellation from disrupting a later recording or an in-flight voice request.
- Validate server URLs (scheme, origin-only, port, no credentials, and private/local host) and constrain dock icon fetches to the Lenovo `/icons/` path.
- Added Coil's OkHttp network fetcher: the SVG/PNG logos were served correctly, but Coil 3 does not load HTTP(S) images unless a network transport is included.
- Added a launcher icon, declared microphone hardware optional, fixed responsive-width observation, and split Compose controls/dialogs into their own files.
- Added unit coverage for WAV headers/shape, wallpaper append progress, voice result formatting, LAN URL validation, and same-origin icon URLs.
- Added an instrumented test that downloads and decodes every dock SVG/PNG logo through the real Coil network pipeline on the Lenovo LAN.
- Updated the wrapper to Gradle 8.14.6, set target SDK 36, refreshed Activity to 1.13.0, and removed an unused Android-test BOM.
- Fixed `/home/shahabas/services/tvremote/server.py`: FanCode resolves using `LEANBACK_LAUNCHER` before the regular launcher category; HDMI input IDs are URL-encoded as one path segment; ADB/activity failures now return HTTP 502 rather than a false `{ok:true}`. Restarted `tv-remote.service`; rollback copy is `server.py.before-launch-fix`.

## Automated checks

- [x] `./gradlew testDebugUnitTest assembleDebug`
- [x] `./gradlew lintDebug` — success, **0 errors**
- [x] `./gradlew connectedDebugAndroidTest` — artwork fetch/decode passed on the I2405i and emulator.
- [x] Install/launch the latest debug APK on the I2405i phone; no Android runtime exception.
- [x] Three Lenovo-side launch regression tests pass (`python3 -m unittest -v test_server_launch`).
- [x] Actual `/launch` POST for FanCode opened `com.fancode.tv/com.fancode.fcandroidtv.MainActivity` on the TV.
- [x] Actual `/launch` POST for PlayStation opened `PassthroughPlayerActivity` with input `com.mediatek.tvinput/.hdmi.HDMIInputService/HW8`.
- [x] Invalid app package now returns HTTP 502 with a launch-resolution error instead of false success.

## Manual acceptance checklist

- [ ] Live/offline status reflects the Lenovo and reconnect displays server hints.
- [ ] Up/down/left/right/OK, Back hold, volume hold, mute, Home, and Input control the TV correctly.
- [ ] Verify YouTube/Jellyfin/Netflix and the touch interaction on the phone; FanCode and PlayStation service launches are verified below.
- [x] Artwork source/decoding verified against the Lenovo service: YouTube, Jellyfin, Netflix light/dark, FanCode light/dark, and PlayStation logos all return HTTP 200 and decode in Coil on both connected devices.
- [x] FanCode and PlayStation service launch behavior verified on the real TV through the exact JSON contract used by the app.
- [ ] Keyboard sends supported text followed by Enter.
- [ ] Sleep activates the photo screensaver (not display power-off).
- [ ] Wallpaper replace/append, provider/sort, progress, completion and service conflict/error messages work.
- [ ] Ask Rocky permission grant/deny, tap-to-listen, hold-to-talk, timeout, cancellation, and response work end-to-end.
- [ ] Backgrounding the app stops microphone capture; returning resumes polling.
- [ ] Losing Wi-Fi/service shows an error and recovers after connectivity returns.

## Known warning rationale

Lint recommends API 37 and newer Lifecycle/Coil versions. The API 37 libraries require AGP 9.1+ and compile SDK 37; one Coil candidate also uses Kotlin 2.4 metadata. This project is intentionally pinned to the API 36/Kotlin 2.2-compatible toolchain. Upgrade those together after implementing and testing Android's newer local-network access behavior.
