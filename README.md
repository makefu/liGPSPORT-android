# ligpsport-android

Native Android app that plans bike routes via [BRouter](https://brouter.de/) and
uploads them to iGPSPORT cycling computers (BSC200 and family) over BLE.

The BLE wire protocol is a Kotlin port of the reverse-engineered Python library
at `../ligpsport` — see `docs/PROTOCOL.md` upstream for the wire spec.

## Features

1. **Plan & upload**: tap a destination on the OSM map, the app fetches a GPX
   route from BRouter and uploads it to your iGPSPORT device.
2. **OsmAnd share**: share a GPX track from OsmAnd via the Android share sheet
   and upload it straight to the device.

## Quick start (NixOS)

```sh
# Enter dev shell (downloads Android SDK, JDK, Gradle on first run)
nix develop

# Run all unit tests (17 tests, fully hermetic)
nix run .#test-unit

# Build the debug APK (writes to app/build/outputs/apk/debug/)
nix run .#build-debug

# Build a release APK (R8-minified; ~6 MB)
#   KEYSTORE_PATH=... KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=... to sign
nix run .#build-release

# Boot a headless emulator and install the debug APK (CI-friendly)
nix run .#emulator

# Boot a windowed emulator on your desktop, install + launch the app.
# Requires an X11 or Wayland (XWayland) session and OpenGL drivers.
# Override the GPU backend via EMULATOR_GPU=swiftshader_indirect if the
# host-GPU passthrough fails (mesa/driver mismatch).
nix run .#gui-emulator

# Run Espresso instrumented tests against the running emulator
nix run .#run-instrumented-tests
```

## Architecture

| Layer | Where |
|---|---|
| BLE framing (20-byte header + CRC-8/MAXIM) | `app/src/main/kotlin/.../ble/Framing.kt` |
| GATT UUIDs (4 parallel Nordic-UART services) | `.../ble/GattUuids.kt` |
| Transport abstraction (live + loopback) | `.../ble/Transport.kt` |
| File upload (FILE_OPERATION ADD on `…-6e`) | `.../ble/FileTransfer.kt` (stub in v0) |
| CNX encoder (iGPSPORT proprietary route XML) | `.../route/CnxEncoder.kt` |
| GPX parser (SAX, JVM-portable) | `.../route/GpxParser.kt` |
| BRouter HTTP client | `.../route/BRouterClient.kt` |
| Compose map + share intent UI | `.../ui/{map,share,upload}/` |

The 33 `.proto` schemas in `app/src/main/proto/` are vendored from the Python
project's `reference/` directory; `protobuf-kotlin-lite` generates the runtime
bindings.

## Known limits (v0)

- **Live BLE upload is not yet implemented**: `BleTransport.kt` and
  `FileTransfer.kt` are stubs that throw at runtime. The protocol primitives
  (framing, CRC, CNX encoder, GPX parser, BRouter client, Compose UI, share
  intent) all work and are tested. The remaining work is the Nordic-ble manager
  port of `ligpsport/ble.py` + the chunked-upload state machine in
  `ligpsport/file_transfer.py`.
- The `release` build is signed with the **debug** keystore unless
  `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` are set —
  don't distribute that APK.
- The Espresso suite runs against a `LoopbackTransport` plus an in-process
  `FakeIgpsportSimulator`; it does not exercise a real BLE radio (the Android
  emulator's Bluetooth HAL is stubbed). A manual smoke checklist for real
  hardware lives in `docs/smoke-tests.md`.

## License

MIT (same as the upstream `ligpsport` library — see `LICENSE`).
