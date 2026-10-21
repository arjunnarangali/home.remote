# my.home Remote (Android)

Native Android replica of the Lenovo TV's mobile remote UI, using the TV's existing HTTP remote service. The phone and TV must be on the same Wi-Fi network.

## Build

Open this folder in Android Studio and sync Gradle, or run:

```sh
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run automated checks with:

```sh
./gradlew testDebugUnitTest lintDebug
```

With an emulator or Android device connected, verify that all the server-hosted dock logos can be downloaded and decoded:

```sh
./gradlew connectedDebugAndroidTest
```

## Install on a phone

Connect the Android phone over USB with USB debugging enabled (or with wireless ADB already paired), then run:

```sh
./gradlew installDebug
```

The phone and Lenovo TV must initially be connected to the same Wi-Fi network.

## Configure the Lenovo address

Use the **More options → Server address** menu in the app. The URL must be an origin only (scheme, host and optional port), and is limited to private IP/local DNS addresses. The default is `http://192.168.1.9:6969`.

The Lenovo service currently uses plain HTTP. Remote commands and Ask Rocky audio are therefore unencrypted while crossing the home LAN; use the app on a trusted Wi-Fi network. Voice recordings are kept in memory only and sent to the Lenovo `/voice` route; they are not saved on the phone.

## Architecture and verification

- `MainActivity.kt`: lifecycle, permissions and screen composition.
- `RemoteControls.kt` / `RemoteDialogs.kt`: touch controls, keyboard, wallpaper and server settings UI.
- `RemoteViewModel.kt`: UI state, lifecycle-aware polling and command coordination.
- `RemoteApi.kt`: server protocol models, URL validation and HTTP client.
- `PcmAudioRecorder.kt`: permission-checked PCM capture and WAV encoding.
- Dock logos are loaded from the Lenovo's `/icons/` directory using Coil's OkHttp network module and SVG decoder.
- The Lenovo launch endpoint resolves Android TV Leanback activities and switches HDMI inputs using encoded TV input IDs. Keep `~/services/tvremote/test_server_launch.py` with the server code and run `python3 -m unittest -v test_server_launch` from that directory after server changes.

Review the [`plan.md`](plan.md) and [`tracker.md`](tracker.md) for design decisions, automated checks, and the remaining TV/device acceptance checklist.

The project currently targets API 36. Lifecycle 2.9.4 and Coil 3.3.0 are intentionally held at API-36/Kotlin-2.2-compatible versions; the newest releases reported by lint require API 37 and, for part of the dependency graph, Kotlin 2.4. Upgrade the Android Gradle Plugin, Kotlin compiler, SDK and affected libraries as a coordinated toolchain change rather than bumping those artifacts independently.
