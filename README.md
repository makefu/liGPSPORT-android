# ligpsport-android

Native Android app that plans bike routes and uploads them to iGPSPORT
cycling computers (BSC200 and family) over BLE — then flips the device
into navigation mode automatically. No iGPSPORT cloud account required.

The BLE wire protocol is a Kotlin port of the reverse-engineered Python
library at [`makefu/ligpsport`](https://github.com/makefu/ligpsport). The
authoritative wire spec lives in that repo's `docs/PROTOCOL.md`.

## Features

- **Tap a destination → upload**: pick a point on the OSM map (or type
  a search query in the docked Photon-backed search bar) and the app
  immediately plans a route to it. Press **Upload** and the route is
  encoded to iGPSPORT's proprietary CNX format, pushed to the BSC200
  via the `FILE_OPERATION ADD` chunked path, and the device flips into
  navigation mode automatically (`ROUTE_PLAN FILE_USE`, PROTOCOL.md
  §7.2).
- **Three pluggable routing backends**, switchable from Settings:
  *BRouter* (cycling-optimised online), *OSRM* (general-purpose
  online), and *straight-line* (offline fallback that interpolates a
  great-circle path — useful when you have no network).
- **Live navigation-status pill** in the map's bottom-left corner:
  polls `ROUTE_PLAN LIST_GET` every ~15 s and surfaces whether the
  BSC200 is currently navigating, plus the active route's name.
  Shows `Pair device first` until you've paired, `Connecting…`
  during the first poll.
- **Route management** in Settings: lists every route the BSC200
  holds (id, name, distance, active flag) with per-row delete and a
  "Delete all routes" bulk action. The active navigation route is
  firmware-protected and gets a guard dialog (PROTOCOL.md §7.4).
- **OsmAnd share**: share a GPX track from OsmAnd via the Android
  share sheet to upload it straight to the device.
- **AGPS pre-seeding**: every route upload silently piggybacks
  ~2.5 KB of u-blox AssistNow ephemeris (`file_type=AGPS(7)`) before
  the route bytes — gives the BSC200's GNSS chip a hot start without
  the rider needing to stand still waiting for a fix. Token is
  auto-resolved from the iGPSPORT prod config endpoint the same way
  the official app does it; override at build time with
  `LIGPSPORT_AGPS_TOKEN` if you have a u-blox developer token.
- **Position-prior injection**: each upload also injects the phone's
  current location via the FACTORY `GPS_COORDINATE_SET` op
  (service=11, op=8). Combined with the AGPS push this gets the
  BSC200 to "I know where I am" in well under a minute on a cold
  start.
- **Headless adb harness**: every user-facing action has a matching
  `am broadcast` shortcut that emits one structured logcat line —
  the basis for `scripts/e2e-test.sh`. See [AGENTS.md](AGENTS.md) for
  the full action table.

## Quick start (NixOS / nix-on-anywhere)

```sh
# Enter the dev shell (downloads Android SDK / JDK / Gradle on first run)
nix develop

# Run the JVM unit tests (hermetic — no device or network)
nix run .#test-unit

# Build the debug APK → app/build/outputs/apk/debug/app-debug.apk
nix run .#build-debug

# Build a release APK (R8-minified; ~6 MB)
#   KEYSTORE_PATH=… KEYSTORE_PASSWORD=… KEY_ALIAS=… KEY_PASSWORD=… to sign
nix run .#build-release

# Install + launch the debug APK on the first attached adb device
nix run .#install

# Headless emulator (CI-friendly) / windowed emulator on your desktop
nix run .#emulator
nix run .#gui-emulator

# Espresso instrumented tests against the running emulator
nix run .#run-instrumented-tests

# End-to-end test against a real BSC200 over adb
# (pairs the device, runs upload + nav-start + delete-all + verify)
nix run .#e2e-test
```

## Architecture

| Layer | Where |
|---|---|
| BLE framing (20-byte header + CRC-8/MAXIM) | `.../ble/Framing.kt`, `.../ble/Crc8Maxim.kt` |
| GATT UUIDs (4 parallel Nordic-UART services) | `.../ble/GattUuids.kt` |
| Transport abstraction (live + loopback) | `.../ble/Transport.kt`, `.../ble/BleTransport.kt`, `.../ble/LoopbackTransport.kt` |
| File ops (FILE_OPERATION ADD, ROUTE_PLAN LIST/USE/FILES_DEL) | `.../ble/FileTransfer.kt` |
| AGPS fetch + position-prior injection | `.../ble/AgpsClient.kt`, `.../ble/LocationInjector.kt` |
| High-level orchestration (GPX→CNX→upload→nav-start) | `.../ble/UploadPipeline.kt` |
| CNX encoder (iGPSPORT proprietary route XML) | `.../route/CnxEncoder.kt` |
| GPX parser (SAX, JVM-portable) | `.../route/GpxParser.kt` |
| Pluggable route providers (`brouter` / `osrm` / `straightline`) | `.../route/RouteProvider.kt`, `.../route/providers/` |
| Photon type-ahead geocoder | `.../search/PhotonClient.kt` |
| Compose UI: map, settings, pairing, share | `.../ui/{map,settings,pairing,share,upload}/` |
| Adb harness (broadcast receiver + foreground service) | `.../cli/AdbCliReceiver.kt`, `.../service/UploadForegroundService.kt` |

The `.proto` schemas in `app/src/main/proto/` are vendored from the
Python project's `reference/` directory and compiled to runtime
bindings via `protobuf-kotlin-lite`.

## How a single upload flows

```
       ┌──────────────────────────────────────────────┐
       │  Map screen                                  │
       │   - tap point → destination = Point          │
       │   - auto-plan: provider.planGpx(start, end)  │
       │   - polyline drawn on the map                │
       │   - user taps Upload                         │
       └────────┬─────────────────────────────────────┘
                ▼
       UploadPipeline.uploadGpx(ctx, gpxBytes, fileId, fileName)
                │
                ├─→ GpxParser → RouteData (incl. distance, trkpts)
                ├─→ CnxEncoder → cnx bytes
                ├─→ BleTransport.open() (paired MAC from DeviceStore)
                ├─→ AgpsClient.fetchAndSeed (~2.5 KB UBX-MGA, best-effort)
                ├─→ LocationInjector.injectCurrentLocation (best-effort)
                ├─→ FileTransfer.uploadGeneralFile (FILE_OPERATION ADD on FOURTH)
                └─→ FileTransfer.startNavigation
                       (ROUTE_PLAN FILE_USE — single merged write on FOURTH,
                        PROTOCOL.md §7.2)
                ▼
              BSC200 switches into the navigation screen
```

The nav-status pill picks this up on its next 15 s poll and updates
the UI to `Navigating: <route name>` — closing the visual feedback
loop end-to-end.

## Known caveats

- **No "stop navigation" over BLE.** The `DEV_NAVI_STATUS` proto enum
  only has `ON`/`OFF` and `route_plan.proto` has no `FILE_UNUSE`
  opcode. The active navigation route is firmware-protected against
  `FILES_DEL`: the device acks `status=0` but the route stays. The
  app surfaces this as a guard dialog in Settings → Routes on device.
  To stop navigation, use the BSC200's own UI / Stop button.
- **`DEV_STATUS.navi_status` is dead on the wire.** The proto exposes
  it but BSC200 firmware (verified 2024-05-14) never populates it.
  The nav-status pill goes via `ROUTE_PLAN LIST_GET` and looks for the
  entry tagged `enum_USED_STATUS = 1` — same mechanism the iGPSPORT
  app uses (`RoutePlanViewModel.requestUsingRouteID`).
- **Release builds default to the debug keystore.** Don't distribute
  an unsigned-to-prod APK. Pass `KEYSTORE_*` env vars to
  `nix run .#build-release` for a properly signed artifact.
- **The emulator can't talk to a real BLE radio.** The instrumented
  Espresso suite runs against a `LoopbackTransport` plus an in-process
  `FakeIgpsportSimulator`; it does not exercise the real BLE stack.
  For BLE coverage, use `scripts/e2e-test.sh` against a paired BSC200.

## Documentation

- [AGENTS.md](AGENTS.md) — full developer guide: build matrix, adb
  harness contract, wire-protocol quirks, failure modes.
- [docs/AGPS_TOKEN.md](docs/AGPS_TOKEN.md) — how to obtain and
  configure a u-blox AssistNow developer token.
- [docs/smoke-tests.md](docs/smoke-tests.md) — manual hardware smoke
  checklist for milestone reviews.
- [CHANGELOG.md](CHANGELOG.md) — release history.
- Wire protocol: `docs/PROTOCOL.md` in
  [`makefu/ligpsport`](https://github.com/makefu/ligpsport).

## License

MIT — same as the upstream `ligpsport` library. See [LICENSE](LICENSE).
