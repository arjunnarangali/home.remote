# my.home Remote — Engineering Plan

## Product scope

Build a native Android replica of `/home/shahabas/services/tvremote/www/index.html`. The Lenovo's `tvremote/server.py` remains the service boundary and continues to perform ADB/TV operations. The phone app sends remote commands over the home LAN; it does not connect to TV ADB directly and does not play media.

- Default service: `http://192.168.1.9:6969`
- UI: Kotlin + Jetpack Compose
- Minimum Android: API 26
- Compile/target: API 36
- Primary deployment: phone and Lenovo on the same Wi-Fi

## Architecture

```text
MainActivity (lifecycle + permission result)
  └── RemoteApp / RemoteControls / RemoteDialogs (Compose rendering and gestures)
        └── RemoteViewModel (screen state, lifecycle polling, command orchestration)
              ├── RemoteApi (typed HTTP contract with tvremote/server.py)
              └── PcmAudioRecorder (short-lived PCM capture and WAV encoding)
```

The UI layer owns presentation and user gestures. `RemoteViewModel` owns screen state and coroutine work. `RemoteApi` serializes requests and responses. `PcmAudioRecorder` owns microphone resources and produces in-memory 16 kHz mono PCM WAV data. This keeps UI recomposition separate from network and audio lifetimes.

## Implementation steps

### 1. Audit the reference and service contract — Complete

- Inspect the reference HTML/CSS/JavaScript and service routes.
- Use `/healthz`, `/dock`, `/key`, `/launch`, `/text`, `/reconnect`, `/sleep`, `/wallpaper/status`, `/wallpaper`, and `/voice` through the existing service.
- Keep the Lenovo service as the ADB/TV execution boundary. A focused correction to its existing `/launch` handler was required: FanCode is Leanback-only, and passthrough input IDs must be encoded as a single URI path segment. No topology, port, or networking changes were made.

### 2. Establish Android structure and lifecycle — Complete

- Use an Activity, lifecycle-aware ViewModel/StateFlow, and Compose component files.
- Start status/dock/wallpaper polling only while the Activity is `STARTED` and cancel those loops when it backgrounds.
- Keep blocking network/audio work off the main thread.
- Cancel microphone capture when the app backgrounds or the user cancels the gesture.

### 3. Implement visual and control parity — Complete

- Header status, reconnect, theme toggle, server settings, and dynamic dock artwork.
- D-pad/OK, held directional and volume controls, held Back, mute, Home/Input, keyboard, Sleep, and Wallpaper.
- Persist theme and server origin.
- Support compact layouts and scrollable content; use semantic labels for touch targets.

### 4. Implement service behavior and failure handling — Complete

- Parse dock, status, reconnect, voice, and wallpaper responses as typed state.
- Preserve server-provided colors, themes, icon fit, app packages, and inputs.
- Fetch SVG/PNG artwork from the Lenovo `/icons/` endpoint with Coil's explicit OkHttp network transport.
- Use command-specific read timeouts: ordinary calls, ADB recovery, and voice inference have different server-side duration bounds.
- Do not automatically retry key/launch commands because retries can duplicate TV actions.
- Resolve Android TV apps via `LEANBACK_LAUNCHER` first, then standard `LAUNCHER`; percent-encode passthrough input IDs; return HTTP errors when TV-side launch commands fail.
- Validate configurable server origins and keep them on private IP ranges or local DNS names.
- Load dock artwork only from the configured Lenovo server's `/icons/` path.

### 5. Implement Ask Rocky and microphone lifecycle — Complete in code

- Request `RECORD_AUDIO` at the point of use.
- Match hold-to-talk and tap-to-listen behavior, with a 12-second cap.
- Record mono 16 kHz PCM, encode WAV in memory, and POST to the LAN `/voice` route.
- Show transcript/action feedback and avoid persisting audio.
- Release or stop the recorder on short clips, cancellation, permission denial, backgrounding, and Activity destruction.

### 6. Quality gates — Code checks complete; hardware workflow checks remain

- Run `./gradlew testDebugUnitTest assembleDebug`.
- Run `./gradlew lintDebug`; resolve all errors and review warnings.
- Install/launch the debug build on an Android device.
- Run `./gradlew connectedDebugAndroidTest` to verify the real server's SVG/PNG logos decode through Coil on connected devices.
- Manually verify TV commands, voice permission/audio, and wallpaper operations against the Lenovo. Do not mark service actions complete based only on compilation or emulator launch.

## Engineering decisions and trade-offs

- **API 36 target:** Current platform/device target is API 36. Lint's API 37 recommendation would opt into newer Android behavior, including local-network access changes, and should only be adopted with the corresponding permission flow and a tested network implementation.
- **Dependency compatibility:** Keep Compose, Lifecycle, and Coil versions compatible with Kotlin 2.2.20 and compile SDK 36. Newer candidates require API 37 and/or Kotlin metadata newer than the project's compiler, so version bumps are gated on upgrading the whole toolchain together.
- **LAN HTTP:** The Lenovo currently serves its API over HTTP. The app limits configured origins to private LAN/local DNS targets, but commands/audio remain unencrypted on that LAN. Use only on a trusted Wi-Fi network; a future TLS/authentication change belongs at the service boundary.
- **Voice implementation:** The `/voice` route is the public service API. The voice sidecar is loopback-only on the Lenovo and must not be called directly from the phone.

## Delivery

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Build/install instructions and endpoint configuration: `README.md`
- Step completion and remaining manual checks: `tracker.md`
