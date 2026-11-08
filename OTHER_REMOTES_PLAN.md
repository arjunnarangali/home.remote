# Other remotes implementation plan

## Goal

Add an **Other remotes** entry on the home screen so people can discover Android TV / Google TV devices on the same Wi-Fi and connect to one without changing the existing Lenovo remote.

## Existing app boundary

The current remote talks to the Lenovo `tvremote/server.py` HTTP API. That API provides the app dock, voice, wallpaper, and ADB-backed control. It is not a generic TV protocol and its configured IP cannot be used as an address for arbitrary TVs.

Google TV's native remote uses Android TV Remote protocol v2. A complete implementation must support its discovery service, encrypted pairing flow, client certificate/key persistence, TLS connection, protobuf messages, and command acknowledgements. Discovery alone is not a connection, so the UI must never report a TV as paired/connected until the protocol handshake succeeds.

## Implementation stages

1. **Home entry and navigation**
   - Keep the existing Lenovo **Remote** entry intact.
   - Add a separate **Other remotes** entry and a dedicated discovery screen.
2. **Device discovery**
   - Use Android `NsdManager` to browse `_androidtvremote2._tcp.` on the local network.
   - Resolve service instances to host/port, deduplicate results, allow rescanning, and stop discovery when leaving the screen.
   - Explain the same-Wi-Fi and Android TV Remote Service requirements and show empty/error states.
3. **Pair and persist**
   - Implement protocol v2 TLS pairing, including the TV-displayed pairing code and certificate/key storage scoped to each device.
   - Let users remove a saved device and re-pair if credentials become invalid.
4. **Remote control**
   - Reuse the Lenovo remote layout, including D-pad, volume, action tiles, keyboard entry, and hold/tap microphone behavior.
   - Connect/reconnect on demand and expose explicit connecting, paired, offline, and error states.
5. **Verification**
   - Unit test discovery result mapping, saved-device data, and protocol message encoding/decoding.
   - Build and lint; manually verify pairing and commands with a real Android TV / Google TV device.

## Current delivery

Stages 1 through 4 are implemented: NSD discovery, manual local-IP fallback, TV-code pairing, per-TV certificate pinning, reconnection, shared Lenovo-style controls, native TV IME text updates, and Android TV Remote voice sessions. Voice capture uses the phone microphone, converts the recorded PCM to the TV protocol's 8 kHz format, and streams it directly to the paired TV. Pairing and D-pad command send were exercised against the AI PONT Android TV (Android 14) from an I2405 phone (Android 16); keyboard and voice still need end-to-end confirmation on a focused TV text field and with spoken input.

## Compatibility notes

- Discovery is limited to TVs advertising Android TV Remote v2; it is not universal smart-TV discovery.
- The TV must have Android TV Remote Service enabled/available and both devices must be on the same LAN with client isolation disabled.
- Android platform changes to local-network access can require runtime permission handling on newer target SDKs; verify that behavior when upgrading the current API 36 target.
