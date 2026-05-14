# AGENTS.md

Operational guide for agentic development on this repo. Captures the
build system, the test harness contract, the wire-protocol quirks that
took the longest to figure out, and the failure modes to expect.

Companion repo: `../ligpsport` (Python reverse-engineered library —
authoritative wire spec lives in `../ligpsport/docs/PROTOCOL.md`).
Use it as your protocol oracle, not as a runtime dependency.

## Repository layout

```
.
├── flake.nix                       Nix entry points (build / install / e2e)
├── settings.gradle.kts             single Gradle module: :app
├── gradle/libs.versions.toml       version catalog
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro          keep-rules for protobuf-lite, ktor, osmdroid
│   ├── src/main/kotlin/de/syntaxfehler/ligpsport/
│   │   ├── ble/                    Wire codec + transport
│   │   │   ├── Framing.kt          20-byte header (PbFrame / ConfirmFrame / RequestFrame)
│   │   │   ├── Crc8Maxim.kt        CRC-8/MAXIM table (verbatim from Python)
│   │   │   ├── GattUuids.kt        4 Nordic-UART services (8e / 9e / 7e / 6e)
│   │   │   ├── Transport.kt        interface (BLE vs Loopback)
│   │   │   ├── BleTransport.kt     real GATT client (serialised by Mutex)
│   │   │   ├── LoopbackTransport.kt for Espresso tests
│   │   │   ├── DeviceScanner.kt    name-prefix filtered BLE scan
│   │   │   ├── DeviceStore.kt      SharedPreferences-backed paired MAC
│   │   │   ├── FileTransfer.kt     uploadGeneralFile / deleteRoute / listRoutes
│   │   │   └── UploadPipeline.kt   high-level orchestration (GPX→CNX→upload)
│   │   ├── route/                  GPX parser, CNX encoder, BRouter client
│   │   ├── search/PhotonClient.kt  type-ahead geocoder
│   │   ├── ui/                     Compose screens (map, pairing, upload, share)
│   │   ├── cli/                    adb broadcast hooks (AdbCliReceiver, AdbResult)
│   │   └── service/UploadForegroundService.kt   worker for BLE ops
│   ├── src/test/                   JVM unit tests (framing, CRC, CNX, GPX)
│   └── src/androidTest/            Espresso tests with LoopbackTransport
├── scripts/e2e-test.sh             adb-driven end-to-end test
└── tests/fixtures/test-route.gpx   small GPX for offline e2e path
```

## Build / run cheatsheet

All entry points are self-contained — each `nix run` target builds
whatever it depends on. No prior step is required.

| Command | Purpose |
|---|---|
| `nix run .#build-debug` | Build the debug APK (~30 s warm) |
| `nix run .#build-release` | R8-minified release APK (~6 MB) |
| `nix run .#test-unit` | JVM unit tests (17 tests, hermetic) |
| `nix run .#install` | Build + install + launch on first adb device |
| `nix run .#emulator` | Headless AVD, install + launch debug APK |
| `nix run .#gui-emulator` | Windowed AVD on the host's X11/Wayland session |
| `nix run .#run-instrumented-tests` | Espresso suite on the running emulator |
| `nix run .#e2e-test` | Real-hardware end-to-end test via adb |

Env knobs:
- `LIGPSPORT_SKIP_BUILD=1` on `install` / `e2e-test` to reuse a prior APK.
- `LIGPSPORT_USE_BROUTER=1` on `e2e-test` to exercise PLAN_AND_UPLOAD
  (network) before falling back to the vendored GPX fixture.
- `LIGPSPORT_FORCE_PAIR=1` on `e2e-test` to discard the persisted
  pairing and re-scan. By default the harness reuses the existing
  pairing via the `STATUS` broadcast and skips PAIR — this matches
  end-user expectations ("don't keep re-pairing the device") and
  shaves ~10 s off each run.
- `LIGPSPORT_ROUTERS="..."` to override the routers tested
  (default: `straightline brouter osrm`).
- `LIGPSPORT_NO_CLEANUP=1` to skip the preflight `DELETE_ALL_ROUTES`.
- `EMULATOR_GPU=swiftshader_indirect` if `gui-emulator` host-GPU
  passthrough fails (mesa driver mismatches).
- `ANDROID_SERIAL=<id>` to pin install to a specific adb device.
- `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
  for signed release APKs (debug keystore is used by default).
- `LIGPSPORT_AGPS_TOKEN` at build time injects a u-blox AssistNow
  developer token, bypassing the runtime fetch from
  `prod.en.igpsport.com`. Empty (the default) is fine — the client
  auto-resolves the token the same way the official iGPSport app
  does. Free u-blox tokens at
  https://www.u-blox.com/en/assistnow-service-evaluation-token-request.
  See `/home/makefu/r/ligpsport/docs/PROTOCOL.md` §12 for the wire shape.

## adb broadcast harness contract

This is the most powerful test surface. Every adb broadcast emits a
**single structured logcat line**:

```
LigpsportAdb: RESULT action=<X> req_id=<Y> status=OK|FAIL [key=value …] [reason="quoted"]
```

The harness greps by `req_id=<Y>` (mandatory `--es req_id <id>` or
`--ei req_id <int>` extra) to correlate one broadcast with one result.

### Actions (all on `AdbCliReceiver`)

| Action | Worker | Extras | Result keys |
|---|---|---|---|
| `…action.PAIR` | service | — | `name=`, `mac=` |
| `…action.UNPAIR` | inline | — | — |
| `…action.STATUS` | inline | — | `bt_enabled=`, `paired_mac=`, `paired_name=` |
| `…action.UPLOAD` | service | `--es gpx_b64` (preferred) **or** `--es path` **or** `--es gpx` (inline xml) **or** `--es uri`; optional `--el file_id`, `--es name` | `file_id=`, `cnx_bytes=`, `points=`, `device_status=` |
| `…action.PLAN_AND_UPLOAD` | service | `--ef end_lat,end_lon` (required); `--ef start_lat,start_lon` optional — if absent, the service resolves current location via `MockLocationStore` → `FusedLocationProviderClient.getCurrentLocation` → `lastLocation`; optional `--es profile`, `--el file_id`, `--es name` | `provider=` (which router was used), plus all UPLOAD keys |
| `…action.LIST_ROUTES` | service | — | `count=`, `r<N>_id=`, `r<N>_name=`, `r<N>_dist_m=` |
| `…action.DELETE_ROUTE` | service | `--el file_id`; optional `--es ext` (default `cnx`) | `file_id=`, `device_status=` |
| `…action.DELETE_ALL_ROUTES` | service | `--es confirm true` (gate) | `device_status=` — DESTRUCTIVE, wipes ROUTE_PLAN storage via `FILES_DEL` op=6 |
| `…action.SET_ROUTER` | inline | `--es id <brouter\|osrm\|straightline>` | `id=` |
| `…action.LIST_ROUTERS` | inline | — | `count=`, `current=`, per-router `r<N>_id=`, `r<N>_name=`, `r<N>_offline=` |
| `…action.MOCK_LOCATION` | inline | `--ef lat --ef lon` | `lat=`, `lon=` — sets in-process `MockLocationStore`, consulted by `PLAN_AND_UPLOAD` when no explicit start is given |
| `…action.SEND_AGPS` | service | — | `agps_bytes=`, `device_status=` — fetches u-blox AssistNow Online and uploads as `file_type=AGPS(7)`. Token is auto-resolved from iGPSport's prod config endpoint (mirrors the official app) when `LIGPSPORT_AGPS_TOKEN` is unset; the env var overrides it when you have your own u-blox AssistNow token. Also piggybacked silently on every `UPLOAD` / `PLAN_AND_UPLOAD` (best-effort: failure doesn't fail the route) — successful piggyback shows `agps_bytes=<N>` on those RESULT lines too. |
| `…action.SEND_LOCATION` | service | — | `seed_lat=`, `seed_lon=`, `device_status=` — injects the current location as a position prior via the FACTORY `GPS_COORDINATE_SET` op (service=11, op=8). Resolves via `MockLocationStore` → `FusedLocationProviderClient` → `lastLocation`. Also piggybacked silently on every `UPLOAD` / `PLAN_AND_UPLOAD` between the AGPS step and the route upload — successful piggyback shows `seed_lat=` + `seed_lon=` on those RESULT lines. |

### Critical rule: BLE ops live in the foreground service, not the receiver

`BroadcastReceiver.goAsync()` only buys ~10 s of grace before the
system ANRs the receiver. A worst-case BLE upload (connect 15 s +
discover 10 s + chunked send + ack) blows past that. The thin receiver
in `cli/AdbCliReceiver.kt` forwards to `service/UploadForegroundService.kt`
via `startForegroundService(...)` and returns immediately. The service
runs with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` for the
unbounded-BLE exemption on Android 14+.

### Inline GPX is the safe path

Android 11+ scoped storage blocks `/sdcard/Download/*.gpx` reads from
app processes without `READ_MEDIA_*` permissions. The harness uses
`--es gpx_b64 "$(base64 -w 0 fixture.gpx)"` and the service decodes
with `android.util.Base64`. Avoid pushing GPX to `/sdcard` and reading
back — it works for the shell user but not for the app uid.

### Race-free logcat polling pattern

```bash
adb logcat -T 1 -s "LigpsportAdb:I" >tmp/log &  # start BEFORE the broadcast
LOGCAT_PID=$!
sleep 0.5  # give the tail time to attach

adb shell am broadcast \
  -n de.syntaxfehler.ligpsport.debug/de.syntaxfehler.ligpsport.cli.AdbCliReceiver \
  -a de.syntaxfehler.ligpsport.action.PAIR \
  --es req_id "42-$RANDOM"

# Poll the log file with a timeout
grep -E "RESULT action=PAIR.*req_id=42-${RANDOM}([[:space:]]|$)" tmp/log
```

Don't use `adb logcat -c` to "reset" first — it races against other
broadcasts in the system and nukes context useful for debugging.

## Navigation / UX shape

```
                       ┌─────────┐
            ┌──────────│  Map    │──────────┐
            │          └─────────┘          │
            │            │   │              │
            │  destination &  │ gear FAB    │ [Plan]→draw polyline
            │  GPX persisted  ▼              │ [Upload]→nav to Upload
            │  in RouteSessionStore   ┌──────────┐
            │                         │ Settings │
            │                         └──────────┘
            ▼                              │
        ┌────────┐  ◀── pop ◀──── ┌────────────┐
        │ Upload │                │  Pairing   │
        │(auto)  │                │ (auto pop  │
        └────────┘                │  on pick)  │
                                  └────────────┘
```

### Map: two-step Plan → Upload

The destination card surfaces **two distinct buttons**, never one
combined "Plan & upload":

- **Plan** runs the configured `RouteProvider`, draws the polyline,
  stores the GPX in `RouteSessionStore.plannedGpx`, and flips the
  Upload button to enabled. Re-tapping says "Re-plan" — useful after
  switching routers in Settings.
- **Upload** is disabled until a plan exists. Tapping navigates to
  the Upload screen with the planned GPX. Splitting these two
  actions prevents the failure mode where a user commits to a BLE
  upload (a costly, ~30 s operation) before seeing the suggested
  route on the map.

When the destination changes (search-pick, map-tap, or X-clear), the
previous plan is dropped: `plannedGpx = null` + `Polyline` overlays
are removed from the MapView. Upload disables itself again.

### Other UX invariants

- **Map ↔ Upload**: `RouteSessionStore` (in `data/`) holds the
  destination + last planned GPX so a Back-to-map round-trip
  preserves the polyline + marker. Cleared on the destination card's
  X button.
- **Upload auto-runs** the BLE pipeline on entry when a device is
  paired. No manual "Send" tap. When unpaired, it shows an "Open
  Settings" button instead. (Justification: by the time the user
  taps Upload on the map they've already opted in; an additional
  "Send" confirmation on the next screen is friction.)
- **Pairing persists across runs and app restarts** via
  `DeviceStore` (SharedPreferences file `ligpsport.paired_device`,
  keys `address` / `name`). Re-opening the app or re-running the
  e2e suite must NOT force a re-pair. The e2e harness explicitly
  reuses the existing pairing — see `scripts/e2e-test.sh` and the
  `LIGPSPORT_FORCE_PAIR` env knob.
- **Pairing is reached from Settings only**, not from Map / Upload —
  the gear FAB owns device management. Tapping a device row in
  `PairingScreen` saves + auto-pops; the manual Done button is gone.
- **`SettingsScreen` re-reads paired state on ON_RESUME** via a
  Lifecycle observer so pairing changes show up immediately after
  popping back.
- **Search bar auto-collapses on map tap** — the
  `MapEventsReceiver.singleTapConfirmedHelper` flips
  `searchActive = false` so the docked search panel doesn't
  obscure the just-picked destination.
- **Map taps reverse-geocode** to a friendly name (street + house
  number, POI, or city) via `PhotonClient.reverse(lat, lon)`. The
  destination card opens immediately with a coordinate stub
  (`"%.5f, %.5f"`) and upgrades in place once Photon responds, so
  the UI never blocks on the network. If the user moves to a new
  destination before the lookup returns, the stale result is
  discarded.
- **The destination name becomes the upload file name**, sanitised
  for the BSC200 firmware (ASCII alphanumerics + `_-.`, spaces →
  `_`, max 32 chars). See `ui/upload/UploadScreen.kt#sanitiseFileName`
  and its unit-test contract in
  `app/src/test/kotlin/.../ui/upload/FileNameSanitiserTest.kt`. The
  adb harness path is unaffected — it passes its own `--es name`
  extra to the foreground service, which bypasses the UI store.

### Compose test tags

The instrumented tests + adb harness identify UI surfaces by
`Modifier.testTag(...)`. Keep these stable:

| testTag | Surface |
|---|---|
| `osm_map` | The `AndroidView` hosting the osmdroid `MapView` |
| `search_bar` | DockedSearchBar at the top of Map |
| `settings_fab` | Gear-icon FAB, bottom-end |
| `my_location_fab` | My-location FAB stacked above settings; shown only once a GPS fix is available |
| `destination_card` | Bottom card on Map when a destination is set |
| `plan_button` | Plan / Re-plan button inside the destination card |
| `upload_button` | Upload button inside the destination card |
| `paired_device_card` | Settings card showing current pairing |
| `pair_button` / `forget_button` | Pair / Forget actions in Settings |
| `router_<id>` | One row per `RouteProvider` in Settings |
| `upload_status` | Status text on the Upload screen |

## Routing providers

Pluggable behind `route/RouteProvider`. Registered in
`route/RouterRegistry.all` (which is the source of truth for both the
Settings UI and the `LIST_ROUTERS` broadcast). User's choice is stored
by `data/RouterPreferences` (single key in a dedicated
SharedPreferences file).

| id | online? | notes |
|---|---|---|
| `brouter` | online | Cycling-optimised OSM router via `https://brouter.de/brouter?…`. Default. **Don't append `/getroute` to the URL** — that's a 404. |
| `osrm` | online | `https://router.project-osrm.org/route/v1/{profile}/…?geometries=geojson&overview=full`. Public instance has no native GPX export, so we convert the GeoJSON `coordinates` array to GPX in-process (`OsrmProvider.geojsonToGpx`). Profile mapping: `trekking` → `bike`. |
| `straightline` | OFFLINE | Synthesises a 20-point great-circle interpolation between start and end. Doesn't follow roads; useful as a deterministic fallback when networking is down and as a healthy smoke-test path that the BLE upload pipeline doesn't depend on the routing layer. |

### Adding another router

1. Implement `RouteProvider` in `route/providers/<Name>Provider.kt`.
2. Add it to the list in `route/RouterRegistry.all`.
3. Add a JVM unit test under `app/src/test/.../route/providers/`.
4. Done — Settings UI + `LIST_ROUTERS` broadcast automatically pick it up.

### Why no OsmAnd backend

OsmAnd's AIDL service binding + GPX serialisation is >50 lines of
boilerplate, requires OsmAnd installed at runtime, and doesn't compose
well with the current `RouteProvider` interface (which is synchronous
from a coroutine's perspective). The existing `ShareImportActivity`
already supports OsmAnd's *outbound* "share GPX" flow, which is the
documented "use OsmAnd" path.

## Wire-protocol quirks (BSC200, verified against firmware 2024-05-14)

These are the parts that took the longest to figure out. Don't relearn
them — they're already encoded in code.

1. **Route uploads use FILE_OPERATION ADD (service 21), NOT
   ROUTE_PLAN FILE_SEND.** The Python library's `upload_route_plan`
   chunked protocol on service 7 is documented but the BSC200 returns
   `DataError=1` for every chunk. The actual working path is
   `upload_general_file` (`FileTransfer.uploadGeneralFile`), which:
   - Builds a 20-byte head with `service=21`, `op=3`, **`byte[3]=0xAA`**
     (the magic upload tag — without it the device treats the payload
     as a normal request and rejects it).
   - Hand-encodes a `general_file_operation` protobuf (no generated
     bindings — the schema was discovered after the gen tool was wired
     up; trivial enough to hand-roll).
   - Composes `payload = head + BE-u32(pb_len) + pb + file_bytes`.
   - Writes the entire payload in MTU-sized chunks to the **Fourth**
     (`…-6e`) characteristic. Reply arrives on the FILE_OPERATION
     service notification.
   - The file format must be **CNX**, not GPX. Use `CnxEncoder`.

2. **Route deletes use ROUTE_PLAN FILE_DEL (service 7, op 3).**
   Different service/op than the upload, different wire shape:
   standard PbFrame on the Control channel (`…-8e`), NOT chunked.
   Body is a `route_plan_data_msg` with three fields: `service_type=7`,
   `route_plan_operate_type=3`, repeated `line_id="<id>.<ext>"`.

3. **`route_plan` LIST_GET doesn't return FILE_OPERATION-uploaded
   routes.** A consequence of the two paths having separate storage
   indexes on the device. Treat the device's status byte on UPLOAD /
   DELETE as authoritative; LIST_GET is informational only.

4. **CRC8 polynomial is 0x31 / reflected / no XOR-out** ("CRC-8/MAXIM"
   or "CRC-8/DOW"). Table verbatim in `ble/Crc8Maxim.kt`. Don't try to
   compute it on the fly — use the table.

5. **Status byte semantics** (from `DeviceReturnStatus`):
   - 0 = Success
   - 1 = DataError (most common rejection — wrong file format)
   - 4 = QuantityIsFull / DoneEarly (treat as success for chunked
     uploads — means the device queued the last useful chunk)
   - any other value = rejection, surfaced as `device_status=<N>`

6. **GATT ops MUST be serialised.** Android's `BluetoothGatt` callbacks
   are one-at-a-time; concurrent writes/discovers/MTU-changes will
   silently drop. `BleTransport` uses a `Mutex` to serialise every
   public method, and `suspendCancellableCoroutine` to bridge the
   callback API to coroutines. Don't change this without good reason.

7. **MTU**: BSC200 negotiates 247 (BLE 4.2). We request that on
   connect and chunk at `mtu - 3` (ATT header overhead). Default falls
   back to 23 if negotiation fails.

## Test strategy

Three layers, each with a different cost/coverage trade.

1. **JVM unit tests** (`nix run .#test-unit`, 17 tests, <30 s) — wire
   codec, CRC, CNX encoder byte-equality, GPX parser. Run on every
   change to the `ble/` or `route/` packages.

2. **Espresso instrumented tests** (`nix run .#run-instrumented-tests`,
   ~2 min on emulator) — protocol round-trip with `LoopbackTransport`
   + `FakeIgpsportSimulator`. Run before any change to the
   transport/dispatch logic.

3. **adb e2e on real hardware** (`nix run .#e2e-test`, ~30 s) —
   only this exercises the real BLE radio and the real BSC200. Run
   after any change to the upload / delete / pair flows.

**Don't try to test real BLE on the Android emulator.** Its Bluetooth
HAL is stubbed; `connectGatt` returns `GATT_FAILURE`. The Espresso
suite injects `LoopbackTransport` precisely to sidestep this.

## Common pitfalls (collected)

- **NixOS sandbox + `__noChroot`**: only works for users in `trusted-users`.
  We avoid this by exposing builds as `nix run` shell-app wrappers, not
  derivations. APK output goes to `app/build/outputs/apk/…` in the
  working tree, not the Nix store.

- **`adb devices` shows nothing**: phone screen locked or USB cable is
  charge-only. `adb kill-server && adb start-server` fixes a stale
  daemon.

- **`pm grant <pkg> <PERM>`**: works for `BLUETOOTH_SCAN`,
  `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` on Android 14 even with
  the `neverForLocation` flag. The harness grants them every run —
  idempotent.

- **`svc bluetooth enable`** sometimes fails silently on userdebug
  builds. The harness checks `settings get global bluetooth_on` after
  the call and fails fast with a clear message rather than hanging.

- **android-nixpkgs flake**: tried, hit infinite recursion in
  `sdk.${system}` for our user. Fell back to
  `pkgs.androidenv.composeAndroidPackages` from nixpkgs unstable.
  Works fine, pin emulator to 35.1.19 — older versions are gone from
  Google's repo.

- **`gradle2nix`** for hermetic APK builds: do NOT use. AGP version
  bumps break regen. Live with `nix run` wrappers + dev-shell Gradle.

- **Hilt**: removed. The lone DI module was overkill, and Hilt's
  generated `UnsafeCasts.unsafeCast` triggers a deprecation Note on
  AGP 8.7 we can't silence without going to Hilt 2.59 (which requires
  AGP 9.0). Plain construction is fine.

- **Compose deprecations**: `DockedSearchBar` got a new `inputField`
  parameter overload — use that, the old single-call form is
  deprecated. `android.preference.PreferenceManager` is deprecated;
  use `ctx.getSharedPreferences(name, MODE_PRIVATE)` directly.

- **osmdroid first-mount**: `Configuration.getInstance().userAgentValue
  = BuildConfig.APPLICATION_ID` MUST run before the first `MapView`
  inflate or the tile server 403s. Done in `App.onCreate`, alongside
  tile-cache setup (see below).

- **Map tile cache**: 50 MiB, app-private (`cacheDir/osmdroid/tiles`),
  trimmed to 45 MiB when the ceiling is hit. Configured in
  `App.onCreate` so the values are in place before any `MapView`
  inflates; `MapScreen` deliberately does NOT call
  `Configuration.load(...)` again — that would race the bootstrap.
  Tiles persist across launches so re-opening the map on a recently
  visited area is offline-fast.

- **BroadcastReceiver `goAsync()`**: only ~10 s of grace. Anything
  longer goes in a Foreground Service with `connectedDevice` type.

## When you need to extend the protocol

1. Check `../ligpsport/docs/PROTOCOL.md` first.
2. Find the equivalent Python in `../ligpsport/ligpsport/`. Each Kotlin
   file in `ble/` was ported from a Python counterpart — keep that
   correspondence so test vectors transfer.
3. The 33 `.proto` schemas live at `app/src/main/proto/`, vendored
   from `../ligpsport/reference/`. `protobuf-kotlin-lite` generates
   bindings, but the codebase prefers **hand-encoded** protobufs for
   the request side (simple, no nested-message ergonomics) and the
   generated bindings only where response parsing actually needs them.

## When you need to add a new broadcast action

1. Add `ACTION_X` constant to `cli/AdbCliReceiver.kt`.
2. Add `<action>` to the `<intent-filter>` in `AndroidManifest.xml`.
3. Add `OP_X` to `service/UploadForegroundService.kt` and a
   `doX(...)` handler that calls into `UploadPipeline`.
4. Add `dispatch(ctx, reqId, OP_X, intent)` in the receiver's
   `when (action)` block.
5. Update `scripts/e2e-test.sh` with `broadcast_and_wait X <timeout>`.
6. Document extras + RESULT keys in the table above.

## What's deliberately out of scope

- Live ride/fitness data download (Python has it; not needed for v1).
- Wi-Fi upload path (only BLE).
- Firmware upgrades.
- Offline BRouter segment files (online API only).
- OsmAnd outgoing-share / FileProvider export of CNX.
- A LoopbackTransport-based version of the adb e2e — the Espresso
  suite already covers that.
